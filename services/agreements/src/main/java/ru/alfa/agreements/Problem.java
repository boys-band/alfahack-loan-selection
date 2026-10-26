package ru.alfa.agreements;

final class Problem extends RuntimeException {
  final int status;
  final String code;

  Problem(int status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  static void require(boolean ok, int status, String code, String detail) {
    if (!ok) throw new Problem(status, code, detail);
  }

  static void conflict(boolean ok, String detail) {
    require(ok, 409, "RESOURCE_CONFLICT", detail);
  }

  static void valid(boolean ok, String detail) {
    require(ok, 422, "VALIDATION_ERROR", detail);
  }
}
