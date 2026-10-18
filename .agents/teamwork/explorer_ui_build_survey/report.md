# JavaFX Frontend Architecture, Styling, & Build Packaging Specification
**Project:** Supermarket Automation Software (SAS) Desktop Application  
**Target Platform:** Windows (x86_64)  
**Baseline Runtime:** Java 21 LTS (OpenJDK) with JavaFX 21+ and SQLite JDBC  
**Author:** JavaFX & Build Packaging Explorer (`explorer_ui_build_survey`)  
**Date:** 2026-10-02

---

## 1. Executive Summary & Host Environment Diagnostics

### 1.1 Host Environment Inspection
An inspection of the local Windows host system revealed the following:
1. **Java Runtime:** The system's default `java` command resolves to an older JRE (`1.8.0_51-b16`). An older JDK 6 is also present in Program Files.
2. **Build Tools:** Apache Maven (`mvn`) is not currently registered in the global `PATH`.
3. **Package Manager:** Windows Package Manager (`winget v1.29.380`) is installed and active.
4. **Network & Tool Availability:**
   - Package `Microsoft.OpenJDK.21` (v21.0.12.101) is available via `winget`.
   - Apache Maven binaries and plugins can be retrieved or bundled via Maven Wrapper (`mvnw` / `mvnw.cmd`).

### 1.2 Toolchain Recommendations for Implementation
To ensure zero friction across developer environments and Windows installations, the implementation should support:
- Installing JDK 21 via winget:
  ```powershell
  winget install --id Microsoft.OpenJDK.21 -e --silent --accept-source-agreements --accept-package-agreements
  ```
- Bundling the official **Maven Wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/*`) within the project repository root. With Maven Wrapper, running `./mvnw.cmd clean test` or `./mvnw.cmd javafx:run` requires only `JAVA_HOME` pointing to JDK 21, with no global Maven installation needed.
- If standalone Maven is preferred, Apache Maven 3.9.9 can be extracted to a local directory or placed in PATH.

---

## 2. Maven Build Setup & Packaging Architecture

### 2.1 Complete `pom.xml` Specification
The build configuration uses Java 21 LTS, JavaFX 21.0.4, SQLite JDBC 3.45.2.0, JUnit 5, Mockito, and AssertJ.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.supermarket</groupId>
    <artifactId>supermarket-automation</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>Supermarket Automation Software (SAS)</name>
    <description>Modern JavaFX Desktop Application for Supermarket POS, Inventory, and Sales Analytics</description>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <maven.compiler.release>21</maven.compiler.release>
        
        <!-- Dependency Versions -->
        <javafx.version>21.0.4</javafx.version>
        <sqlite.version>3.45.2.0</sqlite.version>
        <slf4j.version>2.0.12</slf4j.version>
        <junit.jupiter.version>5.10.2</junit.jupiter.version>
        <mockito.version>5.11.0</mockito.version>
        <assertj.version>3.25.3</assertj.version>
        
        <!-- Plugin Versions -->
        <maven-compiler-plugin.version>3.13.0</maven-compiler-plugin.version>
        <maven-surefire-plugin.version>3.2.5</maven-surefire-plugin.version>
        <javafx-maven-plugin.version>0.0.8</javafx-maven-plugin.version>
        <maven-shade-plugin.version>3.5.2</maven-shade-plugin.version>
    </properties>

    <dependencies>
        <!-- JavaFX Controls & FXML -->
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-fxml</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-graphics</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-base</artifactId>
            <version>${javafx.version}</version>
        </dependency>

        <!-- SQLite JDBC Persistence Driver -->
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>${sqlite.version}</version>
        </dependency>

        <!-- Lightweight Logging (SLF4J Simple) -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>${slf4j.version}</version>
        </dependency>

        <!-- Testing Stack -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.jupiter.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Compiler Plugin targeting Java 21 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>${maven-compiler-plugin.version}</version>
                <configuration>
                    <release>21</release>
                    <parameters>true</parameters>
                </configuration>
            </plugin>

            <!-- Surefire Plugin for Headless Automated Unit Testing -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>${maven-surefire-plugin.version}</version>
                <configuration>
                    <!-- Run in headless mode: no display window required -->
                    <systemPropertyVariables>
                        <testfx.robot>glass</testfx.robot>
                        <testfx.headless>true</testfx.headless>
                        <prism.order>sw</prism.order>
                        <prism.text>t2k</prism.text>
                        <java.awt.headless>true</java.awt.headless>
                    </systemPropertyVariables>
                </configuration>
            </plugin>

            <!-- JavaFX Maven Plugin for Development Run -->
            <plugin>
                <groupId>org.openjfx</groupId>
                <artifactId>javafx-maven-plugin</artifactId>
                <version>${javafx-maven-plugin.version}</version>
                <configuration>
                    <mainClass>com.supermarket.SupermarketApp</mainClass>
                </configuration>
            </plugin>

            <!-- Maven Shade Plugin for Standalone Fat Executable JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade-plugin.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <shadedArtifactAttached>true</shadedArtifactAttached>
                            <shadedClassifierName>shaded</shadedClassifierName>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <!-- Use separate Launcher to bypass JavaFX module path check -->
                                    <mainClass>com.supermarket.Launcher</mainClass>
                                </transformer>
                            </transformers>
                            <filters>
                                <!-- Exclude signature files from dependencies to avoid SecurityException -->
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2.2 The JavaFX Launcher Pattern (Solving the Standalone JAR Trap)
**The Trap:**  
When packaging JavaFX into a Fat/Uber JAR, executing `java -jar app.jar` directly will fail with:
```
Error: JavaFX runtime components are missing, and are required to run this application
```
This occurs because the standard Java launcher detects that the `Main-Class` extends `javafx.application.Application` and enforces that JavaFX modules be loaded on the `--module-path`, ignoring classes loaded from the fat JAR classpath.

**The Solution:**  
Provide a standalone entry point `com.supermarket.Launcher` that does *not* extend `javafx.application.Application`:

```java
package com.supermarket;

/**
 * Main launcher entry point for standalone JAR execution.
 * Decouples the initial main method from javafx.application.Application
 * to prevent the JavaFX runtime module path check.
 */
