# Milestone 2: Core Business Services and Domain Logic Report

**Worker:** `worker_m2`  
**Parent Agent:** `orchestrator_1` (ID: `9258cf3b-c2e1-4a39-9931-4a3c6469ec69`)  
**Project:** Supermarket Automation Software (SAS)  
**Date:** 2026-10-02  
**Status:** Completed & 100% Verified  

---

## 1. Executive Summary

Milestone 2 delivers the complete headless business domain layer and service tier for the Supermarket Automation Software (SAS). Built strictly on pure Java 21 with **zero JavaFX dependencies**, the core services implement all functional rules for retail POS billing, atomic stock decrement with rollback guarantees, historical price snapshot invariance, supply replenishment audit trails, manager dynamic pricing, period-based sales profit analytics, and 40-column monospace thermal bill generation.

All deliverables have been verified against the authoritatively defined acceptance criteria using automated unit and integration test suites. The entire test suite executes via Maven (`.\mvnw.cmd test`), running **72 tests with 0 failures and 0 errors (100% pass rate)**.

---

## 2. Architecture & Deliverables

### 2.1 Domain Models & Exceptions (`com.supermarket.domain`, `com.supermarket.exception`)
| Class / Record | Responsibility | Characteristics |
|---|---|---|
| `Cart.java` | Active shopping cart state holding line items | Thread-safe, insertion-order preserving, live subtotal computation, quantity aggregation |
| `CartItem.java` | Individual cart line item record | Immutable record with `itemCode`, `itemName`, `unitPrice`, `quantity`, `subtotal` |
| `CartTotals.java` | Financial summary for active cart | Immutable record computing `subtotal`, `taxRate`, `taxAmount`, `totalPayable`, `amountTendered`, `changeDue`, `shortfall` |
| `Receipt.java` | Completed sales transaction receipt | Immutable record bundling `SalesTransaction` master header and `List<ReceiptLineItem>` line records with snapshot prices |
| `AnalyticsReport.java` | Period-based sales & profit report | Immutable record encapsulating date range, `AnalyticsSummary` KPIs, and itemized `List<SalesStatistic>` |
| `InsufficientStockException.java` | Inventory shortfall exception | Holds `itemCode`, `itemName`, `requestedQuantity`, `availableStock`; triggers atomic rollback |
| `ItemNotFoundException.java` | Unknown item code exception | Thrown when code is missing from catalog |
| `PaymentShortfallException.java` | Cash shortfall exception | Holds `totalPayable`, `amountTendered`, `shortfall`; blocks underpaid checkout |
| `ValidationException.java` | Business rule validation exception | Enforces non-negative inputs, positive quantities, non-empty carts |

### 2.2 Core Services (`com.supermarket.service`)

#### 1. `BillingService.java`
- **Cart Management**: Provides `createCart()`, `addItemToCart()`, `removeItemFromCart()`, `updateCartItemQuantity()`, `clearCart()`, and `computeTotals()`. Auto-consolidates quantities when an item is scanned multiple times.
- **Atomic Checkout**:
  1. Validates that cart is non-empty and `cashTendered >= totalPayable` (throws `PaymentShortfallException` otherwise).
  2. Executes within `db.executeInTransactionWithResult`:
     - Pre-validates inventory across **all** cart items before any decrement occurs.
     - If any item has insufficient stock, throws `InsufficientStockException`, causing a full atomic rollback with zero stock decremented.
     - Atomically decrements stock via `itemRepository.decrementStock(conn, code, qty)`.
     - Generates sequential, collision-free transaction serial numbers formatted as `SAS-YYYYMMDD-XXXXX`.
     - Records master `SalesTransaction` header.
     - Captures immutable price snapshots (`unit_cost_price_snapshot`, `unit_selling_price_snapshot`) from the catalog at the instant of checkout.
     - Persists line items into `sales_transaction_items`.
  3. Commits transaction and clears the active cart upon success.

