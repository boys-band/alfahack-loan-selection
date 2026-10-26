package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.networknt.schema.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Contract {
  public static final JsonNode SCHEMAS = resource("/contract/schemas.json");
  public static final JsonNode OPENAPI = resource("/contract/openapi.json");
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

  private Contract() {}

  public static void validate(String name, JsonNode body) {
    JsonSchema schema =
        CACHE.computeIfAbsent(
            name,
            key ->
                FACTORY.getSchema(
                    obj(
                        "$schema",
                        "https://json-schema.org/draft/2020-12/schema",
                        "$ref",
                        "#/$defs/" + key,
                        "$defs",
                        SCHEMAS.get("$defs")),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()));
    var messages = schema.validate(body == null ? node(null) : body);
    if (!messages.isEmpty()) {
      ArrayNode errors = arr();
      messages.stream()
          .limit(20)
          .forEach(
              message ->
                  errors.add(
                      obj(
                          "path",
                          message.getInstanceLocation().toString(),
                          "message",
                          "Invalid value (" + message.getType() + ")")));
      throw new Problem(422, "VALIDATION_ERROR", "Request does not match " + name, errors, 0);
    }
  }
}
