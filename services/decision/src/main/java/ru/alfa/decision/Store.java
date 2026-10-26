package ru.alfa.decision;

import static ru.alfa.decision.Json.*;
import static ru.alfa.decision.Problem.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * One connection/transaction per operation. Runtime never executes DDL or reads another database.
 */
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

  Connection connect() throws SQLException {
    return DriverManager.getConnection(url, properties);
  }

  static void bind(PreparedStatement statement, Object... values) throws SQLException {
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
      if (value == null) statement.setNull(i + 1, Types.NULL);
      else if (value instanceof String || value instanceof Instant)
        statement.setObject(i + 1, value.toString(), Types.OTHER);
      else statement.setObject(i + 1, value);
    }
  }

  static List<ObjectNode> rows(Connection conn, String sql, Object... values) throws SQLException {
    try (PreparedStatement st = conn.prepareStatement(sql)) {
      bind(st, values);
      try (ResultSet rs = st.executeQuery()) {
        List<ObjectNode> rows = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
          ObjectNode row = obj();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            String type = md.getColumnTypeName(i);
            if (v != null && (type.equals("jsonb") || type.equals("json")))
              row.set(md.getColumnLabel(i), parse(v.toString()));
            else if (v instanceof java.sql.Timestamp t)
              row.put(md.getColumnLabel(i), t.toInstant().toString());
            else if (v instanceof UUID) row.put(md.getColumnLabel(i), v.toString());
            else row.set(md.getColumnLabel(i), node(v));
          }
          rows.add(row);
        }
        return rows;
      }
    }
  }

  static ObjectNode one(Connection conn, String sql, Object... values) throws SQLException {
    List<ObjectNode> found = rows(conn, sql, values);
    return found.isEmpty() ? null : found.get(0);
  }

  static void execute(Connection conn, String sql, Object... values) throws SQLException {
    try (PreparedStatement st = conn.prepareStatement(sql)) {
      bind(st, values);
      st.execute();
    }
  }

  static void insert(Connection conn, String table, ObjectNode values) throws SQLException {
    List<String> names = new ArrayList<>();
    values.fieldNames().forEachRemaining(names::add);
    // Identifiers are private compile-time constants supplied only by this class.
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
      for (String sql :
          List.of(
              "SELECT body FROM decision_documents LIMIT 0",
              "SELECT request_payload,body FROM action_documents LIMIT 0",
              "SELECT id,input_snapshot,verification_snapshot FROM assessments LIMIT 0",
              "SELECT id,risk_snapshot FROM evaluated_terms LIMIT 0",
              "SELECT id FROM decisions LIMIT 0",
              "SELECT id FROM offers LIMIT 0",
              "SELECT id FROM manual_review_cases LIMIT 0",
              "SELECT id FROM manual_review_actions LIMIT 0",
              "SELECT request_hash FROM idempotency_records LIMIT 0")) execute(conn, sql);
    }
  }

  static JsonNode document(Connection conn, Object identifier) throws SQLException {
    ObjectNode row =
        one(conn, "SELECT body FROM decision_documents WHERE decision_id=?", identifier);
    require(row != null, 404, "NOT_FOUND", "Решение не найдено");
    return row.get("body");
  }

  static ObjectNode getCase(Connection conn, Object identifier, boolean lock) throws SQLException {
    ObjectNode row =
        one(
            conn,
            "SELECT * FROM manual_review_cases WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            identifier);
    require(row != null, 404, "NOT_FOUND", "Дело не найдено");
    return row;
  }

  static ObjectNode caseView(Connection conn, JsonNode row) throws SQLException {
    ArrayNode actions = arr();
    if ("OPEN".equals(text(row, "status"))) {
      if (!row.get("automatic_decision_id").isNull()) {
        JsonNode auto = document(conn, row.get("automatic_decision_id"));
        for (JsonNode t : auto.get("evaluatedTerms"))
          if (t.get("eligible").asBoolean() && number(t.get("risk"), "score") >= 500) {
            actions.add("APPROVE");
            break;
          }
      }
      actions.add("REJECT").add("REQUEST_INFO").add("AMEND_DATA");
    }
    return obj(
        "id",
        row.get("id"),
        "applicationId",
        row.get("application_id"),
        "dataVersion",
        row.get("data_version"),
        "verificationId",
        row.get("verification_id"),
        "decisionId",
        row.get("automatic_decision_id"),
        "assessmentId",
        row.get("assessment_id"),
        "status",
        row.get("status"),
        "reason",
        row.get("reason"),
        "allowedActions",
        actions,
        "createdAt",
        row.get("created_at"));
  }

  JsonNode readDecision(String id) throws SQLException {
    try (Connection conn = connect()) {
      return document(conn, id);
    }
  }

  JsonNode readCase(String id) throws SQLException {
    try (Connection conn = connect()) {
      return caseView(conn, getCase(conn, id, false));
    }
  }

  JsonNode listCases(int limit, String cursor) throws SQLException {
    try (Connection conn = connect()) {
      List<ObjectNode> found;
      if (cursor == null)
        found =
            rows(
                conn,
                "SELECT * FROM manual_review_cases WHERE status='OPEN' ORDER BY created_at DESC,id DESC LIMIT ?",
                limit + 1);
      else {
        ObjectNode row =
            one(conn, "SELECT created_at,id FROM manual_review_cases WHERE id=?", cursor);
        valid(row != null, "Неизвестный cursor");
        found =
            rows(
                conn,
                "SELECT * FROM manual_review_cases WHERE status='OPEN' AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
                row.get("created_at"),
                row.get("id"),
                limit + 1);
      }
      ArrayNode items = arr();
      for (ObjectNode row : found.subList(0, Math.min(limit, found.size())))
        items.add(caseView(conn, row));
      return obj(
          "items",
          items,
          "nextCursor",
          found.size() > limit ? found.get(limit - 1).get("id") : null);
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
        ObjectNode saved =
            one(
                conn,
                "SELECT * FROM idempotency_records WHERE caller='applications' AND method='POST' AND path=? AND idempotency_key=?",
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
        Instant now = clock.instant();
        if (path.equals("/v2/decisions") || path.equals("/v2/manual-reviews")) {
          execute(
              conn,
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
              text(body, "applicationId") + ":" + number(body, "dataVersion"));
          checkSnapshot(body);
          reply =
              path.equals("/v2/decisions")
                  ? createDecision(conn, body, now)
                  : createCase(conn, body, now);
        } else {
          String[] parts = path.split("/");
          ObjectNode review = getCase(conn, parts[3], true);
          if (parts[4].equals("actions")) {
            execute(
                conn,
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                "action:" + text(body, "actionId"));
            reply = action(conn, review, body, now);
          } else reply = close(conn, review, body, now);
        }
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

  static void checkSnapshot(JsonNode body) {
    JsonNode v = body.get("verification"), p = body.get("data").get("product");
    require(
        v.get("applicationId").equals(body.get("applicationId"))
            && Contract.same(v.get("dataVersion"), body.get("dataVersion")),
        422,
        "SNAPSHOT_MISMATCH",
        "Проверка относится к другой версии заявки");
    valid(
        number(p, "minAmountRub") <= number(p, "maxAmountRub")
            && number(p, "minTermMonths") <= number(p, "maxTermMonths"),
        "Некорректные ограничения продукта");
  }

  Reply createDecision(Connection conn, JsonNode body, Instant now) throws SQLException {
    ObjectNode row =
        one(
            conn,
            "SELECT id,request_payload FROM decisions WHERE application_id=? AND data_version=? AND kind='AUTO'",
            body.get("applicationId"),
            body.get("dataVersion"));
    if (row != null) {
      conflict(row.get("request_payload").equals(body), "Версия уже оценена по другим данным");
      return new Reply(200, document(conn, row.get("id")));
    }
    ObjectNode result = Policy.decide(body, now);
    saveDecision(conn, body, result, now, true);
    return new Reply(201, result);
  }

  Reply createCase(Connection conn, JsonNode body, Instant now) throws SQLException {
    ObjectNode row =
        one(
            conn,
            "SELECT * FROM manual_review_cases WHERE application_id=? AND data_version=?",
            body.get("applicationId"),
            body.get("dataVersion"));
    if (row != null) {
      conflict(row.get("request_payload").equals(body), "Для версии уже создано другое дело");
      return new Reply(200, caseView(conn, row));
    }
    JsonNode auto = body.get("decision");
    if (!auto.isNull()) {
      JsonNode saved = document(conn, auto.get("id"));
      conflict(
          saved.equals(auto)
              && "AUTO".equals(text(auto, "kind"))
              && "MANUAL_REVIEW".equals(text(auto, "outcome")),
          "Нужно исходное автоматическое решение сервиса");
      JsonNode req =
          one(conn, "SELECT request_payload FROM decisions WHERE id=?", auto.get("id"))
              .get("request_payload");
      for (String k : List.of("applicationId", "dataVersion", "verification", "data"))
        require(
            req.get(k).equals(body.get(k)),
            422,
            "SNAPSHOT_MISMATCH",
            "Снимки дела отличаются от оценки");
    }
    String identifier = id();
    insert(
        conn,
        "manual_review_cases",
        obj(
            "id",
            identifier,
            "application_id",
            body.get("applicationId"),
            "data_version",
            body.get("dataVersion"),
            "verification_id",
            body.get("verification").get("id"),
            "assessment_id",
            auto.path("assessmentId"),
            "automatic_decision_id",
            auto.path("id"),
            "reason",
            body.get("reason"),
            "created_at",
            now.toString(),
            "request_payload",
            body,
            "request_hash",
            hash(body)));
    return new Reply(201, caseView(conn, getCase(conn, identifier, false)));
  }

  static void saveDecision(
      Connection conn, JsonNode request, JsonNode result, Instant now, boolean newAssessment)
      throws SQLException {
    JsonNode aid = result.get("assessmentId"),
        off = result.get("offer"),
        audit = result.get("staffAudit");
    if (!aid.isNull() && newAssessment) {
      insert(
          conn,
          "assessments",
          obj(
              "id",
              aid,
              "application_id",
              request.get("applicationId"),
              "data_version",
              request.get("dataVersion"),
              "verification_id",
              request.get("verification").get("id"),
              "verification_snapshot",
              request.get("verification"),
              "input_snapshot",
              request.get("data"),
              "policy_version",
              result.get("policyVersion"),
              "created_at",
              now.toString()));
      int ordinal = 0;
      for (JsonNode t : result.get("evaluatedTerms"))
        insert(
            conn,
            "evaluated_terms",
            obj(
                "id",
                t.get("id"),
                "assessment_id",
                aid,
                "ordinal",
                ++ordinal,
                "amount_rub",
                t.get("amountRub"),
                "term_months",
                t.get("termMonths"),
                "annual_rate_bps",
                t.get("annualRateBps"),
                "monthly_payment_rub",
                t.get("monthlyPaymentRub"),
                "risk_snapshot",
                t.get("risk"),
                "eligible",
                t.get("eligible"),
                "reasons",
                t.get("reasons")));
    }
    insert(
        conn,
        "decisions",
        obj(
            "id",
            result.get("id"),
            "application_id",
            result.get("applicationId"),
            "data_version",
            result.get("dataVersion"),
            "kind",
            result.get("kind"),
            "outcome",
            result.get("outcome"),
            "policy_version",
            result.get("policyVersion"),
            "assessment_id",
            aid,
            "selected_terms_id",
            result.get("selectedTermsId"),
            "reasons",
            result.get("reasons"),
            "requirements",
            result.get("requirements"),
            "offer_id",
            off.path("id"),
            "offer_version",
            off.path("version"),
            "staff_id",
            audit.path("staffId"),
            "staff_action_id",
            audit.path("actionId"),
            "staff_reason",
            audit.path("reason"),
            "evaluated_at",
            now.toString(),
            "request_payload",
            request,
            "request_hash",
            hash(request)));
    if (!off.isNull())
      insert(
          conn,
          "offers",
          obj(
              "id",
              off.get("id"),
              "version",
              1,
              "decision_id",
              result.get("id"),
              "application_id",
              result.get("applicationId"),
              "data_version",
              result.get("dataVersion"),
              "assessment_id",
              aid,
              "terms_id",
              off.get("termsId"),
              "product_id",
              off.get("productId"),
              "product_version",
              off.get("productVersion"),
              "type",
              off.get("type"),
              "issued_at",
              now.toString(),
              "expires_at",
              off.get("expiresAt")));
    insert(conn, "decision_documents", obj("decision_id", result.get("id"), "body", result));
  }

  Reply action(Connection conn, JsonNode row, JsonNode body, Instant now) throws SQLException {
    ObjectNode existing =
        one(
            conn,
            "SELECT request_payload,body FROM action_documents WHERE action_id=?",
            body.get("actionId"));
    if (existing != null) {
      conflict(
          existing.get("request_payload").equals(body)
              && existing.get("body").get("caseId").equals(row.get("id")),
          "actionId уже использован иначе");
      return new Reply(200, existing.get("body"));
    }
    require("OPEN".equals(text(row, "status")), 409, "INVALID_STATE", "Дело уже завершено");
    String action = text(body, "action");
    JsonNode request = row.get("request_payload");
    boolean allowed = false;
    for (JsonNode a : caseView(conn, row).get("allowedActions"))
      allowed |= a.asText().equals(action);
    require(
        allowed,
        409,
        action.equals("APPROVE") ? "SCORING_REQUIRED" : "INVALID_STATE",
        "Действие недоступно");
    valid(
        text(body, "reason").strip().codePointCount(0, text(body, "reason").strip().length()) >= 10,
        "Нужно обоснование от 10 символов");
    JsonNode resolution = body.get("factsResolution"), changes = body.get("changes");
    if (!resolution.isNull())
      require(
          resolution.get("verificationId").equals(row.get("verification_id")),
          422,
          "SNAPSHOT_MISMATCH",
          "Уточнение относится к другой проверке");
    if (!changes.isNull()) {
      boolean actual = false;
      Iterator<String> keys = changes.fieldNames();
      while (keys.hasNext()) {
        String k = keys.next();
        if (k.equals("productId"))
          actual |= !changes.get(k).equals(request.get("data").get("product").get("id"));
        else if (k.equals("productVersion"))
          actual |= !changes.get(k).equals(request.get("data").get("product").get("version"));
        else actual |= !changes.get(k).equals(request.get("data").get(k));
      }
      valid(actual || !resolution.isNull(), "Изменения не меняют данные");
    }
    JsonNode selected = null,
        auto =
            row.get("automatic_decision_id").isNull()
                ? null
                : document(conn, row.get("automatic_decision_id"));
    ObjectNode decision = null;
    if (action.equals("APPROVE")) {
      for (JsonNode t : auto.get("evaluatedTerms"))
        if (t.get("id").equals(body.get("termsId"))) {
          selected = t;
          break;
        }
      require(selected != null, 422, "SNAPSHOT_MISMATCH", "Условия не относятся к оценке дела");
      JsonNode verification = request.get("verification");
      require(
          verification.get("confirmed").asBoolean()
              && "COMPLETE".equals(text(verification, "outcome")),
          409,
          "SCORING_REQUIRED",
          "Нет подтвержденной проверки");
      ObjectNode recalculated =
          Policy.evaluate(
              request.get("data"),
              verification.get("facts"),
              number(selected, "amountRub"),
              (int) number(selected, "termMonths"));
      conflict(
          recalculated.get("eligible").asBoolean()
              && number(recalculated.get("risk"), "score") >= 500,
          "Условия не проходят проверку");
      for (String k : List.of("risk", "monthlyPaymentRub", "annualRateBps", "eligible"))
        conflict(
            recalculated.get(k).equals(selected.get(k)), "Результат повторной оценки отличается");
    }
    if (Set.of("APPROVE", "REJECT").contains(action)) {
      if (selected == null && auto != null)
        for (JsonNode t : auto.get("evaluatedTerms"))
          if (t.get("id").equals(auto.get("selectedTermsId"))) {
            selected = t;
            break;
          }
      decision = Policy.base(request, now);
      decision.put("kind", "MANUAL");
      decision.put("outcome", action.equals("APPROVE") ? "APPROVED" : "REJECTED");
      decision.set("assessmentId", auto == null ? node(null) : auto.get("assessmentId"));
      decision.set("risk", selected == null ? node(null) : selected.get("risk"));
      decision.set("evaluatedTerms", auto == null ? arr() : auto.get("evaluatedTerms"));
      decision.set("selectedTermsId", selected == null ? node(null) : selected.get("id"));
      decision.set(
          "reasons",
          arr(Policy.reason(action.equals("APPROVE") ? "APPROVED_BY_STAFF" : "REJECTED_BY_STAFF")));
      if (action.equals("APPROVE")) decision.set("offer", Policy.offer(request, selected, now));
      decision.set(
          "staffAudit",
          obj(
              "staffId",
              body.get("staffId"),
              "actionId",
              body.get("actionId"),
              "reason",
              body.get("reason"),
              "decidedAt",
              now.toString()));
      saveDecision(conn, request, decision, now, false);
    }
    insert(
        conn,
        "manual_review_actions",
        obj(
            "id",
            body.get("actionId"),
            "case_id",
            row.get("id"),
            "application_id",
            row.get("application_id"),
            "data_version",
            row.get("data_version"),
            "staff_id",
            body.get("staffId"),
            "action",
            action,
            "reason",
            body.get("reason"),
            "terms_id",
            body.get("termsId"),
            "requirements",
            body.get("requirements"),
            "changes",
            changes,
            "facts_resolution",
            resolution,
            "decision_id",
            decision == null ? null : decision.get("id"),
            "created_at",
            now.toString()));
    execute(
        conn,
        "UPDATE manual_review_cases SET status='RESOLVED',resolved_action_id=?,resolved_at=? WHERE id=?",
        body.get("actionId"),
        now,
        row.get("id"));
    ObjectNode result =
        obj(
            "id",
            body.get("actionId"),
            "caseId",
            row.get("id"),
            "applicationId",
            row.get("application_id"),
            "dataVersion",
            row.get("data_version"),
            "action",
            action,
            "staffId",
            body.get("staffId"),
            "reason",
            body.get("reason"),
            "createdAt",
            now.toString(),
            "decision",
            decision,
            "requirements",
            body.get("requirements"),
            "changes",
            changes,
            "factsResolution",
            resolution);
    insert(
        conn,
        "action_documents",
        obj("action_id", body.get("actionId"), "request_payload", body, "body", result));
    return new Reply(201, result);
  }

  Reply close(Connection conn, JsonNode row, JsonNode body, Instant now) throws SQLException {
    if ("CLOSED".equals(text(row, "status")))
      conflict(
          row.get("closure_reason").equals(body.get("reason")), "Дело закрыто по другой причине");
    else {
      require(
          "OPEN".equals(text(row, "status")),
          409,
          "INVALID_STATE",
          "Разрешенное дело нельзя закрыть");
      execute(
          conn,
          "UPDATE manual_review_cases SET status='CLOSED',closure_reason=?,resolved_at=? WHERE id=?",
          body.get("reason"),
          now,
          row.get("id"));
    }
    return new Reply(200, caseView(conn, getCase(conn, row.get("id"), false)));
  }
}
