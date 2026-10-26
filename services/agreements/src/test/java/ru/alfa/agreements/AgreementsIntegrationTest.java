package ru.alfa.agreements;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.agreements.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgreementsIntegrationTest {
  static final String TOKEN = "agreements-test-token";

  static final class MutableClock extends Clock {
    final AtomicReference<Instant> value =
        new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return value.get();
    }
  }

  final MutableClock clock = new MutableClock();
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  PostgresFixture pg;
  HttpApi api;

  @BeforeAll
  void start() throws Exception {
    pg = new PostgresFixture();
    restart();
  }

  @BeforeEach
  void resetTime() {
    clock.value.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  void restart() throws Exception {
    if (api != null) api.close();
    api = new HttpApi(new Store(pg.runtimeUrl, clock), TOKEN, "127.0.0.1", 0);
    api.start();
  }

  @AfterAll
  void stop() throws Exception {
    if (api != null) api.close();
    if (pg != null) pg.close();
  }

  record Response(int status, JsonNode body, HttpResponse<String> raw) {}

  Response raw(
      HttpApi target,
      String method,
      String path,
      String key,
      String body,
      String token,
      String requestId)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (key != null) builder.header("Idempotency-Key", key);
    if (requestId != null) builder.header("X-Request-Id", requestId);
    builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode result = parse(response.body());
    String returnedId = response.headers().firstValue("X-Request-Id").orElseThrow();
    assertTrue(returnedId.matches(HttpApi.UUID_PATTERN));
    if (requestId != null && requestId.matches(HttpApi.UUID_PATTERN))
      assertEquals(requestId, returnedId);
    if (response.statusCode() >= 400) {
      Contract.validate("Problem", result);
      assertEquals(
          "application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(returnedId, text(result, "requestId"));
    } else Contract.validate(path.startsWith("/health") ? "Health" : "Agreement", result);
    return new Response(response.statusCode(), result, response);
  }

  Response request(String method, String path, String key, JsonNode body) throws Exception {
    return raw(api, method, path, key, body == null ? null : body.toString(), TOKEN, null);
  }

  Response post(String path, JsonNode body) throws Exception {
    return request("POST", path, id(), body);
  }

  ObjectNode fresh() throws Exception {
    ObjectNode r = AgreementTest.fixture();
    r.put("applicationId", id())
        .put("customerId", id())
        .put("acceptedAt", clock.instant().minusSeconds(2).toString());
    AgreementTest.object(r, "offer").set("applicationId", r.get("applicationId"));
    AgreementTest.object(r, "offer")
        .put("id", id())
        .put("expiresAt", clock.instant().plus(Duration.ofDays(7)).toString());
    return r;
  }

  JsonNode create() throws Exception {
    return create(fresh());
  }

  JsonNode create(ObjectNode input) throws Exception {
    Response response = post("/v2/agreements", input);
    assertEquals(201, response.status(), response.body().toString());
    return response.body();
  }

  ObjectNode signRequest(JsonNode agreement) {
    return obj(
        "applicationId",
        agreement.get("applicationId"),
        "dataVersion",
        agreement.get("dataVersion"),
        "offerId",
        agreement.get("offer").get("id"),
        "offerVersion",
        agreement.get("offer").get("version"),
        "customerId",
        agreement.get("customerId"),
        "documentVersion",
        agreement.get("document").get("version"),
        "documentSha256",
        agreement.get("document").get("sha256"),
        "consent",
        true);
  }

  String path(JsonNode agreement) {
    return "/v2/agreements/" + text(agreement, "id");
  }

  void error(Response response, int status, String code) {
    assertEquals(status, response.status(), response.body().toString());
    assertEquals(code, text(response.body(), "code"));
  }

  @Test
  void healthAuthAndStrictJson() throws Exception {
    assertEquals(200, raw(api, "GET", "/health/live", null, null, null, id()).status());
    assertEquals(200, raw(api, "GET", "/health/ready", null, null, null, null).status());
    error(
        raw(api, "POST", "/v2/agreements", id(), fresh().toString(), "customer-token", null),
        401,
        "UNAUTHENTICATED");
    error(
        raw(api, "POST", "/v2/agreements", id(), "{\"x\":1,\"x\":2}", TOKEN, null),
        400,
        "MALFORMED_JSON");
    error(raw(api, "POST", "/v2/agreements", id(), "{} {}", TOKEN, null), 400, "MALFORMED_JSON");
    ObjectNode invalid = fresh();
    invalid.put("extra", true);
    error(post("/v2/agreements", invalid), 422, "VALIDATION_ERROR");
    error(
        raw(api, "POST", "/v2/agreements", null, fresh().toString(), TOKEN, null),
        422,
        "VALIDATION_ERROR");
    error(raw(api, "GET", "/v2/agreements/" + id(), null, null, TOKEN, null), 404, "NOT_FOUND");
    error(
        raw(api, "POST", "/v2/agreements", id(), fresh().toString(), TOKEN, "bad-id"),
        400,
        "VALIDATION_ERROR");
  }

  @Test
  void creationHashDeadlineAndBusinessReplay() throws Exception {
    ObjectNode input = fresh();
    String key = id();
    Response original = request("POST", "/v2/agreements", key, input);
    assertEquals(201, original.status());
    JsonNode agreement = original.body();
    assertEquals("READY", text(agreement, "status"));
    assertTrue(agreement.get("signature").isNull());
    assertEquals(
        Instant.parse(text(agreement, "createdAt")).plus(Duration.ofHours(72)),
        Instant.parse(text(agreement, "signBy")));
    assertEquals(
        DocumentTemplate.sha256(text(agreement.get("document"), "content")),
        text(agreement.get("document"), "sha256"));
    restart();
    assertEquals(agreement, request("POST", "/v2/agreements", key, input).body());
    assertEquals(201, request("POST", "/v2/agreements", key, input).status());
    Response anotherKey = post("/v2/agreements", input);
    assertEquals(200, anotherKey.status());
    assertEquals(agreement, anotherKey.body());
    input.put("customerId", id());
    error(request("POST", "/v2/agreements", key, input), 409, "IDEMPOTENCY_CONFLICT");
    error(post("/v2/agreements", input), 409, "IDEMPOTENCY_CONFLICT");
    try (Connection conn = DriverManager.getConnection(pg.runtimeUrl)) {
      assertEquals(
          text(agreement.get("document"), "sha256"),
          text(
              Store.one(
                  conn, "SELECT document_sha256 FROM agreements WHERE id=?", agreement.get("id")),
              "document_sha256"));
    }
  }

  @Test
  void createSnapshotGuards() throws Exception {
    for (String field : List.of("applicationId", "dataVersion", "acceptedAt", "future")) {
      ObjectNode r = fresh();
      switch (field) {
        case "applicationId" -> AgreementTest.object(r, "offer").put(field, id());
        case "dataVersion" -> AgreementTest.object(r, "offer").put(field, 2);
        case "acceptedAt" -> r.set("acceptedAt", r.get("offer").get("expiresAt"));
        case "future" -> r.put("acceptedAt", clock.instant().plusSeconds(1).toString());
      }
      error(post("/v2/agreements", r), 422, "SNAPSHOT_MISMATCH");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"fractionalMoney", "overflowVersion"})
  void exactNumbersAreValidatedBeforePersistence(String kind) throws Exception {
    ObjectNode input = fresh();
    String body;
    if (kind.equals("fractionalMoney")) {
      body =
          input
              .toString()
              .replace("\"amountRub\":1000000", "\"amountRub\":1000000.0000000000000000001");
      assertNotEquals(input.toString(), body);
    } else {
      input.set("dataVersion", parse("18446744073709551617"));
      AgreementTest.object(input, "offer").set("dataVersion", input.get("dataVersion"));
      body = input.toString();
    }
    error(raw(api, "POST", "/v2/agreements", id(), body, TOKEN, null), 422, "VALIDATION_ERROR");
  }

  @Test
  void integralDecimalVersionsReplayAsTheSameAgreement() throws Exception {
    ObjectNode input = fresh();
    String key = id();
    Response first = request("POST", "/v2/agreements", key, input);
    assertEquals(201, first.status());
    input.set("dataVersion", parse("1.0"));
    AgreementTest.object(input, "offer").set("dataVersion", parse("1.0"));
    Response replay = request("POST", "/v2/agreements", key, input);
    assertEquals(201, replay.status());
    assertEquals(first.body(), replay.body());
    Response business = post("/v2/agreements", input);
    assertEquals(200, business.status());
    assertEquals(first.body(), business.body());
  }

  @Test
  void expiredOfferDoesNotShortenSignaturePeriod() throws Exception {
    ObjectNode input = fresh();
    input.put("acceptedAt", clock.instant().minus(Duration.ofDays(2)).toString());
    AgreementTest.object(input, "offer")
        .put("expiresAt", clock.instant().minus(Duration.ofDays(1)).toString());
    JsonNode agreement = create(input);
    clock.value.set(Instant.parse(text(agreement, "signBy")).minus(1, ChronoUnit.MICROS));
    JsonNode signed = post(path(agreement) + "/sign", signRequest(agreement)).body();
    assertEquals("SIGNED", text(signed, "status"));
    assertEquals(agreement.get("document"), signed.get("document"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "customerId",
        "applicationId",
        "dataVersion",
        "offerId",
        "offerVersion",
        "documentVersion",
        "documentSha256",
        "consent"
      })
  void invalidSignaturesNeverPersist(String field) throws Exception {
    JsonNode agreement = create();
    ObjectNode sign = signRequest(agreement);
    switch (field) {
      case "customerId", "applicationId", "offerId" -> sign.put(field, id());
      case "dataVersion", "offerVersion", "documentVersion" -> sign.put(field, 2);
      case "documentSha256" -> sign.put(field, "0".repeat(64));
      case "consent" -> sign.put(field, false);
    }
    Response response = post(path(agreement) + "/sign", sign);
    assertTrue(response.status() >= 400 && response.status() < 500);
    String expected =
        switch (field) {
          case "customerId" -> "SIGNER_MISMATCH";
          case "documentVersion", "documentSha256" -> "DOCUMENT_MISMATCH";
          case "consent" -> "VALIDATION_ERROR";
          default -> "SNAPSHOT_MISMATCH";
        };
    assertEquals(expected, text(response.body(), "code"));
    assertEquals("READY", text(request("GET", path(agreement), null, null).body(), "status"));
    try (Connection conn = DriverManager.getConnection(pg.runtimeUrl)) {
      assertEquals(
          0,
          number(
              Store.one(
                  conn,
                  "SELECT count(*) AS n FROM signatures WHERE agreement_id=?",
                  agreement.get("id")),
              "n"));
    }
  }

  @Test
  void signingReplayAcrossRestartAndDeadline() throws Exception {
    JsonNode agreement = create();
    ObjectNode sign = signRequest(agreement);
    String path = path(agreement) + "/sign", key = id();
    Response first = request("POST", path, key, sign);
    assertEquals(200, first.status());
    assertEquals("SIGNED", text(first.body(), "status"));
    JsonNode signature = first.body().get("signature");
    assertEquals(agreement.get("document").get("sha256"), signature.get("documentSha256"));
    assertEquals("DEMO_CONSENT", text(signature, "method"));
    restart();
    clock.value.set(Instant.parse(text(agreement, "signBy")).plusSeconds(1));
    assertEquals(first.body(), request("POST", path, key, sign).body());
    assertEquals(first.body(), post(path, sign).body());
    ObjectNode wrong = sign.deepCopy();
    wrong.put("customerId", id());
    error(request("POST", path, key, wrong), 409, "SIGNER_MISMATCH");
    error(raw(api, "POST", path, key, sign.toString(), "wrong", null), 401, "UNAUTHENTICATED");
    error(
        post(path(agreement) + "/close", obj("reason", "CANCELLED")),
        409,
        "AGREEMENT_ALREADY_SIGNED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXPIRED", "CANCELLED", "SUPERSEDED"})
  void deadlineBoundaryAndClosure(String reason) throws Exception {
    JsonNode agreement = create();
    ObjectNode sign = signRequest(agreement);
    if (reason.equals("EXPIRED"))
      error(post(path(agreement) + "/close", obj("reason", reason)), 409, "INVALID_STATE");
    clock.value.set(Instant.parse(text(agreement, "signBy")));
    error(post(path(agreement) + "/sign", sign), 409, "DEADLINE_EXPIRED");
    assertEquals("READY", text(request("GET", path(agreement), null, null).body(), "status"));
    String key = id();
    Response closed = request("POST", path(agreement) + "/close", key, obj("reason", reason));
    assertEquals(200, closed.status());
    assertEquals(reason, text(closed.body(), "closureReason"));
    assertEquals(
        closed.body(),
        request("POST", path(agreement) + "/close", key, obj("reason", reason)).body());
    assertEquals(
        closed.body(),
        post(
                path(agreement) + "/close",
                obj("reason", reason.equals("CANCELLED") ? "EXPIRED" : "CANCELLED"))
            .body());
    error(post(path(agreement) + "/sign", sign), 409, "AGREEMENT_CLOSED");
    assertEquals(agreement.get("document"), closed.body().get("document"));
  }

  @Test
  void createReplayKeepsOriginalResponseAfterSign() throws Exception {
    ObjectNode input = fresh();
    String key = id();
    JsonNode ready = request("POST", "/v2/agreements", key, input).body();
    post(path(ready) + "/sign", signRequest(ready));
    assertEquals("READY", text(request("POST", "/v2/agreements", key, input).body(), "status"));
    assertEquals("SIGNED", text(post("/v2/agreements", input).body(), "status"));
  }

  @Test
  void parallelCreateAndSignProduceOneResource() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      ObjectNode input = fresh();
      List<Callable<Response>> tasks = new ArrayList<>();
      String key = id();
      for (int i = 0; i < 6; i++) tasks.add(() -> request("POST", "/v2/agreements", key, input));
      JsonNode agreement = null;
      for (Future<Response> future : pool.invokeAll(tasks)) {
        Response r = future.get();
        assertEquals(201, r.status());
        if (agreement == null) agreement = r.body();
        else assertEquals(agreement, r.body());
      }
      String path = path(agreement) + "/sign";
      ObjectNode sign = signRequest(agreement);
      tasks.clear();
      for (int i = 0; i < 6; i++) tasks.add(() -> post(path, sign));
      JsonNode signed = null;
      for (Future<Response> future : pool.invokeAll(tasks)) {
        Response r = future.get();
        assertEquals(200, r.status(), r.body().toString());
        if (signed == null) signed = r.body();
        else assertEquals(signed, r.body());
      }
      try (Connection conn = DriverManager.getConnection(pg.runtimeUrl)) {
        assertEquals(
            1,
            number(
                Store.one(
                    conn,
                    "SELECT count(*) AS n FROM signatures WHERE agreement_id=?",
                    agreement.get("id")),
                "n"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentSignAndCancelHaveOneWinner() throws Exception {
    race(false);
  }

  @Test
  void concurrentSignAndExpiryHaveOneWinner() throws Exception {
    race(true);
  }

  @Test
  void differentCreateKeysStillProduceOneAgreement() throws Exception {
    ObjectNode input = fresh();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Response>> tasks =
          List.of(() -> post("/v2/agreements", input), () -> post("/v2/agreements", input));
      List<Integer> statuses = new ArrayList<>();
      JsonNode first = null;
      for (Future<Response> future : pool.invokeAll(tasks)) {
        Response r = future.get();
        statuses.add(r.status());
        if (first == null) first = r.body();
        else assertEquals(first, r.body());
      }
      Collections.sort(statuses);
      assertEquals(List.of(200, 201), statuses);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void signingSameKeyAndClosingReplayAfterRestart() throws Exception {
    JsonNode agreement = create();
    String key = id(), signPath = path(agreement) + "/sign";
    ObjectNode body = signRequest(agreement);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Response>> tasks =
          List.of(
              () -> request("POST", signPath, key, body),
              () -> request("POST", signPath, key, body));
      JsonNode first = null;
      for (Future<Response> future : pool.invokeAll(tasks)) {
        Response r = future.get();
        assertEquals(200, r.status());
        if (first == null) first = r.body();
        else assertEquals(first, r.body());
      }
    } finally {
      pool.shutdownNow();
    }
    JsonNode another = create();
    String closePath = path(another) + "/close", closeKey = id();
    JsonNode cancel = obj("reason", "CANCELLED");
    JsonNode closed = request("POST", closePath, closeKey, cancel).body();
    restart();
    assertEquals(closed, request("POST", closePath, closeKey, cancel).body());
  }

  void race(boolean expiry) throws Exception {
    JsonNode agreement = create();
    String base = path(agreement);
    ObjectNode sign = signRequest(agreement);
    try (HttpApi other =
        new HttpApi(
            new Store(
                pg.runtimeUrl,
                expiry
                    ? Clock.fixed(Instant.parse(text(agreement, "signBy")), ZoneOffset.UTC)
                    : clock),
            TOKEN,
            "127.0.0.1",
            0)) {
      other.start();
      ExecutorService pool = Executors.newFixedThreadPool(2);
      CyclicBarrier barrier = new CyclicBarrier(2);
      try {
        List<Callable<Response>> tasks =
            List.of(
                () -> {
                  barrier.await();
                  return post(base + "/sign", sign);
                },
                () -> {
                  barrier.await();
                  return raw(
                      other,
                      "POST",
                      base + "/close",
                      id(),
                      obj("reason", expiry ? "EXPIRED" : "CANCELLED").toString(),
                      TOKEN,
                      null);
                });
        int successes = 0;
        for (Future<Response> future : pool.invokeAll(tasks)) {
          Response r = future.get();
          if (r.status() == 200) successes++;
          else assertEquals(409, r.status());
        }
        assertEquals(1, successes);
        JsonNode current = request("GET", base, null, null).body();
        assertTrue(Set.of("SIGNED", "CLOSED").contains(text(current, "status")));
        try (Connection conn = DriverManager.getConnection(pg.runtimeUrl)) {
          long count =
              number(
                  Store.one(
                      conn,
                      "SELECT count(*) AS n FROM signatures WHERE agreement_id=?",
                      agreement.get("id")),
                  "n");
          assertEquals(text(current, "status").equals("SIGNED") ? 1 : 0, count);
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void immutableDocumentAndRestrictedRuntime() throws Exception {
    JsonNode agreement = create();
    post(path(agreement) + "/sign", signRequest(agreement));
    try (Connection conn = DriverManager.getConnection(pg.runtimeUrl)) {
      assertThrows(
          SQLException.class,
          () ->
              Store.execute(
                  conn,
                  "UPDATE agreements SET document_content='changed' WHERE id=?",
                  agreement.get("id")));
      assertThrows(
          SQLException.class,
          () -> Store.execute(conn, "DELETE FROM agreements WHERE id=?", agreement.get("id")));
      assertThrows(
          SQLException.class,
          () ->
              Store.execute(
                  conn,
                  "UPDATE signatures SET method='DEMO_CONSENT' WHERE agreement_id=?",
                  agreement.get("id")));
      assertThrows(SQLException.class, () -> Store.execute(conn, "CREATE TABLE forbidden(id int)"));
      assertThrows(
          SQLException.class, () -> Store.execute(conn, "SELECT * FROM schema_migrations"));
      assertThrows(
          SQLException.class,
          () -> Store.execute(conn, "ALTER TABLE agreements DISABLE TRIGGER ALL"));
    }
    assertEquals(
        agreement.get("document"),
        request("GET", path(agreement), null, null).body().get("document"));
  }

  @Test
  void outageReadinessIs503AndLiveness200() throws Exception {
    try (HttpApi offline =
        new HttpApi(
            new Store("jdbc:postgresql://127.0.0.1:1/absent", clock), TOKEN, "127.0.0.1", 0)) {
      offline.start();
      assertEquals(200, raw(offline, "GET", "/health/live", null, null, null, null).status());
      error(
          raw(offline, "GET", "/health/ready", null, null, null, null),
          503,
          "DEPENDENCY_UNAVAILABLE");
      error(
          raw(offline, "POST", "/v2/agreements", id(), fresh().toString(), TOKEN, null),
          503,
          "DEPENDENCY_UNAVAILABLE");
    }
  }
}
