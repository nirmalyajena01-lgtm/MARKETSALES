package com.supermarket.service;

import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptFormatterTest {

    @Test
    @DisplayName("ReceiptFormatter generates standard 40-column thermal receipt with all mandatory fields")
    void testFormatThermalReceiptLayout() {
        SalesTransaction tx = new SalesTransaction(
                1L,
                "SAS-20261002-00042",
                BigDecimal.valueOf(14.00),
                BigDecimal.valueOf(20.00),
                BigDecimal.valueOf(6.00),
                "Clerk-1",
                LocalDateTime.of(2026, 10, 2, 14, 32, 15)
        );

        ReceiptLineItem item1 = new ReceiptLineItem(
                1L, 1L, "MILK01", "Whole Milk 1L", 2,
                BigDecimal.valueOf(1.20), BigDecimal.valueOf(3.50), BigDecimal.valueOf(7.00)
        );
        ReceiptLineItem item2 = new ReceiptLineItem(
                2L, 1L, "BRD002", "Whole Wheat Bread", 1,
                BigDecimal.valueOf(1.25), BigDecimal.valueOf(2.80), BigDecimal.valueOf(2.80)
        );
        ReceiptLineItem item3 = new ReceiptLineItem(
                3L, 1L, "EGG003", "Organic Eggs 12pk", 1,
                BigDecimal.valueOf(2.40), BigDecimal.valueOf(4.20), BigDecimal.valueOf(4.20)
        );

        Receipt receipt = new Receipt(tx, List.of(item1, item2, item3), BigDecimal.valueOf(14.00), BigDecimal.ZERO);

        ReceiptFormatter formatter = new ReceiptFormatter();
        String formatted = formatter.formatThermalReceipt(receipt);

        assertThat(formatted).isNotBlank();

        // Verify mandatory fields
        assertThat(formatted).contains("SUPERMARKET AUTOMATION");
        assertThat(formatted).contains("SAS-20261002-00042");
        assertThat(formatted).contains("2026-10-02 14:32:15");
        assertThat(formatted).contains("Clerk-1");
        assertThat(formatted).contains("MILK01");
        assertThat(formatted).contains("Whole Milk 1L");
        assertThat(formatted).contains("BRD002");
        assertThat(formatted).contains("EGG003");
        assertThat(formatted).contains("₹14.00"); // Total
        assertThat(formatted).contains("₹20.00"); // Tendered
        assertThat(formatted).contains("₹6.00");  // Change
        assertThat(formatted).contains("Thank you for shopping with us!");

        // Line-by-line 40-column width check
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            assertThat(line.length())
                    .as("Line length must not exceed 40 characters: '%s'", line)
                    .isLessThanOrEqualTo(40);
        }

        // Dividers must be exactly 40 characters
        assertThat(lines[0]).isEqualTo("=".repeat(40));
    }

    @Test
    @DisplayName("Long item names wrap cleanly on continuation lines without breaking column alignment")
    void testLongItemNameWrapping() {
        SalesTransaction tx = new SalesTransaction(
                2L,
                "SAS-20261002-00099",
                BigDecimal.valueOf(18.50),
                BigDecimal.valueOf(20.00),
                BigDecimal.valueOf(1.50),
                "Clerk-2",
                LocalDateTime.now()
        );

        String longName = "Organic Extra Virgin Cold Pressed Olive Oil 750ml";
        ReceiptLineItem item = new ReceiptLineItem(
                1L, 2L, "OIL-750", longName, 1,
                BigDecimal.valueOf(10.00), BigDecimal.valueOf(18.50), BigDecimal.valueOf(18.50)
        );

        Receipt receipt = new Receipt(tx, List.of(item));
        ReceiptFormatter formatter = new ReceiptFormatter();
        String formatted = formatter.formatThermalReceipt(receipt);

        assertThat(formatted).contains("OIL-75");
        assertThat(formatted).contains("₹18.50");

        String[] lines = formatted.split("\n");
        for (String line : lines) {
            assertThat(line.length())
                    .as("Line '%s' length must be <= 40 characters", line)
                    .isLessThanOrEqualTo(40);
        }
    }
}
