package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class Database implements AutoCloseable {
  private final HikariDataSource pool;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public Database(Settings settings) {
    var uri = settings.databaseUri();
    if (!"/alfa_applications".equals(uri.getPath()))
      throw new IllegalArgumentException("Runtime can only use alfa_applications");
    String userInfo = uri.getUserInfo();
    if (userInfo == null || !userInfo.startsWith("applications_app:"))
      throw new IllegalArgumentException("Runtime must use applications_app");
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + uri.getHost()
            + ":"
            + (uri.getPort() < 0 ? 5432 : uri.getPort())
            + uri.getPath());
    config.setUsername("applications_app");
    config.setPassword(userInfo.substring(userInfo.indexOf(':') + 1));
    config.setMaximumPoolSize(12);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setInitializationFailTimeout(-1);
    config.addDataSourceProperty("connectTimeout", "3");
    config.addDataSourceProperty("socketTimeout", "20");
    config.addDataSourceProperty("options", "-c timezone=UTC -c statement_timeout=15000");
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
  }

  public <T> T tx(Supplier<T> action) {
    return transactions.execute(status -> action.get());
  }

  public void run(Runnable action) {
    tx(
        () -> {
          action.run();
          return null;
        });
  }

  private Object parameter(Object value) {
    if (value instanceof com.fasterxml.jackson.databind.JsonNode json) {
      PGobject result = new PGobject();
      try {
        result.setType("jsonb");
        result.setValue(canonical(json));
      } catch (SQLException ex) {
        throw new IllegalStateException(ex);
      }
      return result;
    }
    if (value instanceof Instant time) return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    return value;
  }

  private Object[] params(Object... values) {
    return Arrays.stream(values).map(this::parameter).toArray();
  }

  public int update(String sql, Object... params) {
    return jdbc.update(sql, params(params));
  }

  public List<ObjectNode> rows(String sql, Object... params) {
    return jdbc.query(
        sql,
        (rs, index) -> {
          ObjectNode row = obj();
          var metadata = rs.getMetaData();
          for (int i = 1; i <= metadata.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof PGobject pg
                && ("jsonb".equals(pg.getType()) || "json".equals(pg.getType())))
              value = parse(pg.getValue());
            else if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
            else if (value instanceof java.sql.Date date) value = date.toLocalDate();
            put(row, metadata.getColumnLabel(i), value);
          }
          return row;
        },
        params(params));
  }

  public ObjectNode one(String sql, Object... params) {
    var result = rows(sql, params);
    return result.isEmpty() ? null : result.getFirst();
  }

  public static UUID uuid(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  public static UUID uuid(com.fasterxml.jackson.databind.JsonNode target, String key) {
    return uuid(s(target, key));
  }

  @Override
  public void close() {
    pool.close();
  }
}
