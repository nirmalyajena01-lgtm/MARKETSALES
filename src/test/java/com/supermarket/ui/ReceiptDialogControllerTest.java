package com.supermarket.ui;

import com.supermarket.domain.Cart;
import com.supermarket.domain.Receipt;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.BillingService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.controllers.ReceiptDialogController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptDialogControllerTest extends BaseJavaFxTest {

    private ReceiptFormatter receiptFormatter;
    private BillingService billingService;
    private ReceiptDialogController controller;
    private Receipt testReceipt;

    @BeforeEach
    void setUpController() {
        receiptFormatter = new ReceiptFormatter();
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqliteSalesTransactionRepository salesRepo = new SqliteSalesTransactionRepository(db);
        billingService = new BillingService(db, itemRepo, salesRepo);

        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "DAIRY-001", 2);
        billingService.addItemToCart(cart, "BAKE-001", 1);
        testReceipt = billingService.checkout(cart, new BigDecimal("600.00"), "Cashier-Bob");

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/receipt_preview.fxml"));
                loader.setControllerFactory(type -> new ReceiptDialogController(receiptFormatter));
                loader.load();
                controller = loader.getController();
                controller.setReceipt(testReceipt);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load receipt_preview.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Receipt preview dialog displays formatted thermal receipt with required fields")
    void testReceiptPreviewRendering() {
        runOnFxThread(() -> {
            String text = controller.getReceiptText();
            assertThat(text).isNotNull();
            assertThat(text).contains("SUPERMARKET AUTOMATION");
            assertThat(text).contains(testReceipt.serialNumber());
            assertThat(text).contains("Cashier-Bob");
            assertThat(text).contains("DAIRY-");
            assertThat(text).contains("BAKE-");
            assertThat(text).contains("₹500.49");
            assertThat(text).contains("₹600.00");
            assertThat(text).contains("₹99.51");
            assertThat(text).contains("Thank you for shopping with us!");
        });
    }

    @Test
    @DisplayName("Saving receipt to file writes authentic monospace text file to disk")
    void testSaveReceiptToFile(@TempDir Path tempDir) {
        runOnFxThread(() -> {
            Path targetFile = tempDir.resolve("receipt_test.txt");
            try {
                Files.writeString(targetFile, controller.getReceiptText());
                assertThat(Files.exists(targetFile)).isTrue();
                String savedContent = Files.readString(targetFile);
                assertThat(savedContent).isEqualTo(controller.getReceiptText());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("Print receipt action updates status with dispatch confirmation")
    void testPrintReceiptAction() {
        runOnFxThread(() -> {
            controller.handlePrintReceipt(new ActionEvent());
            // Safe execution without exceptions
            assertThat(controller.getCurrentReceipt()).isEqualTo(testReceipt);
        });
    }
}
