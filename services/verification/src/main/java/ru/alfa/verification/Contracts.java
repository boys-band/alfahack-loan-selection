package ru.alfa.verification;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.*;
import java.util.*;

final class Contracts {
  private static final Map<String, JsonSchema> SCHEMAS = load();

  private static Map<String, JsonSchema> load() {
    try (var in = Contracts.class.getResourceAsStream("/schemas.yaml")) {
      JsonNode source = new ObjectMapper(new YAMLFactory()).readTree(in);
      var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
      var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      Map<String, JsonSchema> result = new HashMap<>();
      for (String name :
          List.of(
              "VerificationRequest",
              "VerificationResult",
              "VerificationFacts",
              "Problem",
              "Health")) {
        ObjectNode root =
            Json.object()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("$ref", "#/$defs/" + name);
        root.set("$defs", source.get("$defs"));
        result.put(name, factory.getSchema(root, config));
      }
      return Map.copyOf(result);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void validate(String name, JsonNode value) {
    validateNumbers(value, "");
    var issues = SCHEMAS.get(name).validate(value);
    if (!issues.isEmpty()) {
      var errors = Json.array();
      issues.stream()
          .limit(25)
          .forEach(
              v ->
                  errors.add(
                      Json.object()
                          .put("path", pointer(v.getInstanceLocation().toString()))
                          .put("message", v.getMessage())));
      throw new ApiException(422, "VALIDATION_ERROR", "Request does not match " + name, errors);
    }
  }

  // Validate exact decimal values before library numeric comparisons can narrow to long/double.
  private static void validateNumbers(JsonNode value, String path) {
    if (value.isNumber()) {
      try {
        var number = value.decimalValue();
        if (number.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) > 0
            || number.compareTo(java.math.BigDecimal.valueOf(Long.MIN_VALUE)) < 0)
          throw new ArithmeticException("Out of range");
        number.toBigIntegerExact().longValueExact();
      } catch (ArithmeticException | NumberFormatException e) {
        throw ApiException.invalid(
            path, "Expected an exact integer within the signed bigint range");
      }
    } else if (value.isObject()) {
      value
          .properties()
          .forEach(
              e ->
                  validateNumbers(
                      e.getValue(), path + "/" + e.getKey().replace("~", "~0").replace("/", "~1")));
    } else if (value.isArray()) {
      for (int i = 0; i < value.size(); i++) validateNumbers(value.get(i), path + "/" + i);
    }
  }

  private static String pointer(String value) {
    if (value.startsWith("/")) return value;
    if (value.equals("$") || value.isEmpty()) return "";
    return value
        .replaceFirst("^\\$\\.?", "/")
        .replace(".", "/")
        .replaceAll("\\[([0-9]+)\\]", "/$1");
  }
}
