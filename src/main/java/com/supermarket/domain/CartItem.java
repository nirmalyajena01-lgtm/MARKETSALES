package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain record representing an item line inside an active POS shopping cart.
 */
public record CartItem(
    String itemCode,
    String itemName,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal subtotal
) {
    public CartItem {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        itemName = (itemName == null || itemName.isBlank()) ? itemCode : itemName;
        unitPrice = Objects.requireNonNullElse(unitPrice, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (quantity <= 0) {
            throw new IllegalArgumentException("CartItem quantity must be strictly positive: " + quantity);
        }
        if (subtotal == null) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        } else {
            subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public CartItem(String itemCode, String itemName, BigDecimal unitPrice, int quantity) {
        this(itemCode, itemName, unitPrice, quantity, null);
    }

    public CartItem withQuantity(int newQuantity) {
        return new CartItem(itemCode, itemName, unitPrice, newQuantity);
    }

    public CartItem withAddedQuantity(int delta) {
        return new CartItem(itemCode, itemName, unitPrice, this.quantity + delta);
    }
}
