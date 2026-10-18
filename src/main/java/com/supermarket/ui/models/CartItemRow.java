package com.supermarket.ui.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Presentation model representing a row in the POS cart TableView.
 * Provides standard JavaBean properties for JavaFX PropertyValueFactory bindings.
 */
public class CartItemRow {
    private final String itemCode;
    private final String itemName;
    private final String category;
    private final BigDecimal unitPrice;
    private int quantity;
    private BigDecimal subtotal;

    public CartItemRow(String itemCode, String itemName, String category, BigDecimal unitPrice, int quantity) {
        this.itemCode = Objects.requireNonNull(itemCode, "Item code cannot be null");
        this.itemName = (itemName == null || itemName.isBlank()) ? itemCode : itemName;
        this.category = (category == null || category.isBlank()) ? "General" : category;
        this.unitPrice = Objects.requireNonNullElse(unitPrice, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        this.quantity = Math.max(1, quantity);
        this.subtotal = this.unitPrice.multiply(BigDecimal.valueOf(this.quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public String getItemCode() {
        return itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public String getFormattedUnitPrice() {
        return "₹" + unitPrice.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
        this.subtotal = this.unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public String getFormattedSubtotal() {
        return "₹" + subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CartItemRow that = (CartItemRow) o;
        return Objects.equals(itemCode, that.itemCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemCode);
    }
}
