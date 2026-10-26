package ru.alfa.verification;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Properties;

final class Database {
  private final String jdbcUrl;
  private final Properties properties = new Properties();

  Database(String url) {
    if (url == null || url.isBlank())
      throw new IllegalArgumentException("DATABASE_URL is required");
    if (url.startsWith("jdbc:postgresql:")) jdbcUrl = url;
    else {
      URI uri = URI.create(url);
      if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme()))
        throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL URL");
      if (uri.getUserInfo() != null) {
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        properties.setProperty("user", URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
        if (credentials.length == 2)
          properties.setProperty(
              "password", URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
      }
      jdbcUrl =
          "jdbc:postgresql://"
              + uri.getHost()
              + ":"
              + (uri.getPort() < 0 ? 5432 : uri.getPort())
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
    properties.setProperty("connectTimeout", "2");
    properties.setProperty("socketTimeout", "10");
    properties.setProperty("ApplicationName", "verification");
  }

  Connection connect() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, properties);
  }
}
