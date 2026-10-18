package com.supermarket.ui;

import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.RestockAudit;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.BillingService;
import com.supermarket.service.InventoryService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.controllers.InventoryController;
import com.supermarket.ui.controllers.PosController;
import com.supermarket.ui.controllers.RestockController;
import com.supermarket.ui.models.CartItemRow;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial and empirical stress-test suite for Milestone 3:
 * 1. PosController cash tendered logic:
 *    - Zero tender (0, 0.00, blank, empty)
 *    - Exact tender
 *    - Massive tender ($1,000,000.00, $999,999,999.99)
 *    - Invalid non-numeric tender ("abc", "$#@!", "--10", "NaN")
 *    - Change calculation precision & sub-cent tolerances
 *    - Shortfall blocking & checkout button state enforcement
 * 2. Active POS cart modifications:
 *    - Adding items, trimming whitespace
 *    - Consolidating duplicate item scans
 *    - Quantity increment and decrement (decrement to 0 auto-removes)
 *    - Deleting rows & clearing cart
 *    - Attempting to exceed inventory (single and cumulative)
 *    - Attempting checkout on empty cart
 * 3. Inventory search and filter:
 *    - Code search (exact, partial, case-insensitive)
 *    - Name search (substring, case-insensitive)
 *    - Category dropdown filtering
 *    - Low stock checkbox filter
 *    - Compound multi-criteria filtering
 *    - Stock status badge assignment (IN STOCK, LOW STOCK, OUT OF STOCK)
 *    - Total and low stock KPI counters
 * 4. RestockController:
 *    - Negative, zero, and blank quantities rejected
 *    - Non-numeric and overflow quantities rejected
 *    - Unselected item rejection
 *    - Positive batches atomically increment stock
 *    - Restock audit log verification
 * 5. CartItemRow presentation model:
 *    - Null-safety fallbacks, quantity updates, subtotal calculation, equals & hashCode
 */
public class PosInventoryRestockAdversarialTest extends BaseJavaFxTest {

    private SqliteItemRepository itemRepo;
    private SqliteSalesTransactionRepository salesRepo;
    private SqliteRestockAuditRepository restockRepo;

    private BillingService billingService;
    private InventoryService inventoryService;
    private ReceiptFormatter receiptFormatter;

    private PosController posController;
    private InventoryController inventoryController;
    private RestockController restockController;

    @BeforeEach
    void setUpControllers() {
        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);

        billingService = new BillingService(db, itemRepo, salesRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
        receiptFormatter = new ReceiptFormatter();

        runOnFxThread(() -> {
            try {
                // Initialize PosController
                FXMLLoader posLoader = new FXMLLoader(getClass().getResource("/fxml/pos.fxml"));
                posLoader.setControllerFactory(type -> new PosController(billingService, inventoryService, receiptFormatter));
                posLoader.load();
                posController = posLoader.getController();

                // Initialize InventoryController
                FXMLLoader invLoader = new FXMLLoader(getClass().getResource("/fxml/inventory.fxml"));
                invLoader.setControllerFactory(type -> new InventoryController(inventoryService));
                invLoader.load();
                inventoryController = invLoader.getController();

                // Initialize RestockController
                FXMLLoader restockLoader = new FXMLLoader(getClass().getResource("/fxml/restock.fxml"));
                restockLoader.setControllerFactory(type -> new RestockController(inventoryService));
                restockLoader.load();
                restockController = restockLoader.getController();

            } catch (IOException e) {
                throw new RuntimeException("Failed to load test FXML hierarchies", e);
            }
        });
    }

    // =========================================================================
    // 1. POS CONTROLLER CASH TENDERED ADVERSARIAL CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("1. PosController Cash Tendered Adversarial Challenges")
    class PosCashTenderedAdversarialTests {

