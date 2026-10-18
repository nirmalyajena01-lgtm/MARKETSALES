package com.supermarket.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain record representing an employee stock replenishment audit entry.
 */
public record RestockAudit(
    Long id,
    String itemCode,
    int quantityAdded,
    int previousStock,
    int newStock,
    String batchNotes,
    String performedBy,
    LocalDateTime timestamp
) {
    public RestockAudit {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        performedBy = (performedBy == null || performedBy.isBlank()) ? "Staff" : performedBy;
        timestamp = (timestamp == null) ? LocalDateTime.now() : timestamp;
    }
}
