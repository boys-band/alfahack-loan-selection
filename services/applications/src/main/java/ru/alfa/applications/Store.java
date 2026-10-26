package ru.alfa.applications;

import static ru.alfa.applications.Database.*;
import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Aggregate persistence. Mutators run under the application's row lock. */
public class Store {
  protected final Database db;
  protected final Clock clock;
  protected final Supplier<UUID> ids;
  public static final Set<String> TERMINAL =
      Set.of("DISBURSED", "REJECTED", "CANCELLED", "EXPIRED");
  public static final Set<String> CANCELLABLE =
      Set.of(
          "DRAFT",
          "SUBMITTED",
          "VALIDATION",
          "IN_REVIEW",
          "NEEDS_INFO",
          "MANUAL_REVIEW",
          "OFFER_READY",
          "OFFER_ACCEPTED",
          "AWAITING_SIGNATURE");
  private static final List<String> FIELDS =
      List.of(
          "input_data",
          "verification_result",
          "decision_result",
          "manual_review_snapshot",
          "agreement_summary",
          "payout_check_result",
          "disbursement_result",
          "technical_error",
          "data_version",
          "status",
          "review_stage",
          "current_information_request_id",
          "current_acceptance_id",
          "current_payout_version",
          "current_job_id",
          "deadline_kind",
          "action_deadline",
          "closure_reason");
  private static final Set<String> JSON_FIELDS =
      Set.of(
          "input_data",
          "verification_result",
          "decision_result",
          "manual_review_snapshot",
          "agreement_summary",
          "payout_check_result",
          "disbursement_result",
          "technical_error");
  private static final Set<String> UUID_FIELDS =
      Set.of("current_information_request_id", "current_acceptance_id", "current_job_id");

  public Store(Database db, Clock clock, Supplier<UUID> ids) {
    this.db = db;
    this.clock = clock;
    this.ids = ids;
  }

  protected Instant now() {
    return clock.instant();
  }

  protected String uid() {
    return ids.get().toString();
  }

  protected ObjectNode load(String id, boolean lock) {
    return db.one("SELECT * FROM applications WHERE id=?" + (lock ? " FOR UPDATE" : ""), uuid(id));
  }

  public static JsonNode companyDto(JsonNode row) {
    return obj(
        "id",
        row.get("id"),
        "legalName",
        row.get("legal_name"),
        "inn",
        row.get("inn"),
        "legalForm",
        row.get("legal_form"),
        "registeredAt",
        row.get("registered_at"),
        "settlementAccountId",
        row.get("settlement_account_id"));
  }

  public static JsonNode agreementSummary(JsonNode result) {
    return obj(
        "id",
        result.get("id"),
        "status",
        result.get("status"),
        "documentVersion",
        result.path("document").get("version"),
        "documentSha256",
        result.path("document").get("sha256"),
        "signBy",
        result.get("signBy"),
        "signedAt",
        result.path("signature").get("signedAt"));
  }

  protected void timeline(
      ObjectNode app, JsonNode old, String actorType, String actorId, String event, String reason) {
    long sequence =
        n(
            db.one(
                "SELECT coalesce(max(sequence),0)+1 AS n FROM application_timeline WHERE"
                    + " application_id=?",
                uuid(app, "id")),
            "n");
    db.update(
        "INSERT INTO"
            + " application_timeline(id,application_id,sequence,aggregate_version,data_version,from_status,from_review_stage,status,review_stage,actor_type,actor_id,event_type,reason,occurred_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        uuid(uid()),
        uuid(app, "id"),
        sequence,
        n(app, "version"),
        n(app, "data_version"),
        old == null ? null : s(old, "status"),
        old == null ? null : s(old, "review_stage"),
        s(app, "status"),
        s(app, "review_stage"),
        actorType,
        uuid(actorId),
        event,
        reason,
        now());
  }

  protected void save(
      ObjectNode app, JsonNode old, String actorType, String actorId, String event, String reason) {
    List<Object> values = new ArrayList<>();
    for (String field : FIELDS) {
      if (!has(app, field)) values.add(null);
      else if (JSON_FIELDS.contains(field)) values.add(app.get(field));
      else if (UUID_FIELDS.contains(field)) values.add(uuid(app, field));
      else if (field.equals("action_deadline")) values.add(instant(app, field));
      else if (field.equals("data_version") || field.equals("current_payout_version"))
        values.add(n(app, field));
      else values.add(s(app, field));
    }
    values.add(uuid(app, "id"));
    String sql =
        "UPDATE applications SET "
            + String.join(",", FIELDS.stream().map(f -> f + "=?").toList())
            + ",version=version+1 WHERE id=? RETURNING *";
    app.setAll(db.one(sql, values.toArray()));
    timeline(app, old, actorType, actorId, event, reason);
  }

