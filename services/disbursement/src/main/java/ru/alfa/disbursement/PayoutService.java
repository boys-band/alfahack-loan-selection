package ru.alfa.disbursement;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PayoutService {
  record Result(int status, JsonNode body) {}

  private final Contracts contracts;
  private final PayoutStore store;
  private final AgreementClient agreements;
  private final TransactionTemplate transaction;

  PayoutService(
      Contracts contracts,
      PayoutStore store,
      AgreementClient agreements,
      PlatformTransactionManager manager) {
    this.contracts = contracts;
    this.store = store;
    this.agreements = agreements;
    transaction = new TransactionTemplate(manager);
    transaction.setTimeout(35);
  }

  JsonNode accounts(String company) {
    return store.accounts(company);
  }

  JsonNode getCheck(String id) {
    return store
        .check(id)
        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Payout check not found."));
  }

  JsonNode getDisbursement(String id, boolean byOperation) {
    return store
        .disbursement(id, byOperation)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "NOT_FOUND", "No committed disbursement is visible for this identifier."));
  }

  Result check(JsonNode request, String key) {
    String path = "/v2/payout-checks", hash = JsonSupport.hash(path, request);
    return transaction.execute(
        tx -> {
          store.lock("idempotency:" + path + ":" + key);
          var replay = store.replay(path, key, hash);
          if (replay.isPresent()) return replay.get();
          contracts.request("PayoutCheckRequest", request);
          store.lock(
              "payout:"
                  + request.path("applicationId").asText()
                  + ":"
                  + request.path("dataVersion").asLong()
                  + ":"
                  + request.path("payoutVersion").asLong());
          var existing = store.findCheck(request);
          Result result;
          if (existing.isPresent()) {
            if (!existing.get().hash().equals(hash))
              throw ApiException.conflict(
                  "A payout check already exists for this version with different input.");
            result = new Result(200, existing.get().body());
          } else result = new Result(201, store.insertCheck(request, hash));
          return store.remember(path, key, hash, result);
        });
  }

  Result disburse(JsonNode request, String key, String requestId) {
    String path = "/v2/disbursements", hash = JsonSupport.hash(path, request);
    // TransactionTemplate returns only AFTER commit, including deferred FK checks.
    return transaction.execute(
        tx -> {
          store.lock("idempotency:" + path + ":" + key);
          var replay = store.replay(path, key, hash);
          if (replay.isPresent()) return replay.get();
          contracts.request("DisbursementRequest", request);
          List.of(
                  "application:" + request.path("applicationId").asText(),
                  "agreement:" + request.path("agreementId").asText(),
                  "operation:" + request.path("operationId").asText())
              .stream()
              .sorted()
              .forEach(store::lock);
          var existing = store.existingDisbursements(request);
          if (!existing.isEmpty()) {
            if (existing.size() != 1 || !existing.getFirst().hash().equals(hash))
              throw ApiException.conflict(
                  "An application, agreement or operation was already disbursed with different input.");
            return store.remember(path, key, hash, new Result(200, existing.getFirst().body()));
          }
          JsonNode check =
              store
                  .check(request.path("payoutCheckId").asText())
                  .orElseThrow(
                      () ->
                          ApiException.conflict("The exact successful payout check is required."));
          if (!check.path("outcome").asText().equals("READY"))
            throw ApiException.conflict("The payout check requires correction.");
          for (String field :
              new String[] {
                "applicationId",
                "dataVersion",
                "payoutVersion",
                "agreementId",
                "companyId",
                "settlementAccountId"
              })
            if (!JsonSupport.sameValue(check.path(field), request.path(field)))
              throw ApiException.conflict("The payout check belongs to a different snapshot.");
          if (store.accountReason(request) != null)
            throw ApiException.conflict("The account is no longer eligible for payout.");
          JsonNode agreement = agreements.signed(request, requestId);
          JsonNode result = store.insertDisbursement(request, agreement, hash);
          return store.remember(path, key, hash, new Result(201, result));
        });
  }
}
