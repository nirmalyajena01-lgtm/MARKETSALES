package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.service.InventoryService;
import com.supermarket.service.PriceService;
import com.supermarket.ui.controllers.PriceController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PriceControllerTest extends BaseJavaFxTest {

    private PriceService priceService;
    private InventoryService inventoryService;
    private PriceController controller;

    @BeforeEach
    void setUpController() {
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqlitePriceHistoryRepository priceRepo = new SqlitePriceHistoryRepository(db);
        SqliteRestockAuditRepository restockRepo = new SqliteRestockAuditRepository(db);

        priceService = new PriceService(db, itemRepo, priceRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/price.fxml"));
                loader.setControllerFactory(type -> new PriceController(priceService, inventoryService));
                loader.load();
                controller = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load price.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Catalog items are loaded into price management table")
    void testInitialCatalogLoad() {
        runOnFxThread(() -> {
            assertThat(controller.getDisplayedCatalog()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("Selecting an item updates editor labels and computes current margin")
    void testSelectItemUpdatesEditor() {
        runOnFxThread(() -> {
            Item item = controller.getDisplayedCatalog().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();

            controller.selectItem(item);
            assertThat(controller.getSelectedItem()).isNotNull();
            assertThat(controller.getSelectedItem().code()).isEqualTo("DAIRY-001");

            // Type a profitable price: cost is ₹99.60, proposed ₹199.20 -> 50% margin
            controller.getTxtNewPrice().setText("199.20");
            assertThat(controller.getLblProposedMargin().getText()).isEqualTo("50.00%");
            assertThat(controller.getLblPriceWarning().getText()).contains("Healthy profit margin");

            // Type a below-cost price: ₹99.00 < ₹99.60
            controller.getTxtNewPrice().setText("99.00");
            assertThat(controller.getLblPriceWarning().getText()).contains("BELOW cost price");
        });
    }

    @Test
    @DisplayName("Submitting price update modifies catalog and records audit log")
    void testSubmitPriceUpdate() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        controller.setOnPriceUpdatedListener(() -> listenerCalled.set(true));

        runOnFxThread(() -> {
            Item item = controller.getDisplayedCatalog().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();

            BigDecimal oldSellingPrice = item.currentSellingPrice(); // 1.89

            controller.selectItem(item);
            controller.getTxtNewPrice().setText("2.99");
            controller.getTxtManagerName().setText("Manager-Bob");
            controller.handleUpdatePrice(new ActionEvent());

            // Check database has new price
            Item updated = inventoryService.getItemByCode("DAIRY-001");
            assertThat(updated.currentSellingPrice()).isEqualByComparingTo("2.99");

            // Check price history has new log
            assertThat(controller.getPriceHistories()).isNotEmpty();
            PriceHistory latest = controller.getPriceHistories().get(controller.getPriceHistories().size() - 1);
            assertThat(latest.itemCode()).isEqualTo("DAIRY-001");
            assertThat(latest.oldPrice()).isEqualByComparingTo(oldSellingPrice);
            assertThat(latest.newPrice()).isEqualByComparingTo("2.99");
            assertThat(latest.changedBy()).isEqualTo("Manager-Bob");

            // Listener called
            assertThat(listenerCalled.get()).isTrue();
        });
    }
}