  protected void save(ObjectNode app, JsonNode old, String reason) {
    save(app, old, "SYSTEM", null, "STATE_CHANGED", reason);
  }

  protected ArrayNode actions(JsonNode app, JsonNode job) {
    String state = s(app, "status");
    ArrayNode result = arr();
    if (TERMINAL.contains(state)) return result;
    boolean alive = !has(app, "action_deadline") || now().isBefore(instant(app, "action_deadline"));
    if (state.equals("DRAFT")) {
      result.add("EDIT");
      result.add("SUBMIT");
    }
    if (alive && job == null) {
      switch (state) {
        case "NEEDS_INFO" -> result.add("PROVIDE_INFO");
        case "OFFER_READY" -> {
          result.add("CHANGE_TERMS");
          result.add("ACCEPT_OFFER");
        }
        case "AWAITING_SIGNATURE" -> result.add("SIGN");
        default -> {}
      }
    }
    if (state.equals("SIGNED")) result.add("UPDATE_PAYOUT_ACCOUNT");
    if (CANCELLABLE.contains(state)) result.add("CANCEL");
    if (has(app, "technical_error")) result.add("RETRY_PROCESSING");
    return result;
  }

  public ObjectNode project(JsonNode app) {
    JsonNode info = null, acceptance = null, job = null, payout = null;
    if (has(app, "current_information_request_id")) {
      var row =
          db.one(
              "SELECT * FROM information_requests WHERE id=?",
              uuid(app, "current_information_request_id"));
      info =
          obj(
              "id",
              row.get("id"),
              "applicationId",
              row.get("application_id"),
              "dataVersion",
              row.get("data_version"),
              "source",
              row.get("source"),
              "requirements",
              row.get("requirements"),
              "createdAt",
              row.get("created_at"),
              "deadline",
              row.get("deadline"),
              "status",
              row.get("status"));
    }
    if (has(app, "current_acceptance_id")) {
      var row =
          db.one("SELECT * FROM offer_acceptances WHERE id=?", uuid(app, "current_acceptance_id"));
      acceptance =
          obj(
              "offerId",
              row.get("offer_id"),
              "offerVersion",
              row.get("offer_version"),
              "dataVersion",
              row.get("data_version"),
              "acceptedAt",
              row.get("accepted_at"));
    }
    if (has(app, "current_job_id")) {
      var row = db.one("SELECT * FROM workflow_jobs WHERE id=?", uuid(app, "current_job_id"));
      if (Set.of("PENDING", "RUNNING", "PAUSED").contains(s(row, "state")))
        job =
            obj(
                "step",
                row.get("step"),
                "operationId",
                row.get("operation_id"),
                "state",
                row.get("state"),
                "attemptCount",
                row.get("attempt_count"));
    }
    if (has(app, "current_payout_version")) {
      var row =
          db.one(
              "SELECT * FROM payout_instructions WHERE application_id=? AND data_version=? AND"
                  + " payout_version=?",
              uuid(app, "id"),
              n(app, "data_version"),
              n(app, "current_payout_version"));
      payout =
          obj(
              "payoutVersion",
              row.get("payout_version"),
              "settlementAccountId",
              row.get("settlement_account_id"),
              "operationId",
              row.get("operation_id"));
    }
    JsonNode data = app.get("input_data");
    ObjectNode result =
        obj(
            "id",
            app.get("id"),
            "customerId",
            app.get("customer_id"),
            "version",
            app.get("version"),
            "dataVersion",
            app.get("data_version"),
            "status",
            app.get("status"),
            "reviewStage",
            app.get("review_stage"),
            "verification",
            app.get("verification_result"),
            "decision",
            app.get("decision_result"),
            "informationRequest",
            info,
            "manualReview",
            app.get("manual_review_snapshot"),
            "acceptedOffer",
            acceptance,
            "agreement",
            app.get("agreement_summary"),
            "payoutInstruction",
            payout == null
                ? obj(
                    "payoutVersion",
                    1,
                    "settlementAccountId",
                    data.path("company").get("settlementAccountId"),
                    "operationId",
                    null)
                : payout,
            "payoutCheck",
            app.get("payout_check_result"),
            "disbursement",
            app.get("disbursement_result"),
            "deadlineKind",
            app.get("deadline_kind"),
            "actionDeadline",
            app.get("action_deadline"),
            "closureReason",
            app.get("closure_reason"),
            "technicalError",
            app.get("technical_error"),
            "processing",
            job,
            "availableActions",
            actions(app, job),
            "nextActionHint",
            has(app, "technical_error") ? "Повторите обработку" : hint(s(app, "status")),
            "createdAt",
            app.get("created_at"),
            "updatedAt",
            app.get("updated_at"));
    for (String key :
        List.of(
            "company",
            "product",
            "amountRub",
            "termMonths",
            "purpose",
            "financialData",
            "additionalInfo",
            "consents")) result.set(key, data.get(key));
    return result;
  }

