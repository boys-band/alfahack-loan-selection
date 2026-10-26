package ru.alfa.decision;

import static org.junit.jupiter.api.Assertions.*;
import static ru.alfa.decision.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class PolicyTest {
  static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

  static ObjectNode fixture() throws Exception {
    try (var in = PolicyTest.class.getResourceAsStream("/approved-request.json")) {
      return (ObjectNode) MAPPER.readTree(in);
    }
  }

  static ObjectNode object(JsonNode n, String key) {
    return (ObjectNode) n.get(key);
  }

  @Test
  void control() throws Exception {
    ObjectNode request = fixture(), result = Policy.decide(request, NOW);
    Contract.validate("DecisionResult", result);
    assertEquals("APPROVED", text(result, "outcome"));
    assertEquals(900, number(result.get("risk"), "score"));
    assertEquals("2026-10-31T00:00:00Z", text(result.get("offer"), "expiresAt"));
    JsonNode example = MAPPER.readTree(Path.of("../../contracts/examples/approved.json").toFile());
    for (JsonNode e : example.get("exchanges"))
      if ("createDecision".equals(text(e, "operationId"))) {
        JsonNode expected = e.get("response").get("body");
        assertEquals(
            number(expected.get("offer"), "monthlyPaymentRub"),
            number(result.get("offer"), "monthlyPaymentRub"));
        assertTrue(
            Contract.same(
                expected.get("risk").get("freeCashFlowRub"),
                result.get("risk").get("freeCashFlowRub")));
      }
  }

  @ParameterizedTest
  @CsvSource({
    "100,3,0,34",
    "120,12,0,10",
    "1000000,12,1800,91680",
    "1,120,10000,1",
    "100,1,1200,101",
    "100,1,2400,102",
    "10100,2,2400,5202"
  })
  void payments(long amount, int months, int rate, long expected) {
    assertEquals(expected, Policy.payment(amount, months, rate));
  }

  static Stream<Arguments> boundaries() {
    return Stream.of(599, 600, 749, 750)
        .flatMap(
            bki ->
                Stream.of(11, 12, 23, 24)
                    .flatMap(
                        age ->
                            Stream.of(3000, 3001, 5000, 5001)
                                .map(burden -> Arguments.of(bki, age, burden))));
  }

  @ParameterizedTest
  @MethodSource("boundaries")
  void factors(int bki, int age, int burden) throws Exception {
    ObjectNode request = fixture(),
        data = object(request, "data"),
        facts = object(request.get("verification"), "facts");
    object(data, "product").put("annualRateBps", 0);
    data.set(
        "financialData",
        obj(
            "monthlyRevenueRub",
            10000,
            "monthlyExpensesRub",
            0,
            "existingMonthlyDebtPaymentsRub",
            burden - 1));
    facts.put("creditHistoryScore", bki);
    facts.put("businessAgeMonths", age);
    JsonNode risk = Policy.evaluate(data, facts, 1, 1).get("risk");
    int expected =
        300
            + (bki >= 750 ? 250 : bki >= 600 ? 150 : 0)
            + (age >= 24 ? 150 : age >= 12 ? 75 : 0)
            + (burden <= 3000 ? 200 : burden <= 5000 ? 100 : 0);
    assertEquals(burden, number(risk, "debtServiceRatioBps"));
    assertEquals(expected, number(risk, "score"));
    assertEquals(Policy.grade(expected), text(risk, "grade"));
  }

  @ParameterizedTest
  @CsvSource({
    "499,REJECTED,HIGH",
    "500,MANUAL_REVIEW,HIGH",
    "599,MANUAL_REVIEW,HIGH",
    "600,APPROVED,MEDIUM",
    "799,APPROVED,MEDIUM",
    "800,APPROVED,LOW"
  })
  void scoreThresholds(int score, String outcome, String grade) {
    assertEquals(outcome, Policy.outcome(score));
    assertEquals(grade, Policy.grade(score));
  }

  @ParameterizedTest
  @CsvSource({
    "registryStatus,INACTIVE,REGISTRY_INACTIVE",
    "hasCurrentOverdue,true,CURRENT_OVERDUE",
    "taxDebtRub,100001,TAX_DEBT_LIMIT",
    "businessAgeMonths,0,BUSINESS_TOO_YOUNG"
  })
  void stops(String field, String value, String code) throws Exception {
    ObjectNode request = fixture(), facts = object(request.get("verification"), "facts");
    facts.set(field, field.equals("registryStatus") ? node(value) : parse(value));
    JsonNode result = Policy.decide(request, NOW);
    assertEquals("REJECTED", text(result, "outcome"));
    assertTrue(
        StreamSupport.stream(result.get("reasons").spliterator(), false)
            .anyMatch(r -> text(r, "code").equals(code)));
    for (JsonNode t : result.get("evaluatedTerms")) assertFalse(t.get("eligible").asBoolean());
  }

  @Test
  void taxBoundary() throws Exception {
    ObjectNode r = fixture();
    object(r.get("verification"), "facts").put("taxDebtRub", 100000);
    assertEquals("APPROVED", text(Policy.decide(r, NOW), "outcome"));
  }

  @ParameterizedTest
  @ValueSource(ints = {99, 100})
  void cash(int revenue) throws Exception {
    ObjectNode r = fixture();
    object(r.get("data"), "financialData")
        .put("monthlyRevenueRub", revenue)
        .put("monthlyExpensesRub", 100);
    JsonNode result = Policy.decide(r, NOW);
    assertEquals("REJECTED", text(result, "outcome"));
    assertTrue(result.get("risk").get("debtServiceRatioBps").isNull());
  }

  @Test
  void needsInfo() throws Exception {
    ObjectNode r = fixture();
    object(r.get("data"), "financialData").put("monthlyExpensesRub", 0);
    object(r, "data").set("additionalInfo", obj());
    JsonNode result = Policy.decide(r, NOW);
    Contract.validate("DecisionResult", result);
    assertEquals("NEEDS_INFO", text(result, "outcome"));
    assertTrue(result.get("assessmentId").isNull());
    assertTrue(result.get("evaluatedTerms").isEmpty());
  }

  static ObjectNode counter() throws Exception {
    ObjectNode r = fixture(), d = object(r, "data");
    d.put("amountRub", 1000000).put("termMonths", 6);
    d.set(
        "financialData",
        obj(
            "monthlyRevenueRub",
            400000,
            "monthlyExpensesRub",
            200000,
            "existingMonthlyDebtPaymentsRub",
            0));
    object(d, "product")
        .put("minAmountRub", 100000)
        .put("maxAmountRub", 10000000)
        .put("minTermMonths", 1)
        .put("maxTermMonths", 36)
        .put("maxDebtServiceRatioBps", 5000);
    return r;
  }

  @Test
  void counteroffer() throws Exception {
    JsonNode result = Policy.decide(counter(), NOW);
    Contract.validate("DecisionResult", result);
    assertEquals("COUNTER", text(result.get("offer"), "type"));
    assertEquals(4, result.get("evaluatedTerms").size());
    long[] amounts = {1000000, 1000000, 750000, 500000};
    int[] months = {6, 36, 36, 36};
    Set<Long> payments = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      JsonNode t = result.get("evaluatedTerms").get(i);
      assertEquals(amounts[i], number(t, "amountRub"));
      assertEquals(months[i], number(t, "termMonths"));
      payments.add(number(t, "monthlyPaymentRub"));
    }
    assertEquals(4, payments.size());
    assertEquals(result.get("evaluatedTerms").get(1).get("id"), result.get("selectedTermsId"));
  }

  @Test
  void minimumAndDedup() throws Exception {
    ObjectNode r = counter(), d = object(r, "data");
    d.put("termMonths", 36);
    object(d, "product").put("minAmountRub", 800000);
    d.set(
        "financialData",
        obj(
            "monthlyRevenueRub",
            200000,
            "monthlyExpensesRub",
            100000,
            "existingMonthlyDebtPaymentsRub",
            50000));
    assertEquals(1, Policy.decide(r, NOW).get("evaluatedTerms").size());
  }

  @Test
  void manualCandidate() throws Exception {
    ObjectNode r = fixture();
    object(r.get("verification"), "facts")
        .put("creditHistoryScore", 599)
        .put("businessAgeMonths", 11);
    object(r.get("data"), "product").put("minBusinessAgeMonths", 0);
    JsonNode result = Policy.decide(r, NOW);
    assertEquals("MANUAL_REVIEW", text(result, "outcome"));
    assertEquals(500, number(result.get("risk"), "score"));
  }

  @Test
  void automaticAlternativePrecedesRequestedManualCandidate() throws Exception {
    ObjectNode r = counter();
    object(r, "data").put("termMonths", 12);
    object(r.get("verification"), "facts")
        .put("creditHistoryScore", 600)
        .put("businessAgeMonths", 0);
    object(r.get("data"), "product").put("minBusinessAgeMonths", 0);
    JsonNode result = Policy.decide(r, NOW);
    assertEquals(550, number(result.get("evaluatedTerms").get(0).get("risk"), "score"));
    assertEquals("APPROVED", text(result, "outcome"));
    assertEquals(result.get("evaluatedTerms").get(1).get("id"), result.get("selectedTermsId"));
  }

  @Test
  void firstManualCandidateSelectedWhenNoneAutomatic() throws Exception {
    ObjectNode r = counter();
    object(r.get("verification"), "facts")
        .put("creditHistoryScore", 599)
        .put("businessAgeMonths", 11);
    object(r.get("data"), "product").put("minBusinessAgeMonths", 0);
    JsonNode result = Policy.decide(r, NOW);
    assertEquals("MANUAL_REVIEW", text(result, "outcome"));
    assertEquals(result.get("evaluatedTerms").get(1).get("id"), result.get("selectedTermsId"));
  }

  @ParameterizedTest
  @CsvSource({
    "99999,12,true",
    "100000,12,false",
    "5000000,12,false",
    "5000001,12,true",
    "1000000,5,true",
    "1000000,6,false",
    "1000000,36,false",
    "1000000,37,true"
  })
  void productBoundaries(long amount, int months, boolean prohibited) throws Exception {
    JsonNode r = fixture();
    JsonNode term =
        Policy.evaluate(r.get("data"), r.get("verification").get("facts"), amount, months);
    boolean limited = false;
    for (JsonNode reason : term.get("reasons"))
      limited |= "PRODUCT_LIMITS".equals(text(reason, "code"));
    assertEquals(prohibited, limited);
  }

  @Test
  void purposeAndBurdenStops() throws Exception {
    ObjectNode r = fixture(), d = object(r, "data");
    object(d, "product").set("purposeCodes", arr("EQUIPMENT"));
    assertEquals("REJECTED", text(Policy.decide(r, NOW), "outcome"));
    object(d, "product").set("purposeCodes", arr("WORKING_CAPITAL"));
    object(d, "financialData").put("existingMonthlyDebtPaymentsRub", 1000000);
    JsonNode result = Policy.decide(r, NOW);
    assertEquals("REJECTED", text(result, "outcome"));
    for (JsonNode t : result.get("evaluatedTerms")) assertFalse(t.get("eligible").asBoolean());
  }

  @Test
  void unsupportedSchemaKeywordFailsClosed() {
    assertThrows(
        IllegalStateException.class, () -> Contract.auditVocabulary(obj("futureAssertion", true)));
  }

  @Test
  void strictSchemasAndSnapshot() throws Exception {
    ObjectNode r = fixture();
    Contract.validate("DecisionRequest", r);
    r.put("unexpected", true);
    assertThrows(Problem.class, () -> Contract.validate("DecisionRequest", r));
    r.remove("unexpected");
    object(r, "verification").put("applicationId", id());
    assertEquals(
        "SNAPSHOT_MISMATCH", assertThrows(Problem.class, () -> Store.checkSnapshot(r)).code);
  }

  @Test
  void schemaSnapshot() throws Exception {
    JsonNode yaml =
        new ObjectMapper(new YAMLFactory())
            .readTree(Path.of("../../contracts/schemas.yaml").toFile());
    assertTrue(Contract.same(Contract.ROOT, yaml));
  }

  @Test
  void allContractExamples() throws Exception {
    Map<String, String> requestTypes =
        Map.of(
            "createDecision",
            "DecisionRequest",
            "createManualReview",
            "ManualReviewRequest",
            "recordManualReviewAction",
            "ManualReviewActionRequest",
            "closeManualReview",
            "CloseCaseRequest");
    Map<String, String> responseTypes =
        Map.of(
            "createDecision",
            "DecisionResult",
            "getDecision",
            "DecisionResult",
            "createManualReview",
            "ManualReviewCase",
            "getManualReview",
            "ManualReviewCase",
            "listManualReviews",
            "ManualReviewPage",
            "recordManualReviewAction",
            "ManualReviewActionResult",
            "closeManualReview",
            "ManualReviewCase");
    int validated = 0;
    try (var files = Files.list(Path.of("../../contracts/examples"))) {
      for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        JsonNode root = MAPPER.readTree(p.toFile());
        for (JsonNode exchange : root.path("exchanges")) {
          String op = text(exchange, "operationId");
          if (!responseTypes.containsKey(op)) continue;
          JsonNode response = exchange.path("response");
          if (response.path("status").asInt() >= 400) continue;
          if (requestTypes.containsKey(op) && exchange.path("request").has("body"))
            Contract.validate(requestTypes.get(op), exchange.get("request").get("body"));
          Contract.validate(responseTypes.get(op), response.get("body"));
          validated++;
        }
      }
    }
    assertTrue(validated >= 10);
  }
}
