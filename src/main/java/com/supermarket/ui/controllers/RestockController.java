package com.supermarket.ui.controllers;

import com.supermarket.domain.Item;
import com.supermarket.domain.RestockAudit;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.ValidationException;
import com.supermarket.service.InventoryService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.StringConverter;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Controller for the Employee Restock Management tab.
 * Implements Requirement R2: recording inbound supply shipments, atomic inventory increment,
 * and immutable restock audit logging.
 */
public class RestockController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML
    private ComboBox<Item> cmbItemSelect;

    @FXML
    private Label lblCurrentStock;

    @FXML
    private TextField txtQuantity;

    @FXML
    private TextField txtSupplierNotes;

    @FXML
    private TextField txtOperator;

    @FXML
    private Button btnSubmitRestock;

    @FXML
    private Label lblRestockStatus;

    @FXML
    private TableView<RestockAudit> tableAuditLog;

    @FXML
    private TableColumn<RestockAudit, String> colAuditTimestamp;

    @FXML
    private TableColumn<RestockAudit, String> colAuditItemCode;

    @FXML
    private TableColumn<RestockAudit, Integer> colAuditQtyAdded;

    @FXML
    private TableColumn<RestockAudit, Integer> colAuditPrevStock;

    @FXML
    private TableColumn<RestockAudit, Integer> colAuditNewStock;

    @FXML
    private TableColumn<RestockAudit, String> colAuditOperator;

    @FXML
    private TableColumn<RestockAudit, String> colAuditNotes;

    private final InventoryService inventoryService;
    private final ObservableList<RestockAudit> auditLogs = FXCollections.observableArrayList();
    private final ObservableList<Item> catalogItems = FXCollections.observableArrayList();
    private Runnable onRestockCompletedListener;

    public RestockController() {
        this(new InventoryService());
    }

    public RestockController(InventoryService inventoryService) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "InventoryService cannot be null");
    }

    @FXML
    public void initialize() {
        setupTableColumns();
        setupItemSelector();
        loadData();
    }

    private void setupTableColumns() {
        if (colAuditTimestamp != null) {
            colAuditTimestamp.setCellValueFactory(cellData -> {
                if (cellData.getValue().timestamp() != null) {
                    return new SimpleStringProperty(cellData.getValue().timestamp().format(DATE_FMT));
                }
                return new SimpleStringProperty("");
            });
        }
        if (colAuditItemCode != null) {
            colAuditItemCode.setCellValueFactory(new PropertyValueFactory<>("itemCode"));
        }
        if (colAuditQtyAdded != null) {
            colAuditQtyAdded.setCellValueFactory(new PropertyValueFactory<>("quantityAdded"));
        }
        if (colAuditPrevStock != null) {
            colAuditPrevStock.setCellValueFactory(new PropertyValueFactory<>("previousStock"));
        }
        if (colAuditNewStock != null) {
            colAuditNewStock.setCellValueFactory(new PropertyValueFactory<>("newStock"));
        }
        if (colAuditOperator != null) {
            colAuditOperator.setCellValueFactory(new PropertyValueFactory<>("performedBy"));
        }
        if (colAuditNotes != null) {
            colAuditNotes.setCellValueFactory(new PropertyValueFactory<>("batchNotes"));
        }

        if (tableAuditLog != null) {
            tableAuditLog.setItems(auditLogs);
        }
    }

    private void setupItemSelector() {
        if (cmbItemSelect == null) return;

        cmbItemSelect.setConverter(new StringConverter<>() {
            @Override
            public String toString(Item item) {
                if (item == null) return "";
                return String.format("[%s] %s (Stock: %d)", item.code(), item.name(), item.currentStock());
            }

            @Override
            public Item fromString(String string) {
                return null;
            }
        });

        cmbItemSelect.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("[%s] %s (Stock: %d | Threshold: %d)",
                            item.code(), item.name(), item.currentStock(), item.minStockThreshold()));
                }
            }
        });

        cmbItemSelect.setOnAction(event -> {
            Item selected = cmbItemSelect.getSelectionModel().getSelectedItem();
            if (selected != null && lblCurrentStock != null) {
                lblCurrentStock.setText(String.format("Current Stock: %d units (Min Threshold: %d)",
                        selected.currentStock(), selected.minStockThreshold()));
            } else if (lblCurrentStock != null) {
                lblCurrentStock.setText("Current Stock: —");
            }
        });
    }

    /**
     * Loads catalog items and recent restock audit log entries from the database.
     */
    public void loadData() {
        try {
            List<Item> items = inventoryService.getAllItems();
            catalogItems.setAll(items);
            if (cmbItemSelect != null) {
                Item prevSelected = cmbItemSelect.getValue();
                cmbItemSelect.setItems(catalogItems);
                if (prevSelected != null) {
                    for (Item it : items) {
                        if (it.code().equals(prevSelected.code())) {
                            cmbItemSelect.setValue(it);
                            break;
                        }
                    }
                }
            }

            List<RestockAudit> history = inventoryService.getRestockAuditHistory();
            auditLogs.setAll(history);
        } catch (Exception e) {
            setStatus("Error loading restock data: " + e.getMessage(), true);
        }
    }

    @FXML
    public void handleSubmitRestock(ActionEvent event) {
        Item selected = cmbItemSelect != null ? cmbItemSelect.getValue() : null;
        if (selected == null) {
            setStatus("Please select an item to restock.", true);
            return;
        }

        String qtyText = txtQuantity != null ? txtQuantity.getText() : null;
        if (qtyText == null || qtyText.isBlank()) {
            setStatus("Please enter quantity to add.", true);
            return;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(qtyText.trim());
            if (quantity <= 0) {
                setStatus("Restock quantity must be greater than zero.", true);
                return;
            }
        } catch (NumberFormatException e) {
            setStatus("Invalid quantity format. Enter a positive integer.", true);
            return;
        }

        String operator = txtOperator != null && !txtOperator.getText().isBlank()
                ? txtOperator.getText().trim() : "Staff";
        String notes = txtSupplierNotes != null ? txtSupplierNotes.getText().trim() : "";

        try {
            inventoryService.restockItem(selected.code(), quantity, notes, operator);

            setStatus(String.format("Restock recorded: +%d units added to [%s] %s.",
                    quantity, selected.code(), selected.name()), false);

            if (txtQuantity != null) txtQuantity.clear();
            if (txtSupplierNotes != null) txtSupplierNotes.clear();

            loadData();

            if (onRestockCompletedListener != null) {
                onRestockCompletedListener.run();
            }
        } catch (ValidationException | ItemNotFoundException e) {
            setStatus("Validation error: " + e.getMessage(), true);
        } catch (Exception e) {
            setStatus("Restock transaction failed: " + e.getMessage(), true);
        }
    }

    private void setStatus(String message, boolean isError) {
        if (lblRestockStatus != null) {
            lblRestockStatus.setText(message);
            if (isError) {
                lblRestockStatus.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblRestockStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }

    // Getters for testing
    public ObservableList<RestockAudit> getAuditLogs() {
        return auditLogs;
    }

    public ObservableList<Item> getCatalogItems() {
        return catalogItems;
    }

    public ComboBox<Item> getCmbItemSelect() {
        return cmbItemSelect;
    }

    public TextField getTxtQuantity() {
        return txtQuantity;
    }

    public TextField getTxtSupplierNotes() {
        return txtSupplierNotes;
    }

    public TextField getTxtOperator() {
        return txtOperator;
    }

    public void setOnRestockCompletedListener(Runnable listener) {
        this.onRestockCompletedListener = listener;
    }
}
