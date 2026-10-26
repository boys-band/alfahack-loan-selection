package ru.alfa.applications;

import static ru.alfa.applications.Database.*;
import static ru.alfa.applications.Json.*;
import static ru.alfa.applications.Problem.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class Service extends Store {
  final Settings settings;
  final Dependencies dependencies;

  public record Actor(String type, String id) {}

  public record Reply(int status, JsonNode body, Map<String, String> headers) {
    public Reply(JsonNode body) {
      this(200, body, Map.of());
    }
  }

  private record Identity(String caller, String method, String path, String key, String hash) {}

  public Service(
      Settings settings, Database db, Dependencies dependencies, Clock clock, Supplier<UUID> ids) {
    super(db, clock, ids);
    this.settings = settings;
    this.dependencies = dependencies;
  }

  public Actor authenticate(String header) {
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      if (secureEquals(token, settings.customerToken()))
        return new Actor("CUSTOMER", settings.customerId());
      if (secureEquals(token, settings.staffToken())) return new Actor("STAFF", settings.staffId());
      if (secureEquals(token, settings.internalToken())) return new Actor("INTERNAL", null);
      if (secureEquals(token, settings.adminToken())) return new Actor("ADMIN", null);
    }
    throw new Problem(401, "UNAUTHENTICATED", "A valid bearer token is required");
  }

  private boolean secureEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  ObjectNode access(String id, Actor actor, boolean lock) {
    var app = load(id, lock);
    if (app == null
        || (actor.type().equals("CUSTOMER") && !actor.id().equals(s(app, "customer_id"))))
      throw new Problem(404, "NOT_FOUND", "Application was not found");
    return app;
  }

  JsonNode company(String id, Actor actor) {
    var row =
        db.one(
            "SELECT c.* FROM companies c JOIN customer_companies cc ON c.id=cc.company_id WHERE"
                + " c.id=? AND cc.customer_id=?",
            uuid(id),
            uuid(actor.id()));
    if (row == null) throw new Problem(404, "NOT_FOUND", "Company was not found");
    return companyDto(row);
  }

  JsonNode product(String id, String version, String requestId, boolean requireCurrent) {
    JsonNode result = readProduct(id, requireCurrent ? null : version, requestId);
    require(
        version.equals(s(result, "version")),
        409,
        "PRODUCT_NOT_CURRENT",
        "Условия программы изменились. Проверьте новые условия и сохраните черновик перед отправкой.");
    return result;
  }

  JsonNode readProduct(String id, String version, String requestId) {
    JsonNode result;
    try {
      result =
          dependencies.get(
              "catalog",
              "/v2/products/" + id + (version == null ? "" : "?version=" + version),
              requestId,
              "Product");
    } catch (Dependencies.Failure ex) {
      if (ex.status == 404)
        throw new Problem(
            409, "PRODUCT_NOT_CURRENT", "Программа больше недоступна для новых заявок.");
      throw unavailable("Catalog is unavailable");
    }
    require(
        id.equals(s(result, "id")),
        409,
        "PRODUCT_NOT_CURRENT",
        "Программа недоступна. Обновите данные.");
    return result;
  }

  static Problem unavailable(String detail) {
    return new Problem(503, "DEPENDENCY_UNAVAILABLE", detail, arr(), 1);
  }

  void validateTerms(JsonNode data, boolean complete) {
    ((ObjectNode) data).setAll((ObjectNode) integralNumbers(data));
    JsonNode product = data.get("product");
    ArrayNode errors = arr();
    for (String field : List.of("amountRub", "termMonths")) {
      String prefix = field.equals("amountRub") ? "AmountRub" : "TermMonths";
      long min = n(product, "min" + prefix), max = n(product, "max" + prefix);
      if (has(data, field) && (n(data, field) < min || n(data, field) > max)) {
        String message =
            field.equals("amountRub")
                ? "Сумма кредита должна быть от "
                    + String.format(Locale.ROOT, "%,d", min).replace(',', ' ')
                    + " до "
                    + String.format(Locale.ROOT, "%,d", max).replace(',', ' ')
                    + " ₽."
                : "Срок кредита должен быть от " + min + " до " + max + " мес.";
        errors.add(obj("path", "/" + field, "message", message));
      }
    }
    if (has(data, "purpose")) {
      boolean supported = false;
      for (JsonNode purpose : product.get("purposeCodes"))
        if (purpose.equals(data.get("purpose"))) supported = true;
      if (!supported)
        errors.add(
            obj(
                "path", "/purpose",
                "message", "Выберите цель кредита, доступную для выбранного продукта."));
    }
    if (!errors.isEmpty()) {
      StringJoiner detail = new StringJoiner(" ");
      errors.forEach(error -> detail.add(s(error, "message")));
      throw new Problem(422, "VALIDATION_ERROR", detail.toString(), errors, 0);
    }
    if (complete) Contract.validate("SubmittedData", data);
  }

  ObjectNode changes(JsonNode data, JsonNode changes, JsonNode product) {
    var result = copy(data);
    changes
        .fields()
        .forEachRemaining(
            e -> {
              if (!Set.of("productId", "productVersion").contains(e.getKey()))
                result.set(e.getKey(), e.getValue());
            });
    if (product != null) result.set("product", product);
    return (ObjectNode) integralNumbers(result);
  }

  void precondition(ObjectNode app, String match) {
    if (match == null) throw new Problem(428, "PRECONDITION_REQUIRED", "If-Match is required");
    require(
        match.matches("\"[0-9a-f]{64}\""),
        422,
        "VALIDATION_ERROR",
        "If-Match must be a quoted SHA-256 ETag");
    require(match.equals(etag(project(app))), 412, "VERSION_CONFLICT", "Application ETag changed");
  }

  private Reply replay(Identity identity) {
    var row =
        db.one(
            "SELECT * FROM idempotency_records WHERE caller=? AND method=? AND path=? AND"
                + " idempotency_key=?",
            identity.caller,
            identity.method,
            identity.path,
            identity.key);
    if (row == null) return null;
    require(
        identity.hash.equals(s(row, "request_hash")),
        409,
        "IDEMPOTENCY_CONFLICT",
        "Key was used for a different request");
    Map<String, String> headers = new HashMap<>();
    row.path("response_headers")
        .fields()
        .forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
    return new Reply((int) n(row, "response_status"), row.get("response_body"), headers);
  }

  public Reply mutate(
      String operation,
      String method,
      String path,
      String appId,
      Actor actor,
      JsonNode body,
      boolean present,
      Map<String, String> headers,
      String requestId) {
    String key = headers.get("idempotency-key");
    require(
        key != null && key.matches("[A-Za-z0-9:_.-]{8,128}"),
        422,
        "VALIDATION_ERROR",
        "Idempotency-Key must contain 8–128 allowed characters");
    var identity =
        new Identity(
            actor.type() + ":" + actor.id(),
            method,
            path,
            key,
            sha(
                canonical(
                    obj(
                        "method",
                        method,
                        "path",
                        path,
                        "bodyPresent",
                        present,
                        "body",
                        present ? body : null,
                        "ifMatch",
                        headers.get("if-match")))));
    Reply previous =
        db.tx(
            () -> {
              if (appId != null) access(appId, actor, false);
              if (operation.equals("createApplication")) {
                Contract.validate("CreateApplicationRequest", body);
                company(s(body, "companyId"), actor);
              }
              return replay(identity);
            });
    if (previous != null) return previous;
    String schema =
        switch (operation) {
          case "createApplication" -> "CreateApplicationRequest";
          case "updateDraft" -> "UpdateDraftRequest";
          case "submitApplication" -> "SubmitRequest";
          case "provideInformation" -> "ProvideInfoRequest";
          case "changeTerms" -> "ChangeTermsRequest";
          case "acceptOffer" -> "AcceptOfferRequest";
          case "signApplicationAgreement" -> "PublicSignRequest";
          case "cancelApplication" -> "CancelRequest";
          case "updatePayoutAccount" -> "UpdatePayoutAccountRequest";
          case "submitStaffReviewAction" -> "StaffReviewActionRequest";
          default -> null;
        };
    if (schema != null) Contract.validate(schema, body);
    else require(!present, 422, "VALIDATION_ERROR", "This command has no request body");
    boolean needsProduct =
        Set.of(
                    "createApplication",
                    "updateDraft",
                    "submitApplication",
                    "provideInformation",
                    "changeTerms")
                .contains(operation)
            || (operation.equals("submitStaffReviewAction")
                && "AMEND_DATA".equals(s(body, "action")));
    JsonNode product = null;
    if (needsProduct) {
      Reply[] replayed = new Reply[1];
      var initial =
          appId == null
              ? null
              : db.tx(
                  () -> {
                    var app = access(appId, actor, false);
                    replayed[0] = replay(identity);
                    if (replayed[0] == null) precondition(app, headers.get("if-match"));
                    return app;
                  });
      if (replayed[0] != null) return replayed[0];
      JsonNode proposed =
          Set.of("provideInformation", "submitStaffReviewAction").contains(operation)
              ? body.path("changes")
              : body;
      if (!proposed.isObject()) proposed = obj();
      String productId =
          has(proposed, "productId")
              ? s(proposed, "productId")
              : s(initial.path("input_data").path("product"), "id");
      String productVersion =
          has(proposed, "productVersion")
              ? s(proposed, "productVersion")
              : s(initial.path("input_data").path("product"), "version");
      boolean requireCurrent =
          initial == null
              || operation.equals("submitApplication") && "DRAFT".equals(s(initial, "status"))
              || !productId.equals(s(initial.path("input_data").path("product"), "id"))
              || !productVersion.equals(s(initial.path("input_data").path("product"), "version"));
      product =
          operation.equals("updateDraft") && "DRAFT".equals(s(initial, "status"))
              ? readProduct(productId, null, requestId)
              : product(productId, productVersion, requestId, requireCurrent);
    }
    JsonNode currentProduct = product;
    long lockId =
        new java.math.BigInteger(
                sha(identity.caller + "|" + method + "|" + path + "|" + key).substring(0, 16), 16)
            .longValue();
    return db.tx(
        () -> {
          db.rows("SELECT pg_advisory_xact_lock(?)", lockId);
          ObjectNode app = appId == null ? null : access(appId, actor, true);
          JsonNode company =
              operation.equals("createApplication") ? company(s(body, "companyId"), actor) : null;
          Reply cached = replay(identity);
          if (cached != null) return cached;
          if (app != null) precondition(app, headers.get("if-match"));
          int status;
          if (operation.equals("createApplication")) {
            var data =
                obj(
                    "company",
                    company,
                    "product",
                    currentProduct,
                    "amountRub",
                    body.get("amountRub"),
                    "termMonths",
                    body.get("termMonths"),
                    "purpose",
                    body.get("purpose"),
                    "financialData",
                    body.get("financialData"),
                    "additionalInfo",
                    body.has("additionalInfo") ? body.get("additionalInfo") : obj(),
                    "consents",
                    null,
                    "staffAttestation",
                    null);
            validateTerms(data, false);
            app =
                db.one(
                    "INSERT INTO applications(id,customer_id,input_data,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?) RETURNING *",
                    uuid(uid()),
                    uuid(actor.id()),
                    data,
                    now(),
                    now());
            timeline(app, null, actor.type(), actor.id(), "DRAFT_CREATED", "Черновик создан");
            status = 201;
          } else {
            var old = app.deepCopy();
            Command command = command(app, operation, body, currentProduct, actor, requestId);
            status = command.status;
            save(app, old, actor.type(), actor.id(), command.event, command.reason);
          }
          JsonNode result = project(app);
          var responseHeaders = Map.of("ETag", etag(result), "Cache-Control", "no-store");
          db.update(
              "INSERT INTO"
                  + " idempotency_records(caller,method,path,idempotency_key,request_hash,response_status,response_headers,response_body,created_at,expires_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)",
              identity.caller,
              method,
              path,
              key,
              identity.hash,
              status,
              node(responseHeaders),
              result,
              now(),
              now().plus(Duration.ofHours(48)));
          return new Reply(status, result, responseHeaders);
        });
  }

  private record Command(int status, String event, String reason) {}

  void deadline(JsonNode app) {
    require(
        !has(app, "action_deadline") || now().isBefore(instant(app, "action_deadline")),
        409,
        "DEADLINE_EXPIRED",
        "The action deadline has passed");
  }

  void idle(JsonNode app) {
    require(
        !has(app, "current_job_id"),
        409,
        "OPERATION_IN_PROGRESS",
        "Another command is being processed");
  }

  private Command command(
      ObjectNode app,
      String operation,
      JsonNode body,
      JsonNode product,
      Actor actor,
      String requestId) {
    String state = s(app, "status");
    int status = 202;
    String event = "COMMAND_ACCEPTED", reason = "Команда принята";
    switch (operation) {
      case "updateDraft" -> {
        state(state.equals("DRAFT"));
        var data = changes(app.get("input_data"), body, product);
        validateTerms(data, false);
        put(app, "input_data", data);
        status = 200;
        event = "DRAFT_UPDATED";
        reason = "Черновик изменен";
      }
      case "submitApplication" -> {
        state(state.equals("DRAFT"));
        var data = copy(app.get("input_data"));
        put(data, "product", product);
        var consents = copy(body);
        put(consents, "acceptedAt", now());
        put(data, "consents", consents);
        validateTerms(data, true);
        newVersion(app, data, "INITIAL_SUBMISSION", actor.id(), "SUBMITTED", requestId);
        event = "DATA_VERSION_CREATED";
        reason = "Заявка отправлена";
      }
      case "provideInformation", "changeTerms" -> {
        boolean info = operation.equals("provideInformation");
        state(state.equals(info ? "NEEDS_INFO" : "OFFER_READY"));
        deadline(app);
        idle(app);
        JsonNode modifications = info ? body.get("changes") : body;
        var data = changes(app.get("input_data"), modifications, product);
        require(
            !data.equals(app.get("input_data")),
            422,
            "VALIDATION_ERROR",
            "At least one actual change is required");
        if (info) {
          var request =
              db.one(
                  "SELECT * FROM information_requests WHERE id=?",
                  uuid(app, "current_information_request_id"));
          require(
              s(body, "informationRequestId").equals(s(request, "id")),
              422,
              "SNAPSHOT_MISMATCH",
              "Information request is not current");
          for (JsonNode requirement : request.get("requirements")) {
            JsonNode supplied;
            try {
              supplied = modifications.at(s(requirement, "path"));
            } catch (IllegalArgumentException ex) {
              throw new Problem(422, "VALIDATION_ERROR", "Unsupported information pointer");
            }
            require(
                !supplied.isMissingNode() && !supplied.isNull(),
                422,
                "VALIDATION_ERROR",
                "All requested fields must be supplied");
          }
          db.update(
              "UPDATE information_requests SET status='ANSWERED',completed_at=? WHERE id=?",
              now(),
              uuid(request, "id"));
        } else
          require(
              !data.get("amountRub").equals(app.path("input_data").get("amountRub"))
                  || !data.get("termMonths").equals(app.path("input_data").get("termMonths"))
                  || !data.get("product").equals(app.path("input_data").get("product")),
              422,
              "VALIDATION_ERROR",
              "Loan amount, term or product must actually change");
        validateTerms(data, true);
        newVersion(
            app,
            data,
            info ? "CUSTOMER_INFO" : "CUSTOMER_TERMS",
            actor.id(),
            info ? "SUBMITTED" : "VALIDATION",
            requestId);
        event = "DATA_VERSION_CREATED";
        reason = "Создана новая версия исходных данных";
      }
      case "acceptOffer" -> {
        state(state.equals("OFFER_READY"));
        deadline(app);
        idle(app);
        var offer = app.path("decision_result").get("offer");
        require(
            s(body, "offerId").equals(s(offer, "id"))
                && n(body, "offerVersion") == n(offer, "version")
                && n(body, "dataVersion") == n(app, "data_version"),
            409,
            "OFFER_NOT_CURRENT",
            "Offer identifiers do not match the current offer");
        String id = uid();
        // PostgreSQL timestamptz stores microseconds. Use the same value in the
        // immutable HTTP job and acceptance row so agreements can echo it exactly.
        Instant acceptedAt = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update(
            "INSERT INTO"
                + " offer_acceptances(id,application_id,data_version,offer_id,offer_version,offer_snapshot,accepted_at)"
                + " VALUES(?,?,?,?,?,?,?)",
            uuid(id),
            uuid(app, "id"),
            n(app, "data_version"),
            uuid(offer, "id"),
            n(offer, "version"),
            offer,
            acceptedAt);
        put(app, "status", "OFFER_ACCEPTED");
        put(app, "current_acceptance_id", id);
        clearDeadline(app);
        httpJob(
            app,
            "AGREEMENT",
            "agreements",
            "/v2/agreements",
            obj(
                "applicationId",
                app.get("id"),
                "dataVersion",
                app.get("data_version"),
                "customerId",
                app.get("customer_id"),
                "company",
                app.path("input_data").get("company"),
                "offer",
                offer,
                "acceptedAt",
                acceptedAt),
            "Agreement",
            requestId);
        reason = "Предложение принято";
      }
      case "signApplicationAgreement" -> {
        state(state.equals("AWAITING_SIGNATURE"));
        deadline(app);
        idle(app);
        var agreement = app.get("agreement_summary");
        require(
            s(body, "agreementId").equals(s(agreement, "id"))
                && n(body, "documentVersion") == n(agreement, "documentVersion")
                && s(body, "documentSha256").equals(s(agreement, "documentSha256")),
            409,
            "DOCUMENT_MISMATCH",
            "Document does not match the current agreement");
        var offer = app.path("decision_result").get("offer");
        httpJob(
            app,
            "SIGNATURE",
            "agreements",
            "/v2/agreements/" + s(agreement, "id") + "/sign",
            obj(
                "applicationId",
                app.get("id"),
                "dataVersion",
                app.get("data_version"),
                "offerId",
                offer.get("id"),
                "offerVersion",
                offer.get("version"),
                "customerId",
                app.get("customer_id"),
                "documentVersion",
                body.get("documentVersion"),
                "documentSha256",
                body.get("documentSha256"),
                "consent",
                true),
            "Agreement",
            requestId);
        reason = "Подпись принята в обработку";
      }
      case "cancelApplication" -> {
        state(CANCELLABLE.contains(state));
        if (state.equals("AWAITING_SIGNATURE")) requestClosure(app, "CANCELLED", requestId);
        else {
          terminate(app, "CANCELLED", "CUSTOMER_CANCELLED", requestId);
          status = 200;
        }
        reason = s(body, "reason");
      }
      case "updatePayoutAccount" -> {
        state(state.equals("SIGNED"));
        var current =
            db.one(
                "SELECT * FROM payout_instructions WHERE application_id=? AND data_version=? AND"
                    + " payout_version=?",
                uuid(app, "id"),
                n(app, "data_version"),
                n(app, "current_payout_version"));
        require(!has(current, "operation_id"), 409, "PAYOUT_NOT_READY", "Payout is already frozen");
        require(
            !s(current, "settlement_account_id").equals(s(body, "settlementAccountId")),
            422,
            "VALIDATION_ERROR",
            "A different account must be supplied");
        detach(app, requestId, "SUPERSEDED");
        put(app, "current_payout_version", n(app, "current_payout_version") + 1);
        put(app, "payout_check_result", null);
        db.update(
            "INSERT INTO"
                + " payout_instructions(application_id,data_version,payout_version,settlement_account_id,created_at)"
                + " VALUES(?,?,?,?,?)",
            uuid(app, "id"),
            n(app, "data_version"),
            n(app, "current_payout_version"),
            uuid(body, "settlementAccountId"),
            now());
        payoutJob(app, s(body, "settlementAccountId"), requestId);
        event = "PAYOUT_UPDATED";
        reason = "Реквизиты поручения изменены";
      }
      case "retryProcessing" -> {
        state(has(app, "technical_error") && has(app, "current_job_id"));
        var job =
            db.one(
                "SELECT * FROM workflow_jobs WHERE id=? FOR UPDATE", uuid(app, "current_job_id"));
        state("PAUSED".equals(s(job, "state")));
        db.update(
            "UPDATE workflow_jobs SET state='PENDING',attempt_count=0,next_attempt_at=? WHERE id=?",
            now(),
            uuid(job, "id"));
        put(app, "technical_error", null);
        event = "PROCESSING_RETRIED";
        reason = "Сохраненный этап возобновлен";
      }
      case "submitStaffReviewAction" -> {
        state(state.equals("MANUAL_REVIEW"));
        idle(app);
        var review = app.get("manual_review_snapshot");
        var decision = app.get("decision_result");
        var verification = app.get("verification_result");
        state("OPEN".equals(s(review, "status")));
        if (s(body, "action").equals("APPROVE")) {
          require(
              has(review, "assessmentId")
                  && has(app, "decision_result")
                  && verification.path("confirmed").asBoolean()
                  && "COMPLETE".equals(s(verification, "outcome"))
                  && s(review, "assessmentId").equals(s(decision, "assessmentId")),
              409,
              "SCORING_REQUIRED",
              "Current confirmed facts and a completed assessment are required");
          boolean evaluated = false;
          for (JsonNode term : decision.get("evaluatedTerms"))
            if (s(term, "id").equals(s(body, "termsId"))) evaluated = true;
          require(
              evaluated,
              422,
              "SNAPSHOT_MISMATCH",
              "Terms were not evaluated for the current version");
        }
        boolean allowed = false;
        for (JsonNode action : review.get("allowedActions"))
          if (action.asText().equals(s(body, "action"))) allowed = true;
        require(allowed, 409, "INVALID_STATE", "Action is not allowed by the current case");
        if (has(body, "factsResolution"))
          require(
              s(body.get("factsResolution"), "verificationId").equals(s(verification, "id")),
              422,
              "SNAPSHOT_MISMATCH",
              "Facts refer to another verification");
        JsonNode amended = null;
        if (s(body, "action").equals("AMEND_DATA")) {
          amended =
              changes(
                  app.get("input_data"),
                  has(body, "changes") ? body.get("changes") : obj(),
                  product);
          require(
              !amended.equals(app.get("input_data")) || has(body, "factsResolution"),
              422,
              "VALIDATION_ERROR",
              "Amendment must change data or resolve facts");
          validateTerms(amended, true);
        }
        var action = copy(body);
        put(action, "actionId", uid());
        put(action, "staffId", actor.id());
        var envelope =
            envelope(
                "decision",
                "/v2/manual-reviews/" + s(review, "id") + "/actions",
                action,
                "ManualReviewActionResult",
                requestId);
        put(envelope, "amendedData", amended);
        job(app, "MANUAL_ACTION", envelope);
        reason = s(body, "reason");
      }
      default -> throw new Problem(404, "NOT_FOUND", "Operation was not found");
    }
    return new Command(status, event, reason);
  }

  String cursor(Instant createdAt, String id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(canonical(arr(createdAt, id)).getBytes(StandardCharsets.UTF_8));
  }

  JsonNode decodeCursor(String value) {
    try {
      var result = parse(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
      if (!result.isArray() || result.size() != 2) throw new IllegalArgumentException();
      Contract.validate("Timestamp", result.get(0));
      Contract.validate("Uuid", result.get(1));
      return result;
    } catch (IllegalArgumentException | Problem ex) {
      throw new Problem(422, "VALIDATION_ERROR", "Invalid cursor");
    }
  }

  private Reply staffApplications(Actor actor, Map<String, String> query) {
    require(actor.type().equals("STAFF"), 403, "FORBIDDEN", "Staff access is required");
    require(
        Set.of("limit", "cursor", "status", "approvalType").containsAll(query.keySet()),
        422,
        "VALIDATION_ERROR",
        "Unknown query parameter");
    String rawLimit = query.getOrDefault("limit", "20");
    int limit;
    try {
      limit = Integer.parseInt(rawLimit);
    } catch (NumberFormatException ex) {
      limit = 0;
    }
    require(
        rawLimit.matches("[0-9]+") && limit >= 1 && limit <= 100,
        422,
        "VALIDATION_ERROR",
        "limit must be between 1 and 100");
    String status = query.get("status"), approvalType = query.get("approvalType");
    if (status != null) Contract.validate("ApplicationStatus", node(status));
    require(
        approvalType == null || Set.of("AUTO", "MANUAL").contains(approvalType),
        422,
        "VALIDATION_ERROR",
        "Invalid approvalType");
    String condition = "true";
    List<Object> params = new ArrayList<>();
    if (status != null) {
      condition += " AND status=?";
      params.add(status);
    }
    if (approvalType != null) {
      condition +=
          " AND decision_result->>'kind'=? AND decision_result->>'outcome'='APPROVED'"
              + " AND (decision_result->>'dataVersion')::bigint=data_version";
      params.add(approvalType);
    }
    if (query.containsKey("cursor")) {
      var cursor = decodeStaffCursor(query.get("cursor"), status, approvalType);
      condition += " AND (created_at,id)<(?,?)";
      params.add(instant(cursor, "createdAt"));
      params.add(uuid(s(cursor, "id")));
    }
    params.add(limit + 1);
    var rows =
        db.rows(
            "SELECT * FROM applications WHERE "
                + condition
                + " ORDER BY created_at DESC,id DESC LIMIT ?",
            params.toArray());
    var page = rows.subList(0, Math.min(limit, rows.size()));
    var items = arr();
    page.forEach(row -> items.add(project(row)));
    String next = null;
    if (rows.size() > limit) {
      var last = page.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  canonical(
                          obj(
                              "v",
                              1,
                              "createdAt",
                              last.get("created_at"),
                              "id",
                              last.get("id"),
                              "status",
                              status,
                              "approvalType",
                              approvalType))
                      .getBytes(StandardCharsets.UTF_8));
    }
    return new Reply(
        200, obj("items", items, "nextCursor", next), Map.of("Cache-Control", "no-store"));
  }

  JsonNode decodeStaffCursor(String value, String status, String approvalType) {
    try {
      if (value == null || value.length() > 2048 || !value.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      var cursor = parse(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
      if (!cursor.isObject()
          || cursor.size() != 5
          || !cursor.path("v").isIntegralNumber()
          || !cursor.path("v").canConvertToInt()
          || cursor.path("v").asInt() != 1
          || !cursor.has("status")
          || !cursor.has("approvalType")
          || !node(status).equals(cursor.get("status"))
          || !node(approvalType).equals(cursor.get("approvalType")))
        throw new IllegalArgumentException();
      Contract.validate("Timestamp", cursor.path("createdAt"));
      Contract.validate("Uuid", cursor.path("id"));
      return cursor;
    } catch (IllegalArgumentException | Problem ex) {
      throw new Problem(422, "VALIDATION_ERROR", "Invalid staff cursor or cursor filters");
    }
  }

  public Reply read(
      String operation,
      String appId,
      String companyId,
      Long dataVersion,
      Actor actor,
      Map<String, String> query,
      String requestId) {
    Reply local =
        db.tx(
            () -> {
              db.update("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              switch (operation) {
                case "listStaffApplications" -> {
                  return staffApplications(actor, query);
                }
                case "listCompanies" -> {
                  var result = arr();
                  db.rows(
                          "SELECT c.* FROM companies c JOIN customer_companies cc ON"
                              + " c.id=cc.company_id WHERE cc.customer_id=? ORDER BY c.id",
                          uuid(actor.id()))
                      .forEach(r -> result.add(companyDto(r)));
                  return new Reply(obj("items", result));
                }
                case "getCompany", "listCompanySettlementAccounts" -> {
                  JsonNode company = company(companyId, actor);
                  if (operation.equals("getCompany")) return new Reply(company);
                  return null;
                }
                case "listApplications", "listStaffManualReviews" -> {
                  String rawLimit = query.getOrDefault("limit", "20");
                  int limit;
                  try {
                    limit = Integer.parseInt(rawLimit);
                  } catch (NumberFormatException ex) {
                    limit = 0;
                  }
                  require(
                      rawLimit.matches("[0-9]+") && limit >= 1 && limit <= 100,
                      422,
                      "VALIDATION_ERROR",
                      "limit must be between 1 and 100");
                  boolean staff = operation.equals("listStaffManualReviews");
                  String timeColumn =
                      staff ? "(manual_review_snapshot->>'createdAt')::timestamptz" : "created_at";
                  String idColumn = staff ? "(manual_review_snapshot->>'id')::uuid" : "id";
                  String condition =
                      staff
                          ? "status='MANUAL_REVIEW' AND manual_review_snapshot->>'status'='OPEN'"
                              + " AND (manual_review_snapshot->>'dataVersion')::bigint=data_version"
                          : "customer_id=?";
                  List<Object> params = new ArrayList<>();
                  if (!staff) params.add(uuid(actor.id()));
                  if (query.containsKey("cursor")) {
                    var cursor = decodeCursor(query.get("cursor"));
                    condition += " AND (" + timeColumn + "," + idColumn + ") < (?,?)";
                    params.add(Instant.parse(cursor.get(0).asText()));
                    params.add(uuid(cursor.get(1).asText()));
                  }
                  params.add(limit + 1);
                  var records =
                      db.rows(
                          "SELECT * FROM applications WHERE "
                              + condition
                              + " ORDER BY "
                              + timeColumn
                              + " DESC,"
                              + idColumn
                              + " DESC LIMIT ?",
                          params.toArray());
                  boolean more = records.size() > limit;
                  records = records.subList(0, Math.min(records.size(), limit));
                  ArrayNode items = arr();
                  records.forEach(
                      r -> items.add(staff ? r.get("manual_review_snapshot") : project(r)));
                  String nextCursor = null;
                  if (more) {
                    JsonNode last = records.getLast();
                    nextCursor =
                        staff
                            ? cursor(
                                instant(last.get("manual_review_snapshot"), "createdAt"),
                                s(last.get("manual_review_snapshot"), "id"))
                            : cursor(instant(last, "created_at"), s(last, "id"));
                  }
                  return new Reply(
                      200,
                      obj("items", items, "nextCursor", nextCursor),
                      Map.of("Cache-Control", "no-store"));
                }
                default -> {}
              }
              var app = access(appId, actor, false);
              switch (operation) {
                case "getApplication" -> {
                  var result = project(app);
                  return new Reply(
                      200, result, Map.of("ETag", etag(result), "Cache-Control", "no-store"));
                }
                case "getDataVersion" -> {
                  var row =
                      db.one(
                          "SELECT * FROM application_data_versions WHERE application_id=? AND"
                              + " data_version=?",
                          uuid(appId),
                          dataVersion);
                  if (row == null)
                    throw new Problem(404, "NOT_FOUND", "Data version was not found");
                  return new Reply(
                      obj(
                          "applicationId",
                          appId,
                          "dataVersion",
                          row.get("data_version"),
                          "data",
                          row.get("payload"),
                          "createdAt",
                          row.get("created_at"),
                          "origin",
                          row.get("origin"),
                          "actorId",
                          row.get("actor_id")));
                }
                case "getTimeline" -> {
                  var items = arr();
                  for (var row :
                      db.rows(
                          "SELECT * FROM application_timeline WHERE application_id=? ORDER BY"
                              + " sequence",
                          uuid(appId)))
                    items.add(
                        obj(
                            "id",
                            row.get("id"),
                            "applicationId",
                            appId,
                            "sequence",
                            row.get("sequence"),
                            "occurredAt",
                            row.get("occurred_at"),
                            "fromStatus",
                            row.get("from_status"),
                            "fromReviewStage",
                            row.get("from_review_stage"),
                            "status",
                            row.get("status"),
                            "reviewStage",
                            row.get("review_stage"),
                            "dataVersion",
                            row.get("data_version"),
                            "actor",
                            obj("type", row.get("actor_type"), "id", row.get("actor_id")),
                            "eventType",
                            row.get("event_type"),
                            "reason",
                            row.get("reason")));
                  return new Reply(obj("items", items));
                }
                case "getStaffManualReview" -> {
                  if (!has(app, "manual_review_snapshot")
                      || !"MANUAL_REVIEW".equals(s(app, "status"))
                      || !"OPEN".equals(s(app.get("manual_review_snapshot"), "status"))
                      || n(app.get("manual_review_snapshot"), "dataVersion")
                          != n(app, "data_version"))
                    throw new Problem(404, "NOT_FOUND", "Current manual review was not found");
                  return new Reply(app.get("manual_review_snapshot"));
                }
                case "getApplicationAgreement" -> {
                  if (!has(app, "agreement_summary"))
                    throw new Problem(404, "NOT_FOUND", "Agreement was not found");
                  // Internal context returned only to this method; never expose it to the caller.
                  return new Reply(0, app, Map.of());
                }
                default -> throw new Problem(404, "NOT_FOUND", "Operation was not found");
              }
            });
    if (operation.equals("listCompanySettlementAccounts")) {
      JsonNode result;
      try {
        result =
            dependencies.get(
                "disbursement", "/v2/company-accounts/" + companyId, requestId, "AccountList");
      } catch (Dependencies.Failure ex) {
        throw unavailable("Account source is unavailable");
      }
      for (var account : result.get("items"))
        if (!companyId.equals(s(account, "companyId")))
          throw unavailable("Account response does not match the company");
      return new Reply(result);
    }
    if (operation.equals("getApplicationAgreement")) {
      var app = local.body;
      JsonNode result;
      String id = s(app.get("agreement_summary"), "id");
      try {
        result = dependencies.get("agreements", "/v2/agreements/" + id, requestId, "Agreement");
      } catch (Dependencies.Failure ex) {
        throw unavailable("Agreement source is unavailable");
      }
      if (!id.equals(s(result, "id"))
          || !appId.equals(s(result, "applicationId"))
          || n(result, "dataVersion") != n(app, "data_version")
          || !s(app, "customer_id").equals(s(result, "customerId")))
        throw unavailable("Agreement response does not match the application");
      return new Reply(200, result, Map.of("Cache-Control", "no-store"));
    }
    return local;
  }
}
