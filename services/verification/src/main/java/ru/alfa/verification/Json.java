package ru.alfa.verification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  static ArrayNode array() {
    return MAPPER.createArrayNode();
  }

  static JsonNode parse(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored JSON", e);
    }
  }

  static String write(JsonNode value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = object();
      TreeSet<String> keys = new TreeSet<>();
      value.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> result.set(k, sorted(value.get(k))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = array();
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

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
