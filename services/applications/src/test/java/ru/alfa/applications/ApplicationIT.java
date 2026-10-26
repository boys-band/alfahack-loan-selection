package ru.alfa.applications;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.applications.Database.*;
import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;

@Tag("integration")
class ApplicationIT {
  static Service testService;

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(Api.class)
  static class TestApp {
    @Bean
    Service service() {
      return testService;
    }
  }

  PostgresHarness postgres;
  Database db;
  DependencyStubs stubs;
  MutableClock clock;
  Service service;
  Worker worker;
  ConfigurableApplicationContext context;
  String base;
  final HttpClient http = HttpClient.newHttpClient();
  static final String CUSTOMER = "demo-customer-only", STAFF = "demo-staff-only";

  @BeforeEach
  void setup() throws Exception {
    postgres = new PostgresHarness();
    clock = new MutableClock();
    stubs = new DependencyStubs(clock);
    Map<String, String> env = new HashMap<>();
    env.put("DATABASE_URL", postgres.url);
    for (String dependency :
        List.of("CATALOG", "VERIFICATION", "DECISION", "AGREEMENTS", "DISBURSEMENT"))
      env.put(dependency + "_URL", stubs.url());
    var settings = Settings.from(env);
    db = new Database(settings);
    service = new Service(settings, db, new Dependencies(settings), clock, UUID::randomUUID);
    worker = new Worker(service, () -> 0);
    testService = service;
    context =
        new SpringApplicationBuilder(TestApp.class)
            .run("--server.port=0", "--logging.level.root=ERROR");
    base =
        "http://127.0.0.1:"
            + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
  }

  @AfterEach
  void teardown() {
    if (context != null) context.close();
    if (db != null) db.close();
    if (stubs != null) stubs.close();
    if (postgres != null) postgres.close();
  }

  record Response(int status, JsonNode body, HttpHeaders headers) {
    String etag() {
      return headers.firstValue("ETag").orElse(null);
    }
  }