  private String hint(String state) {
    return switch (state) {
      case "DRAFT" -> "Заполните и отправьте заявку";
      case "NEEDS_INFO" -> "Ответьте на запрос сведений";
      case "OFFER_READY" -> "Ознакомьтесь с предложением";
      case "AWAITING_SIGNATURE" -> "Подпишите договор";
      case "SIGNED" -> "Ожидается проверка реквизитов";
      case "DISBURSED" -> "Средства зачислены";
      case "CANCELLED" -> "Заявка отменена";
      case "EXPIRED" -> "Срок действия истек";
      case "REJECTED" -> "Получено решение об отказе";
      default -> "Ожидается обработка заявки";
    };
  }

  protected String job(
      ObjectNode app,
      String step,
      ObjectNode envelope,
      boolean cleanup,
      String operationId,
      String key) {
    String id = uid();
    if (key == null)
      key =
          step.toLowerCase(Locale.ROOT)
              + ":"
              + s(app, "id")
              + ":"
              + n(app, "data_version")
              + ":"
              + id;
    db.update(
        "INSERT INTO"
            + " workflow_jobs(id,application_id,data_version,step,logical_key,operation_id,request_payload,idempotency_key,expected_status,expected_review_stage,expected_payout_version,is_cleanup,next_attempt_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        uuid(id),
        uuid(app, "id"),
        n(app, "data_version"),
        step,
        id,
        uuid(operationId == null ? uid() : operationId),
        envelope,
        key,
        s(app, "status"),
        s(app, "review_stage"),
        has(app, "current_payout_version") ? n(app, "current_payout_version") : null,
        cleanup,
        now(),
        now());
    if (!cleanup) put(app, "current_job_id", id);
    return id;
  }

  protected String job(ObjectNode app, String step, ObjectNode envelope) {
    return job(app, step, envelope, false, null, null);
  }

  protected ObjectNode envelope(
      String service, String path, JsonNode body, String schema, String requestId) {
    return obj(
        "kind",
        "http",
        "service",
        service,
        "path",
        path,
        "body",
        body,
        "schema",
        schema,
        "requestId",
        requestId);
  }

  protected void httpJob(
      ObjectNode app,
      String step,
      String service,
      String path,
      JsonNode body,
      String schema,
      String requestId,
      boolean cleanup) {
    job(app, step, envelope(service, path, body, schema, requestId), cleanup, null, null);
  }

  protected void httpJob(
      ObjectNode app,
      String step,
      String service,
      String path,
      JsonNode body,
      String schema,
      String requestId) {
    httpJob(app, step, service, path, body, schema, requestId, false);
  }

  protected void validationJob(ObjectNode app, String requestId) {
    job(app, "VALIDATION", obj("kind", "validation", "requestId", requestId));
  }

  protected void finishJob(JsonNode job, String state) {
    db.update(
        "UPDATE workflow_jobs SET state=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE"
            + " id=?",
        state,
        now(),
        uuid(job, "id"));
  }

  protected void detach(ObjectNode app, String requestId, String reason) {
    if (!has(app, "current_job_id")) return;
    var previous =
        db.one("SELECT * FROM workflow_jobs WHERE id=? FOR UPDATE", uuid(app, "current_job_id"));
    if (Set.of("PENDING", "RUNNING", "PAUSED").contains(s(previous, "state"))) {
      finishJob(previous, "CANCELLED");
      db.update(
          "UPDATE workflow_attempts SET outcome='LEASE_LOST',finished_at=? WHERE job_id=? AND"
              + " outcome='IN_PROGRESS'",
          now(),
          uuid(previous, "id"));
      if (Set.of("AGREEMENT", "MANUAL_CASE").contains(s(previous, "step"))) {
        job(
            app,
            "CLOSURE",
            obj(
                "kind",
                "recover",
                "original",
                previous.get("request_payload"),
                "originalKey",
                previous.get("idempotency_key"),
                "reason",
                reason,
                "requestId",
                requestId),
            true,
            null,
            null);
      }
    }
    put(app, "current_job_id", null);
    put(app, "technical_error", null);
  }

