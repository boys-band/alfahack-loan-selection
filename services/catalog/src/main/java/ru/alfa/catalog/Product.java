package ru.alfa.catalog;

import java.util.List;

public record Product(
    String id,
    String version,
    String name,
    String description,
    List<String> purposeCodes,
    long minAmountRub,
    long maxAmountRub,
    int minTermMonths,
    int maxTermMonths,
    int annualRateBps,
    int minBusinessAgeMonths,
    int maxDebtServiceRatioBps) {
  public Product {
    purposeCodes = List.copyOf(purposeCodes);
  }
}
