package ru.alfa.agreements;

import static ru.alfa.agreements.Json.*;
import static ru.alfa.agreements.Problem.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** PostgreSQL transactions preserve the accepted snapshot, document and single signature. */
final class Store {
  record Reply(int status, JsonNode body) {}

  private final String url;
  private final Properties properties = new Properties();
  private final Clock clock;

  Store(String databaseUrl, Clock clock) {
    this.clock = clock;
    if (databaseUrl.startsWith("jdbc:postgresql:")) url = databaseUrl;
    else {
      URI uri = URI.create(databaseUrl);
      valid(
          Set.of("postgres", "postgresql").contains(uri.getScheme()),
          "DATABASE_URL должен указывать PostgreSQL");
      String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
      url =
          "jdbc:postgresql://"
              + host
              + ":"
              + (uri.getPort() < 0 ? 5432 : uri.getPort())
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      if (uri.getRawUserInfo() != null) {
        String[] parts = uri.getRawUserInfo().split(":", 2);
        properties.setProperty("user", URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
        if (parts.length > 1)
          properties.setProperty("password", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
      }
    }
    properties.setProperty("connectTimeout", "2");
    properties.setProperty("socketTimeout", "12");
    properties.setProperty(
        "options", "-c statement_timeout=10000 -c lock_timeout=5000 -c timezone=UTC");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  Connection connect() throws SQLException {
    return DriverManager.getConnection(url, properties);
  }

  static void bind(PreparedStatement st, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) {
      Object value = values[i];
      if (value instanceof JsonNode n) {
        if (n.isNull() || n.isMissingNode()) value = null;
        else if (n.isTextual()) value = n.textValue();
        else if (n.isNumber() && n.decimalValue().stripTrailingZeros().scale() <= 0)
          value = n.bigIntegerValue().longValueExact();
        else if (n.isBoolean()) value = n.booleanValue();
        else value = n.toString();
      }
      if (value == null) st.setNull(i + 1, Types.NULL);
      else if (value instanceof String || value instanceof Instant)
        st.setObject(i + 1, value.toString(), Types.OTHER);
      else st.setObject(i + 1, value);
    }
  }

  static ObjectNode one(Connection conn, String sql, Object... values) throws SQLException {
    try (PreparedStatement st = conn.prepareStatement(sql)) {
      bind(st, values);
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) return null;
        ObjectNode row = obj();
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          Object value = rs.getObject(i);
          String type = md.getColumnTypeName(i), name = md.getColumnLabel(i);
          if (value != null && (type.equals("jsonb") || type.equals("json")))
            row.set(name, parse(value.toString()));
          else if (value instanceof Timestamp ts) row.put(name, ts.toInstant().toString());
          else if (value instanceof UUID) row.put(name, value.toString());
          else row.set(name, node(value));
        }
        return row;
      }
    }
  }

  static void execute(Connection conn, String sql, Object... values) throws SQLException {
    try (PreparedStatement st = conn.prepareStatement(sql)) {
      bind(st, values);
      st.execute();
    }
  }

  static void insert(Connection conn, String table, ObjectNode values) throws SQLException {
    // All identifiers here are private constants in this service, never request values.
    List<String> names = new ArrayList<>();
    values.fieldNames().forEachRemaining(names::add);
    execute(
        conn,
        "INSERT INTO "
            + table
            + " ("
            + String.join(",", names)
            + ") VALUES ("
            + String.join(",", Collections.nCopies(names.size(), "?"))
            + ")",
        names.stream().map(values::get).toArray());
  }

  void ready() throws SQLException {
    try (Connection conn = connect()) {
      execute(
          conn,
          "SELECT id,offer_snapshot,document_content,document_sha256,signature_id,sign_by FROM agreements LIMIT 0");
      execute(conn, "SELECT id,agreement_id,document_sha256,signed_at FROM signatures LIMIT 0");
      execute(conn, "SELECT request_hash,response_body FROM idempotency_records LIMIT 0");
      ObjectNode triggers =
          one(
              conn,
              "SELECT count(*) AS n FROM pg_trigger WHERE NOT tgisinternal AND tgrelid='agreements'::regclass AND tgenabled='O' AND tgname IN ('document_hash_guard','agreement_update_guard','agreement_no_delete')");
      if (number(triggers, "n") != 3)
        throw new SQLException("Expected schema guards missing", "55000");
      ObjectNode signatureGuards =
          one(
              conn,
              "SELECT count(*) AS n FROM pg_trigger WHERE NOT tgisinternal AND tgrelid='signatures'::regclass AND tgenabled='O' AND tgname IN ('signature_time_guard','signatures_immutable')");
      if (number(signatureGuards, "n") != 2)
        throw new SQLException("Expected signature guards missing", "55000");
    }
  }

  static ObjectNode get(Connection conn, Object identifier, boolean lock) throws SQLException {
    ObjectNode row =
        one(conn, "SELECT * FROM agreements WHERE id=?" + (lock ? " FOR UPDATE" : ""), identifier);
    require(row != null, 404, "NOT_FOUND", "Договор не найден");
    return row;
  }

  static ObjectNode view(Connection conn, JsonNode row) throws SQLException {
    JsonNode signature = null;
    if (!row.get("signature_id").isNull()) {
      JsonNode s = one(conn, "SELECT * FROM signatures WHERE id=?", row.get("signature_id"));
      signature =
          obj(
              "id",
              s.get("id"),
              "customerId",
              s.get("customer_id"),
              "documentVersion",
              s.get("document_version"),
              "documentSha256",
              s.get("document_sha256"),
              "signedAt",
              s.get("signed_at"),
              "method",
              s.get("method"));
    }
    return obj(
        "id",
        row.get("id"),
        "applicationId",
        row.get("application_id"),
        "dataVersion",
        row.get("data_version"),
        "customerId",
        row.get("customer_id"),
        "companyId",
        row.get("company_id"),
        "offer",
        row.get("offer_snapshot"),
        "acceptedAt",
        row.get("accepted_at"),
        "createdAt",
        row.get("created_at"),
        "signBy",
        row.get("sign_by"),
        "status",
        row.get("status"),
        "closureReason",
        row.get("closure_reason"),
        "document",
        obj(
            "version",
            row.get("document_version"),
            "mediaType",
            "text/plain; charset=utf-8",
            "content",
            row.get("document_content"),
            "sha256",
            row.get("document_sha256")),
        "signature",
        signature);
  }

  JsonNode read(String identifier) throws SQLException {
    try (Connection conn = connect()) {
      return view(conn, get(conn, identifier, false));
    }
  }

  Reply mutate(String path, String key, JsonNode body) throws SQLException {
    String fingerprint = hash(obj("method", "POST", "path", path, "body", body));
    try (Connection conn = connect()) {
      conn.setAutoCommit(false);
      try {
        execute(
            conn,
            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            "applications:POST:" + path + ":" + key);
        ObjectNode row = null;
        if (!path.equals("/v2/agreements")) {
          row = get(conn, path.split("/")[3], true);
          // applications attests the signer identity; check ownership before replay.
          if (path.endsWith("/sign"))
            require(
                row.get("customer_id").equals(body.get("customerId")),
                409,
                "SIGNER_MISMATCH",
                "Клиент не является подписантом этого договора");
        }
        ObjectNode saved =
            one(
                conn,
                "SELECT request_hash,response_status,response_body FROM idempotency_records WHERE caller='applications' AND method='POST' AND path=? AND idempotency_key=?",
                path,
                key);
        if (saved != null) {
          require(
              fingerprint.equals(text(saved, "request_hash")),
              409,
              "IDEMPOTENCY_CONFLICT",
              "Ключ использован с другим запросом");
          conn.commit();
          return new Reply((int) number(saved, "response_status"), saved.get("response_body"));
        }
        Reply reply;
        if (row == null) {
          execute(
              conn,
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              text(body, "applicationId") + ":" + number(body, "dataVersion"));
          reply = create(conn, body, now());
        } else
          reply =
              path.endsWith("/sign") ? sign(conn, row, body, now()) : close(conn, row, body, now());
        insert(
            conn,
            "idempotency_records",
            obj(
                "caller",
                "applications",
                "method",
                "POST",
                "path",
                path,
                "idempotency_key",
                key,
                "request_hash",
                fingerprint,
                "response_status",
                reply.status(),
                "response_headers",
                obj(),
                "response_body",
                reply.body()));
        conn.commit();
        return reply;
      } catch (SQLException | RuntimeException e) {
        conn.rollback();
        throw e;
      }
    }
  }

  Reply create(Connection conn, JsonNode request, Instant now) throws SQLException {
    ObjectNode existing =
        one(
            conn,
            "SELECT * FROM agreements WHERE application_id=? AND data_version=?",
            request.get("applicationId"),
            request.get("dataVersion"));
    if (existing != null) {
      require(
          Contract.same(existing.get("request_payload"), request),
          409,
          "IDEMPOTENCY_CONFLICT",
          "Версия заявки уже имеет договор с другим входом");
      return new Reply(200, view(conn, existing));
    }
    DocumentTemplate.check(request, now);
    ObjectNode document = DocumentTemplate.render(request);
    String identifier = id();
    JsonNode offer = request.get("offer");
    insert(
        conn,
        "agreements",
        obj(
            "id",
            identifier,
            "application_id",
            request.get("applicationId"),
            "data_version",
            request.get("dataVersion"),
            "customer_id",
            request.get("customerId"),
            "company_id",
            request.get("company").get("id"),
            "offer_id",
            offer.get("id"),
            "offer_version",
            offer.get("version"),
            "offer_snapshot",
            offer,
            "accepted_at",
            request.get("acceptedAt"),
            "created_at",
            now.toString(),
            "sign_by",
            DocumentTemplate.deadline(now).toString(),
            "document_version",
            1,
            "document_content",
            document.get("content"),
            "document_sha256",
            document.get("sha256"),
            "request_payload",
            request,
            "request_hash",
            hash(request)));
    ObjectNode row = get(conn, identifier, false);
    if (!row.get("document_sha256").equals(document.get("sha256")))
      throw new IllegalStateException("Database document hash mismatch");
    return new Reply(201, view(conn, row));
  }

  Reply sign(Connection conn, JsonNode row, JsonNode body, Instant now) throws SQLException {
    require(
        row.get("application_id").equals(body.get("applicationId"))
            && Contract.same(row.get("data_version"), body.get("dataVersion"))
            && row.get("offer_id").equals(body.get("offerId"))
            && Contract.same(row.get("offer_version"), body.get("offerVersion")),
        422,
        "SNAPSHOT_MISMATCH",
        "Подпись относится к другому предложению или версии заявки");
    require(
        Contract.same(row.get("document_version"), body.get("documentVersion"))
            && row.get("document_sha256").equals(body.get("documentSha256")),
        409,
        "DOCUMENT_MISMATCH",
        "Версия или хеш документа не совпадают");
    require(!"CLOSED".equals(text(row, "status")), 409, "AGREEMENT_CLOSED", "Договор уже закрыт");
    if ("SIGNED".equals(text(row, "status"))) return new Reply(200, view(conn, row));
    Instant deadline = Instant.parse(text(row, "sign_by"));
    require(now.isBefore(deadline), 409, "DEADLINE_EXPIRED", "Срок подписания истек");
    require(
        !now.isBefore(Instant.parse(text(row, "created_at"))),
        409,
        "INVALID_STATE",
        "Серверное время предшествует созданию договора");
    String signatureId = id();
    try {
      insert(
          conn,
          "signatures",
          obj(
              "id",
              signatureId,
              "agreement_id",
              row.get("id"),
              "customer_id",
              body.get("customerId"),
              "document_version",
              body.get("documentVersion"),
              "document_sha256",
              body.get("documentSha256"),
              "signed_at",
              now.toString(),
              "method",
              "DEMO_CONSENT"));
      execute(
          conn,
          "UPDATE agreements SET status='SIGNED',signature_id=? WHERE id=?",
          signatureId,
          row.get("id"));
    } catch (SQLException e) {
      // The accepted SQL also checks wall-clock time. Crossing the deadline between
      // the application guard and INSERT/UPDATE stays a deadline conflict, never a signature.
      if ("23514".equals(e.getSQLState()) && !clock.instant().isBefore(deadline))
        throw new Problem(409, "DEADLINE_EXPIRED", "Срок подписания истек");
      throw e;
    }
    return new Reply(200, view(conn, get(conn, row.get("id"), false)));
  }

  Reply close(Connection conn, JsonNode row, JsonNode body, Instant now) throws SQLException {
    require(
        !"SIGNED".equals(text(row, "status")),
        409,
        "AGREEMENT_ALREADY_SIGNED",
        "Подписанный договор нельзя закрыть");
    // Reclosing preserves the first actual reason, even for another reason/new key.
    if ("CLOSED".equals(text(row, "status"))) return new Reply(200, view(conn, row));
    if ("EXPIRED".equals(text(body, "reason")))
      require(
          !now.isBefore(Instant.parse(text(row, "sign_by"))),
          409,
          "INVALID_STATE",
          "Срок подписания еще не истек");
    execute(
        conn,
        "UPDATE agreements SET status='CLOSED',closure_reason=?,closed_at=? WHERE id=?",
        body.get("reason"),
        now,
        row.get("id"));
    return new Reply(200, view(conn, get(conn, row.get("id"), false)));
  }
}
