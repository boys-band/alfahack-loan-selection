package ru.alfa.agreements;

import static ru.alfa.agreements.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validator for the JSON Schema vocabulary used by the checked-in v2 DTOs. New unsupported
 * assertion keywords fail startup rather than silently weakening validation.
 */
final class Contract {
  static final JsonNode ROOT;

  static {
    try (var in = Contract.class.getResourceAsStream("/schemas.json")) {
      ROOT = MAPPER.readTree(in);
      auditVocabulary(ROOT);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private Contract() {}

  static void auditVocabulary(JsonNode schema) {
    if (schema.isBoolean()) return;
    Set<String> known =
        Set.of(
            "$schema",
            "$defs",
            "$ref",
            "description",
            "type",
            "const",
            "enum",
            "properties",
            "required",
            "additionalProperties",
            "minProperties",
            "dependentRequired",
            "items",
            "minItems",
            "maxItems",
            "uniqueItems",
            "contains",
            "minLength",
            "maxLength",
            "pattern",
            "format",
            "minimum",
            "maximum",
            "allOf",
            "anyOf",
            "oneOf",
            "not",
            "if",
            "then",
            "else");
    schema
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!known.contains(k))
                throw new IllegalStateException("Unsupported schema keyword: " + k);
            });
    for (String k : List.of("$defs", "properties"))
      if (schema.has(k)) schema.get(k).forEach(Contract::auditVocabulary);
    for (String k : List.of("allOf", "anyOf", "oneOf"))
      if (schema.has(k)) schema.get(k).forEach(Contract::auditVocabulary);
    for (String k : List.of("items", "contains", "not", "if", "then", "else"))
      if (schema.has(k)) auditVocabulary(schema.get(k));
    if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean())
      throw new IllegalStateException("Schema additionalProperties is unsupported");
  }

  static void validate(String name, JsonNode value) {
    validateNumbers(value, "");
    String error = check(ROOT.get("$defs").get(name), value, "");
    if (error != null) throw new Problem(422, "VALIDATION_ERROR", error);
  }

  private static void validateNumbers(JsonNode value, String path) {
    if (value.isNumber()) {
      try {
        BigDecimal number = value.decimalValue();
        if (number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
            || number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
          throw new ArithmeticException();
        number.toBigIntegerExact().longValueExact();
      } catch (ArithmeticException | NumberFormatException error) {
        throw new Problem(
            422, "VALIDATION_ERROR", "Ожидается точное целое в диапазоне bigint: " + path);
      }
    } else if (value.isObject()) {
      value.fields().forEachRemaining(e -> validateNumbers(e.getValue(), path + "/" + e.getKey()));
    } else if (value.isArray()) {
      for (int i = 0; i < value.size(); i++) validateNumbers(value.get(i), path + "/" + i);
    }
  }

  static String check(JsonNode schema, JsonNode value, String path) {
    if (schema.isBoolean()) return schema.booleanValue() ? null : "Запрещенное значение: " + path;
    if (schema.has("$ref")) {
      String reference = schema.get("$ref").asText();
      if (!reference.startsWith("#/$defs/"))
        throw new IllegalStateException("External schema reference");
      String error = check(ROOT.at(reference.substring(1)), value, path);
      if (error != null) return error;
    }
    if (schema.has("allOf"))
      for (JsonNode branch : schema.get("allOf")) {
        String error = check(branch, value, path);
        if (error != null) return error;
      }
    for (String keyword : List.of("anyOf", "oneOf"))
      if (schema.has(keyword)) {
        int count = 0;
        for (JsonNode branch : schema.get(keyword)) if (check(branch, value, path) == null) count++;
        if (count == 0 || (keyword.equals("oneOf") && count != 1))
          return "Не соответствует варианту схемы: " + path;
      }
    if (schema.has("not") && check(schema.get("not"), value, path) == null)
      return "Запрещенное сочетание: " + path;
    if (schema.has("if")) {
      String branch = check(schema.get("if"), value, path) == null ? "then" : "else";
      if (schema.has(branch)) {
        String error = check(schema.get(branch), value, path);
        if (error != null) return error;
      }
    }
    if (schema.has("type")) {
      String type = schema.get("type").asText();
      boolean correct =
          switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" ->
                value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("Unknown schema type");
          };
      if (!correct) return "Неверный тип поля: " + path;
    }
    if (schema.has("const") && !same(value, schema.get("const")))
      return "Неверное фиксированное значение: " + path;
    if (schema.has("enum")) {
      boolean found = false;
      for (JsonNode option : schema.get("enum")) found |= same(value, option);
      if (!found) return "Недопустимое значение: " + path;
    }
    if (value.isObject()) {
      if (schema.has("required"))
        for (JsonNode k : schema.get("required"))
          if (!value.has(k.asText())) return "Отсутствует поле: " + path + "/" + k.asText();
      if (schema.has("minProperties") && value.size() < schema.get("minProperties").asInt())
        return "Пустой объект: " + path;
      JsonNode properties = schema.path("properties");
      Iterator<String> keys = value.fieldNames();
      while (keys.hasNext()) {
        String k = keys.next();
        if (properties.has(k)) {
          String error = check(properties.get(k), value.get(k), path + "/" + k);
          if (error != null) return error;
        } else if (schema.has("additionalProperties")
            && schema.get("additionalProperties").isBoolean()
            && !schema.get("additionalProperties").asBoolean())
          return "Неизвестное поле: " + path + "/" + k;
      }
      if (schema.has("dependentRequired")) {
        Iterator<String> dependencies = schema.get("dependentRequired").fieldNames();
        while (dependencies.hasNext()) {
          String k = dependencies.next();
          if (value.has(k))
            for (JsonNode dep : schema.get("dependentRequired").get(k))
              if (!value.has(dep.asText()))
                return "Отсутствует связанное поле: " + path + "/" + dep.asText();
        }
      }
    }
    if (value.isArray()) {
      if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()
          || schema.has("maxItems") && value.size() > schema.get("maxItems").asInt())
        return "Некорректная длина массива: " + path;
      Set<String> seen = new HashSet<>();
      boolean contains = false;
      int i = 0;
      for (JsonNode item : value) {
        if (schema.path("uniqueItems").asBoolean() && !seen.add(sorted(item).toString()))
          return "Повтор элемента: " + path;
        if (schema.has("items")) {
          String error = check(schema.get("items"), item, path + "/" + i);
          if (error != null) return error;
        }
        if (schema.has("contains") && check(schema.get("contains"), item, path) == null)
          contains = true;
        i++;
      }
      if (schema.has("contains") && !contains) return "Не найден обязательный элемент: " + path;
    }
    if (value.isTextual()) {
      String v = value.textValue();
      int length = v.codePointCount(0, v.length());
      if (schema.has("minLength") && length < schema.get("minLength").asInt()
          || schema.has("maxLength") && length > schema.get("maxLength").asInt())
        return "Некорректная длина строки: " + path;
      if (schema.has("pattern")
          && !Pattern.compile(schema.get("pattern").asText()).matcher(v).find())
        return "Некорректный формат: " + path;
      if (schema.has("format"))
        try {
          switch (schema.get("format").asText()) {
            case "uuid" -> {
              if (!v.matches(HttpApi.UUID_PATTERN)) throw new IllegalArgumentException();
            }
            case "date" -> LocalDate.parse(v);
            case "date-time" -> OffsetDateTime.parse(v);
            case "uri" -> {
              if (!URI.create(v).isAbsolute()) throw new IllegalArgumentException();
            }
            default -> throw new IllegalStateException("Unknown schema format");
          }
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
          return "Некорректный формат: " + path;
        }
    }
    if (value.isNumber()) {
      BigDecimal v = value.decimalValue();
      if (schema.has("minimum") && v.compareTo(schema.get("minimum").decimalValue()) < 0
          || schema.has("maximum") && v.compareTo(schema.get("maximum").decimalValue()) > 0)
        return "Число вне диапазона: " + path;
    }
    return null;
  }

  static boolean same(JsonNode a, JsonNode b) {
    if (a == null || b == null) return a == b;
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) return false;
      var keys = a.fieldNames();
      while (keys.hasNext()) {
        String k = keys.next();
        if (!b.has(k) || !same(a.get(k), b.get(k))) return false;
      }
      return true;
    }
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) return false;
      for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
      return true;
    }
    return a.equals(b);
  }
}
