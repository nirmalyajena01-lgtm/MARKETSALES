package com.supermarket.ui.controllers;

import com.supermarket.domain.Cart;
import com.supermarket.domain.CartItem;
import com.supermarket.domain.CartTotals;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.PaymentShortfallException;
import com.supermarket.service.BillingService;
import com.supermarket.service.InventoryService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.models.CartItemRow;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controller for the Point of Sale (POS) sales billing interface.
 * Implements Requirement R1: interactive barcode input, active cart management,
 * live subtotal & change computation, atomic inventory decrement on checkout,
 * and thermal receipt preview dialog invocation.
 */
public class PosController {

    @FXML
    private TextField txtBarcode;

    @FXML
    private ComboBox<Item> cmbItemLookup;

    @FXML
    private Spinner<Integer> spnQuantity;

    @FXML
    private Button btnAddToCart;

    @FXML
    private TableView<CartItemRow> tableCart;

    @FXML
    private TableColumn<CartItemRow, String> colCode;

    @FXML
    private TableColumn<CartItemRow, String> colName;

    @FXML
    private TableColumn<CartItemRow, String> colCategory;

    @FXML
    private TableColumn<CartItemRow, String> colUnitPrice;

    @FXML
    private TableColumn<CartItemRow, Integer> colQuantity;

    @FXML
    private TableColumn<CartItemRow, String> colSubtotal;

    @FXML
    private TableColumn<CartItemRow, Void> colAction;

    @FXML
    private Label lblSubtotal;

    @FXML
    private Label lblTax;

    @FXML
    private Label lblTotalPayable;

    @FXML
    private TextField txtCashTendered;

    @FXML
    private Label lblChangeDue;

    @FXML
    private Label lblPaymentWarning;

    @FXML
    private Button btnCompleteSale;

    @FXML
    private Button btnClearCart;

    @FXML
    private Label lblCartStatus;

    private final BillingService billingService;
    private final InventoryService inventoryService;
    private final ReceiptFormatter receiptFormatter;

    private final Cart cart;
    private final ObservableList<CartItemRow> cartRows = FXCollections.observableArrayList();
    private final Map<String, String> itemCategoryCache = new HashMap<>();

    private Receipt lastCompletedReceipt;
    private Runnable onSaleCompletedListener;
    private String activeCashier = "Cashier-1";

    public PosController() {
        this(new BillingService(), new InventoryService(), new ReceiptFormatter());
    }

    public PosController(BillingService billingService, InventoryService inventoryService, ReceiptFormatter receiptFormatter) {
        this.billingService = Objects.requireNonNull(billingService, "BillingService cannot be null");
        this.inventoryService = Objects.requireNonNull(inventoryService, "InventoryService cannot be null");
        this.receiptFormatter = Objects.requireNonNull(receiptFormatter, "ReceiptFormatter cannot be null");
        this.cart = billingService.createCart();
    }

    @FXML
    public void initialize() {
        setupTableColumns();
        setupSpinner();
        setupItemLookup();
        setupCashTenderedListener();
        setupBarcodeKeyHandler();

        refreshProductLookup();
        updateTotalsDisplay();
    }

    private void setupTableColumns() {
        if (colCode != null) {
            colCode.setCellValueFactory(new PropertyValueFactory<>("itemCode"));
        }
        if (colName != null) {
            colName.setCellValueFactory(new PropertyValueFactory<>("itemName"));
        }
        if (colCategory != null) {
            colCategory.setCellValueFactory(new PropertyValueFactory<>("category"));
        }
        if (colUnitPrice != null) {
            colUnitPrice.setCellValueFactory(new PropertyValueFactory<>("formattedUnitPrice"));
        }
        if (colQuantity != null) {
            colQuantity.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        }
        if (colSubtotal != null) {
            colSubtotal.setCellValueFactory(new PropertyValueFactory<>("formattedSubtotal"));
        }
        if (colAction != null) {
            setupActionColumn();
        }

        if (tableCart != null) {
            tableCart.setItems(cartRows);
        }
    }

