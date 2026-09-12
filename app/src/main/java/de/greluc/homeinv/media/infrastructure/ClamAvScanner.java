/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.VirusScanner;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link VirusScanner} on ClamAV, over the {@code INSTREAM} command (ADR-0024).
 *
 * <h2>Why streaming and not a shared file</h2>
 *
 * <p>{@code SCAN /path} would need clamd to see the same filesystem as the application, which means
 * a shared volume between two containers that otherwise share nothing. {@code INSTREAM} sends the
 * bytes over the socket, so the scanner container needs no volume at all and the application needs
 * no writable path the scanner can also reach.
 *
 * <h2>Fail-closed</h2>
 *
 * <p>Every failure to reach a verdict raises {@link ScannerUnavailableException}, and the upload is
 * refused. ADR-0024 makes the scan mandatory in every profile, and the tempting alternative during
 * an outage — accept it and scan later — means accepting an unscanned file into a store from which
 * it will be served.
 *
 * <p>"Unavailable" and "infected" are deliberately different outcomes. The first is a statement
 * about the system and may be retried; the second is a statement about the file and must not be.
 */
@Component
@Slf4j
public class ClamAvScanner implements VirusScanner {

  /**
   * The largest chunk sent in one frame.
   *
   * <p>Well below clamd's {@code StreamMaxLength} default so a frame is never refused for its size,
   * and large enough that a multi-megabyte photo is a handful of writes rather than thousands.
   */
  private static final int CHUNK_BYTES = 32 * 1024;

  /** {@code "Thu "} — the three-letter weekday clamd prints, and the space after it. */
  private static final int FIRST_SPACE_AFTER_WEEKDAY = 3;

  private final String host;
  private final int port;
  private final int timeoutMillis;

  /**
   * Creates the scanner.
   *
   * @param host the clamd host, inside the deployment network
   * @param port the clamd TCP port
   * @param timeoutMillis how long to wait for a connection and for a verdict
   */
  public ClamAvScanner(
      @Value("${homeinv.media.clamav.host:clamav}") String host,
      @Value("${homeinv.media.clamav.port:3310}") int port,
      @Value("${homeinv.media.clamav.timeout-millis:30000}") int timeoutMillis) {
    this.host = host;
    this.port = port;
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public Verdict scan(InputStream content) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      // Without this a scanner that accepts the connection and then stops
      // answering holds the request thread forever.
      socket.setSoTimeout(timeoutMillis);

      try (OutputStream rawOut = socket.getOutputStream();
          DataOutputStream out = new DataOutputStream(rawOut);
          InputStream in = socket.getInputStream()) {

        // The leading 'z' selects the NUL-terminated command form. The newline
        // form exists too and answers differently; mixing them is the classic way
        // to end up parsing a reply that never comes.
        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

        byte[] buffer = new byte[CHUNK_BYTES];
        int read;
        while ((read = content.read(buffer)) != -1) {
          if (read == 0) {
            continue;
          }
          // Each chunk is a four-byte big-endian length followed by the bytes.
          // A zero length means end of stream, so an empty chunk written here
          // would terminate the transfer early and have the file judged on
          // whatever had arrived so far.
          out.writeInt(read);
          out.write(buffer, 0, read);
        }
        out.writeInt(0);
        out.flush();

        String reply = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        return interpret(reply);
      }

    } catch (IOException unreachable) {
      throw new ScannerUnavailableException(
          "The malware scanner at " + host + ":" + port + " could not be reached", unreachable);
    }
  }

  /**
   * When the scanner's signature database was built (REQ-SEC-093).
   *
   * <p>clamd's {@code VERSION} answers with three fields separated by slashes — the engine version,
   * the database version and the date the database was built:
   *
   * <pre>ClamAV 1.4.3/27512/Thu Sep 11 09:23:41 2026</pre>
   *
   * <p>The date is the one that matters. The database <em>number</em> rises with every update and
   * says nothing on its own: an operator cannot tell 27512 from 27100 without knowing what today's
   * is, which is the question being asked.
   *
   * <p>Empty rather than an exception when the scanner cannot be reached or answers something else.
   * The caller is a metric, and a metric that throws takes an actuator endpoint down with it; the
   * condition "the scanner is unreachable" has its own, louder answer during an upload.
   *
   * @return the date the signatures were built, or empty
   */
  public Optional<Instant> signatureDate() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);

      try (OutputStream out = socket.getOutputStream();
          InputStream in = socket.getInputStream()) {
        out.write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return parseSignatureDate(
            new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
      }
    } catch (IOException unreachable) {
      log.debug("Could not read the scanner's version", unreachable);
      return Optional.empty();
    }
  }

  /**
   * Reads the build date out of a {@code VERSION} reply.
   *
   * <p>Visible so that a test can exercise the parsing without a scanner — which is the half that
   * can go wrong, and can go wrong silently. The class is infrastructure and is published to nobody:
   * {@code media.api} is the block's surface, and ArchUnit refuses an import of this package from
   * another block whatever the modifier says.
   *
   * @param reply what clamd said, trimmed
   * @return the date, or empty when the reply does not carry one
   */
  public static Optional<Instant> parseSignatureDate(String reply) {
    String[] fields = reply.split("/");
    if (fields.length < 3 || fields[2].trim().length() <= FIRST_SPACE_AFTER_WEEKDAY) {
      return Optional.empty();
    }
    try {
      // Three things about this pattern, each of which fails silently without it:
      //
      //   * the locale is pinned to ROOT, because clamd prints the weekday and
      //     the month in the C locale whatever the host's is, and a German
      //     default would refuse "Thu";
      //   * `ppd` accepts the space-padded day clamd prints before the tenth,
      //     which neither `d` nor `dd` does;
      //   * the reply carries no zone, so it is read as UTC. The container runs
      //     UTC, and being an hour or two out cannot change the answer to
      //     "older than 48 hours".
      //   * the weekday is dropped rather than parsed. It is redundant — the date
      //     is complete without it — and parsing it makes the formatter compare
      //     the two and refuse a correct date whose weekday is wrong, which is a
      //     way to fail for no benefit.
      String withoutWeekday = fields[2].trim().substring(FIRST_SPACE_AFTER_WEEKDAY).trim();
      return Optional.of(
          LocalDateTime.parse(
                  withoutWeekday,
                  DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss yyyy", Locale.ROOT))
              .toInstant(ZoneOffset.UTC));
    } catch (DateTimeParseException | IndexOutOfBoundsException unparseable) {
      log.debug("The scanner's version reply carried no readable date: {}", reply);
      return Optional.empty();
    }
  }

  /**
   * Turns clamd's reply into a verdict.
   *
   * @param reply what clamd said, trimmed
   * @return the verdict
   * @throws ScannerUnavailableException when the reply is an error or is not understood. An
   *     unrecognised reply is treated as no verdict rather than as a clean one: a scanner whose
   *     protocol has changed must stop uploads, not wave them through
   */
  private Verdict interpret(String reply) {
    String cleaned = reply.replace("\0", "");
    if (cleaned.endsWith("OK")) {
      return new Verdict(true, null);
    }
    if (cleaned.endsWith("FOUND")) {
      // "stream: Eicar-Test-Signature FOUND"
      int start = cleaned.indexOf(':');
      String signature =
          start >= 0
              ? cleaned.substring(start + 1, cleaned.length() - "FOUND".length()).trim()
              : cleaned;
      log.warn("The malware scanner rejected an upload: {}", signature);
      return new Verdict(false, signature);
    }
    throw new ScannerUnavailableException(
        "The malware scanner answered something this code does not understand: " + cleaned, null);
  }
}
