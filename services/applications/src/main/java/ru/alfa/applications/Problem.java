package ru.alfa.applications;

import static ru.alfa.applications.Json.*;

import com.fasterxml.jackson.databind.JsonNode;

public final class Problem extends RuntimeException {
  public final int status;
  public final String code;
  public final JsonNode errors;
  public final long retryAfter;

  public Problem(int status, String code, String detail) {
    this(status, code, detail, arr(), 0);
  }

  public Problem(int status, String code, String detail, JsonNode errors, long retryAfter) {
    super(detail);
    this.status = status;
    this.code = code;
    this.errors = errors;
    this.retryAfter = retryAfter;
  }

  public JsonNode body(String requestId) {
    return obj(
        "type",
        "urn:alfa:problem:" + code.toLowerCase(java.util.Locale.ROOT),
        "title",
        code,
        "status",
        status,
        "detail",
        getMessage(),
        "code",
        code,
        "requestId",
        requestId,
        "errors",
        errors);
  }

  public static void require(boolean condition, int status, String code, String detail) {
    if (!condition) throw new Problem(status, code, detail);
  }

  public static void state(boolean condition) {
    require(condition, 409, "INVALID_STATE", "Command is unavailable in the current state");
  }
}
