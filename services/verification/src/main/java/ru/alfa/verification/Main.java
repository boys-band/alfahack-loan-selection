package ru.alfa.verification;

import java.time.Clock;

public final class Main {
  public static void main(String[] args) throws Exception {
    try {
      int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8003"));
      var server =
          new VerificationServer(
              port,
              System.getenv("INTERNAL_API_TOKEN"),
              new Database(System.getenv("DATABASE_URL")),
              Clock.systemUTC());
      Runtime.getRuntime().addShutdownHook(new Thread(server::close));
      server.start();
      System.out.println("Verification listening on 0.0.0.0:" + server.port());
    } catch (IllegalArgumentException e) {
      System.err.println("Invalid configuration: check DATABASE_URL, INTERNAL_API_TOKEN and PORT");
      System.exit(1);
    }
  }
}
