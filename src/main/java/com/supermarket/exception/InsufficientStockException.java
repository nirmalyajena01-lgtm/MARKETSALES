package com.supermarket.exception;

import java.util.Objects;

/**
 * Exception thrown when a checkout or cart operation requests more stock
 * than is currently available in the supermarket catalog.
 */
public class InsufficientStockException extends RuntimeException {
    private final String itemCode;
    private final String itemName;
    private final int requestedQuantity;
    private final int availableStock;

    public InsufficientStockException(String itemCode, String itemName, int requestedQuantity, int availableStock) {
        super(String.format("Insufficient stock for %s (%s). Requested: %d, Available: %d",
                itemName != null ? itemName : itemCode,
                itemCode,
                requestedQuantity,
                availableStock));
        this.itemCode = Objects.requireNonNull(itemCode, "Item code cannot be null");
        this.itemName = itemName != null ? itemName : itemCode;
        this.requestedQuantity = requestedQuantity;
        this.availableStock = availableStock;
    }

    public InsufficientStockException(String message) {
        super(message);
        this.itemCode = "";
        this.itemName = "";
        this.requestedQuantity = 0;
        this.availableStock = 0;
    }

    public String getItemCode() {
        return itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableStock() {
        return availableStock;
    }
}
