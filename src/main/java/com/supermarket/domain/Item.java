package com.supermarket.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain record representing an inventory item in the supermarket catalog.
 */
public record Item(
    Long id,
    String code,
    String name,
    String category,
    BigDecimal costPrice,
    BigDecimal currentSellingPrice,
    int currentStock,
    int minStockThreshold,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public Item {
        Objects.requireNonNull(code, "Item code cannot be null");
        Objects.requireNonNull(name, "Item name cannot be null");
        category = (category == null || category.isBlank()) ? "General" : category;
        costPrice = Objects.requireNonNullElse(costPrice, BigDecimal.ZERO);
        currentSellingPrice = Objects.requireNonNullElse(currentSellingPrice, BigDecimal.ZERO);
    }

    public boolean isLowStock() {
        return currentStock <= minStockThreshold;
    }

    public Item withStock(int newStock) {
        return new Item(id, code, name, category, costPrice, currentSellingPrice, newStock, minStockThreshold, createdAt, LocalDateTime.now());
    }

    public Item withSellingPrice(BigDecimal newPrice) {
        return new Item(id, code, name, category, costPrice, newPrice, currentStock, minStockThreshold, createdAt, LocalDateTime.now());
    }
}
