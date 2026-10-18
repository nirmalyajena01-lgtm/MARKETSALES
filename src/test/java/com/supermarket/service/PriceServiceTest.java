package com.supermarket.service;

import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceServiceTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private PriceHistoryRepository priceRepo;
    private PriceService priceService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);
        priceService = new PriceService(db, itemRepo, priceRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Manager dynamic selling price update updates catalog and records audit log")
    void testUpdateSellingPrice() {
        // Initial price of BEV-001 (Sparkling Mineral Water 1.5L) is ₹107.07.
        Item water = itemRepo.findByCode("BEV-001").orElseThrow();
        assertThat(water.currentSellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(107.07));

        priceService.updateSellingPrice("BEV-001", BigDecimal.valueOf(148.57), "StoreManagerAlice");

        // Verify catalog updated
        Item updatedWater = itemRepo.findByCode("BEV-001").orElseThrow();
        assertThat(updatedWater.currentSellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(148.57));

        // Verify price history audit
        List<PriceHistory> history = priceService.getPriceHistory("BEV-001");
        assertThat(history).hasSize(1);

        PriceHistory entry = history.get(0);
        assertThat(entry.itemCode()).isEqualTo("BEV-001");
        assertThat(entry.oldPrice()).isEqualByComparingTo(BigDecimal.valueOf(107.07));
        assertThat(entry.newPrice()).isEqualByComparingTo(BigDecimal.valueOf(148.57));
        assertThat(entry.changedBy()).isEqualTo("StoreManagerAlice");
        assertThat(entry.timestamp()).isNotNull();

        priceService.updateSellingPrice("BEV-001", BigDecimal.valueOf(165.17), "StoreManagerBob");

        List<PriceHistory> secondHistory = priceService.getPriceHistory("BEV-001");
        assertThat(secondHistory).hasSize(2);
        assertThat(secondHistory.get(0).newPrice()).isEqualByComparingTo(BigDecimal.valueOf(165.17));
        assertThat(secondHistory.get(0).oldPrice()).isEqualByComparingTo(BigDecimal.valueOf(148.57));
    }

    @Test
    @DisplayName("Selling price validations reject zero, negative, null, or blank inputs")
    void testPriceValidations() {
        // Zero price
        assertThatThrownBy(() -> priceService.updateSellingPrice("BEV-001", BigDecimal.ZERO, "Manager"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("positive");

        // Negative price
        assertThatThrownBy(() -> priceService.updateSellingPrice("BEV-001", BigDecimal.valueOf(-2.50), "Manager"))
                .isInstanceOf(ValidationException.class);

        // Null price
        assertThatThrownBy(() -> priceService.updateSellingPrice("BEV-001", null, "Manager"))
                .isInstanceOf(ValidationException.class);

        // Non-existent item
        assertThatThrownBy(() -> priceService.updateSellingPrice("DOES-NOT-EXIST", BigDecimal.valueOf(5.00), "Manager"))
                .isInstanceOf(ItemNotFoundException.class);

        // Blank code
        assertThatThrownBy(() -> priceService.updateSellingPrice("  ", BigDecimal.valueOf(5.00), "Manager"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("isSellingPriceBelowCost helper warns if proposed price is below wholesale cost")
    void testIsSellingPriceBelowCost() {
        // BEV-002 Dark Roast Coffee costs ₹415.00.
        assertThat(priceService.isSellingPriceBelowCost("BEV-002", BigDecimal.valueOf(414.99))).isTrue();
        assertThat(priceService.isSellingPriceBelowCost("BEV-002", BigDecimal.valueOf(415.00))).isFalse();
        assertThat(priceService.isSellingPriceBelowCost("BEV-002", BigDecimal.valueOf(539.50))).isFalse();
    }

    @Test
    @DisplayName("Deterministic tie-breaking orders price history by id DESC when timestamps are identical")
    void testPriceHistoryDeterministicTieBreakOnIdenticalTimestamps() {
        LocalDateTime fixedTime = LocalDateTime.of(2026, 10, 2, 12, 0, 0);
        priceRepo.logPriceChange(new PriceHistory(null, "BEV-001", BigDecimal.valueOf(1.00), BigDecimal.valueOf(1.50), "Mgr1", fixedTime));
        priceRepo.logPriceChange(new PriceHistory(null, "BEV-001", BigDecimal.valueOf(1.50), BigDecimal.valueOf(2.00), "Mgr2", fixedTime));
        priceRepo.logPriceChange(new PriceHistory(null, "BEV-001", BigDecimal.valueOf(2.00), BigDecimal.valueOf(2.50), "Mgr3", fixedTime));

        List<PriceHistory> history = priceService.getPriceHistory("BEV-001");
        assertThat(history).hasSize(3);
        // The most recently logged (highest id) must be at index 0
        assertThat(history.get(0).newPrice()).isEqualByComparingTo(BigDecimal.valueOf(2.50));
        assertThat(history.get(1).newPrice()).isEqualByComparingTo(BigDecimal.valueOf(2.00));
        assertThat(history.get(2).newPrice()).isEqualByComparingTo(BigDecimal.valueOf(1.50));

        List<PriceHistory> allHistory = priceRepo.findAll();
        assertThat(allHistory.get(0).newPrice()).isEqualByComparingTo(BigDecimal.valueOf(2.50));
    }
}
