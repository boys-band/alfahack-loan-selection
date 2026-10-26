package ru.alfa.disbursement;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class ApiController {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ApiController.class);
  private final PayoutService service;
  private final Readiness readiness;

  ApiController(PayoutService service, Readiness readiness) {
    this.service = service;
    this.readiness = readiness;
  }

  @GetMapping("/health/live")
  Map<String, String> live() {
    return Map.of("status", "ok");
  }

  @GetMapping("/health/ready")
  Map<String, String> ready() {
    readiness.check();
    return live();
  }

  @GetMapping("/v2/company-accounts/{companyId}")
  JsonNode accounts(@PathVariable String companyId) {
    return service.accounts(uuid(companyId));
  }

  @PostMapping("/v2/payout-checks")
  ResponseEntity<JsonNode> check(@RequestBody JsonNode body, HttpServletRequest request) {
    return response(service.check(body, key(request)), request);
  }

  @GetMapping("/v2/payout-checks/{id}")
  JsonNode getCheck(@PathVariable String id) {
    return service.getCheck(uuid(id));
  }

  @PostMapping("/v2/disbursements")
  ResponseEntity<JsonNode> disburse(@RequestBody JsonNode body, HttpServletRequest request) {
    return response(
        service.disburse(body, key(request), (String) request.getAttribute("requestId")), request);
  }

  @GetMapping("/v2/disbursements/{id}")
  JsonNode getDisbursement(@PathVariable String id) {
    return service.getDisbursement(uuid(id), false);
  }

  @GetMapping("/v2/disbursements/by-operation/{id}")
  JsonNode byOperation(@PathVariable String id) {
    return service.getDisbursement(uuid(id), true);
  }

  private ResponseEntity<JsonNode> response(PayoutService.Result r, HttpServletRequest request) {
    LOG.info(
        "requestId={} applicationId={} dataVersion={} stage={} code={} httpStatus={}",
        request.getAttribute("requestId"),
        r.body().path("applicationId").asText(),
        r.body().path("dataVersion").asLong(),
        r.body().has("operationId") ? "DISBURSEMENT" : "PAYOUT_CHECK",
        r.body().has("operationId")
            ? r.body().path("status").asText()
            : r.body().path("outcome").asText(),
        r.status());
    return ResponseEntity.status(r.status()).body(r.body());
  }

  private String key(HttpServletRequest request) {
    String key = request.getHeader("Idempotency-Key");
    if (key == null || !key.matches("[A-Za-z0-9:_.-]{8,128}"))
      throw new ApiException(
          400,
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must contain 8–128 permitted characters.");
    return key;
  }

  private String uuid(String value) {
    if (!InternalAccessFilter.UUID_PATTERN.matcher(value).matches())
      throw new ApiException(
          422, "VALIDATION_ERROR", "Path identifier must be a canonical lowercase UUID.");
    return value;
  }
}
