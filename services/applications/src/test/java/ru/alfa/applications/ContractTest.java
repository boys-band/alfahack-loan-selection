package ru.alfa.applications;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.applications.Json.*;

import org.junit.jupiter.api.Test;

class ContractTest {
  @Test
  void canonicalJsonMatchesPublishedEtag() {
    var exchanges = resource("/fixtures/approved.json").get("exchanges");
    for (var exchange : exchanges) {
      var headers = exchange.path("response").get("headers");
      if (headers != null && headers.has("ETag"))
        assertEquals(s(headers, "ETag"), etag(exchange.path("response").get("body")));
    }
  }

  @Test
  void draftMissingAndNullHaveDifferentMeaning() {
    Contract.validate("UpdateDraftRequest", obj("amountRub", null));
    Contract.validate("UpdateDraftRequest", obj("additionalInfo", obj()));
    assertThrows(Problem.class, () -> Contract.validate("UpdateDraftRequest", obj()));
    assertThrows(
        Problem.class,
        () ->
            Contract.validate(
                "UpdateDraftRequest", obj("financialData", obj("monthlyRevenueRub", 0))));
    assertThrows(Problem.class, () -> Contract.validate("DataChanges", obj("amountRub", null)));
    assertThrows(
        Problem.class, () -> Contract.validate("DataChanges", obj("productId", "working-capital")));
  }

  @Test
  void validationRejectsPrivilegeFieldsAndFalseConsent() {
    assertThrows(
        Problem.class,
        () ->
            Contract.validate(
                "SubmitRequest", obj("personalDataConsent", 1, "creditBureauConsent", true)));
    assertThrows(
        Problem.class,
        () ->
            Contract.validate(
                "SubmitRequest", obj("personalDataConsent", false, "creditBureauConsent", true)));
    assertThrows(
        Problem.class, () -> Contract.validate("UpdateDraftRequest", obj("status", "SIGNED")));
    assertThrows(
        Problem.class,
        () -> Contract.validate("Uuid", node("ABCDEF00-0000-4000-8000-000000000001")));
    assertThrows(Problem.class, () -> Contract.validate("Timestamp", node("2030-99-99T00:00:00Z")));
  }

  @Test
  void parserRejectsAmbiguousAndNonJsonValues() {
    for (String value : new String[] {"{\"a\":1,\"a\":2}", "{} {}", "NaN", "{\"a\":Infinity}"})
      assertThrows(IllegalArgumentException.class, () -> parse(value));
  }

  @Test
  void allPublishedFixturesRemainSchemaValid() {
    var operationSchemas =
        java.util.Map.ofEntries(
            java.util.Map.entry("createVerification", "VerificationResult"),
            java.util.Map.entry("createDecision", "DecisionResult"),
            java.util.Map.entry("createManualReview", "ManualReviewCase"),
            java.util.Map.entry("recordManualReviewAction", "ManualReviewActionResult"),
            java.util.Map.entry("createAgreement", "Agreement"),
            java.util.Map.entry("signAgreement", "Agreement"),
            java.util.Map.entry("createPayoutCheck", "PayoutCheck"),
            java.util.Map.entry("createDisbursement", "Disbursement"),
            java.util.Map.entry("getApplication", "Application"));
    for (String file :
        new String[] {
          "approved",
          "manual-review",
          "information-and-versions",
          "counteroffer",
          "declined",
          "payout-recovery"
        }) {
      for (var exchange : resource("/fixtures/" + file + ".json").get("exchanges")) {
        String schema = operationSchemas.get(s(exchange, "operationId"));
        if (schema != null && n(exchange.get("response"), "status") < 400)
          Contract.validate(schema, exchange.path("response").get("body"));
      }
    }
  }

  @Test
  void bundledContractsMatchAcceptedRepositorySources() throws Exception {
    var root = java.nio.file.Path.of("../..");
    var yaml =
        new com.fasterxml.jackson.databind.ObjectMapper(
            new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
    assertEquals(
        yaml.readTree(java.nio.file.Files.readString(root.resolve("contracts/schemas.yaml"))),
        Contract.SCHEMAS);
    assertEquals(
        yaml.readTree(
            java.nio.file.Files.readString(root.resolve("contracts/openapi/applications.yaml"))),
        Contract.OPENAPI);
    assertEquals(
        java.nio.file.Files.readString(root.resolve("database/seeds/applications.sql")),
        java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/contract/seed.sql")));
  }

  @Test
  void configurationErrorsDoNotIncludeTokensOrDatabasePasswords() {
    String secret = "test-secret-value";
    var duplicate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                Settings.from(
                    java.util.Map.of("DEMO_CUSTOMER_TOKEN", secret, "DEMO_STAFF_TOKEN", secret)));
    assertFalse(duplicate.toString().contains(secret));
    var settings =
        Settings.from(
            java.util.Map.of(
                "DATABASE_URL",
                "postgresql://applications_app:" + secret + "@bad host/alfa_applications"));
    var invalid = assertThrows(IllegalArgumentException.class, settings::databaseUri);
    assertFalse(invalid.toString().contains(secret));
  }
}