        @Test
        @DisplayName("Zero tender variants (0, 0.00, empty, whitespace) disable checkout and block sale completion")
        void testZeroTenderVariantsBlockCheckout() {
            runOnFxThread(() -> {
                // DAIRY-001 total payable is ₹313.74.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                assertThat(posController.getCartRows()).hasSize(1);
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹313.74");

                String[] zeroInputs = {"0", "0.00", "000.000", "", "   ", "   0   "};
                for (String input : zeroInputs) {
                    posController.getTxtCashTendered().setText(input);
                    assertThat(posController.getBtnCompleteSale().isDisable())
                            .as("Checkout button must be disabled for input '%s'", input)
                            .isTrue();
                    assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                    // Attempting direct checkout must abort cleanly
                    posController.handleCompleteSale(new ActionEvent());
                    assertThat(posController.getLastCompletedReceipt())
                            .as("Sale must not complete with zero tender '%s'", input)
                            .isNull();
                    assertThat(posController.getCartRows()).hasSize(1); // Cart remains intact
                }
            });
        }

        @Test
        @DisplayName("Exact cash tender enables checkout, computes exactly $0.00 change, and completes sale")
        void testExactCashTenderCalculations() {
            runOnFxThread(() -> {
                // PROD-001: bananas ₹140.27 x 3 = ₹420.81.
                posController.getTxtBarcode().setText("PROD-001");
                posController.getSpnQuantity().getValueFactory().setValue(3);
                posController.handleAddToCart(new ActionEvent());

                posController.getTxtCashTendered().setText("420.81");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                posController.handleCompleteSale(new ActionEvent());
                Receipt receipt = posController.getLastCompletedReceipt();
                assertThat(receipt).isNotNull();
                assertThat(receipt.amountTendered()).isEqualByComparingTo("420.81");
                assertThat(receipt.totalAmount()).isEqualByComparingTo("420.81");
                assertThat(receipt.changeDue()).isEqualByComparingTo("0.00");
                assertThat(posController.getCartRows()).isEmpty();
            });
        }

        @Test
        @DisplayName("Massive tender ($1,000,000.00 and $999,999,999.99) computes exact change without overflow")
        void testMassiveCashTenderHandling() {
            runOnFxThread(() -> {
                // DAIRY-001: ₹156.87 x 2 = ₹313.74.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                // Case A: ₹1,000,000.00 with grouping.
                posController.getTxtCashTendered().setText("₹1,000,000.00");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹999686.26");

                // Case B: ₹999,999,999.99.
                posController.getTxtCashTendered().setText("999999999.99");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹999999686.25");

                // Checkout produces receipt with accurate massive change
                posController.handleCompleteSale(new ActionEvent());
                Receipt receipt = posController.getLastCompletedReceipt();
                assertThat(receipt).isNotNull();
                assertThat(receipt.amountTendered()).isEqualByComparingTo("999999999.99");
                assertThat(receipt.changeDue()).isEqualByComparingTo("999999686.25");
            });
        }

        @Test
        @DisplayName("Invalid non-numeric cash tender inputs are parsed as zero and disabled gracefully")
        void testInvalidNonNumericTenderGracefulHandling() {
            runOnFxThread(() -> {
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(1);
                posController.handleAddToCart(new ActionEvent());

                String[] invalidInputs = {"abc", "$#@!", "12.34.56", "--10", "-$50.00", "NaN", "Infinity", "$-", ",", "$$"};
                for (String invalid : invalidInputs) {
                    posController.getTxtCashTendered().setText(invalid);
                    assertThat(posController.getBtnCompleteSale().isDisable())
                            .as("Checkout button must be disabled for invalid input '%s'", invalid)
                            .isTrue();
                    assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                    // Direct checkout attempt blocked
                    posController.handleCompleteSale(new ActionEvent());
                    assertThat(posController.getLastCompletedReceipt()).isNull();
                }
            });
        }

