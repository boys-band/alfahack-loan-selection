package ru.alfa.applications;

import java.net.URI;
import java.util.*;

public record Settings(
    String databaseUrl,
    String customerToken,
    String customerId,
    String staffToken,
    String staffId,
    String internalToken,
    String adminToken,
    Map<String, String> services,
    int port,
    int leaseSeconds) {
  public Settings {
    if (customerToken.isBlank()
        || staffToken.isBlank()
        || internalToken.isBlank()
        || adminToken.isBlank()
        || new HashSet<>(List.of(customerToken, staffToken, internalToken, adminToken)).size() != 4)
      throw new IllegalArgumentException("Tokens must be distinct and nonempty");
    if (!UUID.fromString(customerId).toString().equals(customerId)
        || !UUID.fromString(staffId).toString().equals(staffId))
      throw new IllegalArgumentException("Configured identities must be canonical UUIDs");
    if (leaseSeconds < 30) throw new IllegalArgumentException("Lease is too short");
    services = Map.copyOf(services);
  }

  public static Settings fromEnvironment() {
    return from(System.getenv());
  }

  public static Settings from(Map<String, String> env) {
    Map<String, String> services = new HashMap<>();
    String[] names = {"catalog", "verification", "decision", "agreements", "disbursement"};
    for (int i = 0; i < names.length; i++)
      services.put(
          names[i],
          env.getOrDefault(
              names[i].toUpperCase(Locale.ROOT) + "_URL", "http://" + names[i] + ":" + (8002 + i)));
    return new Settings(
        env.getOrDefault(
            "DATABASE_URL",
            "postgresql://applications_app:demo-applications-only@localhost:5432/alfa_applications"),
        env.getOrDefault("DEMO_CUSTOMER_TOKEN", "demo-customer-only"),
        env.getOrDefault("DEMO_CUSTOMER_ID", "00000000-0000-4000-8000-000000000001"),
        env.getOrDefault("DEMO_STAFF_TOKEN", "demo-staff-only"),
        env.getOrDefault("DEMO_STAFF_ID", "00000000-0000-4000-8000-000000000002"),
        env.getOrDefault("INTERNAL_API_TOKEN", "demo-internal-only"),
        env.getOrDefault("DEMO_ADMIN_TOKEN", "demo-product-admin-only"),
        services,
        Integer.parseInt(env.getOrDefault("PORT", "8001")),
        45);
  }

  public URI databaseUri() {
    try {
      return URI.create(databaseUrl.startsWith("jdbc:") ? databaseUrl.substring(5) : databaseUrl);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("DATABASE_URL must be a valid PostgreSQL URI");
    }
  }
}
