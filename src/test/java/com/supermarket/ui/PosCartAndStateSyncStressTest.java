package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.ui.controllers.AnalyticsController;
import com.supermarket.ui.controllers.InventoryController;
import com.supermarket.ui.controllers.MainController;
import com.supermarket.ui.controllers.PosController;
import com.supermarket.ui.controllers.PriceController;
import com.supermarket.ui.controllers.RestockController;
import com.supermarket.ui.models.CartItemRow;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical stress tests and adversarial edge case verification for:
 * 1. POS Controller cart interactions: adding items, barcode Enter key simulation,
 *    quantity updates, delete/clear actions, and tender calculations under boundary inputs
 *    (tendered = 0, tendered < total, fractional tender, malformed formats).
 * 2. Inter-tab state synchronization: verifying that sales completed in POS immediately
 *    synchronize decremented stock in the Inventory tab and updated KPIs/itemized stats
 *    in the Analytics tab, and that Restock and Price updates reflect across views.
 */
class PosCartAndStateSyncStressTest extends BaseJavaFxTest {

    private MainController mainController;
    private PosController posController;
    private InventoryController inventoryController;
    private AnalyticsController analyticsController;
    private RestockController restockController;
    private PriceController priceController;

    @BeforeEach
    @Override
    public void setUpDatabase() {
        // Enforce shared in-memory database instance so all FXML sub-controllers share SQLite state
        db = DatabaseManager.initialize(":memory:");
        MigrationRunner.migrate(db);

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
                loader.load();
                mainController = loader.getController();

                posController = mainController.getPosTabController();
                inventoryController = mainController.getInventoryTabController();
                analyticsController = mainController.getAnalyticsTabController();
                restockController = mainController.getRestockTabController();
                priceController = mainController.getPriceTabController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load main.fxml hierarchy", e);
            }
        });
    }

    @AfterEach
    @Override
    public void tearDownDatabase() {
        if (db != null) {
            db.close();
        }
    }

    // =========================================================================
    // 1. POS Cart Interactions & Barcode Simulation
    // =========================================================================

    @Test
    @DisplayName("Simulating Enter key on barcode field adds item to cart and clears input field")
    void testBarcodeEnterKeySimulation_SuccessAndEmpty() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(1);
            KeyEvent enterEvent = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
            posController.getTxtBarcode().fireEvent(enterEvent);

            assertThat(posController.getCartRows()).hasSize(1);
            CartItemRow row = posController.getCartRows().get(0);
            assertThat(row.getItemCode()).isEqualTo("DAIRY-001");
            assertThat(row.getItemName()).isEqualTo("Fresh Whole Milk 1L");
            assertThat(row.getQuantity()).isEqualTo(1);
            assertThat(row.getUnitPrice()).isEqualByComparingTo("156.87");
            assertThat(row.getSubtotal()).isEqualByComparingTo("156.87");
            assertThat(posController.getTxtBarcode().getText()).isEmpty();

            posController.getTxtBarcode().setText("");
            posController.getTxtBarcode().fireEvent(enterEvent);
            assertThat(posController.getCartRows()).hasSize(1);
        });
    }

    @Test
    @DisplayName("Product lookup ComboBox selection binds code and allows adding to active cart")
    void testItemLookupComboBox_SelectionAndAdd() {
        runOnFxThread(() -> {
            Optional<Item> itemOpt = inventoryController.getFullCatalog().stream()
                    .filter(i -> "PROD-001".equals(i.code()))
                    .findFirst();
            assertThat(itemOpt).isPresent();
            Item bananas = itemOpt.get();

            // Select item in ComboBox
            posController.refreshProductLookup();
            posController.getSpnQuantity().getValueFactory().setValue(3);
            posController.getTxtBarcode().setText(bananas.code());
            posController.handleAddToCart(new ActionEvent());

            assertThat(posController.getCartRows()).hasSize(1);
            CartItemRow row = posController.getCartRows().get(0);
            assertThat(row.getItemCode()).isEqualTo("PROD-001");
            assertThat(row.getQuantity()).isEqualTo(3);
            assertThat(row.getUnitPrice()).isEqualByComparingTo("140.27");
            assertThat(row.getSubtotal()).isEqualByComparingTo("420.81");
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹420.81");
        });
    }

    @Test
    @DisplayName("Quantity spinner and repeated scans consolidate quantities and recalculate subtotals")
    void testQuantityUpdates_SpinnerAndConsolidatedScans() {
        runOnFxThread(() -> {
            // First scan with quantity 4 (PROD-002: Granny Smith Apples 1kg, price 2.99)
            posController.getTxtBarcode().setText("PROD-002");
            posController.getSpnQuantity().getValueFactory().setValue(4);
            posController.handleAddToCart(new ActionEvent());

            // Second scan of same product with quantity 3
            posController.getTxtBarcode().setText("PROD-002");
            posController.getSpnQuantity().getValueFactory().setValue(3);
            posController.handleAddToCart(new ActionEvent());

            assertThat(posController.getCartRows()).hasSize(1);
            CartItemRow row = posController.getCartRows().get(0);
            assertThat(row.getQuantity()).isEqualTo(7); // 4 + 3
            assertThat(row.getSubtotal()).isEqualByComparingTo("1737.19");
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹1737.19");
        });
    }

    @Test
    @DisplayName("Action column buttons: increment, decrement, auto-remove at zero, and delete")
    void testActionColumnControls_IncrementDecrementDelete() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("BAKE-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            CartItemRow row = posController.getCartRows().get(0);
            assertThat(row.getQuantity()).isEqualTo(2);

            // Increment
            posController.handleIncrementQuantity(row);
            assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(3);
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹560.25");

            // Decrement
            posController.handleDecrementQuantity(posController.getCartRows().get(0));
            assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(2);
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹373.50");

            // Decrement again to 1
            posController.handleDecrementQuantity(posController.getCartRows().get(0));
            assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(1);
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹186.75");

            // Decrement from 1 to 0: auto-removes item from cart
            posController.handleDecrementQuantity(posController.getCartRows().get(0));
            assertThat(posController.getCartRows()).isEmpty();
            assertThat(posController.getCart().isEmpty()).isTrue();
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹0.00");

            // Add new item and delete explicitly
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(1);
            posController.handleAddToCart(new ActionEvent());
            assertThat(posController.getCartRows()).hasSize(1);

            posController.handleDeleteCartItem(posController.getCartRows().get(0));
            assertThat(posController.getCartRows()).isEmpty();
            assertThat(posController.getCart().isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("Stock boundary checks prevent adding more items than available in inventory")
    void testExceedingStockBoundary_HandledGracefully() {
        runOnFxThread(() -> {
            // DAIRY-001 initial stock is 50
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(51); // 51 > 50
            posController.handleAddToCart(new ActionEvent());

            // Cart must remain empty
            assertThat(posController.getCartRows()).isEmpty();
            assertThat(posController.getCart().isEmpty()).isTrue();

            // Add exact stock (50) -> success
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(50);
            posController.handleAddToCart(new ActionEvent());
            assertThat(posController.getCartRows()).hasSize(1);
            assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(50);

            // Increment attempt when already at max stock: must be rejected
            posController.handleIncrementQuantity(posController.getCartRows().get(0));
            assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(50); // Unchanged
        });
    }

    @Test
    @DisplayName("Clear cart button resets active cart, presentation rows, and payment inputs")
    void testClearCart_ResetsAllFieldsAndTotals() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            posController.getTxtBarcode().setText("BAKE-001");
            posController.getSpnQuantity().getValueFactory().setValue(1);
            posController.handleAddToCart(new ActionEvent());

            posController.getTxtCashTendered().setText("1000.00");
            assertThat(posController.getCartRows()).hasSize(2);
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();

            // Clear cart
            posController.handleClearCart(new ActionEvent());

            assertThat(posController.getCartRows()).isEmpty();
            assertThat(posController.getCart().isEmpty()).isTrue();
            assertThat(posController.getTxtCashTendered().getText()).isEmpty();
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹0.00");
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
        });
    }

    // =========================================================================
    // 2. Tender Calculations Under Boundary Inputs
    // =========================================================================

    @Test
    @DisplayName("Tendered = 0 boundary disables checkout and rejects sale completion")
    void testTenderZeroBoundary() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            // Case A: Explicit "0"
            posController.getTxtCashTendered().setText("0");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Case B: Explicit "0.00"
            posController.getTxtCashTendered().setText("0.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                        // Case C: Empty string
                        posController.getTxtCashTendered().setText("");
                        assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Direct checkout execution must abort cleanly without modifying cart
            posController.handleCompleteSale(new ActionEvent());
            assertThat(posController.getCartRows()).hasSize(1);
            assertThat(posController.getLastCompletedReceipt()).isNull();
        });
    }

    @Test
    @DisplayName("Tendered < total boundary (shortfall) disables checkout and rejects sale")
    void testTenderShortfallBoundary() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            // One paisa shortfall.
            posController.getTxtCashTendered().setText("313.73");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Substantial shortfall.
            posController.getTxtCashTendered().setText("100.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Attempt direct completion
            posController.handleCompleteSale(new ActionEvent());
            assertThat(posController.getCartRows()).hasSize(1);
            assertThat(posController.getLastCompletedReceipt()).isNull();
        });
    }

    @Test
    @DisplayName("Fractional tender and formatting tolerances calculate precise change due")
    void testTenderFractionalAndEdgeFormats() {
        runOnFxThread(() -> {
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            posController.getTxtCashTendered().setText("313.74");
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            posController.getTxtCashTendered().setText("500.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹186.26");

            posController.getTxtCashTendered().setText("313.745");
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.01");

            posController.getTxtCashTendered().setText("₹500.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹186.26");

            posController.getTxtCashTendered().setText("  500.00  ");
            assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹186.26");

            posController.getTxtCashTendered().setText("₹ 500.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Negative input rejected ("-5.00") -> disabled
            posController.getTxtCashTendered().setText("-5.00");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

            // Non-numeric malformed input rejected ("abc") -> disabled
            posController.getTxtCashTendered().setText("abc");
            assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");
        });
    }

    // =========================================================================
    // 3. Inter-Tab State Synchronization
    // =========================================================================

    @Test
    @DisplayName("Completing POS checkout immediately reflects decremented stock in Inventory tab")
    void testPosCheckoutImmediatelyUpdatesInventoryTab() {
        runOnFxThread(() -> {
            // Find initial stock of DAIRY-001 in inventory table
            Item initialItem = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();
            int initialStock = initialItem.currentStock(); // 50

            // Execute sale in POS tab: buy 6 units
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(6);
            posController.handleAddToCart(new ActionEvent());

            posController.getTxtCashTendered().setText("1200.00");
            posController.handleCompleteSale(new ActionEvent());

            // Verify POS completed sale
            Receipt receipt = posController.getLastCompletedReceipt();
            assertThat(receipt).isNotNull();
            assertThat(receipt.serialNumber()).startsWith("SAS-");

            // Verify Inventory tab immediately shows decremented stock without manual action
            Item updatedItem = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();

            assertThat(updatedItem.currentStock()).isEqualTo(initialStock - 6);
        });
    }

    @Test
    @DisplayName("Completing POS checkout immediately reflects updated revenue, profit and item stats in Analytics tab")
    void testPosCheckoutImmediatelyUpdatesAnalyticsTab() {
        runOnFxThread(() -> {
            // BAKE-001 is ₹186.75 with a ₹91.30 unit cost.
            posController.getTxtBarcode().setText("BAKE-001");
            posController.getSpnQuantity().getValueFactory().setValue(4);
            posController.handleAddToCart(new ActionEvent());

            posController.getTxtCashTendered().setText("1000.00");
            posController.handleCompleteSale(new ActionEvent());

            // Four units yield ₹747.00 revenue, ₹365.20 cost, and ₹381.80 profit.
            assertThat(analyticsController.getLblKpiRevenue().getText()).isEqualTo("₹747.00");
            assertThat(analyticsController.getLblKpiProfit().getText()).isEqualTo("₹381.80");
            assertThat(analyticsController.getLblKpiItemsSold().getText()).isEqualTo("4");

            // Verify item breakdown row in Analytics TableView
            assertThat(analyticsController.getItemStatsList()).isNotEmpty();
            Optional<SalesStatistic> statOpt = analyticsController.getItemStatsList().stream()
                    .filter(s -> "BAKE-001".equals(s.itemCode()))
                    .findFirst();

            assertThat(statOpt).isPresent();
            SalesStatistic stat = statOpt.get();
            assertThat(stat.totalQuantitySold()).isEqualTo(4);
            assertThat(stat.totalRevenue()).isEqualByComparingTo("747.00");
            assertThat(stat.totalCost()).isEqualByComparingTo("365.20");
            assertThat(stat.netProfit()).isEqualByComparingTo("381.80");
        });
    }

    @Test
    @DisplayName("Cumulative multi-transaction checkouts synchronize across both Inventory and Analytics tabs")
    void testCumulativeStateSyncAcrossMultipleCycles() {
        runOnFxThread(() -> {
            // Transaction 1: 2x bananas -> revenue ₹280.54, cost ₹149.40.
            posController.getTxtBarcode().setText("PROD-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());
            posController.getTxtCashTendered().setText("500.00");
            posController.handleCompleteSale(new ActionEvent());

            // Transaction 2: 3x mineral water -> revenue ₹321.21, cost ₹149.40.
            posController.getTxtBarcode().setText("BEV-001");
            posController.getSpnQuantity().getValueFactory().setValue(3);
            posController.handleAddToCart(new ActionEvent());
            posController.getTxtCashTendered().setText("500.00");
            posController.handleCompleteSale(new ActionEvent());

            // Cumulative Analytics KPIs:
            // Total revenue ₹601.75, cost ₹298.80, and profit ₹302.95.
            assertThat(analyticsController.getLblKpiRevenue().getText()).isEqualTo("₹601.75");
            assertThat(analyticsController.getLblKpiProfit().getText()).isEqualTo("₹302.95");
            assertThat(analyticsController.getLblKpiItemsSold().getText()).isEqualTo("5");
            assertThat(analyticsController.getItemStatsList()).hasSize(2);

            // Verify both items in inventory table show decremented stock
            Item bananas = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "PROD-001".equals(i.code())).findFirst().orElseThrow();
            Item water = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "BEV-001".equals(i.code())).findFirst().orElseThrow();

            assertThat(bananas.currentStock()).isEqualTo(65 - 2); // Initial stock 65 -> 63
            assertThat(water.currentStock()).isEqualTo(80 - 3);   // Initial stock 80 -> 77
        });
    }

    @Test
    @DisplayName("Restock submission immediately updates Inventory catalog and POS product lookup")
    void testRestockTabUpdatesInventoryAndPos() {
        runOnFxThread(() -> {
            Item item = restockController.getCatalogItems().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();

            int initialStock = item.currentStock(); // 50

            restockController.getCmbItemSelect().setValue(item);
            restockController.getTxtQuantity().setText("25");
            restockController.getTxtOperator().setText("Staff-Alice");
            restockController.getTxtSupplierNotes().setText("Emergency restock batch");
            restockController.handleSubmitRestock(new ActionEvent());

            // 1. Verify Inventory tab immediately shows incremented stock (50 + 25 = 75)
            Item updatedInventory = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();
            assertThat(updatedInventory.currentStock()).isEqualTo(initialStock + 25);

            // 2. Verify POS product lookup ComboBox is refreshed
            Item posLookupItem = posController.getCart().isEmpty() ? updatedInventory : null;
            assertThat(posLookupItem).isNotNull();
            assertThat(posLookupItem.currentStock()).isEqualTo(75);
        });
    }

    @Test
    @DisplayName("Price update immediately updates Inventory table and POS product pricing")
    void testPriceManagementUpdatesInventoryAndPos() {
        runOnFxThread(() -> {
            Item item = priceController.getDisplayedCatalog().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();

            priceController.selectItem(item);
            priceController.getTxtNewPrice().setText("2.99");
            priceController.getTxtManagerName().setText("Manager-Carol");
            priceController.handleUpdatePrice(new ActionEvent());

            // 1. Verify Inventory tab displays new selling price
            Item updatedInventory = inventoryController.getDisplayedItems().stream()
                    .filter(i -> "DAIRY-001".equals(i.code()))
                    .findFirst()
                    .orElseThrow();
            assertThat(updatedInventory.currentSellingPrice()).isEqualByComparingTo("2.99");

            // 2. Verify adding product to POS cart applies new updated price
            posController.getTxtBarcode().setText("DAIRY-001");
            posController.getSpnQuantity().getValueFactory().setValue(2);
            posController.handleAddToCart(new ActionEvent());

            CartItemRow row = posController.getCartRows().get(0);
            assertThat(row.getUnitPrice()).isEqualByComparingTo("2.99");
            assertThat(row.getSubtotal()).isEqualByComparingTo("5.98");
            assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹5.98");
        });
    }
}
