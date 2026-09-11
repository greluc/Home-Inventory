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
