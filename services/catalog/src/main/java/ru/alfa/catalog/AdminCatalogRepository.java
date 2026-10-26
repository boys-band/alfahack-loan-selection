package ru.alfa.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;

final class AdminCatalogRepository {
  private final DataSource dataSource;

  AdminCatalogRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  List<AdminProductSummary> list() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT "
                    + ProductRepository.COLUMNS
                    + ", c.active FROM product_versions p JOIN current_products c"
                    + " ON c.product_id=p.product_id AND c.version=p.version ORDER BY p.product_id");
        ResultSet rows = query.executeQuery()) {
      List<AdminProductSummary> result = new ArrayList<>();
      while (rows.next())
        result.add(new AdminProductSummary(ProductRepository.map(rows), rows.getBoolean("active")));
      return List.copyOf(result);
    }
  }

  Optional<AdminProduct> find(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      Optional<AdminProduct> result = read(connection, id, false);
      connection.commit();
      return result;
    }
  }

  private Optional<AdminProduct> read(Connection connection, String id, boolean locked)
      throws SQLException {
    if (locked) {
      try (PreparedStatement pointer =
          connection.prepareStatement(
              "SELECT 1 FROM current_products WHERE product_id=? FOR UPDATE")) {
        pointer.setString(1, id);
        try (ResultSet rows = pointer.executeQuery()) {
          if (!rows.next()) return Optional.empty();
        }
      }
    }
    String sql =
        "SELECT "
            + ProductRepository.COLUMNS
            + ", c.active FROM product_versions p JOIN current_products c"
            + " ON c.product_id=p.product_id AND c.version=p.version WHERE c.product_id=?";
    Product current;
    boolean active;
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, id);
      try (ResultSet rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        current = ProductRepository.map(rows);
        active = rows.getBoolean("active");
      }
    }
    List<Product> versions = new ArrayList<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT "
                + ProductRepository.COLUMNS
                + " FROM product_versions p WHERE p.product_id=?"
                + " ORDER BY length(p.version), p.version")) {
      query.setString(1, id);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) versions.add(ProductRepository.map(rows));
      }
    }
    return Optional.of(new AdminProduct(current, active, versions));
  }

  Publication publish(
      String id,
      UUID actor,
      String path,
      String key,
      String ifMatch,
      JsonNode request,
      Function<JsonNode, Product> validate)
      throws SQLException {
    String fingerprint =
        AdminCodec.sha256("POST\n" + path + "\n" + ifMatch + "\n" + AdminCodec.canonical(request));
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AdminProduct before =
            read(connection, id, true)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND", "Product not found"));
        Publication replay = replay(connection, actor, path, key, fingerprint);
        if (replay != null) {
          connection.commit();
          return replay;
        }
        if (!AdminCodec.etag(before).equals(ifMatch))
          throw new Failure(412, "VERSION_CONFLICT", "Product changed; reload before publishing");
        if (!before.active())
          throw new Failure(409, "PRODUCT_ARCHIVED", "Archived product cannot be published");
        Product proposed = validate.apply(request);
        Product old = before.product();
        if (sameTerms(old, proposed))
          throw new Failure(422, "VALIDATION_ERROR", "At least one product field must change");
        String next =
            new BigInteger(before.versions().getLast().version()).add(BigInteger.ONE).toString();
        Product published =
            new Product(
                id,
                next,
                proposed.name(),
                proposed.description(),
                proposed.purposeCodes(),
                proposed.minAmountRub(),
                proposed.maxAmountRub(),
                proposed.minTermMonths(),
                proposed.maxTermMonths(),
                proposed.annualRateBps(),
                proposed.minBusinessAgeMonths(),
                proposed.maxDebtServiceRatioBps());
        try (PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO product_versions"
                    + " (product_id,version,name,description,purpose_codes,min_amount_rub,max_amount_rub,"
                    + " min_term_months,max_term_months,annual_rate_bps,min_business_age_months,max_debt_service_ratio_bps)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
          insert.setString(1, id);
          insert.setString(2, next);
          insert.setString(3, published.name());
          insert.setString(4, published.description());
          Array purposes = connection.createArrayOf("text", published.purposeCodes().toArray());
          try {
            insert.setArray(5, purposes);
            insert.setLong(6, published.minAmountRub());
            insert.setLong(7, published.maxAmountRub());
            insert.setInt(8, published.minTermMonths());
            insert.setInt(9, published.maxTermMonths());
            insert.setInt(10, published.annualRateBps());
            insert.setInt(11, published.minBusinessAgeMonths());
            insert.setInt(12, published.maxDebtServiceRatioBps());
            insert.executeUpdate();
          } finally {
            purposes.free();
          }
        }
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE current_products SET version=?, updated_at=clock_timestamp() WHERE product_id=?")) {
          update.setString(1, next);
          update.setString(2, id);
          update.executeUpdate();
        }
        try (PreparedStatement audit =
            connection.prepareStatement(
                "INSERT INTO product_publications(product_id,version,actor_id,reason) VALUES (?,?,?,?)")) {
          audit.setString(1, id);
          audit.setString(2, next);
          audit.setObject(3, actor);
          audit.setString(4, request.path("reason").asText());
          audit.executeUpdate();
        }
        List<Product> versions = new ArrayList<>(before.versions());
        versions.add(published);
        AdminProduct after = new AdminProduct(published, before.active(), versions);
        Publication result =
            new Publication(201, AdminCodec.canonical(after), AdminCodec.etag(after));
        save(connection, actor, path, key, fingerprint, result);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      }
    }
  }

  Publication create(
      UUID actor, String path, String key, JsonNode request, Function<JsonNode, Product> validate)
      throws SQLException {
    String fingerprint = AdminCodec.sha256("POST\n" + path + "\n" + AdminCodec.canonical(request));
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        advisoryLock(connection, "command:" + actor + ":" + path + ":" + key);
        Publication replay = replay(connection, actor, path, key, fingerprint);
        if (replay != null) {
          connection.commit();
          return replay;
        }
        Product proposed = validate.apply(request);
        String id = proposed.id();
        advisoryLock(connection, "product:" + id);
        Optional<AdminProduct> existing = read(connection, id, true);
        Publication result;
        if (existing.isPresent()) {
          String originalFingerprint = null;
          try (PreparedStatement query =
              connection.prepareStatement(
                  "SELECT request_fingerprint FROM program_lifecycle_events"
                      + " WHERE product_id=? AND action='CREATED'")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
              if (rows.next()) originalFingerprint = rows.getString(1).trim();
            }
          }
          if (!fingerprint.equals(originalFingerprint))
            throw new Failure(409, "PRODUCT_ALREADY_EXISTS", "Product ID is already reserved");
          AdminProduct current = existing.orElseThrow();
          result = new Publication(200, AdminCodec.canonical(current), AdminCodec.etag(current));
        } else {
          try (PreparedStatement occupied =
              connection.prepareStatement(
                  "SELECT EXISTS (SELECT 1 FROM product_versions WHERE product_id=?)")) {
            occupied.setString(1, id);
            try (ResultSet rows = occupied.executeQuery()) {
              rows.next();
              if (rows.getBoolean(1))
                throw new Failure(409, "PRODUCT_ALREADY_EXISTS", "Product ID is already reserved");
            }
          }
          insertVersion(connection, proposed);
          try (PreparedStatement pointer =
              connection.prepareStatement(
                  "INSERT INTO current_products(product_id,version,active) VALUES (?,'1',true)")) {
            pointer.setString(1, id);
            pointer.executeUpdate();
          }
          try (PreparedStatement publication =
              connection.prepareStatement(
                  "INSERT INTO product_publications(product_id,version,actor_id,reason) VALUES (?,'1',?,?)")) {
            publication.setString(1, id);
            publication.setObject(2, actor);
            publication.setString(3, request.path("reason").asText());
            publication.executeUpdate();
          }
          event(
              connection, id, "CREATED", actor, request.path("reason").asText(), "1", fingerprint);
          AdminProduct created = new AdminProduct(proposed, true, List.of(proposed));
          result = new Publication(201, AdminCodec.canonical(created), AdminCodec.etag(created));
        }
        save(connection, actor, path, key, fingerprint, result);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      }
    }
  }

  Publication archive(
      UUID actor, String id, String path, String key, String ifMatch, JsonNode request)
      throws SQLException {
    String fingerprint =
        AdminCodec.sha256("POST\n" + path + "\n" + ifMatch + "\n" + AdminCodec.canonical(request));
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AdminProduct before =
            read(connection, id, true)
                .orElseThrow(() -> new Failure(404, "NOT_FOUND", "Product not found"));
        Publication replay = replay(connection, actor, path, key, fingerprint);
        if (replay != null) {
          connection.commit();
          return replay;
        }
        if (!AdminCodec.etag(before).equals(ifMatch))
          throw new Failure(412, "VERSION_CONFLICT", "Product changed; reload before archiving");
        CatalogServer.archiveRequest(request);
        if (!before.active()) {
          Publication result =
              new Publication(200, AdminCodec.canonical(before), AdminCodec.etag(before));
          save(connection, actor, path, key, fingerprint, result);
          connection.commit();
          return result;
        }
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE current_products SET active=false,updated_at=clock_timestamp() WHERE product_id=?")) {
          update.setString(1, id);
          update.executeUpdate();
        }
        event(
            connection,
            id,
            "ARCHIVED",
            actor,
            request.path("reason").asText(),
            before.product().version(),
            null);
        AdminProduct after = new AdminProduct(before.product(), false, before.versions());
        Publication result =
            new Publication(200, AdminCodec.canonical(after), AdminCodec.etag(after));
        save(connection, actor, path, key, fingerprint, result);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      }
    }
  }

  private static void advisoryLock(Connection connection, String key) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      query.setString(1, key);
      query.executeQuery().close();
    }
  }

  private static Publication replay(
      Connection connection, UUID actor, String path, String key, String fingerprint)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT fingerprint,response_status,response_body,response_etag"
                + " FROM catalog_idempotency_records"
                + " WHERE caller_id=? AND method='POST' AND path=? AND key=?")) {
      query.setObject(1, actor);
      query.setString(2, path);
      query.setString(3, key);
      try (ResultSet rows = query.executeQuery()) {
        if (!rows.next()) return null;
        if (!fingerprint.equals(rows.getString("fingerprint").trim()))
          throw new Failure(409, "IDEMPOTENCY_CONFLICT", "Key was used for a different request");
        return new Publication(
            rows.getInt("response_status"),
            rows.getString("response_body"),
            rows.getString("response_etag"));
      }
    }
  }

  private static void save(
      Connection connection,
      UUID actor,
      String path,
      String key,
      String fingerprint,
      Publication result)
      throws SQLException {
    try (PreparedStatement record =
        connection.prepareStatement(
            "INSERT INTO catalog_idempotency_records"
                + " (caller_id,method,path,key,fingerprint,response_status,response_body,response_etag)"
                + " VALUES (?,'POST',?,?,?,?,?,?)")) {
      record.setObject(1, actor);
      record.setString(2, path);
      record.setString(3, key);
      record.setString(4, fingerprint);
      record.setInt(5, result.status());
      record.setString(6, result.body());
      record.setString(7, result.etag());
      record.executeUpdate();
    }
  }

  private static void event(
      Connection connection,
      String id,
      String action,
      UUID actor,
      String reason,
      String version,
      String fingerprint)
      throws SQLException {
    try (PreparedStatement audit =
        connection.prepareStatement(
            "INSERT INTO program_lifecycle_events"
                + " (event_id,product_id,action,actor_id,reason,version,request_fingerprint)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      audit.setObject(1, UUID.randomUUID());
      audit.setString(2, id);
      audit.setString(3, action);
      audit.setObject(4, actor);
      audit.setString(5, reason);
      audit.setString(6, version);
      audit.setString(7, fingerprint);
      audit.executeUpdate();
    }
  }

  private static void insertVersion(Connection connection, Product product) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO product_versions"
                + " (product_id,version,name,description,purpose_codes,min_amount_rub,max_amount_rub,"
                + " min_term_months,max_term_months,annual_rate_bps,min_business_age_months,max_debt_service_ratio_bps)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      insert.setString(1, product.id());
      insert.setString(2, product.version());
      insert.setString(3, product.name());
      insert.setString(4, product.description());
      Array purposes = connection.createArrayOf("text", product.purposeCodes().toArray());
      try {
        insert.setArray(5, purposes);
        insert.setLong(6, product.minAmountRub());
        insert.setLong(7, product.maxAmountRub());
        insert.setInt(8, product.minTermMonths());
        insert.setInt(9, product.maxTermMonths());
        insert.setInt(10, product.annualRateBps());
        insert.setInt(11, product.minBusinessAgeMonths());
        insert.setInt(12, product.maxDebtServiceRatioBps());
        insert.executeUpdate();
      } finally {
        purposes.free();
      }
    }
  }

  private static boolean sameTerms(Product a, Product b) {
    return a.name().equals(b.name())
        && a.description().equals(b.description())
        && a.purposeCodes().equals(b.purposeCodes())
        && a.minAmountRub() == b.minAmountRub()
        && a.maxAmountRub() == b.maxAmountRub()
        && a.minTermMonths() == b.minTermMonths()
        && a.maxTermMonths() == b.maxTermMonths()
        && a.annualRateBps() == b.annualRateBps()
        && a.minBusinessAgeMonths() == b.minBusinessAgeMonths()
        && a.maxDebtServiceRatioBps() == b.maxDebtServiceRatioBps();
  }

  record Publication(int status, String body, String etag) {}

  static final class Failure extends RuntimeException {
    final int status;
    final String code;

    Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
}
