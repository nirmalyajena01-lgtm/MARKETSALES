package com.supermarket.service;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Cart;
import com.supermarket.domain.Item;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PriceSnapshotInvarianceServiceTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private AnalyticsRepository analyticsRepo;
    private PriceHistoryRepository priceRepo;

    private BillingService billingService;
    private PriceService priceService;
    private InventoryService inventoryService;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);

        billingService = new BillingService(db, itemRepo, salesRepo);
        priceService = new PriceService(db, itemRepo, priceRepo);
        inventoryService = new InventoryService(db);
        analyticsService = new AnalyticsService(analyticsRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Historical sales revenue and profit remain 100% invariant when catalog selling price and cost price are changed in the future")
    void testPriceSnapshotInvarianceAfterCatalogPriceUpdates() {
        // Initial state of BEV-002 (Dark Roast Coffee Beans 500g):
        // Cost: ₹415.00, Selling: ₹746.17, Stock: 18
        Item initialCoffee = itemRepo.findByCode("BEV-002").orElseThrow();
        assertThat(initialCoffee.costPrice()).isEqualByComparingTo(BigDecimal.valueOf(415.00));
        assertThat(initialCoffee.currentSellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(746.17));

        // Step 1: Customer buys 10 bags of coffee beans
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "BEV-002", 10);
        // Subtotal = ₹7,461.70, cost = ₹4,150.00, profit = ₹3,311.70.
        billingService.checkout(cart, BigDecimal.valueOf(8000.00), "Cashier-1");

        LocalDate today = LocalDate.now();

        // Step 2: Verify immediate analytics report
        AnalyticsReport initialReport = analyticsService.generateDailyReport(today);
        assertThat(initialReport.totalItemsSold()).isEqualTo(10);
        assertThat(initialReport.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(7461.70));
        assertThat(initialReport.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(4150.00));
        assertThat(initialReport.totalNetProfit()).isEqualByComparingTo(BigDecimal.valueOf(3311.70));

        SalesStatistic statBefore = initialReport.itemStatistics().stream()
                .filter(s -> s.itemCode().equals("BEV-002"))
                .findFirst()
                .orElseThrow();
        assertThat(statBefore.totalQuantitySold()).isEqualTo(10);
        assertThat(statBefore.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(7461.70));
        assertThat(statBefore.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(4150.00));
        assertThat(statBefore.netProfit()).isEqualByComparingTo(BigDecimal.valueOf(3311.70));

        // Step 3: Manager raises the price to ₹1,327.17.
        priceService.updateSellingPrice("BEV-002", BigDecimal.valueOf(1327.17), "StoreManager");

        // Supplier also increases wholesale cost price from $5.00 to $11.00
        Item updatedInCatalog = itemRepo.findByCode("BEV-002").orElseThrow();
        Item repricedCatalog = new Item(
                updatedInCatalog.id(),
                updatedInCatalog.code(),
                updatedInCatalog.name(),
                updatedInCatalog.category(),
                BigDecimal.valueOf(913.00), // New Cost Price
                BigDecimal.valueOf(1327.17), // New Selling Price
                updatedInCatalog.currentStock(),
                updatedInCatalog.minStockThreshold(),
                updatedInCatalog.createdAt(),
                updatedInCatalog.updatedAt()
        );
        inventoryService.saveItem(repricedCatalog);

        // Confirm catalog indeed holds the new prices
        Item catalogNow = itemRepo.findByCode("BEV-002").orElseThrow();
        assertThat(catalogNow.costPrice()).isEqualByComparingTo(BigDecimal.valueOf(913.00));
        assertThat(catalogNow.currentSellingPrice()).isEqualByComparingTo(BigDecimal.valueOf(1327.17));

        // Step 4: Re-query analytics report for today
        // INVARIANCE GUARANTEE: The past report must NOT compute 10 * 15.99 or 10 * 11.00!
        AnalyticsReport postChangeReport = analyticsService.generateDailyReport(today);

        assertThat(postChangeReport.totalItemsSold())
                .as("Total items sold must remain 10")
                .isEqualTo(10);

        assertThat(postChangeReport.totalRevenue())
                .as("Historical revenue must remain unchanged after repricing")
                .isEqualByComparingTo(BigDecimal.valueOf(7461.70));

        assertThat(postChangeReport.totalCost())
                .as("Historical cost must remain unchanged after repricing")
                .isEqualByComparingTo(BigDecimal.valueOf(4150.00));

        assertThat(postChangeReport.totalNetProfit())
                .as("Historical profit must remain unchanged after repricing")
                .isEqualByComparingTo(BigDecimal.valueOf(3311.70));

        SalesStatistic statAfter = postChangeReport.itemStatistics().stream()
                .filter(s -> s.itemCode().equals("BEV-002"))
                .findFirst()
                .orElseThrow();
        assertThat(statAfter.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(7461.70));
        assertThat(statAfter.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(4150.00));
        assertThat(statAfter.netProfit()).isEqualByComparingTo(BigDecimal.valueOf(3311.70));

        // Step 5: Perform a NEW sale after price change
        // The new catalog price and cost are ₹1,327.17 and ₹913.00 per bag.
        Cart cart2 = billingService.createCart();
        billingService.addItemToCart(cart2, "BEV-002", 2);
        billingService.checkout(cart2, BigDecimal.valueOf(3000.00), "Cashier-2");

        AnalyticsReport reportWithBothSales = analyticsService.generateDailyReport(today);
        // Total sold = 10 + 2 = 12
        // The second sale adds ₹2,654.34 revenue and ₹1,826.00 cost.
        assertThat(reportWithBothSales.totalItemsSold()).isEqualTo(12);
        assertThat(reportWithBothSales.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(10116.04));
        assertThat(reportWithBothSales.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(5976.00));
        assertThat(reportWithBothSales.totalNetProfit()).isEqualByComparingTo(BigDecimal.valueOf(4140.04));
    }
}
