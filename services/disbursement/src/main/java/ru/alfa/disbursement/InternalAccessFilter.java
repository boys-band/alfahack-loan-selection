package ru.alfa.disbursement;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Integer.MIN_VALUE)
class InternalAccessFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(InternalAccessFilter.class);
  static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private final byte[] expected;

  InternalAccessFilter(Environment environment) {
    String token = environment.getRequiredProperty("INTERNAL_API_TOKEN");
    if (token.isBlank()) throw new IllegalArgumentException("INTERNAL_API_TOKEN must not be blank");
    expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-Id");
    String requestId =
        supplied != null && UUID_PATTERN.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    try {
      boolean health =
          request.getMethod().equals("GET")
              && (request.getRequestURI().equals("/health/live")
                  || request.getRequestURI().equals("/health/ready"));
      String auth = request.getHeader("Authorization");
      if (!health
          && (auth == null
              || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))))
        throw new ApiException(401, "UNAUTHORIZED", "A valid internal bearer token is required.");
      if (supplied != null && !UUID_PATTERN.matcher(supplied).matches())
        throw new ApiException(
            400, "INVALID_REQUEST_ID", "X-Request-Id must be a canonical lowercase UUID.");
      chain.doFilter(request, response);
    } catch (ApiException e) {
      request.setAttribute("problemCode", e.code);
      response.setStatus(e.status);
      response.setContentType("application/problem+json");
      JsonSupport.JSON.writeValue(response.getOutputStream(), Problems.body(e, requestId));
    } finally {
      LOG.info(
          "requestId={} method={} path={} httpStatus={} code={}",
          requestId,
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          request.getAttribute("problemCode") == null ? "OK" : request.getAttribute("problemCode"));
    }
  }
}
