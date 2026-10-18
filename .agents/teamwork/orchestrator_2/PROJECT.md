# Project: Supermarket Automation Software (SAS)

## Architecture
A production-grade JavaFX desktop application backed by SQLite with strict separation between UI, Service, Domain, and Persistence layers:
- **Persistence Layer (`com.supermarket.persistence`, `com.supermarket.repository`)**: SQLite with WAL mode, foreign keys, write serialization lock (`ReentrantLock`), embedded SQL migrations (`V1__init_schema.sql`, `V2__seed_initial_catalog.sql`), and DAO implementations.
- **Domain Layer (`com.supermarket.domain`)**: Pure Java 21 Records / POJOs representing items, carts, transactions, receipts, audit logs, and analytics stats. Zero JavaFX imports.
- **Service Layer (`com.supermarket.service`)**: Headless business logic orchestrating atomic transactions, stock decrement, rollback on insufficient stock, price snapshotting at sale time, restock audit logging, dynamic price management, and arbitrary date profit analytics. Zero JavaFX imports.
- **Receipt Formatting Engine (`com.supermarket.service.ReceiptFormatter`)**: 40-column ESC/POS monospace thermal receipt formatting.
- **UI Layer (`com.supermarket.ui`)**: JavaFX 21 controls, FXML views, modern CSS theme (`style.css`), TabPane (POS, Inventory, Restock, Price Management, Analytics), and modal thermal receipt preview dialog.
- **Launcher (`com.supermarket.Launcher`)**: Independent main class calling `SupermarketApp.main(args)` enabling double-click fat-JAR standalone execution on Windows without JavaFX runtime module path errors.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | F1: Product Catalog Management | CRUD, Code, Name, Category, Cost Price, Selling Price, Current Stock, Min Threshold | M1, M2 | ORIGINAL_REQUEST §R2 |
| 2 | F2: Dynamic Code Lookup & Scanner | Barcode/item code lookup with instant feedback and scanner Enter-key workflow | M3 | ORIGINAL_REQUEST §R1 |
| 3 | F3: Active POS Cart Management | Add, modify quantities, delete items, clear cart, live subtotal | M2 | ORIGINAL_REQUEST §R1 |
| 4 | F4: Real-time Subtotal & Change | Real-time totals, cash tendered, change computation, payment validation | M2 | ORIGINAL_REQUEST §R1 |
| 5 | F5: Atomic Sales Transaction | Atomic SQLite transaction: stock decrement, receipt creation, line item insertion | M2 | ORIGINAL_REQUEST §R1, Criteria |
| 6 | F6: Atomic Rollback on Insufficient Stock | Complete rollback when any item in cart exceeds available inventory | M2 | ORIGINAL_REQUEST §R1, Criteria |
| 7 | F7: Transaction-Time Price Snapshotting | Store unit_cost_price and unit_selling_price snapshots in receipt_items | M2 | ORIGINAL_REQUEST §R3 |
| 8 | F8: Historical Profit Invariance | Future price modifications never alter historical revenue or profit figures | M2 | ORIGINAL_REQUEST §R3, Criteria |
| 9 | F9: Unique Serial Number Generation | Distinct sequential serial numbers (e.g. SAS-YYYYMMDD-XXXXX) for every transaction | M2 | ORIGINAL_REQUEST §R1 |
| 10 | F10: Printable Thermal Bill Generation | 40-column ESC/POS monospace bill containing all mandated fields | M2 | ORIGINAL_REQUEST §R1 |
| 11 | F11: Receipt Preview & Printing Dialog | JavaFX modal dialog with thermal preview, print, and export capabilities | M3 | ORIGINAL_REQUEST §R1, §R5 |
| 12 | F12: Inventory Catalog Query & Search | Instant search and filter by code, name, category, or stock status | M2, M3 | ORIGINAL_REQUEST §R2 |
| 13 | F13: Low Stock Level Visual Alerts | Visual warnings and badges when stock falls below minimum threshold | M3 | ORIGINAL_REQUEST §R2 |
| 14 | F14: Employee Supply Restock Interface | Form to input arriving supply batches, item code, quantity, and batch notes | M2, M3 | ORIGINAL_REQUEST §R2 |
| 15 | F15: Atomic Stock Increment on Restock | Increment item inventory atomically in database | M2 | ORIGINAL_REQUEST §R2 |
| 16 | F16: Restock Audit Trail Logging | Log restock events with previous/new stock, quantity added, operator, timestamp | M2 | ORIGINAL_REQUEST §R2, Criteria |
| 17 | F17: Manager Dynamic Price Management | Manager interface to dynamically adjust item selling price | M2, M3 | ORIGINAL_REQUEST §R4 |
| 18 | F18: Selling Price Validation | Validate selling price > 0, warning/validation against cost price | M2 | ORIGINAL_REQUEST §R4 |
| 19 | F19: Price History Audit Logging | Log old price, new price, operator, and timestamp on price modification | M2 | ORIGINAL_REQUEST §R4 |
| 20 | F20: Arbitrary Period Sales Filtering | Filter sales analytics by single selectable day or arbitrary date range | M2, M3 | ORIGINAL_REQUEST §R3 |
| 21 | F21: Itemized Sales & Profit Statistics | Report quantity sold, realized revenue, total cost, and net profit per item | M2 | ORIGINAL_REQUEST §R3 |
| 22 | F22: Aggregated Sales KPI Metrics | Total revenue, gross profit, margin %, items sold, transactions count | M2 | ORIGINAL_REQUEST §R3 |
| 23 | F23: Modern JavaFX Tabbed Desktop UI | 5 tabbed views styled with modern enterprise CSS | M3 | ORIGINAL_REQUEST §R5 |
| 24 | F24: SQLite Persistence & Migrations | Schema init, WAL mode, foreign keys, transaction locking, DAOs | M1 | ORIGINAL_REQUEST §R1, R2, R3 |
| 25 | F25: Maven Build Setup & Windows Fat JAR | OpenJDK 21, JavaFX 21, SQLite JDBC, JUnit 5, Shaded fat JAR packaging | M1, M4 | ORIGINAL_REQUEST §R5, Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Tooling, Build Setup & Persistence Layer | Java 21, Maven wrapper, pom.xml, SQLite DDL, DatabaseManager, DAOs | none | DONE |
| M2 | Core Business Services & Headless Logic | BillingService, InventoryService, PriceService, AnalyticsService, Unit Tests | M1 | DONE |
| M3 | JavaFX Desktop UI & Thermal Receipt Dialog | FXML views, Controllers, ReceiptPreviewDialog, CSS styling | M2 | DONE |
| M4 | Packaging & Windows Standalone Executable | Launcher class, maven-shade-plugin fat JAR, batch launcher script, execution test | M3 | IN_PROGRESS |
| M5 | E2E Automated Verification & Hardening | Full Tiers 1-4 E2E test suites, adversarial testing, 100% pass rate, package verification | M4 | PLANNED |

