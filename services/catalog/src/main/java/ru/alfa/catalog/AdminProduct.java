package ru.alfa.catalog;

import java.util.List;

public record AdminProduct(Product product, boolean active, List<Product> versions) {
  public AdminProduct {
    versions = List.copyOf(versions);
  }
}
