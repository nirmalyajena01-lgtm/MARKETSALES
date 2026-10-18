package com.supermarket.ui.controllers;

import com.supermarket.domain.Receipt;
import com.supermarket.service.ReceiptFormatter;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Controller for the modal thermal-style receipt preview dialog.
 * Renders the monospace 40-column receipt and provides print, file save, and close actions.
 */
public class ReceiptDialogController {

    @FXML
    private Label lblReceiptTitle;

    @FXML
    private TextArea txtReceiptContent;

    @FXML
    private Button btnPrintReceipt;

    @FXML
    private Button btnSaveToFile;

    @FXML
    private Button btnClose;

    @FXML
    private Label lblDialogStatus;

    private final ReceiptFormatter receiptFormatter;
    private Receipt currentReceipt;
    private String receiptText;

    public ReceiptDialogController() {
        this(new ReceiptFormatter());
    }

    public ReceiptDialogController(ReceiptFormatter receiptFormatter) {
        this.receiptFormatter = Objects.requireNonNull(receiptFormatter, "ReceiptFormatter cannot be null");
    }

    @FXML
    public void initialize() {
        if (txtReceiptContent != null) {
            txtReceiptContent.setEditable(false);
        }
    }

    /**
     * Injects the completed sales receipt domain object and formats it for thermal display.
     */
    public void setReceipt(Receipt receipt) {
        this.currentReceipt = receipt;
        if (receipt != null) {
            this.receiptText = receiptFormatter.formatThermalReceipt(receipt);
            if (lblReceiptTitle != null) {
                lblReceiptTitle.setText("Thermal Receipt Preview: " + receipt.serialNumber());
            }
        } else {
            this.receiptText = "";
            if (lblReceiptTitle != null) {
                lblReceiptTitle.setText("Thermal Receipt Preview");
            }
        }
        if (txtReceiptContent != null) {
            txtReceiptContent.setText(this.receiptText);
        }
    }

    /**
     * Injects direct receipt text.
     */
    public void setReceiptText(String text) {
        this.receiptText = text != null ? text : "";
        if (txtReceiptContent != null) {
            txtReceiptContent.setText(this.receiptText);
        }
    }

    public String getReceiptText() {
        return receiptText != null ? receiptText : (txtReceiptContent != null ? txtReceiptContent.getText() : "");
    }

    public Receipt getCurrentReceipt() {
        return currentReceipt;
    }

    @FXML
    public void handlePrintReceipt(ActionEvent event) {
        // Simulates thermal printer spooling
        String serial = currentReceipt != null ? currentReceipt.serialNumber() : "DIRECT";
        setStatus("Receipt [" + serial + "] dispatched to thermal printer (ESC/POS 40-col).", false);
    }

    @FXML
    public void handleSaveToFile(ActionEvent event) {
        String content = getReceiptText();
        if (content == null || content.isBlank()) {
            setStatus("No receipt content to save.", true);
            return;
        }

        String defaultFileName = "receipt_" +
                (currentReceipt != null ? currentReceipt.serialNumber() : "output") + ".txt";

        File targetFile = null;
        if (btnClose != null && btnClose.getScene() != null && btnClose.getScene().getWindow() != null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save Receipt As");
            chooser.setInitialFileName(defaultFileName);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files (*.txt)", "*.txt"));
            targetFile = chooser.showSaveDialog(btnClose.getScene().getWindow());
        }

        if (targetFile == null) {
            // Fallback to local receipts directory
            try {
                Path receiptsDir = Paths.get("receipts");
                if (!Files.exists(receiptsDir)) {
                    Files.createDirectories(receiptsDir);
                }
                targetFile = receiptsDir.resolve(defaultFileName).toFile();
            } catch (IOException e) {
                setStatus("Error preparing receipts directory: " + e.getMessage(), true);
                return;
            }
        }

        try {
            Files.writeString(targetFile.toPath(), content);
            setStatus("Receipt saved successfully: " + targetFile.getAbsolutePath(), false);
        } catch (IOException e) {
            setStatus("Failed to save receipt: " + e.getMessage(), true);
        }
    }

    @FXML
    public void handleClose(ActionEvent event) {
        if (btnClose != null && btnClose.getScene() != null && btnClose.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    private void setStatus(String message, boolean isError) {
        if (lblDialogStatus != null) {
            lblDialogStatus.setText(message);
            if (isError) {
                lblDialogStatus.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblDialogStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }
}
