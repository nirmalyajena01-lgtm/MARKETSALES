# Supermarket Automation Software (SAS)
A desktop Point of Sale (POS) and inventory management system built with **Java 25**, **JavaFX 27**, **SQLite (JDBC)**, and **Maven**.

---

## 📌 Features & Strict Assignment Requirement Coverage

| Requirement | Assignment Specification | Implementation Details |
|---|---|---|
| **1. Print Bill** | Print bill containing serial number, item name, code, quantity, unit price, item price, and total amount payable. | `BillingService.checkout()` creates atomic transaction → `ReceiptFormatter` formats 40-column thermal receipt → `ReceiptDialogController` provides modal preview with print / export actions. |
| **2. Inventory Management** | Query inventory details, auto-decrement on sale, and support employee restock when new supply arrives. | `InventoryService` with real-time stock status badges (In Stock, Low Stock, Out of Stock) + dedicated `RestockController` dialog logging supplier details to `restock_audits`. |
| **3. Sales Statistics** | Print sales statistics for any item for any particular day or period, showing quantity sold, price realised, and **profit**. | `AnalyticsService` calculates:$$\text{Profit} = \sum (\text{unit\_price\_snapshot} - \text{unit\_cost\_snapshot}) \times \text{qty}$$Aggregates revenue, COGS, and net profit over any date range. |
| **4. Manager Dynamic Pricing** | Manager changes selling price day-to-day without altering historical sales profit. | `PriceService` updates catalog price in `items` table. Past transactions in `receipt_line_items` retain immutable price snapshots (`unit_cost_snapshot`, `unit_price_snapshot`). |

---

## 🏛️ Architectural Design Highlights

1. **Transactional Integrity**: All checkout operations (stock validation, atomic decrement, sequential serial generation, and receipt persistence) execute within a single atomic SQLite transaction with full rollback on failure.
2. **Mathematical Invariance (Price Snapshotting)**: Sales statistics and historical bills read exclusively from immutable snapshot columns in `receipt_line_items`. Subsequent catalog price modifications never distort past profit figures.
3. **Precision Currency Math**: Zero floating-point rounding errors. All prices, totals, taxes, and margins use `java.math.BigDecimal` with `RoundingMode.HALF_UP`.
4. **Inter-Tab Reactive Synchronization**: POS sales immediately reflect in decremented inventory counts and refreshed analytics KPIs across the UI tabs.

---

## 🗂️ Project Structure

```
supermarket-automation/
├── pom.xml                                ← Maven project definition (Java 25 + JavaFX 27)
├── mvnw.cmd                               ← Standalone Maven wrapper
├── run.bat                                ← 1-click Windows desktop launcher
├── src/main/java/com/supermarket/
│   ├── Launcher.java                      ← Bootstrap entry point
│   ├── SupermarketApp.java                ← JavaFX Application class
│   ├── domain/                            ← Immutable records (Item, Cart, Receipt, etc.)
│   ├── persistence/                       ← DatabaseManager (SQLite WAL, busy_timeout)
│   ├── repository/                        ← SQLite DAOs (Item, SalesTransaction, Analytics)
│   ├── service/                           ← Business services (Billing, Inventory, Price, Analytics)
│   └── ui/                                ← JavaFX controllers & models (Pos, Inventory, etc.)
├── src/main/resources/
│   ├── css/style.css                      ← Modern slate/indigo UI styling (415 lines)
│   ├── fxml/                              ← FXML view layouts (main, pos, inventory, etc.)
│   └── db/migrations/                    ← V1 schema DDL + V2 26-item initial catalog
├── src/test/java/                         ← 22 test classes (146 automated tests)
└── target/
    └── supermarket-automation-1.0.0-shaded.jar ← 22.15 MB standalone executable fat JAR
```

---

## 🚀 How to Run

All catalog and historical monetary values are stored and displayed in Indian rupees. Migration V3 converts existing USD amounts at a fixed rate of ₹83 per USD.

### Option 1: 1-Click Desktop Launcher
Double-click `run.bat` inside the project root, or double-click the **Launch Supermarket Automation** shortcut on your Desktop.

### Option 2: Command Line
```powershell
# Run the standalone shaded JAR
java -jar target\supermarket-automation-1.0.0-shaded.jar

# Run the full automated test suite (146 tests)
.\mvnw.cmd test

# Rebuild the standalone executable JAR
.\mvnw.cmd package -DskipTests
```

### Option 3: Browser App
```powershell
java -jar target\supermarket-automation-1.0.0-shaded.jar --web
```
Then open `http://127.0.0.1:8080`. To use another port, pass it after `--web`, for example `--web 8765`.

---

## 🧪 Verification & Test Results
- **Total Tests**: **146 passed**, 0 failures, 0 errors.
- **Coverage**: Database persistence, schema migrations, concurrency stress tests, atomic rollback validation, historical price snapshot invariance, and headless UI controller interactions.