  protected void closeCase(ObjectNode app, String requestId, String reason) {
    if (has(app, "manual_review_snapshot")
        && "OPEN".equals(s(app.get("manual_review_snapshot"), "status"))) {
      var review = copy(app.get("manual_review_snapshot"));
      httpJob(
          app,
          "CLOSURE",
          "decision",
          "/v2/manual-reviews/" + s(review, "id") + "/close",
          obj("reason", reason),
          "ManualReviewCase",
          requestId,
          true);
      put(review, "status", "CLOSED");
      put(review, "allowedActions", arr());
      put(app, "manual_review_snapshot", review);
    }
  }

  protected void clearDeadline(ObjectNode app) {
    put(app, "deadline_kind", null);
    put(app, "action_deadline", null);
  }

  protected void newVersion(
      ObjectNode app,
      JsonNode data,
      String origin,
      String actorId,
      String state,
      String requestId) {
    detach(app, requestId, "SUPERSEDED");
    closeCase(app, requestId, "SUPERSEDED");
    put(app, "data_version", n(app, "data_version") + 1);
    put(app, "input_data", data.deepCopy());
    put(app, "status", state);
    put(app, "review_stage", null);
    for (String field :
        List.of(
            "verification_result",
            "decision_result",
            "manual_review_snapshot",
            "agreement_summary",
            "payout_check_result",
            "disbursement_result",
            "current_information_request_id",
            "current_acceptance_id",
            "current_payout_version",
            "technical_error",
            "closure_reason")) put(app, field, null);
    clearDeadline(app);
    db.update(
        "INSERT INTO"
            + " application_data_versions(application_id,data_version,payload,origin,actor_id,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        uuid(app, "id"),
        n(app, "data_version"),
        data,
        origin,
        uuid(actorId),
        now());
    validationJob(app, requestId);
  }

  protected void needsInfo(ObjectNode app, JsonNode requirements, String source) {
    String id = uid();
    Instant deadline = now().plus(Duration.ofHours(72));
    db.update(
        "INSERT INTO"
            + " information_requests(id,application_id,data_version,source,requirements,created_at,deadline)"
            + " VALUES(?,?,?,?,?,?,?)",
        uuid(id),
        uuid(app, "id"),
        n(app, "data_version"),
        source,
        requirements,
        now(),
        deadline);
    put(app, "status", "NEEDS_INFO");
    put(app, "review_stage", null);
    put(app, "current_information_request_id", id);
    put(app, "deadline_kind", "INFO");
    put(app, "action_deadline", deadline);
  }

  protected void terminate(ObjectNode app, String state, String reason, String requestId) {
    detach(app, requestId, state.equals("CANCELLED") ? "CANCELLED" : "EXPIRED");
    closeCase(app, requestId, state.equals("CANCELLED") ? "CANCELLED" : "EXPIRED");
    if (has(app, "current_information_request_id"))
      db.update(
          "UPDATE information_requests SET status='CLOSED',completed_at=? WHERE id=? AND"
              + " status='OPEN'",
          now(),
          uuid(app, "current_information_request_id"));
    if (has(app, "current_acceptance_id"))
      db.update(
          "UPDATE offer_acceptances SET status='VOIDED' WHERE id=? AND status='ACTIVE'",
          uuid(app, "current_acceptance_id"));
    put(app, "status", state);
    put(app, "review_stage", null);
    put(app, "closure_reason", reason);
    put(app, "current_job_id", null);
    put(app, "technical_error", null);
    clearDeadline(app);
  }

  protected void requestClosure(ObjectNode app, String reason, String requestId) {
    detach(app, requestId, reason);
    httpJob(
        app,
        "CLOSURE",
        "agreements",
        "/v2/agreements/" + s(app.get("agreement_summary"), "id") + "/close",
        obj("reason", reason),
        "Agreement",
        requestId);
  }

  protected boolean matching(JsonNode app, JsonNode job) {
    return Objects.equals(s(app, "current_job_id"), s(job, "id"))
        && n(app, "data_version") == n(job, "data_version")
        && Objects.equals(s(app, "status"), s(job, "expected_status"))
        && Objects.equals(s(app, "review_stage"), s(job, "expected_review_stage"))
        && Objects.equals(app.get("current_payout_version"), job.get("expected_payout_version"));
  }

  protected void payoutJob(ObjectNode app, String accountId, String requestId) {
    httpJob(
        app,
        "PAYOUT_VALIDATION",
        "disbursement",
        "/v2/payout-checks",
        obj(
            "applicationId",
            app.get("id"),
            "dataVersion",
            app.get("data_version"),
            "payoutVersion",
            app.get("current_payout_version"),
            "agreementId",
            app.path("agreement_summary").get("id"),
            "companyId",
            app.path("input_data").path("company").get("id"),
            "settlementAccountId",
            accountId),
        "PayoutCheck",
        requestId);
  }
}