        @Test
        @DisplayName("Change calculation precision: sub-cent rounding and currency formatting tolerances")
        void testChangeCalculationPrecisionAndSubCentRounding() {
            runOnFxThread(() -> {
                // DAIRY-001 total payable is ₹313.74.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                // Tender rounds half-up to exact payment.
                posController.getTxtCashTendered().setText("313.744");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                // Tender rounds half-up one paisa above the total.
                posController.getTxtCashTendered().setText("313.745");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.01");

                posController.getTxtCashTendered().setText("₹500.00");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹186.26");

                posController.getTxtCashTendered().setText("  500.00  ");
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹186.26");

                // Space after the currency symbol is invalid.
                // which causes new BigDecimal(" 10.00") to throw NumberFormatException and safely fallback to ZERO
                posController.getTxtCashTendered().setText("₹ 500.00");
                assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");
            });
        }

        @Test
        @DisplayName("Payment shortfall strictly blocks checkout even with 1-cent difference")
        void testPaymentShortfallStrictBlocking() {
            runOnFxThread(() -> {
                // DAIRY-001 total payable is ₹313.74.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                // One paisa shortfall.
                posController.getTxtCashTendered().setText("313.73");
                assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");

                // Directly invoke checkout action: must be blocked
                posController.handleCompleteSale(new ActionEvent());
                assertThat(posController.getLastCompletedReceipt()).isNull();
                assertThat(posController.getCartRows()).hasSize(1);

                // Live inventory must not have been decremented
                Item item = inventoryService.getItemByCode("DAIRY-001");
                assertThat(item.currentStock()).isEqualTo(50);
            });
        }
    }

    // =========================================================================
    // 2. POS CART MODIFICATIONS & EDGE CASE CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("2. Active POS Cart Modifications & Edge Case Challenges")
    class PosCartModificationsAdversarialTests {

        @Test
        @DisplayName("Adding items trims whitespace and unknown item codes fail gracefully")
        void testAddingItemsAndUnknownCodeHandling() {
            runOnFxThread(() -> {
                // Adding with untrimmed whitespace "  DAIRY-001  "
                posController.getTxtBarcode().setText("  DAIRY-001  ");
                posController.getSpnQuantity().getValueFactory().setValue(1);
                posController.handleAddToCart(new ActionEvent());

                assertThat(posController.getCartRows()).hasSize(1);
                assertThat(posController.getCartRows().get(0).getItemCode()).isEqualTo("DAIRY-001");

                // Adding non-existent code "NONEXISTENT-999"
                posController.getTxtBarcode().setText("NONEXISTENT-999");
                posController.handleAddToCart(new ActionEvent());

                // Cart remains unchanged at size 1
                assertThat(posController.getCartRows()).hasSize(1);

                // Adding with empty barcode and unselected ComboBox
                posController.getTxtBarcode().clear();
                posController.handleAddToCart(new ActionEvent());
                assertThat(posController.getCartRows()).hasSize(1);
            });
        }