public final class Launcher {
    private Launcher() {
        // Prevent instantiation
    }

    public static void main(String[] args) {
        SupermarketApp.main(args);
    }
}
```

The actual JavaFX Application class `SupermarketApp` remains clean:
```java
package com.supermarket;

import javafx.application.Application;
import javafx.stage.Stage;

public class SupermarketApp extends Application {
    @Override
    public void start(Stage primaryStage) {
        // Build primary scene, initialize database, and show stage
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

This guarantees 100% plug-and-play execution via:
```powershell
java -jar target/supermarket-automation-1.0.0-shaded.jar
```

---

## 3. Modular UI Architecture & Headless Testability

### 3.1 Architectural Layering (Zero-JavaFX Dependency in Business Core)
A critical requirement is that **100% of business logic, billing transactions, atomic inventory decrement, and analytics calculations must be testable headlessly without requiring a display or JavaFX toolkit**.

To achieve this, the application is strictly partitioned into three independent layers:

```
┌────────────────────────────────────────────────────────┐
│                   JavaFX UI Layer                      │
│   (Views, FXML, Controllers, ViewModels, CSS)          │
│   Depends on Service Layer ONLY                        │
│   Imports: javafx.*, com.supermarket.service.*         │
└───────────────────────────┬────────────────────────────┘
                            │ Calls Service methods
                            ▼
┌────────────────────────────────────────────────────────┐
│                   Service Layer                        │
│   (BillingService, InventoryService, PricingService,   │
│    AnalyticsService, ReceiptService)                   │
│   Zero JavaFX imports! Pure Java 21 Records & POJOs    │
│   Headless test coverage: 100%                         │
└───────────────────────────┬────────────────────────────┘
                            │ Calls Repository methods
                            ▼
┌────────────────────────────────────────────────────────┐
│                 Persistence / DAO Layer                │
│   (ProductDao, SaleDao, RestockDao, AnalyticsDao)      │
│   SQLite JDBC Transactions, WAL mode, Schema Migrations│
└────────────────────────────────────────────────────────┘
```

### 3.2 Domain Model Contract (Pure Java Records)
All domain objects are pure Java data structures (`record` or immutable POJOs), free of any UI widgets:

```java
package com.supermarket.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Product(
    Long id,
    String code,
    String name,
    String category,
    BigDecimal costPrice,
    BigDecimal sellingPrice,
    int currentStock,
    int minStockThreshold,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}

public record CartItem(
    Product product,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {
    public CartItem withQuantity(int newQty) {
        return new CartItem(product, newQty, unitPrice, unitPrice.multiply(BigDecimal.valueOf(newQty)));
    }
}

public record SaleTransaction(
    Long id,
    String serialNumber,
    BigDecimal totalAmount,
    BigDecimal amountTendered,
    BigDecimal changeAmount,
    LocalDateTime transactionTimestamp
) {}

public record SaleLineItem(
    Long id,
    Long transactionId,
    String itemCode,
    String itemName,
    int quantity,
    BigDecimal unitCostPriceSnapshot,
    BigDecimal unitSellingPriceSnapshot,
    BigDecimal subtotal
) {
    public BigDecimal calculateProfit() {
        BigDecimal unitProfit = unitSellingPriceSnapshot.subtract(unitCostPriceSnapshot);
        return unitProfit.multiply(BigDecimal.valueOf(quantity));
    }
}

public record ItemSalesSummary(
    String itemCode,
    String itemName,
    String category,
    int quantitySold,
    BigDecimal totalRevenue,
    BigDecimal totalCost,
    BigDecimal netProfit,
    double profitMarginPercentage
) {}
```

### 3.3 Headless Service Testability Example
Because `BillingService` has zero UI dependencies, tests execute at lightspeed in continuous integration without requiring virtual framebuffers (`Xvfb`) or Windows display drivers:

```java
@Test
void completeSale_shouldAtomicallyDecrementStockAndPreservePriceSnapshot() {
    // 1. Arrange: setup in-memory SQLite database
    DatabaseManager db = DatabaseManager.createInMemory();
    ProductDao productDao = new SqliteProductDao(db);
    SaleDao saleDao = new SqliteSaleDao(db);
    BillingService billingService = new BillingServiceImpl(db, productDao, saleDao);

    Product milk = productDao.findByCode("MILK-001").orElseThrow();
    assertThat(milk.currentStock()).isEqualTo(50);

    Cart cart = new Cart();
    cart.addItem(milk, 5); // 5 units at $3.50

    // 2. Act: process sale
    SaleResult result = billingService.processSale(cart, new BigDecimal("20.00"), "CLERK-01");

    // 3. Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.transaction().serialNumber()).startsWith("RCP-");
    assertThat(result.transaction().changeAmount()).isEqualByComparingTo("2.50");

    // Live inventory decremented atomically
    Product updatedMilk = productDao.findByCode("MILK-001").orElseThrow();
    assertThat(updatedMilk.currentStock()).isEqualTo(45);

    // Historical price snapshot is preserved
    SaleLineItem line = result.lineItems().get(0);
    assertThat(line.unitSellingPriceSnapshot()).isEqualByComparingTo("3.50");
    assertThat(line.unitCostPriceSnapshot()).isEqualByComparingTo(milk.costPrice());
}
```

---

## 4. UI View & Tab Breakdown Specification

The application features a responsive main layout anchored by a modern `TabPane` or custom navigation sidebar with five primary tabs, plus a modal receipt preview dialog:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│  METRO SUPERMARKET AUTOMATION SYSTEM                    [Clerk: John Doe] [F1: Help]   │
├────────────────┬──────────────┬──────────────┬────────────────┬────────────────────────┤
│ [1] POS Sales  │ [2] Catalog  │ [3] Restock  │ [4] Dynamic    │ [5] Sales Analytics    │
│     Billing    │     Inventory│  Management  │     Pricing    │     & Profit Reports   │
├────────────────┴──────────────┴──────────────┴────────────────┴────────────────────────┤
│                                                                                        │
│                                (Active Tab Content Area)                               │
│                                                                                        │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ Status: Ready | Database: Connected (WAL Mode) | Active Cart Items: 3 | Total: $14.25  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 4.1 Tab 1: Sales Clerk POS (Point of Sale & Bill Generation)

#### Purpose & Workflow
The frontline cashier billing interface designed for high speed and minimal friction:
1. **Barcode / Code Input:** Clerk scans barcode with a handheld scanner or types code and presses Enter.
2. **Auto-Cart Increment:** If the scanned code is already in the cart, the quantity increments by 1 automatically.
3. **Quantity Spinner / Quick Actions:** Easy adjustment of quantity, plus `Remove` button.
4. **Instant Calculation:** Real-time calculation of subtotal, tax (if configured), and grand total payable in high-contrast typography.
5. **Tender & Change Calculation:** Cashier enters amount tendered; change is computed dynamically on every keystroke.
6. **Checkout Validation:**
   - Validates that cart is not empty.
   - Validates that cash tendered >= total payable.
   - Atomically executes database transaction (stock decrement + receipt generation).
   - If stock is insufficient, displays an alert detailing the shortfall and aborts without decrementing.
   - On success, pops open the **Thermal Receipt Preview Modal** and clears the cart for the next customer.

#### Visual Layout Grid
```
┌───────────────────────────────────────────────────┬────────────────────────────────────┐
│                  CART TABLEVIEW                   │          CHECKOUT PANEL            │
│  ┌──────────────────────────────────────────────┐ │ ┌────────────────────────────────┐ │
│  │ Barcode: [ MILK-001                  ] [Add] │ │ │ BILLING SUMMARY                │ │
│  └──────────────────────────────────────────────┘ │ │ Subtotal:              $12.50  │ │
│  ┌──────────┬─────────────┬─────┬─────┬─────────┐ │ │ Tax (0%):               $0.00  │ │
│  │ Code     │ Item Name   │Price│ Qty │ Total   │ │ │ Total Payable:         $12.50  │ │
│  ├──────────┼─────────────┼─────┼─────┼─────────┤ │ ├────────────────────────────────┤ │
│  │ MILK-001 │ Whole Milk  │$3.50│  2  │  $7.00  │ │ │ Amount Tendered:               │ │
│  │ BREAD-02 │ Sliced Bread│$2.75│  2  │  $5.50  │ │ │ [ $20.00                     ] │ │
│  │          │             │     │     │         │ │ │ Change Due:            $7.50   │ │
│  └──────────┴─────────────┴─────┴─────┴─────────┘ │ ├────────────────────────────────┤ │
│  [Clear Cart (F4)]            [Remove Selected]   │ │ [ COMPLETE SALE & BILL (F12) ] │ │
│                                                   │ └────────────────────────────────┘ │
└───────────────────────────────────────────────────┴────────────────────────────────────┘
```

#### POS Controller Technical Contract
- `PosTabController`:
  - `TextField txtBarcode`: KeyEventHandler for `KeyCode.ENTER` triggers `handleAddItem()`.
  - `TableView<CartItemModel> cartTable`: columns for `code`, `name`, `unitPrice`, `quantity`, `lineTotal`, `actionCol`.
  - `Label lblSubtotal`, `Label lblTotalPayable`, `Label lblChangeDue`.
  - `TextField txtTendered`: listens to text changes; parses `BigDecimal` safely, updates change label, toggles "Complete Sale" button disabled state.
  - Keyboard Accelerators:
    - `F2`: focus barcode input.
    - `F4`: clear cart with confirmation.
    - `F12`: execute sale checkout.

---

### 4.2 Tab 2: Inventory Catalog & Stock Tracking

#### Purpose & Features
Complete visibility into the supermarket catalog, current stock levels, and replenishment requirements.
1. **Search & Filtering:**
   - Live search field filtering by barcode, SKU, name, or category.
   - Category dropdown filter (e.g. "All", "Dairy", "Bakery", "Beverages", "Produce", "Pantry").
   - Toggle checkbox: `[x] Show Low Stock Only (<= threshold)`.
2. **Visual Stock Level Badges:**
   - **Out of Stock (`0`):** Crimson red badge (`badge-danger`).
   - **Low Stock (`<= threshold`):** Amber badge (`badge-warning`).
   - **Healthy Stock (`> threshold`):** Emerald green badge (`badge-success`).
3. **Item Details & Add/Edit Actions:**
   - Detailed side panel showing cost price, selling price, current margin %, supplier info, and last updated timestamp.
   - Action buttons: "Add New Product", "Refresh Inventory".

#### Visual Layout Grid
```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Filter: [ Search by name or code... ]  Category: [ All Categories ▼ ]  [x] Low Stock   │
├──────────┬──────────────────────┬──────────┬──────────┬──────────┬───────┬─────────────┤
│ Code     │ Product Name         │ Category │ Cost ($) │ Sell ($) │ Stock │ Status      │
├──────────┼──────────────────────┼──────────┼──────────┼──────────┼───────┼─────────────┤
│ MILK-001 │ Whole Milk 1L        │ Dairy    │ $2.20    │ $3.50    │  45   │ [IN STOCK]  │
│ APPL-010 │ Fuji Apples (1kg)    │ Produce  │ $1.80    │ $2.99    │   4   │ [LOW STOCK] │
│ EGGS-012 │ Farm Eggs 12pk       │ Dairy    │ $2.50    │ $4.20    │   0   │ [DEPLETED]  │
└──────────┴──────────────────────┴──────────┴──────────┴──────────┴───────┴─────────────┘
```

---

### 4.3 Tab 3: Employee Restock Management

#### Purpose & Audit Trail Workflow
Allows warehouse and floor employees to record inbound supply shipments and batch deliveries:
1. **Restock Input Form:**
   - **Supplier Name:** e.g., "Metro Dairy Distributors Ltd."
   - **Delivery / Batch Ref:** e.g., "INV-2026-9921"
   - **Item Code Selector:** Barcode scanner input or autocomplete combobox.
   - **Quantity Added:** Positive integer validation (`quantity > 0`).
   - **Batch Unit Cost ($):** Enables tracking supplier price shifts.
   - **Audit Notes:** Free-text reason (e.g., "Weekly scheduled restock", "Damaged goods replacement").
2. **Atomic Inventory Increment:**
   - On submission, executes an atomic SQLite transaction:
     - Updates `products.current_stock = current_stock + :quantityAdded`.
     - Inserts record into `restock_audit_log` with timestamp, employee ID, previous stock, new stock, and notes.
3. **Restock History / Audit Table:**
   - Displays real-time log of recent restocks to prevent duplicate entries and maintain strict chain of custody.

#### Visual Layout Grid
```
┌───────────────────────────────────────────────┬────────────────────────────────────────┐
│           RECEIVE SUPPLY SHIPMENT             │         RECENT RESTOCK AUDIT LOG       │
├───────────────────────────────────────────────┼────────────────────────────────────────┤
│ Supplier Name: [ Global Foods Inc.          ] │ ┌────────────────────────────────────┐ │
│ Invoice Ref:   [ PO-883921                  ] │ │ Timestamp | Item | Added | Notes   │ │
│ Item Code:     [ MILK-001 (Whole Milk)    ▼ ] │ ├────────────────────────────────────┤ │
│ Quantity:      [ 100                        ] │ │ 14:22:10  | MILK | +100  | Weekly  │ │
│ Batch Cost:    [ $2.20                      ] │ │ 11:05:40  | BRED | +50   | Fresh   │ │
│ Audit Notes:   [ Regular weekly delivery    ] │ └────────────────────────────────────┘ │
│                                               │                                        │
│ [ SUBMIT RESTOCK BATCH ]                      │                                        │
└───────────────────────────────────────────────┴────────────────────────────────────────┘
```

---

### 4.4 Tab 4: Manager Dynamic Selling Price Management

#### Purpose & Price Invariance Guarantee
Allows the store manager to adjust selling prices as market rates fluctuate daily, while strictly preserving the integrity of historical sales records:
1. **Dynamic Price Editing:**
   - Searchable table of all products with columns:
     - Code, Name, Category.
     - **Cost Price ($):** Current wholesale/procurement cost.
     - **Current Selling Price ($).**
     - **Current Profit Margin (%):** Computed as `((Sell - Cost) / Sell) * 100`.
2. **Inline / Modal Price Editor:**
   - Prompts for `New Selling Price ($)`.
   - Real-time margin calculator updates dynamically as the manager types.
   - **Validation Rules:**
     - Must be a valid positive number (`> 0.00`).
     - If `New Selling Price < Cost Price`, displays a prominent warning dialog confirming intentional negative margin / clearance sale.
     - Reason field (e.g., "Weekend promotional discount", "Wholesale price hike").
3. **Invariance Guarantee:**
   - Updating `products.current_selling_price` updates only the active catalog.
   - All past sales records in `sales_transaction_items` retain their original immutable price snapshots (`unit_selling_price_snapshot` and `unit_cost_price_snapshot`).
   - Consequently, **historical profits and sales analytics remain 100% invariant and will never be corrupted by future price changes**.

---

### 4.5 Tab 5: Sales Analytics & Profit Tracking

#### Purpose & Arbitrary Date Range Filtering
Provides managerial insight into sales performance and net profitability across any selectable day or arbitrary date range:
1. **Date Range Filter Controls:**
   - `From Date` and `To Date` DatePickers.
   - Quick range preset buttons: `[ Today ]`, `[ Yesterday ]`, `[ Last 7 Days ]`, `[ This Month ]`, `[ All Time ]`.
   - `[ Generate Analytics Report ]` button.
2. **Top KPI Summary Cards:**
   - **Total Gross Revenue ($):** Realized sales sum.
   - **Total Cost of Goods Sold ($):** Total cost snapshot sum.
   - **Net Realized Profit ($):** Revenue minus Cost.
   - **Overall Margin (%) & Total Units Sold:** Volume and margin performance.
3. **Item-Level Sales Statistics TableView:**
   - Displays every item the supermarket dealt with during the selected period:
     - Code, Item Name, Category, Units Sold, Realized Revenue ($), Total Cost ($), Net Profit ($), Margin (%).
   - Supports sorting by any column (e.g. click "Net Profit" to find top-earning products).
4. **Export Capabilities:**
   - `[ Export to CSV ]`: Generates standard spreadsheet report.
   - `[ Print Summary Report ]`: Formatted managerial printable summary.

#### Visual Layout Grid
```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Date Range: [ 2026-10-01 📅 ] to [ 2026-10-02 📅 ]  [Today] [This Week] [Generate]    │
├─────────────────────┬─────────────────────┬─────────────────────┬──────────────────────┤
│ TOTAL REVENUE       │ TOTAL COGS (COST)   │ NET REALIZED PROFIT │ UNITS SOLD           │
│    $3,420.50        │    $2,140.20        │    $1,280.30        │     1,142            │
│ (+12.4% vs prev)    │ Margin: 37.4%       │ High Margin         │ Avg $2.99 / item     │
├─────────────────────┴─────────────────────┴─────────────────────┴──────────────────────┤
│ ITEM SALES BREAKDOWN                                                                   │
│ ┌──────────┬──────────────────────┬───────┬──────────────┬────────────┬──────────────┐ │
│ │ Code     │ Item Name            │ Qty   │ Realized ($) │ Cost ($)   │ Profit ($)   │ │
│ ├──────────┼──────────────────────┼───────┼──────────────┼────────────┼──────────────┤ │
│ │ MILK-001 │ Whole Milk 1L        │  120  │   $420.00    │  $264.00   │   $156.00    │ │
│ │ ORNG-004 │ Valencia Oranges     │   85  │   $254.15    │  $144.50   │   $109.65    │ │
│ │ SOUP-088 │ Tomato Soup Can      │   60  │   $119.40    │   $72.00   │    $47.40    │ │
│ └──────────┴──────────────────────┴───────┴──────────────┴────────────┴──────────────┘ │
│ [ Export to CSV ]                                                [ Print Report ]      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 4.6 Modal Thermal-Style Receipt Preview

#### Purpose & ESC/POS Aesthetic
Following every completed sale, or upon clerk request, a modal dialog renders an authentic 40-column monospace thermal receipt preview:

```
========================================
           METRO SUPERMARKET            
     100 Market Street, Tech City       
           Tel: (555) 019-2831          
           GST / Tax ID: SAS-99281      
========================================
Receipt #: RCP-20261002-00042
Date/Time: 2026-10-02 19:14:02
Cashier:   CLERK-01
----------------------------------------
ITEM            QTY   UNIT        TOTAL
----------------------------------------
Whole Milk 1L     2   $3.50       $7.00
Sliced Bread      2   $2.75       $5.50
Fuji Apples 1kg   1   $2.99       $2.99
----------------------------------------
Subtotal:                        $15.49
Tax (0%):                         $0.00
----------------------------------------
TOTAL PAYABLE:                   $15.49
Cash Tendered:                   $20.00
Change Due:                       $4.51
----------------------------------------
Total Items Sold: 5
========================================
       THANK YOU FOR SHOPPING!          
      PLEASE VISIT US AGAIN SOON        
========================================
```

#### Monospace Receipt Formatter Implementation
```java
package com.supermarket.service;

import com.supermarket.domain.SaleLineItem;
import com.supermarket.domain.SaleTransaction;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReceiptFormatter {
    private static final int WIDTH = 40;
    private static final DateTimeFormatter DATE_FMT = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String formatThermalReceipt(SaleTransaction sale, List<SaleLineItem> items) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(repeat('=', WIDTH)).append("\n");
        sb.append(center("METRO SUPERMARKET", WIDTH)).append("\n");
        sb.append(center("100 Market Street, Tech City", WIDTH)).append("\n");
        sb.append(center("Tel: (555) 019-2831 | Tax ID: SAS-99281", WIDTH)).append("\n");
        sb.append(repeat('=', WIDTH)).append("\n");
        
        sb.append(String.format("Receipt #: %s\n", sale.serialNumber()));
        sb.append(String.format("Date/Time: %s\n", sale.transactionTimestamp().format(DATE_FMT)));
        sb.append(repeat('-', WIDTH)).append("\n");
        
        sb.append(String.format("%-15s %4s %8s %9s\n", "ITEM", "QTY", "UNIT", "TOTAL"));
        sb.append(repeat('-', WIDTH)).append("\n");
        
        int totalItems = 0;
        for (SaleLineItem item : items) {
            String name = item.itemName();
            if (name.length() > 15) {
                name = name.substring(0, 14) + ".";
            }
            sb.append(String.format("%-15s %4d %8s %9s\n",
                name,
                item.quantity(),
                String.format("$%.2f", item.unitSellingPriceSnapshot()),
                String.format("$%.2f", item.subtotal())
            ));
            totalItems += item.quantity();
        }
        
        sb.append(repeat('-', WIDTH)).append("\n");
        sb.append(formatLine("Subtotal:", String.format("$%.2f", sale.totalAmount())));
        sb.append(formatLine("TOTAL PAYABLE:", String.format("$%.2f", sale.totalAmount())));
        sb.append(formatLine("Cash Tendered:", String.format("$%.2f", sale.amountTendered())));
        sb.append(formatLine("Change Due:", String.format("$%.2f", sale.changeAmount())));
        sb.append(repeat('-', WIDTH)).append("\n");
        sb.append(String.format("Total Items Count: %d\n", totalItems));
        sb.append(repeat('=', WIDTH)).append("\n");
        sb.append(center("THANK YOU FOR SHOPPING!", WIDTH)).append("\n");
        sb.append(center("PLEASE VISIT US AGAIN", WIDTH)).append("\n");
        sb.append(repeat('=', WIDTH)).append("\n");
        
        return sb.toString();
    }

    private static String center(String text, int width) {
        if (text.length() >= width) return text;
        int left = (width - text.length()) / 2;
        return " ".repeat(left) + text;
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    private static String formatLine(String label, String value) {
        int space = WIDTH - label.length() - value.length();
        if (space < 1) space = 1;
        return label + " ".repeat(space) + value + "\n";
    }
}
```

---

## 5. Polished Modern CSS Styling (`style.css`)

A complete CSS styling stylesheet providing a clean, high-contrast, modern enterprise aesthetic:

```css
/* ========================================================
   Supermarket Automation Software (SAS) Modern Stylesheet
   ======================================================== */

.root {
    -fx-font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    -fx-font-size: 13px;
    -fx-base: #f8fafc;
    -fx-background-color: #f1f5f9;
    -fx-text-fill: #1e293b;
}

/* TabPane Styling */
.tab-pane {
    -fx-tab-min-height: 42px;
    -fx-tab-max-height: 42px;
}

.tab-pane .tab-header-area .tab-header-background {
    -fx-background-color: #ffffff;
    -fx-border-color: #e2e8f0;
    -fx-border-width: 0 0 1 0;
}

.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-background-radius: 0;
    -fx-padding: 8 20 8 20;
    -fx-border-color: transparent;
}

.tab-pane .tab:selected {
    -fx-background-color: #ffffff;
    -fx-border-color: #4f46e5;
    -fx-border-width: 0 0 3 0;
}

.tab-pane .tab .tab-label {
    -fx-text-fill: #64748b;
    -fx-font-weight: bold;
    -fx-font-size: 14px;
}

.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #4f46e5;
}

