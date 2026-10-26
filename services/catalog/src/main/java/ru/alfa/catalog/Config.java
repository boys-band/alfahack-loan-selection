package ru.alfa.catalog;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public record Config(
    int port,
    String databaseUrl,
    String customerToken,
    String internalToken,
    String adminToken,
    UUID adminId) {
  public Config {
    if (port < 0 || port > 65535)
      throw new IllegalArgumentException("PORT must be between 0 and 65535");
    if (!validToken(customerToken)
        || !validToken(internalToken)
        || !validToken(adminToken)
        || adminId == null
        || customerToken.equals(internalToken)
        || customerToken.equals(adminToken)
        || internalToken.equals(adminToken)) {
      throw new IllegalArgumentException(
          "Configure three distinct nonempty bearer tokens and admin ID");
    }
    databaseUri(databaseUrl);
  }

  public static Config fromEnvironment(Map<String, String> env) {
    try {
      int port = Integer.parseInt(env.getOrDefault("PORT", "8002"));
      if (port == 0) throw new IllegalArgumentException("PORT must be positive");
      return new Config(
          port,
          env.get("DATABASE_URL"),
          env.get("DEMO_CUSTOMER_TOKEN"),
          env.get("INTERNAL_API_TOKEN"),
          env.get("DEMO_ADMIN_TOKEN"),
          UUID.fromString(env.getOrDefault("DEMO_ADMIN_ID", "")));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("PORT must be an integer");
    }
  }

  private static boolean validToken(String token) {
    return token != null && token.matches("[A-Za-z0-9._~+/=-]+") && !token.isBlank();
  }

  private static URI databaseUri(String url) {
    try {
      URI uri = URI.create(url);
      if (!"postgresql".equals(uri.getScheme())
          || uri.getHost() == null
          || !"/alfa_catalog".equals(uri.getPath())
          || uri.getFragment() != null
          || uri.getRawUserInfo() == null
          || !uri.getRawUserInfo().contains(":")) {
        throw new IllegalArgumentException();
      }
      String username = decode(uri.getRawUserInfo().split(":", 2)[0]);
      if (!"catalog_app".equals(username)) throw new IllegalArgumentException();
      // Credentials have one source. JDBC query options must not override the runtime role.
      if (uri.getRawQuery() != null) {
        for (String part : uri.getRawQuery().split("&")) {
          String key = decode(part.split("=", 2)[0]);
          if (!key.equals("sslmode") && !key.equals("sslrootcert"))
            throw new IllegalArgumentException();
        }
      }
      return uri;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "DATABASE_URL requires a PostgreSQL URL with credentials for catalog_app on alfa_catalog (optional sslmode/sslrootcert)");
    }
  }

  private static String decode(String text) {
    return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  public HikariDataSource dataSource() {
    URI uri = databaseUri(databaseUrl);
    String[] credentials = uri.getRawUserInfo().split(":", 2);
    HikariConfig pool = new HikariConfig();
    pool.setJdbcUrl(
        "jdbc:postgresql://"
            + uri.getHost()
            + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
            + uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    pool.setUsername(decode(credentials[0]));
    pool.setPassword(decode(credentials[1]));
    pool.setMaximumPoolSize(8);
    pool.setMinimumIdle(0);
    pool.setConnectionTimeout(2500);
    pool.setValidationTimeout(1000);
    pool.setInitializationFailTimeout(-1);
    pool.setReadOnly(false);
    pool.addDataSourceProperty("connectTimeout", "2");
    pool.addDataSourceProperty("socketTimeout", "3");
    pool.addDataSourceProperty(
        "options", "-c statement_timeout=3000 -c search_path=public,pg_catalog");
    return new HikariDataSource(pool);
  }

  @Override
  public String toString() {
    return "Config[port=" + port + ", credentials=redacted]";
  }
}
