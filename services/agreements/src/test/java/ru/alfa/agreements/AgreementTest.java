package ru.alfa.agreements;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.agreements.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgreementTest {
  static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

  static ObjectNode fixture() throws Exception {
    try (var in = AgreementTest.class.getResourceAsStream("/agreement-request.json")) {
      return (ObjectNode) MAPPER.readTree(in);
    }
  }

  static ObjectNode object(JsonNode parent, String field) {
    return (ObjectNode) parent.get(field);
  }

  @Test
  void documentHashAndTerms() throws Exception {
    ObjectNode request = fixture(), document = DocumentTemplate.render(request);
    Contract.validate("Document", document);
    assertEquals(1, number(document, "version"));
    String content = text(document, "content");
    assertEquals(DocumentTemplate.sha256(content), text(document, "sha256"));
    assertEquals(64, text(document, "sha256").length());
    assertTrue(content.contains("1000000 рублей"));
    assertTrue(content.contains("12 месяцев"));
    assertTrue(content.contains("2400 базисных пунктов"));
    assertTrue(content.contains("94560 рублей"));
    assertTrue(content.contains("Демо Мастерская"));
    assertFalse(content.contains(text(request.get("company"), "settlementAccountId")));
    assertEquals(document, DocumentTemplate.render(request));
  }

  @Test
  void payoutAccountExcluded() throws Exception {
    ObjectNode request = fixture();
    JsonNode before = DocumentTemplate.render(request);
    object(request, "company").put("settlementAccountId", id());
    assertEquals(before, DocumentTemplate.render(request));
  }

  @Test
  void utf8HashIsExact() {
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        DocumentTemplate.sha256(""));
    assertNotEquals(DocumentTemplate.sha256("Договор\n"), DocumentTemplate.sha256("Договор\r\n"));
    assertNotEquals(DocumentTemplate.sha256("Договор"), DocumentTemplate.sha256("Договор "));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"amountRub", "termMonths", "annualRateBps", "monthlyPaymentRub", "productVersion"})
  void changedTermsChangeHash(String field) throws Exception {
    ObjectNode request = fixture();
    String before = text(DocumentTemplate.render(request), "sha256");
    if (field.equals("productVersion")) object(request, "offer").put(field, "2");
    else object(request, "offer").put(field, number(request.get("offer"), field) + 1);
    assertNotEquals(before, text(DocumentTemplate.render(request), "sha256"));
  }

  @Test
  void validAcceptanceBeforeExpiredOfferAndIndependentDeadline() throws Exception {
    ObjectNode r = fixture();
    object(r, "offer").put("expiresAt", NOW.minusSeconds(1).toString());
    r.put("acceptedAt", NOW.minusSeconds(2).toString());
    DocumentTemplate.check(r, NOW);
    assertEquals(NOW.plus(Duration.ofHours(72)), DocumentTemplate.deadline(NOW));
  }

  @ParameterizedTest
  @ValueSource(strings = {"applicationId", "dataVersion", "expiresAt", "futureAcceptance"})
  void invalidSnapshots(String kind) throws Exception {
    ObjectNode r = fixture();
    switch (kind) {
      case "applicationId" -> object(r, "offer").put("applicationId", id());
      case "dataVersion" -> object(r, "offer").put("dataVersion", 2);
      case "expiresAt" -> object(r, "offer").set("expiresAt", r.get("acceptedAt"));
      case "futureAcceptance" -> r.put("acceptedAt", NOW.plusSeconds(1).toString());
    }
    assertEquals(
        "SNAPSHOT_MISMATCH",
        assertThrows(Problem.class, () -> DocumentTemplate.check(r, NOW)).code);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unknown", "missing", "null", "uppercaseUuid", "negativeMoney", "badDate"})
  void strictInputs(String kind) throws Exception {
    ObjectNode r = fixture();
    switch (kind) {
      case "unknown" -> r.put("extra", true);
      case "missing" -> r.remove("company");
      case "null" -> r.set("company", node(null));
      case "uppercaseUuid" -> r.put("customerId", "AAAAAAAA-0000-0000-0000-000000000001");
      case "negativeMoney" -> object(r, "offer").put("amountRub", -1);
      case "badDate" -> object(r, "company").put("registeredAt", "2026-02-30");
    }
    assertThrows(Problem.class, () -> Contract.validate("AgreementRequest", r));
  }

  @Test
  void schemaSnapshotAndVocabulary() throws Exception {
    JsonNode yaml =
        new ObjectMapper(new YAMLFactory())
            .readTree(Path.of("../../contracts/schemas.yaml").toFile());
    assertTrue(Contract.same(Contract.ROOT, yaml));
    assertThrows(
        IllegalStateException.class, () -> Contract.auditVocabulary(obj("futureAssertion", true)));
  }

  @Test
  void contractExamples() throws Exception {
    Map<String, String> requests =
        Map.of(
            "createAgreement",
            "AgreementRequest",
            "signAgreement",
            "InternalSignRequest",
            "closeAgreement",
            "CloseAgreementRequest");
    Set<String> operations =
        Set.of("createAgreement", "getAgreement", "signAgreement", "closeAgreement");
    int count = 0;
    try (var files = Files.list(Path.of("../../contracts/examples"))) {
      for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        for (JsonNode e : MAPPER.readTree(p.toFile()).path("exchanges")) {
          String operation = text(e, "operationId");
          if (!operations.contains(operation)) continue;
          if (requests.containsKey(operation))
            Contract.validate(requests.get(operation), e.get("request").get("body"));
          Contract.validate(
              e.get("response").get("status").asInt() < 400 ? "Agreement" : "Problem",
              e.get("response").get("body"));
          count++;
        }
      }
    }
    assertTrue(count >= 7);
  }
}