/* Card Containers */
.card {
    -fx-background-color: #ffffff;
    -fx-background-radius: 8px;
    -fx-border-radius: 8px;
    -fx-border-color: #e2e8f0;
    -fx-border-width: 1px;
    -fx-padding: 18px;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 8, 0, 0, 2);
}

/* KPI Summary Cards */
.kpi-card {
    -fx-background-color: #ffffff;
    -fx-background-radius: 8px;
    -fx-border-color: #e2e8f0;
    -fx-border-width: 1px;
    -fx-padding: 16px;
    -fx-pref-width: 240px;
}

.kpi-title {
    -fx-text-fill: #64748b;
    -fx-font-size: 12px;
    -fx-font-weight: bold;
}

.kpi-value {
    -fx-text-fill: #0f172a;
    -fx-font-size: 24px;
    -fx-font-weight: bold;
}

.kpi-value-profit {
    -fx-text-fill: #059669;
    -fx-font-size: 24px;
    -fx-font-weight: bold;
}

/* Buttons */
.button {
    -fx-background-color: #f8fafc;
    -fx-border-color: #cbd5e1;
    -fx-border-radius: 6px;
    -fx-background-radius: 6px;
    -fx-text-fill: #334155;
    -fx-font-weight: 600;
    -fx-padding: 8 16 8 16;
    -fx-cursor: hand;
}

