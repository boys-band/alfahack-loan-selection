package ru.alfa.disbursement;

final class AccountRules {
  private AccountRules() {}

  static String reason(String actualCompany, String status, String requestedCompany) {
    if (!requestedCompany.equals(actualCompany)) return "ACCOUNT_NOT_OWNED";
    return "ACTIVE".equals(status) ? null : "ACCOUNT_INVALID";
  }
}
