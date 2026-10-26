package ru.alfa.agreements;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Always creates its own cluster/container. Never accepts an existing database URL. */
final class PostgresFixture implements AutoCloseable {
  private final String name = "agreements-it-" + UUID.randomUUID();
  private final Path root = Path.of("build", "integration", name).toAbsolutePath().normalize();
  private final String bin = System.getProperty("pg.bin", "");
  String adminUrl, runtimeUrl;
  private boolean started;

  PostgresFixture() throws Exception {
    try {
      Files.createDirectories(root);
      int port;
      if (!bin.isBlank()) {
        try (ServerSocket socket = new ServerSocket(0)) {
          port = socket.getLocalPort();
        }
        run(
            nativeCommand("initdb"),
            "-D",
            root.resolve("data").toString(),
            "-U",
            "postgres",
            "-A",
            "trust",
            "--encoding=UTF8",
            "--locale=C");
        run(
            nativeCommand("pg_ctl"),
            "-D",
            root.resolve("data").toString(),
            "-l",
            root.resolve("postgres.log").toString(),
            "-o",
            "-h 127.0.0.1 -p " + port,
            "-w",
            "start");
        started = true;
      } else {
        run(
            "docker",
            "run",
            "--detach",
            "--rm",
            "--name",
            name,
            "-e",
            "POSTGRES_PASSWORD=agreements-it-only",
            "-p",
            "127.0.0.1::5432",
            "postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea");
        started = true;
        String output = run("docker", "port", name, "5432/tcp").strip();
        port = Integer.parseInt(output.substring(output.lastIndexOf(':') + 1));
      }
      adminUrl =
          "jdbc:postgresql://127.0.0.1:"
              + port
              + "/postgres?user=postgres&password=agreements-it-only";
      SQLException last = null;
      for (int i = 0; i < 60; i++)
        try (Connection ignored = DriverManager.getConnection(adminUrl)) {
          last = null;
          break;
        } catch (SQLException e) {
          last = e;
          Thread.sleep(250);
        }
      if (last != null) throw last;
      try (Connection conn = DriverManager.getConnection(adminUrl);
          Statement st = conn.createStatement()) {
        st.execute("CREATE ROLE agreements_owner NOLOGIN");
        st.execute(
            "CREATE ROLE agreements_app LOGIN PASSWORD 'agreements-it-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        st.execute("CREATE DATABASE alfa_agreements OWNER agreements_owner");
      }
      adminUrl = adminUrl.replace("/postgres?", "/alfa_agreements?");
      runtimeUrl =
          "jdbc:postgresql://127.0.0.1:"
              + port
              + "/alfa_agreements?user=agreements_app&password=agreements-it-only";
      try (Connection conn = DriverManager.getConnection(adminUrl);
          Statement st = conn.createStatement()) {
        st.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC; SET ROLE agreements_owner");
        st.execute(Files.readString(Path.of("migrations/001_initial.sql")));

        st.execute(Files.readString(Path.of("../../database/seeds/agreements.sql")));
        st.execute(
            "CREATE TABLE schema_migrations(version text PRIMARY KEY); INSERT INTO schema_migrations VALUES ('001_initial'); RESET ROLE");
        st.execute(
            "GRANT USAGE ON SCHEMA public TO agreements_app; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO agreements_app; REVOKE ALL ON schema_migrations FROM agreements_app; REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC; GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO agreements_app");
      }
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private String nativeCommand(String command) {
    return Path.of(
            bin, command + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
        .toString();
  }

  private String run(String... args) throws Exception {
    Path out = root.resolve("command-" + UUID.randomUUID() + ".log");
    Process p =
        new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IOException("Test infrastructure command timed out");
    }
    String output = Files.readString(out);
    if (p.exitValue() != 0) throw new IOException("Test infrastructure failed: " + output);
    return output;
  }

  public void close() throws Exception {
    if (started) {
      if (bin.isBlank()) run("docker", "stop", name);
      else
        run(
            nativeCommand("pg_ctl"),
            "-D",
            root.resolve("data").toString(),
            "-m",
            "immediate",
            "-w",
            "stop");
      started = false;
    }
    Path allowed = Path.of("build", "integration").toAbsolutePath().normalize();
    if (!root.startsWith(allowed) || root.equals(allowed))
      throw new IllegalStateException("Unsafe cleanup target");
    if (Files.exists(root))
      try (var paths = Files.walk(root)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
  }
}
