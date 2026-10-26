package ru.alfa.catalog;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

public final class ProductRepository {
  static final String COLUMNS =
      "p.product_id, p.version, p.name, p.description, p.purpose_codes, "
          + "p.min_amount_rub, p.max_amount_rub, p.min_term_months, p.max_term_months, "
          + "p.annual_rate_bps, p.min_business_age_months, p.max_debt_service_ratio_bps";
  private static final String CURRENT =
      " FROM product_versions p JOIN current_products c "
          + "ON c.product_id=p.product_id AND c.version=p.version WHERE c.active";
  private final DataSource dataSource;

  public ProductRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  AdminCatalogRepository admin() {
    return new AdminCatalogRepository(dataSource);
  }

  public List<Product> listCurrent() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement("SELECT " + COLUMNS + CURRENT + " ORDER BY p.product_id");
        ResultSet rows = query.executeQuery()) {
      List<Product> products = new ArrayList<>();
      while (rows.next()) products.add(map(rows));
      return List.copyOf(products);
    }
  }

  public Optional<Product> find(String id, String version) throws SQLException {
    String sql =
        "SELECT "
            + COLUMNS
            + (version == null
                ? CURRENT + " AND p.product_id=?"
                : " FROM product_versions p WHERE p.product_id=? AND p.version=?");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, id);
      if (version != null) query.setString(2, version);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? Optional.of(map(rows)) : Optional.empty();
      }
    }
  }

  public boolean ready() throws SQLException {
    // Probe the expected columns and both pointers, independently of active/current version.
    // schema_migrations is deliberately not accessible to catalog_app.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT "
                    + COLUMNS
                    + ", p.created_at, c.version, c.active, c.updated_at"
                    + " FROM product_versions p JOIN current_products c ON c.product_id=p.product_id "
                    + "WHERE p.version='1' AND p.product_id IN ('working-capital','equipment-finance')");
        ResultSet rows = query.executeQuery()) {
      Set<String> seeds = new HashSet<>();
      while (rows.next()) seeds.add(map(rows).id());
      return seeds.size() == 2;
    }
  }

  static Product map(ResultSet row) throws SQLException {
    Array purposes = row.getArray("purpose_codes");
    try {
      // SQL permits duplicates in this array; the API requires uniqueItems.
      List<String> codes = Arrays.stream((String[]) purposes.getArray()).distinct().toList();
      return new Product(
          row.getString("product_id"),
          row.getString("version"),
          row.getString("name"),
          row.getString("description"),
          codes,
          row.getLong("min_amount_rub"),
          row.getLong("max_amount_rub"),
          row.getInt("min_term_months"),
          row.getInt("max_term_months"),
          row.getInt("annual_rate_bps"),
          row.getInt("min_business_age_months"),
          row.getInt("max_debt_service_ratio_bps"));
    } finally {
      purposes.free();
    }
  }
}
