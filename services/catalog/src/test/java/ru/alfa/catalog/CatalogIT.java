package ru.alfa.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.yaml.snakeyaml.Yaml;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogIT {
  private static final String POSTGRES =
      "postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path root = Path.of("../..").toAbsolutePath().normalize();
  private final String container = "catalog-it-" + UUID.randomUUID();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private HikariDataSource pool;
  private CatalogServer server;
  private Config config;
  private JsonNode schemas;

  @BeforeAll
  void startDatabaseAndHttp() throws Exception {
    try {
      schemas =
          JSON.valueToTree(
              new Yaml().load(Files.readString(root.resolve("contracts/schemas.yaml"))));
      int publishedPort;
      try (ServerSocket reservation = new ServerSocket(0)) {
        publishedPort = reservation.getLocalPort();
      }
      command(
          null,
          "docker",
          "run",
          "-d",
          "--name",
          container,
          "--label",
          "alfa.test=catalog",
          "--tmpfs",
          "/var/lib/postgresql/data:rw",
          "-e",
          "POSTGRES_PASSWORD=it-admin-only",
          "-p",
          "127.0.0.1:" + publishedPort + ":5432",
          POSTGRES);
      awaitDatabase();
      sql(
          """
                    CREATE ROLE catalog_owner NOLOGIN;
                    CREATE ROLE catalog_app LOGIN PASSWORD 'it-runtime-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
                    CREATE DATABASE alfa_catalog OWNER catalog_owner;
                    REVOKE CONNECT, TEMPORARY ON DATABASE alfa_catalog FROM PUBLIC;
                    GRANT CONNECT ON DATABASE alfa_catalog TO catalog_app;
                    CREATE DATABASE alfa_applications;
                    REVOKE CONNECT ON DATABASE alfa_applications FROM PUBLIC;
                    """,
          "postgres",
          "postgres");
      String mapping = command(null, "docker", "port", container, "5432/tcp").strip();
      int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
      config =
          new Config(
              0,
              "postgresql://catalog_app:it-runtime-only@127.0.0.1:" + port + "/alfa_catalog",
              "it-customer",
              "it-internal",
              "it-admin",
              UUID.fromString("00000000-0000-4000-8000-000000000099"));
      startHttp();
    } catch (Throwable failure) {
      cleanup();
      throw failure;
    }
  }

  private void startHttp() throws IOException {
    pool = config.dataSource();
    server = new CatalogServer(config, new ProductRepository(pool));
    server.start();
  }

  @AfterAll
  void cleanup() throws Exception {
    if (server != null) server.close();
    if (pool != null) pool.close();
    command(null, "docker", "rm", "-f", container);
  }

  @Test
  @Order(1)
  void readinessRequiresSchemaAndExpectedSeedButLivenessDoesNot() throws Exception {
    get("/health/live", null, 200, "Health");
    get("/health/ready", null, 503, "Problem");
    get("/v2/products", "it-customer", 503, "Problem");
    owner(
        "REVOKE ALL ON SCHEMA public FROM PUBLIC;\n"
            + Files.readString(Path.of("migrations/001_initial.sql"))
            + Files.readString(Path.of("migrations/002_admin_publication.sql"))
            + Files.readString(Path.of("migrations/003_program_lifecycle.sql"))
            + """
                CREATE TABLE schema_migrations (version text PRIMARY KEY);
                GRANT USAGE ON SCHEMA public TO catalog_app;
                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO catalog_app;
                REVOKE ALL ON schema_migrations FROM catalog_app;
                REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
                GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO catalog_app;
                """);
    get("/health/ready", null, 503, "Problem");
    assertTrue(get("/v2/products", "it-customer", 200, "ProductList").path("items").isEmpty());
    owner(Files.readString(root.resolve("database/seeds/catalog.sql")));
    get("/health/ready", null, 200, "Health");
  }

  @Test
  @Order(2)
  void readsActualSeedWithExactTypesAndUnitsForBothCallers() throws Exception {
    JsonNode seed =
        JSON.readTree(root.resolve("contracts/examples/seed.json").toFile()).path("products");
    for (String token : List.of("it-customer", "it-internal")) {
      JsonNode products = get("/v2/products", token, 200, "ProductList").path("items");
      assertEquals(2, products.size());
      for (JsonNode expected : seed) {
        assertEquals(
            expected, get("/v2/products/" + expected.path("id").asText(), token, 200, "Product"));
        assertEquals(
            expected,
            get(
                "/v2/products/" + expected.path("id").asText() + "?version=1",
                token,
                200,
                "Product"));
        assertTrue(contains(products, expected));
      }
    }
  }

  @Test
  @Order(3)
  void rejectsMissingInvalidAndStaffTokensWithoutLeakingData() throws Exception {
    for (String token : Arrays.asList(null, "wrong-token", "it-staff")) {
      JsonNode problem = get("/v2/products", token, 401, "Problem");
      assertEquals("UNAUTHENTICATED", problem.path("code").asText());
    }
    for (String auth :
        List.of(
            "Basic it-customer",
            "Bearer",
            "Bearer  it-customer",
            "Bearer it-customer,it-internal")) {
      response(
          HttpRequest.newBuilder(uri("/v2/products")).header("Authorization", auth).GET().build(),
          401,
          "Problem");
    }
    response(
        HttpRequest.newBuilder(uri("/v2/products"))
            .header("Authorization", "bearer it-customer")
            .GET()
            .build(),
        200,
        "ProductList");
    get("/v2/products/WORKING-CAPITAL?version=0", null, 401, "Problem");
    response(
        HttpRequest.newBuilder(uri("/v2/products")).header("X-Request-Id", "bad").GET().build(),
        401,
        "Problem");
  }

  @Test
  @Order(4)
  void validatesVersionIdentifiersHeadersAndUnknownParameters() throws Exception {
    for (String value :
        List.of("", "0", "01", "-1", "1.0", "null", "%20", "%2B1", "1%0A", "%D9%A1")) {
      JsonNode problem =
          get("/v2/products/working-capital?version=" + value, "it-customer", 422, "Problem");
      assertEquals("VALIDATION_ERROR", problem.path("code").asText());
      assertEquals("/version", problem.path("errors").get(0).path("path").asText());
    }
    for (String path :
        List.of(
            "/v2/products/a",
            "/v2/products/WORKING-CAPITAL",
            "/v2/products/" + "x".repeat(65),
            "/v2/products/working-capital?version=1&version=2",
            "/v2/products?version=1",
            "/v2/products?extra=yes",
            "/v2/products/working-capital?extra=yes")) {
      get(path, "it-customer", 422, "Problem");
    }
    for (String id : List.of("bad", "00000000-0000-4000-8000-00000000000A")) {
      response(
          HttpRequest.newBuilder(uri("/v2/products"))
              .header("Authorization", "Bearer it-customer")
              .header("X-Request-Id", id)
              .GET()
              .build(),
          422,
          "Problem");
    }
    response(
        HttpRequest.newBuilder(uri("/v2/products"))
            .header("Authorization", "Bearer it-customer")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Request-Id", UUID.randomUUID().toString())
            .GET()
            .build(),
        422,
        "Problem");
  }

  @Test
  @Order(5)
  void returns404ForUnknownProductAndArbitrarilyLargeValidVersion() throws Exception {
    for (String path :
        List.of(
            "/v2/products/does-not-exist",
            "/v2/products/working-capital?version=999",
            "/v2/products/working-capital?version=" + "9".repeat(150),
            "/v2/products/working-capital/extra",
            "/unknown")) {
      assertEquals("NOT_FOUND", get(path, "it-customer", 404, "Problem").path("code").asText());
    }
  }

  @Test
  @Order(6)
  void rejectsMutationRoutesAndBodies() throws Exception {
    response(
        HttpRequest.newBuilder(uri("/v2/products"))
            .header("Authorization", "Bearer it-customer")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        405,
        "Problem");
    response(
        HttpRequest.newBuilder(uri("/v2/products"))
            .header("Authorization", "Bearer it-customer")
            .method("GET", HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        422,
        "Problem");
  }

  @Test
  @Order(6)
  void adminPublishesImmutableVersionAndReplaysExactCommand() throws Exception {
    String path = "/v2/admin/products/equipment-finance";
    JsonNode listing = get("/v2/admin/products", "it-admin", 200, "AdminProductList");
    assertEquals(2, listing.path("items").size());
    get("/v2/admin/products", "it-customer", 403, "Problem");
    get("/v2/admin/products", "it-internal", 403, "Problem");
    HttpResponse<String> before =
        client.send(
            HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer it-admin")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    JsonNode detail = check(before, 200, "AdminProduct");
    String oldEtag = before.headers().firstValue("ETag").orElseThrow();
    assertEquals("\"" + AdminCodec.sha256(AdminCodec.canonical(detail)) + "\"", oldEtag);
    ObjectNode change = (ObjectNode) detail.path("product").deepCopy();
    change.remove(List.of("id", "version"));
    change.put("annualRateBps", 2000);
    change.put("reason", "Демо: снижение ставки на оборудование");
    String body = JSON.writeValueAsString(change);
    String commandPath = path + "/versions";
    ObjectNode shortUnicodeReason = change.deepCopy();
    shortUnicodeReason.put("reason", "😀".repeat(5));
    assertEquals(
        "VALIDATION_ERROR",
        response(
                HttpRequest.newBuilder(uri(commandPath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("If-Match", oldEtag)
                    .header("Idempotency-Key", "publish-equipment-short-unicode")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(shortUnicodeReason)))
                    .build(),
                422,
                "Problem")
            .path("code")
            .asText());
    for (String empty : List.of("", " \t\r\n ")) {
      assertEquals(
          "MALFORMED_JSON",
          response(
                  HttpRequest.newBuilder(uri(commandPath))
                      .header("Authorization", "Bearer it-admin")
                      .header("Content-Type", "application/json")
                      .header("If-Match", oldEtag)
                      .header("Idempotency-Key", "publish-equipment-empty-" + empty.length())
                      .POST(HttpRequest.BodyPublishers.ofString(empty))
                      .build(),
                  400,
                  "Problem")
              .path("code")
              .asText());
    }
    List<ObjectNode> invalidBounds = new ArrayList<>();
    for (Map.Entry<String, Integer> entry :
        Map.of(
                "minAmountRub", 0,
                "maxAmountRub", 99999,
                "minTermMonths", 0,
                "maxTermMonths", 11,
                "annualRateBps", 10001,
                "minBusinessAgeMonths", -1,
                "maxDebtServiceRatioBps", 0)
            .entrySet()) {
      ObjectNode invalidBody = change.deepCopy();
      invalidBody.put(entry.getKey(), entry.getValue());
      invalidBounds.add(invalidBody);
    }
    ObjectNode emptyName = change.deepCopy();
    emptyName.put("name", "");
    invalidBounds.add(emptyName);
    ObjectNode blankDescription = change.deepCopy();
    blankDescription.put("description", "  ");
    invalidBounds.add(blankDescription);
    ObjectNode longReason = change.deepCopy();
    longReason.put("reason", "a".repeat(2001));
    invalidBounds.add(longReason);
    ObjectNode duplicatePurpose = change.deepCopy();
    duplicatePurpose.putArray("purposeCodes").add("EQUIPMENT").add("EQUIPMENT");
    invalidBounds.add(duplicatePurpose);
    for (int index = 0; index < invalidBounds.size(); index++) {
      assertEquals(
          "VALIDATION_ERROR",
          response(
                  HttpRequest.newBuilder(uri(commandPath))
                      .header("Authorization", "Bearer it-admin")
                      .header("Content-Type", "application/json")
                      .header("If-Match", oldEtag)
                      .header("Idempotency-Key", "publish-equipment-bound-" + index)
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              JSON.writeValueAsString(invalidBounds.get(index))))
                      .build(),
                  422,
                  "Problem")
              .path("code")
              .asText());
    }
    HttpRequest first =
        HttpRequest.newBuilder(uri(commandPath))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("If-Match", oldEtag)
            .header("Idempotency-Key", "publish-equipment-v2")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> accepted = client.send(first, HttpResponse.BodyHandlers.ofString());
    JsonNode published = check(accepted, 201, "AdminProduct");
    assertEquals("2", published.path("product").path("version").asText());
    assertEquals(2000, published.path("product").path("annualRateBps").asInt());
    assertEquals(2, published.path("versions").size());
    HttpResponse<String> replayed = client.send(first, HttpResponse.BodyHandlers.ofString());
    check(replayed, 201, "AdminProduct");
    assertEquals(accepted.body(), replayed.body());
    assertEquals(accepted.headers().firstValue("ETag"), replayed.headers().firstValue("ETag"));
    try (Connection connection = pool.getConnection();
        PreparedStatement audit =
            connection.prepareStatement(
                "SELECT actor_id,reason FROM product_publications"
                    + " WHERE product_id='equipment-finance' AND version='2'");
        ResultSet row = audit.executeQuery()) {
      assertTrue(row.next());
      assertEquals(config.adminId(), row.getObject("actor_id"));
      assertEquals("Демо: снижение ставки на оборудование", row.getString("reason"));
      assertFalse(row.next());
    }
    try (Connection connection = pool.getConnection();
        PreparedStatement receipts =
            connection.prepareStatement(
                "SELECT count(*) FROM catalog_idempotency_records"
                    + " WHERE path=? AND key='publish-equipment-v2'")) {
      receipts.setString(1, commandPath);
      try (ResultSet count = receipts.executeQuery()) {
        assertTrue(count.next());
        assertEquals(1, count.getInt(1));
      }
    }
    assertEquals(
        "VERSION_CONFLICT",
        response(
                HttpRequest.newBuilder(uri(commandPath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("If-Match", oldEtag)
                    .header("Idempotency-Key", "publish-equipment-stale")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                412,
                "Problem")
            .path("code")
            .asText());
    ObjectNode conflict = change.deepCopy();
    conflict.put("annualRateBps", 1900);
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        response(
                HttpRequest.newBuilder(uri(commandPath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("If-Match", oldEtag)
                    .header("Idempotency-Key", "publish-equipment-v2")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(conflict)))
                    .build(),
                409,
                "Problem")
            .path("code")
            .asText());
    ObjectNode invalid = change.deepCopy();
    invalid.put("annualRateBps", 10001);
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        response(
                HttpRequest.newBuilder(uri(commandPath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("If-Match", oldEtag)
                    .header("Idempotency-Key", "publish-equipment-v2")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(invalid)))
                    .build(),
                409,
                "Problem")
            .path("code")
            .asText());
    response(
        HttpRequest.newBuilder(uri(commandPath))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/jsonjunk")
            .header("If-Match", oldEtag)
            .header("Idempotency-Key", "publish-equipment-bad-type")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        422,
        "Problem");
    assertEquals(
        "2",
        get("/v2/products/equipment-finance", "it-customer", 200, "Product")
            .path("version")
            .asText());
    assertEquals(
        "1",
        get("/v2/products/equipment-finance?version=1", "it-customer", 200, "Product")
            .path("version")
            .asText());

    HttpResponse<String> concurrentBase =
        client.send(
            HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer it-admin")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    ObjectNode third =
        (ObjectNode) check(concurrentBase, 200, "AdminProduct").path("product").deepCopy();
    third.remove(List.of("id", "version"));
    third.put("annualRateBps", 1900);
    third.put("reason", "😀".repeat(10));
    ObjectNode competing = third.deepCopy();
    competing.put("annualRateBps", 1800);
    String currentEtag = concurrentBase.headers().firstValue("ETag").orElseThrow();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      List<Future<HttpResponse<String>>> results = new ArrayList<>();
      for (ObjectNode candidate : List.of(third, competing)) {
        String key = "publish-equipment-concurrent-" + results.size();
        results.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  release.await();
                  return client.send(
                      HttpRequest.newBuilder(uri(commandPath))
                          .header("Authorization", "Bearer it-admin")
                          .header("Content-Type", "application/json")
                          .header("If-Match", currentEtag)
                          .header("Idempotency-Key", key)
                          .POST(
                              HttpRequest.BodyPublishers.ofString(
                                  JSON.writeValueAsString(candidate)))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
                }));
      }
      boolean bothReady = ready.await(5, TimeUnit.SECONDS);
      release.countDown();
      assertTrue(bothReady);
      List<Integer> statuses = new ArrayList<>();
      for (Future<HttpResponse<String>> result : results) {
        HttpResponse<String> reply = result.get(15, TimeUnit.SECONDS);
        statuses.add(reply.statusCode());
        if (reply.statusCode() == 201) check(reply, 201, "AdminProduct");
        else assertEquals("VERSION_CONFLICT", check(reply, 412, "Problem").path("code").asText());
      }
      assertEquals(List.of(201, 412), statuses.stream().sorted().toList());
    }
    assertEquals(
        "3",
        get("/v2/products/equipment-finance", "it-customer", 200, "Product")
            .path("version")
            .asText());
    assertEquals(
        "2",
        get("/v2/products/equipment-finance?version=2", "it-customer", 200, "Product")
            .path("version")
            .asText());
    try (Connection connection = pool.getConnection();
        PreparedStatement count =
            connection.prepareStatement(
                "SELECT count(*) FROM product_publications WHERE product_id='equipment-finance' AND version='3'");
        ResultSet row = count.executeQuery()) {
      assertTrue(row.next());
      assertEquals(1, row.getInt(1));
    }
  }

  @Test
  @Order(7)
  void adminCreatesAndArchivesWithoutLosingHistoricalVersion() throws Exception {
    String path = "/v2/admin/products";
    ObjectNode body =
        (ObjectNode) get("/v2/products/working-capital", "it-customer", 200, "Product").deepCopy();
    body.remove(List.of("id", "version"));
    body.put("id", "new-program");
    body.put("reason", "Новая программа для сезонного спроса");
    String raw = JSON.writeValueAsString(body);
    HttpResponse<String> forbidden =
        client.send(
            HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer it-customer")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "forbidden-create")
                .POST(HttpRequest.BodyPublishers.ofString(raw))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    check(forbidden, 403, "Problem");
    assertEquals("no-store", forbidden.headers().firstValue("Cache-Control").orElseThrow());
    HttpRequest create =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "new-program-create")
            .POST(HttpRequest.BodyPublishers.ofString(raw))
            .build();
    HttpResponse<String> first = client.send(create, HttpResponse.BodyHandlers.ofString());
    JsonNode created = check(first, 201, "AdminProduct");
    assertTrue(created.path("active").asBoolean());
    assertEquals("1", created.path("product").path("version").asText());
    assertEquals(1, created.path("versions").size());
    assertEquals(first.body(), client.send(create, HttpResponse.BodyHandlers.ofString()).body());
    assertEquals(
        "1",
        get("/v2/products/new-program", "it-customer", 200, "Product").path("version").asText());
    HttpRequest duplicate =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "new-program-duplicate")
            .POST(HttpRequest.BodyPublishers.ofString(raw))
            .build();
    response(duplicate, 200, "AdminProduct");
    ObjectNode different = body.deepCopy();
    different.put("name", "Другая программа");
    assertEquals(
        "PRODUCT_ALREADY_EXISTS",
        response(
                HttpRequest.newBuilder(uri(path))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "new-program-conflict")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(different)))
                    .build(),
                409,
                "Problem")
            .path("code")
            .asText());
    String archivePath = path + "/new-program/archive";
    assertEquals(
        "VALIDATION_ERROR",
        response(
                HttpRequest.newBuilder(uri(archivePath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "archive-short-unicode")
                    .header("If-Match", first.headers().firstValue("ETag").orElseThrow())
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"reason\":\"" + "😀".repeat(5) + "\"}"))
                    .build(),
                422,
                "Problem")
            .path("code")
            .asText());
    assertEquals(
        "MALFORMED_JSON",
        response(
                HttpRequest.newBuilder(uri(archivePath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "archive-empty-json")
                    .header("If-Match", first.headers().firstValue("ETag").orElseThrow())
                    .POST(HttpRequest.BodyPublishers.ofString(""))
                    .build(),
                400,
                "Problem")
            .path("code")
            .asText());
    HttpRequest archive =
        HttpRequest.newBuilder(uri(archivePath))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "new-program-archive")
            .header("If-Match", first.headers().firstValue("ETag").orElseThrow())
            .POST(
                HttpRequest.BodyPublishers.ofString("{\"reason\":\"Завершился сезонный период\"}"))
            .build();
    HttpResponse<String> archivedResponse =
        client.send(archive, HttpResponse.BodyHandlers.ofString());
    JsonNode archived = check(archivedResponse, 200, "AdminProduct");
    assertFalse(archived.path("active").asBoolean());
    assertEquals(created.path("versions"), archived.path("versions"));
    get("/v2/products/new-program", "it-customer", 404, "Problem");
    assertEquals(
        created.path("product"),
        get("/v2/products/new-program?version=1", "it-customer", 200, "Product"));
    assertEquals(
        archivedResponse.body(), client.send(archive, HttpResponse.BodyHandlers.ofString()).body());
    assertEquals(first.body(), client.send(create, HttpResponse.BodyHandlers.ofString()).body());
    HttpRequest duplicateAfterArchive =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "new-program-duplicate-archived")
            .POST(HttpRequest.BodyPublishers.ofString(raw))
            .build();
    assertFalse(response(duplicateAfterArchive, 200, "AdminProduct").path("active").asBoolean());
    String archivedEtag = archivedResponse.headers().firstValue("ETag").orElseThrow();
    HttpRequest rearchive =
        HttpRequest.newBuilder(uri(archivePath))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "new-program-rearchive")
            .header("If-Match", archivedEtag)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"reason\":\"Повторное архивирование программы\"}"))
            .build();
    response(rearchive, 200, "AdminProduct");
    assertEquals(
        "VERSION_CONFLICT",
        response(
                HttpRequest.newBuilder(uri(archivePath))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "new-program-stale-archive")
                    .header("If-Match", first.headers().firstValue("ETag").orElseThrow())
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"reason\":\"Проверка устаревшего заголовка\"}"))
                    .build(),
                412,
                "Problem")
            .path("code")
            .asText());
    try (Connection connection = pool.getConnection();
        PreparedStatement count =
            connection.prepareStatement(
                "SELECT count(*) FROM program_lifecycle_events WHERE product_id='new-program' AND action='ARCHIVED'");
        ResultSet rows = count.executeQuery()) {
      rows.next();
      assertEquals(1, rows.getInt(1));
    }
    ObjectNode publishBody = body.deepCopy();
    publishBody.remove("id");
    publishBody.put("annualRateBps", 2000);
    assertEquals(
        "PRODUCT_ARCHIVED",
        response(
                HttpRequest.newBuilder(uri(path + "/new-program/versions"))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "new-program-publish-after-archive")
                    .header("If-Match", archivedResponse.headers().firstValue("ETag").orElseThrow())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(publishBody)))
                    .build(),
                409,
                "Problem")
            .path("code")
            .asText());
    owner(
        "INSERT INTO product_versions SELECT 'orphan-id', version, name, description,"
            + " purpose_codes, min_amount_rub, max_amount_rub, min_term_months, max_term_months,"
            + " annual_rate_bps, min_business_age_months, max_debt_service_ratio_bps, created_at"
            + " FROM product_versions WHERE product_id='new-program' AND version='1';");
    ObjectNode orphan = body.deepCopy();
    orphan.put("id", "orphan-id");
    assertEquals(
        "PRODUCT_ALREADY_EXISTS",
        response(
                HttpRequest.newBuilder(uri(path))
                    .header("Authorization", "Bearer it-admin")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "orphan-id-conflict")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(orphan)))
                    .build(),
                409,
                "Problem")
            .path("code")
            .asText());
  }

  @Test
  @Order(7)
  void serializesConcurrentCreationAndArchiveAgainstPublication() throws Exception {
    ObjectNode creation =
        (ObjectNode)
            get("/v2/products/working-capital?version=1", "it-customer", 200, "Product").deepCopy();
    creation.remove(List.of("id", "version"));
    creation.put("id", "race-program");
    creation.put("reason", "Проверка одновременного создания программы");
    String body = JSON.writeValueAsString(creation);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "race-create-" + i;
        results.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  release.await();
                  return client
                      .send(
                          HttpRequest.newBuilder(uri("/v2/admin/products"))
                              .header("Authorization", "Bearer it-admin")
                              .header("Content-Type", "application/json")
                              .header("Idempotency-Key", key)
                              .POST(HttpRequest.BodyPublishers.ofString(body))
                              .build(),
                          HttpResponse.BodyHandlers.ofString())
                      .statusCode();
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertEquals(
          List.of(200, 201),
          results.stream()
              .map(
                  result -> {
                    try {
                      return result.get(15, TimeUnit.SECONDS);
                    } catch (Exception error) {
                      throw new RuntimeException(error);
                    }
                  })
              .sorted()
              .toList());
    }
    assertEquals(
        1,
        get("/v2/admin/products/race-program", "it-admin", 200, "AdminProduct")
            .path("versions")
            .size());
    try (Connection connection = pool.getConnection();
        PreparedStatement count =
            connection.prepareStatement(
                "SELECT count(*) FROM program_lifecycle_events WHERE product_id='race-program' AND action='CREATED'");
        ResultSet rows = count.executeQuery()) {
      rows.next();
      assertEquals(1, rows.getInt(1));
    }
    ObjectNode firstId = creation.deepCopy();
    firstId.put("id", "race-key-one");
    ObjectNode secondId = creation.deepCopy();
    secondId.put("id", "race-key-two");
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> statuses = new ArrayList<>();
      for (ObjectNode candidate : List.of(firstId, secondId)) {
        statuses.add(
            workers.submit(
                () ->
                    client
                        .send(
                            HttpRequest.newBuilder(uri("/v2/admin/products"))
                                .header("Authorization", "Bearer it-admin")
                                .header("Content-Type", "application/json")
                                .header("Idempotency-Key", "same-key-different-id")
                                .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(candidate)))
                                .build(),
                            HttpResponse.BodyHandlers.ofString())
                        .statusCode()));
      }
      assertEquals(
          List.of(201, 409),
          statuses.stream()
              .map(
                  result -> {
                    try {
                      return result.get(15, TimeUnit.SECONDS);
                    } catch (Exception error) {
                      throw new RuntimeException(error);
                    }
                  })
              .sorted()
              .toList());
    }
    HttpResponse<String> before =
        client.send(
            HttpRequest.newBuilder(uri("/v2/admin/products/race-program"))
                .header("Authorization", "Bearer it-admin")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String etag = before.headers().firstValue("ETag").orElseThrow();
    ObjectNode publication = creation.deepCopy();
    publication.remove("id");
    publication.put("annualRateBps", 1900);
    String publicationBody = JSON.writeValueAsString(publication);
    HttpRequest publish =
        HttpRequest.newBuilder(uri("/v2/admin/products/race-program/versions"))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "race-publish")
            .header("If-Match", etag)
            .POST(HttpRequest.BodyPublishers.ofString(publicationBody))
            .build();
    HttpRequest archive =
        HttpRequest.newBuilder(uri("/v2/admin/products/race-program/archive"))
            .header("Authorization", "Bearer it-admin")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "race-archive")
            .header("If-Match", etag)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"reason\":\"Проверка гонки публикации и архива\"}"))
            .build();
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(
              () -> client.send(publish, HttpResponse.BodyHandlers.ofString()).statusCode());
      Future<Integer> second =
          workers.submit(
              () -> client.send(archive, HttpResponse.BodyHandlers.ofString()).statusCode());
      List<Integer> statuses =
          List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      assertEquals(
          1, statuses.stream().filter(status -> status == 412).count(), statuses.toString());
      assertTrue(statuses.contains(200) || statuses.contains(201));
    }
  }

  @Test
  @Order(7)
  void switchesVersionDeactivatesAndRetainsImmutableSnapshotsAcrossRestart() throws Exception {
    JsonNode original =
        get("/v2/products/working-capital?version=1", "it-customer", 200, "Product");
    owner(
        """
                INSERT INTO product_versions SELECT product_id, '2', name, description, purpose_codes,
                  min_amount_rub, 9000000, min_term_months, max_term_months, 2100,
                  min_business_age_months, max_debt_service_ratio_bps, clock_timestamp()
                  FROM product_versions WHERE product_id='working-capital' AND version='1';
                UPDATE current_products SET version='2' WHERE product_id='working-capital';
                """);
    JsonNode current = get("/v2/products/working-capital", "it-customer", 200, "Product");
    assertEquals("2", current.path("version").asText());
    assertEquals(2100, current.path("annualRateBps").asInt());
    assertEquals(9000000, current.path("maxAmountRub").asLong());
    assertTrue(
        contains(get("/v2/products", "it-internal", 200, "ProductList").path("items"), current));
    assertEquals(
        original, get("/v2/products/working-capital?version=1", "it-internal", 200, "Product"));
    owner("UPDATE current_products SET active=false WHERE product_id='working-capital';");
    assertEquals(1, get("/v2/products", "it-customer", 200, "ProductList").path("items").size());
    get("/v2/products/working-capital", "it-customer", 404, "Problem");
    assertEquals(
        original, get("/v2/products/working-capital?version=1", "it-customer", 200, "Product"));
    assertEquals(
        current, get("/v2/products/working-capital?version=2", "it-customer", 200, "Product"));
    get("/health/ready", null, 200, "Health");
    server.close();
    pool.close();
    startHttp();
    assertEquals(
        original, get("/v2/products/working-capital?version=1", "it-customer", 200, "Product"));
    assertEquals(
        current, get("/v2/products/working-capital?version=2", "it-customer", 200, "Product"));
    get("/v2/products/working-capital", "it-customer", 404, "Problem");
    owner("UPDATE current_products SET active=false WHERE product_id='equipment-finance';");
    assertTrue(get("/v2/products", "it-customer", 200, "ProductList").path("items").isEmpty());
    get("/health/ready", null, 200, "Health");
  }

  @Test
  @Order(8)
  void runtimeCannotMutateVersionsUseDdlReadMigrationMetadataOrAccessOtherDatabases()
      throws Exception {
    for (String query :
        List.of(
            "UPDATE product_versions SET annual_rate_bps=1",
            "DELETE FROM product_versions",
            "CREATE TABLE forbidden (id int)",
            "ALTER TABLE product_versions DISABLE TRIGGER ALL",
            "SELECT * FROM schema_migrations",
            "SET ROLE catalog_owner")) {
      String error =
          assertThrows(IllegalStateException.class, () -> sql(query, "catalog_app", "alfa_catalog"))
              .getMessage();
      assertTrue(error.contains("ERROR"));
    }
    assertThrows(
        IllegalStateException.class, () -> sql("SELECT 1", "catalog_app", "alfa_applications"));
    assertThrows(
        IllegalStateException.class, () -> owner("UPDATE product_versions SET annual_rate_bps=1"));
    assertThrows(
        IllegalStateException.class,
        () ->
            owner("UPDATE current_products SET version='999' WHERE product_id='working-capital'"));
  }

  @Test
  @Order(8)
  void readsUnpointedVersionWithBoundaryAmountsAndUniquePurposeCodes() throws Exception {
    owner(
        """
                INSERT INTO product_versions(product_id,version,name,description,purpose_codes,min_amount_rub,max_amount_rub,
                  min_term_months,max_term_months,annual_rate_bps,min_business_age_months,max_debt_service_ratio_bps)
                VALUES('boundary-product','99999999999999999999999','Boundary','Boundary test',
                  ARRAY['EQUIPMENT','EQUIPMENT','REFINANCING'],1,1000000000,1,120,0,0,10000);
                """);
    JsonNode product =
        get(
            "/v2/products/boundary-product?version=99999999999999999999999",
            "it-internal",
            200,
            "Product");
    assertEquals(1000000000L, product.path("maxAmountRub").asLong());
    assertEquals(0, product.path("annualRateBps").asInt());
    assertEquals(2, product.path("purposeCodes").size());
    get("/v2/products/boundary-product", "it-customer", 404, "Problem");
  }

  @Test
  @Order(9)
  void concurrentReadersReturnTheSameSavedVersion() throws Exception {
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<JsonNode>> results = new ArrayList<>();
      for (int i = 0; i < 24; i++)
        results.add(
            workers.submit(
                () ->
                    get("/v2/products/working-capital?version=1", "it-customer", 200, "Product")));
      JsonNode first = results.getFirst().get();
      for (Future<JsonNode> result : results) assertEquals(first, result.get(15, TimeUnit.SECONDS));
    }
  }

  @Test
  @Order(10)
  void unavailableDatabaseReturns503WhileProcessStaysAliveAndRecovers() throws Exception {
    // Freeze the real server. Stopping a container would discard its tmpfs database.
    command(null, "docker", "pause", container);
    get("/health/live", null, 200, "Health");
    assertEquals(
        "DEPENDENCY_UNAVAILABLE", get("/health/ready", null, 503, "Problem").path("code").asText());
    get("/v2/products", "it-customer", 503, "Problem");
    command(null, "docker", "unpause", container);
    awaitDatabase();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      var probe =
          client.send(
              HttpRequest.newBuilder(uri("/health/ready")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (probe.statusCode() == 200) break;
      Thread.sleep(200);
    }
    get("/health/ready", null, 200, "Health");
    get("/v2/products/working-capital?version=1", "it-customer", 200, "Product");
  }

  private JsonNode get(String path, String token, int status, String schema) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    String requestId = UUID.randomUUID().toString();
    request.header("X-Request-Id", requestId);
    HttpResponse<String> response =
        client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
    return check(response, status, schema);
  }

  private JsonNode response(HttpRequest request, int status, String schema) throws Exception {
    return check(client.send(request, HttpResponse.BodyHandlers.ofString()), status, schema);
  }

  private JsonNode check(HttpResponse<String> response, int status, String schema)
      throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
    assertEquals(requestId, UUID.fromString(requestId).toString());
    assertEquals(
        (status >= 400 ? "application/problem+json" : "application/json") + "; charset=utf-8",
        response.headers().firstValue("Content-Type").orElseThrow());
    JsonNode body = JSON.readTree(response.body());
    JsonNode document =
        JSON.createObjectNode()
            .put("$ref", "#/$defs/" + schema)
            .set("$defs", schemas.path("$defs"));
    var errors =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(document)
            .validate(body);
    assertTrue(errors.isEmpty(), errors.toString());
    if (status >= 400) assertEquals(requestId, body.path("requestId").asText());
    if (status == 503)
      assertEquals("3", response.headers().firstValue("Retry-After").orElseThrow());
    if (status == 401)
      assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
    return body;
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private static boolean contains(JsonNode array, JsonNode expected) {
    for (JsonNode item : array) if (item.equals(expected)) return true;
    return false;
  }

  private void owner(String query) throws Exception {
    sql("SET ROLE catalog_owner;\n" + query, "postgres", "alfa_catalog");
  }

  private void sql(String query, String user, String database) throws Exception {
    command(
        query,
        "docker",
        "exec",
        "-i",
        container,
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        user,
        "-d",
        database);
  }

  private void awaitDatabase() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        // PID 1 becomes postgres only after Docker's temporary initialization server exits.
        command(
            null,
            "docker",
            "exec",
            container,
            "sh",
            "-c",
            "test \"$(cat /proc/1/comm)\" = postgres && pg_isready -U postgres");
        return;
      } catch (IllegalStateException ignored) {
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("Test PostgreSQL did not become ready");
  }

  private static String command(String input, String... args) throws Exception {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    CompletableFuture<String> output =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    try (OutputStream stdin = process.getOutputStream()) {
      if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
    }
    if (!process.waitFor(180, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Test command timed out: " + args[0]);
    }
    String text = output.get(10, TimeUnit.SECONDS);
    if (process.exitValue() != 0) throw new IllegalStateException(text);
    return text;
  }
}
