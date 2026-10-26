package ru.alfa.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

/** Produces facts, never a credit decision. Source payloads stay unmodified. */
final class Enrichment {
  static int ageMonths(LocalDate registeredAt, Instant asOf) {
    LocalDate date = asOf.atOffset(ZoneOffset.UTC).toLocalDate();
    int months =
        (date.getYear() - registeredAt.getYear()) * 12
            + date.getMonthValue()
            - registeredAt.getMonthValue();
    return Math.max(0, months - (date.getDayOfMonth() < registeredAt.getDayOfMonth() ? 1 : 0));
  }

  ObjectNode evaluate(ObjectNode request, Map<String, ObjectNode> sources, Instant completedAt) {
    Instant asOf = Instant.parse(request.path("asOf").asText());
    JsonNode registry = sources.get("REGISTRY"), bki = sources.get("BKI"), fns = sources.get("FNS");
    ObjectNode raw = Json.object();
    try {
      if (registry == null || bki == null || fns == null) throw ApiException.unavailable();
      for (String flag : List.of("needsDescription", "disputedFacts"))
        if (!registry.path(flag).isBoolean()) throw ApiException.unavailable();
      copy(raw, registry, "registryStatus", "registryRegisteredAt");
      raw.put(
          "businessAgeMonths",
          ageMonths(LocalDate.parse(raw.path("registryRegisteredAt").asText()), asOf));
      copy(raw, bki, "creditHistoryScore", "hasCurrentOverdue");
      copy(raw, fns, "taxDebtRub");
      Contracts.validate("VerificationFacts", raw);
      if (LocalDate.parse(raw.path("registryRegisteredAt").asText())
          .isAfter(asOf.atOffset(ZoneOffset.UTC).toLocalDate())) throw ApiException.unavailable();
    } catch (RuntimeException e) {
      throw ApiException.unavailable();
    }
    ObjectNode facts = raw.deepCopy();
    ArrayNode findings = Json.array(), requirements = Json.array();
    boolean disputed = registry.path("disputedFacts").asBoolean();
    // Fixtures currently only supply the registration date for identity comparison.
    if (!raw.path("registryRegisteredAt").equals(request.at("/data/company/registeredAt"))) {
      disputed = true;
      finding(
          findings,
          "REGISTRY",
          "REGISTRATION_DATE_MISMATCH",
          "WARNING",
          "Дата регистрации расходится со сведениями заявки");
    }
    if (registry.path("disputedFacts").asBoolean())
      finding(
          findings,
          "REGISTRY",
          "DISPUTED_FACTS",
          "WARNING",
          "Сведения источников требуют подтверждения сотрудником");
    JsonNode attestation = request.at("/data/staffAttestation");
    if (!attestation.isNull() && !attestation.isMissingNode()) {
      facts = attestation.path("confirmedFacts").deepCopy();
      facts.put(
          "businessAgeMonths",
          ageMonths(LocalDate.parse(facts.path("registryRegisteredAt").asText()), asOf));
      disputed = false;
      finding(
          findings,
          "STAFF",
          "FACTS_CONFIRMED",
          "INFO",
          "Факты подтверждены аудируемым уточнением сотрудника");
    }
    if (registry.path("needsDescription").asBoolean()
        && request.at("/data/additionalInfo/businessDescription").asText("").isBlank())
      requirements.add(
          Json.object()
              .put("path", "/additionalInfo/businessDescription")
              .put("message", "Опишите деятельность компании"));
    finding(
        findings,
        "BKI",
        facts.path("hasCurrentOverdue").asBoolean() ? "CURRENT_OVERDUE" : "NO_CURRENT_OVERDUE",
        facts.path("hasCurrentOverdue").asBoolean() ? "WARNING" : "INFO",
        facts.path("hasCurrentOverdue").asBoolean()
            ? "Имеется текущая просрочка"
            : "Текущей просрочки нет");
    if (facts.path("registryStatus").asText().equals("INACTIVE"))
      finding(findings, "REGISTRY", "REGISTRY_INACTIVE", "WARNING", "Компания неактивна в реестре");
    if (facts.path("taxDebtRub").asLong() > 0)
      finding(findings, "FNS", "TAX_DEBT", "WARNING", "Имеется налоговая задолженность");
    String outcome =
        !requirements.isEmpty() ? "NEEDS_INFO" : disputed ? "MANUAL_REVIEW" : "COMPLETE";
    ObjectNode result =
        Json.object()
            .put("id", UUID.randomUUID().toString())
            .put("applicationId", request.path("applicationId").asText())
            .put("dataVersion", request.path("dataVersion").longValue())
            .put("outcome", outcome)
            .put("asOf", asOf.toString())
            .put("completedAt", completedAt.toString())
            .put("confirmed", outcome.equals("COMPLETE"));
    result.set("rawFacts", raw);
    result.set("facts", facts);
    result.set("requirements", requirements);
    result.set("findings", findings);
    return result;
  }

  private static void copy(ObjectNode target, JsonNode source, String... fields) {
    for (String field : fields) {
      if (!source.hasNonNull(field)) throw ApiException.unavailable();
      target.set(field, source.get(field).deepCopy());
    }
  }

  private static void finding(
      ArrayNode target, String source, String code, String severity, String message) {
    target.add(
        Json.object()
            .put("source", source)
            .put("code", code)
            .put("severity", severity)
            .put("message", message));
  }
}
