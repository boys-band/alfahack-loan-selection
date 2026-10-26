package ru.alfa.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class CatalogServer implements AutoCloseable {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern PRODUCT_ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ProductRepository repository;
  private final AdminCatalogRepository adminRepository;
  private final byte[] customerToken;
  private final byte[] internalToken;
  private final byte[] adminToken;
  private final UUID adminId;

  public CatalogServer(Config config, ProductRepository repository) throws IOException {
    this.repository = repository;
    adminRepository = repository.admin();
    customerToken = config.customerToken().getBytes(StandardCharsets.UTF_8);
    internalToken = config.internalToken().getBytes(StandardCharsets.UTF_8);
    adminToken = config.adminToken().getBytes(StandardCharsets.UTF_8);
    adminId = config.adminId();
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 128);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String requestId = UUID.randomUUID().toString();
    int status = 500;
    try {
      List<String> suppliedIds = exchange.getRequestHeaders().get("X-Request-Id");
      if (suppliedIds != null
          && suppliedIds.size() == 1
          && UUID_PATTERN.matcher(suppliedIds.getFirst()).matches())
        requestId = suppliedIds.getFirst();
      exchange.getResponseHeaders().set("X-Request-Id", requestId);
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/v2/admin/"))
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
      boolean health = path.equals("/health/live") || path.equals("/health/ready");
      if (!health) authenticate(exchange, path.startsWith("/v2/admin/"));
      String suppliedId = singleHeader(exchange, "X-Request-Id", "/headers/X-Request-Id");
      if (suppliedId != null && !UUID_PATTERN.matcher(suppliedId).matches())
        throw invalid("/headers/X-Request-Id", "Expected canonical lowercase UUID");
      if (path.startsWith("/v2/admin/")) {
        status = handleAdmin(exchange, path);
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().set("Allow", "GET");
        throw new ApiError(405, "METHOD_NOT_ALLOWED", "Only GET is supported", List.of());
      }
      if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
          || !"0".equals(exchange.getRequestHeaders().getFirst("Content-Length"))
              && exchange.getRequestHeaders().containsKey("Content-Length")) {
        throw invalid("", "This operation does not accept a request body");
      }
      Object result;
      if (health) {
        query(exchange, false);
        if (path.equals("/health/ready") && !repository.ready()) throw unavailable();
        result = Map.of("status", "ok");
      } else if (path.equals("/v2/products")) {
        query(exchange, false);
        result = Map.of("items", repository.listCurrent());
      } else if (path.startsWith("/v2/products/")
          && !path.substring("/v2/products/".length()).contains("/")) {
        String id = path.substring("/v2/products/".length());
        if (!PRODUCT_ID.matcher(id).matches()) throw invalid("/productId", "Invalid product ID");
        String version = query(exchange, true);
        result =
            repository
                .find(id, version)
                .orElseThrow(
                    () -> new ApiError(404, "NOT_FOUND", "Product version not found", List.of()));
      } else {
        throw new ApiError(404, "NOT_FOUND", "Resource not found", List.of());
      }
      status = 200;
      send(exchange, status, "application/json", result);
    } catch (ApiError error) {
      status = error.status;
      problem(exchange, error, requestId);
    } catch (AdminCatalogRepository.Failure error) {
      status = error.status;
      problem(
          exchange,
          new ApiError(error.status, error.code, error.getMessage(), List.of()),
          requestId);
    } catch (SQLException error) {
      status = 503;
      problem(exchange, unavailable(), requestId);
    } catch (Exception error) {
      status = 500;
      problem(
          exchange,
          new ApiError(500, "INTERNAL_ERROR", "Unexpected server error", List.of()),
          requestId);
    } finally {
      // Never log request headers, URL queries, credentials, or database exception text.
      System.out.println("requestId=" + requestId + " status=" + status);
      exchange.close();
    }
  }

  private void authenticate(HttpExchange exchange, boolean adminPath) {
    String header = singleHeader(exchange, "Authorization", "/headers/Authorization");
    if (header != null && header.length() > 7 && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      byte[] token = header.substring(7).getBytes(StandardCharsets.UTF_8);
      boolean customer = MessageDigest.isEqual(token, customerToken);
      boolean internal = MessageDigest.isEqual(token, internalToken);
      boolean admin = MessageDigest.isEqual(token, adminToken);
      if (adminPath && admin || !adminPath && (customer | internal)) return;
      if (adminPath && (customer | internal) || !adminPath && admin)
        throw new ApiError(403, "FORBIDDEN", "This role cannot access the resource", List.of());
    }
    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
    throw new ApiError(401, "UNAUTHENTICATED", "A valid bearer token is required", List.of());
  }

  private int handleAdmin(HttpExchange exchange, String path) throws IOException, SQLException {
    String method = exchange.getRequestMethod();
    if (path.equals("/v2/admin/products") && method.equals("GET")) {
      noBody(exchange);
      query(exchange, false);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      send(exchange, 200, "application/json", Map.of("items", adminRepository.list()));
      return 200;
    }
    boolean creation = path.equals("/v2/admin/products") && method.equals("POST");
    String prefix = "/v2/admin/products/";
    if (!creation && !path.startsWith(prefix))
      throw new ApiError(404, "NOT_FOUND", "Resource not found", List.of());
    String suffix = creation ? "" : path.substring(prefix.length());
    boolean publication = suffix.endsWith("/versions");
    boolean archive = suffix.endsWith("/archive");
    String id =
        publication
            ? suffix.substring(0, suffix.length() - "/versions".length())
            : archive ? suffix.substring(0, suffix.length() - "/archive".length()) : suffix;
    if (!creation && (id.contains("/") || !PRODUCT_ID.matcher(id).matches()))
      throw invalid("/productId", "Invalid product ID");
    query(exchange, false);
    if (!creation && !publication && !archive && method.equals("GET")) {
      noBody(exchange);
      AdminProduct product =
          adminRepository
              .find(id)
              .orElseThrow(() -> new ApiError(404, "NOT_FOUND", "Product not found", List.of()));
      exchange.getResponseHeaders().set("ETag", AdminCodec.etag(product));
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      send(exchange, 200, "application/json", product);
      return 200;
    }
    if (!method.equals("POST") || !creation && !publication && !archive) {
      exchange
          .getResponseHeaders()
          .set("Allow", creation || publication || archive ? "POST" : "GET");
      throw new ApiError(405, "METHOD_NOT_ALLOWED", "Method is not supported", List.of());
    }
    String key = singleHeader(exchange, "Idempotency-Key", "/headers/Idempotency-Key");
    if (key == null)
      throw new ApiError(428, "PRECONDITION_REQUIRED", "Idempotency-Key is required", List.of());
    if (!key.matches("[A-Za-z0-9:_.-]{8,128}"))
      throw invalid("/headers/Idempotency-Key", "Invalid idempotency key");
    String ifMatch = singleHeader(exchange, "If-Match", "/headers/If-Match");
    if (!creation && ifMatch == null)
      throw new ApiError(428, "PRECONDITION_REQUIRED", "If-Match is required", List.of());
    if (creation && ifMatch != null)
      throw invalid("/headers/If-Match", "Creation does not accept If-Match");
    if (!creation && !ifMatch.matches("\"[0-9a-f]{64}\""))
      throw invalid("/headers/If-Match", "Invalid ETag");
    String contentType = singleHeader(exchange, "Content-Type", "/headers/Content-Type");
    if (contentType == null
        || !contentType.matches(
            "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\\\"utf-8\\\"))?"))
      throw invalid("/headers/Content-Type", "Expected application/json");
    byte[] raw = exchange.getRequestBody().readNBytes(1_048_577);
    if (raw.length > 1_048_576) throw invalid("", "Request body is too large");
    JsonNode body;
    try {
      body = JSON.readTree(raw);
    } catch (IOException error) {
      throw new ApiError(400, "MALFORMED_JSON", "Malformed JSON", List.of());
    }
    if (body == null || body.isMissingNode())
      throw new ApiError(400, "MALFORMED_JSON", "Malformed JSON", List.of());
    AdminCatalogRepository.Publication result;
    if (creation) {
      result = adminRepository.create(adminId, path, key, body, CatalogServer::createdProduct);
    } else if (archive) {
      result = adminRepository.archive(adminId, id, path, key, ifMatch, body);
    } else {
      result =
          adminRepository.publish(
              id, adminId, path, key, ifMatch, body, request -> proposed(id, request));
    }
    exchange.getResponseHeaders().set("ETag", result.etag());
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    sendRaw(exchange, result.status(), result.body());
    return result.status();
  }

  private static void noBody(HttpExchange exchange) {
    if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
        || exchange.getRequestHeaders().containsKey("Content-Length")
            && !"0".equals(exchange.getRequestHeaders().getFirst("Content-Length")))
      throw invalid("", "This operation does not accept a request body");
  }

  private static Product proposed(String id, JsonNode body) {
    Set<String> fields =
        Set.of(
            "name",
            "description",
            "purposeCodes",
            "minAmountRub",
            "maxAmountRub",
            "minTermMonths",
            "maxTermMonths",
            "annualRateBps",
            "minBusinessAgeMonths",
            "maxDebtServiceRatioBps",
            "reason");
    if (body == null || !body.isObject() || body.size() != fields.size())
      throw invalid("", "Expected all product fields and reason, without extra fields");
    for (String field : fields) if (!body.has(field)) throw invalid("/" + field, "Missing field");
    String name = requiredText(body, "name", 1, Integer.MAX_VALUE);
    String description = requiredText(body, "description", 1, Integer.MAX_VALUE);
    requiredText(body, "reason", 10, 2000);
    JsonNode purposes = body.get("purposeCodes");
    if (!purposes.isArray() || purposes.isEmpty())
      throw invalid("/purposeCodes", "Choose at least one purpose");
    List<String> codes = new ArrayList<>();
    for (JsonNode purpose : purposes) {
      if (!purpose.isTextual()
          || !Set.of("WORKING_CAPITAL", "EQUIPMENT", "REFINANCING").contains(purpose.textValue())
          || codes.contains(purpose.textValue()))
        throw invalid("/purposeCodes", "Invalid or repeated purpose");
      codes.add(purpose.textValue());
    }
    long minAmount = number(body, "minAmountRub", 1, 1_000_000_000);
    long maxAmount = number(body, "maxAmountRub", minAmount, 1_000_000_000);
    int minTerm = (int) number(body, "minTermMonths", 1, 120);
    int maxTerm = (int) number(body, "maxTermMonths", minTerm, 120);
    int rate = (int) number(body, "annualRateBps", 0, 10_000);
    int age = (int) number(body, "minBusinessAgeMonths", 0, Integer.MAX_VALUE);
    int debt = (int) number(body, "maxDebtServiceRatioBps", 1, 10_000);
    return new Product(
        id, "1", name, description, codes, minAmount, maxAmount, minTerm, maxTerm, rate, age, debt);
  }

  private static Product createdProduct(JsonNode body) {
    if (body == null || !body.isObject()) throw invalid("", "Expected product fields");
    String id = requiredText(body, "id", 2, 64);
    if (!PRODUCT_ID.matcher(id).matches()) throw invalid("/id", "Invalid product ID");
    ObjectNode terms = ((ObjectNode) body).deepCopy();
    terms.remove("id");
    return proposed(id, terms);
  }

  static void archiveRequest(JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 1 || !body.has("reason"))
      throw invalid("", "Expected reason without extra fields");
    requiredText(body, "reason", 10, 2000);
  }

  private static String requiredText(JsonNode body, String field, int min, int max) {
    JsonNode value = body.get(field);
    String text = value != null && value.isTextual() ? value.textValue() : null;
    if (text == null) throw invalid("/" + field, "Invalid text");
    int length = text.codePointCount(0, text.length());
    if (length < min || length > max || text.isBlank()) throw invalid("/" + field, "Invalid text");
    return text;
  }

  private static long number(JsonNode body, String field, long min, long max) {
    JsonNode value = body.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < min
        || value.longValue() > max) throw invalid("/" + field, "Number is outside product limits");
    return value.longValue();
  }

  private static void sendRaw(HttpExchange exchange, int status, String raw) throws IOException {
    byte[] body = raw.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static String singleHeader(HttpExchange exchange, String name, String path) {
    List<String> values = exchange.getRequestHeaders().get(name);
    if (values == null) return null;
    if (values.size() != 1) throw invalid(path, "Header must occur once");
    return values.getFirst();
  }

  private static String query(HttpExchange exchange, boolean allowVersion) {
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null || raw.isEmpty()) return null;
    String version = null;
    for (String part : raw.split("&", -1)) {
      String[] pair = part.split("=", 2);
      String key = decode(pair[0]);
      if (!allowVersion || !key.equals("version"))
        throw invalid("/query", "Unknown query parameter");
      if (version != null) throw invalid("/version", "Version must occur once");
      version = pair.length == 2 ? decode(pair[1]) : "";
      if (!version.matches("[1-9][0-9]*"))
        throw invalid("/version", "Expected a positive decimal version without leading zeros");
    }
    return version;
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw invalid("/query", "Invalid URL encoding");
    }
  }

  private static ApiError invalid(String path, String message) {
    return new ApiError(
        422,
        "VALIDATION_ERROR",
        "Request validation failed",
        List.of(Map.of("path", path, "message", message)));
  }

  private static ApiError unavailable() {
    return new ApiError(
        503,
        "DEPENDENCY_UNAVAILABLE",
        "Catalog database, schema or seed is unavailable",
        List.of());
  }

  private static void problem(HttpExchange exchange, ApiError error, String requestId)
      throws IOException {
    exchange.getResponseHeaders().set("X-Request-Id", requestId);
    if (error.status == 503) exchange.getResponseHeaders().set("Retry-After", "3");
    send(
        exchange,
        error.status,
        "application/problem+json",
        Map.of(
            "type",
            "about:blank",
            "title",
            error.code,
            "status",
            error.status,
            "detail",
            error.getMessage(),
            "code",
            error.code,
            "requestId",
            requestId,
            "errors",
            error.errors));
  }

  private static void send(HttpExchange exchange, int status, String contentType, Object value)
      throws IOException {
    byte[] body = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
    if (exchange.getRequestMethod().equals("HEAD")) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }

  @Override
  public void close() {
    server.stop(5);
    executor.close();
  }

  private static final class ApiError extends RuntimeException {
    final int status;
    final String code;
    final List<Map<String, String>> errors;

    ApiError(int status, String code, String detail, List<Map<String, String>> errors) {
      super(detail);
      this.status = status;
      this.code = code;
      this.errors = errors;
    }
  }
}
