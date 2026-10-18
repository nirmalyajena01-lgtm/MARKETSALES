package com.supermarket.service;

import com.supermarket.domain.Cart;
import com.supermarket.domain.CartItem;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.RestockAudit;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.PaymentShortfallException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.RestockAuditRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical Challenger stress test suite for BillingService:
 * 1. Concurrent checkouts competing for limited inventory (preventing overselling, stock decrements to 0).
 * 2. Multi-item cart atomic rollback when final or mid-flight item fails stock check.
 * 3. Payment shortfall scenarios (underpayment, penny deficit, zero tendered) verifying zero DB mutation.
 * 4. Concurrent checkout vs restock invariants and file-based SQLite WAL persistence.
 */
class BillingConcurrencyAndRollbackStressTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private RestockAuditRepository restockRepo;
    private BillingService billingService;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);
        billingService = new BillingService(db, itemRepo, salesRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // =========================================================================
    // TASK 1: CONCURRENCY STRESS TESTS (OVERSELLING PREVENTION & ZERO STOCK)
    // =========================================================================

    @Test
    @DisplayName("Task 1: 50 concurrent threads competing for 15 items - exactly 15 succeed, 35 fail, stock reaches 0, zero overselling")
    void testConcurrentCheckoutSingleItemPreventOverselling() throws InterruptedException {
        // Create an isolated limited-inventory item
        String testCode = "STRESS-ITEM-01";
        int initialStock = 15;
        BigDecimal price = BigDecimal.valueOf(9.99);
        Item item = new Item(null, testCode, "High Demand Gadget", "Electronics",
                BigDecimal.valueOf(5.00), price, initialStock, 5,
                LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(item);

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> serialNumbers = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int cashierId = i;
            Cart cart = billingService.createCart();
            billingService.addItemToCart(cart, testCode, 1);
            executor.submit(() -> {
                try {
                    startGate.await();
                    Receipt receipt = billingService.checkout(cart, BigDecimal.valueOf(20.00), "Cashier-" + cashierId);
                    successCount.incrementAndGet();
                    serialNumbers.add(receipt.serialNumber());
                } catch (InsufficientStockException e) {
                    insufficientStockCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release the hounds
        startGate.countDown();
        boolean finished = doneGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("All concurrent checkout threads must finish within timeout").isTrue();
        for (Throwable t : unexpectedErrors) {
            t.printStackTrace();
        }
        assertThat(unexpectedErrors).as("No unexpected exceptions (deadlocks/SQLITE_BUSY)").isEmpty();

        // Strict assertions on concurrency outcomes
        assertThat(successCount.get())
                .as("Exactly 15 transactions must succeed (equal to initial stock)")
                .isEqualTo(initialStock);

        assertThat(insufficientStockCount.get())
                .as("Remaining 35 threads must be cleanly rejected with InsufficientStockException")
                .isEqualTo(threadCount - initialStock);

        // Verify database stock invariant: must be exactly 0, NEVER negative
        Item finalItem = itemRepo.findByCode(testCode).orElseThrow();
        assertThat(finalItem.currentStock())
                .as("Stock must decrement to exactly zero without underselling or overselling")
                .isEqualTo(0);

        // Verify serial numbers: exactly 15 distinct collision-free serial numbers
        assertThat(serialNumbers).hasSize(initialStock);
        Set<String> uniqueSerials = new HashSet<>(serialNumbers);
        assertThat(uniqueSerials)
                .as("Every successful transaction must receive a distinct unique serial number")
                .hasSize(initialStock);

        // Verify total line items in database
        int totalUnitsSold = countTotalUnitsSoldForCode(testCode);
        assertThat(totalUnitsSold)
                .as("Total units recorded in sales_transaction_items must equal initial stock")
                .isEqualTo(initialStock);
    }

    @Test
    @DisplayName("Task 1: Concurrent checkout of heterogeneous multi-item carts maintains strict stock conservation")
    void testConcurrentMultiItemCheckoutUnderContention() throws InterruptedException {
        // 3 items with distinct stock limits
        Item itemA = new Item(null, "HET-A", "Hetero Item A", "Dry Goods", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 12, 2, LocalDateTime.now(), LocalDateTime.now());
        Item itemB = new Item(null, "HET-B", "Hetero Item B", "Dry Goods", BigDecimal.valueOf(2.00), BigDecimal.valueOf(4.00), 16, 2, LocalDateTime.now(), LocalDateTime.now());
        Item itemC = new Item(null, "HET-C", "Hetero Item C", "Dry Goods", BigDecimal.valueOf(3.00), BigDecimal.valueOf(6.00), 8, 2, LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(itemA);
        itemRepo.save(itemB);
        itemRepo.save(itemC);

        int threadCount = 36;
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int type = i % 3;
            final int threadId = i;
            Cart cart = billingService.createCart();
            if (type == 0) {
                billingService.addItemToCart(cart, "HET-A", 2);
                billingService.addItemToCart(cart, "HET-B", 1);
            } else if (type == 1) {
                billingService.addItemToCart(cart, "HET-B", 2);
                billingService.addItemToCart(cart, "HET-C", 1);
            } else {
                billingService.addItemToCart(cart, "HET-A", 1);
                billingService.addItemToCart(cart, "HET-C", 2);
            }
            executor.submit(() -> {
                try {
                    startGate.await();
                    billingService.checkout(cart, BigDecimal.valueOf(100.00), "Cashier-" + threadId);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    rejectedCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean done = doneGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(done).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get() + rejectedCount.get()).isEqualTo(threadCount);

        // Strict stock conservation checks
        int finalStockA = itemRepo.findByCode("HET-A").orElseThrow().currentStock();
        int finalStockB = itemRepo.findByCode("HET-B").orElseThrow().currentStock();
        int finalStockC = itemRepo.findByCode("HET-C").orElseThrow().currentStock();

        int soldA = countTotalUnitsSoldForCode("HET-A");
        int soldB = countTotalUnitsSoldForCode("HET-B");
        int soldC = countTotalUnitsSoldForCode("HET-C");

        assertThat(finalStockA).as("Item A stock must never be negative").isGreaterThanOrEqualTo(0);
        assertThat(finalStockB).as("Item B stock must never be negative").isGreaterThanOrEqualTo(0);
        assertThat(finalStockC).as("Item C stock must never be negative").isGreaterThanOrEqualTo(0);

        assertThat(finalStockA + soldA).as("Item A initial stock invariant").isEqualTo(12);
        assertThat(finalStockB + soldB).as("Item B initial stock invariant").isEqualTo(16);
        assertThat(finalStockC + soldC).as("Item C initial stock invariant").isEqualTo(8);
    }

    // =========================================================================
    // TASK 2: ATOMIC ROLLBACK VERIFICATION (100% CLEAN ROLLBACK ON FAILURE)
    // =========================================================================

    @Test
    @DisplayName("Task 2: Multi-item cart where last item fails inventory check results in 100% rollback")
    void testAtomicRollbackWhenLastItemFailsInMultiItemCart() {
        // Setup 6 distinct catalog items
        // 5 items have plenty of stock, 6th item has low stock
        Item i1 = new Item(null, "ROLL-01", "Item 1", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 50, 5, LocalDateTime.now(), LocalDateTime.now());
        Item i2 = new Item(null, "ROLL-02", "Item 2", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 40, 5, LocalDateTime.now(), LocalDateTime.now());
        Item i3 = new Item(null, "ROLL-03", "Item 3", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 30, 5, LocalDateTime.now(), LocalDateTime.now());
        Item i4 = new Item(null, "ROLL-04", "Item 4", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 20, 5, LocalDateTime.now(), LocalDateTime.now());
        Item i5 = new Item(null, "ROLL-05", "Item 5", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 10, 5, LocalDateTime.now(), LocalDateTime.now());
        Item i6 = new Item(null, "ROLL-06", "Item 6", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 3, 5, LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(i1);
        itemRepo.save(i2);
        itemRepo.save(i3);
        itemRepo.save(i4);
        itemRepo.save(i5);
        itemRepo.save(i6);

        long txCountBefore = countAllTransactions();

        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "ROLL-01", 5);
        billingService.addItemToCart(cart, "ROLL-02", 4);
        billingService.addItemToCart(cart, "ROLL-03", 3);
        billingService.addItemToCart(cart, "ROLL-04", 2);
        billingService.addItemToCart(cart, "ROLL-05", 1);
        billingService.addItemToCart(cart, "ROLL-06", 3); // cart valid initially (3 <= 3)

        assertThat(cart.getDistinctItemCount()).isEqualTo(6);

        // Before checkout, simulate another register or inventory count taking the last 2 units of ROLL-06, leaving only 1
        Item roll6Reduced = itemRepo.findByCode("ROLL-06").orElseThrow().withStock(1);
        itemRepo.save(roll6Reduced);

        // Now checkout should fail specifically because ROLL-06 requests 3 but DB only has 1
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(200.00), "Cashier-1"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("ROLL-06")
                .hasMessageContaining("Requested: 3")
                .hasMessageContaining("Available: 1");

        // VERIFY 100% ATOMIC ROLLBACK:
        // Stock for ALL items 1-5 must remain untouched
        assertThat(itemRepo.findByCode("ROLL-01").orElseThrow().currentStock()).isEqualTo(50);
        assertThat(itemRepo.findByCode("ROLL-02").orElseThrow().currentStock()).isEqualTo(40);
        assertThat(itemRepo.findByCode("ROLL-03").orElseThrow().currentStock()).isEqualTo(30);
        assertThat(itemRepo.findByCode("ROLL-04").orElseThrow().currentStock()).isEqualTo(20);
        assertThat(itemRepo.findByCode("ROLL-05").orElseThrow().currentStock()).isEqualTo(10);
        assertThat(itemRepo.findByCode("ROLL-06").orElseThrow().currentStock()).isEqualTo(1);

        // Zero transactions or line items persisted
        assertThat(countAllTransactions()).isEqualTo(txCountBefore);
        assertThat(countTotalUnitsSoldForCode("ROLL-01")).isEqualTo(0);

        // Active cart remains intact in memory so cashier does not have to re-scan items
        assertThat(cart.getDistinctItemCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("Task 2: Mid-execution rollback when decrementStock returns false restores prior decremented items")
    void testAtomicRollbackWhenMidTransactionDecrementFails() {
        // Create 2 test items
        Item item1 = new Item(null, "MID-01", "Item 1", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 10, 2, LocalDateTime.now(), LocalDateTime.now());
        Item item2 = new Item(null, "MID-02", "Item 2", "General", BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 10, 2, LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(item1);
        itemRepo.save(item2);

        // Wrap ItemRepository so that decrementStock succeeds for MID-01 but intentionally returns false for MID-02
        ItemRepository faultInjectingRepo = new ItemRepository() {
            @Override
            public Optional<Item> findByCode(String code) {
                return itemRepo.findByCode(code);
            }

            @Override
            public Optional<Item> findByCode(Connection conn, String code) {
                return itemRepo.findByCode(conn, code);
            }

            @Override
            public Optional<Item> findById(long id) {
                return itemRepo.findById(id);
            }

            @Override
            public Optional<Item> findById(Connection conn, long id) {
                return itemRepo.findById(conn, id);
            }

            @Override
            public List<Item> findAll() {
                return itemRepo.findAll();
            }

            @Override
            public List<Item> findAll(Connection conn) {
                return itemRepo.findAll(conn);
            }

            @Override
            public List<Item> search(String query, String category) {
                return itemRepo.search(query, category);
            }

            @Override
            public List<Item> search(Connection conn, String query, String category) {
                return itemRepo.search(conn, query, category);
            }

            @Override
            public List<Item> findLowStock() {
                return itemRepo.findLowStock();
            }

            @Override
            public List<Item> findLowStock(Connection conn) {
                return itemRepo.findLowStock(conn);
            }

            @Override
            public void save(Item item) {
                itemRepo.save(item);
            }

            @Override
            public void save(Connection conn, Item item) {
                itemRepo.save(conn, item);
            }

            @Override
            public boolean decrementStock(Connection conn, String code, int quantity) {
                if ("MID-02".equals(code)) {
                    // Simulate decrement failure midway through step 2
                    return false;
                }
                return itemRepo.decrementStock(conn, code, quantity);
            }

            @Override
            public void incrementStock(Connection conn, String code, int quantity) {
                itemRepo.incrementStock(conn, code, quantity);
            }

            @Override
            public void updateSellingPrice(Connection conn, String code, BigDecimal newPrice) {
                itemRepo.updateSellingPrice(conn, code, newPrice);
            }
        };

        BillingService testBillingService = new BillingService(db, faultInjectingRepo, salesRepo);

        Cart cart = testBillingService.createCart();
        testBillingService.addItemToCart(cart, "MID-01", 3);
        testBillingService.addItemToCart(cart, "MID-02", 3);

        long txCountBefore = countAllTransactions();

        // Checkout should fail when attempting to decrement MID-02
        assertThatThrownBy(() -> testBillingService.checkout(cart, BigDecimal.valueOf(50.00), "Cashier-1"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("MID-02");

        // MID-01 was decremented in Step 2 before MID-02 failed.
        // SQLite ROLLBACK must have restored MID-01 back to its initial stock of 10!
        Item finalItem1 = itemRepo.findByCode("MID-01").orElseThrow();
        assertThat(finalItem1.currentStock())
                .as("MID-01 must roll back to 10 despite being decremented before the failure in the transaction")
                .isEqualTo(10);

        Item finalItem2 = itemRepo.findByCode("MID-02").orElseThrow();
        assertThat(finalItem2.currentStock()).isEqualTo(10);

        assertThat(countAllTransactions()).isEqualTo(txCountBefore);
    }

    // =========================================================================
    // TASK 3: PAYMENT SHORTFALL TESTS (ZERO ALTERATION OF DATABASE STATE)
    // =========================================================================

    @Test
    @DisplayName("Task 3: Payment shortfall throws PaymentShortfallException and leaves database completely unaltered")
    void testPaymentShortfallPreservesDatabaseState() {
        // Setup item with initial stock 25
        String code = "PAY-001";
        Item item = new Item(null, code, "Premium Olive Oil", "Pantry",
                BigDecimal.valueOf(10.00), BigDecimal.valueOf(15.50), 25, 5,
                LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(item);

        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, code, 2); // 2 * 15.50 = 31.00 total
        BigDecimal totalPayable = BigDecimal.valueOf(31.00);

        long initialTxCount = countAllTransactions();

        // 1. Exact 1 cent deficit ($30.99)
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(30.99), "Cashier-1"))
                .isInstanceOf(PaymentShortfallException.class)
                .satisfies(ex -> {
                    PaymentShortfallException pse = (PaymentShortfallException) ex;
                    assertThat(pse.getTotalPayable()).isEqualByComparingTo(totalPayable);
                    assertThat(pse.getAmountTendered()).isEqualByComparingTo(BigDecimal.valueOf(30.99));
                    assertThat(pse.getShortfall()).isEqualByComparingTo(BigDecimal.valueOf(0.01));
                });

        // 2. Zero tendered ($0.00)
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.ZERO, "Cashier-1"))
                .isInstanceOf(PaymentShortfallException.class)
                .satisfies(ex -> {
                    PaymentShortfallException pse = (PaymentShortfallException) ex;
                    assertThat(pse.getTotalPayable()).isEqualByComparingTo(totalPayable);
                    assertThat(pse.getAmountTendered()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(pse.getShortfall()).isEqualByComparingTo(totalPayable);
                });

        // 3. Negative tendered (-$10.00)
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(-10.00), "Cashier-1"))
                .isInstanceOf(PaymentShortfallException.class);

        // 4. Substantial deficit ($15.00 against $31.00)
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(15.00), "Cashier-1"))
                .isInstanceOf(PaymentShortfallException.class)
                .satisfies(ex -> {
                    PaymentShortfallException pse = (PaymentShortfallException) ex;
                    assertThat(pse.getShortfall()).isEqualByComparingTo(BigDecimal.valueOf(16.00));
                });

        // VERIFY ZERO DATABASE MUTATION
        Item unchangedItem = itemRepo.findByCode(code).orElseThrow();
        assertThat(unchangedItem.currentStock())
                .as("Item stock must remain exactly 25 after all payment shortfall rejections")
                .isEqualTo(25);

        assertThat(countAllTransactions())
                .as("Zero transactions must be recorded")
                .isEqualTo(initialTxCount);

        assertThat(countTotalUnitsSoldForCode(code))
                .as("Zero units must be recorded in sales_transaction_items")
                .isEqualTo(0);

        // Verify cart is NOT cleared on payment shortfall so customer can provide additional funds
        assertThat(cart.getDistinctItemCount()).isEqualTo(1);
        assertThat(cart.getTotalItemQuantity()).isEqualTo(2);

        // Once sufficient funds are tendered, checkout succeeds smoothly
        Receipt receipt = billingService.checkout(cart, BigDecimal.valueOf(35.00), "Cashier-1");
        assertThat(receipt).isNotNull();
        assertThat(receipt.changeDue()).isEqualByComparingTo(BigDecimal.valueOf(4.00));
        assertThat(itemRepo.findByCode(code).orElseThrow().currentStock()).isEqualTo(23);
        assertThat(cart.isEmpty()).isTrue();
    }

    // =========================================================================
    // TASK 4: CONCURRENT CHECKOUT VS RESTOCK & FILE-BASED PERSISTENCE
    // =========================================================================

    @Test
    @DisplayName("Task 4: Concurrent checkouts intertwined with concurrent restocks maintain mathematical conservation")
    void testConcurrentCheckoutsAndRestocksIntegrity() throws InterruptedException {
        String code = "INV-RACE-01";
        int initialStock = 10;
        Item item = new Item(null, code, "Energy Drink", "Beverages",
                BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.50), initialStock, 5,
                LocalDateTime.now(), LocalDateTime.now());
        itemRepo.save(item);

        int checkoutThreads = 20;
        int restockThreads = 10;
        int restockQtyPerThread = 4; // 10 * 4 = 40 restocked

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(checkoutThreads + restockThreads);

        AtomicInteger successfulSales = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // Submit checkout tasks (each buying 2 units)
        for (int i = 0; i < checkoutThreads; i++) {
            final int id = i;
            Cart cart = billingService.createCart();
            billingService.addItemToCart(cart, code, 2);
            executor.submit(() -> {
                try {
                    startGate.await();
                    billingService.checkout(cart, BigDecimal.valueOf(10.00), "Cashier-" + id);
                    successfulSales.incrementAndGet();
                } catch (InsufficientStockException expected) {
                    // Allowed when stock is momentarily depleted
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Submit restock tasks
        for (int i = 0; i < restockThreads; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    inventoryService.restockItem(code, restockQtyPerThread, "Batch-" + id, "Staff-" + id);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        // Mathematical conservation check:
        // finalStock = initialStock (10) + (10 * 4 restocked = 40) - (successfulSales * 2)
        int totalRestocked = restockThreads * restockQtyPerThread;
        int totalUnitsSold = countTotalUnitsSoldForCode(code);
        int expectedFinalStock = initialStock + totalRestocked - totalUnitsSold;

        Item finalItem = itemRepo.findByCode(code).orElseThrow();
        assertThat(finalItem.currentStock())
                .as("Final stock must strictly satisfy: initial + restocked - sold")
                .isEqualTo(expectedFinalStock);
        assertThat(finalItem.currentStock()).isGreaterThanOrEqualTo(0);

        // Verify restock audit entries
        List<RestockAudit> audits = inventoryService.getRestockAuditHistory(code);
        assertThat(audits).hasSize(restockThreads);
    }

    @Test
    @DisplayName("Task 4: High-concurrency checkout against file-based SQLite database with WAL mode")
    void testFileBasedSqliteConcurrentCheckoutStress() throws InterruptedException {
        File dbFile = new File(tempDir.toFile(), "file_concurrency_stress.db");
        try (DatabaseManager fileDb = DatabaseManager.forDatabase(dbFile.getAbsolutePath())) {
            MigrationRunner.migrate(fileDb);

            ItemRepository fileItemRepo = new SqliteItemRepository(fileDb);
            SalesTransactionRepository fileSalesRepo = new SqliteSalesTransactionRepository(fileDb);
            BillingService fileBillingService = new BillingService(fileDb, fileItemRepo, fileSalesRepo);

            String code = "FILE-CONC-01";
            int initialStock = 20;
            fileItemRepo.save(new Item(null, code, "Artisan Coffee", "Beverages",
                    BigDecimal.valueOf(4.00), BigDecimal.valueOf(8.00), initialStock, 5,
                    LocalDateTime.now(), LocalDateTime.now()));

            int threadCount = 40;
            ExecutorService executor = Executors.newFixedThreadPool(12);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger outOfStockCount = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                final int id = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        Cart cart = fileBillingService.createCart();
                        fileBillingService.addItemToCart(cart, code, 1);
                        fileBillingService.checkout(cart, BigDecimal.valueOf(20.00), "Cashier-" + id);
                        successCount.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        outOfStockCount.incrementAndGet();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneGate.await(20, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(errors).isEmpty();
            assertThat(successCount.get()).isEqualTo(initialStock);
            assertThat(outOfStockCount.get()).isEqualTo(threadCount - initialStock);

            Item finalItem = fileItemRepo.findByCode(code).orElseThrow();
            assertThat(finalItem.currentStock()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Task 4: High-concurrency mixed workload on WAL database (sales, restocks, price changes) maintains zero corruption")
    void testHighContentionFullWorkloadOnFileBasedWALDatabase() throws InterruptedException {
        File dbFile = new File(tempDir.toFile(), "full_workload_wal.db");
        try (DatabaseManager fileDb = DatabaseManager.forDatabase(dbFile.getAbsolutePath())) {
            MigrationRunner.migrate(fileDb);

            ItemRepository fileItemRepo = new SqliteItemRepository(fileDb);
            SalesTransactionRepository fileSalesRepo = new SqliteSalesTransactionRepository(fileDb);
            RestockAuditRepository fileRestockRepo = new SqliteRestockAuditRepository(fileDb);
            PriceHistoryRepository filePriceRepo = new SqlitePriceHistoryRepository(fileDb);

            BillingService fileBillingService = new BillingService(fileDb, fileItemRepo, fileSalesRepo);
            InventoryService fileInventoryService = new InventoryService(fileDb, fileItemRepo, fileRestockRepo);
            PriceService filePriceService = new PriceService(fileDb, fileItemRepo, filePriceRepo);

            String code = "WAL-MIX-01";
            int initialStock = 20;
            fileItemRepo.save(new Item(null, code, "Organic Honey", "Pantry",
                    BigDecimal.valueOf(5.00), BigDecimal.valueOf(10.00), initialStock, 5,
                    LocalDateTime.now(), LocalDateTime.now()));

            int checkoutThreads = 25; // wants 25 units total
            int restockThreads = 10;  // adds 2 units each = 20 units
            int priceThreads = 5;     // updates price
            int totalThreads = checkoutThreads + restockThreads + priceThreads;

            ExecutorService executor = Executors.newFixedThreadPool(16);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(totalThreads);

            AtomicInteger successfulSales = new AtomicInteger(0);
            AtomicInteger stockFailures = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            // Pre-create carts for checkout threads
            List<Cart> carts = new ArrayList<>();
            for (int i = 0; i < checkoutThreads; i++) {
                Cart cart = fileBillingService.createCart();
                fileBillingService.addItemToCart(cart, code, 1);
                carts.add(cart);
            }

            // Launch checkout tasks
            for (int i = 0; i < checkoutThreads; i++) {
                final int id = i;
                final Cart cart = carts.get(i);
                executor.submit(() -> {
                    try {
                        startGate.await();
                        fileBillingService.checkout(cart, BigDecimal.valueOf(20.00), "Cashier-" + id);
                        successfulSales.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        stockFailures.incrementAndGet();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            // Launch restock tasks
            for (int i = 0; i < restockThreads; i++) {
                final int id = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        fileInventoryService.restockItem(code, 2, "Batch-WAL-" + id, "Staff-" + id);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            // Launch price update tasks
            for (int i = 0; i < priceThreads; i++) {
                final int id = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        filePriceService.updateSellingPrice(code, BigDecimal.valueOf(10.00 + id), "Manager-" + id);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneGate.await(25, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            for (Throwable t : errors) {
                t.printStackTrace();
            }
            assertThat(errors).isEmpty();

            // Verify all checkout attempts either succeeded or were rejected due to momentary zero stock
            assertThat(successfulSales.get() + stockFailures.get()).isEqualTo(checkoutThreads);

            // Mathematical stock conservation invariant:
            // finalStock = initial (20) + restocked (20) - unitsSold
            int totalRestocked = restockThreads * 2;
            int totalUnitsSold = successfulSales.get();
            Item finalItem = fileItemRepo.findByCode(code).orElseThrow();
            assertThat(finalItem.currentStock())
                    .as("Stock conservation must hold: initial + restocked - sold == final")
                    .isEqualTo(initialStock + totalRestocked - totalUnitsSold);
            assertThat(finalItem.currentStock()).isGreaterThanOrEqualTo(0);

            // Price history has 5 entries
            assertThat(filePriceService.getPriceHistory(code)).hasSize(5);

            // Restock history has 10 entries
            assertThat(fileInventoryService.getRestockAuditHistory(code)).hasSize(10);
        }
    }

    @Test
    @DisplayName("Task 2: Zero stock item cannot be checked out and non-existent item triggers atomic rollback")
    void testZeroStockAndNonExistentItemRollback() {
        // Zero stock item
        String zeroCode = "ZERO-01";
        fileItemRepoSafeSave(new Item(null, zeroCode, "Zero Stock Widget", "Misc",
                BigDecimal.valueOf(1.00), BigDecimal.valueOf(2.00), 0, 5,
                LocalDateTime.now(), LocalDateTime.now()));

        Cart cartZero = billingService.createCart();
        // Trying to add item with 0 stock throws InsufficientStockException immediately
        assertThatThrownBy(() -> billingService.addItemToCart(cartZero, zeroCode, 1))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Available: 0");

        // Now test non-existent item in cart during checkout
        Cart cartWithGhost = billingService.createCart();
        // Add valid item
        billingService.addItemToCart(cartWithGhost, "DAIRY-001", 1);
        // Forcefully put a non-existent item into cart
        cartWithGhost.addItem(new CartItem("GHOST-999", "Phantom Item", BigDecimal.valueOf(10.00), 1));

        int milkStockBefore = itemRepo.findByCode("DAIRY-001").orElseThrow().currentStock();
        long txCountBefore = countAllTransactions();

        assertThatThrownBy(() -> billingService.checkout(cartWithGhost, BigDecimal.valueOf(2000.00), "Cashier-1"))
                .isInstanceOf(com.supermarket.exception.ItemNotFoundException.class)
                .hasMessageContaining("GHOST-999");

        // Verify full rollback: valid item stock was NOT decremented
        assertThat(itemRepo.findByCode("DAIRY-001").orElseThrow().currentStock()).isEqualTo(milkStockBefore);
        assertThat(countAllTransactions()).isEqualTo(txCountBefore);
    }

    private void fileItemRepoSafeSave(Item item) {
        itemRepo.save(item);
    }

    // =========================================================================
    // HELPER METHODS FOR DIRECT DATABASE VERIFICATION
    // =========================================================================

    private int countTotalUnitsSoldForCode(String code) {
        String sql = "SELECT COALESCE(SUM(quantity), 0) FROM sales_transaction_items WHERE item_code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    private long countAllTransactions() {
        String sql = "SELECT COUNT(*) FROM sales_transactions";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }
}
