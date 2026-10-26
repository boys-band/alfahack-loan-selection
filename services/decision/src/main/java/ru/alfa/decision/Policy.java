package ru.alfa.decision;

import static ru.alfa.decision.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pure synthetic demo-policy-v2. Decimal intermediates are exact; only final payments round upward.
 */
final class Policy {
  static final Map<String, String> MESSAGES =
      Map.ofEntries(
          Map.entry("REGISTRY_INACTIVE", "Компания неактивна"),
          Map.entry("CURRENT_OVERDUE", "Есть текущая просрочка"),
          Map.entry("TAX_DEBT_LIMIT", "Налоговый долг превышает 100000 рублей"),
          Map.entry("BUSINESS_TOO_YOUNG", "Недостаточный возраст бизнеса"),
          Map.entry("NON_POSITIVE_CASH_FLOW", "Неположительный свободный поток"),
          Map.entry("DEBT_BURDEN_LIMIT", "Превышена допустимая нагрузка"),
          Map.entry("PRODUCT_LIMITS", "Нарушены ограничения продукта"),
          Map.entry("RISK_SCORE_LOW", "Недостаточный балл риска"),
          Map.entry("BORDERLINE_RISK", "Требуется ручная оценка риска"),
          Map.entry("ADDITIONAL_INFORMATION_REQUIRED", "Объясните нулевые расходы"),
          Map.entry("APPROVED_BY_POLICY", "Условия соответствуют политике"),
          Map.entry("APPROVED_BY_STAFF", "Одобрено сотрудником"),
          Map.entry("REJECTED_BY_STAFF", "Отказ сотрудника"));

  static ObjectNode reason(String code) {
    return obj("code", code, "message", MESSAGES.get(code));
  }

  static long payment(long amount, int months, int rate) {
    BigDecimal a = BigDecimal.valueOf(amount), m = BigDecimal.valueOf(months);
    if (rate == 0) return a.divide(m, 0, RoundingMode.CEILING).longValueExact();
    // Algebraically identical annuity with integer-scaled decimal powers. Avoids an
    // intermediate reciprocal moving an exact whole-ruble result across ceil().
    BigDecimal scale = BigDecimal.valueOf(120000);
    BigDecimal growth = scale.add(BigDecimal.valueOf(rate)).pow(months);
    BigDecimal denominator = scale.multiply(growth.subtract(scale.pow(months)));
    return a.multiply(BigDecimal.valueOf(rate))
        .multiply(growth)
        .divide(denominator, 0, RoundingMode.CEILING)
        .longValueExact();
  }

  static String grade(long score) {
    return score >= 800 ? "LOW" : score >= 600 ? "MEDIUM" : "HIGH";
  }

  static String outcome(long score) {
    return score >= 600 ? "APPROVED" : score >= 500 ? "MANUAL_REVIEW" : "REJECTED";
  }

  static void stop(ArrayNode reasons, boolean condition, String code) {
    if (condition) reasons.add(reason(code));
  }

  static ObjectNode evaluate(JsonNode data, JsonNode facts, long amount, int months) {
    JsonNode p = data.get("product"), f = data.get("financialData");
    int rate = p.get("annualRateBps").intValue();
    long pay = payment(amount, months, rate),
        cash = number(f, "monthlyRevenueRub") - number(f, "monthlyExpensesRub");
    Long burden =
        cash <= 0
            ? null
            : BigDecimal.valueOf(number(f, "existingMonthlyDebtPaymentsRub") + pay)
                .multiply(BigDecimal.valueOf(10000))
                .divide(BigDecimal.valueOf(cash), 0, RoundingMode.CEILING)
                .longValueExact();
    long bki = number(facts, "creditHistoryScore"), age = number(facts, "businessAgeMonths");
    int[] points = {
      300,
      bki >= 750 ? 250 : bki >= 600 ? 150 : 0,
      age >= 24 ? 150 : age >= 12 ? 75 : 0,
      burden != null && burden <= 3000 ? 200 : burden != null && burden <= 5000 ? 100 : 0
    };
    String[] codes = {"BASE", "CREDIT_HISTORY", "BUSINESS_AGE", "DEBT_BURDEN"};
    String[] descriptions = {
      "Базовые баллы", "Кредитная история", "Возраст бизнеса", "Долговая нагрузка"
    };
    ArrayNode factors = arr();
    int score = 0;
    for (int i = 0; i < 4; i++) {
      factors.add(obj("code", codes[i], "points", points[i], "description", descriptions[i]));
      score += points[i];
    }
    ObjectNode risk =
        obj(
            "score",
            score,
            "grade",
            grade(score),
            "factors",
            factors,
            "freeCashFlowRub",
            cash,
            "debtServiceRatioBps",
            burden);
    ArrayNode reasons = arr();
    stop(reasons, !"ACTIVE".equals(text(facts, "registryStatus")), "REGISTRY_INACTIVE");
    stop(reasons, facts.get("hasCurrentOverdue").booleanValue(), "CURRENT_OVERDUE");
    stop(reasons, number(facts, "taxDebtRub") > 100000, "TAX_DEBT_LIMIT");
    stop(reasons, age < number(p, "minBusinessAgeMonths"), "BUSINESS_TOO_YOUNG");
    stop(reasons, cash <= 0, "NON_POSITIVE_CASH_FLOW");
    stop(
        reasons,
        burden != null && burden > number(p, "maxDebtServiceRatioBps"),
        "DEBT_BURDEN_LIMIT");
    boolean purpose = false;
    for (JsonNode v : p.get("purposeCodes")) purpose |= v.equals(data.get("purpose"));
    stop(
        reasons,
        !(amount >= number(p, "minAmountRub")
            && amount <= number(p, "maxAmountRub")
            && months >= number(p, "minTermMonths")
            && months <= number(p, "maxTermMonths")
            && purpose),
        "PRODUCT_LIMITS");
    boolean eligible = reasons.isEmpty() && score >= 500;
    if (score < 600) reasons.add(reason(score < 500 ? "RISK_SCORE_LOW" : "BORDERLINE_RISK"));
    return obj(
        "id",
        id(),
        "amountRub",
        amount,
        "termMonths",
        months,
        "annualRateBps",
        rate,
        "monthlyPaymentRub",
        pay,
        "risk",
        risk,
        "eligible",
        eligible,
        "reasons",
        reasons);
  }

