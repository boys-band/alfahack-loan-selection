package ru.alfa.verification;

import static ru.alfa.verification.VerificationStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.*;
import java.util.UUID;

final class VerificationService {
  private final Database db;
  private final Clock clock;
  private final SourceAdapter sources = new SourceAdapter();
  private final Enrichment enrichment = new Enrichment();

  VerificationService(Database db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  Reply create(JsonNode json, String key) {
    String hash = Json.hash("POST\n/v2/verifications\n" + Json.write(Json.sorted(json)));
    try (var c = db.connect()) {
      c.setAutoCommit(false);
      try {
        try (var s = c.createStatement()) {
          s.execute("SET LOCAL lock_timeout='3s'");
          s.execute("SET LOCAL statement_timeout='8s'");
        }
        lock(c, "verification:idem:" + key);
        Reply replay = replay(c, key, hash);
        if (replay != null) {
          c.commit();
          return replay;
        }
        Contracts.validate("VerificationRequest", json);
        ObjectNode request = (ObjectNode) json;
        UUID app = UUID.fromString(request.path("applicationId").asText());
        if (!request.path("dataVersion").canConvertToLong())
          throw ApiException.invalid("/dataVersion", "Version exceeds supported bigint range");
        long version = request.path("dataVersion").asLong();
        lock(c, "verification:business:" + app + ":" + version);
        Stored existing = findBusiness(c, app, version);
        Reply reply;
        if (existing != null) {
          checkHash(hash, existing.hash());
          reply = new Reply(200, existing.body());
        } else {
          validateSnapshot(request, clock.instant());
          var payloads = sources.read(c, UUID.fromString(request.at("/data/company/id").asText()));
          ObjectNode result = enrichment.evaluate(request, payloads, clock.instant());
          Contracts.validate("VerificationResult", result);
          insertResult(c, request, hash, result, payloads);
          reply = new Reply(201, result);
        }
        saveReply(c, key, hash, reply);
        c.commit();
        return reply;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      if ("55P03".equals(e.getSQLState()))
        throw new ApiException(
            409,
            "IDEMPOTENCY_IN_PROGRESS",
            "Concurrent request is still running; retry with the same key");
      throw ApiException.unavailable();
    } catch (SourceAdapter.Unavailable e) {
      throw ApiException.unavailable();
    }
  }

  ObjectNode get(UUID id) {
    try (var c = db.connect()) {
      ObjectNode result = findId(c, id);
      if (result == null) throw new ApiException(404, "NOT_FOUND", "Verification was not found");
      return result;
    } catch (SQLException e) {
      throw ApiException.unavailable();
    }
  }

  void ready() {
    try (var c = db.connect();
        var s = c.createStatement()) {
      s.setQueryTimeout(2);
      // Check required columns without opening the owner-only migration ledger.
      s.executeQuery(
              "SELECT id,source_payloads,result_payload,request_hash FROM verification_results LIMIT 0")
          .close();
      s.executeQuery("SELECT caller,request_hash,response_body FROM idempotency_records LIMIT 0")
          .close();
      try (var r =
          s.executeQuery(
              """
                SELECT count(*) FROM source_fixtures
                WHERE company_id IN ('00000000-0000-4000-8000-000000000101','00000000-0000-4000-8000-000000000102',
                '00000000-0000-4000-8000-000000000103','00000000-0000-4000-8000-000000000104','00000000-0000-4000-8000-000000000105')
                AND source IN ('REGISTRY','BKI','FNS')
                """)) {
        if (!r.next() || r.getInt(1) != 15) throw ApiException.unavailable();
      }
    } catch (SQLException e) {
      throw ApiException.unavailable();
    }
  }

  private static void validateSnapshot(ObjectNode r, Instant now) {
    Instant asOf = Instant.parse(r.path("asOf").asText());
    if (asOf.atOffset(ZoneOffset.UTC).getYear() < 1)
      throw ApiException.invalid("/asOf", "Snapshot year must be at least 0001");
    if (asOf.isAfter(now)) throw ApiException.invalid("/asOf", "Snapshot cannot be in the future");
    if (LocalDate.parse(r.at("/data/company/registeredAt").asText())
        .isAfter(asOf.atOffset(ZoneOffset.UTC).toLocalDate()))
      throw ApiException.invalid("/data/company/registeredAt", "Registration cannot be after asOf");
    if (Instant.parse(r.at("/data/consents/acceptedAt").asText()).isAfter(asOf))
      throw ApiException.invalid("/data/consents/acceptedAt", "Consent cannot be after asOf");
    JsonNode a = r.at("/data/staffAttestation");
    if (!a.isNull()) {
      if (r.path("dataVersion").asLong() <= 1)
        throw new ApiException(
            422, "SNAPSHOT_MISMATCH", "Staff attestation requires a new data version");
      if (a.path("reason").asText().strip().length() < 10)
        throw ApiException.invalid(
            "/data/staffAttestation/reason", "Reason must contain at least 10 characters");
      if (Instant.parse(a.path("recordedAt").asText()).isAfter(asOf))
        throw ApiException.invalid(
            "/data/staffAttestation/recordedAt", "Attestation cannot be after asOf");
      if (LocalDate.parse(a.at("/confirmedFacts/registryRegisteredAt").asText())
          .isAfter(asOf.atOffset(ZoneOffset.UTC).toLocalDate()))
        throw ApiException.invalid(
            "/data/staffAttestation/confirmedFacts/registryRegisteredAt",
            "Registration cannot be after asOf");
    }
  }
}
