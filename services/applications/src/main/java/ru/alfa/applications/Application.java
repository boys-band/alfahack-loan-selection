package ru.alfa.applications;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
  @Bean(destroyMethod = "close")
  Database database() {
    return new Database(Settings.fromEnvironment());
  }

  @Bean
  Service service(Database database) {
    var settings = Settings.fromEnvironment();
    return new Service(
        settings, database, new Dependencies(settings), Clock.systemUTC(), UUID::randomUUID);
  }

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("worker")) {
      var settings = Settings.fromEnvironment();
      try (var database = new Database(settings)) {
        var service =
            new Service(
                settings,
                database,
                new Dependencies(settings),
                Clock.systemUTC(),
                UUID::randomUUID);
        var worker = new Worker(service);
        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
        worker.run();
      }
    } else SpringApplication.run(Application.class, args);
  }
}
