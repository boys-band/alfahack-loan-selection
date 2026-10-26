package ru.alfa.applications;

import static ru.alfa.applications.Database.*;
import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.*;

/** A network operation never holds a database transaction. Every completion is lease-fenced. */
public final class Worker implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
  private static final Set<String> RACES =
      Set.of("AGREEMENT_CLOSED", "AGREEMENT_ALREADY_SIGNED", "DEADLINE_EXPIRED");
  private final Service s;
  private final Database db;
  private final DoubleSupplier jitter;
  private volatile boolean stopping;

  public Worker(Service service) {
    this(service, () -> ThreadLocalRandom.current().nextDouble(0.25));
  }

  public Worker(Service service, DoubleSupplier jitter) {
    this.s = service;
    this.db = service.db;
    this.jitter = jitter;
  }

  public void stop() {
    stopping = true;
  }

  ObjectNode claim() {
    return db.tx(
        () -> {
          var job =
              db.one(
                  "SELECT * FROM workflow_jobs WHERE state='PENDING' AND next_attempt_at<=? ORDER"
                      + " BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
                  s.now());
          if (job == null) return null;
          var claimed =
              db.one(
                  "UPDATE workflow_jobs SET"
                      + " state='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1,total_attempt_count=total_attempt_count+1"
                      + " WHERE id=? RETURNING *",
                  uuid(s.uid()),
                  s.now().plusSeconds(s.settings.leaseSeconds()),
                  uuid(job, "id"));
          db.update(
              "INSERT INTO workflow_attempts(id,job_id,attempt_no,lease_token,started_at)"
                  + " VALUES(?,?,?,?,?)",
              uuid(s.uid()),
              uuid(job, "id"),
              n(claimed, "total_attempt_count"),
              uuid(claimed, "lease_token"),
              s.now());
          return claimed;
        });
  }

  private boolean owns(JsonNode current, JsonNode job) {
    return current != null
        && "RUNNING".equals(s(current, "state"))
        && Objects.equals(s(current, "lease_token"), s(job, "lease_token"))
        && instant(current, "lease_until").isAfter(s.now());
  }

  private void finishAttempt(JsonNode job, String outcome, String code) {
    db.update(
        "UPDATE workflow_attempts SET outcome=?,finished_at=?,error_code=?,error_message=? WHERE"
            + " job_id=? AND lease_token=? AND outcome='IN_PROGRESS'",
        outcome,
        s.now(),
        code,
        code == null ? null : "Dependency request failed",
        uuid(job, "id"),
        uuid(job, "lease_token"));
  }

  private void technical(ObjectNode app, JsonNode job, String code, boolean retryable) {
    put(
        app,
        "technical_error",
        obj(
            "step",
            job.get("step"),
            "code",
            code,
            "message",
            "Обработка этапа приостановлена",
            "attemptCount",
            job.get("attempt_count"),
            "lastAttemptAt",
            s.now(),
            "retryable",
            retryable));
  }

  boolean recover() {
    return db.tx(
        () -> {
          var candidate =
              db.one(
                  "SELECT * FROM workflow_jobs WHERE state='RUNNING' AND lease_until<=? ORDER BY"
                      + " lease_until,id LIMIT 1",
                  s.now());
          if (candidate == null) return false;
          var app = s.load(s(candidate, "application_id"), true);
          var job =
              db.one("SELECT * FROM workflow_jobs WHERE id=? FOR UPDATE", uuid(candidate, "id"));
          if (!"RUNNING".equals(s(job, "state")) || instant(job, "lease_until").isAfter(s.now()))
            return false;
          finishAttempt(job, "LEASE_LOST", "LEASE_LOST");
          if (n(job, "attempt_count") >= 5 && !job.path("is_cleanup").asBoolean()) {
            db.update(
                "UPDATE workflow_jobs SET state='PAUSED',lease_token=NULL,lease_until=NULL WHERE"
                    + " id=?",
                uuid(job, "id"));
            if (s.matching(app, job)) {
              var old = app.deepCopy();
              technical(app, job, "LEASE_LOST", true);
              s.save(
                  app, old, "SYSTEM", null, "TECHNICAL_FAILURE", "Истек lease последней попытки");
            }
          } else
            db.update(
                "UPDATE workflow_jobs SET"
                    + " state='PENDING',lease_token=NULL,lease_until=NULL,attempt_count=?,next_attempt_at=?"
                    + " WHERE id=?",
                n(job, "attempt_count") >= 5 ? 0 : n(job, "attempt_count"),
                s.now(),
                uuid(job, "id"));
          return true;
        });
  }

  private JsonNode call(JsonNode envelope, String key) {
    return s.dependencies.call(
        s(envelope, "service"),
        "POST",
        s(envelope, "path"),
        s(envelope, "requestId"),
        envelope.get("body"),
        key,
        s(envelope, "schema"));
  }

  JsonNode execute(JsonNode job) {
    var envelope = job.get("request_payload");
    String kind = s(envelope, "kind"), step = s(job, "step");
    if (kind.equals("validation")) return obj();
    if (kind.equals("reconcile"))
      return s.dependencies.get(
          s(envelope, "service"),
          s(envelope, "path"),
          s(envelope, "requestId"),
          s(envelope, "schema"));
    if (kind.equals("recover")) return call(envelope.get("original"), s(envelope, "originalKey"));
    if (step.equals("DISBURSEMENT")) {
      try {
        return s.dependencies.get(
            "disbursement",
            "/v2/disbursements/by-operation/" + s(envelope.get("body"), "operationId"),
            s(envelope, "requestId"),
            "Disbursement");
      } catch (Dependencies.Failure ex) {
        if (ex.status != 404) throw ex;
      }
    }
    try {
      return call(envelope, s(job, "idempotency_key"));
    } catch (Dependencies.Failure ex) {
      if (Set.of("SIGNATURE", "CLOSURE").contains(step)
          && "agreements".equals(s(envelope, "service"))
          && RACES.contains(ex.code)) {
        String path = s(envelope, "path");
        path = path.substring(0, path.lastIndexOf('/'));
        var result = s.dependencies.get("agreements", path, s(envelope, "requestId"), "Agreement");
        if ("READY".equals(s(result, "status")) && !ex.code.equals("DEADLINE_EXPIRED"))
          throw new Dependencies.Failure("AGREEMENT_RACE_UNRESOLVED", true);
        return result;
      }
      throw ex;
    }
  }

  void fail(JsonNode job, Dependencies.Failure error) {
    db.run(
        () -> {
          var app = s.load(s(job, "application_id"), true);
          var current =
              db.one("SELECT * FROM workflow_jobs WHERE id=? FOR UPDATE", uuid(job, "id"));
          if (!owns(current, job)) return;
          finishAttempt(job, error.retryable ? "RETRYABLE_ERROR" : "PERMANENT_ERROR", error.code);
          boolean cleanup = job.path("is_cleanup").asBoolean();
          boolean retry = cleanup || (error.retryable && n(job, "attempt_count") < 5);
          if (retry) {
            double delay =
                Math.max(
                    error.retryAfter,
                    (1L << Math.min(n(job, "attempt_count") - 1, 3)) + jitter.getAsDouble());
            if (cleanup && n(job, "attempt_count") >= 5) delay = Math.max(delay, 60);
            db.update(
                "UPDATE workflow_jobs SET"
                    + " state='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=?,attempt_count=?"
                    + " WHERE id=?",
                s.now().plusMillis((long) (delay * 1000)),
                cleanup && n(job, "attempt_count") >= 5 ? 0 : n(job, "attempt_count"),
                uuid(job, "id"));
          } else {
            db.update(
                "UPDATE workflow_jobs SET state='PAUSED',lease_token=NULL,lease_until=NULL WHERE"
                    + " id=?",
                uuid(job, "id"));
            if (s.matching(app, job)) {
              var old = app.deepCopy();
              technical(app, job, error.code, error.retryable);
              s.save(
                  app,
                  old,
                  "SYSTEM",
                  null,
                  "TECHNICAL_FAILURE",
                  "Этап приостановлен после технической ошибки");
            }
          }
          LOG.warn(
              "workflow_failure application={} data_version={} step={} code={} request_id={}",
              s(job, "application_id"),
              n(job, "data_version"),
              s(job, "step"),
              error.code,
              s(job.get("request_payload"), "requestId"));
        });
  }

  private void check(boolean condition) {
    if (!condition) throw new Dependencies.Failure("SNAPSHOT_MISMATCH", false);
  }

  private void equalFields(JsonNode result, JsonNode expected, String... fields) {
    for (String field : fields) check(Objects.equals(result.get(field), expected.get(field)));
  }

  private void verifyDecision(JsonNode app, JsonNode result) {
    check(
        s(app, "id").equals(s(result, "applicationId"))
            && n(app, "data_version") == n(result, "dataVersion"));
    if ("APPROVED".equals(s(result, "outcome"))) {
      JsonNode offer = result.get("offer"), selected = null;
      for (var term : result.get("evaluatedTerms"))
        if (s(term, "id").equals(s(result, "selectedTermsId"))) selected = term;
      check(
          selected != null
              && s(offer, "termsId").equals(s(selected, "id"))
              && s(offer, "applicationId").equals(s(app, "id"))
              && n(offer, "dataVersion") == n(app, "data_version"));
      check(
          s(offer, "productId").equals(s(app.path("input_data").get("product"), "id"))
              && s(offer, "productVersion")
                  .equals(s(app.path("input_data").get("product"), "version")));
      equalFields(offer, selected, "amountRub", "termMonths", "annualRateBps", "monthlyPaymentRub");
      check(
          selected.path("eligible").asBoolean() && selected.get("risk").equals(result.get("risk")));
    }
  }

  private void verify(JsonNode app, JsonNode job, JsonNode result) {
    JsonNode envelope = job.get("request_payload");
    String kind = s(envelope, "kind"), step = s(job, "step");
    if (kind.equals("validation")) return;
    JsonNode source = kind.equals("recover") ? envelope.get("original") : envelope;
    Contract.validate(s(source, "schema"), result);
    check(
        s(app, "id").equals(s(result, "applicationId"))
            && n(job, "data_version") == n(result, "dataVersion"));
    JsonNode body = source.get("body");
    boolean cleanup = job.path("is_cleanup").asBoolean();
    if (Set.of("PAYOUT_VALIDATION", "DISBURSEMENT").contains(step))
      body.fields().forEachRemaining(e -> check(e.getValue().equals(result.get(e.getKey()))));
    if ("Agreement".equals(s(source, "schema"))) {
      if (step.equals("AGREEMENT") || kind.equals("recover")) {
        equalFields(
            result, body, "applicationId", "dataVersion", "customerId", "offer", "acceptedAt");
        check(s(result, "companyId").equals(s(body.get("company"), "id")));
      } else {
        String[] parts = s(source, "path").split("/");
        check(s(result, "id").equals(parts[3]));
        check(
            s(result, "customerId").equals(s(app, "customer_id"))
                && s(result, "companyId").equals(s(app.path("input_data").get("company"), "id")));
        if (!cleanup) {
          var summary = app.get("agreement_summary");
          check(
              n(result.get("document"), "version") == n(summary, "documentVersion")
                  && s(result.get("document"), "sha256").equals(s(summary, "documentSha256"))
                  && s(result, "signBy").equals(s(summary, "signBy")));
          check(result.get("offer").equals(app.path("decision_result").get("offer")));
          var acceptance =
              db.one(
                  "SELECT accepted_at FROM offer_acceptances WHERE id=?",
                  uuid(app, "current_acceptance_id"));
          check(instant(result, "acceptedAt").equals(instant(acceptance, "accepted_at")));
        }
      }
      check(sha(s(result.get("document"), "content")).equals(s(result.get("document"), "sha256")));
      if (s(result, "status").equals("SIGNED")) {
        var signature = result.get("signature");
        check(
            s(signature, "customerId").equals(s(result, "customerId"))
                && n(signature, "documentVersion") == n(result.get("document"), "version")
                && s(signature, "documentSha256").equals(s(result.get("document"), "sha256"))
                && instant(signature, "signedAt").isBefore(instant(result, "signBy")));
      }
    }
    if (step.equals("ENRICHMENT")) equalFields(result, body, "asOf");
    if (step.equals("SCORING")) {
      check("AUTO".equals(s(result, "kind")));
      verifyDecision(app, result);
    }
    if (step.equals("MANUAL_CASE")) {
      check(
          s(result, "verificationId").equals(s(body.get("verification"), "id"))
              && s(result, "status").equals("OPEN"));
      check(
          Objects.equals(s(result, "decisionId"), s(body.path("decision"), "id"))
              && Objects.equals(
                  s(result, "assessmentId"), s(body.path("decision"), "assessmentId")));
    }
    if (step.equals("MANUAL_ACTION")) {
      equalFields(
          result,
          body,
          "action",
          "staffId",
          "reason",
          "requirements",
          "changes",
          "factsResolution");
      check(
          s(result, "id").equals(s(body, "actionId"))
              && s(result, "caseId").equals(s(source, "path").split("/")[3]));
      if (has(result, "decision")) {
        var decision = result.get("decision");
        verifyDecision(app, decision);
        check("MANUAL".equals(s(decision, "kind")));
        check(
            s(decision.get("staffAudit"), "staffId").equals(s(body, "staffId"))
                && s(decision.get("staffAudit"), "actionId").equals(s(body, "actionId")));
        if (s(body, "action").equals("APPROVE")) {
          check(s(decision, "selectedTermsId").equals(s(body, "termsId")));
          check(
              s(decision, "assessmentId")
                  .equals(s(app.get("manual_review_snapshot"), "assessmentId")));
        }
      }
    }
    if (step.equals("DISBURSEMENT"))
      check(n(result, "amountRub") == n(app.path("decision_result").get("offer"), "amountRub"));
  }

  boolean apply(JsonNode job, JsonNode result) {
    return db.tx(
        () -> {
          var app = s.load(s(job, "application_id"), true);
          var current =
              db.one("SELECT * FROM workflow_jobs WHERE id=? FOR UPDATE", uuid(job, "id"));
          if (!owns(current, job)) return false;
          verify(app, job, result);
          if (!"validation".equals(s(job.get("request_payload"), "kind")))
            db.update(
                "INSERT INTO"
                    + " workflow_results(job_id,application_id,data_version,step,payload,recorded_at)"
                    + " VALUES(?,?,?,?,?,?)",
                uuid(job, "id"),
                uuid(app, "id"),
                n(job, "data_version"),
                s(job, "step"),
                result,
                s.now());
          finishAttempt(job, "SUCCEEDED", null);
          s.finishJob(job, "SUCCEEDED");
          if (job.path("is_cleanup").asBoolean()) {
            if ("recover".equals(s(job.get("request_payload"), "kind"))) {
              var envelope = job.get("request_payload");
              String service = s(envelope.get("original"), "service");
              String path =
                  (service.equals("agreements") ? "/v2/agreements/" : "/v2/manual-reviews/")
                      + s(result, "id")
                      + "/close";
              var shadow = app.deepCopy();
              put(shadow, "data_version", n(job, "data_version"));
              s.httpJob(
                  shadow,
                  "CLOSURE",
                  service,
                  path,
                  obj("reason", envelope.get("reason")),
                  service.equals("agreements") ? "Agreement" : "ManualReviewCase",
                  s(envelope, "requestId"),
                  true);
            }
            return true;
          }
          if (!s.matching(app, job)) return false;
          var old = app.deepCopy();
          put(app, "current_job_id", null);
          put(app, "technical_error", null);
          advance(app, job, result);
          s.save(app, old, "Завершен этап " + s(job, "step"));
          return true;
        });
  }

  private void manualJob(ObjectNode app, String requestId) {
    s.httpJob(
        app,
        "MANUAL_CASE",
        "decision",
        "/v2/manual-reviews",
        obj(
            "applicationId",
            app.get("id"),
            "dataVersion",
            app.get("data_version"),
            "data",
            app.get("input_data"),
            "verification",
            app.get("verification_result"),
            "decision",
            app.get("decision_result"),
            "reason",
            "Требуется рассмотрение сотрудником"),
        "ManualReviewCase",
        requestId);
  }

  private void decision(ObjectNode app, JsonNode result) {
    put(app, "decision_result", result);
    put(app, "review_stage", null);
    switch (s(result, "outcome")) {
      case "APPROVED" -> {
        put(app, "status", "OFFER_READY");
        put(app, "deadline_kind", "OFFER");
        put(app, "action_deadline", result.path("offer").get("expiresAt"));
      }
      case "REJECTED" -> {
        put(app, "status", "REJECTED");
        put(app, "closure_reason", "CREDIT_REJECTED");
        s.clearDeadline(app);
      }
      case "NEEDS_INFO" -> s.needsInfo(app, result.get("requirements"), "SCORING");
      default -> throw new Dependencies.Failure("DEPENDENCY_PROTOCOL_ERROR", false);
    }
  }

  private void signed(ObjectNode app, JsonNode result, String requestId) {
    put(app, "status", "SIGNED");
    put(app, "review_stage", null);
    put(app, "agreement_summary", Store.agreementSummary(result));
    put(app, "current_payout_version", 1);
    s.clearDeadline(app);
    String account = s(app.path("input_data").get("company"), "settlementAccountId");
    db.update(
        "INSERT INTO"
            + " payout_instructions(application_id,data_version,payout_version,settlement_account_id,created_at)"
            + " VALUES(?,?,1,?,?)",
        uuid(app, "id"),
        n(app, "data_version"),
        uuid(account),
        s.now());
    s.payoutJob(app, account, requestId);
  }

  private void agreement(ObjectNode app, JsonNode job, JsonNode result) {
    String requestId = s(job.get("request_payload"), "requestId");
    put(app, "agreement_summary", Store.agreementSummary(result));
    if (s(job, "step").equals("AGREEMENT")) {
      put(app, "status", "AWAITING_SIGNATURE");
      put(app, "deadline_kind", "SIGNATURE");
      put(app, "action_deadline", result.get("signBy"));
      if (!s(result, "status").equals("READY"))
        s.job(
            app,
            "SIGNATURE",
            obj(
                "kind",
                "reconcile",
                "service",
                "agreements",
                "path",
                "/v2/agreements/" + s(result, "id"),
                "body",
                obj(),
                "schema",
                "Agreement",
                "requestId",
                requestId));
    } else if (s(result, "status").equals("SIGNED")) signed(app, result, requestId);
    else if (s(result, "status").equals("CLOSED")) {
      boolean cancelled = s(result, "closureReason").equals("CANCELLED");
      s.terminate(
          app,
          cancelled ? "CANCELLED" : "EXPIRED",
          cancelled ? "CUSTOMER_CANCELLED" : "SIGNATURE_TIMEOUT",
          requestId);
    } else s.requestClosure(app, "EXPIRED", requestId);
  }

  private void advance(ObjectNode app, JsonNode job, JsonNode result) {
    String requestId = s(job.get("request_payload"), "requestId");
    switch (s(job, "step")) {
      case "VALIDATION" -> {
        if (s(app, "status").equals("SUBMITTED")) {
          put(app, "status", "VALIDATION");
          s.validationJob(app, requestId);
        } else if ("REFINANCING".equals(s(app.get("input_data"), "purpose"))
            && !app.path("input_data").path("additionalInfo").has("refinancedDebtRub"))
          s.needsInfo(
              app,
              arr(
                  obj(
                      "path",
                      "/additionalInfo/refinancedDebtRub",
                      "message",
                      "Укажите сумму рефинансируемого долга")),
              "VALIDATION");
        else {
          put(app, "status", "IN_REVIEW");
          put(app, "review_stage", "ENRICHMENT");
          s.httpJob(
              app,
              "ENRICHMENT",
              "verification",
              "/v2/verifications",
              obj(
                  "applicationId",
                  app.get("id"),
                  "dataVersion",
                  app.get("data_version"),
                  "data",
                  app.get("input_data"),
                  "asOf",
                  s.now()),
              "VerificationResult",
              requestId);
        }
      }
      case "ENRICHMENT" -> {
        put(app, "verification_result", result);
        switch (s(result, "outcome")) {
          case "COMPLETE" -> {
            put(app, "review_stage", "SCORING");
            s.httpJob(
                app,
                "SCORING",
                "decision",
                "/v2/decisions",
                obj(
                    "applicationId",
                    app.get("id"),
                    "dataVersion",
                    app.get("data_version"),
                    "data",
                    app.get("input_data"),
                    "verification",
                    result),
                "DecisionResult",
                requestId);
          }
          case "NEEDS_INFO" -> s.needsInfo(app, result.get("requirements"), "ENRICHMENT");
          case "MANUAL_REVIEW" -> manualJob(app, requestId);
          default -> throw new Dependencies.Failure("DEPENDENCY_PROTOCOL_ERROR", false);
        }
      }
      case "SCORING" -> {
        put(app, "decision_result", result);
        if (s(result, "outcome").equals("MANUAL_REVIEW")) manualJob(app, requestId);
        else decision(app, result);
      }
      case "MANUAL_CASE" -> {
        put(app, "status", "MANUAL_REVIEW");
        put(app, "review_stage", null);
        put(app, "manual_review_snapshot", result);
      }
      case "MANUAL_ACTION" -> {
        var review = copy(app.get("manual_review_snapshot"));
        put(review, "status", "RESOLVED");
        put(review, "allowedActions", arr());
        put(app, "manual_review_snapshot", review);
        switch (s(result, "action")) {
          case "APPROVE", "REJECT" -> decision(app, result.get("decision"));
          case "REQUEST_INFO" -> s.needsInfo(app, result.get("requirements"), "STAFF");
          case "AMEND_DATA" -> {
            var data = copy(job.path("request_payload").get("amendedData"));
            if (has(result, "factsResolution"))
              put(
                  data,
                  "staffAttestation",
                  obj(
                      "caseActionId",
                      result.get("id"),
                      "staffId",
                      result.get("staffId"),
                      "reason",
                      result.get("reason"),
                      "recordedAt",
                      result.get("createdAt"),
                      "confirmedFacts",
                      result.path("factsResolution").get("confirmedFacts")));
            Contract.validate("SubmittedData", data);
            s.newVersion(
                app, data, "STAFF_AMENDMENT", s(result, "staffId"), "VALIDATION", requestId);
          }
          default -> throw new Dependencies.Failure("DEPENDENCY_PROTOCOL_ERROR", false);
        }
      }
      case "AGREEMENT", "SIGNATURE", "CLOSURE" -> agreement(app, job, result);
      case "PAYOUT_VALIDATION" -> {
        put(app, "payout_check_result", result);
        if (s(result, "outcome").equals("READY")) {
          String operation = s.uid();
          db.update(
              "UPDATE payout_instructions SET operation_id=? WHERE application_id=? AND"
                  + " data_version=? AND payout_version=?",
              uuid(operation),
              uuid(app, "id"),
              n(app, "data_version"),
              n(app, "current_payout_version"));
          put(app, "status", "DISBURSING");
          s.job(
              app,
              "DISBURSEMENT",
              s.envelope(
                  "disbursement",
                  "/v2/disbursements",
                  obj(
                      "operationId",
                      operation,
                      "applicationId",
                      app.get("id"),
                      "dataVersion",
                      app.get("data_version"),
                      "agreementId",
                      app.path("agreement_summary").get("id"),
                      "payoutVersion",
                      app.get("current_payout_version"),
                      "payoutCheckId",
                      result.get("id"),
                      "companyId",
                      result.get("companyId"),
                      "settlementAccountId",
                      result.get("settlementAccountId")),
                  "Disbursement",
                  requestId),
              false,
              operation,
              "disburse:" + operation);
        }
      }
      case "DISBURSEMENT" -> {
        put(app, "status", "DISBURSED");
        put(app, "disbursement_result", result);
      }
      default -> throw new Dependencies.Failure("DEPENDENCY_PROTOCOL_ERROR", false);
    }
  }

  boolean expire() {
    return db.tx(
        () -> {
          var app =
              db.one(
                  "SELECT * FROM applications WHERE action_deadline<=? AND status IN"
                      + " ('NEEDS_INFO','OFFER_READY','AWAITING_SIGNATURE') AND (current_job_id IS"
                      + " NULL OR NOT EXISTS (SELECT 1 FROM workflow_jobs j WHERE"
                      + " j.id=applications.current_job_id AND j.step='CLOSURE')) ORDER BY"
                      + " action_deadline,id FOR UPDATE SKIP LOCKED LIMIT 1",
                  s.now());
          if (app == null) return false;
          var old = app.deepCopy();
          String requestId = s.uid();
          if (s(app, "status").equals("AWAITING_SIGNATURE"))
            s.requestClosure(app, "EXPIRED", requestId);
          else
            s.terminate(
                app,
                "EXPIRED",
                s(app, "status").equals("NEEDS_INFO") ? "INFO_TIMEOUT" : "OFFER_TIMEOUT",
                requestId);
          s.save(app, old, "Истек срок действия " + s(old, "deadline_kind"));
          return true;
        });
  }

  public boolean tick() {
    boolean progress = expire();
    progress = recover() || progress;
    var job = claim();
    if (job == null) return progress;
    try {
      apply(job, execute(job));
    } catch (Dependencies.Failure ex) {
      fail(job, ex);
    } catch (Problem ex) {
      fail(job, new Dependencies.Failure("DEPENDENCY_PROTOCOL_ERROR", false));
    }
    return true;
  }

  @Override
  public void run() {
    while (!stopping) {
      try {
        if (tick()) continue;
      } catch (RuntimeException ex) {
        LOG.error("worker_tick_failed code=INTERNAL_ERROR");
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
