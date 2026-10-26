package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only HTTP contract doubles. Credit calculations belong to the real decision service. */
final class DependencyStubs implements AutoCloseable {
  final MutableClock clock;
  final HttpServer server;
  final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final Map<String, JsonNode> idempotency = new ConcurrentHashMap<>();
  final Map<String, ObjectNode> agreements = new ConcurrentHashMap<>(),
      cases = new ConcurrentHashMap<>();
  final Map<String, JsonNode> disbursements = new ConcurrentHashMap<>();
  final List<ObjectNode> requests = new CopyOnWriteArrayList<>();
  final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
  volatile String verificationMode = "COMPLETE", decisionMode = "APPROVED";
  volatile String currentProductVersion = "1";
  volatile ObjectNode currentProductChanges = obj();
  volatile int failureStatus = 503;
  volatile String failureCode = "DEPENDENCY_UNAVAILABLE";
  volatile boolean correction, dropPayoutResponse, timeoutPayoutResponse;
  volatile String corruptField;

  DependencyStubs(MutableClock clock) throws IOException {
    this.clock = clock;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void reset() {
    verificationMode = "COMPLETE";
    decisionMode = "APPROVED";
    correction = false;
    dropPayoutResponse = false;
    corruptField = null;
    failures.clear();
    failureStatus = 503;
    failureCode = "DEPENDENCY_UNAVAILABLE";
    requests.clear();
  }

  static JsonNode template(String file, String operation) {
    for (var exchange : resource("/fixtures/" + file + ".json").get("exchanges"))
      if (s(exchange, "operationId").equals(operation))
        return exchange.path("response").get("body").deepCopy();
    throw new IllegalArgumentException(operation);
  }

  JsonNode templateAction(String action) {
    for (var exchange : resource("/fixtures/manual-review.json").get("exchanges"))
      if (s(exchange, "operationId").equals("recordManualReviewAction")
          && action.equals(s(exchange.path("response").get("body"), "action")))
        return exchange.path("response").get("body").deepCopy();
    throw new IllegalArgumentException(action);
  }

  private void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
    byte[] bytes = canonical(body).getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .set("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void error(HttpExchange exchange, int status, String code) throws IOException {
    send(
        exchange,
        status,
        new Problem(status, code, "Synthetic dependency error")
            .body(exchange.getRequestHeaders().getFirst("X-Request-Id")));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"Bearer demo-internal-only"
          .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        error(exchange, 401, "UNAUTHENTICATED");
        return;
      }
      Contract.validate("Uuid", node(exchange.getRequestHeaders().getFirst("X-Request-Id")));
      String path = exchange.getRequestURI().getPath(),
          method = exchange.getRequestMethod(),
          key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode body = raw.isEmpty() ? null : parse(raw);
      requests.add(
          obj(
              "method",
              method,
              "path",
              path,
              "key",
              key,
              "body",
              body,
              "requestId",
              exchange.getRequestHeaders().getFirst("X-Request-Id")));
      var remaining = failures.get(path);
      if (remaining != null && remaining.getAndDecrement() > 0) {
        exchange.getResponseHeaders().set("Retry-After", "2");
        error(exchange, failureStatus, failureCode);
        return;
      }
      String idem = method + ":" + path + ":" + key;
      if (method.equals("POST") && idempotency.containsKey(idem)) {
        send(exchange, 200, idempotency.get(idem));
        return;
      }
      JsonNode result;
      int status = 200;
      if (method.equals("GET")) result = get(path, exchange.getRequestURI().getQuery());
      else {
        if (key == null || !key.matches("[A-Za-z0-9:_.-]{8,128}"))
          throw new IllegalStateException("Missing internal idempotency key");
        result = post(path, body);
        idempotency.put(idem, result.deepCopy());
        status = path.endsWith("/sign") || path.endsWith("/close") ? 200 : 201;
        if (path.equals("/v2/disbursements") && dropPayoutResponse) {
          dropPayoutResponse = false;
          exchange.close();
          return;
        }
        if (path.equals("/v2/disbursements") && timeoutPayoutResponse) {
          timeoutPayoutResponse = false;
          Thread.sleep(11000);
        }
      }
      if (corruptField != null && result.isObject() && ((ObjectNode) result).has(corruptField)) {
        result = result.deepCopy();
        put((ObjectNode) result, corruptField, UUID.randomUUID().toString());
      }
      send(exchange, status, result);
    } catch (Problem ex) {
      error(exchange, ex.status, ex.code);
    } catch (Exception ex) {
      send(
          exchange,
          500,
          obj("code", "STUB_ASSERTION_FAILED", "detail", ex.getClass().getSimpleName()));
    }
  }

  private JsonNode get(String path, String query) {
    if (path.startsWith("/v2/products/")) {
      var product = copy(template("approved", "listProducts").get("items").get(0));
      if (path.endsWith("equipment-finance")) {
        put(product, "id", "equipment-finance");
        put(product, "purposeCodes", arr("EQUIPMENT"));
      } else if (!path.endsWith("working-capital"))
        throw new Problem(404, "NOT_FOUND", "Unknown product");
      String version = currentProductVersion;
      if (query != null)
        for (String parameter : query.split("&"))
          if (parameter.startsWith("version=")) version = parameter.substring(8);
      if (!version.equals("1") && !version.equals(currentProductVersion))
        throw new Problem(404, "NOT_FOUND", "Unknown product version");
      put(product, "version", version);
      if (version.equals(currentProductVersion))
        ((ObjectNode) product).setAll(currentProductChanges);
      return product;
    }
    if (path.startsWith("/v2/company-accounts/")) {
      String company = path.substring(path.lastIndexOf('/') + 1);
      return obj(
          "items",
          arr(
              obj(
                  "id",
                  "00000000-0000-4000-8000-000000001101",
                  "companyId",
                  company,
                  "label",
                  "Демосчет",
                  "status",
                  "ACTIVE")));
    }
    if (path.startsWith("/v2/agreements/")) {
      var result = agreements.get(path.substring(path.lastIndexOf('/') + 1));
      if (result != null) return result.deepCopy();
    }
    if (path.startsWith("/v2/disbursements/by-operation/")) {
      var result = disbursements.get(path.substring(path.lastIndexOf('/') + 1));
      if (result != null) return result.deepCopy();
    }
    throw new Problem(404, "NOT_FOUND", "Resource was not found");
  }

  private JsonNode version(JsonNode template, JsonNode body) {
    JsonNode result = template.deepCopy();
    rewrite(result, s(body, "applicationId"), n(body, "dataVersion"));
    return result;
  }

  private void rewrite(JsonNode result, String applicationId, long dataVersion) {
    if (result.isObject()) {
      var object = (ObjectNode) result;
      if (object.has("applicationId")) put(object, "applicationId", applicationId);
      if (object.has("dataVersion")) put(object, "dataVersion", dataVersion);
      object.elements().forEachRemaining(child -> rewrite(child, applicationId, dataVersion));
    } else if (result.isArray())
      result.forEach(child -> rewrite(child, applicationId, dataVersion));
  }

  private JsonNode post(String path, JsonNode body) {
    switch (path) {
      case "/v2/verifications" -> {
        Contract.validate("VerificationRequest", body);
        String mode = verificationMode;
        if (has(body.path("data"), "staffAttestation")) mode = "COMPLETE";
        if (mode.equals("NEEDS_INFO")
            && body.path("data").path("additionalInfo").has("businessDescription"))
          mode = "COMPLETE";
        JsonNode base;
        if (mode.equals("COMPLETE")) base = template("approved", "createVerification");
        else if (mode.equals("NEEDS_INFO"))
          base = template("information-and-versions", "createVerification");
        else {
          var manual = copy(template("approved", "createVerification"));
          put(manual, "outcome", "MANUAL_REVIEW");
          put(manual, "confirmed", false);
          base = manual;
        }
        var result = copy(version(base, body));
        put(result, "id", UUID.randomUUID());
        put(result, "asOf", body.get("asOf"));
        put(result, "completedAt", clock.instant());
        return result;
      }
      case "/v2/decisions" -> {
        Contract.validate("DecisionRequest", body);
        String mode = decisionMode;
        if (mode.equals("NEEDS_INFO")
            && body.path("data").path("additionalInfo").has("revenueExplanation"))
          mode = "APPROVED";
        JsonNode base =
            switch (mode) {
              case "REJECTED" -> template("declined", "createDecision");
              case "MANUAL_REVIEW" -> template("manual-review", "createDecision");
              case "COUNTER" -> template("counteroffer", "createDecision");
              case "NEEDS_INFO" -> template("information-and-versions", "createDecision");
              default -> template("approved", "createDecision");
            };
        var result = copy(version(base, body));
        put(result, "id", UUID.randomUUID());
        put(result, "evaluatedAt", clock.instant());
        if (has(result, "offer")) {
          var offer = copy(result.get("offer"));
          put(offer, "id", UUID.randomUUID());
          put(offer, "expiresAt", clock.instant().plus(Duration.ofHours(168)));
          put(offer, "productId", body.path("data").path("product").get("id"));
          put(offer, "productVersion", body.path("data").path("product").get("version"));
          put(result, "offer", offer);
        }
        return result;
      }
      case "/v2/manual-reviews" -> {
        Contract.validate("ManualReviewRequest", body);
        var result =
            obj(
                "id",
                UUID.randomUUID(),
                "applicationId",
                body.get("applicationId"),
                "dataVersion",
                body.get("dataVersion"),
                "verificationId",
                body.path("verification").get("id"),
                "decisionId",
                body.path("decision").get("id"),
                "assessmentId",
                body.path("decision").get("assessmentId"),
                "status",
                "OPEN",
                "reason",
                body.get("reason"),
                "allowedActions",
                has(body, "decision")
                    ? arr("APPROVE", "REJECT", "REQUEST_INFO", "AMEND_DATA")
                    : arr("REJECT", "REQUEST_INFO", "AMEND_DATA"),
                "createdAt",
                clock.instant());
        cases.put(s(result, "id"), result.deepCopy());
        return result;
      }
      case "/v2/agreements" -> {
        Contract.validate("AgreementRequest", body);
        String content = "Синтетический договор " + s(body, "applicationId");
        var result =
            obj(
                "id",
                UUID.randomUUID(),
                "applicationId",
                body.get("applicationId"),
                "dataVersion",
                body.get("dataVersion"),
                "customerId",
                body.get("customerId"),
                "companyId",
                body.path("company").get("id"),
                "offer",
                body.get("offer"),
                "acceptedAt",
                body.get("acceptedAt"),
                "createdAt",
                clock.instant(),
                "signBy",
                clock.instant().plus(Duration.ofHours(72)),
                "status",
                "READY",
                "closureReason",
                null,
                "document",
                obj(
                    "version",
                    1,
                    "mediaType",
                    "text/plain; charset=utf-8",
                    "content",
                    content,
                    "sha256",
                    sha(content)),
                "signature",
                null);
        agreements.put(s(result, "id"), result.deepCopy());
        return result;
      }
      case "/v2/payout-checks" -> {
        Contract.validate("PayoutCheckRequest", body);
        var result = copy(body);
        put(result, "id", UUID.randomUUID());
        put(result, "outcome", correction ? "NEEDS_CORRECTION" : "READY");
        put(result, "reasonCodes", correction ? arr("ACCOUNT_INVALID") : arr());
        put(result, "checkedAt", clock.instant());
        return result;
      }
      case "/v2/disbursements" -> {
        Contract.validate("DisbursementRequest", body);
        var result = copy(body);
        put(result, "id", UUID.randomUUID());
        put(result, "currency", "RUB");
        put(result, "status", "EXECUTED");
        var agreement = agreements.get(s(body, "agreementId"));
        put(result, "amountRub", agreement.path("offer").get("amountRub"));
        put(result, "creditEntryId", UUID.randomUUID());
        put(result, "executedAt", clock.instant());
        disbursements.put(s(result, "operationId"), result);
        return result;
      }
      default -> {}
    }
    if (path.startsWith("/v2/agreements/")) {
      var result = agreements.get(path.split("/")[3]);
      synchronized (result) {
        if (path.endsWith("/sign")) {
          Contract.validate("InternalSignRequest", body);
          if (s(result, "status").equals("CLOSED"))
            throw new Problem(409, "AGREEMENT_CLOSED", "Agreement closed");
          if (!clock.instant().isBefore(instant(result, "signBy")))
            throw new Problem(409, "DEADLINE_EXPIRED", "Deadline passed");
          put(result, "status", "SIGNED");
          put(
              result,
              "signature",
              obj(
                  "id",
                  UUID.randomUUID(),
                  "customerId",
                  body.get("customerId"),
                  "documentVersion",
                  body.get("documentVersion"),
                  "documentSha256",
                  body.get("documentSha256"),
                  "signedAt",
                  clock.instant(),
                  "method",
                  "DEMO_CONSENT"));
        } else {
          Contract.validate("CloseAgreementRequest", body);
          if (s(result, "status").equals("SIGNED"))
            throw new Problem(409, "AGREEMENT_ALREADY_SIGNED", "Agreement signed");
          if (!s(result, "status").equals("CLOSED")) {
            put(result, "status", "CLOSED");
            put(result, "closureReason", body.get("reason"));
          }
        }
        return result.deepCopy();
      }
    }
    if (path.startsWith("/v2/manual-reviews/")) {
      var review = cases.get(path.split("/")[3]);
      if (path.endsWith("/close")) {
        Contract.validate("CloseCaseRequest", body);
        put(review, "status", "CLOSED");
        put(review, "allowedActions", arr());
        return review.deepCopy();
      }
      Contract.validate("ManualReviewActionRequest", body);
      var result =
          copy(
              version(
                  templateAction(s(body, "action")),
                  obj(
                      "applicationId",
                      review.get("applicationId"),
                      "dataVersion",
                      review.get("dataVersion"))));
      for (String field :
          List.of("action", "staffId", "reason", "requirements", "changes", "factsResolution"))
        put(result, field, body.get(field));
      put(result, "id", body.get("actionId"));
      put(result, "caseId", review.get("id"));
      put(result, "createdAt", clock.instant());
      if (has(result, "decision")) {
        var decision = copy(result.get("decision"));
        put(
            decision,
            "staffAudit",
            obj(
                "staffId",
                body.get("staffId"),
                "actionId",
                body.get("actionId"),
                "reason",
                body.get("reason"),
                "decidedAt",
                clock.instant()));
        if (has(decision, "offer")) {
          var offer = copy(decision.get("offer"));
          put(offer, "expiresAt", clock.instant().plus(Duration.ofHours(168)));
          put(decision, "offer", offer);
        }
        put(result, "decision", decision);
      }
      put(review, "status", "RESOLVED");
      put(review, "allowedActions", arr());
      return result;
    }
    throw new Problem(404, "NOT_FOUND", "Unknown dependency operation");
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }
}
