package com.supermarket;

import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Main JavaFX Application entry point for Supermarket Automation Software (SAS).
 * Initializes SQLite database persistence and schema migrations, loads primary FXML layout
 * and enterprise CSS styling, and configures the desktop primary window stage.
 */
public class SupermarketApp extends Application {

    public static final String APP_TITLE = "Supermarket Automation Software (SAS)";
    public static final double DEFAULT_WIDTH = 1200.0;
    public static final double DEFAULT_HEIGHT = 800.0;
    public static final double MIN_WIDTH = 1024.0;
    public static final double MIN_HEIGHT = 720.0;

    @Override
    public void init() throws Exception {
        super.init();
        // Initialize SQLite persistence engine and execute embedded schema migrations
        DatabaseManager db = DatabaseManager.getInstance();
        MigrationRunner.migrate(db);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        try {
            scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        } catch (Exception ignored) {
            // CSS fallback
        }

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