## Interface Contracts
### DatabaseManager ↔ DAOs
```java
public class DatabaseManager {
    public static DatabaseManager getInstance();
    public Connection getConnection() throws SQLException;
    public void executeInTransaction(TransactionWork work) throws SQLException;
    public <T> T executeInTransactionWithResult(TransactionResultWork<T> work) throws SQLException;
}
```

### Repositories / DAOs
```java
public interface ItemRepository {
    Optional<Item> findByCode(String code);
    List<Item> findAll();
    List<Item> search(String query, String category);
    void save(Item item);
    boolean decrementStock(Connection conn, String code, int quantity);
    void incrementStock(Connection conn, String code, int quantity);
    void updateSellingPrice(Connection conn, String code, BigDecimal newPrice);
}

public interface SalesTransactionRepository {
    void createTransaction(Connection conn, SalesTransaction tx, List<ReceiptLineItem> items);
    Optional<SalesTransaction> findBySerialNumber(String serialNumber);
    List<SalesTransaction> findByDateRange(LocalDateTime start, LocalDateTime end);
    List<ReceiptLineItem> findItemsByTransactionId(long transactionId);
}

public interface RestockAuditRepository {
    void logRestock(Connection conn, RestockAudit audit);
    List<RestockAudit> findAll();
}

public interface PriceHistoryRepository {
    void logPriceChange(Connection conn, PriceHistory history);
    List<PriceHistory> findByItemCode(String code);
}

public interface AnalyticsRepository {
    List<SalesStatistic> getItemStatistics(LocalDateTime start, LocalDateTime end);
    AnalyticsSummary getSummary(LocalDateTime start, LocalDateTime end);
}
```

### Service Layer Contracts (Pure Headless Java)
```java
public class BillingService {
    public Cart createCart();
    public CartItem addItemToCart(Cart cart, String itemCode, int quantity);
    public void removeItemFromCart(Cart cart, String itemCode);
    public void updateCartItemQuantity(Cart cart, String itemCode, int newQuantity);
    public CartTotals computeTotals(Cart cart);
    public Receipt checkout(Cart cart, BigDecimal cashTendered, String cashierName) throws InsufficientStockException, PaymentShortfallException;
}

public class InventoryService {
    public List<Item> getAllItems();
    public List<Item> searchItems(String keyword, String category);
    public Item getItemByCode(String code);
    public void restockItem(String itemCode, int quantityAdded, String batchNotes, String operator);
    public List<RestockAudit> getRestockAuditHistory();
}

public class PriceService {
    public void updateSellingPrice(String itemCode, BigDecimal newPrice, String managerName);
    public List<PriceHistory> getPriceHistory(String itemCode);
}

public class AnalyticsService {
    public AnalyticsReport generateReport(LocalDate startDate, LocalDate endDate);
    public AnalyticsReport generateDailyReport(LocalDate date);
}

public class ReceiptFormatter {
    public String formatThermalReceipt(Receipt receipt);
}
```