        @Test
        @DisplayName("Duplicate item additions consolidate quantities and recalculate subtotals")
        void testConsolidatingQuantitiesOnRepeatedAdds() {
            runOnFxThread(() -> {
                // First add: 2 units of BAKE-001 (₹186.75 each).
                posController.getTxtBarcode().setText("BAKE-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                // Second add: 3 units of BAKE-001
                posController.getTxtBarcode().setText("BAKE-001");
                posController.getSpnQuantity().getValueFactory().setValue(3);
                posController.handleAddToCart(new ActionEvent());

                assertThat(posController.getCartRows()).hasSize(1);
                CartItemRow row = posController.getCartRows().get(0);
                assertThat(row.getQuantity()).isEqualTo(5);
                assertThat(row.getSubtotal()).isEqualByComparingTo("933.75");
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹933.75");
            });
        }

        @Test
        @DisplayName("Quantity buttons: increment, decrement, and auto-removal at zero")
        void testQuantityIncrementAndDecrementControls() {
            runOnFxThread(() -> {
                posController.getTxtBarcode().setText("BAKE-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                CartItemRow row = posController.getCartRows().get(0);

                // Increment (+1 -> 3)
                posController.handleIncrementQuantity(row);
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(3);
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹560.25");

                // Decrement (-1 -> 2)
                posController.handleDecrementQuantity(row);
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(2);
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹373.50");

                // Decrement (-1 -> 1)
                posController.handleDecrementQuantity(row);
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(1);
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹186.75");

                // Decrement to zero (-1 -> 0): removes row automatically
                posController.handleDecrementQuantity(row);
                assertThat(posController.getCartRows()).isEmpty();
                assertThat(posController.getCart().isEmpty()).isTrue();
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹0.00");
            });
        }

        @Test
        @DisplayName("Deleting individual rows updates cart and recalibrates total payable")
        void testDeleteCartItemUpdatesTotals() {
            runOnFxThread(() -> {
                // Add milk (₹156.87).
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(1);
                posController.handleAddToCart(new ActionEvent());

                // Add bread (₹186.75).
                posController.getTxtBarcode().setText("BAKE-001");
                posController.getSpnQuantity().getValueFactory().setValue(1);
                posController.handleAddToCart(new ActionEvent());

                assertThat(posController.getCartRows()).hasSize(2);
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹343.62");

                // Delete first item
                posController.handleDeleteCartItem(posController.getCartRows().get(0));
                assertThat(posController.getCartRows()).hasSize(1);
                assertThat(posController.getCartRows().get(0).getItemCode()).isEqualTo("BAKE-001");
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹186.75");
            });
        }

        @Test
        @DisplayName("Clear cart button resets all cart rows, payment inputs, and totals")
        void testClearCartResetsCompleteState() {
            runOnFxThread(() -> {
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                posController.getTxtCashTendered().setText("1000.00");
                assertThat(posController.getCartRows()).isNotEmpty();
                assertThat(posController.getBtnCompleteSale().isDisable()).isFalse();

                posController.handleClearCart(new ActionEvent());

                assertThat(posController.getCartRows()).isEmpty();
                assertThat(posController.getCart().isEmpty()).isTrue();
                assertThat(posController.getTxtCashTendered().getText()).isEmpty();
                assertThat(posController.getLblTotalPayable().getText()).isEqualTo("₹0.00");
                assertThat(posController.getLblChangeDue().getText()).isEqualTo("₹0.00");
                assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();
            });
        }

        @Test
        @DisplayName("Attempting to exceed available inventory stock is rejected cleanly")
        void testExceedingStockBoundaryRejection() {
            runOnFxThread(() -> {
                // DAIRY-001 stock is 50. Requesting 51 units.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(51);
                posController.handleAddToCart(new ActionEvent());

                // Cart remains empty
                assertThat(posController.getCartRows()).isEmpty();

                // Add exact stock (50 units)
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(50);
                posController.handleAddToCart(new ActionEvent());
                assertThat(posController.getCartRows()).hasSize(1);
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(50);

                // Attempt to add 1 more unit via scan
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(1);
                posController.handleAddToCart(new ActionEvent());
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(50);

                // Attempt to increment via button when already at max stock
                posController.handleIncrementQuantity(posController.getCartRows().get(0));
                assertThat(posController.getCartRows().get(0).getQuantity()).isEqualTo(50);
            });
        }

        @Test
        @DisplayName("Attempting to checkout with an empty cart is blocked cleanly")
        void testCheckoutWithEmptyCartBlocked() {
            runOnFxThread(() -> {
                assertThat(posController.getCartRows()).isEmpty();
                assertThat(posController.getBtnCompleteSale().isDisable()).isTrue();

                // Invoking checkout handler directly on empty cart
                posController.handleCompleteSale(new ActionEvent());
                assertThat(posController.getLastCompletedReceipt()).isNull();
            });
        }
    }

    // =========================================================================
    // 3. INVENTORY SEARCH & FILTER ADVERSARIAL CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("3. Inventory Search & Filter Adversarial Challenges")
    class InventorySearchAndFilterAdversarialTests {

        @Test
        @DisplayName("Code search matches exact, partial, and case-insensitive queries")
        void testCodeSearchFiltering() {
            runOnFxThread(() -> {
                // Exact code
                inventoryController.getTxtSearch().setText("DAIRY-001");
                assertThat(inventoryController.getDisplayedItems()).hasSize(1);
                assertThat(inventoryController.getDisplayedItems().get(0).code()).isEqualTo("DAIRY-001");

                // Partial code
                inventoryController.getTxtSearch().setText("DAIRY");
                assertThat(inventoryController.getDisplayedItems()).isNotEmpty();
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.code()).contains("DAIRY");
                }

                // Lowercase code
                inventoryController.getTxtSearch().setText("dairy-001");
                assertThat(inventoryController.getDisplayedItems()).hasSize(1);

                // Non-matching code
                inventoryController.getTxtSearch().setText("NONEXISTENT_SKU_999");
                assertThat(inventoryController.getDisplayedItems()).isEmpty();
            });
        }