    private void setupActionColumn() {
        colAction.setCellFactory(col -> new TableCell<>() {
            private final Button btnDec = new Button("-");
            private final Button btnInc = new Button("+");
            private final Button btnDel = new Button("×");
            private final HBox pane = new HBox(4, btnDec, btnInc, btnDel);

            {
                pane.setAlignment(Pos.CENTER);
                btnDec.getStyleClass().add("button-sm");
                btnInc.getStyleClass().add("button-sm");
                btnDel.getStyleClass().add("button-sm");
                btnDel.setStyle("-fx-text-fill: #dc2626;");

                btnDec.setOnAction(e -> {
                    CartItemRow row = getTableView().getItems().get(getIndex());
                    handleDecrementQuantity(row);
                });
                btnInc.setOnAction(e -> {
                    CartItemRow row = getTableView().getItems().get(getIndex());
                    handleIncrementQuantity(row);
                });
                btnDel.setOnAction(e -> {
                    CartItemRow row = getTableView().getItems().get(getIndex());
                    handleDeleteCartItem(row);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    setGraphic(pane);
                }
            }
        });
    }

    private void setupSpinner() {
        if (spnQuantity != null) {
            SpinnerValueFactory<Integer> valueFactory =
                    new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 9999, 1);
            spnQuantity.setValueFactory(valueFactory);
            spnQuantity.setEditable(true);
        }
    }

