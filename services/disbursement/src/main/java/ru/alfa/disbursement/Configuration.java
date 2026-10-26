package ru.alfa.disbursement;

import com.zaxxer.hikari.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@org.springframework.context.annotation.Configuration
class Configuration {
  @Bean
  HikariDataSource dataSource(Environment env) {
    String url = env.getRequiredProperty("DATABASE_URL");
    HikariConfig config = new HikariConfig();
    if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
      URI uri = URI.create(url);
      String authority = uri.getRawAuthority();
      if (uri.getRawUserInfo() != null) {
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        config.setUsername(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
        if (credentials.length == 2)
          config.setPassword(URLDecoder.decode(credentials[1], StandardCharsets.UTF_8));
        authority = authority.substring(authority.indexOf('@') + 1);
      }
      url =
          "jdbc:postgresql://"
              + authority
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
    if (!url.startsWith("jdbc:postgresql://"))
      throw new IllegalArgumentException("DATABASE_URL must identify PostgreSQL");
    config.setJdbcUrl(url);
    config.setMaximumPoolSize(16);
    config.setConnectionTimeout(2000);
    config.setInitializationFailTimeout(-1);
    config.addDataSourceProperty("connectTimeout", "2");
    config.addDataSourceProperty("socketTimeout", "5");
    return new HikariDataSource(config);
  }
}
