package ru.alfa.catalog;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.CountDownLatch;

public final class Main {
  public static void main(String[] args) throws Exception {
    Config config;
    try {
      config = Config.fromEnvironment(System.getenv());
    } catch (IllegalArgumentException error) {
      System.err.println(error.getMessage());
      System.exit(1);
      return;
    }
    HikariDataSource pool = config.dataSource();
    CatalogServer server = new CatalogServer(config, new ProductRepository(pool));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.close();
                  pool.close();
                },
                "catalog-shutdown"));
    server.start();
    System.out.println("catalog listening on port=" + server.port());
    new CountDownLatch(1).await();
  }
}
