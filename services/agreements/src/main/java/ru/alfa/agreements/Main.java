package ru.alfa.agreements;

import java.time.Clock;
import java.util.Objects;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    // Bound header/body timeouts are JDK HttpServer settings, not public API switches.
    System.setProperty("sun.net.httpserver.maxReqTime", "15");
    System.setProperty("sun.net.httpserver.maxRspTime", "15");
    try {
      Store store =
          new Store(
              Objects.requireNonNull(System.getenv("DATABASE_URL"), "DATABASE_URL is required"),
              Clock.systemUTC());
      HttpApi api =
          new HttpApi(
              store,
              System.getenv("INTERNAL_API_TOKEN"),
              "0.0.0.0",
              Integer.parseInt(System.getenv().getOrDefault("PORT", "8005")));
      Runtime.getRuntime().addShutdownHook(new Thread(api::close));
      api.start();
    } catch (Exception e) {
      // Invalid connection strings may contain credentials; never print their exception text.
      System.err.println("agreements startup failed: configuration or listener unavailable");
      System.exit(1);
    }
  }
}
