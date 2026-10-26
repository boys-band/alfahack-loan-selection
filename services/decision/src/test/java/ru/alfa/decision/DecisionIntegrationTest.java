package ru.alfa.decision;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.decision.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionIntegrationTest {
  PostgresFixture pg;
  HttpApi api;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  static final String TOKEN = "integration-internal-token";

  @BeforeAll
  void start() throws Exception {
    pg = new PostgresFixture();
    restart();
  }

  void restart() throws Exception {
    if (api != null) api.close();
    api =
        new HttpApi(
            new Store(pg.runtimeUrl, Clock.fixed(PolicyTest.NOW, ZoneOffset.UTC)),
            TOKEN,
            "127.0.0.1",
            0);
    api.start();
  }

  @AfterAll
  void stop() throws Exception {
    if (api != null) api.close();
    if (pg != null) pg.close();
  }

  record Response(int status, JsonNode body, HttpResponse<String> raw) {}

  Response request(String method, String path, String key, JsonNode body, String token)
      throws Exception {
    return raw(method, path, key, body == null ? null : body.toString(), token);
  }

  Response raw(String method, String path, String key, String body, String token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (key != null) b.header("Idempotency-Key", key);
    b.header("Content-Type", "application/json");
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode data = parse(r.body());
    assertTrue(r.headers().firstValue("X-Request-Id").orElseThrow().matches(HttpApi.UUID_PATTERN));
    if (r.statusCode() >= 400) {
      Contract.validate("Problem", data);
      assertEquals(
          "application/problem+json", r.headers().firstValue("Content-Type").orElseThrow());
    }
    return new Response(r.statusCode(), data, r);
  }

  Response post(String path, JsonNode body) throws Exception {
    return request("POST", path, id(), body, TOKEN);
  }

  ObjectNode fresh() throws Exception {
    ObjectNode r = PolicyTest.fixture();
    r.put("applicationId", id());
    PolicyTest.object(r, "verification").set("applicationId", r.get("applicationId"));
    PolicyTest.object(r, "verification").put("id", id());
    return r;
  }

  ObjectNode manual(boolean scored) throws Exception {
    ObjectNode r = fresh();
    JsonNode decision = null;
    if (scored) {
      PolicyTest.object(r.get("verification"), "facts")
          .put("creditHistoryScore", 599)
          .put("businessAgeMonths", 11);
      PolicyTest.object(r.get("data"), "product").put("minBusinessAgeMonths", 0);
      Response response = post("/v2/decisions", r);
      assertEquals(201, response.status(), response.body().toString());
      decision = response.body();
      assertEquals("MANUAL_REVIEW", text(decision, "outcome"));
    } else {
      PolicyTest.object(r, "verification").put("outcome", "MANUAL_REVIEW").put("confirmed", false);
    }
    r.set("decision", node(decision));
    r.put("reason", "Требуется ручное рассмотрение");
    return r;
  }

  JsonNode createCase(ObjectNode r) throws Exception {
    Response response = post("/v2/manual-reviews", r);
    assertEquals(201, response.status(), response.body().toString());
    Contract.validate("ManualReviewCase", response.body());
    return response.body();
  }

  ObjectNode action(String action) {
    return obj(
        "actionId",
        id(),
        "staffId",
        id(),
        "action",
        action,
        "reason",
        "Обоснование решения сотрудником",
        "termsId",
        null,
        "requirements",
        arr(),
        "changes",
        null,
        "factsResolution",
        null);
  }

  @Test
  void healthAuthValidationAndOutage() throws Exception {
    assertEquals(200, request("GET", "/health/live", null, null, null).status());
    assertEquals(200, request("GET", "/health/ready", null, null, null).status());
    assertEquals(401, request("POST", "/v2/decisions", id(), fresh(), null).status());
    ObjectNode r = fresh();
    r.put("extra", 1);
    assertEquals(422, post("/v2/decisions", r).status());
    r.remove("extra");
    assertEquals(
        "MALFORMED_JSON",
        text(raw("POST", "/v2/decisions", id(), "{\"a\":1,\"a\":2}", TOKEN).body(), "code"));
    assertEquals(422, request("GET", "/v2/manual-reviews?limit=101", null, null, TOKEN).status());
    assertEquals(422, request("GET", "/v2/manual-reviews?other=1", null, null, TOKEN).status());
    PolicyTest.object(r, "verification").put("dataVersion", 2);
    assertEquals("SNAPSHOT_MISMATCH", text(post("/v2/decisions", r).body(), "code"));
    try (HttpApi broken =
        new HttpApi(
            new Store("jdbc:postgresql://127.0.0.1:1/no", Clock.systemUTC()),
            TOKEN,
            "127.0.0.1",
            0)) {
      broken.start();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + broken.port() + "/health/ready"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
    }
  }

  @Test
  void replayAfterRestartAndBusinessKey() throws Exception {
    ObjectNode r = fresh();
    String key = id();
    Response first = request("POST", "/v2/decisions", key, r, TOKEN);
    assertEquals(201, first.status(), first.body().toString());
    Contract.validate("DecisionResult", first.body());
    restart();
    Response replay = request("POST", "/v2/decisions", key, r, TOKEN);
    assertEquals(201, replay.status());
    assertEquals(first.body(), replay.body());
    assertEquals(401, request("POST", "/v2/decisions", key, r, "wrong").status());
    Response differentKey = post("/v2/decisions", r);
    assertEquals(200, differentKey.status());
    assertEquals(first.body(), differentKey.body());
    assertEquals(
        first.body(),
        request("GET", "/v2/decisions/" + text(first.body(), "id"), null, null, TOKEN).body());
    PolicyTest.object(r, "data").put("amountRub", 999999);
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        text(request("POST", "/v2/decisions", key, r, TOKEN).body(), "code"));
    assertEquals(409, post("/v2/decisions", r).status());
  }

  @Test
  void automaticOutcomesPersist() throws Exception {
    ObjectNode rejected = fresh();
    PolicyTest.object(rejected.get("verification"), "facts").put("hasCurrentOverdue", true);
    Response decline = post("/v2/decisions", rejected);
    assertEquals(201, decline.status());
    assertEquals("REJECTED", text(decline.body(), "outcome"));
    ObjectNode info = fresh();
    PolicyTest.object(info.get("data"), "financialData").put("monthlyExpensesRub", 0);
    PolicyTest.object(info, "data").set("additionalInfo", obj());
    Response information = post("/v2/decisions", info);
    assertEquals(201, information.status());
    assertEquals("NEEDS_INFO", text(information.body(), "outcome"));
    assertTrue(information.body().get("assessmentId").isNull());
    ObjectNode counter = PolicyTest.counter();
    counter.put("applicationId", id());
    PolicyTest.object(counter, "verification").set("applicationId", counter.get("applicationId"));
    Response alternative = post("/v2/decisions", counter);
    assertEquals(201, alternative.status());
    assertEquals("COUNTER", text(alternative.body().get("offer"), "type"));
    assertEquals(4, alternative.body().get("evaluatedTerms").size());
  }

  @Test
  void exactDecimalMoneyAndBigintOverflowAreRejected() throws Exception {
    ObjectNode decimal = fresh();
    String body =
        decimal
            .toString()
            .replace(
                "\"monthlyRevenueRub\":1000000",
                "\"monthlyRevenueRub\":1000000.0000000000000000001");
    assertNotEquals(decimal.toString(), body);
    assertEquals(422, raw("POST", "/v2/decisions", id(), body, TOKEN).status());
    ObjectNode overflow = fresh();
    overflow.set("dataVersion", parse("18446744073709551617"));
    PolicyTest.object(overflow, "verification").set("dataVersion", overflow.get("dataVersion"));
    assertEquals(422, post("/v2/decisions", overflow).status());
  }

  @Test
  void integralDecimalVersionsReplayAsTheSameRequest() throws Exception {
    ObjectNode body = fresh();
    String key = id();
    Response first = request("POST", "/v2/decisions", key, body, TOKEN);
    assertEquals(201, first.status());
    body.set("dataVersion", parse("1.0"));
    PolicyTest.object(body, "verification").set("dataVersion", parse("1.0"));
    Response replay = request("POST", "/v2/decisions", key, body, TOKEN);
    assertEquals(201, replay.status());
    assertEquals(first.body(), replay.body());
    Response business = post("/v2/decisions", body);
    assertEquals(200, business.status());
    assertEquals(first.body(), business.body());
  }

  @Test
  void approveAndRejectWithAudit() throws Exception {
    for (String command : List.of("APPROVE", "REJECT")) {
      ObjectNode r = manual(true);
      JsonNode review = createCase(r);
      ObjectNode a = action(command);
      if (command.equals("APPROVE")) a.set("termsId", r.get("decision").get("selectedTermsId"));
      String path = "/v2/manual-reviews/" + text(review, "id") + "/actions", key = id();
      Response result = request("POST", path, key, a, TOKEN);
      assertEquals(201, result.status(), result.body().toString());
      Contract.validate("ManualReviewActionResult", result.body());
      assertEquals("MANUAL", text(result.body().get("decision"), "kind"));
      assertEquals(
          a.get("staffId"), result.body().get("decision").get("staffAudit").get("staffId"));
      assertEquals(result.body(), request("POST", path, key, a, TOKEN).body());
      assertEquals(200, post(path, a).status());
      assertEquals(409, post(path, action("REJECT")).status());
      JsonNode resolved =
          request("GET", "/v2/manual-reviews/" + text(review, "id"), null, null, TOKEN).body();
      assertEquals("RESOLVED", text(resolved, "status"));
      assertTrue(resolved.get("allowedActions").isEmpty());
      assertEquals(
          r.get("decision"),
          request("GET", "/v2/decisions/" + text(r.get("decision"), "id"), null, null, TOKEN)
              .body());
    }
  }

  @Test
  void unscoredRejectInfoAmendAndApprovalGuards() throws Exception {
    for (String command : List.of("REJECT", "REQUEST_INFO", "AMEND_DATA")) {
      ObjectNode r = manual(false);
      JsonNode review = createCase(r);
      String path = "/v2/manual-reviews/" + text(review, "id") + "/actions";
      ObjectNode approve = action("APPROVE");
      approve.put("termsId", id());
      assertEquals("SCORING_REQUIRED", text(post(path, approve).body(), "code"));
      ObjectNode a = action(command);
      if (command.equals("REQUEST_INFO"))
        a.set(
            "requirements",
            arr(obj("path", "/additionalInfo/revenueExplanation", "message", "Объясните расходы")));
      if (command.equals("AMEND_DATA"))
        a.set(
            "factsResolution",
            obj(
                "verificationId",
                r.get("verification").get("id"),
                "confirmedFacts",
                r.get("verification").get("facts")));
      Response response = post(path, a);
      assertEquals(201, response.status(), response.body().toString());
      Contract.validate("ManualReviewActionResult", response.body());
      if (command.equals("REJECT"))
        assertTrue(response.body().get("decision").get("assessmentId").isNull());
      else assertTrue(response.body().get("decision").isNull());
    }
    ObjectNode r = manual(true);
    JsonNode review = createCase(r);
    ObjectNode a = action("APPROVE");
    a.put("termsId", id());
    assertEquals(422, post("/v2/manual-reviews/" + text(review, "id") + "/actions", a).status());
  }

  @Test
  void amendmentDoesNotMutateAutomaticDecision() throws Exception {
    ObjectNode r = manual(true);
    JsonNode review = createCase(r);
    String path = "/v2/manual-reviews/" + text(review, "id") + "/actions";
    ObjectNode a = action("AMEND_DATA");
    a.set("changes", obj("amountRub", r.get("data").get("amountRub")));
    assertEquals(422, post(path, a).status());
    a.set("changes", obj("amountRub", 123456));
    a.set(
        "factsResolution",
        obj("verificationId", id(), "confirmedFacts", r.get("verification").get("facts")));
    assertEquals(422, post(path, a).status());
    a.set("factsResolution", node(null));
    assertEquals(201, post(path, a).status());
    assertEquals(
        r.get("decision"),
        request("GET", "/v2/decisions/" + text(r.get("decision"), "id"), null, null, TOKEN).body());
  }

  @Test
  void closeAndPagination() throws Exception {
    JsonNode first = createCase(manual(false)), second = createCase(manual(false));
    Response page = request("GET", "/v2/manual-reviews?limit=1", null, null, TOKEN);
    assertEquals(200, page.status());
    Contract.validate("ManualReviewPage", page.body());
    String cursor = text(page.body(), "nextCursor");
    assertNotNull(cursor);
    Response next =
        request("GET", "/v2/manual-reviews?limit=1&cursor=" + cursor, null, null, TOKEN);
    assertNotEquals(
        page.body().get("items").get(0).get("id"), next.body().get("items").get(0).get("id"));
    String path = "/v2/manual-reviews/" + text(first, "id") + "/close";
    ObjectNode body = obj("reason", "SUPERSEDED");
    assertEquals(200, post(path, body).status());
    JsonNode closed =
        request("GET", "/v2/manual-reviews/" + text(first, "id"), null, null, TOKEN).body();
    assertEquals("CLOSED", text(closed, "status"));
    assertEquals(first.get("reason"), closed.get("reason"));
    assertTrue(closed.get("allowedActions").isEmpty());
    assertEquals(200, post(path, body).status());
    assertEquals(409, post(path, obj("reason", "EXPIRED")).status());
  }

  @Test
  void concurrentDecisionsAndActions() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      ObjectNode r = fresh();
      String key = id();
      List<Callable<Response>> tasks = new ArrayList<>();
      for (int i = 0; i < 6; i++) tasks.add(() -> request("POST", "/v2/decisions", key, r, TOKEN));
      List<Future<Response>> responses = pool.invokeAll(tasks);
      JsonNode first = responses.get(0).get().body();
      for (Future<Response> response : responses) {
        assertEquals(201, response.get().status());
        assertEquals(first, response.get().body());
      }
      JsonNode review = createCase(manual(true));
      String path = "/v2/manual-reviews/" + text(review, "id") + "/actions";
      List<Callable<Response>> actions =
          List.of(() -> post(path, action("REJECT")), () -> post(path, action("REJECT")));
      List<Integer> statuses = new ArrayList<>();
      for (Future<Response> response : pool.invokeAll(actions))
        statuses.add(response.get().status());
      Collections.sort(statuses);
      assertEquals(List.of(201, 409), statuses);
      try (Connection c = DriverManager.getConnection(pg.runtimeUrl)) {
        assertEquals(
            1,
            number(
                Store.one(
                    c,
                    "SELECT count(*) AS n FROM manual_review_actions WHERE case_id=?",
                    review.get("id")),
                "n"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentDifferentKeysAndSameActionId() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      ObjectNode r = fresh();
      List<Callable<Response>> tasks =
          List.of(() -> post("/v2/decisions", r), () -> post("/v2/decisions", r));
      List<Integer> statuses = new ArrayList<>();
      List<JsonNode> bodies = new ArrayList<>();
      for (Future<Response> f : pool.invokeAll(tasks)) {
        statuses.add(f.get().status());
        bodies.add(f.get().body());
      }
      Collections.sort(statuses);
      assertEquals(List.of(200, 201), statuses);
      assertEquals(bodies.get(0), bodies.get(1));
      JsonNode review = createCase(manual(false));
      String path = "/v2/manual-reviews/" + text(review, "id") + "/actions";
      ObjectNode a = action("REJECT");
      List<Callable<Response>> actions = List.of(() -> post(path, a), () -> post(path, a));
      statuses.clear();
      bodies.clear();
      for (Future<Response> f : pool.invokeAll(actions)) {
        statuses.add(f.get().status());
        bodies.add(f.get().body());
      }
      Collections.sort(statuses);
      assertEquals(List.of(200, 201), statuses);
      assertEquals(bodies.get(0), bodies.get(1));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void actionVersusClosureHasOneWinner() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      JsonNode review = createCase(manual(false));
      String base = "/v2/manual-reviews/" + text(review, "id");
      List<Callable<Response>> commands =
          List.of(
              () -> post(base + "/actions", action("REJECT")),
              () -> post(base + "/close", obj("reason", "CANCELLED")));
      List<Future<Response>> outcomes = pool.invokeAll(commands);
      int successful = 0;
      for (Future<Response> outcome : outcomes) {
        int status = outcome.get().status();
        if (status < 300) successful++;
        else assertEquals(409, status);
      }
      assertEquals(1, successful);
      assertTrue(request("GET", base, null, null, TOKEN).body().get("allowedActions").isEmpty());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void caseRejectsForgedDecisionAndWrongStaffReplay() throws Exception {
    ObjectNode r = manual(true);
    JsonNode original = r.get("decision").deepCopy();
    PolicyTest.object(r, "decision").put("id", id());
    assertEquals(404, post("/v2/manual-reviews", r).status());
    r.set("decision", original);
    JsonNode review = createCase(r);
    String path = "/v2/manual-reviews/" + text(review, "id") + "/actions";
    ObjectNode a = action("REJECT");
    assertEquals(201, post(path, a).status());
    a.put("staffId", id());
    assertEquals(409, post(path, a).status());
  }

  @Test
  void databaseGuardsAndRuntimePrivileges() throws Exception {
    JsonNode result = post("/v2/decisions", fresh()).body();
    try (Connection c = DriverManager.getConnection(pg.runtimeUrl)) {
      assertThrows(
          SQLException.class,
          () ->
              Store.execute(
                  c, "UPDATE decisions SET outcome='REJECTED' WHERE id=?", result.get("id")));
      assertThrows(
          SQLException.class,
          () -> Store.execute(c, "DELETE FROM assessments WHERE id=?", result.get("assessmentId")));
      assertThrows(SQLException.class, () -> Store.execute(c, "CREATE TABLE forbidden(id int)"));
      assertThrows(SQLException.class, () -> Store.execute(c, "SELECT * FROM schema_migrations"));
      assertThrows(
          SQLException.class, () -> Store.execute(c, "ALTER TABLE decisions DISABLE TRIGGER ALL"));
    }
  }
}
