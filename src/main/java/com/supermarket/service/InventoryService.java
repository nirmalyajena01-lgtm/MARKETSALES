package com.supermarket.service;

import com.supermarket.domain.Item;
import com.supermarket.domain.RestockAudit;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.RestockAuditRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Service handling inventory management, catalog queries, low-stock alerts,
 * and employee supply replenishment with mandatory audit logging.
 */
public class InventoryService {
    private final DatabaseManager db;
    private final ItemRepository itemRepository;
    private final RestockAuditRepository restockAuditRepository;

    public InventoryService(DatabaseManager db, ItemRepository itemRepository, RestockAuditRepository restockAuditRepository) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "ItemRepository cannot be null");
        this.restockAuditRepository = Objects.requireNonNull(restockAuditRepository, "RestockAuditRepository cannot be null");
    }

    public InventoryService(DatabaseManager db) {
        this(db, new SqliteItemRepository(db), new SqliteRestockAuditRepository(db));
    }

    public InventoryService() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Retrieves all inventory items in the catalog.
     */
    public List<Item> getAllItems() {
        return itemRepository.findAll();
    }

    /**
     * Searches items by keyword (code or name) and optional category filter.
     */
    public List<Item> searchItems(String keyword, String category) {
        return itemRepository.search(keyword, category);
    }

    /**
     * Retrieves a single item by its unique code.
     */
    public Item getItemByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        return itemRepository.findByCode(code.trim())
                .orElseThrow(() -> new ItemNotFoundException(code.trim()));
    }

    /**
     * Retrieves all items whose current stock is at or below their minimum threshold.
     */
    public List<Item> getLowStockItems() {
        return itemRepository.findLowStock();
    }

    /**
     * Adds or updates an item in the catalog.
     */
    public void saveItem(Item item) {
        Objects.requireNonNull(item, "Item cannot be null");
        if (item.code() == null || item.code().isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        if (item.name() == null || item.name().isBlank()) {
            throw new ValidationException("Item name cannot be null or blank");
        }
        if (item.costPrice() == null || item.costPrice().signum() < 0) {
            throw new ValidationException("Item cost price cannot be negative");
        }
        if (item.currentSellingPrice() == null || item.currentSellingPrice().signum() < 0) {
            throw new ValidationException("Item selling price cannot be negative");
        }
        if (item.currentStock() < 0) {
            throw new ValidationException("Item current stock cannot be negative");
        }
        itemRepository.save(item);
    }

    /**
     * Executes employee restock workflow:
     * 1. Validates item exists and quantityAdded > 0.
     * 2. Within an atomic transaction:
     *    - Increments current_stock in items table.
     *    - Creates and persists an immutable RestockAudit entry.
     */
    public void restockItem(String itemCode, int quantityAdded, String batchNotes, String operator) {
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        if (quantityAdded <= 0) {
            throw new ValidationException("Restock quantity must be strictly positive: " + quantityAdded);
        }

        String code = itemCode.trim();
        String op = (operator == null || operator.isBlank()) ? "Staff" : operator.trim();
        String notes = (batchNotes == null) ? "" : batchNotes.trim();

        try {
            db.executeInTransaction(conn -> {
                Item item = itemRepository.findByCode(conn, code)
                        .orElseThrow(() -> new ItemNotFoundException(code));

                int previousStock = item.currentStock();
                int newStock = previousStock + quantityAdded;

                itemRepository.incrementStock(conn, code, quantityAdded);

                RestockAudit audit = new RestockAudit(
                        null,
                        code,
                        quantityAdded,
                        previousStock,
                        newStock,
                        notes,
                        op,
                        LocalDateTime.now()
                );
                restockAuditRepository.logRestock(conn, audit);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Database error during restock transaction for item: " + code, e);
        }
    }

    /**
     * Retrieves full restock audit history.
     */
    public List<RestockAudit> getRestockAuditHistory() {
        return restockAuditRepository.findAll();
    }

    /**
     * Retrieves restock audit history for a specific item code.
     */
    public List<RestockAudit> getRestockAuditHistory(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        return restockAuditRepository.findByItemCode(itemCode.trim());
    }
}
