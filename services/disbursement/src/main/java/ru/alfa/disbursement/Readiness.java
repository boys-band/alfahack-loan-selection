package ru.alfa.disbursement;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Readiness {
  private final JdbcTemplate db;

  Readiness(JdbcTemplate db) {
    this.db = db;
  }

  void check() {
    // Deliberately never read schema_migrations: the runtime role has no access.
    for (String query :
        List.of(
            "SELECT caller,method,path,idempotency_key,request_hash,response_status,response_headers,response_body,expires_at FROM idempotency_records LIMIT 0",
            "SELECT id,application_id,data_version,payout_version,agreement_id,company_id,settlement_account_id,outcome,reason_codes,checked_at,request_payload,request_hash FROM payout_checks LIMIT 0",
            "SELECT id,operation_id,application_id,data_version,agreement_id,payout_version,payout_check_id,company_id,settlement_account_id,amount_rub,currency,status,credit_entry_id,executed_at,agreement_snapshot,request_payload,request_hash FROM disbursements LIMIT 0",
            "SELECT id,disbursement_id,settlement_account_id,amount_rub,currency,posted_at FROM credit_entries LIMIT 0")) {
      db.queryForList(query);
    }
    Integer seeded =
        db.queryForObject(
            """
            SELECT count(*) FROM account_fixtures WHERE
            (id,company_id,status) IN (
            ('00000000-0000-4000-8000-000000001101'::uuid,'00000000-0000-4000-8000-000000000101'::uuid,'ACTIVE'),
            ('00000000-0000-4000-8000-000000001102'::uuid,'00000000-0000-4000-8000-000000000102'::uuid,'ACTIVE'),
            ('00000000-0000-4000-8000-000000001103'::uuid,'00000000-0000-4000-8000-000000000103'::uuid,'ACTIVE'),
            ('00000000-0000-4000-8000-000000001104'::uuid,'00000000-0000-4000-8000-000000000104'::uuid,'ACTIVE'),
            ('00000000-0000-4000-8000-000000001105'::uuid,'00000000-0000-4000-8000-000000000105'::uuid,'ACTIVE'),
            ('00000000-0000-4000-8000-000000001199'::uuid,'00000000-0000-4000-8000-000000000101'::uuid,'INVALID'))
            """,
            Integer.class);
    if (seeded == null || seeded != 6) throw ApiException.unavailable();
  }
}