  static ObjectNode offer(JsonNode request, JsonNode selected, Instant now) {
    JsonNode d = request.get("data"), p = d.get("product");
    ObjectNode result =
        obj(
            "id",
            id(),
            "version",
            1,
            "applicationId",
            request.get("applicationId"),
            "dataVersion",
            request.get("dataVersion"),
            "productId",
            p.get("id"),
            "productVersion",
            p.get("version"),
            "type",
            number(d, "amountRub") == number(selected, "amountRub")
                    && number(d, "termMonths") == number(selected, "termMonths")
                ? "REQUESTED"
                : "COUNTER",
            "termsId",
            selected.get("id"),
            "expiresAt",
            now.plus(168, ChronoUnit.HOURS).toString());
    for (String k : List.of("amountRub", "termMonths", "annualRateBps", "monthlyPaymentRub"))
      result.set(k, selected.get(k));
    return result;
  }

  static ObjectNode base(JsonNode request, Instant now) {
    return obj(
        "id",
        id(),
        "applicationId",
        request.get("applicationId"),
        "dataVersion",
        request.get("dataVersion"),
        "kind",
        "AUTO",
        "policyVersion",
        "demo-policy-v2",
        "evaluatedAt",
        now.toString(),
        "assessmentId",
        null,
        "risk",
        null,
        "evaluatedTerms",
        arr(),
        "selectedTermsId",
        null,
        "reasons",
        arr(),
        "requirements",
        arr(),
        "offer",
        null,
        "staffAudit",
        null);
  }

  static ObjectNode decide(JsonNode request, Instant now) {
    JsonNode d = request.get("data"), facts = request.get("verification").get("facts");
    ObjectNode result = base(request, now);
    if (number(d.get("financialData"), "monthlyExpensesRub") == 0
        && !d.get("additionalInfo").hasNonNull("revenueExplanation")) {
      result.put("outcome", "NEEDS_INFO");
      result.set("reasons", arr(reason("ADDITIONAL_INFORMATION_REQUIRED")));
      result.set(
          "requirements",
          arr(
              obj(
                  "path",
                  "/additionalInfo/revenueExplanation",
                  "message",
                  "Объясните нулевые расходы")));
      return result;
    }
    ObjectNode first = evaluate(d, facts, number(d, "amountRub"), (int) number(d, "termMonths"));
    ArrayNode terms = arr(first);
    JsonNode burden = first.get("risk").get("debtServiceRatioBps");
    boolean otherStops = false;
    for (JsonNode r : first.get("reasons"))
      otherStops |=
          !Set.of("DEBT_BURDEN_LIMIT", "RISK_SCORE_LOW", "BORDERLINE_RISK")
              .contains(text(r, "code"));
    if (!(first.get("eligible").asBoolean() && number(first.get("risk"), "score") >= 600)
        && !burden.isNull()
        && burden.longValue() > 3000
        && !otherStops) {
      Set<String> seen = new HashSet<>();
      seen.add(number(d, "amountRub") + ":" + number(d, "termMonths"));
      int maxMonths = (int) number(d.get("product"), "maxTermMonths");
      long amount = number(d, "amountRub");
      for (long a : new long[] {amount, amount * 75 / 100, amount / 2})
        if (a >= number(d.get("product"), "minAmountRub") && seen.add(a + ":" + maxMonths))
          terms.add(evaluate(d, facts, a, maxMonths));
    }
    JsonNode selected = null;
    String outcome = "APPROVED";
    for (JsonNode t : terms)
      if (t.get("eligible").asBoolean() && number(t.get("risk"), "score") >= 600) {
        selected = t;
        break;
      }
    if (selected == null) {
      outcome = "REJECTED";
      for (JsonNode t : terms)
        if (t.get("eligible").asBoolean()) {
          selected = t;
          outcome = "MANUAL_REVIEW";
          break;
        }
    }
    if (selected == null) selected = first;
    result.put("outcome", outcome);
    result.put("assessmentId", id());
    result.set("risk", selected.get("risk"));
    result.set("evaluatedTerms", terms);
    result.set("selectedTermsId", selected.get("id"));
    result.set(
        "reasons",
        outcome.equals("APPROVED") ? arr(reason("APPROVED_BY_POLICY")) : selected.get("reasons"));
    if (outcome.equals("APPROVED")) result.set("offer", offer(request, selected, now));
    return result;
  }
}
