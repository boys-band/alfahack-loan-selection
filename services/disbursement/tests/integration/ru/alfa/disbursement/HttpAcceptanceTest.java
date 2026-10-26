package ru.alfa.disbursement;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpAcceptanceTest {
  static final String COMPANY = "00000000-0000-4000-8000-000000000101";
  static final String ACCOUNT = "00000000-0000-4000-8000-000000001101";
  static final String INVALID = "00000000-0000-4000-8000-000000001199";
  static final String FOREIGN = "00000000-0000-4000-8000-000000001102";
  static final String TOKEN = "integration-internal-only";
  final ObjectMapper json = new ObjectMapper();
  final Contracts responseContracts = new Contracts();
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final AtomicReference<ObjectNode> agreement = new AtomicReference<>();
  final AtomicReference<String> rawAgreement = new AtomicReference<>();
  final AtomicInteger dependencyStatus = new AtomicInteger(200), calls = new AtomicInteger();
  final AtomicLong delay = new AtomicLong();
  final AtomicReference<String> trace = new AtomicReference<>(), auth = new AtomicReference<>();
  HttpServer stub;
  ConfigurableApplicationContext app;
  String base;

  @BeforeAll
  void start() throws Exception {
    assertNotNull(System.getenv("TEST_DATABASE_URL"), "Use make test-integration");
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stub.createContext(
        "/v2/agreements/",
        exchange -> {
          calls.incrementAndGet();
          trace.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          try {
            Thread.sleep(delay.get());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body =
              rawAgreement.get() == null
                  ? json.writeValueAsBytes(agreement.get())
                  : rawAgreement.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(dependencyStatus.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    stub.start();
    startApplication();
  }

  void startApplication() {
    app =
        new SpringApplicationBuilder(DisbursementApplication.class)
            .run(
                "--server.port=0",
                "--DATABASE_URL=" + System.getenv("TEST_DATABASE_URL"),
                "--INTERNAL_API_TOKEN=" + TOKEN,
                "--AGREEMENTS_URL=http://127.0.0.1:" + stub.getAddress().getPort(),
                "--AGREEMENTS_TIMEOUT_MS=500",
                "--spring.main.banner-mode=off");
    base =
        "http://127.0.0.1:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort();
  }

  @BeforeEach
  void fixture() throws Exception {
    ObjectNode a =
        (ObjectNode) json.readTree(getClass().getResourceAsStream("/signed-agreement.json"));
    a.put("id", UUID.randomUUID().toString()).put("applicationId", UUID.randomUUID().toString());
    ((ObjectNode) a.get("offer")).put("applicationId", a.path("applicationId").asText());
    agreement.set(a);
    rawAgreement.set(null);
    dependencyStatus.set(200);
    delay.set(0);
    calls.set(0);
  }

  @AfterAll
  void stop() {
    if (app != null) app.close();
    if (stub != null) stub.stop(0);
  }

  ObjectNode checkRequest(String account, long version) {
    ObjectNode a = agreement.get(), r = json.createObjectNode();
    r.put("applicationId", a.path("applicationId").asText())
        .put("agreementId", a.path("id").asText())
        .put("companyId", COMPANY)
        .put("dataVersion", 1)
        .put("payoutVersion", version)
        .put("settlementAccountId", account);
    return r;
  }

  ObjectNode disbursementRequest() throws Exception {
    ObjectNode r = checkRequest(ACCOUNT, 1);
    JsonNode check = body(post("/v2/payout-checks", r, key()));
    r.put("payoutCheckId", check.path("id").asText())
        .put("operationId", UUID.randomUUID().toString());
    return r;
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  HttpResponse<String> post(String path, JsonNode body, String key) throws Exception {
    return send("POST", path, body.toString(), key, TOKEN, null);
  }

  HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, null, null, TOKEN, null);
  }

  HttpResponse<String> send(
      String method, String path, String body, String key, String token, String requestId)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (key != null) b.header("Idempotency-Key", key);
    if (requestId != null) b.header("X-Request-Id", requestId);
    if (body != null) b.header("Content-Type", "application/json");
    var response =
        http.send(
            b.method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String schema =
        response.statusCode() >= 400
            ? "Problem"
            : path.startsWith("/health/")
                ? "Health"
                : path.startsWith("/v2/company-accounts/")
                    ? "AccountList"
                    : path.startsWith("/v2/payout-checks") ? "PayoutCheck" : "Disbursement";
    assertTrue(
        responseContracts.valid(schema, json.readTree(response.body())),
        "Invalid " + schema + ": " + response.body());
    return response;
  }

  JsonNode body(HttpResponse<String> r) throws Exception {
    return json.readTree(r.body());
  }

  void status(int expected, HttpResponse<String> r) {
    assertEquals(expected, r.statusCode(), r.body());
  }

  void problem(int expected, HttpResponse<String> r) throws Exception {
    status(expected, r);
    assertTrue(
        r.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
    assertEquals(
        r.headers().firstValue("X-Request-Id").orElseThrow(), body(r).path("requestId").asText());
    assertTrue(body(r).path("errors").isArray());
  }

  @Test
  void healthAndCompanyAccounts() throws Exception {
    status(200, send("GET", "/health/live", null, null, null, null));
    status(200, send("GET", "/health/ready", null, null, null, null));
    dependencyStatus.set(503);
    status(200, send("GET", "/health/ready", null, null, null, null));
    JsonNode items = body(get("/v2/company-accounts/" + COMPANY)).get("items");
    assertEquals(2, items.size());
    for (JsonNode item : items) assertEquals(COMPANY, item.path("companyId").asText());
    assertEquals(0, body(get("/v2/company-accounts/" + UUID.randomUUID())).get("items").size());
    try (Connection c = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"))) {
      assertThrows(
          SQLException.class, () -> c.createStatement().execute("SELECT * FROM schema_migrations"));
      assertThrows(
          SQLException.class, () -> c.createStatement().execute("CREATE TABLE forbidden (id int)"));
    }
  }

  @Test
  void badAccountCorrectionPreservesOldResult() throws Exception {
    var bad = post("/v2/payout-checks", checkRequest(INVALID, 1), key());
    status(201, bad);
    assertEquals("NEEDS_CORRECTION", body(bad).path("outcome").asText());
    assertEquals("ACCOUNT_INVALID", body(bad).path("reasonCodes").get(0).asText());
    var corrected = post("/v2/payout-checks", checkRequest(ACCOUNT, 2), key());
    status(201, corrected);
    assertEquals("READY", body(corrected).path("outcome").asText());
    assertEquals(body(bad), body(get("/v2/payout-checks/" + body(bad).path("id").asText())));
    assertNotEquals(body(bad).path("id"), body(corrected).path("id"));
    problem(409, post("/v2/payout-checks", checkRequest(ACCOUNT, 1), key()));
  }

  @ParameterizedTest
  @ValueSource(strings = {FOREIGN, "99999999-0000-4000-8000-000000000001"})
  void foreignOrUnknownAccountNeedsCorrection(String account) throws Exception {
    var r = post("/v2/payout-checks", checkRequest(account, 1), key());
    status(201, r);
    assertEquals("ACCOUNT_NOT_OWNED", body(r).path("reasonCodes").get(0).asText());
  }

  @Test
  void strictValidationAndAuthentication() throws Exception {
    String path = "/v2/payout-checks";
    problem(401, send("POST", path, "{}", key(), null, null));
    problem(
        401, send("GET", "/v2/company-accounts/" + COMPANY, null, null, "customer-token", null));
    problem(400, send("POST", path, "{", key(), TOKEN, null));
    problem(400, send("POST", path, checkRequest(ACCOUNT, 1).toString(), null, TOKEN, null));
    problem(400, send("POST", path, checkRequest(ACCOUNT, 1).toString(), "bad", TOKEN, null));
    problem(400, send("GET", "/health/live", null, null, null, "INVALID"));
    problem(422, post(path, checkRequest(ACCOUNT, 1).put("dataVersion", "1"), key()));
    problem(422, post(path, checkRequest(ACCOUNT, 1).putNull("companyId"), key()));
    problem(422, post(path, checkRequest(ACCOUNT, 1).put("unexpected", true), key()));
    problem(
        400, send("POST", path, checkRequest(ACCOUNT, 1).toString() + " {}", key(), TOKEN, null));
    problem(
        400,
        send(
            "POST",
            path,
            checkRequest(ACCOUNT, 1)
                .toString()
                .replace("\"dataVersion\":1", "\"dataVersion\":1,\"dataVersion\":2"),
            key(),
            TOKEN,
            null));
    problem(422, get("/v2/payout-checks/not-a-uuid"));
    problem(404, get("/v2/disbursements/by-operation/" + UUID.randomUUID()));
    problem(404, get("/unknown"));
  }

  @Test
  void atomicExecutionReplayLookupAndRestart() throws Exception {
    ObjectNode r = disbursementRequest();
    String k = key(), requestId = key();
    var first = send("POST", "/v2/disbursements", r.toString(), k, TOKEN, requestId);
    status(201, first);
    assertEquals("Bearer " + TOKEN, auth.get());
    assertEquals(requestId, trace.get());
    JsonNode result = body(first);
    assertEquals(1000000, result.path("amountRub").asLong());
    assertEquals("RUB", result.path("currency").asText());
    assertEquals("EXECUTED", result.path("status").asText());
    assertEquals(result, body(get("/v2/disbursements/" + result.path("id").asText())));
    assertEquals(
        result, body(get("/v2/disbursements/by-operation/" + r.path("operationId").asText())));
    dependencyStatus.set(503);
    var replay = post("/v2/disbursements", r, k);
    status(201, replay);
    assertEquals(result, body(replay));
    var alias = post("/v2/disbursements", r, key());
    status(200, alias);
    assertEquals(result, body(alias));
    assertEquals(1, calls.get());
    problem(401, send("POST", "/v2/disbursements", r.toString(), k, "wrong-token", null));
    app.close();
    startApplication();
    assertEquals(
        result, body(get("/v2/disbursements/by-operation/" + r.path("operationId").asText())));
    assertEquals(result, body(post("/v2/disbursements", r, k)));
    assertEquals(1, count("disbursements", "application_id", r.path("applicationId").asText()));
    assertEquals(1, count("credit_entries", "disbursement_id", result.path("id").asText()));
  }

  @Test
  void idempotencyConflictAndBusinessConflict() throws Exception {
    ObjectNode r = disbursementRequest();
    String k = key();
    status(201, post("/v2/disbursements", r, k));
    ObjectNode changed = r.deepCopy().put("operationId", UUID.randomUUID().toString());
    var conflict = post("/v2/disbursements", changed, k);
    problem(409, conflict);
    assertEquals("IDEMPOTENCY_CONFLICT", body(conflict).path("code").asText());
    var invalidChanged = post("/v2/disbursements", r.deepCopy().put("amountRub", 10), k);
    problem(409, invalidChanged);
    assertEquals("IDEMPOTENCY_CONFLICT", body(invalidChanged).path("code").asText());
    problem(409, post("/v2/disbursements", changed, key()));
    assertEquals(1, count("disbursements", "application_id", r.path("applicationId").asText()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "READY",
        "CLOSED",
        "MISSING",
        "UNAVAILABLE",
        "MALFORMED",
        "TIMEOUT",
        "FRACTIONAL",
        "TRAILING",
        "DUPLICATE"
      })
  void dependencyFailuresCannotCreditAndSameKeyCanRecover(String state) throws Exception {
    ObjectNode r = disbursementRequest();
    String k = key();
    ObjectNode signed = agreement.get().deepCopy();
    int expected = 409;
    switch (state) {
      case "READY", "CLOSED" -> {
        agreement.get().put("status", state).putNull("signature");
        if (state.equals("CLOSED")) agreement.get().put("closureReason", "CANCELLED");
      }
      case "MISSING" -> {
        dependencyStatus.set(404);
        expected = 409;
      }
      case "UNAVAILABLE" -> {
        dependencyStatus.set(503);
        expected = 503;
      }
      case "MALFORMED" -> {
        agreement.get().remove("offer");
        expected = 503;
      }
      case "TIMEOUT" -> {
        delay.set(1000);
        expected = 503;
      }
      case "FRACTIONAL" -> {
        rawAgreement.set(
            signed.toString().replace("\"dataVersion\":1", "\"dataVersion\":1.0000000000000001"));
        expected = 503;
      }
      case "TRAILING" -> {
        rawAgreement.set(signed + " {}");
        expected = 503;
      }
      case "DUPLICATE" -> {
        rawAgreement.set(
            signed
                .toString()
                .replace("\"status\":\"SIGNED\"", "\"status\":\"READY\",\"status\":\"SIGNED\""));
        expected = 503;
      }
    }
    problem(expected, post("/v2/disbursements", r, k));
    assertEquals(0, count("disbursements", "application_id", r.path("applicationId").asText()));
    agreement.set(signed);
    rawAgreement.set(null);
    dependencyStatus.set(200);
    delay.set(0);
    status(201, post("/v2/disbursements", r, k));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "applicationId",
        "companyId",
        "dataVersion",
        "offerVersion",
        "signatureHash",
        "documentContent"
      })
  void mismatchedAgreementNeverCredits(String field) throws Exception {
    ObjectNode r = disbursementRequest(), a = agreement.get();
    switch (field) {
      case "applicationId", "companyId" -> a.put(field, UUID.randomUUID().toString());
      case "dataVersion" -> a.put("dataVersion", 2);
      case "offerVersion" -> ((ObjectNode) a.get("offer")).put("dataVersion", 2);
      case "signatureHash" ->
          ((ObjectNode) a.get("signature")).put("documentSha256", "0".repeat(64));
      case "documentContent" ->
          ((ObjectNode) a.get("document")).put("content", "tampered document");
    }
    problem(409, post("/v2/disbursements", r, key()));
    assertEquals(0, count("disbursements", "application_id", r.path("applicationId").asText()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "applicationId",
        "companyId",
        "dataVersion",
        "agreementId",
        "payoutVersion",
        "settlementAccountId",
        "payoutCheckId"
      })
  void exactReadyCheckIsRequired(String field) throws Exception {
    ObjectNode r = disbursementRequest();
    if (field.endsWith("Version")) r.put(field, 2);
    else r.put(field, UUID.randomUUID().toString());
    problem(409, post("/v2/disbursements", r, key()));
    assertEquals(0, count("disbursements", "operation_id", r.path("operationId").asText()));
  }

  @Test
  void amountCannotBeSuppliedByCaller() throws Exception {
    ObjectNode r = disbursementRequest().put("amountRub", 999999999);
    problem(422, post("/v2/disbursements", r, key()));
  }

  @Test
  void preciseFractionalVersionsAreRejectedBeforePersistence() throws Exception {
    ObjectNode r = checkRequest(ACCOUNT, 1);
    String raw = r.toString().replace("\"dataVersion\":1", "\"dataVersion\":1.0000000000000001");
    problem(422, send("POST", "/v2/payout-checks", raw, key(), TOKEN, null));
    assertEquals(0, count("payout_checks", "application_id", r.path("applicationId").asText()));
  }

  @Test
  void integralExponentVersionsReplayWithoutConflicts() throws Exception {
    ObjectNode r = checkRequest(ACCOUNT, 1).put("dataVersion", 1000);
    String k = key();
    var first = post("/v2/payout-checks", r, k);
    status(201, first);
    String exponent = r.toString().replace("\"dataVersion\":1000", "\"dataVersion\":1e3");
    assertNotEquals(r.toString(), exponent);
    var replay = send("POST", "/v2/payout-checks", exponent, k, TOKEN, null);
    status(201, replay);
    assertEquals(body(first), body(replay));
    var business = send("POST", "/v2/payout-checks", exponent, key(), TOKEN, null);
    status(200, business);
    assertEquals(body(first), body(business));
  }

  @Test
  void concurrentKeysAndOperationIdsLeaveOneCredit() throws Exception {
    ObjectNode r = disbursementRequest();
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch gate = new CountDownLatch(1);
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        ObjectNode candidate = r.deepCopy();
        if (i >= 6) candidate.put("operationId", UUID.randomUUID().toString());
        String k = i < 3 ? "same-concurrent-key-" + r.path("operationId").asText() : key();
        futures.add(
            workers.submit(
                () -> {
                  gate.await();
                  return post("/v2/disbursements", candidate, k);
                }));
      }
      gate.countDown();
      int success = 0;
      for (var f : futures) {
        var response = f.get();
        assertTrue(Set.of(200, 201, 409).contains(response.statusCode()), response.body());
        if (response.statusCode() < 300) success++;
      }
      assertTrue(success >= 1);
    }
    assertEquals(1, count("disbursements", "application_id", r.path("applicationId").asText()));
    assertEquals(
        1,
        sqlCount(
            "SELECT count(*) FROM credit_entries c JOIN disbursements d ON d.id=c.disbursement_id WHERE d.application_id='"
                + r.path("applicationId").asText()
                + "'"));
  }

  @Test
  void unfinishedSameKeyReturnsRetryAfter() throws Exception {
    ObjectNode r = disbursementRequest();
    String k = key();
    delay.set(300);
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = workers.submit(() -> post("/v2/disbursements", r, k));
      long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (calls.get() == 0 && System.nanoTime() < until) Thread.sleep(5);
      var second = post("/v2/disbursements", r, k);
      problem(409, second);
      assertEquals("IDEMPOTENCY_IN_PROGRESS", body(second).path("code").asText());
      assertEquals("1", second.headers().firstValue("Retry-After").orElseThrow());
      status(201, first.get());
    }
  }

  @Test
  void failureBetweenInsertsRollsBackAndRetryWorks() throws Exception {
    ObjectNode r = disbursementRequest();
    String k = key();
    try (Connection c = DriverManager.getConnection(System.getenv("TEST_ADMIN_DATABASE_URL"))) {
      c.createStatement()
          .execute(
              "CREATE FUNCTION fail_test_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected credit failure'; END $$");
      c.createStatement()
          .execute(
              "CREATE TRIGGER fail_test_credit BEFORE INSERT ON credit_entries FOR EACH ROW EXECUTE FUNCTION fail_test_credit()");
      try {
        problem(503, post("/v2/disbursements", r, k));
        assertEquals(0, count("disbursements", "application_id", r.path("applicationId").asText()));
      } finally {
        c.createStatement().execute("DROP TRIGGER fail_test_credit ON credit_entries");
        c.createStatement().execute("DROP FUNCTION fail_test_credit()");
      }
    }
    status(201, post("/v2/disbursements", r, k));
  }

  @Test
  void readinessDetectsMissingSeedAndSchema() throws Exception {
    try (Connection c = DriverManager.getConnection(System.getenv("TEST_ADMIN_DATABASE_URL"))) {
      c.createStatement()
          .execute("UPDATE account_fixtures SET status='INVALID' WHERE id='" + ACCOUNT + "'");
      try {
        problem(503, get("/health/ready"));
        status(200, get("/health/live"));
      } finally {
        c.createStatement()
            .execute("UPDATE account_fixtures SET status='ACTIVE' WHERE id='" + ACCOUNT + "'");
      }
      c.createStatement()
          .execute("ALTER TABLE credit_entries RENAME COLUMN amount_rub TO missing_amount");
      try {
        problem(503, get("/health/ready"));
      } finally {
        c.createStatement()
            .execute("ALTER TABLE credit_entries RENAME COLUMN missing_amount TO amount_rub");
      }
    }
    status(200, get("/health/ready"));
  }

  @Test
  void unreadyDatabaseDoesNotStopLiveness() throws Exception {
    String previousBase = base;
    try (ConfigurableApplicationContext unavailable =
        new SpringApplicationBuilder(DisbursementApplication.class)
            .run(
                "--server.port=0",
                "--DATABASE_URL=jdbc:postgresql://127.0.0.1:1/unavailable",
                "--INTERNAL_API_TOKEN=" + TOKEN,
                "--AGREEMENTS_URL=http://127.0.0.1:" + stub.getAddress().getPort(),
                "--spring.main.banner-mode=off")) {
      base =
          "http://127.0.0.1:"
              + ((ServletWebServerApplicationContext) unavailable).getWebServer().getPort();
      status(200, get("/health/live"));
      problem(503, get("/health/ready"));
    } finally {
      base = previousBase;
    }
  }

  int count(String table, String column, String id) throws Exception {
    return sqlCount(
        "SELECT count(*) FROM " + table + " WHERE " + column + "='" + UUID.fromString(id) + "'");
  }

  int sqlCount(String sql) throws Exception {
    try (Connection c = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"));
        ResultSet rs = c.createStatement().executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
