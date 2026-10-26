package ru.alfa.disbursement;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.*;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** A local, unmodified copy of contracts/schemas.yaml; no remote schema resolution. */
@Component
public final class Contracts {
  private final Map<String, JsonSchema> schemas;

  public Contracts() {
    try (InputStream input = getClass().getResourceAsStream("/contract-schemas.yaml")) {
      ObjectNode root = (ObjectNode) new YAMLMapper().readTree(Objects.requireNonNull(input));
      var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
      Map<String, JsonSchema> loaded = new HashMap<>();
      for (String name :
          List.of(
              "PayoutCheckRequest",
              "DisbursementRequest",
              "Agreement",
              "PayoutCheck",
              "Disbursement",
              "Problem",
              "AccountList",
              "Health")) {
        loaded.put(name, factory.getSchema(root.deepCopy().put("$ref", "#/$defs/" + name), config));
      }
      schemas = Map.copyOf(loaded);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load API contract", e);
    }
  }

  public void request(String type, JsonNode node) {
    var errors = schemas.get(type).validate(node);
    if (!errors.isEmpty())
      throw new ApiException(
          422,
          "VALIDATION_ERROR",
          "Request does not match the API contract.",
          errors.stream()
              .limit(20)
              .map(e -> new ApiException.FieldError("", e.getMessage()))
              .toList());
    for (String field : List.of("dataVersion", "payoutVersion")) {
      if (node.has(field)
          && node.get(field).decimalValue().compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE))
              > 0)
        throw new ApiException(
            422,
            "VALIDATION_ERROR",
            "Version exceeds the PostgreSQL bigint storage range.",
            List.of(
                new ApiException.FieldError(
                    "/" + field, "Maximum supported version is 9223372036854775807.")));
    }
  }

  public boolean valid(String type, JsonNode node) {
    return schemas.get(type).validate(node).isEmpty();
  }
}
