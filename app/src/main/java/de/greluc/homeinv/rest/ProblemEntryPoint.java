/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers a request that carried no usable session (REQ-API-003).
 *
 * <p>This replaced {@code HttpStatusEntryPoint}, which sent a {@code 401} with an empty body. The
 * reasoning behind that was right and incomplete: an API must not answer a {@code fetch()} with a
 * redirect to an HTML login page. It does not follow that it should answer with nothing — a client
 * then has one response in the whole surface it cannot parse the way it parses every other failure,
 * on the most common failure there is.
 *
 * <p>The document is identical to the one {@link ApiExceptionHandler} produces for an expired
 * session reaching a handler, because from the client's side those are the same condition.
 *
 * <p>No {@code WWW-Authenticate} header. RFC 9110 asks for one on a {@code 401}, and the honest
 * answer here would be a scheme this API does not implement: the only way in is the JSON login
 * endpoint, and a browser shown {@code Basic} would open a native credentials dialogue that leads
 * nowhere.
 */
@Component
@RequiredArgsConstructor
public class ProblemEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper json;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {

    ProblemDetail problem =
        Problems.of(
            ProblemType.UNAUTHENTICATED,
            "This endpoint needs a session. Sign in at /api/v1/auth/login.",
            request);

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), problem);
  }
}
