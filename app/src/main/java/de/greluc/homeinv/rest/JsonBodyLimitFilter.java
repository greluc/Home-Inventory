/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounds a non-upload request body at 1 MB (REQ-SEC-065).
 *
 * <p>Uploads are bounded elsewhere and much higher: the upload pipeline's own ceiling and, outside
 * it, {@code spring.servlet.multipart.max-file-size}. This is the limit for everything else, and
 * without it a JSON body was bounded by nothing at all — a single request could hold a gigabyte of
 * heap while Jackson parsed it.
 *
 * <h2>Two checks, because one of them can be lied about</h2>
 *
 * <p>{@code Content-Length} is refused before the body is read, which is the case that matters: no
 * bytes are accepted and the answer costs nothing. A chunked request declares no length, so the
 * stream itself counts as well and stops at the same number. Trusting the header alone would make
 * the limit a request header away from not applying.
 *
 * <p>The limit is {@code 1_000_000} bytes and not {@code 1_048_576}: the requirement says one
 * megabyte, and the decimal reading is the one that satisfies it under either.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JsonBodyLimitFilter extends OncePerRequestFilter {

  /** One megabyte, decimal. */
  static final long LIMIT = 1_000_000L;

  private final ObjectMapper json;

  /**
   * Creates the filter.
   *
   * @param json the configured mapper, so the refusal is serialised exactly like every other problem
   *     document
   */
  public JsonBodyLimitFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    String contentType = request.getContentType();
    // Multipart carries the uploads, which have their own, much higher limit and
    // their own answer for exceeding it. Everything else — JSON, form encoding,
    // whatever a client invents — is bounded here.
    return contentType != null
        && contentType.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {

    if (request.getContentLengthLong() > LIMIT) {
      refuse(request, response);
      return;
    }

    chain.doFilter(new BoundedRequest(request), response);
  }

  /**
   * Answers an oversized request without reading it.
   *
   * @param request the request, for the {@code instance} member
   * @param response the response to write
   * @throws IOException when the response cannot be written
   */
  private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    ProblemDetail problem =
        Problems.of(
            ProblemType.PAYLOAD_TOO_LARGE,
            "The request body exceeds " + LIMIT + " bytes.",
            request);
    problem.setProperty("limit", LIMIT);

    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), problem);
  }

  /** A request whose body stops at {@link #LIMIT}, however long it claims to be. */
  private static final class BoundedRequest extends HttpServletRequestWrapper {

    private BoundedRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new BoundedStream(super.getInputStream());
    }
  }

  /**
   * Counts what is read and refuses to hand over more than the limit.
   *
   * <p>Throws rather than reporting end-of-stream. A truncated body would reach Jackson as a syntax
   * error, and a {@code 400 malformed-request} for a request that was merely too big is an answer a
   * client cannot act on.
   */
  private static final class BoundedStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private long read;

    private BoundedStream(ServletInputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value != -1) {
        count(1);
      }
      return value;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
      int count = delegate.read(buffer, offset, length);
      if (count > 0) {
        count(count);
      }
      return count;
    }

    private void count(int bytes) {
      read += bytes;
      if (read > LIMIT) {
        throw new PayloadTooLargeException("The request body exceeds " + LIMIT + " bytes.");
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
