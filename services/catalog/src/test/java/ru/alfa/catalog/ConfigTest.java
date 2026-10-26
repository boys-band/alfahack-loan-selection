package ru.alfa.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigTest {
  private static final String URL = "postgresql://catalog_app:password@localhost:5432/alfa_catalog";
  private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000099");

  @Test
  void readsRequiredConfigurationAndDefaultPort() {
    Config config =
        Config.fromEnvironment(
            Map.of(
                "DATABASE_URL",
                URL,
                "DEMO_CUSTOMER_TOKEN",
                "customer",
                "INTERNAL_API_TOKEN",
                "internal",
                "DEMO_ADMIN_TOKEN",
                "admin",
                "DEMO_ADMIN_ID",
                ADMIN_ID.toString()));
    assertEquals(8002, config.port());
    assertFalse(config.toString().contains("password"));
    assertFalse(config.toString().contains("customer"));
  }

  @Test
  void rejectsPrivilegedCredentialsOtherDatabasesAndMissingSecrets() {
    for (String url :
        new String[] {
          URL.replace("catalog_app", "postgres"),
          URL.replace("alfa_catalog", "alfa_applications"),
          URL + "?user=postgres",
          "invalid",
          "postgresql://password"
        }) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new Config(8002, url, "customer", "internal", "admin", ADMIN_ID));
      assertFalse(error.getMessage().contains("password@"));
    }
    assertThrows(IllegalArgumentException.class, () -> Config.fromEnvironment(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(8002, URL, "same", "same", "admin", ADMIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(8002, URL, " ", "internal", "admin", ADMIN_ID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(65536, URL, "customer", "internal", "admin", ADMIN_ID));
  }

  @Test
  void acceptsEncodedPasswordsAndTlsOptions() {
    assertDoesNotThrow(
        () ->
            new Config(
                8002,
                URL.replace("password", "p%40ss%3Aword") + "?sslmode=require",
                "customer",
                "internal",
                "admin",
                ADMIN_ID));
  }
}
