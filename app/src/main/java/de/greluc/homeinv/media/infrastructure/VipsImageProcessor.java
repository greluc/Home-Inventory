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

  private final String vipsheader;
  private final String vipsthumbnail;

  /**
   * Creates the processor.
   *
   * @param vipsheader the {@code vipsheader} executable, on the path in the container image
   * @param vipsthumbnail the {@code vipsthumbnail} executable
   */
  public VipsImageProcessor(
      @Value("${homeinv.media.vipsheader-binary:vipsheader}") String vipsheader,
      @Value("${homeinv.media.vipsthumbnail-binary:vipsthumbnail}") String vipsthumbnail) {
    this.vipsheader = vipsheader;
    this.vipsthumbnail = vipsthumbnail;
  }

  @Override
  public Dimensions probe(Path source) {
    String width = run(List.of(vipsheader, "-f", "width", source.toString()));
    String height = run(List.of(vipsheader, "-f", "height", source.toString()));
    return new Dimensions(number(width, source), number(height, source));
  }

  @Override
  public Dimensions derive(Path source, Path target, int maxEdge, OutputFormat format) {
    String extension =
        switch (format) {
          case AVIF -> ".avif";
          case WEBP -> ".webp";
        };
    String options =
        switch (format) {
          case AVIF -> "[Q=60,strip=true]";
          case WEBP -> "[Q=80,strip=true]";
        };

    Path name = target.getFileName();
    if (name == null || !name.toString().endsWith(extension)) {
      throw new ImageProcessingException(
          "The target " + target + " does not end in " + extension
              + ", which is the extension libvips would take the format from",
          null);
    }

    run(
        List.of(
            vipsthumbnail,
            source.toString(),
            "--size",
            maxEdge + "x" + maxEdge + ">",
            "-o",
            target.toAbsolutePath() + options));

    return probe(target);
  }

  /**
   * Picks the number out of what {@code vipsheader} printed.
   *
   * <p>{@link #run} merges stderr into stdout on purpose, so that a failure carries its own
   * explanation — and libvips writes warnings there on perfectly successful reads. A real AVIF
   * written by this very class produces
   * {@code (vipsheader:1135): VIPS-WARNING **: heifload: ignoring nclx profile} above the number,
   * and parsing the whole output as an integer then fails on a file that is completely fine. It did,
   * for every image upload, until 2026-09-12.
   *
   * @param output everything the command printed, warnings included
   * @param source the file, for the message
   * @return the single line that is a number
   * @throws ImageProcessingException when no line is one, which is what a non-image looks like
   */
  private static int number(String output, Path source) {
    for (String line : output.split("\\R")) {
      String candidate = line.strip();
      if (!candidate.isEmpty() && candidate.chars().allMatch(Character::isDigit)) {
        return Integer.parseInt(candidate);
      }
    }
    throw new ImageProcessingException("Could not read the dimensions of " + source, null);
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
              .redirectErrorStream(true)
              .start();

      process.getOutputStream().close();

      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new ImageProcessingException(
            "Image conversion exceeded " + TIMEOUT_SECONDS + "s and was abandoned", null);
      }
      if (process.exitValue() != 0) {
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
        process.destroyForcibly();
      }
    }
  }
}
