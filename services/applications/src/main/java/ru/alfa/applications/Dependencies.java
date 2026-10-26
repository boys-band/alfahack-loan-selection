package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;

public class Dependencies {
  public static class Failure extends RuntimeException {
    public final String code;
    public final boolean retryable;
    public final long retryAfter;
    public final int status;

    public Failure(String code, boolean retryable) {
      this(code, retryable, 0, 503);
    }

    public Failure(String code, boolean retryable, long retryAfter, int status) {
      super(code);
      this.code = code;
      this.retryable = retryable;
      this.retryAfter = retryAfter;
      this.status = status;
    }
  }

  private final Settings settings;
  private final HttpClient http;

  public Dependencies(Settings settings) {
    this(
        settings,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public Dependencies(Settings settings, HttpClient http) {
    this.settings = settings;
    this.http = http;
  }

  public JsonNode call(
      String service,
      String method,
      String path,
      String requestId,
      JsonNode body,
      String key,
      String schema) {
    var builder =
        HttpRequest.newBuilder(URI.create(settings.services().get(service) + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + settings.internalToken())
            .header("X-Request-Id", requestId);
    if (key != null) builder.header("Idempotency-Key", key);
    if (method.equals("GET")) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(canonical(body)));
    HttpResponse<String> response;
    try {
      response =
          http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
              .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
              .join();
    } catch (CompletionException ex) {
      throw new Failure("DEPENDENCY_UNAVAILABLE", true);
    }
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      String code = "DEPENDENCY_PROTOCOL_ERROR";
      try {
        String candidate = s(parse(response.body()), "code");
        if (candidate != null && candidate.matches("[A-Z][A-Z0-9_]*")) code = candidate;
      } catch (IllegalArgumentException ignored) {
      }
      long retryAfter = 0;
      String raw = response.headers().firstValue("Retry-After").orElse("0");
      try {
        retryAfter = Math.max(0, Long.parseLong(raw));
      } catch (NumberFormatException ignored) {
        try {
          retryAfter =
              Math.max(
                  0,
                  Duration.between(
                          Instant.now(),
                          ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                              .toInstant())
                      .toSeconds());
        } catch (java.time.format.DateTimeParseException ignoredDate) {
        }
      }
      boolean retry =
          response.statusCode() == 408
              || response.statusCode() == 429
              || response.statusCode() >= 500
              || (response.statusCode() == 409 && code.equals("IDEMPOTENCY_IN_PROGRESS"));
      throw new Failure(code, retry, retryAfter, response.statusCode());
    }
    try {
      JsonNode result = parse(response.body());
      Contract.validate(schema, result);
      return result;
    } catch (IllegalArgumentException | Problem ex) {
      throw new Failure("DEPENDENCY_PROTOCOL_ERROR", false);
    }
  }

  public JsonNode get(String service, String path, String requestId, String schema) {
    return call(service, "GET", path, requestId, null, null, schema);
  }
}
