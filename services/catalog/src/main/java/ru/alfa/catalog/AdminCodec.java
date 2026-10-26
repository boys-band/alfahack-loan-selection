package ru.alfa.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class AdminCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private AdminCodec() {}

  static String canonical(Object value) {
    try {
      return JSON.writeValueAsString(sorted(JSON.valueToTree(value)));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Object sorted(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> result = new TreeMap<>();
      node.fields().forEachRemaining(field -> result.put(field.getKey(), sorted(field.getValue())));
      return result;
    }
    if (node.isArray()) {
      List<Object> result = new ArrayList<>();
      node.forEach(item -> result.add(sorted(item)));
      return result;
    }
    if (node.isTextual()) return node.textValue();
    if (node.isIntegralNumber()) return node.bigIntegerValue();
    if (node.isFloatingPointNumber()) return node.decimalValue();
    if (node.isBoolean()) return node.booleanValue();
    return null;
  }

  static String sha256(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  static String etag(AdminProduct product) {
    return "\"" + sha256(canonical(product)) + "\"";
  }
}
