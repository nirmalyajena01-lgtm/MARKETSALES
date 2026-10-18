package com.supermarket.ui.controllers;

import com.supermarket.domain.Item;
import com.supermarket.service.InventoryService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InventoryController {
    private static final Logger LOGGER = Logger.getLogger(InventoryController.class.getName());

    @FXML
    private TextField txtSearch;

    @FXML
    private ComboBox<String> cmbCategory;

    @FXML
    private CheckBox chkLowStockOnly;

    @FXML
    private Button btnRefresh;

    @FXML
    private Label lblTotalItems;

    @FXML
    private Label lblLowStockCount;

    @FXML
    private TableView<Item> tableInventory;

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
    private TableColumn<Item, Integer> colCurrentStock;

    @FXML
    private TableColumn<Item, Integer> colMinThreshold;

    @FXML
    private TableColumn<Item, String> colStockStatus;

    private final InventoryService inventoryService;
    private final ObservableList<Item> displayedItems = FXCollections.observableArrayList();
    private List<Item> fullCatalog = new ArrayList<>();

    public InventoryController() {
        this(new InventoryService());
    }

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "InventoryService cannot be null");
    }

    @FXML
    public void initialize() {
        setupTableColumns();
        setupFilters();
        loadCatalog();
    }

    private void setupTableColumns() {
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
        if (colCurrentStock != null) {
            colCurrentStock.setCellValueFactory(new PropertyValueFactory<>("currentStock"));
        }
        if (colMinThreshold != null) {
            colMinThreshold.setCellValueFactory(new PropertyValueFactory<>("minStockThreshold"));
        }
        if (colStockStatus != null) {
            setupStatusColumn();
        }

        if (tableInventory != null) {
            tableInventory.setItems(displayedItems);
        }
    }

    private void setupStatusColumn() {
        colStockStatus.setCellValueFactory(cellData -> {
            Item item = cellData.getValue();
            if (item.currentStock() <= 0) {
                return new SimpleStringProperty("OUT OF STOCK");
            } else if (item.isLowStock()) {
                return new SimpleStringProperty("LOW STOCK");
            } else {
                return new SimpleStringProperty("IN STOCK");
            }
        });

        colStockStatus.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();

            {
                badge.setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    badge.setText(status);
                    badge.getStyleClass().removeAll("badge-success", "badge-warning", "badge-danger");
                    if ("OUT OF STOCK".equals(status)) {
                        badge.getStyleClass().add("badge-danger");
                    } else if ("LOW STOCK".equals(status)) {
                        badge.getStyleClass().add("badge-warning");
                    } else {
                        badge.getStyleClass().add("badge-success");
                    }
                    setGraphic(badge);
                    setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                }
            }
        });
    }

    private void setupFilters() {
        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        }
        if (cmbCategory != null) {
            cmbCategory.valueProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        }
        if (chkLowStockOnly != null) {
            chkLowStockOnly.selectedProperty().addListener((obs, oldVal, newVal) -> applyFilter());
        }
    }

    /**
     * Loads the catalog from the database, updates category choices, and recalculates summaries.
     */
    public void loadCatalog() {
        try {
            fullCatalog = inventoryService.getAllItems();
            updateCategoryChoices();
            applyFilter();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load inventory catalog", e);
            fullCatalog = Collections.emptyList();
            displayedItems.clear();
        }
    }

    private void updateCategoryChoices() {
        if (cmbCategory == null) return;
        String currentSelection = cmbCategory.getValue();

        Set<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Item item : fullCatalog) {
            if (item.category() != null && !item.category().isBlank()) {
                categories.add(item.category());
            }
        }

        ObservableList<String> options = FXCollections.observableArrayList();
        options.add("All Categories");
        options.addAll(categories);

        cmbCategory.setItems(options);
        if (currentSelection != null && options.contains(currentSelection)) {
            cmbCategory.setValue(currentSelection);
        } else {
            cmbCategory.setValue("All Categories");
        }
    }

    /**
     * Filters the catalog list according to search query, selected category, and low stock toggle.
     */
    public void applyFilter() {
        String query = txtSearch != null && txtSearch.getText() != null
                ? txtSearch.getText().trim().toLowerCase() : "";
        String category = cmbCategory != null && cmbCategory.getValue() != null
                ? cmbCategory.getValue() : "All Categories";
        boolean lowStockOnly = chkLowStockOnly != null && chkLowStockOnly.isSelected();

        List<Item> filtered = new ArrayList<>();
        int lowStockCount = 0;

        for (Item item : fullCatalog) {
            if (item.isLowStock() || item.currentStock() <= 0) {
                lowStockCount++;
            }

            boolean matchesSearch = query.isEmpty()
                    || item.code().toLowerCase().contains(query)
                    || item.name().toLowerCase().contains(query)
                    || (item.category() != null && item.category().toLowerCase().contains(query));

            boolean matchesCategory = "All Categories".equals(category)
                    || (item.category() != null && item.category().equalsIgnoreCase(category));

            boolean matchesLowStock = !lowStockOnly || (item.isLowStock() || item.currentStock() <= 0);

            if (matchesSearch && matchesCategory && matchesLowStock) {
                filtered.add(item);
            }
        }

        displayedItems.setAll(filtered);

        if (lblTotalItems != null) {
            lblTotalItems.setText("Total Products: " + fullCatalog.size() + " (" + filtered.size() + " showing)");
        }
        if (lblLowStockCount != null) {
            lblLowStockCount.setText("Low Stock Items: " + lowStockCount);
            if (lowStockCount > 0) {
                lblLowStockCount.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblLowStockCount.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }

    @FXML
    public void handleRefresh(ActionEvent event) {
        loadCatalog();
    }

    private String formatCurrency(BigDecimal amount) {
        return "₹" + Objects.requireNonNullElse(amount, BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    // Getters for testing
    public ObservableList<Item> getDisplayedItems() {
        return displayedItems;
    }

    public List<Item> getFullCatalog() {
        return fullCatalog;
    }

    public TextField getTxtSearch() {
        return txtSearch;
    }

    public ComboBox<String> getCmbCategory() {
        return cmbCategory;
    }

    public CheckBox getChkLowStockOnly() {
        return chkLowStockOnly;
    }

    public TableView<Item> getTableInventory() {
        return tableInventory;
    }
}
