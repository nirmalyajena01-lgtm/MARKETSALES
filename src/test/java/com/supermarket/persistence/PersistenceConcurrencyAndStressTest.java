package com.supermarket.persistence;

import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.RestockAudit;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical stress test harness challenging DatabaseManager, SQLite persistence,
 * multi-threaded concurrency, atomic stock decrement, and transactional rollback.
 */
class PersistenceConcurrencyAndStressTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Stress Test: Concurrent write transactions on in-memory database must yield zero SQLITE_BUSY crashes")
    void testConcurrentWritersNoSqliteBusyInMemory() throws InterruptedException {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);

            Item initialItem = itemRepo.findByCode("PROD-001").orElseThrow();
            int initialStock = initialItem.currentStock();

            int threadCount = 20;
            int opsPerThread = 25;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            final int opId = j;
                            db.executeInTransaction(conn -> {
                                itemRepo.incrementStock(conn, "PROD-001", 1);
                                if (opId % 5 == 0) {
                                    itemRepo.updateSellingPrice(conn, "PROD-001", new BigDecimal("2." + (opId % 100)));
                                }
                            });
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).as("Encountered errors during concurrent in-memory transactions").isEmpty();

            Item item = itemRepo.findByCode("PROD-001").orElseThrow();
            assertThat(item.currentStock()).isEqualTo(initialStock + (threadCount * opsPerThread));
        }
    }

    @Test
    @DisplayName("Stress Test: Concurrent write transactions on file-based WAL database must yield zero SQLITE_BUSY crashes")
    void testConcurrentWritersNoSqliteBusyFileBased() throws InterruptedException, SQLException {
        File dbFile = tempDir.resolve("stress_wal.db").toFile();
        try (DatabaseManager db = DatabaseManager.forDatabase(dbFile.getAbsolutePath())) {
            MigrationRunner.migrate(db);

            // Verify journal_mode is WAL
            try (Connection conn = db.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1).toLowerCase()).isEqualTo("wal");
            }

            ItemRepository itemRepo = new SqliteItemRepository(db);
            int threadCount = 16;
            int opsPerThread = 25;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            final String code = "ITEM-T" + threadId + "-O" + j;
                            db.executeInTransaction(conn -> {
                                Item newItem = new Item(
                                        null,
                                        code,
                                        "Concurrent Item " + code,
                                        "General",
                                        new BigDecimal("5.00"),
                                        new BigDecimal("10.00"),
                                        100,
                                        5,
                                        LocalDateTime.now(),
                                        LocalDateTime.now()
                                );
                                itemRepo.save(conn, newItem);
                            });
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).as("Encountered errors during concurrent file WAL writes").isEmpty();

            // Total created items should be threadCount * opsPerThread = 400
            try (Connection conn = db.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items WHERE code LIKE 'ITEM-T%'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(threadCount * opsPerThread);
            }
        }
    }

    @Test
    @DisplayName("Stress Test: Concurrent readers and writers on file-based WAL database must not block or crash each other")
    void testConcurrentReadersAndWritersFileBased() throws InterruptedException {
        File dbFile = tempDir.resolve("readers_writers.db").toFile();
        try (DatabaseManager db = DatabaseManager.forDatabase(dbFile.getAbsolutePath())) {
            MigrationRunner.migrate(db);

            ItemRepository itemRepo = new SqliteItemRepository(db);
            int writerThreads = 8;
            int readerThreads = 16;
            int totalThreads = writerThreads + readerThreads;
            ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

            CountDownLatch readyLatch = new CountDownLatch(totalThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalThreads);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean stopReaders = new AtomicBoolean(false);
            AtomicInteger readCount = new AtomicInteger(0);

            // Writers
            for (int i = 0; i < writerThreads; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        for (int j = 0; j < 30; j++) {
                            db.executeInTransaction(conn -> {
                                itemRepo.incrementStock(conn, "BEV-001", 1);
                            });
                            Thread.sleep(2);
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Readers
            for (int i = 0; i < readerThreads; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        while (!stopReaders.get()) {
                            List<Item> all = itemRepo.findAll();
                            assertThat(all).isNotEmpty();
                            readCount.incrementAndGet();
                            Thread.sleep(1);
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            // Wait for writers to complete first
            Thread.sleep(1000);
            stopReaders.set(true);

            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).as("Errors during concurrent readers/writers").isEmpty();
            assertThat(readCount.get()).as("Readers should have completed multiple reads").isGreaterThan(100);

            Item bev = itemRepo.findByCode("BEV-001").orElseThrow();
            // Seed stock is 80. Writers incremented 8 * 30 = 240. Total = 320.
            assertThat(bev.currentStock()).isEqualTo(80 + (writerThreads * 30));
        }
    }

    @Test
    @DisplayName("Atomic Stock Decrement: 100 concurrent threads racing for 50 items must prevent overselling and end at exactly 0")
    void testAtomicStockDecrementHighContention() throws InterruptedException {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);

            // Create a dedicated item with stock = 50
            Item testItem = new Item(
                    null,
                    "RACE-ITEM",
                    "Limited Race Item",
                    "Test",
                    new BigDecimal("10.00"),
                    new BigDecimal("15.00"),
                    50,
                    5,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            itemRepo.save(testItem);

            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        boolean decremented = db.executeInTransactionWithResult(conn ->
                                itemRepo.decrementStock(conn, "RACE-ITEM", 1)
                        );
                        if (decremented) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).as("No SQL exceptions should occur during stock race").isEmpty();
            assertThat(successCount.get()).as("Exactly 50 decrements must succeed").isEqualTo(50);
            assertThat(failCount.get()).as("Exactly 50 decrements must be rejected").isEqualTo(50);

            Item finalItem = itemRepo.findByCode("RACE-ITEM").orElseThrow();
            assertThat(finalItem.currentStock()).as("Final stock must be exactly 0, never negative").isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Atomic Stock Decrement: Multi-quantity decrements racing must never oversell")
    void testAtomicStockDecrementMultiQuantityContention() throws InterruptedException {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);

            // Seed an item with stock = 33
            Item testItem = new Item(
                    null,
                    "MULTI-QTY-ITEM",
                    "Multi Quantity Item",
                    "Test",
                    new BigDecimal("2.00"),
                    new BigDecimal("4.00"),
                    33,
                    5,
                    LocalDateTime.now(),
                    LocalDateTime.now()
            );
            itemRepo.save(testItem);

            // 20 threads each trying to decrement 5 units (total requested = 100, only 6 can succeed: 6 * 5 = 30, remaining stock = 3)
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        boolean decremented = db.executeInTransactionWithResult(conn ->
                                itemRepo.decrementStock(conn, "MULTI-QTY-ITEM", 5)
                        );
                        if (decremented) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).isEmpty();
            assertThat(successCount.get()).as("Only 6 requests of 5 units can fit in stock 33").isEqualTo(6);
            assertThat(failCount.get()).as("14 requests must be rejected").isEqualTo(14);

            Item finalItem = itemRepo.findByCode("MULTI-QTY-ITEM").orElseThrow();
            assertThat(finalItem.currentStock()).as("Final stock must be exactly 3 (33 - 30)").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("Rollback Verification: Multi-item transaction failure mid-flight must leave zero partial records and preserve original inventory")
    void testTransactionRollbackLeavesZeroPartialRecords() throws SQLException {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);
            SalesTransactionRepository salesRepo = new SqliteSalesTransactionRepository(db);

            Item item1 = itemRepo.findByCode("PROD-001").orElseThrow();
            Item item2 = itemRepo.findByCode("PROD-002").orElseThrow();
            int initialStock1 = item1.currentStock();
            int initialStock2 = item2.currentStock();

            SalesTransaction tx = new SalesTransaction(
                    null,
                    "TX-FAIL-ROLLBACK-001",
                    new BigDecimal("50.00"),
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    "Cashier_1",
                    LocalDateTime.now()
            );

            ReceiptLineItem line1 = new ReceiptLineItem(null, null, "PROD-001", item1.name(), 5, item1.costPrice(), item1.currentSellingPrice(), new BigDecimal("9.95"));
            ReceiptLineItem line2 = new ReceiptLineItem(null, null, "PROD-002", item2.name(), 10, item2.costPrice(), item2.currentSellingPrice(), new BigDecimal("29.90"));

            assertThatThrownBy(() -> {
                db.executeInTransaction(conn -> {
                    // Step 1: Decrement item 1
                    boolean dec1 = itemRepo.decrementStock(conn, "PROD-001", 5);
                    assertThat(dec1).isTrue();

                    // Step 2: Decrement item 2
                    boolean dec2 = itemRepo.decrementStock(conn, "PROD-002", 10);
                    assertThat(dec2).isTrue();

                    // Step 3: Insert master transaction and line items
                    salesRepo.createTransaction(conn, tx, List.of(line1, line2));

                    // Step 4: Simulate catastrophic failure (e.g., payment gateway failure, inventory constraint violation, out of memory)
                    throw new IllegalStateException("Intentional failure simulating mid-transaction crash");
                });
            }).isInstanceOf(IllegalStateException.class).hasMessageContaining("Intentional failure");

            // Verify master transaction was completely rolled back
            assertThat(salesRepo.findBySerialNumber("TX-FAIL-ROLLBACK-001")).isEmpty();

            // Verify line items were completely rolled back
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sales_transaction_items WHERE item_code IN ('PROD-001', 'PROD-002')");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("No sales line items should exist for rolled-back transaction").isZero();
            }

            // Verify both items have 100% restored inventory
            Item restored1 = itemRepo.findByCode("PROD-001").orElseThrow();
            Item restored2 = itemRepo.findByCode("PROD-002").orElseThrow();
            assertThat(restored1.currentStock()).as("Item 1 stock must be fully restored").isEqualTo(initialStock1);
            assertThat(restored2.currentStock()).as("Item 2 stock must be fully restored").isEqualTo(initialStock2);
        }
    }

    @Test
    @DisplayName("Rollback Verification: CHECK constraint violation must roll back all prior statements in the transaction")
    void testRollbackOnCheckConstraintViolation() throws SQLException {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);

            assertThatThrownBy(() -> {
                db.executeInTransaction(conn -> {
                    // 1. Insert valid item
                    Item validItem = new Item(
                            null,
                            "PRIOR-VALID-ITEM",
                            "Valid Item",
                            "Test",
                            new BigDecimal("1.00"),
                            new BigDecimal("2.00"),
                            10,
                            5,
                            LocalDateTime.now(),
                            LocalDateTime.now()
                    );
                    itemRepo.save(conn, validItem);

                    // 2. Execute statement violating CHECK constraint: amount_tendered >= total_amount
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("INSERT INTO sales_transactions (serial_number, total_amount, amount_tendered, change_amount) VALUES ('TX-BAD-CHECK', 100.0, 50.0, 0.0)");
                    }
                });
            }).isInstanceOf(SQLException.class);

            // Verify prior item was NOT persisted
            assertThat(itemRepo.findByCode("PRIOR-VALID-ITEM")).isEmpty();

            // Verify bad transaction was NOT persisted
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sales_transactions WHERE serial_number = 'TX-BAD-CHECK'");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("Rollback Verification: Foreign Key constraint violation must roll back prior operations")
    void testRollbackOnForeignKeyViolation() {
        try (DatabaseManager db = DatabaseManager.createInMemory()) {
            MigrationRunner.migrate(db);
            ItemRepository itemRepo = new SqliteItemRepository(db);

            Item initialMilk = itemRepo.findByCode("PROD-001").orElseThrow();
            int initialStock = initialMilk.currentStock();

            assertThatThrownBy(() -> {
                db.executeInTransaction(conn -> {
                    // 1. Decrement existing item
                    itemRepo.decrementStock(conn, "PROD-001", 10);

                    // 2. Insert line item with NON-EXISTENT transaction_id = 999999
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO sales_transaction_items (transaction_id, item_code, item_name, quantity, unit_cost_price_snapshot, unit_selling_price_snapshot, subtotal) " +
                            "VALUES (999999, 'PROD-001', 'Milk', 10, 1.0, 2.0, 20.0)")) {
                        ps.executeUpdate();
                    }
                });
            }).isInstanceOf(SQLException.class);

            // Verify PROD-001 stock is unchanged
            Item milk = itemRepo.findByCode("PROD-001").orElseThrow();
            assertThat(milk.currentStock()).isEqualTo(initialStock);
        }
    }

    @Test
    @DisplayName("Throughput & Stress: 16 threads executing mixed operations (sales, restock, price updates, queries) simultaneously")
    void testMixedHighThroughputConcurrencyHarness() throws InterruptedException, SQLException {
        File dbFile = tempDir.resolve("mixed_stress.db").toFile();
        try (DatabaseManager db = DatabaseManager.forDatabase(dbFile.getAbsolutePath())) {
            MigrationRunner.migrate(db);

            ItemRepository itemRepo = new SqliteItemRepository(db);
            SalesTransactionRepository salesRepo = new SqliteSalesTransactionRepository(db);
            RestockAuditRepository restockRepo = new SqliteRestockAuditRepository(db);
            PriceHistoryRepository priceRepo = new SqlitePriceHistoryRepository(db);
            AnalyticsRepository analyticsRepo = new SqliteAnalyticsRepository(db);

            Item initialItem = itemRepo.findByCode("PROD-003").orElseThrow();
            int initialStock = initialItem.currentStock();

            int threadCount = 16;
            int opsPerThread = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger checkoutCount = new AtomicInteger(0);
            AtomicInteger restockCount = new AtomicInteger(0);
            AtomicInteger priceUpdateCount = new AtomicInteger(0);
            AtomicInteger queryCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            int action = (threadId + op) % 4;
                            switch (action) {
                                case 0 -> {
                                    // POS Checkout Transaction
                                    final String serial = "TX-" + threadId + "-" + op + "-" + System.nanoTime();
                                    boolean success = db.executeInTransactionWithResult(conn -> {
                                        boolean decremented = itemRepo.decrementStock(conn, "PROD-003", 1);
                                        if (!decremented) {
                                            return false;
                                        }
                                        SalesTransaction tx = new SalesTransaction(
                                                null,
                                                serial,
                                                new BigDecimal("3.49"),
                                                new BigDecimal("5.00"),
                                                new BigDecimal("1.51"),
                                                "Cashier_" + threadId,
                                                LocalDateTime.now()
                                        );
                                        ReceiptLineItem item = new ReceiptLineItem(
                                                null,
                                                null,
                                                "PROD-003",
                                                "Russet Potatoes",
                                                1,
                                                new BigDecimal("1.80"),
                                                new BigDecimal("3.49"),
                                                new BigDecimal("3.49")
                                        );
                                        salesRepo.createTransaction(conn, tx, List.of(item));
                                        return true;
                                    });
                                    if (success) {
                                        checkoutCount.incrementAndGet();
                                    }
                                }
                                case 1 -> {
                                    // Supply Restock
                                    db.executeInTransaction(conn -> {
                                        itemRepo.incrementStock(conn, "PROD-003", 2);
                                        RestockAudit audit = new RestockAudit(
                                                null,
                                                "PROD-003",
                                                2,
                                                0,
                                                2,
                                                "Batch restock T" + threadId,
                                                "Staff_" + threadId,
                                                LocalDateTime.now()
                                        );
                                        restockRepo.logRestock(conn, audit);
                                    });
                                    restockCount.incrementAndGet();
                                }
                                case 2 -> {
                                    // Dynamic Price Management
                                    final int currentOp = op;
                                    final BigDecimal newPrice = new BigDecimal("3." + (currentOp % 90 + 10));
                                    db.executeInTransaction(conn -> {
                                        itemRepo.updateSellingPrice(conn, "PROD-003", newPrice);
                                        PriceHistory history = new PriceHistory(
                                                null,
                                                "PROD-003",
                                                new BigDecimal("3.49"),
                                                newPrice,
                                                "Manager_" + threadId,
                                                LocalDateTime.now()
                                        );
                                        priceRepo.logPriceChange(conn, history);
                                    });
                                    priceUpdateCount.incrementAndGet();
                                }
                                case 3 -> {
                                    // Concurrent Queries / Analytics
                                    List<Item> search = itemRepo.search("Potatoes", "Produce");
                                    assertThat(search).isNotEmpty();
                                    analyticsRepo.getSummary(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
                                    queryCount.incrementAndGet();
                                }
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).isTrue();
            assertThat(errors).as("No exceptions allowed during heavy mixed stress test").isEmpty();
            assertThat(checkoutCount.get()).isGreaterThan(0);
            assertThat(restockCount.get()).isGreaterThan(0);
            assertThat(priceUpdateCount.get()).isGreaterThan(0);
            assertThat(queryCount.get()).isGreaterThan(0);

            // Verify database consistency
            Item finalItem = itemRepo.findByCode("PROD-003").orElseThrow();
            // Expected stock = initialStock - checkouts + (restocks * 2)
            int expectedStock = initialStock - checkoutCount.get() + (restockCount.get() * 2);
            assertThat(finalItem.currentStock()).as("Final stock must mathematically balance: initial - checkouts + restocks").isEqualTo(expectedStock);

            // Verify total transaction items in DB equals checkouts
            try (Connection conn = db.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sales_transaction_items WHERE item_code = 'PROD-003'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(checkoutCount.get());
            }
        }
    }
}
