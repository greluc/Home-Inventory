/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.Page;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Fills in {@code meta.took} on every page, on the way out (08 §8.2).
 *
 * <p>A block knows its rows and its cursor; it does not know how long the request took, and it
 * should not — the number covers routing, deserialisation, authorisation and the response itself,
 * none of which a use case can see. So {@link Page} leaves it empty and this sets it at the edge,
 * which is the only place that knows.
 *
 * <p>Two halves, because a servlet filter can measure and only an advice can rewrite the body. The
 * filter records when the request started; the advice subtracts. A response that somehow arrives
 * without the attribute keeps {@code took} absent rather than reporting a wrong number.
 */
@RestControllerAdvice
public class PageTiming implements ResponseBodyAdvice<Page<?>> {

  /** Where the start is kept for the length of one request. */
  private static final String STARTED_AT = PageTiming.class.getName() + ".startedAt";

  /**
   * Whether this response is a page.
   *
   * @param returnType what the handler declared
   * @param converterType which converter is about to write it
   * @return true when the body is a {@link Page}
   */
  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return Page.class.isAssignableFrom(returnType.getParameterType());
  }

  /**
   * Stamps the page with how long the request took.
   *
   * @param body the page the handler produced
   * @param returnType what the handler declared
   * @param contentType the media type being written
   * @param converterType which converter is writing it
   * @param request the request, carrying the start
   * @param response the response
   * @return the same page, with {@code meta.took} filled in
   */
  @Override
  public Page<?> beforeBodyWrite(
      Page<?> body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {

    if (body == null || !(request instanceof ServletServerHttpRequest servlet)) {
      return body;
    }
    Object startedAt = servlet.getServletRequest().getAttribute(STARTED_AT);
    if (!(startedAt instanceof Long nanos)) {
      // No measurement rather than a wrong one. A page that reached here without
      // passing the filter is a wiring fault, not a request that took zero.
      return body;
    }
    return body.tookMillis((System.nanoTime() - nanos) / 1_000_000);
  }

  /**
   * Records when each request started.
   *
   * <p>Registered as a bean rather than nested inside the advice's lifecycle, because a filter runs
   * long before an advice exists for the request and the two only meet through the attribute.
   */
  public static class Clock extends OncePerRequestFilter {

    /**
     * Stamps the request with the moment it began and lets it through.
     *
     * @param request the request
     * @param response the response
     * @param chain the rest of the chain
     * @throws ServletException when a later filter does
     * @throws IOException when a later filter does
     */
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      // nanoTime, not currentTimeMillis: this is a duration, and a wall clock that
      // steps backwards over an NTP correction would report a negative one.
      request.setAttribute(STARTED_AT, System.nanoTime());
      chain.doFilter(request, response);
    }
  }
}