#### 2. `InventoryService.java`
- Queries all items, performs search by code/name with optional category filter.
- Identifies low-stock items where `current_stock <= min_stock_threshold`.
- Restock Workflow:
  - Validates `quantityAdded > 0` and that item exists.
  - Atomically increments stock in `items` table and records an immutable audit log entry in `restock_audit_log` with operator and batch notes.
- Queries restock audit history.

#### 3. `PriceService.java`
- Dynamic Pricing:
  - Validates `newSellingPrice > 0` and item exists.
  - In an atomic transaction, updates `current_selling_price` in `items` and appends an audit record to `price_history_log`.
- Queries price change history per item code or catalog-wide.
- Helper `isSellingPriceBelowCost` alerts when a proposed price drops below wholesale cost.

#### 4. `AnalyticsService.java`
- Calculates sales statistics and profit analytics over arbitrary periods (single calendar day or arbitrary date range).
- Queries `SqliteAnalyticsRepository` which aggregates purely from `unit_cost_price_snapshot` and `unit_selling_price_snapshot`.
- Calculates total revenue, total cost (COGS), net profit, margin %, total items sold, and transactions count.
- Packages into `AnalyticsReport`.
- Handles empty periods cleanly without null pointer or division-by-zero errors.

#### 5. `ReceiptFormatter.java`
- Generates standard 40-column monospace thermal bill:
  - Centered store header and contact details.
  - Transaction serial number, date/time, and cashier name.
  - Formatted 5-column itemized table: `Item`, `Code`, `Qty`, `Price`, `Total`.
  - Clean line wrapping with indentation for long item names, preserving strict 40-column alignment.
  - Subtotal, tax (if > 0), Total Amount Payable, Cash Tendered, Change Due.
  - Centered thank-you footer.

---

## 3. Invariance & Integrity Guarantees

1. **Price Snapshot Invariance (Requirement R3)**:
   - When a sale occurs, the item's live cost price and selling price are permanently locked into `sales_transaction_items.unit_cost_price_snapshot` and `unit_selling_price_snapshot`.
   - Subsequent changes to catalog selling price or cost price do not alter historical reports. Tested and proven via `PriceSnapshotInvarianceServiceTest`.
2. **Inventory Atomicity & Rollback (Requirement R1, R2)**:
   - If a customer attempts to purchase multiple items and even 1 item exceeds available inventory, SQLite's write transaction rolls back completely. Zero inventory is decremented for all other items, and no partial transaction is recorded. Tested and proven via `AtomicRollbackServiceTest`.
3. **Monetary Precision**:
   - All arithmetic uses `java.math.BigDecimal` with `RoundingMode.HALF_UP` (2 decimal places).

---

## 4. Test Execution Summary

The project was compiled and verified using the Maven wrapper:
```powershell
.\mvnw.cmd test
```

### Test Suites Executed:
- `com.supermarket.persistence.DatabaseManagerTest`: 6 tests
- `com.supermarket.persistence.EdgeCaseAndConstraintTest`: 8 tests
- `com.supermarket.persistence.PersistenceConcurrencyAndStressTest`: 9 tests
- `com.supermarket.persistence.R3ProfitSnapshotInvarianceChallengerTest`: 12 tests
- `com.supermarket.persistence.RepositoryIntegrationTest`: 7 tests
- `com.supermarket.service.BillingServiceTest`: 11 tests
- `com.supermarket.service.AtomicRollbackServiceTest`: 2 tests
- `com.supermarket.service.PriceSnapshotInvarianceServiceTest`: 1 test
- `com.supermarket.service.InventoryServiceTest`: 7 tests
- `com.supermarket.service.PriceServiceTest`: 3 tests
- `com.supermarket.service.AnalyticsServiceTest`: 4 tests
- `com.supermarket.service.ReceiptFormatterTest`: 2 tests

**Total Tests Run:** 72  
**Failures:** 0  
**Errors:** 0  
**Skipped:** 0  
**Build Result:** `BUILD SUCCESS`
