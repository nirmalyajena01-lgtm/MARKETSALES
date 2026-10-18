package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.BillingService;
import com.supermarket.service.InventoryService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.controllers.PosController;
import com.supermarket.ui.models.CartItemRow;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PosControllerTest extends BaseJavaFxTest {

    private BillingService billingService;
    private InventoryService inventoryService;
    private ReceiptFormatter receiptFormatter;
    private PosController controller;

    @BeforeEach
    void setUpController() {
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqliteSalesTransactionRepository salesRepo = new SqliteSalesTransactionRepository(db);
        SqliteRestockAuditRepository restockRepo = new SqliteRestockAuditRepository(db);

        billingService = new BillingService(db, itemRepo, salesRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
        receiptFormatter = new ReceiptFormatter();

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pos.fxml"));
                loader.setControllerFactory(type -> new PosController(billingService, inventoryService, receiptFormatter));
                loader.load();
                controller = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load pos.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Scanning item code adds it to cart table with correct pricing and subtotal")
    void testScanBarcodeAddsItemToCart() {
        runOnFxThread(() -> {
            // DAIRY-001: Whole Milk 1L, selling price ₹156.87, stock 50
            controller.getTxtBarcode().setText("DAIRY-001");
            controller.getSpnQuantity().getValueFactory().setValue(2);
            controller.handleAddToCart(new ActionEvent());

            assertThat(controller.getCartRows()).hasSize(1);
            CartItemRow row = controller.getCartRows().get(0);
            assertThat(row.getItemCode()).isEqualTo("DAIRY-001");
            assertThat(row.getItemName()).isEqualTo("Fresh Whole Milk 1L");
            assertThat(row.getCategory()).isEqualTo("Dairy");
            assertThat(row.getQuantity()).isEqualTo(2);
            assertThat(row.getUnitPrice()).isEqualByComparingTo("156.87");
            assertThat(row.getSubtotal()).isEqualByComparingTo("313.74");

            assertThat(controller.getLblTotalPayable().getText()).isEqualTo("₹313.74");
            assertThat(controller.getTxtBarcode().getText()).isEmpty(); // Cleared for next scan
        });
    }

    @Test
    @DisplayName("Scanning existing item in cart aggregates quantity correctly")
    void testScanExistingItemAggregatesQuantity() {
        runOnFxThread(() -> {
            controller.getTxtBarcode().setText("DAIRY-001");
            controller.getSpnQuantity().getValueFactory().setValue(1);
            controller.handleAddToCart(new ActionEvent());

            controller.getTxtBarcode().setText("DAIRY-001");
            controller.getSpnQuantity().getValueFactory().setValue(2);
            controller.handleAddToCart(new ActionEvent());

            assertThat(controller.getCartRows()).hasSize(1);
            CartItemRow row = controller.getCartRows().get(0);
            assertThat(row.getQuantity()).isEqualTo(3);
            assertThat(row.getSubtotal()).isEqualByComparingTo("470.61");
            assertThat(controller.getLblTotalPayable().getText()).isEqualTo("₹470.61");
        });
    }

    @Test
    @DisplayName("Quantity buttons increment and decrement items in cart")
    void testQuantityIncrementAndDecrementButtons() {
        runOnFxThread(() -> {
            controller.getTxtBarcode().setText("BAKE-001");
            controller.getSpnQuantity().getValueFactory().setValue(3);
            controller.handleAddToCart(new ActionEvent());

            CartItemRow row = controller.getCartRows().get(0);
            assertThat(row.getQuantity()).isEqualTo(3);

            // Increment
            controller.handleIncrementQuantity(row);
            assertThat(controller.getCartRows().get(0).getQuantity()).isEqualTo(4);

            // Decrement
            controller.handleDecrementQuantity(controller.getCartRows().get(0));
            assertThat(controller.getCartRows().get(0).getQuantity()).isEqualTo(3);

            // Delete item
            controller.handleDeleteCartItem(row);
            assertThat(controller.getCartRows()).isEmpty();
            assertThat(controller.getCart().isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("Cash tendered calculates change and toggles checkout button validation")
    void testCashTenderedValidationAndChangeCalculation() {
        runOnFxThread(() -> {
            controller.getTxtBarcode().setText("DAIRY-001");
            controller.getSpnQuantity().getValueFactory().setValue(2);
            controller.handleAddToCart(new ActionEvent());

            // Underpaid: Tendered ₹300.00 < ₹313.74
            controller.getTxtCashTendered().setText("300.00");
            assertThat(controller.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(controller.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Overpaid: Tendered ₹500.00 > ₹313.74
            controller.getTxtCashTendered().setText("500.00");
            assertThat(controller.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(controller.getLblChangeDue().getText()).isEqualTo("₹186.26");

            // Exact payment: Tendered ₹313.74
            controller.getTxtCashTendered().setText("313.74");
            assertThat(controller.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(controller.getLblChangeDue().getText()).isEqualTo("₹0.00");
        });
    }

    @Test
    @DisplayName("Completing a sale atomically decrements stock in SQLite and produces Receipt")
    void testCompleteSaleAtomicallyDecrementsStock() {
        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        controller.setOnSaleCompletedListener(() -> listenerNotified.set(true));

        runOnFxThread(() -> {
            Item beforeItem = inventoryService.getItemByCode("DAIRY-001");
            int initialStock = beforeItem.currentStock(); // 50

            controller.getTxtBarcode().setText("DAIRY-001");
            controller.getSpnQuantity().getValueFactory().setValue(5);
            controller.handleAddToCart(new ActionEvent());

            controller.getTxtCashTendered().setText("1000.00");
            controller.handleCompleteSale(new ActionEvent());

            // Cart must be cleared after checkout
            assertThat(controller.getCartRows()).isEmpty();
            assertThat(controller.getCart().isEmpty()).isTrue();

            // Receipt must be produced
            Receipt receipt = controller.getLastCompletedReceipt();
            assertThat(receipt).isNotNull();
            assertThat(receipt.serialNumber()).startsWith("SAS-");
            assertThat(receipt.totalAmount()).isEqualByComparingTo("784.35");
            assertThat(receipt.amountTendered()).isEqualByComparingTo("1000.00");
            assertThat(receipt.changeDue()).isEqualByComparingTo("215.65");
            assertThat(receipt.lineItems()).hasSize(1);
            assertThat(receipt.lineItems().get(0).unitSellingPriceSnapshot()).isEqualByComparingTo("156.87");

            // Verify live stock decremented in persistence layer
            Item afterItem = inventoryService.getItemByCode("DAIRY-001");
            assertThat(afterItem.currentStock()).isEqualTo(initialStock - 5);

            // Listener was called
            assertThat(listenerNotified.get()).isTrue();
        });
    }

    @Test
    @DisplayName("Clear cart button resets active cart, table rows, and payment fields")
    void testClearCartButton() {
        runOnFxThread(() -> {
            controller.getTxtBarcode().setText("DAIRY-001");
            controller.handleAddToCart(new ActionEvent());
            controller.getTxtCashTendered().setText("5.00");

            assertThat(controller.getCartRows()).hasSize(1);

            controller.handleClearCart(new ActionEvent());
            assertThat(controller.getCartRows()).isEmpty();
            assertThat(controller.getCart().isEmpty()).isTrue();
            assertThat(controller.getLblTotalPayable().getText()).isEqualTo("₹0.00");
            assertThat(controller.getTxtCashTendered().getText()).isEmpty();
        });
    }
}
