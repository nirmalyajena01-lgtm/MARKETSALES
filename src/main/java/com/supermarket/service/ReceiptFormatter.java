package com.supermarket.service;

import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Service engine for generating standard 40-column monospace thermal receipts.
 * Ensures consistent column alignment, clean line wrapping/truncation for long names,
 * and standard thermal POS bill aesthetics.
 */
public class ReceiptFormatter {
    public static final int RECEIPT_WIDTH = 40;
    private static final String STORE_NAME = "SUPERMARKET AUTOMATION";
    private static final String STORE_ADDRESS = "123 Market Street, City";
    private static final String STORE_PHONE = "Tel: (555) 019-2834";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String storeName;
    private final String storeAddress;
    private final String storePhone;
    private final int width;

    public ReceiptFormatter() {
        this(STORE_NAME, STORE_ADDRESS, STORE_PHONE, RECEIPT_WIDTH);
    }

    public ReceiptFormatter(String storeName, String storeAddress, String storePhone, int width) {
        this.storeName = (storeName == null || storeName.isBlank()) ? STORE_NAME : storeName;
        this.storeAddress = (storeAddress == null || storeAddress.isBlank()) ? STORE_ADDRESS : storeAddress;
        this.storePhone = (storePhone == null || storePhone.isBlank()) ? STORE_PHONE : storePhone;
        this.width = width > 20 ? width : RECEIPT_WIDTH;
    }

    /**
     * Formats a complete 40-column monospace thermal receipt.
     */
    public String formatThermalReceipt(Receipt receipt) {
        Objects.requireNonNull(receipt, "Receipt cannot be null");

        StringBuilder sb = new StringBuilder();
        String doubleLine = "=".repeat(width);
        String singleLine = "-".repeat(width);

        // Header
        sb.append(doubleLine).append("\n");
        sb.append(center(storeName, width)).append("\n");
        sb.append(center(storeAddress, width)).append("\n");
        sb.append(center(storePhone, width)).append("\n");
        sb.append(doubleLine).append("\n");

        // Transaction Metadata
        sb.append(formatKeyValue("Receipt No", receipt.serialNumber())).append("\n");
        sb.append(formatKeyValue("Date/Time", receipt.timestamp().format(DATE_FMT))).append("\n");
        sb.append(formatKeyValue("Cashier", receipt.cashierName())).append("\n");
        sb.append(singleLine).append("\n");

        sb.append(String.format("%-14s %-6s %3s %6s %7s", "Item", "Code", "Qty", "Price", "Total")).append("\n");
        sb.append(singleLine).append("\n");

        // Itemized Line Items
        for (ReceiptLineItem item : receipt.lineItems()) {
            String fullName = item.itemName();
            String code = truncate(item.itemCode(), 6);
            int qty = item.quantity();
            BigDecimal unitPrice = item.unitSellingPriceSnapshot();
            BigDecimal total = item.subtotal();

            String nameFirstLine = truncate(fullName, 14);
            sb.append(String.format("%-14s %-6s %3d %6.2f %7.2f",
                    nameFirstLine,
                    code,
                    qty,
                    unitPrice.doubleValue(),
                    total.doubleValue())).append("\n");

            // If item name was longer than 14 characters, print the continuation cleanly indented
            if (fullName.length() > 14) {
                String remainder = fullName.substring(14).trim();
                while (!remainder.isEmpty()) {
                    int chunkLen = Math.min(remainder.length(), width - 4);
                    String chunk = remainder.substring(0, chunkLen).trim();
                    sb.append("  ").append(chunk).append("\n");
                    remainder = remainder.substring(chunkLen).trim();
                }
            }
        }

        // Totals & Financials
        sb.append(singleLine).append("\n");
        sb.append(formatRow("Subtotal:", formatCurrency(receipt.subtotal()))).append("\n");
        if (receipt.tax().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(formatRow("Tax:", formatCurrency(receipt.tax()))).append("\n");
        }
        sb.append(formatRow("TOTAL AMOUNT PAYABLE:", formatCurrency(receipt.totalAmount()))).append("\n");
        sb.append(singleLine).append("\n");
        sb.append(formatRow("Cash Tendered:", formatCurrency(receipt.amountTendered()))).append("\n");
        sb.append(formatRow("Change Due:", formatCurrency(receipt.changeDue()))).append("\n");

        // Footer
        sb.append(doubleLine).append("\n");
        sb.append(center("Thank you for shopping with us!", width)).append("\n");
        sb.append(center("Please visit again soon!", width)).append("\n");
        sb.append(doubleLine).append("\n");

        return sb.toString();
    }

    private String formatKeyValue(String key, String value) {
        String prefix = key + ": ";
        return String.format("%-11s%s", prefix, value);
    }

    private String formatRow(String label, String value) {
        int labelWidth = width - value.length();
        if (labelWidth <= 0) {
            return label + " " + value;
        }
        return String.format("%-" + labelWidth + "s%s", label, value);
    }

    private String formatCurrency(BigDecimal amount) {
        return "₹" + Objects.requireNonNullElse(amount, BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String center(String text, int targetWidth) {
        if (text == null) {
            return " ".repeat(targetWidth);
        }
        if (text.length() >= targetWidth) {
            return text.substring(0, targetWidth);
        }
        int totalPad = targetWidth - text.length();
        int leftPad = totalPad / 2;
        int rightPad = totalPad - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
