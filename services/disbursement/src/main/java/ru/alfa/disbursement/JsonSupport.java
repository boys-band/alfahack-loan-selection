package ru.alfa.disbursement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class JsonSupport {
  static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private JsonSupport() {}

  static JsonNode parse(String text) {
    try {
      return JSON.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored JSON", e);
    }
  }

  static String hash(String path, JsonNode body) {
    return sha256("POST\n" + path + "\n" + canonical(body));
  }

  static boolean sameValue(JsonNode left, JsonNode right) {
    if (left.isNumber() && right.isNumber())
      return left.decimalValue().compareTo(right.decimalValue()) == 0;
    return left.equals(right);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      TreeSet<String> names = new TreeSet<>();
      node.fieldNames().forEachRemaining(names::add);
      names.forEach(name -> result.set(name, canonical(node.get(name))));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      node.forEach(n -> result.add(canonical(n)));
      return result;
    }
    if (node.isNumber()) {
      var number = node.decimalValue().stripTrailingZeros();
      return number.scale() <= 0 && number.scale() > -1000
          ? BigIntegerNode.valueOf(number.toBigIntegerExact())
          : DecimalNode.valueOf(number);
    }
    return node;
  }
}
