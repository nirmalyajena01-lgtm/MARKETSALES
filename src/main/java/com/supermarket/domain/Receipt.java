package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain record representing a complete checkout sales receipt, bundling
 * the master transaction header and all itemized line items with snapshot prices.
 */
public record Receipt(
    SalesTransaction transaction,
    List<ReceiptLineItem> lineItems,
    BigDecimal subtotal,
    BigDecimal tax
) {
    public Receipt {
        Objects.requireNonNull(transaction, "SalesTransaction cannot be null");
        lineItems = (lineItems == null) ? List.of() : Collections.unmodifiableList(lineItems);
        if (subtotal == null) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ReceiptLineItem item : lineItems) {
                sum = sum.add(item.subtotal());
            }
            subtotal = sum.setScale(2, RoundingMode.HALF_UP);
        } else {
            subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        }
        tax = Objects.requireNonNullElse(tax, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public Receipt(SalesTransaction transaction, List<ReceiptLineItem> lineItems) {
        this(transaction, lineItems, null, BigDecimal.ZERO);
    }

    public String serialNumber() {
        return transaction.serialNumber();
    }

    public LocalDateTime timestamp() {
        return transaction.timestamp();
    }

    public String cashierName() {
        return transaction.cashierName();
    }

    public BigDecimal totalAmount() {
        return transaction.totalAmount();
    }

    public BigDecimal amountTendered() {
        return transaction.amountTendered();
    }

    public BigDecimal changeDue() {
        return transaction.changeAmount();
    }

    public int totalItemsCount() {
        int count = 0;
        for (ReceiptLineItem item : lineItems) {
            count += item.quantity();
        }
        return count;
    }
}
