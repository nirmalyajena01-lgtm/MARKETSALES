package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.domain.RestockAudit;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.service.InventoryService;
import com.supermarket.ui.controllers.RestockController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RestockControllerTest extends BaseJavaFxTest {

    private InventoryService inventoryService;
    private RestockController controller;

    @BeforeEach
    void setUpController() {
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqliteRestockAuditRepository restockRepo = new SqliteRestockAuditRepository(db);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/restock.fxml"));
                loader.setControllerFactory(type -> new RestockController(inventoryService));
                loader.load();
                controller = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load restock.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Initial load populates catalog items selector and audit log table")
    void testInitialDataLoad() {
        runOnFxThread(() -> {
            assertThat(controller.getCatalogItems()).isNotEmpty();
            assertThat(controller.getCmbItemSelect().getItems()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("Submitting valid restock increments stock and appends audit log record")
    void testSubmitValidRestock() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        controller.setOnRestockCompletedListener(() -> listenerCalled.set(true));

        runOnFxThread(() -> {
            Item item = controller.getCatalogItems().get(0);
            int prevStock = item.currentStock();

            controller.getCmbItemSelect().setValue(item);
            controller.getTxtQuantity().setText("25");
            controller.getTxtSupplierNotes().setText("Weekly delivery batch PO-1002");
            controller.getTxtOperator().setText("Staff-Alice");

            controller.handleSubmitRestock(new ActionEvent());

            // Check database updated
            Item updated = inventoryService.getItemByCode(item.code());
            assertThat(updated.currentStock()).isEqualTo(prevStock + 25);

            // Check audit log table contains new entry
            assertThat(controller.getAuditLogs()).isNotEmpty();
            RestockAudit latest = controller.getAuditLogs().get(controller.getAuditLogs().size() - 1);
            assertThat(latest.itemCode()).isEqualTo(item.code());
            assertThat(latest.quantityAdded()).isEqualTo(25);
            assertThat(latest.previousStock()).isEqualTo(prevStock);
            assertThat(latest.newStock()).isEqualTo(prevStock + 25);
            assertThat(latest.performedBy()).isEqualTo("Staff-Alice");
            assertThat(latest.batchNotes()).isEqualTo("Weekly delivery batch PO-1002");

            // Form inputs cleared
            assertThat(controller.getTxtQuantity().getText()).isEmpty();
            assertThat(controller.getTxtSupplierNotes().getText()).isEmpty();

            // Listener triggered
            assertThat(listenerCalled.get()).isTrue();
        });
    }

    @Test
    @DisplayName("Submitting restock with invalid or non-positive quantity fails validation")
    void testSubmitInvalidQuantityFails() {
        runOnFxThread(() -> {
            Item item = controller.getCatalogItems().get(0);
            int prevStock = item.currentStock();

            controller.getCmbItemSelect().setValue(item);
            controller.getTxtQuantity().setText("-5");

            controller.handleSubmitRestock(new ActionEvent());

            // Stock must not change
            Item notUpdated = inventoryService.getItemByCode(item.code());
            assertThat(notUpdated.currentStock()).isEqualTo(prevStock);
        });
    }
}
