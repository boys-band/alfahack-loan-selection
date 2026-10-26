package ru.alfa.decision;

import java.time.Clock;
import java.util.Objects;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    // Bound header/body timeouts are JDK HttpServer settings, not public API switches.
    System.setProperty("sun.net.httpserver.maxReqTime", "15");
    System.setProperty("sun.net.httpserver.maxRspTime", "15");
    Store store =
        new Store(
            Objects.requireNonNull(System.getenv("DATABASE_URL"), "DATABASE_URL is required"),
            Clock.systemUTC());
    HttpApi api =
        new HttpApi(
            store,
            System.getenv("INTERNAL_API_TOKEN"),
            "0.0.0.0",
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8004")));
    Runtime.getRuntime().addShutdownHook(new Thread(api::close));
    api.start();
  }
}
