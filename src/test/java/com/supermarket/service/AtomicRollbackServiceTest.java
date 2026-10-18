package com.supermarket.service;

import com.supermarket.domain.Cart;
import com.supermarket.domain.Item;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicRollbackServiceTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        billingService = new BillingService(db, itemRepo, salesRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("If 1 of 5 items has insufficient stock during checkout, entire transaction aborts with zero inventory decremented")
    void testAtomicRollbackWhenOneOfFiveItemsFails() {
        // Initial setup of 5 distinct items
        // 1. PROD-001 (Organic Bananas) stock = 65
        // 2. PROD-002 (Apples) stock = 45
        // 3. DAIRY-001 (Milk) stock = 50
        // 4. BAKE-001 (Bread) stock = 40
        // 5. BEV-002 (Coffee Beans) initial stock = 18

        int initialBananas = itemRepo.findByCode("PROD-001").orElseThrow().currentStock();
        int initialApples = itemRepo.findByCode("PROD-002").orElseThrow().currentStock();
        int initialMilk = itemRepo.findByCode("DAIRY-001").orElseThrow().currentStock();
        int initialBread = itemRepo.findByCode("BAKE-001").orElseThrow().currentStock();

        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "PROD-001", 5); // requests 5
        billingService.addItemToCart(cart, "PROD-002", 4); // requests 4
        billingService.addItemToCart(cart, "DAIRY-001", 3); // requests 3
        billingService.addItemToCart(cart, "BAKE-001", 2);  // requests 2
        billingService.addItemToCart(cart, "BEV-002", 5);  // requests 5 (valid initially, 5 <= 18)

        assertThat(cart.getDistinctItemCount()).isEqualTo(5);

        // Simulate concurrent change in database right before checkout:
        // Another sales lane or inventory adjustment reduces BEV-002 stock from 18 down to 2!
        Item modifiedCoffee = itemRepo.findByCode("BEV-002").orElseThrow().withStock(2);
        itemRepo.save(modifiedCoffee);

        assertThat(itemRepo.findByCode("BEV-002").orElseThrow().currentStock()).isEqualTo(2);

        // Attempt checkout: cart has requested 5 coffee, but DB only has 2
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(10000.00), "Cashier-1"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("BEV-002")
                .hasMessageContaining("Requested: 5")
                .hasMessageContaining("Available: 2");

        // CRITICAL INTEGRITY CHECK: Full atomic rollback verification
        // None of the other 4 items must be decremented!
        assertThat(itemRepo.findByCode("PROD-001").orElseThrow().currentStock())
                .as("PROD-001 stock must remain untouched")
                .isEqualTo(initialBananas);

        assertThat(itemRepo.findByCode("PROD-002").orElseThrow().currentStock())
                .as("PROD-002 stock must remain untouched")
                .isEqualTo(initialApples);

        assertThat(itemRepo.findByCode("DAIRY-001").orElseThrow().currentStock())
                .as("DAIRY-001 stock must remain untouched")
                .isEqualTo(initialMilk);

        assertThat(itemRepo.findByCode("BAKE-001").orElseThrow().currentStock())
                .as("BAKE-001 stock must remain untouched")
                .isEqualTo(initialBread);

        assertThat(itemRepo.findByCode("BEV-002").orElseThrow().currentStock())
                .as("BEV-002 stock must remain at 2")
                .isEqualTo(2);

        // Verify zero transactions were committed to the database
        assertThat(salesRepo.findByDateRange(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)))
                .isEmpty();

        // Cart items must remain intact so clerk can adjust quantity rather than rescanning everything
        assertThat(cart.getDistinctItemCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Rollback when last item out of multiple has zero remaining stock")
    void testAtomicRollbackWhenLastItemCompletelyOutOfStock() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "DAIRY-002", 2); // Eggs (stock 30)
        billingService.addItemToCart(cart, "HOUS-001", 1);  // Dishwashing liquid (stock 30)

        // Force HOUS-001 stock to 0 in database
        Item outOfStockItem = itemRepo.findByCode("HOUS-001").orElseThrow().withStock(0);
        itemRepo.save(outOfStockItem);

        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(1000.00), "Cashier-2"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("HOUS-001");

        // Eggs stock must remain exactly 30
        assertThat(itemRepo.findByCode("DAIRY-002").orElseThrow().currentStock()).isEqualTo(30);
        // Zero transactions recorded
        assertThat(salesRepo.findByDateRange(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)))
                .isEmpty();
    }
}
