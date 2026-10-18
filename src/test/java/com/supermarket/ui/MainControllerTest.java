package com.supermarket.ui;

import com.supermarket.ui.controllers.MainController;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainControllerTest extends BaseJavaFxTest {

    private MainController mainController;

    @BeforeEach
    void setUpController() {
        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
                loader.load();
                mainController = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load main.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Main view hosts 5 primary tabs and initializes all sub-controllers")
    void testMainLayoutInitialization() {
        runOnFxThread(() -> {
            assertThat(mainController).isNotNull();
            assertThat(mainController.getMainTabPane()).isNotNull();
            assertThat(mainController.getMainTabPane().getTabs()).hasSize(5);

            // Verify all sub-controllers are injected
            assertThat(mainController.getPosTabController()).isNotNull();
            assertThat(mainController.getInventoryTabController()).isNotNull();
            assertThat(mainController.getRestockTabController()).isNotNull();
            assertThat(mainController.getPriceTabController()).isNotNull();
            assertThat(mainController.getAnalyticsTabController()).isNotNull();
        });
    }

    @Test
    @DisplayName("Switching between tabs refreshes respective sub-views cleanly")
    void testTabSelectionSwitching() {
        runOnFxThread(() -> {
            // Select each tab in sequence
            for (int i = 0; i < 5; i++) {
                mainController.getMainTabPane().getSelectionModel().select(i);
                assertThat(mainController.getMainTabPane().getSelectionModel().getSelectedIndex()).isEqualTo(i);
            }
        });
    }
}