        @Test
        @DisplayName("Name search matches substrings case-insensitively")
        void testNameSearchFiltering() {
            runOnFxThread(() -> {
                // Substring "milk"
                inventoryController.getTxtSearch().setText("milk");
                assertThat(inventoryController.getDisplayedItems()).isNotEmpty();
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.name().toLowerCase()).contains("milk");
                }

                // Uppercase "BREAD"
                inventoryController.getTxtSearch().setText("BREAD");
                assertThat(inventoryController.getDisplayedItems()).isNotEmpty();
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.name().toLowerCase()).contains("bread");
                }
            });
        }

        @Test
        @DisplayName("Category dropdown filters catalog and All Categories restores all")
        void testCategoryDropdownFiltering() {
            runOnFxThread(() -> {
                inventoryController.getCmbCategory().setValue("Bakery");
                assertThat(inventoryController.getDisplayedItems()).isNotEmpty();
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.category()).isEqualTo("Bakery");
                }

                inventoryController.getCmbCategory().setValue("Produce");
                assertThat(inventoryController.getDisplayedItems()).isNotEmpty();
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.category()).isEqualTo("Produce");
                }

                inventoryController.getCmbCategory().setValue("All Categories");
                assertThat(inventoryController.getDisplayedItems()).hasSameSizeAs(inventoryController.getFullCatalog());
            });
        }

        @Test
        @DisplayName("Low stock toggle filters items at or below threshold")
        void testLowStockFilterCheckbox() {
            runOnFxThread(() -> {
                inventoryController.getChkLowStockOnly().setSelected(true);
                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.isLowStock() || item.currentStock() <= 0).isTrue();
                }

                inventoryController.getChkLowStockOnly().setSelected(false);
                assertThat(inventoryController.getDisplayedItems()).hasSameSizeAs(inventoryController.getFullCatalog());
            });
        }

        @Test
        @DisplayName("Multi-criteria compound filter (search + category + low stock toggle)")
        void testMultiFieldCompoundFiltering() {
            runOnFxThread(() -> {
                // Combine: search="bread", category="Bakery", lowStock=false
                inventoryController.getTxtSearch().setText("bread");
                inventoryController.getCmbCategory().setValue("Bakery");
                inventoryController.getChkLowStockOnly().setSelected(false);

                for (Item item : inventoryController.getDisplayedItems()) {
                    assertThat(item.name().toLowerCase()).contains("bread");
                    assertThat(item.category()).isEqualTo("Bakery");
                }
            });
        }

        @Test
        @DisplayName("Stock status values and badge classification: OUT OF STOCK, LOW STOCK, IN STOCK")
        void testStockStatusBadgeAndTextClassification() {
            runOnFxThread(() -> {
                // Verify initial catalog has items with appropriate status mappings
                List<Item> catalog = inventoryController.getFullCatalog();
                for (Item item : catalog) {
                    String expectedStatus;
                    if (item.currentStock() <= 0) {
                        expectedStatus = "OUT OF STOCK";
                    } else if (item.isLowStock()) {
                        expectedStatus = "LOW STOCK";
                    } else {
                        expectedStatus = "IN STOCK";
                    }

                    if (item.currentStock() <= 0) {
                        assertThat(expectedStatus).isEqualTo("OUT OF STOCK");
                    } else if (item.currentStock() <= item.minStockThreshold()) {
                        assertThat(expectedStatus).isEqualTo("LOW STOCK");
                    } else {
                        assertThat(expectedStatus).isEqualTo("IN STOCK");
                    }
                }
            });
        }
    }

    // =========================================================================
    // 4. RESTOCK CONTROLLER ADVERSARIAL CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("4. RestockController Adversarial Challenges")
    class RestockControllerAdversarialTests {

        @Test
        @DisplayName("Negative, zero, and blank quantities are rejected without database modification")
        void testRestockNegativeZeroAndBlankQuantitiesRejected() {
            runOnFxThread(() -> {
                Item item = restockController.getCatalogItems().get(0);
                int initialStock = item.currentStock();

                restockController.getCmbItemSelect().setValue(item);

                String[] badQuantities = {"0", "-1", "-50", "", "   "};
                for (String badQty : badQuantities) {
                    restockController.getTxtQuantity().setText(badQty);
                    restockController.handleSubmitRestock(new ActionEvent());

                    // Stock remains unmodified
                    Item checkItem = inventoryService.getItemByCode(item.code());
                    assertThat(checkItem.currentStock()).isEqualTo(initialStock);
                }
            });
        }

        @Test
        @DisplayName("Non-numeric and overflow quantity strings are rejected without crashing")
        void testRestockInvalidNonNumericFormatsRejected() {
            runOnFxThread(() -> {
                Item item = restockController.getCatalogItems().get(0);
                int initialStock = item.currentStock();

                restockController.getCmbItemSelect().setValue(item);

                String[] malformedQuantities = {"abc", "12.5", "10,000", "99999999999999999999", "$50", "NaN"};
                for (String badQty : malformedQuantities) {
                    restockController.getTxtQuantity().setText(badQty);
                    restockController.handleSubmitRestock(new ActionEvent());

                    // Stock remains unmodified
                    Item checkItem = inventoryService.getItemByCode(item.code());
                    assertThat(checkItem.currentStock()).isEqualTo(initialStock);
                }
            });
        }

        @Test
        @DisplayName("Submitting restock without selecting an item is rejected cleanly")
        void testRestockWithoutSelectedItemRejected() {
            runOnFxThread(() -> {
                restockController.getCmbItemSelect().setValue(null);
                restockController.getTxtQuantity().setText("20");

                restockController.handleSubmitRestock(new ActionEvent());
                // Does not throw and does not insert audit log
            });
        }

        @Test
        @DisplayName("Positive restock increments stock atomically and appends audit log entry")
        void testPositiveRestockIncrementsStockAndLogsAudit() {
            AtomicBoolean listenerFired = new AtomicBoolean(false);
            restockController.setOnRestockCompletedListener(() -> listenerFired.set(true));

            runOnFxThread(() -> {
                Item item = restockController.getCatalogItems().get(0);
                int prevStock = item.currentStock();

                restockController.getCmbItemSelect().setValue(item);
                restockController.getTxtQuantity().setText("40");
                restockController.getTxtOperator().setText("Operator-Bob");
                restockController.getTxtSupplierNotes().setText("Supplier Batch #SUP-2026-X");

                restockController.handleSubmitRestock(new ActionEvent());

                // Database stock must be incremented
                Item updatedItem = inventoryService.getItemByCode(item.code());
                assertThat(updatedItem.currentStock()).isEqualTo(prevStock + 40);

                // Audit log table must contain new entry
                assertThat(restockController.getAuditLogs()).isNotEmpty();
                RestockAudit latest = restockController.getAuditLogs().get(restockController.getAuditLogs().size() - 1);
                assertThat(latest.itemCode()).isEqualTo(item.code());
                assertThat(latest.quantityAdded()).isEqualTo(40);
                assertThat(latest.previousStock()).isEqualTo(prevStock);
                assertThat(latest.newStock()).isEqualTo(prevStock + 40);
                assertThat(latest.performedBy()).isEqualTo("Operator-Bob");
                assertThat(latest.batchNotes()).isEqualTo("Supplier Batch #SUP-2026-X");
                assertThat(latest.timestamp()).isNotNull();

                // Form fields cleared
                assertThat(restockController.getTxtQuantity().getText()).isEmpty();
                assertThat(restockController.getTxtSupplierNotes().getText()).isEmpty();

                // Listener called
                assertThat(listenerFired.get()).isTrue();
            });
        }
    }

    // =========================================================================
    // 5. CART ITEM ROW MODEL ADVERSARIAL CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("5. CartItemRow Presentation Model Adversarial Challenges")
    class CartItemRowModelAdversarialTests {

        @Test
        @DisplayName("CartItemRow constructor handles null and boundary values safely")
        void testCartItemRowConstructorRobustness() {
            // Null name falls back to code
            CartItemRow row1 = new CartItemRow("SKU-001", null, null, null, -5);
            assertThat(row1.getItemName()).isEqualTo("SKU-001");
            assertThat(row1.getCategory()).isEqualTo("General");
            assertThat(row1.getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row1.getQuantity()).isEqualTo(1); // Clamped to min 1
            assertThat(row1.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row1.getFormattedUnitPrice()).isEqualTo("₹0.00");
            assertThat(row1.getFormattedSubtotal()).isEqualTo("₹0.00");

            // Blank name falls back to code
            CartItemRow row2 = new CartItemRow("SKU-002", "   ", "   ", new BigDecimal("4.50"), 2);
            assertThat(row2.getItemName()).isEqualTo("SKU-002");
            assertThat(row2.getCategory()).isEqualTo("General");
            assertThat(row2.getUnitPrice()).isEqualByComparingTo("4.50");
            assertThat(row2.getQuantity()).isEqualTo(2);
            assertThat(row2.getSubtotal()).isEqualByComparingTo("9.00");
            assertThat(row2.getFormattedUnitPrice()).isEqualTo("₹4.50");
            assertThat(row2.getFormattedSubtotal()).isEqualTo("₹9.00");
        }

        @Test
        @DisplayName("CartItemRow setQuantity dynamically updates subtotal and formatted output")
        void testCartItemRowSetQuantityRecalculatesSubtotal() {
            CartItemRow row = new CartItemRow("SKU-003", "Test Item", "Dairy", new BigDecimal("2.50"), 1);
            assertThat(row.getSubtotal()).isEqualByComparingTo("2.50");
            assertThat(row.getFormattedSubtotal()).isEqualTo("₹2.50");

            row.setQuantity(4);
            assertThat(row.getQuantity()).isEqualTo(4);
            assertThat(row.getSubtotal()).isEqualByComparingTo("10.00");
            assertThat(row.getFormattedSubtotal()).isEqualTo("₹10.00");
        }

        @Test
        @DisplayName("CartItemRow equals and hashCode adhere to itemCode identity contract")
        void testCartItemRowEqualsAndHashCodeContract() {
            CartItemRow row1 = new CartItemRow("SKU-SAME", "Item A", "Cat A", new BigDecimal("1.00"), 1);
            CartItemRow row2 = new CartItemRow("SKU-SAME", "Item B", "Cat B", new BigDecimal("2.00"), 5);
            CartItemRow row3 = new CartItemRow("SKU-DIFF", "Item A", "Cat A", new BigDecimal("1.00"), 1);

            assertThat(row1).isEqualTo(row2);
            assertThat(row1.hashCode()).isEqualTo(row2.hashCode());
            assertThat(row1).isNotEqualTo(row3);
            assertThat(row1).isNotEqualTo(null);
            assertThat(row1).isNotEqualTo("StringObject");
        }
    }
}
