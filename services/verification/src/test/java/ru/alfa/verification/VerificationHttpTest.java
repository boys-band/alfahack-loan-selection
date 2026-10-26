package ru.alfa.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class VerificationHttpTest {
  static final String TOKEN = "demo-internal-verification-only";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
  static VerificationServer server;
  static Database db, admin;
  static final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @BeforeAll
  static void start() throws Exception {
    db =
        new Database(
            Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"), "Run tests/integration.py"));
    admin = new Database(System.getenv("TEST_ADMIN_DATABASE_URL"));
    restart();
  }

  static void restart() throws Exception {
    if (server != null) server.close();
    server = new VerificationServer(0, TOKEN, db, CLOCK);
    server.start();
  }

  @AfterAll
  static void stop() {
    if (server != null) server.close();
  }

  @Test
  void createGetReplayBusinessConflictAndRestart() throws Exception {
    ObjectNode r = request(101);
    String key = key();
    var first = post(r, key);
    assertEquals(201, first.statusCode(), first.body());
    JsonNode body = json(first);
    assertEquals("COMPLETE", body.path("outcome").asText());
    assertEquals("2026-10-24T09:00:00Z", body.path("asOf").asText());
    Contracts.validate("VerificationResult", body);
    var same = post(r, key);
    assertEquals(201, same.statusCode());
    assertEquals(body, json(same));
    var other = post(r, key());
    assertEquals(200, other.statusCode());
    assertEquals(body, json(other));
    assertEquals(body, json(get("/v2/verifications/" + body.path("id").asText(), TOKEN)));
    restart();
    assertEquals(body, json(post(r, key)));
    r.put("asOf", "2026-10-24T09:00:01Z");
    assertProblem(post(r, key), 409, "IDEMPOTENCY_CONFLICT");
    assertProblem(post(r, key()), 409, "IDEMPOTENCY_CONFLICT");
  }

  @Test
  void authenticationPrecedesReplayAndErrorsHaveTracing() throws Exception {
    ObjectNode r = request(101);
    String key = key();
    post(r, key);
    var noAuth = send("POST", "/v2/verifications", Json.write(r), key, null, null);
    assertProblem(noAuth, 401, "UNAUTHENTICATED");
    assertProblem(
        get("/v2/verifications/" + UUID.randomUUID(), "customer-token"), 401, "UNAUTHENTICATED");
    String requestId = UUID.randomUUID().toString();
    var traced = send("POST", "/v2/verifications", Json.write(r), key, TOKEN, requestId);
    assertEquals(requestId, traced.headers().firstValue("X-Request-Id").orElseThrow());
    assertProblem(get("/v2/verifications/" + UUID.randomUUID(), TOKEN), 404, "NOT_FOUND");
  }

  @Test
  void strictSchemaAndMalformedJson() throws Exception {
    ObjectNode r = request(101);
    r.put("unknown", 1);
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    ((ObjectNode) r.path("data")).remove("staffAttestation");
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    ((ObjectNode) r.at("/data/financialData")).put("monthlyRevenueRub", 1000000000001L);
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    assertProblem(
        send("POST", "/v2/verifications", "{", key(), TOKEN, null), 400, "MALFORMED_JSON");
    assertProblem(
        send("POST", "/v2/verifications", "{}{}", key(), TOKEN, null), 400, "MALFORMED_JSON");
    assertProblem(
        send("POST", "/v2/verifications", "{\"x\":1,\"x\":2}", key(), TOKEN, null),
        400,
        "MALFORMED_JSON");
    assertProblem(
        send("POST", "/v2/verifications", Json.write(request(101)), null, TOKEN, null),
        422,
        "VALIDATION_ERROR");
    assertProblem(get("/v2/verifications/BAD-UUID", TOKEN), 422, "VALIDATION_ERROR");
  }

  @Test
  void descriptionsDisputesAndNegativeFacts() throws Exception {
    ObjectNode r = request(103);
    JsonNode info = json(post(r, key()));
    assertEquals("NEEDS_INFO", info.path("outcome").asText());
    assertEquals("/additionalInfo/businessDescription", info.at("/requirements/0/path").asText());
    r.put("dataVersion", 2);
    ((ObjectNode) r.at("/data/additionalInfo")).put("businessDescription", "Ремонт оборудования");
    assertEquals("COMPLETE", json(post(r, key())).path("outcome").asText());
    assertEquals("MANUAL_REVIEW", json(post(request(104), key())).path("outcome").asText());
    var overdue = post(request(102), key());
    assertEquals(201, overdue.statusCode());
    assertEquals("COMPLETE", json(overdue).path("outcome").asText());
    assertTrue(json(overdue).at("/facts/hasCurrentOverdue").asBoolean());
  }

  @Test
  void staffCorrectionIsNewVersionAuditedAndRawSourcesSurvive() throws Exception {
    ObjectNode r = request(104);
    JsonNode before = json(post(r, key()));
    r.put("dataVersion", 2);
    ObjectNode a =
        Json.object()
            .put("caseActionId", UUID.randomUUID().toString())
            .put("staffId", UUID.randomUUID().toString())
            .put("reason", "Проверены первичные документы")
            .put("recordedAt", "2026-10-24T09:00:00Z");
    ObjectNode facts = before.path("rawFacts").deepCopy();
    facts.put("creditHistoryScore", 750).put("businessAgeMonths", 0);
    a.set("confirmedFacts", facts);
    ((ObjectNode) r.path("data")).set("staffAttestation", a);
    var response = post(r, key());
    assertEquals(201, response.statusCode(), response.body());
    JsonNode out = json(response);
    assertEquals("COMPLETE", out.path("outcome").asText());
    assertEquals(800, out.at("/rawFacts/creditHistoryScore").asInt());
    assertEquals(750, out.at("/facts/creditHistoryScore").asInt());
    assertEquals(81, out.at("/facts/businessAgeMonths").asInt());
    assertEquals(before, json(get("/v2/verifications/" + before.path("id").asText(), TOKEN)));
    try (var c = admin.connect();
        var st =
            c.prepareStatement(
                "SELECT request_payload,source_payloads FROM verification_results WHERE id=?")) {
      st.setObject(1, UUID.fromString(out.path("id").asText()));
      try (var rs = st.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(
            Json.sorted(a), Json.sorted(Json.parse(rs.getString(1)).at("/data/staffAttestation")));
        assertTrue(Json.parse(rs.getString(2)).at("/REGISTRY/disputedFacts").asBoolean());
      }
    }
    r.put("applicationId", UUID.randomUUID().toString()).put("dataVersion", 1);
    assertProblem(post(r, key()), 422, "SNAPSHOT_MISMATCH");
  }

  @Test
  void sourceFailureRollsBackAndSameKeyCanRecover() throws Exception {
    ObjectNode r = request(101);
    String company = UUID.randomUUID().toString();
    ((ObjectNode) r.at("/data/company")).put("id", company);
    String key = key();
    assertProblem(post(r, key), 503, "DEPENDENCY_UNAVAILABLE");
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM verification_results WHERE application_id='"
                + r.path("applicationId").asText()
                + "'"));
    assertEquals(
        0, count("SELECT count(*) FROM idempotency_records WHERE idempotency_key='" + key + "'"));
    fixture(
        company,
        "REGISTRY",
        "{\"registryStatus\":\"ACTIVE\",\"registryRegisteredAt\":\"2020-01-15\",\"needsDescription\":false,\"disputedFacts\":false}");
    fixture(company, "BKI", "{\"creditHistoryScore\":800,\"hasCurrentOverdue\":false}");
    fixture(company, "FNS", "{\"taxDebtRub\":0}");
    var recovered = post(r, key);
    assertEquals(201, recovered.statusCode(), recovered.body());
    fixture(company, "FNS", "{\"taxDebtRub\":200000}");
    restart();
    assertEquals(json(recovered), json(post(r, key)));
    assertEquals(json(recovered), json(post(r, key())));
    r.put("dataVersion", 2);
    JsonNode updated = json(post(r, key()));
    assertEquals(200000, updated.at("/facts/taxDebtRub").asInt());
    assertEquals("COMPLETE", updated.path("outcome").asText());
  }

  @Test
  void concurrentRequestsHaveOneResourceAndCommittedReplies() throws Exception {
    ObjectNode r = request(101);
    String key = key();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> post(r, key)));
      JsonNode body = null;
      for (var future : futures) {
        var out = future.get(15, TimeUnit.SECONDS);
        assertEquals(201, out.statusCode(), out.body());
        if (body == null) body = json(out);
        else assertEquals(body, json(out));
      }
    }
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM verification_results WHERE application_id='"
                + r.path("applicationId").asText()
                + "'"));
    assertEquals(
        1, count("SELECT count(*) FROM idempotency_records WHERE idempotency_key='" + key + "'"));
  }

  @Test
  void concurrentDifferentKeysRespectBusinessUniqueness() throws Exception {
    ObjectNode r = request(101);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> post(r, key())));
      Set<String> ids = new HashSet<>();
      int created = 0;
      for (var f : futures) {
        var out = f.get(15, TimeUnit.SECONDS);
        assertTrue(Set.of(200, 201).contains(out.statusCode()), out.body());
        if (out.statusCode() == 201) created++;
        ids.add(json(out).path("id").asText());
      }
      assertEquals(1, created);
      assertEquals(1, ids.size());
    }
  }

  @Test
  void readinessDoesNotNeedMigrationAccessAndRuntimeCannotMutateResults() throws Exception {
    assertEquals(200, get("/health/live", null).statusCode());
    var ready = get("/health/ready", null);
    assertEquals(200, ready.statusCode(), ready.body());
    Contracts.validate("Health", json(ready));
    var out = json(post(request(101), key()));
    try (var c = db.connect();
        var s = c.createStatement()) {
      assertThrows(SQLException.class, () -> s.execute("SELECT * FROM schema_migrations"));
      assertThrows(SQLException.class, () -> s.execute("CREATE TABLE public.forbidden(id int)"));
      assertThrows(
          SQLException.class,
          () ->
              s.execute(
                  "UPDATE verification_results SET confirmed=false WHERE id='"
                      + out.path("id").asText()
                      + "'"));
      assertThrows(
          SQLException.class,
          () ->
              s.execute(
                  "DELETE FROM verification_results WHERE id='" + out.path("id").asText() + "'"));
    }
  }

  @Test
  void rejectsOverflowFractionalMoneyAndInvalidSnapshotDates() throws Exception {
    ObjectNode r = request(101);
    r.set("dataVersion", Json.parse("9223372036854775808"));
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    ((ObjectNode) r.at("/data/financialData"))
        .set("monthlyRevenueRub", Json.parse("1000000.0000000000000000001"));
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    ((ObjectNode) r.at("/data/financialData")).set("monthlyRevenueRub", Json.parse("1e10000"));
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    r.put("asOf", "2030-01-01T00:00:00Z");
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    ((ObjectNode) r.at("/data/company")).put("registeredAt", "2030-01-01");
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
    r = request(101);
    r.put("asOf", "2026-10-24T09:00:00+03:00");
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
  }

  @Test
  void malformedSourcesAreNotCreditResultsAndReadinessNeedsSeeds() throws Exception {
    ObjectNode r = request(101);
    String company = UUID.randomUUID().toString();
    ((ObjectNode) r.at("/data/company")).put("id", company);
    fixture(
        company,
        "REGISTRY",
        "{\"registryStatus\":\"INACTIVE\",\"registryRegisteredAt\":\"2020-01-15\",\"needsDescription\":false,\"disputedFacts\":false}");
    fixture(company, "BKI", "{\"creditHistoryScore\":1001,\"hasCurrentOverdue\":true}");
    fixture(company, "FNS", "{\"taxDebtRub\":100001}");
    String key = key();
    assertProblem(post(r, key), 503, "DEPENDENCY_UNAVAILABLE");
    fixture(company, "BKI", "{\"creditHistoryScore\":200,\"hasCurrentOverdue\":true}");
    var out = post(r, key);
    assertEquals(201, out.statusCode(), out.body());
    assertEquals("COMPLETE", json(out).path("outcome").asText());
    assertEquals("INACTIVE", json(out).at("/facts/registryStatus").asText());
    try (var c = admin.connect();
        var st = c.createStatement()) {
      st.executeUpdate(
          "DELETE FROM source_fixtures WHERE company_id='00000000-0000-4000-8000-000000000105' AND source='FNS'");
      try {
        assertProblem(get("/health/ready", null), 503, "DEPENDENCY_UNAVAILABLE");
        assertEquals(200, get("/health/live", null).statusCode());
      } finally {
        fixture("00000000-0000-4000-8000-000000000105", "FNS", "{\"taxDebtRub\":0}");
      }
    }
  }

  @Test
  void lockTimeoutDoesNotPoisonKey() throws Exception {
    ObjectNode r = request(101);
    String key = key();
    try (var c = admin.connect()) {
      c.setAutoCommit(false);
      VerificationStore.lock(c, "verification:idem:" + key);
      var blocked = post(r, key);
      assertProblem(blocked, 409, "IDEMPOTENCY_IN_PROGRESS");
      assertEquals("1", blocked.headers().firstValue("Retry-After").orElseThrow());
      c.rollback();
    }
    assertEquals(201, post(r, key).statusCode());
  }

  @Test
  void yearZeroSnapshotIsValidationErrorRatherThanSourceFailure() throws Exception {
    ObjectNode r = request(101);
    r.put("asOf", "0000-10-24T09:00:00Z");
    ((ObjectNode) r.at("/data/company")).put("registeredAt", "0000-01-15");
    ((ObjectNode) r.at("/data/consents")).put("acceptedAt", "0000-10-24T09:00:00Z");
    assertProblem(post(r, key()), 422, "VALIDATION_ERROR");
  }

  @Test
  void mathematicallyIntegralVersionUsesCanonicalJsonAndReplays() throws Exception {
    ObjectNode r = request(101);
    r.set("dataVersion", Json.parse("1.0"));
    String key = key();
    var first = post(r, key);
    assertEquals(201, first.statusCode(), first.body());
    r.put("dataVersion", 1);
    var replay = post(r, key);
    assertEquals(201, replay.statusCode(), replay.body());
    assertEquals(json(first), json(replay));
  }

  static ObjectNode request(int company) throws Exception {
    ObjectNode r = EnrichmentTest.request();
    r.put("applicationId", UUID.randomUUID().toString());
    ((ObjectNode) r.at("/data/company"))
        .put("id", String.format("00000000-0000-4000-8000-%012d", company));
    return r;
  }

  static String key() {
    return "test:" + UUID.randomUUID();
  }

  static HttpResponse<String> post(ObjectNode body, String key) throws Exception {
    return send("POST", "/v2/verifications", Json.write(body), key, TOKEN, null);
  }

  static HttpResponse<String> get(String path, String token) throws Exception {
    return send("GET", path, null, null, token, null);
  }

  static HttpResponse<String> send(
      String method, String path, String body, String key, String token, String requestId)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .timeout(Duration.ofSeconds(12));
    if (key != null) b.header("Idempotency-Key", key);
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (requestId != null) b.header("X-Request-Id", requestId);
    if (body != null) b.header("Content-Type", "application/json");
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode json(HttpResponse<String> out) throws Exception {
    return Json.MAPPER.readTree(out.body());
  }

  static void assertProblem(HttpResponse<String> out, int status, String code) throws Exception {
    assertEquals(status, out.statusCode(), out.body());
    assertEquals(code, json(out).path("code").asText());
    assertTrue(
        out.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
    Contracts.validate("Problem", json(out));
    assertEquals(
        json(out).path("requestId").asText(),
        out.headers().firstValue("X-Request-Id").orElseThrow());
  }

  static long count(String sql) throws Exception {
    try (var c = admin.connect();
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  static void fixture(String company, String source, String payload) throws Exception {
    try (var c = admin.connect();
        var s =
            c.prepareStatement(
                "INSERT INTO source_fixtures(company_id,source,payload) VALUES(?,?,?::jsonb) ON CONFLICT(company_id,source) DO UPDATE SET payload=excluded.payload")) {
      s.setObject(1, UUID.fromString(company));
      s.setString(2, source);
      s.setString(3, payload);
      s.executeUpdate();
    }
  }
}
