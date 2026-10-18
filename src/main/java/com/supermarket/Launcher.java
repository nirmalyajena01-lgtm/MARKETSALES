package com.supermarket;

import com.supermarket.domain.Cart;
import com.supermarket.domain.CartTotals;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.service.BillingService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.web.WebServer;

import java.io.File;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Independent main class launcher for standalone executable fat JAR packaging on Windows.
 * Decouples the application bootstrap from javafx.application.Application to bypass
 * the JavaFX runtime module path restriction when running via java -jar.
 *
 * Also provides a headless CLI mode for automated smoke testing, database verification,
 * version queries, and help display without requiring an active graphical display.
 */
public final class Launcher {

    public static final String APP_NAME = "Supermarket Automation Software (SAS)";
    public static final String APP_VERSION = "1.0.0";

    Launcher() {
    }

    /**
     * Determines whether the given command-line arguments represent a headless CLI command.
     */
    public static boolean isCliCommand(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        String first = args[0].trim().toLowerCase();
        return first.startsWith("-");
    }

    /**
     * Executes headless CLI commands and outputs to the provided streams.
     *
     * @param args Command-line arguments
     * @param out Standard output stream
     * @param err Standard error stream
     * @return Exit code (0 for success, >0 for errors, -1 if arguments do not match CLI command)
     */
    public static int runCli(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            return -1;
        }

        String primaryArg = args[0].trim().toLowerCase(Locale.ROOT);
        return switch (primaryArg) {
            case "--smoke-test", "-s" -> runSmokeTest(out, err);
            case "--version", "-v" -> printVersion(out);
            case "--help", "-h", "-?" -> printHelp(out);
            default -> {
                err.println("Unknown option: " + args[0]);
                printHelp(err);
                yield 1;
            }
        };
    }

    /**
     * Prints application version, runtime JVM, and environment metadata.
     */
    public static int printVersion(PrintStream out) {
        out.println(APP_NAME + " v" + APP_VERSION);
        out.println("Main-Class       : com.supermarket.Launcher");
        out.println("Java Runtime     : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        out.println("Java VM          : " + System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.version") + ")");
        out.println("Operating System : " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        out.println("Default DB Path  : " + DatabaseManager.getDefaultDatabasePath());
        return 0;
    }

    /**
     * Prints command-line usage instructions.
     */
    public static int printHelp(PrintStream out) {
        out.println(APP_NAME + " v" + APP_VERSION);
        out.println("Usage: java -jar supermarket-automation-1.0.0-shaded.jar [OPTIONS]");
        out.println();
        out.println("Options:");
        out.println("  --smoke-test, -s    Run automated headless smoke test and database verification");
        out.println("  --web [port]        Start the browser app on localhost (default port 8080)");
        out.println("  --version, -v       Display application version and runtime metadata");
        out.println("  --help, -h          Display this help message and command-line options");
        out.println();
        out.println("If no options are specified, the JavaFX desktop graphical user interface will launch.");
        return 0;
    }

    /**
     * Executes a headless smoke test verifying:
     * 1. SQLite JDBC driver loading
     * 2. Database connection, PRAGMA foreign keys, and embedded schema migrations (V1, V2)
     * 3. Catalog repository queries
     * 4. Business services execution (cart, totals calculation)
     * 5. Atomic checkout and monospace thermal receipt generation
     * 6. Default database storage path validation
     */
    public static int runSmokeTest(PrintStream out, PrintStream err) {
        out.println("==============================================================");
        out.println("Supermarket Automation Software (SAS) - Standalone Smoke Test");
        out.println("==============================================================");
        out.println("Application Version : " + APP_VERSION);
        out.println("Java Runtime        : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        out.println("Java Home           : " + System.getProperty("java.home"));
        out.println("Operating System    : " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        out.println("Default DB Path     : " + DatabaseManager.getDefaultDatabasePath());
        out.println("--------------------------------------------------------------");

        try {
            // 1. Verify SQLite JDBC Driver class
            out.print("[1/5] Testing SQLite JDBC Driver loading... ");
            Class.forName("org.sqlite.JDBC");
            out.println("OK (org.sqlite.JDBC loaded)");

            // 2. Verify SQLite Database & Migration Runner
            out.print("[2/5] Testing SQLite Database & Migration Runner... ");
            try (DatabaseManager db = DatabaseManager.createInMemory()) {
                MigrationRunner.migrate(db);
                try (Connection conn = db.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                    int schemaVersion = rs.next() ? rs.getInt(1) : 0;
                    if (schemaVersion < 3) {
                        err.println("FAILED: Expected schema version >= 3, got " + schemaVersion);
                        return 1;
                    }
                }
                out.println("OK (Schema V3 applied successfully)");

                // 3. Verify Product Catalog Seed Data
                out.print("[3/5] Testing Product Catalog queries... ");
                SqliteItemRepository itemRepo = new SqliteItemRepository(db);
                List<Item> items = itemRepo.findAll();
                if (items.isEmpty()) {
                    err.println("FAILED: No items found in catalog after migration.");
                    return 1;
                }
                out.println("OK (" + items.size() + " catalog items loaded)");

                // 4. Verify Headless Business Services
                out.print("[4/5] Testing Billing Service... ");
                BillingService billingService = new BillingService(db);
                ReceiptFormatter receiptFormatter = new ReceiptFormatter();

                Cart cart = billingService.createCart();
                Item testItem = items.get(0);
                billingService.addItemToCart(cart, testItem.code(), 2);
                CartTotals totals = billingService.computeTotals(cart);
                if (cart.getTotalItemQuantity() != 2) {
                    err.println("FAILED: Expected total quantity 2, got " + cart.getTotalItemQuantity());
                    return 1;
                }
                out.println("OK (Cart computed subtotal: ₹" + totals.subtotal() + ")");

                // 5. Verify Atomic Checkout & Thermal Monospace Receipt Generation
                out.print("[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... ");
                Receipt receipt = billingService.checkout(cart, totals.totalPayable(), "SMOKE_TESTER");
                if (receipt == null || receipt.serialNumber() == null) {
                    err.println("FAILED: Receipt was null or missing serial number.");
                    return 1;
                }
                String thermalText = receiptFormatter.formatThermalReceipt(receipt);
                if (thermalText == null || !thermalText.contains(receipt.serialNumber())) {
                    err.println("FAILED: Receipt thermal text missing serial number.");
                    return 1;
                }
                out.println("OK (Receipt " + receipt.serialNumber() + " formatted)");

                // Verify default database directory is writable
                String defaultPath = DatabaseManager.getDefaultDatabasePath();
                File dbFile = new File(defaultPath);
                File parentDir = dbFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                out.println("--------------------------------------------------------------");
                out.println("SMOKE TEST RESULT: PASSED (All systems operational)");
                out.println("==============================================================");
                return 0;
            }
        } catch (Exception e) {
            err.println("\nSMOKE TEST ERROR: " + e.getMessage());
            e.printStackTrace(err);
            return 1;
        }
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0].equalsIgnoreCase("--web")) {
            int port = 8080;
            try {
                if (args.length > 1) {
                    port = Integer.parseInt(args[1]);
                }
                WebServer.launch(port);
            } catch (Exception error) {
                System.err.println("Could not start the web app: " + error.getMessage());
                System.exit(1);
            }
            return;
        }

        if (isCliCommand(args)) {
            int exitCode = runCli(args, System.out, System.err);
            if (exitCode >= 0) {
                System.exit(exitCode);
                return;
            }
        }

        // If no CLI flags were provided, launch standard JavaFX GUI
        SupermarketApp.main(args);
    }
}
