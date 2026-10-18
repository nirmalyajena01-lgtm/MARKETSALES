package com.supermarket.service;

import com.supermarket.domain.Item;
import com.supermarket.domain.RestockAudit;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.RestockAuditRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryServiceTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private RestockAuditRepository restockRepo;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Query all items returns full initial catalog")
    void testGetAllItems() {
        List<Item> allItems = inventoryService.getAllItems();
        assertThat(allItems).hasSize(27);
    }

    @Test
    @DisplayName("Search items by keyword and category filters correctly")
    void testSearchItems() {
        // Search by name keyword
        List<Item> milkItems = inventoryService.searchItems("Milk", "All");
        assertThat(milkItems).isNotEmpty();
        assertThat(milkItems).allMatch(i -> i.name().toLowerCase().contains("milk"));

        // Search by category
        List<Item> dairyItems = inventoryService.searchItems("", "Dairy");
        assertThat(dairyItems).hasSize(5);
        assertThat(dairyItems).allMatch(i -> i.category().equals("Dairy"));

        // Search by code and category
        List<Item> prod001 = inventoryService.searchItems("PROD-001", "Produce");
        assertThat(prod001).hasSize(1);
        assertThat(prod001.get(0).name()).isEqualTo("Organic Bananas 1kg");

        // No matches
        List<Item> noMatches = inventoryService.searchItems("NON_EXISTENT_XYZ", "Produce");
        assertThat(noMatches).isEmpty();
    }

    @Test
    @DisplayName("Get item by code returns item or throws ItemNotFoundException")
    void testGetItemByCode() {
        Item item = inventoryService.getItemByCode("DAIRY-001");
        assertThat(item).isNotNull();
        assertThat(item.code()).isEqualTo("DAIRY-001");
        assertThat(item.name()).isEqualTo("Fresh Whole Milk 1L");

        assertThatThrownBy(() -> inventoryService.getItemByCode("INVALID-999"))
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessageContaining("INVALID-999");

        assertThatThrownBy(() -> inventoryService.getItemByCode("   "))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Low stock filter returns only items at or below threshold")
    void testLowStockFilter() {
        // Initially, no seed items are at or below threshold
        List<Item> lowStockInitial = inventoryService.getLowStockItems();
        assertThat(lowStockInitial).isEmpty();

        // Reduce an item's stock below its threshold: BAKE-003 threshold is 5
        Item croissants = inventoryService.getItemByCode("BAKE-003");
        Item lowCroissants = croissants.withStock(3);
        inventoryService.saveItem(lowCroissants);

        List<Item> lowStockUpdated = inventoryService.getLowStockItems();
        assertThat(lowStockUpdated).hasSize(1);
        assertThat(lowStockUpdated.get(0).code()).isEqualTo("BAKE-003");
        assertThat(lowStockUpdated.get(0).currentStock()).isEqualTo(3);
        assertThat(lowStockUpdated.get(0).isLowStock()).isTrue();
    }

    @Test
    @DisplayName("Employee restock increments stock and registers immutable audit trail")
    void testRestockItemWorkflow() {
        Item milkBefore = inventoryService.getItemByCode("DAIRY-001");
        int stockBefore = milkBefore.currentStock(); // 50

        // Perform restock of +25 units
        inventoryService.restockItem("DAIRY-001", 25, "Fresh shipment batch #B-20261002", "WarehouseClerk");

        // Verify stock incremented
        Item milkAfter = inventoryService.getItemByCode("DAIRY-001");
        assertThat(milkAfter.currentStock()).isEqualTo(stockBefore + 25);

        // Verify audit log
        List<RestockAudit> auditLogs = inventoryService.getRestockAuditHistory("DAIRY-001");
        assertThat(auditLogs).hasSize(1);

        RestockAudit log = auditLogs.get(0);
        assertThat(log.itemCode()).isEqualTo("DAIRY-001");
        assertThat(log.quantityAdded()).isEqualTo(25);
        assertThat(log.previousStock()).isEqualTo(stockBefore);
        assertThat(log.newStock()).isEqualTo(stockBefore + 25);
        assertThat(log.batchNotes()).isEqualTo("Fresh shipment batch #B-20261002");
        assertThat(log.performedBy()).isEqualTo("WarehouseClerk");
        assertThat(log.timestamp()).isNotNull();

        // Verify full history
        List<RestockAudit> allLogs = inventoryService.getRestockAuditHistory();
        assertThat(allLogs).hasSize(1);
    }

    @Test
    @DisplayName("Restock validations reject zero or negative quantities")
    void testRestockValidations() {
        assertThatThrownBy(() -> inventoryService.restockItem("DAIRY-001", 0, "batch", "clerk"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> inventoryService.restockItem("DAIRY-001", -5, "batch", "clerk"))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> inventoryService.restockItem("UNKNOWN-CODE", 10, "batch", "clerk"))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @DisplayName("Saving new item adds to catalog and enforces validations")
    void testSaveNewItem() {
        Item newItem = new Item(
                null,
                "SNACK-001",
                "Dark Chocolate Bar 100g",
                "Snacks",
                BigDecimal.valueOf(1.20),
                BigDecimal.valueOf(2.49),
                50,
                10,
                null,
                null
        );

        inventoryService.saveItem(newItem);

        Item saved = inventoryService.getItemByCode("SNACK-001");
        assertThat(saved.name()).isEqualTo("Dark Chocolate Bar 100g");
        assertThat(saved.category()).isEqualTo("Snacks");
        assertThat(saved.currentStock()).isEqualTo(50);

        // Validate negative stock rejection
        Item invalidStock = new Item(
                null, "BAD-001", "Bad Item", "Snacks",
                BigDecimal.valueOf(1.0), BigDecimal.valueOf(2.0), -1, 5, null, null
        );
        assertThatThrownBy(() -> inventoryService.saveItem(invalidStock))
                .isInstanceOf(ValidationException.class);
    }
}