.button:hover {
    -fx-background-color: #f1f5f9;
    -fx-border-color: #94a3b8;
}

.button-primary {
    -fx-background-color: #4f46e5;
    -fx-border-color: #4338ca;
    -fx-text-fill: #ffffff;
}

.button-primary:hover {
    -fx-background-color: #4338ca;
}

.button-success {
    -fx-background-color: #059669;
    -fx-border-color: #047857;
    -fx-text-fill: #ffffff;
}

.button-success:hover {
    -fx-background-color: #047857;
}

.button-danger {
    -fx-background-color: #dc2626;
    -fx-border-color: #b91c1c;
    -fx-text-fill: #ffffff;
}

.button-danger:hover {
    -fx-background-color: #b91c1c;
}

/* Big Checkout Button */
.button-checkout {
    -fx-background-color: #059669;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-padding: 14 24 14 24;
    -fx-background-radius: 8px;
    -fx-cursor: hand;
}

.button-checkout:hover {
    -fx-background-color: #047857;
}

/* Text Fields */
.text-field {
    -fx-background-color: #ffffff;
    -fx-border-color: #cbd5e1;
    -fx-border-radius: 6px;
    -fx-background-radius: 6px;
    -fx-padding: 8 12 8 12;
    -fx-text-fill: #0f172a;
}

