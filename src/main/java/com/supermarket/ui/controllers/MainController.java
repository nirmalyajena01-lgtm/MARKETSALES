package com.supermarket.ui.controllers;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main application coordinator controller.
 * Hosts the primary TabPane, store branding header, and bottom status bar.
 * Coordinates inter-tab state synchronization when sales, restocks, or price updates occur.
 */
public class MainController {

    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML
    private TabPane mainTabPane;

    @FXML
    private Tab tabPos;

    @FXML
    private Tab tabInventory;

    @FXML
    private Tab tabRestock;

    @FXML
    private Tab tabPrice;

    @FXML
    private Tab tabAnalytics;

    // Header & Status bar labels
    @FXML
    private Label lblHeaderClock;

    @FXML
    private Label lblStatusDb;

    @FXML
    private Label lblStatusUser;

    @FXML
    private Label lblStatusClock;

    // Nested sub-tab controllers injected by JavaFX fx:include
    @FXML
    private PosController posTabController;

    @FXML
    private InventoryController inventoryTabController;

    @FXML
    private RestockController restockTabController;

    @FXML
    private PriceController priceTabController;

    @FXML
    private AnalyticsController analyticsTabController;

    private Timeline clockTimeline;

    @FXML
    public void initialize() {
        startClock();
        setupTabSelectionListener();
        setupInterTabListeners();
    }

    private void startClock() {
        updateClockDisplays();
        try {
            clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClockDisplays()));
            clockTimeline.setCycleCount(Animation.INDEFINITE);
            clockTimeline.play();
        } catch (Exception ignored) {
            // Safe in headless test environments
        }
    }

    private void updateClockDisplays() {
        String nowStr = LocalDateTime.now().format(CLOCK_FMT);
        if (lblHeaderClock != null) {
            lblHeaderClock.setText(nowStr);
        }
        if (lblStatusClock != null) {
            lblStatusClock.setText("System Time: " + nowStr);
        }
    }

    private void setupTabSelectionListener() {
        if (mainTabPane == null) return;

        mainTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;

            if (newTab == tabPos && posTabController != null) {
                posTabController.refreshProductLookup();
            } else if (newTab == tabInventory && inventoryTabController != null) {
                inventoryTabController.loadCatalog();
            } else if (newTab == tabRestock && restockTabController != null) {
                restockTabController.loadData();
            } else if (newTab == tabPrice && priceTabController != null) {
                priceTabController.loadData();
            } else if (newTab == tabAnalytics && analyticsTabController != null) {
                analyticsTabController.refreshAnalytics();
            }
        });
    }

    private void setupInterTabListeners() {
        // When POS completes a sale: refresh inventory catalog & analytics
        if (posTabController != null) {
            posTabController.setOnSaleCompletedListener(() -> {
                if (inventoryTabController != null) {
                    inventoryTabController.loadCatalog();
                }
                if (analyticsTabController != null) {
                    analyticsTabController.refreshAnalytics();
                }
            });
        }

        // When staff restocks an item: refresh inventory catalog & POS product lookup
        if (restockTabController != null) {
            restockTabController.setOnRestockCompletedListener(() -> {
                if (inventoryTabController != null) {
                    inventoryTabController.loadCatalog();
                }
                if (posTabController != null) {
                    posTabController.refreshProductLookup();
                }
            });
        }

        // When manager updates selling price: refresh catalog & POS product lookup
        if (priceTabController != null) {
            priceTabController.setOnPriceUpdatedListener(() -> {
                if (inventoryTabController != null) {
                    inventoryTabController.loadCatalog();
                }
                if (posTabController != null) {
                    posTabController.refreshProductLookup();
                }
            });
        }
    }

    // Getters for testing and programmatic coordination
    public TabPane getMainTabPane() {
        return mainTabPane;
    }

    public PosController getPosTabController() {
        return posTabController;
    }

    public InventoryController getInventoryTabController() {
        return inventoryTabController;
    }

    public RestockController getRestockTabController() {
        return restockTabController;
    }

    public PriceController getPriceTabController() {
        return priceTabController;
    }

    public AnalyticsController getAnalyticsTabController() {
        return analyticsTabController;
    }
}
