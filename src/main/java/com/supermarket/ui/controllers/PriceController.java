package com.supermarket.ui.controllers;

import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.exception.ValidationException;
import com.supermarket.service.InventoryService;
import com.supermarket.service.PriceService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controller for the Manager Dynamic Selling Price Management tab.
 * Implements Requirement R4: dynamic selling price adjustments, real-time profit margin
 * computation, below-cost warning detection, and historical price audit tracking.
 */
public class PriceController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML
    private TextField txtSearch;

    @FXML
    private TableView<Item> tableItems;

    @FXML
    private TableColumn<Item, String> colCode;

    @FXML
    private TableColumn<Item, String> colName;

    @FXML
    private TableColumn<Item, String> colCategory;

    @FXML
    private TableColumn<Item, String> colCostPrice;

    @FXML
    private TableColumn<Item, String> colSellingPrice;

    @FXML
    private TableColumn<Item, String> colMargin;

    // Price Editor fields
    @FXML
    private Label lblSelectedItemCode;

    @FXML
    private Label lblSelectedItemName;

    @FXML
    private Label lblCurrentCost;

    @FXML
    private Label lblCurrentPrice;

    @FXML
    private Label lblCurrentMargin;

    @FXML
    private TextField txtNewPrice;

    @FXML
    private Label lblProposedMargin;

    @FXML
    private Label lblPriceWarning;

    @FXML
    private TextField txtManagerName;

    @FXML
    private Button btnUpdatePrice;

    @FXML
    private Label lblPriceStatus;

    // Price History table
    @FXML
    private TableView<PriceHistory> tablePriceHistory;

    @FXML
    private TableColumn<PriceHistory, String> colHistTimestamp;

    @FXML
    private TableColumn<PriceHistory, String> colHistItemCode;

    @FXML
    private TableColumn<PriceHistory, String> colHistOldPrice;

    @FXML
    private TableColumn<PriceHistory, String> colHistNewPrice;

    @FXML
    private TableColumn<PriceHistory, String> colHistChangedBy;

    private final PriceService priceService;
    private final InventoryService inventoryService;

    private final ObservableList<Item> displayedCatalog = FXCollections.observableArrayList();
    private final ObservableList<PriceHistory> priceHistories = FXCollections.observableArrayList();
    private List<Item> fullCatalog = new ArrayList<>();
    private Item selectedItem;
    private Runnable onPriceUpdatedListener;

    public PriceController() {
        this(new PriceService(), new InventoryService());
    }

    public PriceController(PriceService priceService, InventoryService inventoryService) {
        this.priceService = Objects.requireNonNull(priceService, "PriceService cannot be null");
        this.inventoryService = Objects.requireNonNull(inventoryService, "InventoryService cannot be null");
    }

    @FXML
    public void initialize() {
        setupCatalogTable();
        setupHistoryTable();
        setupSearchFilter();
        setupNewPriceListener();
        loadData();
    }

    private void setupCatalogTable() {
        if (colCode != null) {
            colCode.setCellValueFactory(new PropertyValueFactory<>("code"));
        }
        if (colName != null) {
            colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        }
        if (colCategory != null) {
            colCategory.setCellValueFactory(new PropertyValueFactory<>("category"));
        }
        if (colCostPrice != null) {
            colCostPrice.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().costPrice())));
        }
        if (colSellingPrice != null) {
            colSellingPrice.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().currentSellingPrice())));
        }
        if (colMargin != null) {
            colMargin.setCellValueFactory(cellData -> {
                Item it = cellData.getValue();
                double margin = computeMargin(it.costPrice(), it.currentSellingPrice());
                return new SimpleStringProperty(String.format("%.1f%%", margin));
            });
        }

        if (tableItems != null) {
            tableItems.setItems(displayedCatalog);
            tableItems.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                selectItem(newVal);
            });
        }
    }

    private void setupHistoryTable() {
        if (colHistTimestamp != null) {
            colHistTimestamp.setCellValueFactory(cellData -> {
                if (cellData.getValue().timestamp() != null) {
                    return new SimpleStringProperty(cellData.getValue().timestamp().format(DATE_FMT));
                }
                return new SimpleStringProperty("");
            });
        }
        if (colHistItemCode != null) {
            colHistItemCode.setCellValueFactory(new PropertyValueFactory<>("itemCode"));
        }
        if (colHistOldPrice != null) {
            colHistOldPrice.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().oldPrice())));
        }
        if (colHistNewPrice != null) {
            colHistNewPrice.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().newPrice())));
        }
        if (colHistChangedBy != null) {
            colHistChangedBy.setCellValueFactory(new PropertyValueFactory<>("changedBy"));
        }

        if (tablePriceHistory != null) {
            tablePriceHistory.setItems(priceHistories);
        }
    }

    private void setupSearchFilter() {
        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, oldVal, newVal) -> filterCatalog());
        }
    }

    private void setupNewPriceListener() {
        if (txtNewPrice != null) {
            txtNewPrice.textProperty().addListener((obs, oldVal, newVal) -> updateProposedMargin());
        }
    }

    public void loadData() {
        try {
            fullCatalog = inventoryService.getAllItems();
            filterCatalog();

            List<PriceHistory> history = priceService.getAllPriceHistories();
            priceHistories.setAll(history);

            if (selectedItem != null) {
                // Refresh selected item from catalog
                for (Item it : fullCatalog) {
                    if (it.code().equals(selectedItem.code())) {
                        selectItem(it);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            setStatus("Error loading price management data: " + e.getMessage(), true);
        }
    }

    private void filterCatalog() {
        String query = txtSearch != null && txtSearch.getText() != null
                ? txtSearch.getText().trim().toLowerCase() : "";

        if (query.isEmpty()) {
            displayedCatalog.setAll(fullCatalog);
        } else {
            List<Item> filtered = new ArrayList<>();
            for (Item item : fullCatalog) {
                if (item.code().toLowerCase().contains(query)
                        || item.name().toLowerCase().contains(query)
                        || (item.category() != null && item.category().toLowerCase().contains(query))) {
                    filtered.add(item);
                }
            }
            displayedCatalog.setAll(filtered);
        }
    }

    public void selectItem(Item item) {
        this.selectedItem = item;
        if (item == null) {
            if (lblSelectedItemCode != null) lblSelectedItemCode.setText("None selected");
            if (lblSelectedItemName != null) lblSelectedItemName.setText("—");
            if (lblCurrentCost != null) lblCurrentCost.setText("—");
            if (lblCurrentPrice != null) lblCurrentPrice.setText("—");
            if (lblCurrentMargin != null) lblCurrentMargin.setText("—");
            if (txtNewPrice != null) txtNewPrice.clear();
            if (lblProposedMargin != null) lblProposedMargin.setText("—");
            if (lblPriceWarning != null) lblPriceWarning.setText("");
            return;
        }

        if (lblSelectedItemCode != null) lblSelectedItemCode.setText(item.code());
        if (lblSelectedItemName != null) lblSelectedItemName.setText(item.name());
        if (lblCurrentCost != null) lblCurrentCost.setText(formatCurrency(item.costPrice()));
        if (lblCurrentPrice != null) lblCurrentPrice.setText(formatCurrency(item.currentSellingPrice()));

        double margin = computeMargin(item.costPrice(), item.currentSellingPrice());
        if (lblCurrentMargin != null) {
            lblCurrentMargin.setText(String.format("%.1f%%", margin));
        }

        updateProposedMargin();
    }

    private void updateProposedMargin() {
        if (selectedItem == null || txtNewPrice == null || txtNewPrice.getText() == null || txtNewPrice.getText().isBlank()) {
            if (lblProposedMargin != null) lblProposedMargin.setText("—");
            if (lblPriceWarning != null) lblPriceWarning.setText("");
            return;
        }

        try {
            String clean = txtNewPrice.getText().trim().replace("₹", "").replace(",", "");
            BigDecimal proposed = new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);

            if (proposed.compareTo(BigDecimal.ZERO) <= 0) {
                if (lblProposedMargin != null) lblProposedMargin.setText("—");
                if (lblPriceWarning != null) {
                    lblPriceWarning.setText("Selling price must be strictly positive.");
                    lblPriceWarning.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                }
                return;
            }

            double proposedMargin = computeMargin(selectedItem.costPrice(), proposed);
            if (lblProposedMargin != null) {
                lblProposedMargin.setText(String.format("%.2f%%", proposedMargin));
            }

            if (lblPriceWarning != null) {
                if (proposed.compareTo(selectedItem.costPrice()) < 0) {
                    lblPriceWarning.setText(String.format("⚠️ WARNING: New price (₹%.2f) is BELOW cost price (₹%.2f)!",
                            proposed.doubleValue(), selectedItem.costPrice().doubleValue()));
                    lblPriceWarning.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                } else {
                    lblPriceWarning.setText("✓ Price is above cost. Healthy profit margin.");
                    lblPriceWarning.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
                }
            }
        } catch (NumberFormatException e) {
            if (lblProposedMargin != null) lblProposedMargin.setText("—");
            if (lblPriceWarning != null) {
                lblPriceWarning.setText("Invalid price format.");
                lblPriceWarning.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            }
        }
    }

    @FXML
    public void handleUpdatePrice(ActionEvent event) {
        if (selectedItem == null) {
            setStatus("Please select an item from the table first.", true);
            return;
        }

        String priceStr = txtNewPrice != null ? txtNewPrice.getText() : null;
        if (priceStr == null || priceStr.isBlank()) {
            setStatus("Please enter a new selling price.", true);
            return;
        }

        BigDecimal newPrice;
        try {
            String clean = priceStr.trim().replace("₹", "").replace(",", "");
            newPrice = new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);
            if (newPrice.compareTo(BigDecimal.ZERO) <= 0) {
                setStatus("New selling price must be strictly positive.", true);
                return;
            }
        } catch (NumberFormatException e) {
            setStatus("Invalid price format. Please enter a valid decimal number.", true);
            return;
        }

        String manager = txtManagerName != null && !txtManagerName.getText().isBlank()
                ? txtManagerName.getText().trim() : "Manager";

        try {
            BigDecimal oldPrice = selectedItem.currentSellingPrice();
            priceService.updateSellingPrice(selectedItem.code(), newPrice, manager);

            setStatus(String.format("Price updated for [%s] %s: ₹%.2f ➔ ₹%.2f.",
                    selectedItem.code(), selectedItem.name(), oldPrice.doubleValue(), newPrice.doubleValue()), false);

            if (txtNewPrice != null) txtNewPrice.clear();
            loadData();

            if (onPriceUpdatedListener != null) {
                onPriceUpdatedListener.run();
            }
        } catch (ValidationException e) {
            setStatus("Validation error: " + e.getMessage(), true);
        } catch (Exception e) {
            setStatus("Price update failed: " + e.getMessage(), true);
        }
    }

    private double computeMargin(BigDecimal cost, BigDecimal selling) {
        if (selling == null || selling.compareTo(BigDecimal.ZERO) == 0 || cost == null) {
            return 0.0;
        }
        BigDecimal profit = selling.subtract(cost);
        return profit.divide(selling, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String formatCurrency(BigDecimal amount) {
        return "₹" + Objects.requireNonNullElse(amount, BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private void setStatus(String message, boolean isError) {
        if (lblPriceStatus != null) {
            lblPriceStatus.setText(message);
            if (isError) {
                lblPriceStatus.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblPriceStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }

    // Getters for testing
    public ObservableList<Item> getDisplayedCatalog() {
        return displayedCatalog;
    }

    public ObservableList<PriceHistory> getPriceHistories() {
        return priceHistories;
    }

    public Item getSelectedItem() {
        return selectedItem;
    }

    public TextField getTxtNewPrice() {
        return txtNewPrice;
    }

    public TextField getTxtManagerName() {
        return txtManagerName;
    }

    public Label getLblProposedMargin() {
        return lblProposedMargin;
    }

    public Label getLblPriceWarning() {
        return lblPriceWarning;
    }

    public void setOnPriceUpdatedListener(Runnable listener) {
        this.onPriceUpdatedListener = listener;
    }
}
