package com.supermarket.service;

import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Service handling dynamic selling price updates and managerial price history audits.
 */
public class PriceService {
    private final DatabaseManager db;
    private final ItemRepository itemRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public PriceService(DatabaseManager db, ItemRepository itemRepository, PriceHistoryRepository priceHistoryRepository) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "ItemRepository cannot be null");
        this.priceHistoryRepository = Objects.requireNonNull(priceHistoryRepository, "PriceHistoryRepository cannot be null");
    }

    public PriceService(DatabaseManager db) {
        this(db, new SqliteItemRepository(db), new SqlitePriceHistoryRepository(db));
    }

    public PriceService() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Updates an item's current selling price and logs an audit trail in price_history_log.
     * Validates that newPrice > 0 and item exists.
     */
    public void updateSellingPrice(String itemCode, BigDecimal newPrice, String managerName) {
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("New selling price must be strictly positive: " + newPrice);
        }

        String code = itemCode.trim();
        BigDecimal price = newPrice.setScale(2, RoundingMode.HALF_UP);
        String manager = (managerName == null || managerName.isBlank()) ? "Manager" : managerName.trim();

        try {
            db.executeInTransaction(conn -> {
                Item item = itemRepository.findByCode(conn, code)
                        .orElseThrow(() -> new ItemNotFoundException(code));

                BigDecimal oldPrice = item.currentSellingPrice();
                itemRepository.updateSellingPrice(conn, code, price);

                PriceHistory history = new PriceHistory(
                        null,
                        code,
                        oldPrice,
                        price,
                        manager,
                        LocalDateTime.now()
                );
                priceHistoryRepository.logPriceChange(conn, history);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Database error during price update for item: " + code, e);
        }
    }

    /**
     * Retrieves the price change audit history for a specific item code.
     */
    public List<PriceHistory> getPriceHistory(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        return priceHistoryRepository.findByItemCode(itemCode.trim());
    }

    /**
     * Retrieves all price histories across the catalog.
     */
    public List<PriceHistory> getAllPriceHistories() {
        return priceHistoryRepository.findAll();
    }

    /**
     * Helper to verify if a proposed selling price falls below the catalog cost price.
     */
    public boolean isSellingPriceBelowCost(String itemCode, BigDecimal proposedPrice) {
        if (itemCode == null || proposedPrice == null) {
            return false;
        }
        return itemRepository.findByCode(itemCode.trim())
                .map(item -> proposedPrice.compareTo(item.costPrice()) < 0)
                .orElse(false);
    }
}
