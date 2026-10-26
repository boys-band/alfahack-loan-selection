package ru.alfa.disbursement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PayoutStore {
  record Stored(JsonNode body, String hash) {}

  private final JdbcTemplate db;

  PayoutStore(JdbcTemplate db) {
    this.db = db;
  }

  void lock(String value) {
    Boolean locked =
        db.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
            Boolean.class,
            "disbursement:" + value);
    if (!Boolean.TRUE.equals(locked))
      throw new ApiException(
          409,
          "IDEMPOTENCY_IN_PROGRESS",
          "An overlapping operation is in progress; retry the original request.");
  }

  Optional<PayoutService.Result> replay(String path, String key, String hash) {
    var rows =
        db.query(
            "SELECT request_hash,response_status,response_body FROM idempotency_records WHERE caller='applications' AND method='POST' AND path=? AND idempotency_key=?",
            (rs, n) -> {
              if (!hash.equals(rs.getString("request_hash")))
                throw new ApiException(
                    409,
                    "IDEMPOTENCY_CONFLICT",
                    "The idempotency key was used with different input.");
              return new PayoutService.Result(
                  rs.getInt("response_status"), JsonSupport.parse(rs.getString("response_body")));
            },
            path,
            key);
    return rows.stream().findFirst();
  }

  PayoutService.Result remember(String path, String key, String hash, PayoutService.Result result) {
    db.update(
        """
            INSERT INTO idempotency_records(caller,method,path,idempotency_key,request_hash,response_status,response_body)
            VALUES('applications','POST',?,?,?,?,?::jsonb)
            """,
        path,
        key,
        hash,
        result.status(),
        result.body().toString());
    return result;
  }

  JsonNode accounts(String company) {
    ArrayNode items = JsonSupport.JSON.createArrayNode();
    db.query(
        "SELECT id,company_id,label,status FROM account_fixtures WHERE company_id=?::uuid ORDER BY id",
        rs -> {
          items.add(
              JsonSupport.JSON
                  .createObjectNode()
                  .put("id", rs.getString("id"))
                  .put("companyId", rs.getString("company_id"))
                  .put("label", rs.getString("label"))
                  .put("status", rs.getString("status")));
        },
        company);
    return JsonSupport.JSON.createObjectNode().set("items", items);
  }

  String accountReason(JsonNode request) {
    var reasons =
        db.query(
            "SELECT company_id,status FROM account_fixtures WHERE id=?::uuid",
            (rs, n) ->
                Optional.ofNullable(
                    AccountRules.reason(
                        rs.getString("company_id"),
                        rs.getString("status"),
                        request.path("companyId").asText())),
            request.path("settlementAccountId").asText());
    return reasons.isEmpty() ? "ACCOUNT_NOT_OWNED" : reasons.getFirst().orElse(null);
  }

  Optional<Stored> findCheck(JsonNode request) {
    return db
        .query(
            "SELECT * FROM payout_checks WHERE application_id=?::uuid AND data_version=? AND payout_version=?",
            (rs, n) -> new Stored(checkBody(rs), rs.getString("request_hash")),
            request.path("applicationId").asText(),
            request.path("dataVersion").asLong(),
            request.path("payoutVersion").asLong())
        .stream()
        .findFirst();
  }

  Optional<JsonNode> check(String id) {
    return db
        .query("SELECT * FROM payout_checks WHERE id=?::uuid", (rs, n) -> checkBody(rs), id)
        .stream()
        .findFirst();
  }

  JsonNode insertCheck(JsonNode request, String hash) {
    String id = UUID.randomUUID().toString(), reason = accountReason(request);
    db.update(
        """
            INSERT INTO payout_checks(id,application_id,data_version,payout_version,agreement_id,company_id,settlement_account_id,outcome,reason_codes,request_payload,request_hash)
            VALUES(?::uuid,?::uuid,?,?,?::uuid,?::uuid,?::uuid,?,?::text[],?::jsonb,?)
            """,
        id,
        request.path("applicationId").asText(),
        request.path("dataVersion").asLong(),
        request.path("payoutVersion").asLong(),
        request.path("agreementId").asText(),
        request.path("companyId").asText(),
        request.path("settlementAccountId").asText(),
        reason == null ? "READY" : "NEEDS_CORRECTION",
        reason == null ? "{}" : "{" + reason + "}",
        request.toString(),
        hash);
    return check(id).orElseThrow();
  }

  List<Stored> existingDisbursements(JsonNode request) {
    return db.query(
        "SELECT * FROM disbursements WHERE application_id=?::uuid OR agreement_id=?::uuid OR operation_id=?::uuid",
        (rs, n) -> new Stored(disbursementBody(rs), rs.getString("request_hash")),
        request.path("applicationId").asText(),
        request.path("agreementId").asText(),
        request.path("operationId").asText());
  }

  Optional<JsonNode> disbursement(String id, boolean byOperation) {
    String column = byOperation ? "operation_id" : "id";
    return db
        .query(
            "SELECT d.* FROM disbursements d JOIN credit_entries c ON c.id=d.credit_entry_id AND c.disbursement_id=d.id WHERE d."
                + column
                + "=?::uuid",
            (rs, n) -> disbursementBody(rs),
            id)
        .stream()
        .findFirst();
  }

  JsonNode insertDisbursement(JsonNode request, JsonNode agreement, String hash) {
    String id = UUID.randomUUID().toString(), credit = UUID.randomUUID().toString();
    long amount = agreement.path("offer").path("amountRub").asLong();
    db.update(
        """
            INSERT INTO disbursements(id,operation_id,application_id,data_version,agreement_id,payout_version,payout_check_id,company_id,settlement_account_id,amount_rub,credit_entry_id,agreement_snapshot,request_payload,request_hash)
            VALUES(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?::uuid,?::uuid,?::uuid,?,?::uuid,?::jsonb,?::jsonb,?)
            """,
        id,
        request.path("operationId").asText(),
        request.path("applicationId").asText(),
        request.path("dataVersion").asLong(),
        request.path("agreementId").asText(),
        request.path("payoutVersion").asLong(),
        request.path("payoutCheckId").asText(),
        request.path("companyId").asText(),
        request.path("settlementAccountId").asText(),
        amount,
        credit,
        agreement.toString(),
        request.toString(),
        hash);
    db.update(
        "INSERT INTO credit_entries(id,disbursement_id,settlement_account_id,amount_rub) VALUES(?::uuid,?::uuid,?::uuid,?)",
        credit,
        id,
        request.path("settlementAccountId").asText(),
        amount);
    return disbursement(id, false).orElseThrow();
  }

  private static ObjectNode baseBody(ResultSet rs) throws SQLException {
    return JsonSupport.JSON
        .createObjectNode()
        .put("id", rs.getString("id"))
        .put("applicationId", rs.getString("application_id"))
        .put("dataVersion", rs.getLong("data_version"))
        .put("payoutVersion", rs.getLong("payout_version"))
        .put("agreementId", rs.getString("agreement_id"))
        .put("companyId", rs.getString("company_id"))
        .put("settlementAccountId", rs.getString("settlement_account_id"));
  }

  private static JsonNode checkBody(ResultSet rs) throws SQLException {
    ObjectNode result =
        baseBody(rs)
            .put("outcome", rs.getString("outcome"))
            .put("checkedAt", rs.getTimestamp("checked_at").toInstant().toString());
    ArrayNode reasons = result.putArray("reasonCodes");
    for (String reason : (String[]) rs.getArray("reason_codes").getArray()) reasons.add(reason);
    return result;
  }

  private static JsonNode disbursementBody(ResultSet rs) throws SQLException {
    return baseBody(rs)
        .put("operationId", rs.getString("operation_id"))
        .put("payoutCheckId", rs.getString("payout_check_id"))
        .put("amountRub", rs.getLong("amount_rub"))
        .put("currency", rs.getString("currency"))
        .put("status", rs.getString("status"))
        .put("creditEntryId", rs.getString("credit_entry_id"))
        .put("executedAt", rs.getTimestamp("executed_at").toInstant().toString());
  }
}
