package ru.alfa.verification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** Local synthetic sources, queried within the result transaction's snapshot. */
final class SourceAdapter {
  static final class Unavailable extends RuntimeException {
    Unavailable() {
      super("Source fixtures are unavailable or invalid");
    }
  }

  Map<String, ObjectNode> read(Connection connection, UUID company) throws SQLException {
    Map<String, ObjectNode> sources = new HashMap<>();
    try (var statement =
        connection.prepareStatement(
            "SELECT source,payload FROM source_fixtures WHERE company_id=?")) {
      statement.setObject(1, company);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          var payload = Json.parse(rows.getString("payload"));
          if (!payload.isObject()) throw new Unavailable();
          sources.put(rows.getString("source"), (ObjectNode) payload);
        }
      }
    }
    if (!sources.keySet().equals(Set.of("REGISTRY", "BKI", "FNS"))) throw new Unavailable();
    return sources;
  }
}
