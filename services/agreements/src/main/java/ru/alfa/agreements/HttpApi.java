package ru.alfa.agreements;

import static ru.alfa.agreements.Json.*;
import static ru.alfa.agreements.Problem.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

final class HttpApi implements AutoCloseable {
  static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private final HttpServer server;
  private final ExecutorService executor;
  private final Store store;
  private final byte[] expectedToken;

  HttpApi(Store store, String token, String host, int port) throws IOException {
    if (token == null || token.isBlank())
      throw new IllegalArgumentException("INTERNAL_API_TOKEN is required");
    Contract.auditVocabulary(Contract.ROOT);
    this.store = store;
    expectedToken = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress(host, port), 128);
    executor =
        new ThreadPoolExecutor(
            8,
            32,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            new ThreadPoolExecutor.CallerRunsPolicy());
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  public void close() {
    server.stop(1);
    executor.shutdownNow();
  }

  private static String header(HttpExchange e, String key) {
    List<String> v = e.getRequestHeaders().get(key);
    return v == null ? null : v.size() == 1 ? v.get(0) : "";
  }

  private void handle(HttpExchange exchange) throws IOException {
    String requestId = header(exchange, "X-Request-Id");
    boolean invalid = requestId != null && !requestId.matches(UUID_PATTERN);
    if (requestId == null || invalid) requestId = id();
    Store.Reply reply;
    String code = null;
    try {
      require(!invalid, 400, "VALIDATION_ERROR", "X-Request-Id должен быть UUID lowercase");
      reply = dispatch(exchange);
    } catch (Problem e) {
      code = e.code;
      reply = problem(e.status, e.code, e.getMessage(), requestId);
    } catch (SQLException e) {
      String state = e.getSQLState();
      if ("55P03".equals(state) || "40P01".equals(state)) {
        code = "IDEMPOTENCY_IN_PROGRESS";
        reply = problem(409, code, "Операция выполняется; повторите запрос", requestId);
      } else if (state != null
          && (state.startsWith("08")
              || state.equals("57014")
              || state.startsWith("53")
              || state.startsWith("57"))) {
        code = "DEPENDENCY_UNAVAILABLE";
        reply = problem(503, code, "Собственная БД временно недоступна", requestId);
      } else if ("23505".equals(state)) {
        code = "RESOURCE_CONFLICT";
        reply = problem(409, code, "Бизнес-ресурс уже существует", requestId);
      } else {
        code = "INTERNAL_ERROR";
        reply = problem(500, code, "Не удалось завершить операцию", requestId);
      }
    } catch (Exception e) {
      code = "INTERNAL_ERROR";
      reply = problem(500, code, "Не удалось завершить операцию", requestId);
    }
    byte[] bytes = reply.body().toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("X-Request-Id", requestId);
    exchange
        .getResponseHeaders()
        .set(
            "Content-Type",
            reply.status() >= 400 ? "application/problem+json" : "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    if ("IDEMPOTENCY_IN_PROGRESS".equals(code))
      exchange.getResponseHeaders().set("Retry-After", "1");
    try {
      exchange.sendResponseHeaders(reply.status(), bytes.length);
      exchange.getResponseBody().write(bytes);
    } finally {
      exchange.close();
    }
    System.out.println(obj("requestId", requestId, "status", reply.status(), "code", code));
  }

  private static Store.Reply problem(int status, String code, String detail, String requestId) {
    return new Store.Reply(
        status,
        obj(
            "type",
            "about:blank",
            "title",
            code,
            "status",
            status,
            "detail",
            detail,
            "code",
            code,
            "requestId",
            requestId,
            "errors",
            arr()));
  }

  private Store.Reply dispatch(HttpExchange e) throws SQLException, IOException {
    String method = e.getRequestMethod(), path = e.getRequestURI().getRawPath();
    if (method.equals("GET") && (path.equals("/health/live") || path.equals("/health/ready"))) {
      if (path.endsWith("ready"))
        try {
          store.ready();
        } catch (SQLException ex) {
          throw new Problem(503, "DEPENDENCY_UNAVAILABLE", "БД или ожидаемая схема не готовы");
        }
      return new Store.Reply(200, obj("status", "ok"));
    }
    String auth = header(e, "Authorization");
    require(
        auth != null && MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expectedToken),
        401,
        "UNAUTHENTICATED",
        "Требуется внутренний Bearer токен");
    Map<String, String> query = new HashMap<>();
    if (e.getRequestURI().getRawQuery() != null)
      for (String pair : e.getRequestURI().getRawQuery().split("&", -1)) {
        String[] parts = pair.split("=", 2);
        String key, value;
        try {
          key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
          value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
        } catch (IllegalArgumentException ex) {
          throw new Problem(422, "VALIDATION_ERROR", "Некорректные query параметры");
        }
        valid(!query.containsKey(key), "Повтор query параметра");
        query.put(key, value);
      }
    if (method.equals("GET")) {
      valid(query.isEmpty(), "Неизвестный query параметр");
      Matcher m = Pattern.compile("/v2/agreements/(" + UUID_PATTERN + ")").matcher(path);
      if (m.matches()) return new Store.Reply(200, store.read(m.group(1)));
    }
    if (method.equals("POST")) {
      String schema = path.equals("/v2/agreements") ? "AgreementRequest" : null;
      Matcher m =
          Pattern.compile("/v2/agreements/(" + UUID_PATTERN + ")/(sign|close)").matcher(path);
      if (m.matches())
        schema = m.group(2).equals("sign") ? "InternalSignRequest" : "CloseAgreementRequest";
      if (schema != null) {
        valid(query.isEmpty(), "Неизвестный query параметр");
        String key = header(e, "Idempotency-Key");
        valid(
            key != null && key.matches("[A-Za-z0-9:_.-]{8,128}"),
            "Требуется корректный Idempotency-Key");
        String content = header(e, "Content-Type");
        require(
            content != null
                && content.split(";", 2)[0].strip().equalsIgnoreCase("application/json"),
            400,
            "MALFORMED_JSON",
            "Ожидается application/json");
        byte[] bytes = e.getRequestBody().readNBytes(1048577);
        require(bytes.length <= 1048576, 400, "MALFORMED_JSON", "Слишком большое тело запроса");
        JsonNode body;
        try {
          body = MAPPER.readTree(bytes);
          if (body == null) throw new IllegalArgumentException();
        } catch (Exception ex) {
          throw new Problem(400, "MALFORMED_JSON", "Некорректный JSON");
        }
        Contract.validate(schema, body);
        return store.mutate(path, key, sorted(body));
      }
    }
    throw new Problem(404, "NOT_FOUND", "Маршрут не найден");
  }
}
