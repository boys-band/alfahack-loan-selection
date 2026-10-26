package ru.alfa.applications;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Owns exactly one randomly named disposable container; never touches a developer database. */
final class PostgresHarness implements AutoCloseable {
  static final String IMAGE =
      "postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
  private final String name = "applications-it-" + UUID.randomUUID().toString().substring(0, 12);
  String url;

  PostgresHarness() throws Exception {
    try {
      String hostPort = "";
      if (System.getProperty("os.name").startsWith("Windows")) {
        // Docker's Linux port allocator does not know Windows excluded port ranges.
        // Ask the host OS for an available loopback port before publishing it.
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
          hostPort = Integer.toString(socket.getLocalPort());
        }
      }
      command(
          "docker",
          "run",
          "--detach",
          "--rm",
          "--name",
          name,
          "-e",
          "POSTGRES_PASSWORD=isolated-demo-only",
          "-p",
          "127.0.0.1:" + hostPort + ":5432",
          IMAGE);
      String binding = command("docker", "port", name, "5432/tcp").strip();
      String port = binding.substring(binding.lastIndexOf(':') + 1);
      url =
          "postgresql://applications_app:isolated-demo-only@127.0.0.1:"
              + port
              + "/alfa_applications";
      Connection admin = null;
      for (int i = 0; i < 480; i++) {
        try {
          admin =
              DriverManager.getConnection(
                  "jdbc:postgresql://127.0.0.1:" + port + "/postgres?connectTimeout=2",
                  "postgres",
                  "isolated-demo-only");
          break;
        } catch (SQLException ex) {
          Thread.sleep(250);
        }
      }
      if (admin == null) throw new IllegalStateException("PostgreSQL did not start");
      try (var connection = admin;
          var statement = connection.createStatement()) {
        statement.execute(
            "CREATE ROLE applications_owner NOLOGIN; CREATE ROLE applications_app LOGIN PASSWORD"
                + " 'isolated-demo-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        statement.execute("CREATE DATABASE alfa_applications OWNER applications_owner");
        statement.execute(
            "REVOKE CONNECT,TEMPORARY ON DATABASE alfa_applications FROM PUBLIC; GRANT CONNECT ON"
                + " DATABASE alfa_applications TO applications_app");
      }
      try (var connection =
              DriverManager.getConnection(
                  "jdbc:postgresql://127.0.0.1:" + port + "/alfa_applications",
                  "postgres",
                  "isolated-demo-only");
          var statement = connection.createStatement()) {
        statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC; SET ROLE applications_owner");
        statement.execute(Files.readString(Path.of("migrations/001_initial.sql")));
        statement.execute(
            "CREATE TABLE schema_migrations(version text PRIMARY KEY,checksum varchar(64) NOT"
                + " NULL,applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),seeded_at"
                + " timestamptz); INSERT INTO schema_migrations(version,checksum)"
                + " VALUES('001_initial',repeat('0',64))");
        statement.execute(Files.readString(Path.of("src/main/resources/contract/seed.sql")));
        statement.execute(
            "GRANT USAGE ON SCHEMA public TO applications_app; GRANT SELECT,INSERT,UPDATE,DELETE ON"
                + " ALL TABLES IN SCHEMA public TO applications_app; REVOKE ALL ON"
                + " schema_migrations FROM applications_app; REVOKE EXECUTE ON ALL FUNCTIONS IN"
                + " SCHEMA public FROM PUBLIC; GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO"
                + " applications_app");
      }
      applyMigration();
    } catch (Exception ex) {
      close();
      throw ex;
    }
  }

  void applyMigration() throws Exception {
    command(
        "docker",
        "exec",
        name,
        "mkdir",
        "-p",
        "/tmp/applications/scripts",
        "/tmp/applications/migrations");
    command("docker", "cp", "scripts/migrate.sh", name + ":/tmp/applications/scripts/migrate.sh");
    command(
        "docker",
        "cp",
        "migrations/002_result_history.sql",
        name + ":/tmp/applications/migrations/002_result_history.sql");
    command(
        "docker",
        "cp",
        "migrations/003_staff_registry_index.sql",
        name + ":/tmp/applications/migrations/003_staff_registry_index.sql");
    command(
        "docker",
        "exec",
        "-e",
        "MIGRATION_DATABASE_URL=postgresql://postgres:isolated-demo-only@127.0.0.1:5432/alfa_applications",
        name,
        "bash",
        "/tmp/applications/scripts/migrate.sh");
  }

  private static String command(String... args) throws Exception {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IOException("Docker command failed: " + output);
    return output;
  }

  @Override
  public void close() {
    try {
      command("docker", "rm", "-f", name);
    } catch (Exception ignored) {
    }
  }
}