.text-field:focused {
    -fx-border-color: #4f46e5;
    -fx-background-color: #ffffff;
}

/* Large Input for POS Barcode */
.pos-input {
    -fx-font-size: 16px;
    -fx-font-weight: bold;
    -fx-padding: 10 14 10 14;
}

/* TableView Polishing */
.table-view {
    -fx-background-color: #ffffff;
    -fx-border-color: #e2e8f0;
    -fx-border-radius: 6px;
    -fx-background-radius: 6px;
}

.table-view .column-header {
    -fx-background-color: #f8fafc;
    -fx-border-color: #e2e8f0;
    -fx-border-width: 0 0 1 0;
    -fx-font-weight: bold;
    -fx-text-fill: #475569;
    -fx-padding: 10 8 10 8;
}

.table-row-cell {
    -fx-background-color: #ffffff;
    -fx-border-color: #f1f5f9;
    -fx-border-width: 0 0 1 0;
    -fx-cell-size: 38px;
}

.table-row-cell:odd {
    -fx-background-color: #fcfdfe;
}

.table-row-cell:hover {
    -fx-background-color: #f1f5f9;
}

.table-row-cell:selected {
    -fx-background-color: #e0e7ff;
    -fx-text-fill: #1e1b4b;
}

/* Stock Status Badges */
.badge-success {
    -fx-background-color: #dcfce7;
    -fx-text-fill: #166534;
    -fx-padding: 4 8 4 8;
    -fx-background-radius: 4px;
    -fx-font-weight: bold;
    -fx-font-size: 11px;
}

