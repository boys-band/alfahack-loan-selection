package ru.alfa.agreements;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private Json() {}

  static ObjectNode obj(Object... entries) {
    ObjectNode result = MAPPER.createObjectNode();
    for (int i = 0; i < entries.length; i += 2)
      result.set((String) entries[i], node(entries[i + 1]));
    return result;
  }

  static JsonNode node(Object value) {
    return value instanceof JsonNode n ? n : MAPPER.valueToTree(value);
  }

  static ArrayNode arr(Object... values) {
    ArrayNode result = MAPPER.createArrayNode();
    for (Object value : values) result.add(node(value));
    return result;
  }

  static JsonNode parse(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  static String text(JsonNode n, String key) {
    return n.path(key).isNull() || n.path(key).isMissingNode() ? null : n.path(key).asText();
  }

  static long number(JsonNode n, String key) {
    return n.path(key).longValue();
  }

  static String id() {
    return UUID.randomUUID().toString();
  }

  static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = obj();
      TreeSet<String> keys = new TreeSet<>();
      value.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> result.set(k, sorted(value.get(k))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = arr();
      value.forEach(v -> result.add(sorted(v)));
      return result;
    }
    if (value.isNumber()) {
      var number = value.decimalValue().stripTrailingZeros();
      return number.scale() <= 0 && number.scale() > -1000
          ? BigIntegerNode.valueOf(number.toBigIntegerExact())
          : DecimalNode.valueOf(number);
    }
    return value;
  }

  static String hash(JsonNode value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(sorted(value).toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
