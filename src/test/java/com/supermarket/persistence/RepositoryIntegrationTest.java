package com.supermarket.persistence;

import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.RestockAudit;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.RestockAuditRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryIntegrationTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private RestockAuditRepository restockRepo;
    private PriceHistoryRepository priceRepo;
    private AnalyticsRepository analyticsRepo;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Should perform CRUD operations and search on ItemRepository")
    void testItemCrudAndSearch() {
        // Create / Save
        Item newItem = new Item(
                null,
                "TEST-SNACK",
                "Potato Crisps 150g",
                "Snacks",
                new BigDecimal("1.20"),
                new BigDecimal("2.50"),
                50,
                10,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        itemRepo.save(newItem);

        // Find by code
        Optional<Item> fetchedOpt = itemRepo.findByCode("TEST-SNACK");
        assertThat(fetchedOpt).isPresent();
        Item fetched = fetchedOpt.get();
        assertThat(fetched.name()).isEqualTo("Potato Crisps 150g");
        assertThat(fetched.category()).isEqualTo("Snacks");
        assertThat(fetched.costPrice()).isEqualByComparingTo("1.20");
        assertThat(fetched.currentSellingPrice()).isEqualByComparingTo("2.50");
        assertThat(fetched.currentStock()).isEqualTo(50);
        assertThat(fetched.isLowStock()).isFalse();

        // Update
        Item updated = new Item(
                fetched.id(),
                "TEST-SNACK",
                "Potato Crisps 150g (Salted)",
                "Snacks",
                new BigDecimal("1.30"),
                new BigDecimal("2.75"),
                8, // Below minStockThreshold (10)
                10,
                fetched.createdAt(),
                LocalDateTime.now()
        );
        itemRepo.save(updated);

        Item reFetched = itemRepo.findByCode("TEST-SNACK").orElseThrow();
        assertThat(reFetched.name()).isEqualTo("Potato Crisps 150g (Salted)");
        assertThat(reFetched.costPrice()).isEqualByComparingTo("1.30");
        assertThat(reFetched.currentSellingPrice()).isEqualByComparingTo("2.75");
        assertThat(reFetched.currentStock()).isEqualTo(8);
        assertThat(reFetched.isLowStock()).isTrue();

        // Search by keyword
        List<Item> searchResults = itemRepo.search("Crisps", "Snacks");
        assertThat(searchResults).hasSize(1);
        assertThat(searchResults.get(0).code()).isEqualTo("TEST-SNACK");

        // Search with non-matching filter
        List<Item> emptyResults = itemRepo.search("Crisps", "Beverages");
        assertThat(emptyResults).isEmpty();

        // Low stock detection
        List<Item> lowStockList = itemRepo.findLowStock();
        assertThat(lowStockList).extracting(Item::code).contains("TEST-SNACK");
    }

    @Test
    @DisplayName("Should atomically decrement stock and prevent negative inventory")
    void testAtomicStockDecrement() throws SQLException {
        Item milk = itemRepo.findByCode("DAIRY-001").orElseThrow();
        int initialStock = milk.currentStock();
        assertThat(initialStock).isGreaterThan(5);

        // Successful decrement in transaction
        db.executeInTransaction(conn -> {
            boolean success = itemRepo.decrementStock(conn, "DAIRY-001", 5);
            assertThat(success).isTrue();
        });

        Item decrementedMilk = itemRepo.findByCode("DAIRY-001").orElseThrow();
        assertThat(decrementedMilk.currentStock()).isEqualTo(initialStock - 5);

        // Over-decrement should return false (conditional WHERE current_stock >= ?)
        db.executeInTransaction(conn -> {
            boolean success = itemRepo.decrementStock(conn, "DAIRY-001", initialStock + 100);
            assertThat(success).isFalse();
        });

        // Stock should remain unchanged after rejected decrement
        Item unchangedMilk = itemRepo.findByCode("DAIRY-001").orElseThrow();
        assertThat(unchangedMilk.currentStock()).isEqualTo(initialStock - 5);
    }

    @Test
    @DisplayName("Should atomically increment stock on restock")
    void testStockIncrement() throws SQLException {
        Item bread = itemRepo.findByCode("BAKE-001").orElseThrow();
        int initialStock = bread.currentStock();

        db.executeInTransaction(conn -> {
            itemRepo.incrementStock(conn, "BAKE-001", 20);
        });

        Item restockedBread = itemRepo.findByCode("BAKE-001").orElseThrow();
        assertThat(restockedBread.currentStock()).isEqualTo(initialStock + 20);
    }

    @Test
    @DisplayName("Should create sales transaction with immutable price snapshots")
    void testCreateSalesTransactionWithSnapshots() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        SalesTransaction tx = new SalesTransaction(
                null,
                "SAS-20261002-00001",
                new BigDecimal("15.50"),
                new BigDecimal("20.00"),
                new BigDecimal("4.50"),
                "Cashier_Alice",
                now
        );

        ReceiptLineItem line1 = new ReceiptLineItem(
                null,
                null,
                "DAIRY-001",
                "Fresh Whole Milk 1L",
                2,
                new BigDecimal("1.20"),
                new BigDecimal("1.89"),
                new BigDecimal("3.78")
        );

        ReceiptLineItem line2 = new ReceiptLineItem(
                null,
                null,
                "BEV-002",
                "Dark Roast Coffee Beans 500g",
                1,
                new BigDecimal("5.00"),
                new BigDecimal("8.99"),
                new BigDecimal("8.99")
        );

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx, List.of(line1, line2));
        });

        // Query by serial number
        Optional<SalesTransaction> savedTxOpt = salesRepo.findBySerialNumber("SAS-20261002-00001");
        assertThat(savedTxOpt).isPresent();
        SalesTransaction savedTx = savedTxOpt.get();
        assertThat(savedTx.totalAmount()).isEqualByComparingTo("15.50");
        assertThat(savedTx.amountTendered()).isEqualByComparingTo("20.00");
        assertThat(savedTx.changeAmount()).isEqualByComparingTo("4.50");
        assertThat(savedTx.cashierName()).isEqualTo("Cashier_Alice");

        // Verify line items
        List<ReceiptLineItem> savedLines = salesRepo.findItemsByTransactionId(savedTx.id());
        assertThat(savedLines).hasSize(2);

        ReceiptLineItem savedMilk = savedLines.stream().filter(l -> l.itemCode().equals("DAIRY-001")).findFirst().orElseThrow();
        assertThat(savedMilk.quantity()).isEqualTo(2);
        assertThat(savedMilk.unitCostPriceSnapshot()).isEqualByComparingTo("1.20");
        assertThat(savedMilk.unitSellingPriceSnapshot()).isEqualByComparingTo("1.89");
        assertThat(savedMilk.subtotal()).isEqualByComparingTo("3.78");
        // Profit = 3.78 - (2 * 1.20) = 1.38
        assertThat(savedMilk.getProfit()).isEqualByComparingTo("1.38");
    }

    @Test
    @DisplayName("Should insert and retrieve restock audit log")
    void testRestockAuditLog() throws SQLException {
        RestockAudit audit = new RestockAudit(
                null,
                "DAIRY-001",
                50,
                20,
                70,
                "Weekly dairy delivery PO-9912",
                "Warehouse_Bob",
                LocalDateTime.now()
        );

        db.executeInTransaction(conn -> {
            restockRepo.logRestock(conn, audit);
        });

        List<RestockAudit> history = restockRepo.findByItemCode("DAIRY-001");
        assertThat(history).isNotEmpty();
        RestockAudit entry = history.get(0);
        assertThat(entry.itemCode()).isEqualTo("DAIRY-001");
        assertThat(entry.quantityAdded()).isEqualTo(50);
        assertThat(entry.previousStock()).isEqualTo(20);
        assertThat(entry.newStock()).isEqualTo(70);
        assertThat(entry.performedBy()).isEqualTo("Warehouse_Bob");
        assertThat(entry.batchNotes()).isEqualTo("Weekly dairy delivery PO-9912");
    }

        @Test
        @DisplayName("Restock repository writes directly and returns newest matching audit entries first")
        void testRestockConvenienceMethodsAndOrdering() {
        LocalDateTime earlier = LocalDateTime.of(2026, 10, 17, 9, 0);
        LocalDateTime later = earlier.plusHours(2);
        restockRepo.logRestock(new RestockAudit(null, "DAIRY-001", 4, 10, 14,
            "morning delivery", "Staff A", earlier));
        restockRepo.logRestock(new RestockAudit(null, "BAKE-001", 3, 5, 8,
            "afternoon delivery", "Staff B", later));

        assertThat(restockRepo.findAll())
            .extracting(RestockAudit::itemCode)
            .containsExactly("BAKE-001", "DAIRY-001");
        assertThat(restockRepo.findByItemCode("DAIRY-001"))
            .extracting(RestockAudit::timestamp)
            .containsExactly(earlier);
        assertThat(restockRepo.findByItemCode("UNKNOWN")).isEmpty();
        }

        @Test
        @DisplayName("Restock repository handles SQLite UTC timestamps and malformed timestamps")
        void testRestockTimestampParsingFallbacks() throws SQLException {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 18, 4, 5, 6);
        try (Connection connection = db.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO restock_audit_log (item_code, quantity_added, previous_stock, new_stock, timestamp) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, "UTC-ITEM");
            statement.setInt(2, 1);
            statement.setInt(3, 0);
            statement.setInt(4, 1);
            statement.setString(5, "2026-10-18T04:05:06.123Z");
            statement.executeUpdate();

            statement.setString(1, "BAD-TIME");
            statement.setString(5, "not-a-timestamp");
            statement.executeUpdate();
        }

        assertThat(restockRepo.findByItemCode("UTC-ITEM").getFirst().timestamp()).isEqualTo(expected);

        LocalDateTime beforeLookup = LocalDateTime.now().minusSeconds(2);
        LocalDateTime malformedTimestamp = restockRepo.findByItemCode("BAD-TIME").getFirst().timestamp();
        assertThat(malformedTimestamp).isAfter(beforeLookup).isBefore(LocalDateTime.now().plusSeconds(2));
        }

        @Test
        @DisplayName("Restock repository rejects null dependencies and inputs")
        void testRestockRepositoryRejectsNullInputs() throws SQLException {
        RestockAudit audit = new RestockAudit(null, "DAIRY-001", 1, 0, 1,
            "delivery", "Staff", LocalDateTime.now());

        assertThatThrownBy(() -> new SqliteRestockAuditRepository(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restockRepo.logRestock((Connection) null, audit))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> restockRepo.logRestock((Connection) null, null))
            .isInstanceOf(NullPointerException.class);

        try (Connection connection = db.getConnection()) {
            assertThatThrownBy(() -> restockRepo.logRestock(connection, null))
                .isInstanceOf(NullPointerException.class);
        }
        }

    @Test
    @DisplayName("Should insert and retrieve price history log and update selling price")
    void testPriceHistoryLog() throws SQLException {
        PriceHistory history = new PriceHistory(
                null,
                "PROD-001",
                new BigDecimal("1.69"),
                new BigDecimal("1.99"),
                "Store_Manager_Carol",
                LocalDateTime.now()
        );

        db.executeInTransaction(conn -> {
            priceRepo.logPriceChange(conn, history);
            itemRepo.updateSellingPrice(conn, "PROD-001", new BigDecimal("1.99"));
        });

        List<PriceHistory> logs = priceRepo.findByItemCode("PROD-001");
        assertThat(logs).isNotEmpty();
        PriceHistory logged = logs.get(0);
        assertThat(logged.oldPrice()).isEqualByComparingTo("1.69");
        assertThat(logged.newPrice()).isEqualByComparingTo("1.99");
        assertThat(logged.changedBy()).isEqualTo("Store_Manager_Carol");

        Item updatedItem = itemRepo.findByCode("PROD-001").orElseThrow();
        assertThat(updatedItem.currentSellingPrice()).isEqualByComparingTo("1.99");
    }

    @Test
    @DisplayName("Should verify Requirement R3: Future price changes must NEVER alter historical profit statistics")
    void testPriceSnapshotInvarianceForAnalytics() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 1, 10, 30, 0);

        // Initial state of PROD-002 (Granny Smith Apples):
        // Cost: 1.50, Selling: 2.99
        Item apple = itemRepo.findByCode("PROD-002").orElseThrow();
        assertThat(apple.costPrice()).isEqualByComparingTo("124.50");
        assertThat(apple.currentSellingPrice()).isEqualByComparingTo("248.17");

        // Record a sale of 10 apples on Oct 1
        SalesTransaction tx = new SalesTransaction(
                null,
                "TXN-HIST-001",
                new BigDecimal("2481.70"),
                new BigDecimal("2500.00"),
                new BigDecimal("18.30"),
                "Cashier_1",
                txTime
        );

        ReceiptLineItem appleLine = new ReceiptLineItem(
                null,
                null,
                "PROD-002",
                "Granny Smith Apples 1kg",
                10,
                new BigDecimal("124.50"), // Cost snapshot
                new BigDecimal("248.17"), // Selling snapshot
                new BigDecimal("2481.70")  // Subtotal
        );

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx, List.of(appleLine));
        });

        // Check analytics for Oct 1:
        // Revenue = 29.90, Cost = 15.00, Profit = 14.90
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 23, 59, 59);

        List<SalesStatistic> statsBefore = analyticsRepo.getItemStatistics(start, end);
        assertThat(statsBefore).hasSize(1);
        SalesStatistic stat1 = statsBefore.get(0);
        assertThat(stat1.itemCode()).isEqualTo("PROD-002");
        assertThat(stat1.totalQuantitySold()).isEqualTo(10);
        assertThat(stat1.totalRevenue()).isEqualByComparingTo("2481.70");
        assertThat(stat1.totalCost()).isEqualByComparingTo("1245.00");
        assertThat(stat1.netProfit()).isEqualByComparingTo("1236.70");

        AnalyticsSummary summaryBefore = analyticsRepo.getSummary(start, end);
        assertThat(summaryBefore.totalTransactions()).isEqualTo(1);
        assertThat(summaryBefore.totalItemsSold()).isEqualTo(10);
        assertThat(summaryBefore.totalRevenue()).isEqualByComparingTo("2481.70");
        assertThat(summaryBefore.totalNetProfit()).isEqualByComparingTo("1236.70");

        // NOW: The manager drastically changes the price of Apples in the catalog next week
        // Selling price changes from $2.99 to $9.99!
        // Cost price changes from $1.50 to $6.00!
        db.executeInTransaction(conn -> {
            itemRepo.updateSellingPrice(conn, "PROD-002", new BigDecimal("9.99"));
            // Also update cost price in items table
            Item updatedApple = new Item(
                    apple.id(),
                    apple.code(),
                    apple.name(),
                    apple.category(),
                    new BigDecimal("6.00"),
                    new BigDecimal("9.99"),
                    apple.currentStock(),
                    apple.minStockThreshold(),
                    apple.createdAt(),
                    LocalDateTime.now()
            );
            itemRepo.save(conn, updatedApple);
        });

        // Verify catalog changed
        Item catalogApple = itemRepo.findByCode("PROD-002").orElseThrow();
        assertThat(catalogApple.currentSellingPrice()).isEqualByComparingTo("9.99");
        assertThat(catalogApple.costPrice()).isEqualByComparingTo("6.00");

        // CRITICAL INVARIANCE ASSERTION:
        // Historical statistics for Oct 1 MUST REMAIN COMPLETELY UNCHANGED!
        List<SalesStatistic> statsAfter = analyticsRepo.getItemStatistics(start, end);
        assertThat(statsAfter).hasSize(1);
        SalesStatistic statAfter = statsAfter.get(0);
        assertThat(statAfter.totalRevenue()).isEqualByComparingTo("2481.70");
        assertThat(statAfter.totalCost()).isEqualByComparingTo("1245.00");
        assertThat(statAfter.netProfit()).isEqualByComparingTo("1236.70");

        AnalyticsSummary summaryAfter = analyticsRepo.getSummary(start, end);
        assertThat(summaryAfter.totalRevenue()).isEqualByComparingTo("2481.70");
        assertThat(summaryAfter.totalCost()).isEqualByComparingTo("1245.00");
        assertThat(summaryAfter.totalNetProfit()).isEqualByComparingTo("1236.70");
    }
}
