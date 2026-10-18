package com.supermarket.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ExceptionContractTest {

    @Test
    @DisplayName("Item not found exceptions retain the item code or supplied cause")
    void itemNotFoundConstructorsExposeContext() {
        ItemNotFoundException itemException = new ItemNotFoundException("DAIRY-001");
        assertThat(itemException.getItemCode()).isEqualTo("DAIRY-001");
        assertThat(itemException).hasMessage("Item code not found in catalog: 'DAIRY-001'");

        Throwable cause = new IllegalStateException("storage unavailable");
        ItemNotFoundException causedException = new ItemNotFoundException("lookup failed", cause);
        assertThat(causedException.getItemCode()).isEmpty();
        assertThat(causedException).hasMessage("lookup failed").hasCause(cause);
    }

    @Test
    @DisplayName("Validation exception constructors preserve messages and causes")
    void validationConstructorsPreserveCause() {
        ValidationException withoutCause = new ValidationException("quantity must be positive");
        assertThat(withoutCause).hasMessage("quantity must be positive").hasNoCause();

        Throwable cause = new NumberFormatException("invalid quantity");
        ValidationException withCause = new ValidationException("invalid input", cause);
        assertThat(withCause).hasMessage("invalid input").hasCause(cause);
    }

    @Test
    @DisplayName("Insufficient stock exception exposes requested and available quantities")
    void insufficientStockConstructorsExposeDetails() {
        InsufficientStockException details = new InsufficientStockException("DAIRY-001", null, 8, 3);
        assertThat(details.getItemCode()).isEqualTo("DAIRY-001");
        assertThat(details.getItemName()).isEqualTo("DAIRY-001");
        assertThat(details.getRequestedQuantity()).isEqualTo(8);
        assertThat(details.getAvailableStock()).isEqualTo(3);
        assertThat(details).hasMessage("Insufficient stock for DAIRY-001 (DAIRY-001). Requested: 8, Available: 3");

        InsufficientStockException messageOnly = new InsufficientStockException("stock unavailable");
        assertThat(messageOnly).hasMessage("stock unavailable");
        assertThat(messageOnly.getItemCode()).isEmpty();
        assertThat(messageOnly.getItemName()).isEmpty();
        assertThat(messageOnly.getRequestedQuantity()).isZero();
        assertThat(messageOnly.getAvailableStock()).isZero();

        assertThatNullPointerException()
                .isThrownBy(() -> new InsufficientStockException(null, "Milk", 1, 0));
    }

    @Test
    @DisplayName("Payment shortfall values are rounded to currency precision")
    void paymentShortfallRoundsAmountsAndComputesDifference() {
        PaymentShortfallException exception = new PaymentShortfallException(
                new BigDecimal("10.005"), new BigDecimal("5.00"));

        assertThat(exception.getTotalPayable()).isEqualByComparingTo("10.01");
        assertThat(exception.getAmountTendered()).isEqualByComparingTo("5.00");
        assertThat(exception.getShortfall()).isEqualByComparingTo("5.01");
        assertThat(exception.getMessage()).contains("10.01", "5.00", "5.01");

        PaymentShortfallException messageOnly = new PaymentShortfallException("payment rejected");
        assertThat(messageOnly).hasMessage("payment rejected");
        assertThat(messageOnly.getTotalPayable()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(messageOnly.getAmountTendered()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(messageOnly.getShortfall()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}