package com.supermarket.exception;

/**
 * Exception thrown when a catalog lookup for a specified item code returns no record.
 */
public class ItemNotFoundException extends RuntimeException {
    private final String itemCode;

    public ItemNotFoundException(String itemCode) {
        super(String.format("Item code not found in catalog: '%s'", itemCode));
        this.itemCode = itemCode;
    }

    public ItemNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.itemCode = "";
    }

    public String getItemCode() {
        return itemCode;
    }
}
