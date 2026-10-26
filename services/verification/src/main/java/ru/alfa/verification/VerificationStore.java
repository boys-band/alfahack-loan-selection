package ru.alfa.verification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

final class VerificationStore {
  record Stored(String hash, ObjectNode body) {}

  record Reply(int status, ObjectNode body) {}

  static void lock(Connection c, String key) throws SQLException {
    try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
      s.setString(1, key);
      s.execute();
    }
  }

  static Reply replay(Connection c, String key, String hash) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT request_hash,response_status,response_body FROM idempotency_records WHERE caller='applications' AND method='POST' AND path='/v2/verifications' AND idempotency_key=?")) {
      s.setString(1, key);
      try (var r = s.executeQuery()) {
        if (!r.next()) return null;
        checkHash(hash, r.getString("request_hash"));
        return new Reply(
            r.getInt("response_status"), (ObjectNode) Json.parse(r.getString("response_body")));
      }
    }
  }

  static Stored findBusiness(Connection c, UUID app, long version) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT * FROM verification_results WHERE application_id=? AND data_version=?")) {
      s.setObject(1, app);
      s.setLong(2, version);
      try (var r = s.executeQuery()) {
        return r.next() ? new Stored(r.getString("request_hash"), body(r)) : null;
      }
    }
  }

  static ObjectNode findId(Connection c, UUID id) throws SQLException {
    try (var s = c.prepareStatement("SELECT * FROM verification_results WHERE id=?")) {
      s.setObject(1, id);
      try (var r = s.executeQuery()) {
        return r.next() ? body(r) : null;
      }
    }
  }

  static ObjectNode body(ResultSet r) throws SQLException {
    String stored = r.getString("result_payload");
    if (stored != null) return (ObjectNode) Json.parse(stored);
    // Read results produced against accepted migration 001 before this service was installed.
    ObjectNode body =
        Json.object()
            .put("id", r.getString("id"))
            .put("applicationId", r.getString("application_id"))
            .put("dataVersion", r.getLong("data_version"))
            .put("outcome", r.getString("outcome"))
            .put("asOf", r.getTimestamp("as_of").toInstant().toString())
            .put("completedAt", r.getTimestamp("completed_at").toInstant().toString())
            .put("confirmed", r.getBoolean("confirmed"));
    for (var field :
        Map.of(
                "rawFacts",
                "raw_facts",
                "facts",
                "facts",
                "requirements",
                "requirements",
                "findings",
                "findings")
            .entrySet()) {
      String value = r.getString(field.getValue());
      body.set(field.getKey(), value == null ? Json.MAPPER.nullNode() : Json.parse(value));
    }
    return body;
  }

  static void insertResult(
      Connection c,
      ObjectNode request,
      String hash,
      ObjectNode result,
      Map<String, ObjectNode> sources)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            """
            INSERT INTO verification_results(id,application_id,data_version,outcome,as_of,completed_at,raw_facts,facts,
                confirmed,requirements,findings,request_payload,request_hash,source_payloads,result_payload)
            VALUES(?,?,?,?,?::timestamptz,?::timestamptz,?::jsonb,?::jsonb,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?::jsonb)
            """)) {
      s.setObject(1, UUID.fromString(result.path("id").asText()));
      s.setObject(2, UUID.fromString(result.path("applicationId").asText()));
      s.setLong(3, result.path("dataVersion").asLong());
      s.setString(4, result.path("outcome").asText());
      s.setString(5, result.path("asOf").asText());
      s.setString(6, result.path("completedAt").asText());
      s.setString(7, Json.write(result.path("rawFacts")));
      s.setString(8, Json.write(result.path("facts")));
      s.setBoolean(9, result.path("confirmed").asBoolean());
      s.setString(10, Json.write(result.path("requirements")));
      s.setString(11, Json.write(result.path("findings")));
      s.setString(12, Json.write(request));
      s.setString(13, hash);
      ObjectNode payloads = Json.object();
      sources.forEach(payloads::set);
      s.setString(14, Json.write(payloads));
      s.setString(15, Json.write(result));
      s.executeUpdate();
    }
  }

  static void saveReply(Connection c, String key, String hash, Reply reply) throws SQLException {
    try (var s =
        c.prepareStatement(
            """
            INSERT INTO idempotency_records(caller,method,path,idempotency_key,request_hash,response_status,response_headers,response_body)
            VALUES('applications','POST','/v2/verifications',?,?,?,'{"Content-Type":"application/json"}'::jsonb,?::jsonb)
            """)) {
      s.setString(1, key);
      s.setString(2, hash);
      s.setInt(3, reply.status());
      s.setString(4, Json.write(reply.body()));
      s.executeUpdate();
    }
  }

  static void checkHash(String expected, String actual) {
    if (!expected.equals(actual))
      throw new ApiException(
          409,
          "IDEMPOTENCY_CONFLICT",
          "The key or application data version already has a different request");
  }
}
