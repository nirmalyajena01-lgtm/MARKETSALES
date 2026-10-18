package com.supermarket.ui;

import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Base test class for headless JavaFX controller unit and integration tests.
 * Initializes the JavaFX runtime toolkit and creates an isolated in-memory SQLite database.
 */
public abstract class BaseJavaFxTest {

    protected DatabaseManager db;

    @BeforeAll
    public static void initJavaFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit already initialized
        }
    }

    @BeforeEach
    public void setUpDatabase() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);
    }

    @AfterEach
    public void tearDownDatabase() {
        if (db != null) {
            db.close();
        }
    }

    /**
     * Helper to run an action synchronously on the JavaFX Application Thread if required.
     */
    protected void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];

        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });

        try {
            boolean done = latch.await(5, TimeUnit.SECONDS);
            if (!done) {
                throw new RuntimeException("FX thread execution timed out");
            }
            if (error[0] != null) {
                if (error[0] instanceof RuntimeException re) throw re;
                throw new RuntimeException(error[0]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
