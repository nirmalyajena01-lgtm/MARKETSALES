package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.service.InventoryService;
import com.supermarket.ui.controllers.InventoryController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryControllerTest extends BaseJavaFxTest {

    private InventoryService inventoryService;
    private InventoryController controller;

    @BeforeEach
    void setUpController() {
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqliteRestockAuditRepository restockRepo = new SqliteRestockAuditRepository(db);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/inventory.fxml"));
                loader.setControllerFactory(type -> new InventoryController(inventoryService));
                loader.load();
                controller = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load inventory.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Catalog loads initial seeded products correctly")
    void testInitialCatalogLoad() {
        runOnFxThread(() -> {
            assertThat(controller.getFullCatalog()).isNotEmpty();
            assertThat(controller.getDisplayedItems()).hasSameSizeAs(controller.getFullCatalog());

            // Check that categories dropdown has options
            assertThat(controller.getCmbCategory().getItems()).contains("All Categories", "Dairy", "Bakery");
        });
    }

    @Test
    @DisplayName("Live search field filters items by code or product name")
    void testSearchFiltering() {
        runOnFxThread(() -> {
            // Search by code
            controller.getTxtSearch().setText("DAIRY-001");
            assertThat(controller.getDisplayedItems()).hasSize(1);
            assertThat(controller.getDisplayedItems().get(0).name()).isEqualTo("Fresh Whole Milk 1L");

            // Search by name substring
            controller.getTxtSearch().setText("Bread");
            assertThat(controller.getDisplayedItems()).isNotEmpty();
            for (Item item : controller.getDisplayedItems()) {
                assertThat(item.name().toLowerCase()).contains("bread");
            }

            // Clear search restores all items
            controller.getTxtSearch().setText("");
            assertThat(controller.getDisplayedItems()).hasSameSizeAs(controller.getFullCatalog());
        });
    }

    @Test
    @DisplayName("Category dropdown filters products by category")
    void testCategoryDropdownFiltering() {
        runOnFxThread(() -> {
            controller.getCmbCategory().setValue("Bakery");
            assertThat(controller.getDisplayedItems()).isNotEmpty();
            for (Item item : controller.getDisplayedItems()) {
                assertThat(item.category()).isEqualTo("Bakery");
            }

            controller.getCmbCategory().setValue("All Categories");
            assertThat(controller.getDisplayedItems()).hasSameSizeAs(controller.getFullCatalog());
        });
    }

    @Test
    @DisplayName("Low stock toggle filters items at or below minimum threshold")
    void testLowStockFilterToggle() {
        runOnFxThread(() -> {
            controller.getChkLowStockOnly().setSelected(true);
            for (Item item : controller.getDisplayedItems()) {
                assertThat(item.currentStock()).isLessThanOrEqualTo(item.minStockThreshold());
            }

            controller.getChkLowStockOnly().setSelected(false);
            assertThat(controller.getDisplayedItems()).hasSameSizeAs(controller.getFullCatalog());
        });
    }

    @Test
    @DisplayName("Refresh button updates catalog from database")
    void testRefreshCatalog() {
        runOnFxThread(() -> {
            int beforeCount = controller.getFullCatalog().size();
            controller.handleRefresh(new ActionEvent());
            assertThat(controller.getFullCatalog()).hasSize(beforeCount);
        });
    }
}
