package ru.alfa.disbursement;

import java.util.List;

public final class ApiException extends RuntimeException {
  final int status;
  final String code;
  final List<FieldError> errors;

  public record FieldError(String path, String message) {}

  ApiException(int status, String code, String detail) {
    this(status, code, detail, List.of());
  }

  ApiException(int status, String code, String detail, List<FieldError> errors) {
    super(detail);
    this.status = status;
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  static ApiException conflict(String detail) {
    return new ApiException(409, "RESOURCE_CONFLICT", detail);
  }

  static ApiException unavailable() {
    return new ApiException(
        503,
        "SERVICE_UNAVAILABLE",
        "A required service or database is unavailable; retry the original request.");
  }
}
