package ru.alfa.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

final class VerificationServer implements AutoCloseable {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9:_.-]{8,128}");
  private static final int MAX_BODY = 1024 * 1024;
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore active = new Semaphore(128);
  private final VerificationService service;
  private final byte[] token;

  VerificationServer(int port, String token, Database db, Clock clock) throws IOException {
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("INTERNAL_API_TOKEN is required");
    this.token = token.getBytes(StandardCharsets.UTF_8);
    service = new VerificationService(db, clock);
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String requestId = UUID.randomUUID().toString();
    boolean acquired = false;
    try {
      String path = exchange.getRequestURI().getRawPath();
      boolean health = path.equals("/health/live") || path.equals("/health/ready");
      if (!health) authenticate(exchange);
      String trace = header(exchange, "X-Request-Id");
      if (trace != null) {
        if (!UUID_PATTERN.matcher(trace).matches())
          throw ApiException.invalid("", "X-Request-Id must be a canonical UUID");
        requestId = trace;
      }
      acquired = active.tryAcquire();
      if (!acquired) throw new ApiException(429, "RATE_LIMITED", "Too many concurrent requests");
      String method = exchange.getRequestMethod();
      if (method.equals("GET") && health) {
        if (path.endsWith("/ready")) service.ready();
        respond(exchange, 200, Json.object().put("status", "ok"), requestId, false);
      } else if (method.equals("POST") && path.equals("/v2/verifications")) {
        String key = header(exchange, "Idempotency-Key");
        if (key == null || !KEY_PATTERN.matcher(key).matches())
          throw ApiException.invalid("", "Idempotency-Key must match [A-Za-z0-9:_.-]{8,128}");
        String contentType = header(exchange, "Content-Type");
        if (contentType == null
            || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
          throw ApiException.invalid("", "Content-Type must be application/json");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw ApiException.invalid("", "Request body exceeds 1 MiB");
        JsonNode json;
        try {
          json = Json.MAPPER.readTree(bytes);
        } catch (JsonProcessingException e) {
          throw new ApiException(
              400, "MALFORMED_JSON", "Request body must contain one valid JSON value");
        }
        if (json == null) throw new ApiException(400, "MALFORMED_JSON", "Request body is empty");
        var out = service.create(json, key);
        respond(exchange, out.status(), out.body(), requestId, false);
      } else if (method.equals("GET")
          && path.startsWith("/v2/verifications/")
          && !path.substring(18).contains("/")) {
        String id = path.substring("/v2/verifications/".length());
        if (!UUID_PATTERN.matcher(id).matches())
          throw ApiException.invalid("", "verificationId must be a canonical UUID");
        respond(exchange, 200, service.get(UUID.fromString(id)), requestId, false);
      } else throw new ApiException(404, "NOT_FOUND", "Endpoint was not found");
    } catch (ApiException e) {
      problem(exchange, e, requestId);
    } catch (Exception e) {
      System.err.println("requestId=" + requestId + " code=INTERNAL_ERROR");
      problem(
          exchange,
          new ApiException(500, "INTERNAL_ERROR", "An internal error occurred"),
          requestId);
    } finally {
      if (acquired) active.release();
      exchange.close();
    }
  }

  private void authenticate(HttpExchange exchange) {
    var values = exchange.getRequestHeaders().get("Authorization");
    String value = values != null && values.size() == 1 ? values.getFirst() : null;
    if (value == null
        || value.length() < 7
        || !value.regionMatches(true, 0, "Bearer ", 0, 7)
        || !MessageDigest.isEqual(token, value.substring(7).getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(401, "UNAUTHENTICATED", "Valid internal bearer token is required");
  }

  private static String header(HttpExchange exchange, String name) {
    var values = exchange.getRequestHeaders().get(name);
    if (values == null) return null;
    if (values.size() != 1) throw ApiException.invalid("", name + " must occur once");
    return values.getFirst();
  }

  private static void problem(HttpExchange exchange, ApiException error, String requestId)
      throws IOException {
    if (error.status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
    if (error.status == 503 || error.status == 429 || error.code.equals("IDEMPOTENCY_IN_PROGRESS"))
      exchange.getResponseHeaders().set("Retry-After", "1");
    var body =
        Json.object()
            .put("type", "urn:alfa:problem:" + error.code.toLowerCase(Locale.ROOT))
            .put("title", error.code)
            .put("status", error.status)
            .put("detail", error.getMessage())
            .put("code", error.code)
            .put("requestId", requestId);
    body.set("errors", error.errors);
    respond(exchange, error.status, body, requestId, true);
  }

  private static void respond(
      HttpExchange exchange, int status, JsonNode json, String requestId, boolean problem)
      throws IOException {
    byte[] body = Json.write(json).getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .set(
            "Content-Type",
            (problem ? "application/problem+json" : "application/json") + "; charset=utf-8");
    exchange.getResponseHeaders().set("X-Request-Id", requestId);
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  public void close() {
    server.stop(0);
    executor.close();
  }
}
