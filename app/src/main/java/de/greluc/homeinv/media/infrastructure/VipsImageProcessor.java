/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.ImageProcessingException;
import de.greluc.homeinv.media.api.ImageProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ImageProcessor} on libvips, driven as a subprocess.
 *
 * <h2>Why a subprocess and not bindings</h2>
 *
 * <p>This code decodes files that strangers upload. A malformed image that crashes the decoder takes
 * down whatever process it runs in: with JNA bindings that is the JVM and every request it was
 * serving; as a subprocess it is a child that exits non-zero and one upload that fails. The cost is
 * a process per derivative, three per image — real, and the smaller of the two risks.
 *
 * <p>The other reason is supply chain. libvips is installed in the container image, where it is
 * visible in the SBOM and updated by rebuilding; a binding library would add a second artifact that
 * must be kept in step with the native one it wraps.
 *
 * <h2>How the arguments are built</h2>
 *
 * <p>Never as a shell string. {@link ProcessBuilder} takes a list, so a filename is an argument and
 * not something a shell parses — which matters because one of the paths is derived from content the
 * uploader controls. There is no shell in the chain at all.
 */
@Component
@Slf4j
public class VipsImageProcessor implements ImageProcessor {

  /**
   * How long a single conversion may take.
   *
   * <p>A bound rather than a courtesy: a crafted file can make a decoder work for a very long time,
   * and an unbounded child process is a way to exhaust the worker pool with one upload.
   */
  private static final long TIMEOUT_SECONDS = 60;

  private final String vips;
  private final String vipsthumbnail;

  /**
   * Creates the processor.
   *
   * @param vips the {@code vips} executable, absolute in the container image
   * @param vipsthumbnail the {@code vipsthumbnail} executable
   */
  public VipsImageProcessor(
      @Value("${homeinv.media.vips-binary:vips}") String vips,
      @Value("${homeinv.media.vipsthumbnail-binary:vipsthumbnail}") String vipsthumbnail) {
    this.vips = vips;
    this.vipsthumbnail = vipsthumbnail;
  }

  @Override
  public Dimensions probe(Path source) {
    // Header only: no pixels are decoded, which is the entire point. The limit
    // that protects against a decompression bomb has to be checked before the
    // bitmap it would allocate exists (REQ-SEC-040).
    String width = run(List.of(vips, "header", "-f", "width", source.toString()));
    String height = run(List.of(vips, "header", "-f", "height", source.toString()));
    try {
      return new Dimensions(Integer.parseInt(width.trim()), Integer.parseInt(height.trim()));
    } catch (NumberFormatException notAnImage) {
      throw new ImageProcessingException("Could not read the dimensions of " + source, notAnImage);
    }
  }

  @Override
  public Dimensions derive(Path source, Path target, int maxEdge, OutputFormat format) {
    // --size=NxN> - the trailing '>' means "shrink only". Without it a 40x40
    // avatar becomes a 1024x1024 blur that is larger than the original.
    //
    // strip=true removes every metadata block, EXIF and its GPS tags included
    // (REQ-MED-006). It is the default in recent libvips and stated anyway,
    // because a privacy guarantee that depends on a default is a guarantee that
    // changes when the default does.
    String suffix =
        switch (format) {
          case AVIF -> ".avif[Q=60,strip=true]";
          case WEBP -> ".webp[Q=80,strip=true]";
        };

    run(
        List.of(
            vipsthumbnail,
            source.toString(),
            "--size",
            maxEdge + "x" + maxEdge + ">",
            "-o",
            target.toAbsolutePath() + suffix));

    return probe(target);
  }

  /**
   * Runs a command and returns its standard output.
   *
   * @param command the executable and its arguments, already split
   * @return what the command printed
   * @throws ImageProcessingException when the command fails, times out, or cannot be started
   */
  private String run(List<String> command) {
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              // Merged, so a libvips warning on stderr appears in the same place
              // as its output and a failure carries its own explanation.
              .redirectErrorStream(true)
              .start();

      // The child gets no input. Closing it immediately means a tool that decides
      // to read stdin fails fast instead of hanging until the timeout.
      process.getOutputStream().close();

      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new ImageProcessingException(
            "Image conversion exceeded " + TIMEOUT_SECONDS + "s and was abandoned", null);
      }
      if (process.exitValue() != 0) {
        // The output is logged and not returned: libvips echoes the file path,
        // and the path carries content the uploader chose.
        log.warn("libvips exited {} for {}: {}", process.exitValue(), command.get(0), output.strip());
        throw new ImageProcessingException("Image conversion failed", null);
      }
      return output;

    } catch (IOException cannotStart) {
      throw new ImageProcessingException(
          "libvips is not available. It is part of the application image; a missing binary means "
              + "the image was built without it.",
          cannotStart);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ImageProcessingException("Image conversion was interrupted", interrupted);
    } finally {
      if (process != null && process.isAlive()) {
        // A timed-out child must not outlive the request that started it.
        process.destroyForcibly();
      }
    }
  }
}
