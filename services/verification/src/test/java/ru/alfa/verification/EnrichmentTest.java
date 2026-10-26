package ru.alfa.verification;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnrichmentTest {
  @Test
  void monthBoundaryUsesUtcDayAndDoesNotClampLeapDays() {
    assertEquals(
        0,
        Enrichment.ageMonths(LocalDate.parse("2024-02-29"), Instant.parse("2024-03-28T23:59:59Z")));
    assertEquals(
        1,
        Enrichment.ageMonths(LocalDate.parse("2024-02-29"), Instant.parse("2024-03-29T00:00:00Z")));
    assertEquals(
        11,
        Enrichment.ageMonths(LocalDate.parse("2024-02-29"), Instant.parse("2025-02-28T00:00:00Z")));
    assertEquals(
        0,
        Enrichment.ageMonths(LocalDate.parse("2030-01-01"), Instant.parse("2026-10-24T00:00:00Z")));
  }

  @Test
  void completeFactsAndNegativeFactsAreSuccessful() throws Exception {
    ObjectNode r = request();
    Map<String, ObjectNode> sources = sources();
    sources.get("REGISTRY").put("registryStatus", "INACTIVE");
    sources.get("BKI").put("hasCurrentOverdue", true);
    sources.get("FNS").put("taxDebtRub", 100001);
    ObjectNode out = new Enrichment().evaluate(r, sources, Instant.parse("2026-10-24T09:00:03Z"));
    assertEquals("COMPLETE", out.path("outcome").asText());
    assertTrue(out.path("confirmed").asBoolean());
    assertEquals(81, out.at("/facts/businessAgeMonths").asInt());
    assertEquals("INACTIVE", out.at("/facts/registryStatus").asText());
    assertTrue(out.at("/facts/hasCurrentOverdue").asBoolean());
    assertEquals(100001, out.at("/facts/taxDebtRub").asInt());
    Contracts.validate("VerificationResult", out);
  }

  @Test
  void descriptionIsRequiredUntilProvidedInANewSnapshot() throws Exception {
    ObjectNode r = request();
    var s = sources();
    s.get("REGISTRY").put("needsDescription", true);
    ObjectNode out = new Enrichment().evaluate(r, s, Instant.now());
    assertEquals("NEEDS_INFO", out.path("outcome").asText());
    assertEquals("/additionalInfo/businessDescription", out.at("/requirements/0/path").asText());
    ((ObjectNode) r.at("/data/additionalInfo")).put("businessDescription", "Производство изделий");
    assertEquals(
        "COMPLETE", new Enrichment().evaluate(r, s, Instant.now()).path("outcome").asText());
  }

  @Test
  void disputesRequireReviewAndStaffCorrectionPreservesOriginalFacts() throws Exception {
    ObjectNode r = request();
    var s = sources();
    s.get("REGISTRY").put("disputedFacts", true);
    ObjectNode old = new Enrichment().evaluate(r, s, Instant.now());
    assertEquals("MANUAL_REVIEW", old.path("outcome").asText());
    r.put("dataVersion", 2);
    ObjectNode a =
        Json.object()
            .put("caseActionId", java.util.UUID.randomUUID().toString())
            .put("staffId", java.util.UUID.randomUUID().toString())
            .put("reason", "Проверены документы компании")
            .put("recordedAt", "2026-10-24T09:00:00Z");
    ObjectNode facts = old.path("rawFacts").deepCopy();
    facts.put("creditHistoryScore", 750).put("businessAgeMonths", 0);
    a.set("confirmedFacts", facts);
    ((ObjectNode) r.path("data")).set("staffAttestation", a);
    ObjectNode out = new Enrichment().evaluate(r, s, Instant.now());
    assertEquals("COMPLETE", out.path("outcome").asText());
    assertEquals(800, out.at("/rawFacts/creditHistoryScore").asInt());
    assertEquals(750, out.at("/facts/creditHistoryScore").asInt());
    assertEquals(81, out.at("/facts/businessAgeMonths").asInt());
    assertEquals("MANUAL_REVIEW", old.path("outcome").asText());
  }

  @Test
  void malformedOrMissingSourcesAreTechnicalFailures() throws Exception {
    var s = sources();
    s.remove("FNS");
    var missing = s;
    assertThrows(
        ApiException.class, () -> new Enrichment().evaluate(request(), missing, Instant.now()));
    s = sources();
    s.get("BKI").put("creditHistoryScore", 1001);
    var malformed = s;
    assertThrows(
        ApiException.class, () -> new Enrichment().evaluate(request(), malformed, Instant.now()));
  }

  @Test
  void schemaRejectsUnknownNullMissingAndIncorrectTypes() throws Exception {
    ObjectNode r = request();
    Contracts.validate("VerificationRequest", r);
    ObjectNode extra = r;
    r.put("extra", true);
    assertThrows(ApiException.class, () -> Contracts.validate("VerificationRequest", extra));
    r.remove("extra");
    r.remove("asOf");
    ObjectNode missing = r;
    assertThrows(ApiException.class, () -> Contracts.validate("VerificationRequest", missing));
    r = request();
    r.put("dataVersion", 1.1);
    ObjectNode decimal = r;
    assertThrows(ApiException.class, () -> Contracts.validate("VerificationRequest", decimal));
    r = request();
    r.put("asOf", "2026-02-30T00:00:00Z");
    ObjectNode invalidDate = r;
    assertThrows(ApiException.class, () -> Contracts.validate("VerificationRequest", invalidDate));
  }

  @Test
  void enormousExponentIsRejectedWithoutDoubleConversion() throws Exception {
    ObjectNode r = request();
    ((ObjectNode) r.at("/data/financialData")).set("monthlyRevenueRub", Json.parse("1e10000"));
    assertThrows(
        ApiException.class, () -> Contracts.validate("VerificationRequest", r), Json.write(r));
  }

  static ObjectNode request() throws Exception {
    return (ObjectNode)
        Json.MAPPER.readTree(EnrichmentTest.class.getResourceAsStream("/request.json"));
  }

  static Map<String, ObjectNode> sources() {
    return new java.util.HashMap<>(
        Map.of(
            "REGISTRY",
                Json.object()
                    .put("registryStatus", "ACTIVE")
                    .put("registryRegisteredAt", "2020-01-15")
                    .put("needsDescription", false)
                    .put("disputedFacts", false),
            "BKI", Json.object().put("creditHistoryScore", 800).put("hasCurrentOverdue", false),
            "FNS", Json.object().put("taxDebtRub", 0)));
  }
}