  Response request(
      String method, String path, JsonNode body, String token, Map<String, String> headers)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(canonical(body)));
    var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    var parsed = parse(result.body());
    Contract.validate("Uuid", node(result.headers().firstValue("X-Request-Id").orElseThrow()));
    if (result.statusCode() >= 400) {
      Contract.validate("Problem", parsed);
      assertTrue(
          result
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .startsWith("application/problem+json"));
    }
    return new Response(result.statusCode(), parsed, result.headers());
  }

  Response get(String id) throws Exception {
    var r = request("GET", "/v2/applications/" + id, null, CUSTOMER, Map.of());
    assertEquals(200, r.status, canonical(r.body));
    Contract.validate("Application", r.body);
    return r;
  }

  Response create(JsonNode modifications) throws Exception {
    var body = copy(DependencyStubs.template("approved", "createApplication"));
    // Response is a projection; the actual create command has a much narrower shape.
    body =
        obj(
            "companyId",
            "00000000-0000-4000-8000-000000000101",
            "productId",
            "working-capital",
            "productVersion",
            "1",
            "amountRub",
            1200000,
            "termMonths",
            12,
            "purpose",
            "WORKING_CAPITAL",
            "financialData",
            obj(
                "monthlyRevenueRub",
                800000,
                "monthlyExpensesRub",
                500000,
                "existingMonthlyDebtPaymentsRub",
                20000));
    if (modifications != null) body.setAll((ObjectNode) modifications);
    var result =
        request(
            "POST",
            "/v2/applications",
            body,
            CUSTOMER,
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(201, result.status, canonical(result.body));
    Contract.validate("Application", result.body);
    return result;
  }

  Response command(String id, String action, JsonNode body) throws Exception {
    return command(id, action, body, CUSTOMER);
  }

  Response command(String id, String action, JsonNode body, String token) throws Exception {
    String path =
        token.equals(STAFF)
            ? "/v2/staff/applications/" + id + "/manual-review/actions"
            : "/v2/applications/" + id + "/" + action;
    return request(
        "POST",
        path,
        body,
        token,
        Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", get(id).etag()));
  }

  String submitted(JsonNode changes) throws Exception {
    String id = s(create(changes).body, "id");
    var result =
        command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true));
    assertEquals(202, result.status, canonical(result.body));
    return id;
  }

  void drain() {
    for (int i = 0; i < 40; i++) if (!worker.tick()) return;
    fail("Worker did not become idle");
  }

  void until(String id, String state) throws Exception {
    for (int i = 0; i < 30; i++) {
      if (state.equals(s(get(id).body, "status"))) return;
      if (!worker.tick()) break;
    }
    assertEquals(state, s(get(id).body, "status"), canonical(get(id).body));
  }

  String offered() throws Exception {
    String id = submitted(null);
    until(id, "OFFER_READY");
    return id;
  }

  String awaitingSignature() throws Exception {
    String id = offered();
    var offer = get(id).body.path("decision").get("offer");
    assertEquals(
        202,
        command(
                id,
                "accept-offer",
                obj(
                    "offerId",
                    offer.get("id"),
                    "offerVersion",
                    offer.get("version"),
                    "dataVersion",
                    1))
            .status);
    until(id, "AWAITING_SIGNATURE");
    return id;
  }

  JsonNode signBody(String id) throws Exception {
    var agreement = get(id).body.get("agreement");
    return obj(
        "agreementId",
        agreement.get("id"),
        "documentVersion",
        agreement.get("documentVersion"),
        "documentSha256",
        agreement.get("documentSha256"),
        "consent",
        true);
  }

  JsonNode staffAction(
      String action, JsonNode terms, JsonNode requirements, JsonNode changes, JsonNode facts) {
    return obj(
        "action",
        action,
        "reason",
        "Решение сотрудника после анализа данных",
        "termsId",
        terms,
        "requirements",
        requirements == null ? arr() : requirements,
        "changes",
        changes,
        "factsResolution",
        facts);
  }

  @Test
  void acceptedOfferUsesDatabasePrecisionWithNanosecondClock() throws Exception {
    clock.set(Instant.parse("2030-01-01T00:00:00.123456789Z"));
    String id = awaitingSignature();
    var persisted =
        db.one("SELECT accepted_at FROM offer_acceptances WHERE application_id=?", uuid(id));
    var agreement =
        request("GET", "/v2/applications/" + id + "/agreement", null, CUSTOMER, Map.of());
    assertEquals(instant(persisted, "accepted_at"), instant(agreement.body, "acceptedAt"));
    assertEquals(
        Instant.parse("2030-01-01T00:00:00.123456Z"), instant(agreement.body, "acceptedAt"));
    assertEquals(202, command(id, "sign", signBody(id)).status);
    until(id, "DISBURSED");
  }

  @Test
  void productLimitErrorsExplainAllowedValuesAndLeaveDraftUnchanged() throws Exception {
    var draft = create(null);
    String id = s(draft.body, "id");
    String amountMessage = "Сумма кредита должна быть от 100 000 до 5 000 000 ₽.";
    String termMessage = "Срок кредита должен быть от 6 до 36 мес.";
    for (var invalid :
        List.of(
            obj("amountRub", 99999),
            obj("amountRub", 5000001),
            obj("termMonths", 5),
            obj("termMonths", 37),
            obj("amountRub", 5000001, "termMonths", 37))) {
      var expectedErrors = arr();
      if (invalid.has("amountRub"))
        expectedErrors.add(obj("path", "/amountRub", "message", amountMessage));
      if (invalid.has("termMonths"))
        expectedErrors.add(obj("path", "/termMonths", "message", termMessage));
      for (String method : List.of("POST", "PATCH")) {
        var body = copy(invalid);
        if (method.equals("POST"))
          body.setAll(
              obj(
                  "companyId", "00000000-0000-4000-8000-000000000101",
                  "productId", "working-capital",
                  "productVersion", "1"));
        var response =
            request(
                method,
                "/v2/applications" + (method.equals("PATCH") ? "/" + id : ""),
                body,
                CUSTOMER,
                Map.of(
                    "Idempotency-Key", UUID.randomUUID().toString(),
                    "If-Match", draft.etag()));
        assertEquals(422, response.status, canonical(response.body));
        assertEquals("VALIDATION_ERROR", s(response.body, "code"));
        assertEquals(expectedErrors, response.body.get("errors"));
        assertTrue(
            s(response.body, "detail")
                .contains(invalid.has("amountRub") ? amountMessage : termMessage));
        assertEquals(
            s(response.body, "requestId"),
            response.headers.firstValue("X-Request-Id").orElseThrow());
        assertEquals(draft.body, get(id).body);
      }
    }
    assertEquals(1, n(db.one("SELECT count(*) AS n FROM applications"), "n"));
    // Product bounds are inclusive; empty draft fields still remain valid.
    create(obj("amountRub", 100000, "termMonths", 6));
    create(obj("amountRub", 5000000, "termMonths", 36));
    create(obj("amountRub", null, "termMonths", null));
  }

  @Test
  void fullPathUsesHttpAndRealPostgres() throws Exception {
    postgres.applyMigration(); // Reapplying the owner runner verifies checksum/idempotency.
    var health = request("GET", "/health/ready", null, null, Map.of());
    assertEquals(200, health.status);
    String id = awaitingSignature();
    assertEquals(
        200, request("GET", "/v2/applications/" + id + "/agreement", null, STAFF, Map.of()).status);
    assertEquals(202, command(id, "sign", signBody(id)).status);
    until(id, "DISBURSED");
    drain();
    var current = get(id);
    assertTrue(current.body.get("availableActions").isEmpty());
    assertNull(current.body.get("processing").textValue());
    var history = request("GET", "/v2/applications/" + id + "/timeline", null, CUSTOMER, Map.of());
    Contract.validate("Timeline", history.body);
    List<String> stages = new ArrayList<>();
    history.body.get("items").forEach(e -> stages.add(s(e, "status") + ":" + s(e, "reviewStage")));
    assertTrue(
        stages.containsAll(
            List.of(
                "VALIDATION:null", "IN_REVIEW:ENRICHMENT", "IN_REVIEW:SCORING", "DISBURSED:null")));
    assertEquals(1, stubs.disbursements.size());
    assertEquals(
        0,
        n(db.one("SELECT count(*) AS n FROM workflow_attempts WHERE outcome='IN_PROGRESS'"), "n"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.rows("SELECT * FROM schema_migrations"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("CREATE TABLE runtime_ddl_forbidden(id integer)"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update(
                "UPDATE application_data_versions SET origin='CUSTOMER_INFO' WHERE"
                    + " application_id=?",
                uuid(id)));
  }

  @Test
  void draftPatchAndStrictValidation() throws Exception {
    var integral = create(obj("amountRub", 300000.0));
    assertEquals(300000, n(integral.body, "amountRub"));
    var draft = create(obj("amountRub", null, "financialData", null));
    String id = s(draft.body, "id");
    assertEquals(
        428,
        request(
                "PATCH",
                "/v2/applications/" + id,
                obj("amountRub", 200000),
                CUSTOMER,
                Map.of("Idempotency-Key", "missing-if-match"))
            .status);
    assertEquals(
        422,
        request(
                "PATCH",
                "/v2/applications/" + id,
                obj("staffId", UUID.randomUUID()),
                CUSTOMER,
                Map.of("Idempotency-Key", "forbidden-field", "If-Match", draft.etag()))
            .status);
    var updated =
        request(
            "PATCH",
            "/v2/applications/" + id,
            obj(
                "amountRub",
                300000,
                "financialData",
                obj(
                    "monthlyRevenueRub",
                    null,
                    "monthlyExpensesRub",
                    0,
                    "existingMonthlyDebtPaymentsRub",
                    0)),
            CUSTOMER,
            Map.of("Idempotency-Key", "patch-null-fields", "If-Match", draft.etag()));
    assertEquals(200, updated.status, canonical(updated.body));
    assertEquals(12, n(updated.body, "termMonths"));
    assertTrue(updated.body.path("financialData").get("monthlyRevenueRub").isNull());
    assertEquals(
        422,
        command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true))
            .status);
    assertEquals(
        412,
        request(
                "PATCH",
                "/v2/applications/" + id,
                obj("amountRub", null),
                CUSTOMER,
                Map.of("Idempotency-Key", "outdated-etag", "If-Match", draft.etag()))
            .status);
  }

  @Test
  void idempotencyReplayPrecedesCurrentGuardsAndPreservesOldEtag() throws Exception {
    var draft = create(null);
    String id = s(draft.body, "id"), path = "/v2/applications/" + id + "/submit";
    var headers = Map.of("Idempotency-Key", "submission-replay", "If-Match", draft.etag());
    JsonNode body = obj("personalDataConsent", true, "creditBureauConsent", true);
    var first = request("POST", path, body, CUSTOMER, headers);
    drain();
    stubs.failures.put("/v2/products/working-capital", new AtomicInteger(10));
    var again = request("POST", path, body, CUSTOMER, headers);
    assertEquals(202, again.status);
    assertEquals(first.body, again.body);
    assertEquals(first.etag(), again.etag());
    assertEquals(
        409,
        request(
                "POST",
                path,
                body,
                CUSTOMER,
                Map.of("Idempotency-Key", "submission-replay", "If-Match", get(id).etag()))
            .status);
    assertEquals(403, request("POST", path, body, STAFF, headers).status);
    var outsider = new Service(service.settings, db, service.dependencies, clock, UUID::randomUUID);
    assertThrows(
        Problem.class,
        () ->
            db.tx(
                () ->
                    outsider.access(
                        id, new Service.Actor("CUSTOMER", UUID.randomUUID().toString()), false)));
  }

  @Test
  void allThreeInformationSourcesProduceNewImmutableVersions() throws Exception {
    for (String source : List.of("VALIDATION", "ENRICHMENT", "SCORING")) {
      stubs.verificationMode = source.equals("ENRICHMENT") ? "NEEDS_INFO" : "COMPLETE";
      stubs.decisionMode = source.equals("SCORING") ? "NEEDS_INFO" : "APPROVED";
      String id = submitted(source.equals("VALIDATION") ? obj("purpose", "REFINANCING") : null);
      until(id, "NEEDS_INFO");
      var app = get(id).body;
      assertEquals(source, s(app.get("informationRequest"), "source"));
      JsonNode extra =
          switch (source) {
            case "VALIDATION" -> obj("refinancedDebtRub", 500000);
            case "ENRICHMENT" -> obj("businessDescription", "Производство изделий");
            default -> obj("revenueExplanation", "Уточненный доход");
          };
      var result =
          command(
              id,
              "provide-info",
              obj(
                  "informationRequestId",
                  app.path("informationRequest").get("id"),
                  "changes",
                  obj("additionalInfo", extra)));
      assertEquals(202, result.status, canonical(result.body));
      assertEquals(2, n(result.body, "dataVersion"));
      var old = request("GET", "/v2/applications/" + id + "/versions/1", null, CUSTOMER, Map.of());
      Contract.validate("DataVersion", old.body);
      assertTrue(old.body.path("data").path("additionalInfo").isEmpty());
      until(id, "OFFER_READY");
    }
  }

  @Test
  void draftMustAdoptCurrentProductBeforeSubmissionWithoutChangingSuccessfulReplays()
      throws Exception {
    var draft = create(null);
    String id = s(draft.body, "id");
    var patchHeaders =
        Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", draft.etag());
    var originalPatch =
        request(
            "PATCH", "/v2/applications/" + id, obj("amountRub", 1100000), CUSTOMER, patchHeaders);
    assertEquals(200, originalPatch.status);
    stubs.currentProductVersion = "2";
    stubs.currentProductChanges =
        obj(
            "maxAmountRub",
            800000,
            "minTermMonths",
            18,
            "annualRateBps",
            1800,
            "purposeCodes",
            arr("EQUIPMENT"));
    var replay =
        request(
            "PATCH", "/v2/applications/" + id, obj("amountRub", 1100000), CUSTOMER, patchHeaders);
    assertEquals(canonical(originalPatch.body), canonical(replay.body));
    assertEquals(originalPatch.etag(), replay.etag());
    var before = get(id);
    var rejected =
        command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true));
    assertEquals(409, rejected.status);
    assertEquals("PRODUCT_NOT_CURRENT", s(rejected.body, "code"));
    assertEquals(canonical(before.body), canonical(get(id).body));
    var invalid =
        request(
            "PATCH",
            "/v2/applications/" + id,
            obj("amountRub", 1100000),
            CUSTOMER,
            Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", before.etag()));
    assertEquals(422, invalid.status, canonical(invalid.body));
    assertTrue(canonical(invalid.body).contains("800 000"));
    assertTrue(canonical(invalid.body).contains("18"));
    assertTrue(canonical(invalid.body).contains("/purpose"));
    assertEquals(before.etag(), get(id).etag());
    assertEquals("1", s(get(id).body.get("product"), "version"));
    assertEquals(0, n(get(id).body, "dataVersion"));
    assertEquals(
        0,
        n(
            db.one(
                "SELECT count(*) AS n FROM application_data_versions WHERE application_id=?",
                uuid(id)),
            "n"));
    var patch =
        request(
            "PATCH",
            "/v2/applications/" + id,
            obj("amountRub", 700000, "termMonths", 24, "purpose", "EQUIPMENT"),
            CUSTOMER,
            Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", get(id).etag()));
    assertEquals(200, patch.status, canonical(patch.body));
    assertEquals("2", s(patch.body.get("product"), "version"));
    assertEquals(1800, n(patch.body.get("product"), "annualRateBps"));
    var submitHeaders =
        Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", patch.etag());
    var consents = obj("personalDataConsent", true, "creditBureauConsent", true);
    var submitted =
        request("POST", "/v2/applications/" + id + "/submit", consents, CUSTOMER, submitHeaders);
    assertEquals(202, submitted.status, canonical(submitted.body));
    until(id, "OFFER_READY");
    stubs.currentProductVersion = "3";
    var submittedReplay =
        request("POST", "/v2/applications/" + id + "/submit", consents, CUSTOMER, submitHeaders);
    assertEquals(canonical(submitted.body), canonical(submittedReplay.body));
    assertEquals(submitted.etag(), submittedReplay.etag());
    assertEquals("2", s(get(id).body.get("product"), "version"));
    var snapshot =
        request("GET", "/v2/applications/" + id + "/versions/1", null, CUSTOMER, Map.of());
    assertEquals("2", s(snapshot.body.path("data").path("product"), "version"));
    var stale =
        request(
            "POST",
            "/v2/applications",
            obj(
                "companyId",
                "00000000-0000-4000-8000-000000000101",
                "productId",
                "working-capital",
                "productVersion",
                "1"),
            CUSTOMER,
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertEquals(409, stale.status, canonical(stale.body));
    assertEquals("PRODUCT_NOT_CURRENT", s(stale.body, "code"));
  }

  @Test
  void productRolloverDoesNotBlockInformationOrStaffAmendment() throws Exception {
    stubs.verificationMode = "NEEDS_INFO";
    String id = submitted(null);
    until(id, "NEEDS_INFO");
    stubs.currentProductVersion = "2";
    var info = get(id).body.get("informationRequest");
    var provided =
        command(
            id,
            "provide-info",
            obj(
                "informationRequestId",
                info.get("id"),
                "changes",
                obj("additionalInfo", obj("businessDescription", "Производство изделий"))));
    assertEquals(202, provided.status, canonical(provided.body));
    assertEquals("1", s(provided.body.get("product"), "version"));
    stubs.verificationMode = "COMPLETE";
    stubs.decisionMode = "MANUAL_REVIEW";
    until(id, "MANUAL_REVIEW");
    var amended =
        command(
            id,
            "",
            staffAction(
                "AMEND_DATA",
                null,
                null,
                obj(
                    "financialData",
                    obj(
                        "monthlyRevenueRub",
                        900000,
                        "monthlyExpensesRub",
                        500000,
                        "existingMonthlyDebtPaymentsRub",
                        20000)),
                null),
            STAFF);
    assertEquals(202, amended.status, canonical(amended.body));
    worker.tick();
    assertEquals(3, n(get(id).body, "dataVersion"));
    assertEquals("1", s(get(id).body.get("product"), "version"));
  }

  @Test
  void unavailableCurrentProductCannotUpdateOrSubmitDraft() throws Exception {
    var draft = create(null);
    String id = s(draft.body, "id");
    for (int dependencyStatus : List.of(404, 503)) {
      stubs.failureStatus = dependencyStatus;
      stubs.failures.put("/v2/products/working-capital", new AtomicInteger(2));
      var patch =
          request(
              "PATCH",
              "/v2/applications/" + id,
              obj("amountRub", 1100000),
              CUSTOMER,
              Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", draft.etag()));
      var submit =
          command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true));
      for (var result : List.of(patch, submit)) {
        assertEquals(dependencyStatus == 404 ? 409 : 503, result.status, canonical(result.body));
        assertEquals(
            dependencyStatus == 404 ? "PRODUCT_NOT_CURRENT" : "DEPENDENCY_UNAVAILABLE",
            s(result.body, "code"));
      }
      assertEquals(canonical(draft.body), canonical(get(id).body));
      assertEquals(draft.etag(), get(id).etag());
    }
  }

  @Test
  void rejectionCounterofferAndProductChange() throws Exception {
    stubs.decisionMode = "REJECTED";
    String rejected = submitted(null);
    until(rejected, "REJECTED");
    assertEquals("CREDIT_REJECTED", s(get(rejected).body, "closureReason"));
    stubs.decisionMode = "COUNTER";
    String id = submitted(null);
    until(id, "OFFER_READY");
    assertEquals("COUNTER", s(get(id).body.path("decision").get("offer"), "type"));
    assertEquals(422, command(id, "change-terms", obj("amountRub", 1200000)).status);
    var changed =
        command(
            id,
            "change-terms",
            obj(
                "productId",
                "equipment-finance",
                "productVersion",
                "1",
                "termMonths",
                24,
                "purpose",
                "EQUIPMENT"));
    assertEquals(202, changed.status, canonical(changed.body));
    assertEquals("VALIDATION", s(changed.body, "status"));
    assertEquals(2, n(changed.body, "dataVersion"));
    assertTrue(changed.body.get("decision").isNull());
    until(id, "OFFER_READY");
  }

  @Test
  void manualApprovalAndRejection() throws Exception {
    stubs.decisionMode = "MANUAL_REVIEW";
    for (String action : List.of("APPROVE", "REJECT")) {
      String id = submitted(null);
      until(id, "MANUAL_REVIEW");
      var app = get(id).body;
      JsonNode terms =
          action.equals("APPROVE") ? app.path("decision").get("selectedTermsId") : null;
      var accepted = command(id, "", staffAction(action, terms, null, null, null), STAFF);
      assertEquals(202, accepted.status, canonical(accepted.body));
      until(id, action.equals("APPROVE") ? "OFFER_READY" : "REJECTED");
    }
  }

  @Test
  void staffRequestInfoAndFactsAmendmentCannotSkipScoring() throws Exception {
    stubs.verificationMode = "MANUAL_REVIEW";
    String id = submitted(null);
    until(id, "MANUAL_REVIEW");
    var app = get(id).body;
    var review =
        request("GET", "/v2/staff/applications/" + id + "/manual-review", null, STAFF, Map.of());
    assertEquals(200, review.status);
    Contract.validate("ManualReviewCase", review.body);
    var queue = request("GET", "/v2/staff/manual-reviews?limit=1", null, STAFF, Map.of());
    Contract.validate("ManualReviewPage", queue.body);
    assertEquals(1, queue.body.get("items").size());
    assertEquals(
        409,
        command(
                id,
                "",
                staffAction("APPROVE", node(UUID.randomUUID().toString()), null, null, null),
                STAFF)
            .status);
    JsonNode facts =
        obj(
            "verificationId",
            app.path("verification").get("id"),
            "confirmedFacts",
            app.path("verification").get("rawFacts"));
    assertEquals(
        202, command(id, "", staffAction("AMEND_DATA", null, null, null, facts), STAFF).status);
    until(id, "VALIDATION");
    var next = request("GET", "/v2/applications/" + id + "/versions/2", null, STAFF, Map.of());
    Contract.validate("DataVersion", next.body);
    assertEquals(
        service.settings.staffId(), s(next.body.path("data").get("staffAttestation"), "staffId"));
    until(id, "OFFER_READY");
    assertEquals(
        404,
        request("GET", "/v2/staff/applications/" + id + "/manual-review", null, STAFF, Map.of())
            .status);
    assertTrue(
        request("GET", "/v2/staff/manual-reviews", null, STAFF, Map.of())
            .body
            .get("items")
            .isEmpty());
    stubs.verificationMode = "COMPLETE";
    stubs.decisionMode = "MANUAL_REVIEW";
    String second = submitted(null);
    until(second, "MANUAL_REVIEW");
    assertEquals(
        202,
        command(
                second,
                "",
                staffAction(
                    "REQUEST_INFO",
                    null,
                    arr(
                        obj(
                            "path",
                            "/additionalInfo/businessDescription",
                            "message",
                            "Уточните описание")),
                    null,
                    null),
                STAFF)
            .status);
    until(second, "NEEDS_INFO");
    assertEquals("STAFF", s(get(second).body.get("informationRequest"), "source"));
  }

  @Test
  void exactDeadlineChangesEtagWithoutGetMutationAndTimersExpire() throws Exception {
    String offer = offered();
    var before = get(offer);
    clock.set(Instant.parse(s(before.body, "actionDeadline")));
    var after = get(offer);
    assertEquals(n(before.body, "version"), n(after.body, "version"));
    assertNotEquals(before.etag(), after.etag());
    assertFalse(after.body.get("availableActions").toString().contains("ACCEPT_OFFER"));
    var terms = before.body.path("decision").get("offer");
    assertEquals(
        409,
        command(
                offer,
                "accept-offer",
                obj("offerId", terms.get("id"), "offerVersion", 1, "dataVersion", 1))
            .status);
    until(offer, "EXPIRED");
    assertEquals("OFFER_TIMEOUT", s(get(offer).body, "closureReason"));
    String info = submitted(obj("purpose", "REFINANCING"));
    until(info, "NEEDS_INFO");
    clock.set(Instant.parse(s(get(info).body, "actionDeadline")));
    until(info, "EXPIRED");
    assertEquals("INFO_TIMEOUT", s(get(info).body, "closureReason"));
    String sign = awaitingSignature();
    clock.set(Instant.parse(s(get(sign).body, "actionDeadline")));
    assertEquals(409, command(sign, "sign", signBody(sign)).status);
    until(sign, "EXPIRED");
  }

  @Test
  void cancellationAndRemoteSignRaceUseAgreementAsTruth() throws Exception {
    String cancelled = awaitingSignature();
    assertEquals(
        202, command(cancelled, "cancel", obj("reason", "Финансирование не требуется")).status);
    until(cancelled, "CANCELLED");
    String id = awaitingSignature();
    assertEquals(202, command(id, "sign", signBody(id)).status);
    var sign = worker.claim();
    var signed = worker.execute(sign);
    assertEquals("SIGNED", s(signed, "status"));
    assertEquals(
        202, command(id, "cancel", obj("reason", "Отмена при потерянном ответе подписи")).status);
    assertFalse(worker.apply(sign, signed));
    until(id, "SIGNED");
    until(id, "DISBURSED");
    assertEquals(1, stubs.disbursements.size());
  }

  @Test
  void lateAgreementAndCaseCreationAreClosedAfterCancellation() throws Exception {
    String id = offered();
    var offer = get(id).body.path("decision").get("offer");
    command(
        id, "accept-offer", obj("offerId", offer.get("id"), "offerVersion", 1, "dataVersion", 1));
    var job = worker.claim();
    var result = worker.execute(job);
    assertEquals(
        200, command(id, "cancel", obj("reason", "Отмена во время подготовки договора")).status);
    assertFalse(worker.apply(job, result));
    drain();
    assertEquals("CANCELLED", s(get(id).body, "status"));
    assertEquals("CLOSED", s(stubs.agreements.get(s(result, "id")), "status"));
    stubs.verificationMode = "MANUAL_REVIEW";
    String second = submitted(null);
    worker.tick();
    worker.tick();
    worker.tick();
    var caseJob = worker.claim();
    assertEquals("MANUAL_CASE", s(caseJob, "step"));
    assertEquals(
        200, command(second, "cancel", obj("reason", "Отмена во время открытия дела")).status);
    drain();
    assertTrue(stubs.cases.values().stream().allMatch(c -> s(c, "status").equals("CLOSED")));
  }

  @Test
  void retryLeaseAndUnknownPayoutPreserveOperationAndExactRequest() throws Exception {
    stubs.failures.put("/v2/verifications", new AtomicInteger(5));
    String id = submitted(null);
    for (int i = 0; i < 12; i++) {
      worker.tick();
      clock.advance(20);
    }
    var paused = get(id).body;
    assertEquals("PAUSED", s(paused.get("processing"), "state"));
    assertEquals("IN_REVIEW", s(paused, "status"));
    var persisted =
        db.one(
            "SELECT * FROM workflow_jobs WHERE id=?",
            uuid(service.load(id, false), "current_job_id"));
    assertEquals(202, command(id, "retry-processing", null).status);
    until(id, "OFFER_READY");
    var retried = db.one("SELECT * FROM workflow_jobs WHERE id=?", uuid(persisted, "id"));
    assertEquals(persisted.get("request_payload"), retried.get("request_payload"));
    assertEquals(persisted.get("idempotency_key"), retried.get("idempotency_key"));
    assertEquals(6, n(retried, "total_attempt_count"));
    var offer = get(id).body.path("decision").get("offer");
    command(
        id, "accept-offer", obj("offerId", offer.get("id"), "offerVersion", 1, "dataVersion", 1));
    var lost = worker.claim();
    var ready = worker.execute(lost);
    clock.advance(46);
    assertFalse(worker.apply(lost, ready));
    assertTrue(worker.recover());
    until(id, "AWAITING_SIGNATURE");
    command(id, "sign", signBody(id));
    until(id, "SIGNED");
    stubs.dropPayoutResponse = true;
    worker.tick();
    worker.tick();
    assertEquals(1, stubs.disbursements.size());
    assertEquals("DISBURSING", s(get(id).body, "status"));
    clock.advance(3);
    until(id, "DISBURSED");
    assertEquals(1, stubs.disbursements.size());
    long posts =
        stubs.requests.stream().filter(r -> "/v2/disbursements".equals(s(r, "path"))).count();
    assertEquals(1, posts);
  }

  @Test
  void correctionAndLatePayoutCheckAreVersionFenced() throws Exception {
    String id = awaitingSignature();
    command(id, "sign", signBody(id));
    until(id, "SIGNED");
    stubs.correction = true;
    drain();
    var original = get(id).body.get("agreement").deepCopy();
    assertEquals("NEEDS_CORRECTION", s(get(id).body.get("payoutCheck"), "outcome"));
    assertEquals(
        202,
        command(
                id,
                "payout-account",
                obj("settlementAccountId", "00000000-0000-4000-8000-000000001199"))
            .status);
    var old = worker.claim();
    var result = worker.execute(old);
    assertEquals(
        202,
        command(
                id,
                "payout-account",
                obj("settlementAccountId", "00000000-0000-4000-8000-000000001101"))
            .status);
    assertFalse(worker.apply(old, result));
    stubs.correction = false;
    until(id, "DISBURSED");
    assertEquals(original, get(id).body.get("agreement"));
    assertEquals(3, n(get(id).body.get("payoutInstruction"), "payoutVersion"));
  }

  @Test
  void concurrentRequestsAndWorkersProduceOneResult() throws Exception {
    var draft = create(null);
    String id = s(draft.body, "id");
    JsonNode body = obj("personalDataConsent", true, "creditBureauConsent", true);
    Map<String, String> headers =
        Map.of("Idempotency-Key", "concurrent-submit", "If-Match", draft.etag());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Response>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () ->
                    request(
                        "POST", "/v2/applications/" + id + "/submit", body, CUSTOMER, headers)));
      JsonNode first = results.getFirst().get().body;
      for (var result : results) {
        var response = result.get();
        assertEquals(202, response.status, canonical(response.body));
        assertEquals(first, response.body);
      }
      var workers = List.of(worker, new Worker(service, () -> 0), new Worker(service, () -> 0));
      List<Future<?>> runs = new ArrayList<>();
      for (var instance : workers)
        runs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 15; i++) instance.tick();
                }));
      for (var run : runs) run.get();
    }
    until(id, "OFFER_READY");
    assertEquals(
        1,
        n(
            db.one(
                "SELECT count(*) AS n FROM application_data_versions WHERE application_id=?",
                uuid(id)),
            "n"));
    assertEquals(
        1, stubs.requests.stream().filter(r -> "/v2/verifications".equals(s(r, "path"))).count());
  }

  @Test
  void rolesCompaniesPaginationAndReadinessUseOnlyOwnedData() throws Exception {
    assertEquals(401, request("GET", "/v2/applications", null, null, Map.of()).status);
    assertEquals(403, request("GET", "/v2/companies", null, STAFF, Map.of()).status);
    assertEquals(403, request("GET", "/v2/staff/manual-reviews", null, CUSTOMER, Map.of()).status);
    var companies = request("GET", "/v2/companies", null, CUSTOMER, Map.of());
    Contract.validate("CompanyList", companies.body);
    assertEquals(5, companies.body.get("items").size());
    String companyId = companies.body.get("items").get(0).path("id").asText();
    var accounts =
        request("GET", "/v2/companies/" + companyId + "/accounts", null, CUSTOMER, Map.of());
    assertEquals(200, accounts.status);
    Contract.validate("AccountList", accounts.body);
    assertEquals(
        404, request("GET", "/v2/companies/" + UUID.randomUUID(), null, CUSTOMER, Map.of()).status);
    for (int i = 0; i < 3; i++) create(null);
    var first = request("GET", "/v2/applications?limit=2", null, CUSTOMER, Map.of());
    Contract.validate("ApplicationPage", first.body);
    assertEquals(2, first.body.get("items").size());
    var next =
        request(
            "GET",
            "/v2/applications?limit=2&cursor=" + s(first.body, "nextCursor"),
            null,
            CUSTOMER,
            Map.of());
    assertEquals(1, next.body.get("items").size());
    assertTrue(next.body.get("nextCursor").isNull());
    assertEquals(
        422, request("GET", "/v2/applications?cursor=invalid", null, CUSTOMER, Map.of()).status);
    assertEquals(
        422, request("GET", "/v2/applications?limit=101", null, CUSTOMER, Map.of()).status);
    stubs.failures.put("/v2/products/working-capital", new AtomicInteger(100));
    assertEquals(200, request("GET", "/health/ready", null, null, Map.of()).status);
    db.update(
        "DELETE FROM customer_companies WHERE company_id='00000000-0000-4000-8000-000000000105'");
    assertEquals(503, request("GET", "/health/ready", null, null, Map.of()).status);
    assertEquals(200, request("GET", "/health/live", null, null, Map.of()).status);
  }

  @Test
  void concurrentCreationIsAtomicAndOwnershipPrecedesReplay() throws Exception {
    var body =
        obj(
            "companyId",
            "00000000-0000-4000-8000-000000000101",
            "productId",
            "working-capital",
            "productVersion",
            "1");
    var headers = Map.of("Idempotency-Key", "create-concurrent");
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = pool.submit(() -> request("POST", "/v2/applications", body, CUSTOMER, headers));
      var b = pool.submit(() -> request("POST", "/v2/applications", body, CUSTOMER, headers));
      var first = a.get();
      var second = b.get();
      assertEquals(201, first.status);
      assertEquals(first.body, second.body);
      assertEquals(1, n(db.one("SELECT count(*) AS n FROM applications"), "n"));
      String id = s(first.body, "id");
      var settings =
          new Settings(
              service.settings.databaseUrl(),
              CUSTOMER,
              UUID.randomUUID().toString(),
              STAFF,
              service.settings.staffId(),
              "demo-internal-only",
              service.settings.adminToken(),
              service.settings.services(),
              8001,
              45);
      var stranger = new Service(settings, db, service.dependencies, clock, UUID::randomUUID);
      var problem =
          assertThrows(
              Problem.class,
              () ->
                  stranger.mutate(
                      "cancelApplication",
                      "POST",
                      "/v2/applications/" + id + "/cancel",
                      id,
                      new Service.Actor("CUSTOMER", settings.customerId()),
                      obj("reason", "Отмена"),
                      true,
                      Map.of("idempotency-key", "cancel-stranger", "if-match", first.etag()),
                      UUID.randomUUID().toString()));
      assertEquals(404, problem.status);
    }
  }

  @Test
  void retryAfterAndPermanentProtocolErrorsPreserveBusinessState() throws Exception {
    stubs.failureStatus = 409;
    stubs.failureCode = "IDEMPOTENCY_IN_PROGRESS";
    stubs.failures.put("/v2/verifications", new AtomicInteger(1));
    String id = submitted(null);
    worker.tick();
    worker.tick();
    worker.tick();
    var job =
        db.one(
            "SELECT * FROM workflow_jobs WHERE id=?",
            uuid(service.load(id, false), "current_job_id"));
    assertEquals("PENDING", s(job, "state"));
    assertEquals(clock.instant().plusSeconds(2), instant(job, "next_attempt_at"));
    assertFalse(worker.tick());
    clock.advance(2);
    until(id, "OFFER_READY");
    stubs.corruptField = "applicationId";
    String bad = submitted(null);
    worker.tick();
    worker.tick();
    worker.tick();
    var paused = get(bad).body;
    assertEquals("IN_REVIEW", s(paused, "status"));
    assertEquals("ENRICHMENT", s(paused, "reviewStage"));
    assertEquals("SNAPSHOT_MISMATCH", s(paused.get("technicalError"), "code"));
    assertEquals(1, n(paused.get("technicalError"), "attemptCount"));
    assertEquals(
        0,
        n(
            db.one("SELECT count(*) AS n FROM workflow_results WHERE application_id=?", uuid(bad)),
            "n"));
  }

  @Test
  void processRestartAndOldLeaseCannotCommitAfterNewOwner() throws Exception {
    String id = submitted(null);
    worker.tick();
    worker.tick();
    var first = worker.claim();
    var result = worker.execute(first);
    clock.advance(46);
    try (var replacementDb = new Database(service.settings)) {
      var replacement =
          new Service(
              service.settings,
              replacementDb,
              new Dependencies(service.settings),
              clock,
              UUID::randomUUID);
      var restarted = new Worker(replacement, () -> 0);
      assertTrue(restarted.recover());
      var second = restarted.claim();
      assertEquals(s(first, "id"), s(second, "id"));
      assertNotEquals(s(first, "lease_token"), s(second, "lease_token"));
      assertFalse(worker.apply(first, result));
      assertTrue(restarted.apply(second, restarted.execute(second)));
      assertEquals("SCORING", s(get(id).body, "reviewStage"));
    }
    until(id, "OFFER_READY");
    assertEquals(
        2,
        n(
            db.one("SELECT count(*) AS n FROM workflow_attempts WHERE job_id=?", uuid(first, "id")),
            "n"));
  }

  @Test
  void acceptedOfferDeadlineDoesNotLimitSignatureAndExpiryResolvesLostSign() throws Exception {
    String id = offered();
    var app = get(id).body;
    var offer = app.path("decision").get("offer");
    clock.set(instant(offer, "expiresAt").minusSeconds(1));
    assertEquals(
        202,
        command(
                id,
                "accept-offer",
                obj("offerId", offer.get("id"), "offerVersion", 1, "dataVersion", 1))
            .status);
    until(id, "AWAITING_SIGNATURE");
    clock.advance(2);
    assertTrue(get(id).body.get("availableActions").toString().contains("SIGN"));
    assertEquals(202, command(id, "sign", signBody(id)).status);
    var job = worker.claim();
    var signed = worker.execute(job);
    clock.set(Instant.parse(s(get(id).body, "actionDeadline")));
    assertTrue(worker.expire());
    assertFalse(worker.apply(job, signed));
    until(id, "SIGNED");
    until(id, "DISBURSED");
    assertEquals(1, stubs.disbursements.size());
  }

  @Test
  void everyPreSignatureStateCanBeCancelled() throws Exception {
    for (String target :
        List.of(
            "DRAFT",
            "SUBMITTED",
            "VALIDATION",
            "ENRICHMENT",
            "SCORING",
            "NEEDS_INFO",
            "MANUAL_REVIEW",
            "OFFER_READY",
            "OFFER_ACCEPTED")) {
      stubs.verificationMode = target.equals("MANUAL_REVIEW") ? "MANUAL_REVIEW" : "COMPLETE";
      String id =
          s(create(target.equals("NEEDS_INFO") ? obj("purpose", "REFINANCING") : null).body, "id");
      if (!target.equals("DRAFT"))
        command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true));
      if (Set.of("VALIDATION", "NEEDS_INFO", "MANUAL_REVIEW", "OFFER_READY").contains(target))
        until(id, target);
      if (Set.of("ENRICHMENT", "SCORING").contains(target)) {
        until(id, "IN_REVIEW");
        if (target.equals("SCORING")) worker.tick();
        assertEquals(target, s(get(id).body, "reviewStage"));
      }
      if (target.equals("OFFER_ACCEPTED")) {
        until(id, "OFFER_READY");
        var offer = get(id).body.path("decision").get("offer");
        command(
            id,
            "accept-offer",
            obj("offerId", offer.get("id"), "offerVersion", 1, "dataVersion", 1));
      }
      assertEquals(
          200, command(id, "cancel", obj("reason", "Отмена в состоянии " + target)).status);
      drain();
      assertEquals("CANCELLED", s(get(id).body, "status"));
    }
  }

  @Test
  void timeoutAfterCreditAnd404RecoveryNeverAllocateAnotherOperation() throws Exception {
    String id = awaitingSignature();
    command(id, "sign", signBody(id));
    until(id, "SIGNED");
    worker.tick();
    var frozen = get(id).body.get("payoutInstruction").deepCopy();
    stubs.timeoutPayoutResponse = true;
    Instant started = Instant.now();
    worker.tick();
    assertTrue(Duration.between(started, Instant.now()).toSeconds() >= 9);
    assertEquals("DISBURSING", s(get(id).body, "status"));
    clock.advance(2);
    until(id, "DISBURSED");
    assertEquals(frozen, get(id).body.get("payoutInstruction"));
    assertEquals(1, stubs.disbursements.size());
    String second = awaitingSignature();
    command(second, "sign", signBody(second));
    until(second, "SIGNED");
    worker.tick();
    stubs.failures.put("/v2/disbursements", new AtomicInteger(1));
    worker.tick();
    assertEquals("DISBURSING", s(get(second).body, "status"));
    clock.advance(2);
    until(second, "DISBURSED");
    var posts =
        stubs.requests.stream()
            .filter(
                r ->
                    "/v2/disbursements".equals(s(r, "path"))
                        && second.equals(s(r.path("body"), "applicationId")))
            .toList();
    assertEquals(2, posts.size());
    assertEquals(posts.get(0).get("body"), posts.get(1).get("body"));
    assertEquals(posts.get(0).get("key"), posts.get(1).get("key"));
    assertEquals(2, stubs.disbursements.size());
  }
}
