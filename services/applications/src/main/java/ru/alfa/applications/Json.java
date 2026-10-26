package ru.alfa.applications;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

public final class Json {
  private Json() {}

  public static final ObjectMapper MAPPER =
      new ObjectMapper(
          JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

  public static ObjectNode obj(Object... entries) {
    ObjectNode result = MAPPER.createObjectNode();
    for (int i = 0; i < entries.length; i += 2) put(result, (String) entries[i], entries[i + 1]);
    return result;
  }

  public static ArrayNode arr(Object... values) {
    ArrayNode result = MAPPER.createArrayNode();
    for (Object value : values) result.add(node(value));
    return result;
  }

  public static JsonNode node(Object value) {
    if (value == null) return NullNode.instance;
    if (value instanceof JsonNode json) return json;
    if (value instanceof Instant || value instanceof LocalDate || value instanceof UUID)
      return TextNode.valueOf(value.toString());
    return MAPPER.valueToTree(value);
  }

  public static void put(ObjectNode target, String key, Object value) {
    target.set(key, node(value));
  }

  public static String s(JsonNode target, String key) {
    return text(target.path(key));
  }

  public static String text(JsonNode value) {
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }

  public static long n(JsonNode target, String key) {
    return target.path(key).asLong();
  }

  public static boolean has(JsonNode target, String key) {
    return !target.path(key).isNull() && !target.path(key).isMissingNode();
  }

  public static Instant instant(JsonNode target, String key) {
    return has(target, key) ? Instant.parse(s(target, key)) : null;
  }

  public static ObjectNode copy(JsonNode node) {
    return (ObjectNode) node.deepCopy();
  }

  public static JsonNode parse(String text) {
    try {
      JsonNode result = MAPPER.readTree(text);
      if (result == null) throw new IllegalArgumentException("Empty JSON");
      // readTree alone accepts trailing tokens; require a single complete JSON value.
      try (var parser = MAPPER.createParser(text)) {
        MAPPER.readTree(parser);
        if (parser.nextToken() != null) throw new IllegalArgumentException("Trailing JSON");
      }
      return result;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid JSON");
    }
  }

  public static JsonNode resource(String name) {
    try (InputStream stream = Json.class.getResourceAsStream(name)) {
      if (stream == null) throw new IllegalStateException("Missing resource " + name);
      return MAPPER.readTree(stream);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read resource", ex);
    }
  }

  // JSON Schema treats 1.0 as an integer. Store its exact integer representation because
  // PostgreSQL's generated bigint columns deliberately reject the textual value "1.0".
  public static JsonNode integralNumbers(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = obj();
      value.fields().forEachRemaining(e -> result.set(e.getKey(), integralNumbers(e.getValue())));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = arr();
      value.forEach(item -> result.add(integralNumbers(item)));
      return result;
    }
    if (value.isFloatingPointNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0) {
      try {
        long exact = value.decimalValue().longValueExact();
        return exact >= Integer.MIN_VALUE && exact <= Integer.MAX_VALUE
            ? IntNode.valueOf((int) exact)
            : LongNode.valueOf(exact);
      } catch (ArithmeticException ignored) {
        return value;
      }
    }
    return value;
  }

  public static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = obj();
      TreeSet<String> names = new TreeSet<>();
      value.fieldNames().forEachRemaining(names::add);
      names.forEach(name -> result.set(name, sorted(value.get(name))));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = arr();
      value.forEach(item -> result.add(sorted(item)));
      return result;
    }
    return value;
  }

  public static String canonical(JsonNode value) {
    try {
      return MAPPER.writeValueAsString(sorted(value));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String etag(JsonNode value) {
    return "\"" + sha(canonical(value)) + "\"";
  }
}
