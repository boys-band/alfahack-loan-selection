package ru.alfa.verification;

import com.fasterxml.jackson.databind.node.ArrayNode;

final class ApiException extends RuntimeException {
  final int status;
  final String code;
  final ArrayNode errors;

  ApiException(int status, String code, String detail) {
    this(status, code, detail, Json.array());
  }

  ApiException(int status, String code, String detail, ArrayNode errors) {
    super(detail);
    this.status = status;
    this.code = code;
    this.errors = errors;
  }

  static ApiException invalid(String path, String message) {
    return new ApiException(
        422,
        "VALIDATION_ERROR",
        "Request validation failed",
        Json.array().add(Json.object().put("path", path).put("message", message)));
  }

  static ApiException unavailable() {
    return new ApiException(
        503, "DEPENDENCY_UNAVAILABLE", "Verification source or database is unavailable");
  }
}