.badge-warning {
    -fx-background-color: #fef3c7;
    -fx-text-fill: #92400e;
    -fx-padding: 4 8 4 8;
    -fx-background-radius: 4px;
    -fx-font-weight: bold;
    -fx-font-size: 11px;
}

.badge-danger {
    -fx-background-color: #fee2e2;
    -fx-text-fill: #991b1b;
    -fx-padding: 4 8 4 8;
    -fx-background-radius: 4px;
    -fx-font-weight: bold;
    -fx-font-size: 11px;
}

/* Thermal Receipt Monospace Paper View */
.receipt-paper {
    -fx-background-color: #faf9f5;
    -fx-border-color: #d1d5db;
    -fx-border-radius: 4px;
    -fx-background-radius: 4px;
    -fx-padding: 20px;
    -fx-font-family: "Consolas", "Courier New", monospace;
    -fx-font-size: 13px;
    -fx-text-fill: #111827;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 12, 0, 0, 4);
}
```

---

## 6. Verification & Automated Test Strategy

### 6.1 Headless Test Suite Mapping
To fulfill the acceptance criteria, the build must execute tests completely headlessly via `mvn test`. The test suite should be organized into:

| Test Class | Focus & Acceptance Criteria |
|---|---|
| `BillingServiceTest` | Atomically decrements stock on complete sale; rolls back and aborts on insufficient stock; verifies price snapshot immutability. |
| `PriceSnapshotInvarianceTest` | Explicit verification: dynamic price update in catalog leaves past `sales_transaction_items` unit selling prices and profits completely unaltered. |
| `RestockServiceTest` | Supply restock increments stock and records an immutable audit log entry. |
| `AnalyticsServiceTest` | Accurate date range filtering (single day, multi-day, empty ranges); accurate revenue and profit calculations. |
| `ReceiptFormatterTest` | Verifies receipt text formatting, alignment, column widths, serial number, and totals. |

### 6.2 Execution Commands
- **Run automated headless test suite:**
  ```powershell
  mvn clean test
  ```
- **Run desktop application in development mode:**
  ```powershell
  mvn javafx:run
  ```
- **Build standalone executable shaded JAR:**
  ```powershell
  mvn clean package
  ```
- **Run standalone application on Windows:**
  ```powershell
  java -jar target/supermarket-automation-1.0.0-shaded.jar
  ```
