package ru.alfa.applications;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.applications.Database.*;
import static ru.alfa.applications.Json.*;

import java.util.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class StaffRegistryIT {
  ApplicationIT f;

  @BeforeEach
  void setup() throws Exception {
    f = new ApplicationIT();
    f.setup();
  }

  @AfterEach
  void teardown() {
    if (f != null) f.teardown();
  }

  ApplicationIT.Response registry(String query) throws Exception {
    var response =
        f.request("GET", "/v2/staff/applications" + query, null, ApplicationIT.STAFF, Map.of());
    if (response.status() == 200) {
      Contract.validate("ApplicationPage", response.body());
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    return response;
  }

  void visible(String id, String status) throws Exception {
    f.until(id, status);
    var page = registry("?status=" + status + "&limit=100");
    assertEquals(200, page.status(), canonical(page.body()));
    assertTrue(
        java.util.stream.StreamSupport.stream(page.body().get("items").spliterator(), false)
            .anyMatch(app -> id.equals(s(app, "id"))),
        status);
    assertEquals(
        200,
        f.request("GET", "/v2/applications/" + id, null, ApplicationIT.STAFF, Map.of()).status());
  }

  @Test
  void everyStatusIsVisibleThroughRealTransitions() throws Exception {
    String id = s(f.create(null).body(), "id");
    visible(id, "DRAFT");
    f.command(id, "submit", obj("personalDataConsent", true, "creditBureauConsent", true));
    for (String status : List.of("SUBMITTED", "VALIDATION", "IN_REVIEW", "OFFER_READY"))
      visible(id, status);
    var offer = f.get(id).body().path("decision").get("offer");
    f.command(
        id, "accept-offer", obj("offerId", offer.get("id"), "offerVersion", 1, "dataVersion", 1));
    visible(id, "OFFER_ACCEPTED");
    visible(id, "AWAITING_SIGNATURE");
    f.command(id, "sign", f.signBody(id));
    for (String status : List.of("SIGNED", "DISBURSING", "DISBURSED")) visible(id, status);
    String info = f.submitted(obj("purpose", "REFINANCING"));
    visible(info, "NEEDS_INFO");
    f.stubs.decisionMode = "MANUAL_REVIEW";
    String manual = f.submitted(null);
    visible(manual, "MANUAL_REVIEW");
    f.command(manual, "", f.staffAction("REJECT", null, null, null, null), ApplicationIT.STAFF);
    visible(manual, "REJECTED");
    f.stubs.decisionMode = "APPROVED";
    String cancelled = s(f.create(null).body(), "id");
    f.command(cancelled, "cancel", obj("reason", "Проверка реестра"));
    visible(cancelled, "CANCELLED");
    String expired = f.offered();
    f.clock.set(java.time.Instant.parse(s(f.get(expired).body(), "actionDeadline")));
    visible(expired, "EXPIRED");
  }

  @Test
  void globalKeysetTiesOwnershipAndReadOnly() throws Exception {
    String first = s(f.create(null).body(), "id"), other = UUID.randomUUID().toString();
    var input =
        f.db.one("SELECT input_data FROM applications WHERE id=?", uuid(first)).get("input_data");
    for (int i = 0; i < 26; i++) {
      final int index = i;
      f.db.run(
          () -> {
            String owner = index % 2 == 0 ? f.service.settings.customerId() : other;
            f.db.update(
                "INSERT INTO customer_companies(customer_id,company_id) VALUES(?,?) ON CONFLICT DO NOTHING",
                uuid(owner),
                uuid("00000000-0000-4000-8000-000000000101"));
            var row =
                f.db.one(
                    "INSERT INTO applications(id,customer_id,input_data,created_at,updated_at) VALUES(?,?,?,?,?) RETURNING *",
                    UUID.randomUUID(),
                    uuid(owner),
                    input,
                    f.clock.instant(),
                    f.clock.instant());
            f.service.timeline(row, null, "CUSTOMER", owner, "DRAFT_CREATED", "Registry fixture");
          });
    }
    var before = f.db.one("SELECT count(*) AS n,sum(version) AS versions FROM applications");
    int calls = f.stubs.requests.size();
    Set<String> ids = new HashSet<>();
    List<String> order = new ArrayList<>();
    String cursor = null;
    do {
      var page = registry("?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
      assertEquals(200, page.status(), canonical(page.body()));
      for (var app : page.body().get("items")) {
        assertTrue(ids.add(s(app, "id")));
        order.add(s(app, "id"));
      }
      cursor = s(page.body(), "nextCursor");
    } while (cursor != null);
    assertEquals(27, ids.size());
    List<String> sorted = new ArrayList<>(order);
    sorted.sort(Comparator.reverseOrder());
    assertEquals(sorted, order);
    assertEquals(
        before, f.db.one("SELECT count(*) AS n,sum(version) AS versions FROM applications"));
    assertEquals(calls, f.stubs.requests.size());
    assertEquals(27, registry("?limit=100").body().get("items").size());
    var customer =
        f.request("GET", "/v2/applications?limit=100", null, ApplicationIT.CUSTOMER, Map.of());
    assertEquals(14, customer.body().get("items").size());
    for (var app : customer.body().get("items"))
      assertEquals(f.service.settings.customerId(), s(app, "customerId"));
    for (String token :
        List.of(ApplicationIT.CUSTOMER, "demo-internal-only", "demo-product-admin-only"))
      assertEquals(403, f.request("GET", "/v2/staff/applications", null, token, Map.of()).status());
    assertEquals(
        401, f.request("GET", "/v2/staff/applications", null, "unknown", Map.of()).status());
    assertEquals(401, f.request("GET", "/v2/staff/applications", null, null, Map.of()).status());
    f.postgres.applyMigration();
    assertNotNull(
        f.db.one(
            "SELECT indexname FROM pg_indexes WHERE indexname='application_staff_registry_idx'"));
    System.out.println(
        "Registry plan: "
            + f.db.rows(
                "EXPLAIN SELECT * FROM applications ORDER BY created_at DESC,id DESC LIMIT 1"));
  }

  @Test
  void filtersAndCursorAreBoundToCurrentVersion() throws Exception {
    String auto = f.offered(), another = f.offered();
    var first = registry("?approvalType=AUTO&limit=1");
    assertEquals(1, first.body().get("items").size());
    String cursor = s(first.body(), "nextCursor");
    assertNotNull(cursor);
    var second = registry("?approvalType=AUTO&limit=1&cursor=" + cursor);
    assertNotEquals(
        first.body().path("items").get(0).get("id"), second.body().path("items").get(0).get("id"));
    assertEquals(422, registry("?approvalType=MANUAL&cursor=" + cursor).status());
    assertEquals(422, registry("?approvalType=AUTO&status=OFFER_READY&cursor=" + cursor).status());
    assertEquals(2, registry("?approvalType=AUTO&status=OFFER_READY").body().get("items").size());
    for (String query :
        List.of(
            "?status=bad",
            "?approvalType=bad",
            "?limit=0",
            "?limit=101",
            "?limit=abc",
            "?limit=99999999999999999999",
            "?cursor=bad",
            "?cursor=",
            "?unknown=x",
            "?status=DRAFT&status=SIGNED")) assertEquals(422, registry(query).status(), query);
    assertEquals(0, registry("?approvalType=MANUAL").body().get("items").size());
    var changed =
        f.command(
            auto,
            "change-terms",
            obj(
                "amountRub",
                1100000,
                "termMonths",
                12,
                "productId",
                "working-capital",
                "productVersion",
                "1",
                "additionalInfo",
                obj()));
    assertEquals(202, changed.status(), canonical(changed.body()));
    assertEquals(1, registry("?approvalType=AUTO").body().get("items").size());
    assertTrue(f.get(auto).body().get("decision").isNull());
    assertEquals(
        200,
        f.request(
                "GET",
                "/v2/applications/" + auto + "/versions/1",
                null,
                ApplicationIT.STAFF,
                Map.of())
            .status());
    assertEquals(
        200,
        f.request(
                "GET",
                "/v2/applications/" + auto + "/timeline",
                null,
                ApplicationIT.STAFF,
                Map.of())
            .status());
    assertEquals(
        403,
        f.request(
                "POST",
                "/v2/applications/" + another + "/cancel",
                obj("reason", "Unauthorized staff command"),
                ApplicationIT.STAFF,
                Map.of(
                    "Idempotency-Key", "staff-cancel-forbidden", "If-Match", f.get(another).etag()))
            .status());
  }

  @Test
  void autoAndManualApprovalsSurviveLaterStates() throws Exception {
    String auto = f.awaitingSignature();
    assertEquals(
        1, registry("?approvalType=AUTO&status=AWAITING_SIGNATURE").body().get("items").size());
    assertEquals(
        200,
        f.request(
                "GET",
                "/v2/applications/" + auto + "/agreement",
                null,
                ApplicationIT.STAFF,
                Map.of())
            .status());
    assertEquals(202, f.command(auto, "sign", f.signBody(auto)).status());
    f.until(auto, "DISBURSED");
    assertEquals(1, registry("?approvalType=AUTO&status=DISBURSED").body().get("items").size());
    assertEquals(
        403,
        f.request(
                "POST",
                "/v2/applications/" + auto + "/sign",
                f.signBody(auto),
                ApplicationIT.STAFF,
                Map.of("Idempotency-Key", "staff-cannot-sign", "If-Match", f.get(auto).etag()))
            .status());
    assertEquals(
        404,
        f.request(
                "GET",
                "/v2/staff/applications/" + auto + "/manual-review",
                null,
                ApplicationIT.STAFF,
                Map.of())
            .status());
    f.stubs.decisionMode = "MANUAL_REVIEW";
    String manual = f.submitted(null);
    f.until(manual, "MANUAL_REVIEW");
    assertEquals(0, registry("?approvalType=MANUAL").body().get("items").size());
    var app = f.get(manual).body();
    assertEquals(
        202,
        f.command(
                manual,
                "",
                f.staffAction(
                    "APPROVE", app.get("decision").get("selectedTermsId"), null, null, null),
                ApplicationIT.STAFF)
            .status());
    f.until(manual, "OFFER_READY");
    assertEquals(1, registry("?approvalType=MANUAL&status=OFFER_READY").body().get("items").size());
    assertEquals(
        0,
        f.request("GET", "/v2/staff/manual-reviews", null, ApplicationIT.STAFF, Map.of())
            .body()
            .get("items")
            .size());
    assertEquals(1, registry("?approvalType=AUTO").body().get("items").size());
    var offer = f.get(manual).body().path("decision").get("offer");
    f.command(
        manual,
        "accept-offer",
        obj("offerId", offer.get("id"), "offerVersion", offer.get("version"), "dataVersion", 1));
    f.until(manual, "AWAITING_SIGNATURE");
    assertEquals(
        1, registry("?approvalType=MANUAL&status=AWAITING_SIGNATURE").body().get("items").size());
    f.command(manual, "sign", f.signBody(manual));
    f.until(manual, "DISBURSED");
    assertEquals(1, registry("?approvalType=MANUAL&status=DISBURSED").body().get("items").size());
  }
}