    private void setupItemLookup() {
        if (cmbItemLookup == null) return;

        cmbItemLookup.setConverter(new StringConverter<>() {
            @Override
            public String toString(Item item) {
                if (item == null) return "";
                return String.format("[%s] %s (₹%.2f | Stock: %d)",
                        item.code(), item.name(), item.currentSellingPrice().doubleValue(), item.currentStock());
            }

            @Override
            public Item fromString(String string) {
                return null;
            }
        });

        cmbItemLookup.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("[%s] %s (₹%.2f | Stock: %d)",
                            item.code(), item.name(), item.currentSellingPrice().doubleValue(), item.currentStock()));
                }
            }
        });

        cmbItemLookup.setOnAction(event -> {
            Item selected = cmbItemLookup.getSelectionModel().getSelectedItem();
            if (selected != null && txtBarcode != null) {
                txtBarcode.setText(selected.code());
            }
        });
    }

    private void setupBarcodeKeyHandler() {
        if (txtBarcode != null) {
            txtBarcode.setOnKeyPressed((KeyEvent event) -> {
                if (event.getCode() == KeyCode.ENTER) {
                    handleAddToCart(new ActionEvent());
                }
            });
        }
    }

    private void setupCashTenderedListener() {
        if (txtCashTendered != null) {
            txtCashTendered.textProperty().addListener((obs, oldVal, newVal) -> {
                updateTotalsDisplay();
            });
        }
    }

    /**
     * Refreshes the product catalog choices in the search ComboBox and caches categories.
     */
    public void refreshProductLookup() {
        try {
            List<Item> items = inventoryService.getAllItems();
            itemCategoryCache.clear();
            for (Item item : items) {
                itemCategoryCache.put(item.code(), item.category());
            }
            if (cmbItemLookup != null) {
                cmbItemLookup.setItems(FXCollections.observableArrayList(items));
            }
        } catch (Exception e) {
            setStatus("Failed to load inventory lookup: " + e.getMessage(), true);
        }
    }

    @FXML
    public void handleAddToCart(ActionEvent event) {
        String code = txtBarcode != null ? txtBarcode.getText() : null;
        if ((code == null || code.isBlank()) && cmbItemLookup != null && cmbItemLookup.getValue() != null) {
            code = cmbItemLookup.getValue().code();
        }

        if (code == null || code.isBlank()) {
            setStatus("Please enter or select an item code / barcode.", true);
            return;
        }

        int quantity = 1;
        if (spnQuantity != null && spnQuantity.getValue() != null) {
            quantity = spnQuantity.getValue();
        }

        try {
            Item item = inventoryService.getItemByCode(code.trim());
            billingService.addItemToCart(cart, item.code(), quantity);

            // Sync cartRows with active cart
            syncCartRows();
            updateTotalsDisplay();

            setStatus("Added " + quantity + "x " + item.name() + " to cart.", false);
            if (txtBarcode != null) {
                txtBarcode.clear();
                txtBarcode.requestFocus();
            }
            if (spnQuantity != null) {
                spnQuantity.getValueFactory().setValue(1);
            }
            if (cmbItemLookup != null) {
                cmbItemLookup.getSelectionModel().clearSelection();
            }
        } catch (ItemNotFoundException e) {
            setStatus("Item not found: " + code, true);
        } catch (InsufficientStockException e) {
            setStatus(String.format("Insufficient stock for %s (%s). Requested: %d, Available: %d",
                    e.getItemName(), e.getItemCode(), e.getRequestedQuantity(), e.getAvailableStock()), true);
        } catch (Exception e) {
            setStatus("Error adding item: " + e.getMessage(), true);
        }
    }

    public void handleIncrementQuantity(CartItemRow row) {
        if (row == null) return;
        try {
            int newQty = row.getQuantity() + 1;
            billingService.updateCartItemQuantity(cart, row.getItemCode(), newQty);
            row.setQuantity(newQty);
            syncCartRows();
            updateTotalsDisplay();
            setStatus("Incremented quantity for " + row.getItemName(), false);
        } catch (InsufficientStockException e) {
            setStatus(String.format("Cannot increment: only %d units of %s available in stock.",
                    e.getAvailableStock(), row.getItemName()), true);
        } catch (Exception e) {
            setStatus("Error updating quantity: " + e.getMessage(), true);
        }
    }

    public void handleDecrementQuantity(CartItemRow row) {
        if (row == null) return;
        try {
            int newQty = row.getQuantity() - 1;
            if (newQty <= 0) {
                handleDeleteCartItem(row);
            } else {
                billingService.updateCartItemQuantity(cart, row.getItemCode(), newQty);
                row.setQuantity(newQty);
                syncCartRows();
                updateTotalsDisplay();
                setStatus("Decremented quantity for " + row.getItemName(), false);
            }
        } catch (Exception e) {
            setStatus("Error updating quantity: " + e.getMessage(), true);
        }
    }

    public void handleDeleteCartItem(CartItemRow row) {
        if (row == null) return;
        billingService.removeItemFromCart(cart, row.getItemCode());
        syncCartRows();
        updateTotalsDisplay();
        setStatus("Removed " + row.getItemName() + " from cart.", false);
    }

    @FXML
    public void handleClearCart(ActionEvent event) {
        billingService.clearCart(cart);
        syncCartRows();
        if (txtCashTendered != null) {
            txtCashTendered.clear();
        }
        updateTotalsDisplay();
        setStatus("Cart cleared.", false);
    }

    @FXML
    public void handleCompleteSale(ActionEvent event) {
        if (cart.isEmpty()) {
            setStatus("Cannot checkout: cart is empty.", true);
            return;
        }

        BigDecimal tendered = parseTenderedAmount();
        CartTotals totals = billingService.computeTotals(cart, tendered);

        if (!totals.isPaidInFull()) {
            setStatus(String.format("Payment shortfall! Total payable: ₹%.2f, Tendered: ₹%.2f",
                    totals.totalPayable().doubleValue(), tendered.doubleValue()), true);
            return;
        }

        try {
            Receipt receipt = billingService.checkout(cart, tendered, activeCashier);
            this.lastCompletedReceipt = receipt;

            // Clear table and inputs
            syncCartRows();
            if (txtCashTendered != null) {
                txtCashTendered.clear();
            }
            updateTotalsDisplay();
            refreshProductLookup();

            setStatus("Sale completed! Bill No: " + receipt.serialNumber() + " | Change Due: ₹" +
                    receipt.changeDue().setScale(2, RoundingMode.HALF_UP).toPlainString(), false);

            // Open modal thermal receipt preview
            openReceiptModal(receipt);

            // Trigger listener to update other views (inventory, analytics)
            if (onSaleCompletedListener != null) {
                onSaleCompletedListener.run();
            }

        } catch (InsufficientStockException e) {
            setStatus(String.format("Checkout aborted! Insufficient stock for %s (%s). Available: %d",
                    e.getItemName(), e.getItemCode(), e.getAvailableStock()), true);
            showErrorAlert("Checkout Aborted - Insufficient Stock",
                    String.format("Stock level for '%s' is insufficient for this sale.\nRequested: %d, Available: %d.\nNo items were decremented.",
                            e.getItemName(), e.getRequestedQuantity(), e.getAvailableStock()));
        } catch (PaymentShortfallException e) {
            setStatus(String.format("Shortfall: requires ₹%.2f", e.getShortfall().doubleValue()), true);
        } catch (Exception e) {
            setStatus("Checkout transaction failed: " + e.getMessage(), true);
            showErrorAlert("Checkout Error", "An unexpected database error occurred: " + e.getMessage());
        }
    }

    protected void openReceiptModal(Receipt receipt) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/receipt_preview.fxml"));
            Parent root = loader.load();
            ReceiptDialogController controller = loader.getController();
            controller.setReceipt(receipt);

            Stage dialogStage = new Stage();
            dialogStage.setTitle("Receipt Preview - " + receipt.serialNumber());
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (txtBarcode != null && txtBarcode.getScene() != null && txtBarcode.getScene().getWindow() != null) {
                dialogStage.initOwner(txtBarcode.getScene().getWindow());
            }

            Scene scene = new Scene(root);
            try {
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
            } catch (Exception ignored) {}

            dialogStage.setScene(scene);
            dialogStage.show();
        } catch (Exception e) {
            // In headless testing environments or modal errors, log and continue
            setStatus("Receipt generated: " + receipt.serialNumber() + " (Modal preview unavailable)", false);
        }
    }

    private void syncCartRows() {
        cartRows.clear();
        for (CartItem ci : cart.getItems()) {
            String category = itemCategoryCache.getOrDefault(ci.itemCode(), "General");
            cartRows.add(new CartItemRow(ci.itemCode(), ci.itemName(), category, ci.unitPrice(), ci.quantity()));
        }
    }

    private void updateTotalsDisplay() {
        BigDecimal tendered = parseTenderedAmount();
        CartTotals totals = billingService.computeTotals(cart, tendered);

        if (lblSubtotal != null) {
            lblSubtotal.setText("₹" + totals.subtotal().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (lblTax != null) {
            lblTax.setText("₹" + totals.taxAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (lblTotalPayable != null) {
            lblTotalPayable.setText("₹" + totals.totalPayable().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        if (cart.isEmpty()) {
            if (lblChangeDue != null) lblChangeDue.setText("₹0.00");
            if (lblPaymentWarning != null) lblPaymentWarning.setText("");
            if (btnCompleteSale != null) btnCompleteSale.setDisable(true);
            return;
        }

        if (totals.isPaidInFull()) {
            if (lblChangeDue != null) {
                lblChangeDue.setText("₹" + totals.changeDue().setScale(2, RoundingMode.HALF_UP).toPlainString());
                lblChangeDue.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 16px;");
            }
            if (lblPaymentWarning != null) {
                lblPaymentWarning.setText("✓ Tendered amount is sufficient.");
                lblPaymentWarning.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
            if (btnCompleteSale != null) {
                btnCompleteSale.setDisable(false);
            }
        } else {
            if (lblChangeDue != null) {
                lblChangeDue.setText("₹0.00");
                lblChangeDue.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 16px;");
            }
            if (lblPaymentWarning != null) {
                lblPaymentWarning.setText(String.format("Payment shortfall: requires ₹%.2f", totals.getShortfall().doubleValue()));
                lblPaymentWarning.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            }
            if (btnCompleteSale != null) {
                btnCompleteSale.setDisable(true);
            }
        }
    }

    private BigDecimal parseTenderedAmount() {
        if (txtCashTendered == null || txtCashTendered.getText() == null || txtCashTendered.getText().isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            String clean = txtCashTendered.getText().trim().replace("₹", "").replace(",", "");
            BigDecimal val = new BigDecimal(clean);
            return val.signum() >= 0 ? val : BigDecimal.ZERO;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private void setStatus(String message, boolean isError) {
        if (lblCartStatus != null) {
            lblCartStatus.setText(message);
            if (isError) {
                lblCartStatus.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblCartStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }

    private void showErrorAlert(String title, String message) {
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception ignored) {
            // Headless testing safe
        }
    }

    // Getters and helper methods for testing and coordination
    public Cart getCart() {
        return cart;
    }

    public ObservableList<CartItemRow> getCartRows() {
        return cartRows;
    }

    public Receipt getLastCompletedReceipt() {
        return lastCompletedReceipt;
    }

    public void setOnSaleCompletedListener(Runnable listener) {
        this.onSaleCompletedListener = listener;
    }

    public void setActiveCashier(String cashier) {
        this.activeCashier = (cashier == null || cashier.isBlank()) ? "Cashier-1" : cashier;
    }

    public TextField getTxtBarcode() {
        return txtBarcode;
    }

    public TextField getTxtCashTendered() {
        return txtCashTendered;
    }

    public Spinner<Integer> getSpnQuantity() {
        return spnQuantity;
    }

    public Label getLblTotalPayable() {
        return lblTotalPayable;
    }

    public Label getLblChangeDue() {
        return lblChangeDue;
    }

    public Button getBtnCompleteSale() {
        return btnCompleteSale;
    }

    public ReceiptFormatter getReceiptFormatter() {
        return receiptFormatter;
    }
}
