# Supermarket Automation Software (SAS) — Specification Mining Report

**Date**: 2026-10-02  
**Author**: Requirement Spec Miner  
**Authoritative Specification**: `ORIGINAL_REQUEST.md`  
**Workspace**: `C:\Users\User\.gemini\antigravity\scratch\supermarket-automation`  

---

## 1. Executive Summary & System Overview

The Supermarket Automation Software (SAS) is a production-grade desktop application engineered using **JavaFX** for the presentation layer and **SQLite** for ACID-compliant persistence. The system automates the core retail workflow of a supermarket across three operational roles:
1. **Sales Clerk (POS)**: Rapid item lookup/scanning, dynamic cart building, subtotal and change computation, atomic inventory decrement upon purchase, and generation of a formatted, printable bill.
2. **Stock Employee (Restock)**: Inventory queries, receiving new supply batches, stock quantity increments, and mandatory audit log generation.
3. **Store Manager (Pricing & Analytics)**: Dynamic selling price updates responding to daily market variations, and granular sales/profit analytics over selectable single days or arbitrary date ranges without retroactive distortion of historical financial metrics.

---

## 2. Features Discovered

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | POS & Billing | Item Scanning / Code Lookup | Fast search and selection of items by unique item code or barcode | Item Code (`String`), optional scan input | Item details populated (Name, Unit Price, Available Stock) | If item does not exist: display error alert / inline message "Item code not found". Cart unchanged. | `ORIGINAL_REQUEST.md` § R1 |
| 2 | POS & Billing | Cart Line Addition & Consolidation | Add scanned item with specified quantity to active transaction cart; auto-accumulate if already in cart | Item Code, Quantity (`int > 0`) | Cart item list updated, row added or quantity updated, line total calculated | If quantity <= 0: reject with validation error. If requested quantity > available stock: block addition or alert insufficient stock. | `ORIGINAL_REQUEST.md` § R1 |
| 3 | POS & Billing | Cart Line Modification & Removal | Adjust quantity of item already in cart or remove line item | Cart Line ID / Item Code, new quantity or remove action | Updated cart line total and cart summary | If new quantity <= 0: prompt to remove or remove line. If new quantity > stock: display error and clamp/revert. | `ORIGINAL_REQUEST.md` § R1 |
| 4 | POS & Billing | Real-Time Subtotal Computation | Automatically recompute transaction subtotal as cart items are added, modified, or removed | Active cart line items | Subtotal amount (`BigDecimal` / currency format) | None (pure deterministic calculation, $0.00 for empty cart). | `ORIGINAL_REQUEST.md` § R1 |
| 5 | POS & Billing | Cash Tendered & Real-Time Change Calculation | Computes change due dynamically as the clerk enters amount paid | Amount Tendered (`BigDecimal`) | Change Due (`Amount Tendered - Subtotal`) | If Tendered < Subtotal: display negative change / balance due; disable Checkout confirmation. | `ORIGINAL_REQUEST.md` § R1 |
| 6 | POS & Billing | Atomic Checkout & Stock Decrement | Atomically decrement database inventory and commit receipt header and snapshot line items in a single DB transaction | Checkout trigger, active cart, amount tendered | Transaction committed, unique Serial Number generated, stock decreased, cart cleared | If DB failure or stock dropped below cart quantity: entire transaction rolls back; no stock decremented; error displayed. | `ORIGINAL_REQUEST.md` § R1, Acceptance Criteria |
| 7 | POS & Billing | Thermal-Style Receipt Preview & Print | Renders formatted bill with store header, serial number, itemized lines, totals, tendered, change, and triggers system print | Completed transaction record | Modal preview window with printable layout, printer dialog invocation | If printing fails: print error dialog; receipt data remains safely saved in SQLite. | `ORIGINAL_REQUEST.md` § R1, R5 |
| 8 | POS & Billing | Clear Cart / Cancel Transaction | Abort current pending billing session without modifying database stock | Cancel / Clear button click | Empty cart, reset tendered and change fields | If cart is already empty: no-op. No stock modification occurs. | `ORIGINAL_REQUEST.md` § R1 |
| 9 | Inventory | Catalog Display & Table View | Comprehensive tabular display of all items with Code, Name, Category, Cost Price, Selling Price, and Stock Quantity | Table load / refresh | Observable list rendered in JavaFX TableView with sortable columns | If database query fails: error notification; empty table view. | `ORIGINAL_REQUEST.md` § R2 |
| 10 | Inventory | Real-Time Inventory Search & Filtering | Filter catalog records dynamically by item code, item name, or category | Search query string | Filtered subset of inventory items in TableView | If no match found: display "No matching items found" in table placeholder. | `ORIGINAL_REQUEST.md` § R2 |
| 11 | Inventory | Stock Quantity Low-Level Indicator | Visual badge or highlighting for items with low stock (e.g. stock <= 5 or 0) | Current stock value | Visual styling (warning colors / tags) in inventory table | None. | `ORIGINAL_REQUEST.md` § R2 |
| 12 | Restock | Supply Batch Restock Interface | Employee UI to receive and add incoming supply batches to existing inventory | Item Code, Restock Quantity (`int > 0`), optional Operator Name / Batch Ref | Database inventory increased (`stock = stock + qty`) | If quantity <= 0: reject with error "Restock quantity must be greater than zero". If item code invalid: error "Item not found". | `ORIGINAL_REQUEST.md` § R2, Acceptance Criteria |
| 13 | Restock | Restock Batch Audit Logging | Every restock event automatically registers an immutable audit record with timestamp, item, quantity added, old/new stock, operator | Restock event execution | Audit record inserted into `restock_audit_logs` table | If audit log insertion fails: restock transaction rolls back to preserve audit integrity. | `ORIGINAL_REQUEST.md` § R2, Acceptance Criteria |
| 14 | Restock | Audit Log Viewer | Staff/Manager view of historical restock operations | Filter / date range / item filter | Table of restock records (Timestamp, Code, Name, Qty Added, Prev Stock, New Stock, Operator) | If query fails: display error alert. | `ORIGINAL_REQUEST.md` § R2, Acceptance Criteria |
| 15 | Price Management | Manager Dynamic Price Update | Manager interface to update the retail selling price of any catalog item | Item Code, New Selling Price (`BigDecimal > 0`) | Updated selling price in `items` table; catalog reflects new price immediately | If price <= 0: reject with error "Selling price must be greater than zero". If item not found: error. | `ORIGINAL_REQUEST.md` § R4 |
| 16 | Price Management | Price History Audit / Log (Optional Enhancement) | Tracks price modification history (old price, new price, timestamp, changed by) | Price update execution | Record in price history log | If failed: logged to application log. | `ORIGINAL_REQUEST.md` § R4 |
| 17 | Price Management | Margin & Markup Indicator | Computes and displays current gross profit margin percentage `(Selling - Cost) / Selling * 100%` during price edit | Cost Price, Proposed Selling Price | Margin % indicator in UI | If Selling Price < Cost Price: warning indicator "Selling price below cost price (negative margin)". | `ORIGINAL_REQUEST.md` § R4, R3 |
| 18 | Analytics | Single Day Sales Statistics | Displays sales statistics for any selected calendar day | Selected Date (`LocalDate`) | Summary metrics (Total Qty Sold, Revenue Realized, Net Profit) + Itemized breakdown table | If no sales on date: show zeroed metrics and empty/clean table. | `ORIGINAL_REQUEST.md` § R3 |
| 19 | Analytics | Arbitrary Date Range Filtering | Calculates aggregated sales and profit between Start Date and End Date (inclusive) | Start Date (`LocalDate`), End Date (`LocalDate`) | Aggregated metrics and itemized breakdown table for the specified window | If Start Date > End Date: show error "Start date cannot be after end date". | `ORIGINAL_REQUEST.md` § R3 |
| 20 | Analytics | Item-Level Quantity Sold Tracking | Total units sold per item across the selected period | Sales query over selected date window | Count of units sold per item | Zero if item had no sales in the period. | `ORIGINAL_REQUEST.md` § R3 |
| 21 | Analytics | Item-Level Realized Price (Revenue) | Actual gross revenue generated by each item based on historical transaction prices | Sum of line totals for the item in the period | Realized revenue amount ($) | $0.00 if no sales. | `ORIGINAL_REQUEST.md` § R3 |
| 22 | Analytics | Item-Level Net Profit Tracking | Computes net profit per item as `Realized Revenue - Cost of Goods Sold (COGS)` using snapshotted cost and selling prices | Transaction-time snapshotted prices in receipt lines | Net profit amount ($) and profit margin % | Reflects accurate net profit, impervious to subsequent catalog price edits. | `ORIGINAL_REQUEST.md` § R3, Acceptance Criteria |
| 23 | Analytics | Sales Statistics Print / Export | Generate printable or exportable summary report of sales and profit for the selected period | Report trigger | Formatted printable view or CSV/PDF summary | Print dialog / file save error handling. | `ORIGINAL_REQUEST.md` § R3 |
| 24 | UI / UX | Modern JavaFX Tabbed Shell | Integrated desktop application interface containing tabs: POS, Inventory, Restock, Dynamic Pricing, Analytics | Application launch | Polished JavaFX window with tab switching, status bar, and responsive layout | JavaFX initialization or UI thread exceptions logged gracefully. | `ORIGINAL_REQUEST.md` § R5 |
| 25 | Packaging | Standalone Windows Packaging & Maven Build | Automated Maven build (`mvn clean test package`) producing executable JAR / JavaFX launcher for Windows | Build command | Tested executable artifact running on Windows OpenJDK | Build fails if automated tests fail or dependencies unresolved. | `ORIGINAL_REQUEST.md` § R5, Acceptance Criteria |

---

## 3. Invariance Requirements & Mathematical Guarantees

### 3.1 The Price Snapshotting Invariance Contract (R3 & Acceptance Criteria)
**Core Invariant Statement**:  
> *Updating an item's selling price (or cost price) in the inventory catalog at time $T_{update}$ must NEVER modify, corrupt, or retroactively recalculate the transaction line prices, realized revenue, or net profit of any transaction completed at time $T_{sale} < T_{update}$.*

#### Mathematical Formulation:
Let a transaction $k$ completed at time $t_k$ contain an item $i$ with quantity $q_{k,i}$.
- At time $t_k$, the item has catalog selling price $P_{catalog}(i, t_k)$ and cost price $C_{catalog}(i, t_k)$.
- When transaction $k$ is committed, the receipt item record **must capture immutable snapshots**:
  $$P_{snap}(k, i) = P_{catalog}(i, t_k)$$
  $$C_{snap}(k, i) = C_{catalog}(i, t_k)$$
  $$\text{LineRevenue}(k, i) = q_{k,i} \times P_{snap}(k, i)$$
  $$\text{LineCOGS}(k, i) = q_{k,i} \times C_{snap}(k, i)$$
  $$\text{LineProfit}(k, i) = \text{LineRevenue}(k, i) - \text{LineCOGS}(k, i) = q_{k,i} \times (P_{snap}(k, i) - C_{snap}(k, i))$$

- At a later time $t' > t_k$, the manager updates the selling price in the catalog:
  $$P_{catalog}(i, t') \neq P_{catalog}(i, t_k)$$
- **Invariant Requirement**:
  $$\forall t' > t_k, \quad \frac{\partial \text{LineRevenue}(k, i)}{\partial P_{catalog}(i, t')} = 0 \quad \text{and} \quad \frac{\partial \text{LineProfit}(k, i)}{\partial P_{catalog}(i, t')} = 0$$
- When calculating sales analytics over any period $[T_{start}, T_{end}]$ containing $t_k$:
  $$\text{TotalRevenue}(i) = \sum_{k \in [T_{start}, T_{end}]} q_{k,i} \times P_{snap}(k, i)$$
  $$\text{TotalProfit}(i) = \sum_{k \in [T_{start}, T_{end}]} q_{k,i} \times (P_{snap}(k, i) - C_{snap}(k, i))$$
  Under no circumstance should the query join against `items.selling_price` or `items.cost_price` to compute revenue or profit.

### 3.2 Inventory Non-Negativity & Atomic Decrement Invariance (R1, R2)
**Core Invariant Statement**:  
> *Inventory stock for any item $i$ must satisfy $\text{stock}(i) \ge 0$ at all times. A sale of $q$ units of item $i$ is valid if and only if $\text{stock}_{pre}(i) \ge q$, and upon completion, $\text{stock}_{post}(i) = \text{stock}_{pre}(i) - q$ atomically.*

- **Database Constraint**: `CHECK(stock_quantity >= 0)` on the `items` table.
- **Transaction Atomicity**: The entire multi-item cart decrement and receipt creation must be executed inside a single SQLite transaction (`BEGIN TRANSACTION ... COMMIT`). If any single item fails the stock constraint, SQLite or business validation will trigger `ROLLBACK`, guaranteeing that zero items are decremented and no partial receipt is created.

### 3.3 Restock Audit Logging Invariance (R2)
**Core Invariant Statement**:  
> *Every increment to item stock via supply restocking must be accompanied by an immutable audit log record created within the same atomic transaction.*

- $\text{stock}_{post}(i) = \text{stock}_{pre}(i) + \Delta_{restock}$ where $\Delta_{restock} > 0$.
- `restock_audit_logs` must record: `timestamp`, `item_code`, `item_name`, `quantity_added`, `previous_stock`, `new_stock`, `operator_name`.
- Audit logs are append-only; update and delete operations are forbidden.

### 3.4 Financial Precision Invariance
**Core Invariant Statement**:  
> *All monetary calculations must be free from IEEE 754 floating-point rounding errors.*

- All calculations (subtotal, line totals, tax, cash tendered, change, revenue, COGS, profit) must use `java.math.BigDecimal` with `RoundingMode.HALF_UP` (2 decimal places) or integer cents representation.

---

## 4. Edge Cases & Boundary Conditions

| # | Feature | Input / Condition | Expected Observed Behavior |
|---|---------|-------------------|----------------------------|
| 1 | POS Checkout | Cart item quantity exceeds available stock (e.g., requested 10, stock 4) | Checkout is blocked. Clear alert dialog: "Insufficient stock for [Item Name]. Requested: 10, Available: 4". Database transaction does not commit; inventory remains 4. |
| 2 | POS Checkout | Stock drops concurrently before checkout confirmation | Atomic transaction attempts update with `WHERE stock_quantity >= ?`. If update count is 0 or CHECK constraint violated, transaction rolls back cleanly, displays error, and prompts clerk to refresh cart. |
| 3 | POS Cart | Adding the same item code multiple times | Cart consolidates into a single line row with accumulated quantity `qty = qty_existing + qty_new`, recalculating line total. Stock check verifies `accumulated_qty <= stock`. |
| 4 | POS Cart | Clerk attempts checkout with empty cart (0 items) | Checkout button is disabled or clicking shows warning: "Cart is empty. Please scan or add items before checkout." No receipt is generated; no DB queries executed. |
| 5 | POS Cart | Quantity entered is zero (`0`) or negative (`-5`) | Input validation fails immediately with tooltip/dialog: "Quantity must be a positive integer greater than zero." Cart line is not added or updated. |
| 6 | POS Cart | Non-numeric characters entered in quantity field (e.g., `"abc"`, `"!@"`, `""`) | UI validation prevents typing or displays format error: "Please enter a valid numeric integer." |
| 7 | POS Billing | Amount Tendered is less than Subtotal (e.g., Subtotal = $25.00, Tendered = $20.00) | Change display indicates shortfall (e.g. "-$5.00" or red highlight). Checkout button is disabled or clicking alerts "Payment tendered ($20.00) is insufficient for total ($25.00)". |
| 8 | POS Billing | Amount Tendered exactly equals Subtotal (e.g., Subtotal = $25.00, Tendered = $25.00) | Change displays exactly `$0.00`. Checkout proceeds normally. |
| 9 | POS Billing | High cash tendered (e.g., Subtotal = $2.50, Tendered = $100.00) | Change displays `$97.50` accurately without floating point precision artifact (e.g. not `97.499999999`). |
| 10 | POS Item Scan | Entering non-existent item code (e.g. `"UNKNOWN999"`) | Search yields no record; UI shows error: "Item code 'UNKNOWN999' not found in catalog." Input field is highlighted; cart remains intact. |
| 11 | POS Item Scan | Case sensitivity and leading/trailing whitespace in item code (e.g. `"  item001  "`) | Code input is trimmed (`.trim()`) and normalized (e.g. uppercase if alphanumeric) so valid items resolve cleanly regardless of accidental whitespace. |
| 12 | Restock | Entering zero or negative restock quantity (e.g. `0`, `-10`) | Validation error: "Restock quantity must be a positive integer (> 0)." Restock button disabled; no DB update or audit log written. |
| 13 | Restock | Large restock quantity (e.g. `+100,000` units) | Stock increases without integer overflow (safe handling up to `Integer.MAX_VALUE`). Audit log records the exact previous and new stock. |
| 14 | Restock | Restocking item with 0 current stock | Stock transitions from 0 to $\Delta_{restock}$; low-stock badge clears immediately in inventory table; audit log records `previous_stock = 0`, `new_stock = qty`. |
| 15 | Dynamic Pricing | Manager enters selling price equal to zero or negative (e.g., `$0.00`, `-$15.50`) | Validation error: "Selling price must be greater than zero." Price update is blocked. |
| 16 | Dynamic Pricing | Manager enters selling price less than cost price (e.g., Cost = $10.00, New Price = $8.00) | System permits (for promotional clearance / loss leaders) but displays a distinct warning dialog: "Warning: New selling price ($8.00) is below cost price ($10.00). Gross margin will be negative (-25%). Proceed?" |
| 17 | Dynamic Pricing | Catalog price changed right after a sale | Transaction completed at $10.00. Manager changes price to $15.00. Historical receipt remains at $10.00. Sales analytics for today reflect $10.00 revenue and original profit. Next sale uses $15.00. |
| 18 | Dynamic Pricing | Modifying cost price in catalog | Does not affect cost price recorded in past `receipt_items` snapshots. Past net profit remains 100% stable. |
| 19 | Sales Analytics | Start Date equals End Date (e.g., 2026-10-02 to 2026-10-02) | Query properly spans the full 24 hours of that single day: `timestamp >= '2026-10-02T00:00:00' AND timestamp <= '2026-10-02T23:59:59'`. Displays all sales made on that day. |
| 20 | Sales Analytics | Start Date is strictly after End Date (e.g., Start: 2026-10-10, End: 2026-10-05) | UI validation detects invalid date interval: "Start date (2026-10-10) cannot be after End date (2026-10-05)." Query execution is prevented. |
| 21 | Sales Analytics | Date range with zero sales (e.g., a future date or holiday) | Analytics view displays gracefully: Total Sales = $0.00, Total Profit = $0.00, Total Items Sold = 0, empty table with message "No sales transactions recorded in this period." No null pointer or division-by-zero exceptions. |
| 22 | Sales Analytics | Leap Year date range (e.g., Feb 29 in leap years like 2024 or 2028) | Handled correctly via `java.time.LocalDate` parsing; leap day queries execute without calendar arithmetic errors. |
| 23 | Sales Analytics | Year-crossing date range (e.g., 2025-12-25 to 2026-01-05) | Query accurately aggregates across calendar years based on standard ISO-8601 string or epoch comparison in SQLite. |
| 24 | Sales Analytics | Item sold with zero net profit (Selling Price = Cost Price) | Profit computed as `$0.00`, margin computed as `0.00%`. Displayed without numerical formatting errors. |
| 25 | Sales Analytics | Item sold with negative profit (Selling Price < Cost Price) | Realized revenue positive, Net Profit negative (e.g. `-$2.50`), displayed with clear minus sign / red text; overall aggregate net profit accurately reduced. |
| 26 | Receipt Generation | Receipt serial number uniqueness | Generated via monotonic database sequence or formatted timestamp + ID (e.g. `REC-20261002-0001`); guaranteed unique across all transactions even under high transaction volume. |
| 27 | Receipt Generation | Long item names (e.g. "Organic Fair-Trade Extra Virgin Olive Oil 1L") | Receipt layout truncates or cleanly wraps item name across lines without breaking the columnar alignment of Code, Qty, Unit Price, and Item Total. |
| 28 | Persistence | Application relaunch / SQLite database reconnect | All catalog items, stock levels, historical receipts, and restock logs persist across application restarts. SQLite schema is verified or created on startup with `IF NOT EXISTS`. |

---

## 5. Persistence Architecture & Database Schema Specification

To satisfy all invariance and transactional requirements, the SQLite database must adhere to the following schema:

```sql
-- 1. Inventory Catalog Table
CREATE TABLE IF NOT EXISTS items (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'General',
    cost_price REAL NOT NULL CHECK(cost_price >= 0),
    selling_price REAL NOT NULL CHECK(selling_price >= 0),
    stock_quantity INTEGER NOT NULL CHECK(stock_quantity >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 2. Sales Receipt Master Table
CREATE TABLE IF NOT EXISTS receipts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_number TEXT UNIQUE NOT NULL,
    transaction_timestamp TEXT NOT NULL, -- ISO-8601: YYYY-MM-DDTHH:MM:SS
    subtotal REAL NOT NULL CHECK(subtotal >= 0),
    tax REAL NOT NULL DEFAULT 0.0 CHECK(tax >= 0),
    total_amount REAL NOT NULL CHECK(total_amount >= 0),
    cash_tendered REAL NOT NULL CHECK(cash_tendered >= 0),
    change_due REAL NOT NULL CHECK(change_due >= 0),
    clerk_id TEXT DEFAULT 'Clerk-1'
);

-- 3. Sales Receipt Items Snapshot Table (MANDATORY INVARIANCE)
CREATE TABLE IF NOT EXISTS receipt_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    receipt_id INTEGER NOT NULL,
    item_code TEXT NOT NULL,
    item_name TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK(quantity > 0),
    unit_cost_price REAL NOT NULL CHECK(unit_cost_price >= 0),    -- Cost price snapshot at sale time
    unit_selling_price REAL NOT NULL CHECK(unit_selling_price >= 0), -- Selling price snapshot at sale time
    line_total REAL NOT NULL CHECK(line_total >= 0),
    FOREIGN KEY(receipt_id) REFERENCES receipts(id) ON DELETE CASCADE
);

-- 4. Employee Restock Audit Log Table
CREATE TABLE IF NOT EXISTS restock_audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL, -- ISO-8601: YYYY-MM-DDTHH:MM:SS
    item_code TEXT NOT NULL,
    item_name TEXT NOT NULL,
    quantity_added INTEGER NOT NULL CHECK(quantity_added > 0),
    previous_stock INTEGER NOT NULL CHECK(previous_stock >= 0),
    new_stock INTEGER NOT NULL CHECK(new_stock >= 0),
    operator_name TEXT NOT NULL DEFAULT 'Employee',
    notes TEXT
);

-- Indexes for performance & rapid period querying
CREATE INDEX IF NOT EXISTS idx_receipts_timestamp ON receipts(transaction_timestamp);
CREATE INDEX IF NOT EXISTS idx_receipt_items_receipt_id ON receipt_items(receipt_id);
CREATE INDEX IF NOT EXISTS idx_receipt_items_item_code ON receipt_items(item_code);
CREATE INDEX IF NOT EXISTS idx_restock_timestamp ON restock_audit_logs(timestamp);
```

### Analytics SQL Query Contract:
```sql
SELECT 
    ri.item_code,
    ri.item_name,
    SUM(ri.quantity) AS total_quantity_sold,
    SUM(ri.quantity * ri.unit_selling_price) AS total_revenue,
    SUM(ri.quantity * ri.unit_cost_price) AS total_cogs,
    SUM(ri.quantity * (ri.unit_selling_price - ri.unit_cost_price)) AS total_net_profit
FROM receipt_items ri
JOIN receipts r ON ri.receipt_id = r.id
WHERE r.transaction_timestamp >= :start_timestamp 
  AND r.transaction_timestamp <= :end_timestamp
GROUP BY ri.item_code, ri.item_name
ORDER BY total_net_profit DESC;
```
*Notice that `items` table is not joined here — ensuring 100% price and profit invariance regardless of future price changes.*

---

## 6. Printable Receipt Specification

Per requirement R1, the receipt preview and printout must contain the following required fields in a clear, thermal-style monospace layout:

```text
========================================
         SUPERMARKET AUTOMATION         
         123 Market Street, City        
            Tel: (555) 019-2834         
========================================
Receipt No: REC-20261002-0001
Date/Time : 2026-10-02 14:32:15
Cashier   : Clerk-1
----------------------------------------
Item Name           Code   Qty  Price  Total
----------------------------------------
Whole Milk 1L       MILK01   2   3.50   7.00
Whole Wheat Bread   BRD002   1   2.80   2.80
Organic Eggs (12pk) EGG003   1   4.20   4.20
----------------------------------------
Subtotal:                              $14.00
Tax (0%):                               $0.00
TOTAL AMOUNT PAYABLE:                  $14.00
----------------------------------------
Cash Tendered:                         $20.00
Change Due:                             $6.00
========================================
       Thank you for shopping with us!  
          Please visit again soon!      
========================================
```

---

## 7. Acceptance Criteria Traceability Matrix

| Acceptance Criterion | Verification Strategy | Target Modules |
|----------------------|-----------------------|----------------|
| **AC-1: Maven builds cleanly (`mvn clean test`) with automated unit tests** | Standard Maven project structure (`pom.xml`) with JUnit 5 / AssertJ tests executed via `mvn test`. | Project Build, CI/CD pipeline |
| **AC-2: Atomic stock decrement on sale** | Unit test: execute sale of item with initial stock $S$. Assert post-sale stock is $S - Q$. | `SalesService`, `InventoryDao` |
| **AC-3: Atomic rollback on insufficient stock** | Unit test: cart with Item A (stock 10, buy 5) and Item B (stock 2, buy 5). Execute checkout. Assert transaction throws `InsufficientStockException`, Item A stock remains 10, Item B stock remains 2, and no receipt is created. | `SalesService`, `TransactionManager` |
| **AC-4: Price snapshot invariance** | Unit test: Sell Item X at $10.00 (cost $6.00). Assert profit is $4.00. Change Item X catalog price to $20.00 (cost $15.00). Re-run period profit query for sale date. Assert realized price remains $10.00 and profit remains $4.00. | `AnalyticsService`, `PriceService` |
| **AC-5: Employee restock increments stock & writes audit log** | Unit test: Item stock is 10. Call `restockItem("CODE", 15, "Staff-1")`. Assert stock is 25. Query `restock_audit_logs`. Assert entry exists with `previous_stock=10`, `new_stock=25`, `quantity_added=15`. | `RestockService`, `AuditDao` |
| **AC-6: Sales statistics accurately filter by selected date range** | Unit test: Create sales on Day 1, Day 2, Day 3. Query Day 2 only. Assert Day 1 and Day 3 sales are excluded. Query Day 1 to Day 2. Assert Day 3 excluded. | `AnalyticsService`, `ReceiptDao` |
| **AC-7: Unique serial number & itemized printable receipt** | Unit test & UI test: Checkout generates unique serial string, receipts table enforces unique constraint. Receipt formatting function outputs all required fields. | `ReceiptFormatter`, `SalesService` |
| **AC-8: Standalone JavaFX Desktop App execution** | Maven JavaFX plugin (`javafx-maven-plugin`) configuration and shade / assembly packaging for standalone run. | `pom.xml`, JavaFX Launcher |

---

## 8. Recommendations for Implementation Architecture

1. **Layered Design**:
   - `model`: Immutable domain models (`Item`, `Receipt`, `ReceiptItem`, `RestockLog`, `SalesStat`).
   - `dao` / `repository`: SQLite data access objects with prepared statements (`ItemDao`, `ReceiptDao`, `RestockDao`, `AnalyticsDao`).
   - `service`: Business logic & transaction boundaries (`SalesService`, `InventoryService`, `PricingService`, `AnalyticsService`).
   - `ui` / `controller`: JavaFX controllers for each tab (`PosController`, `InventoryController`, `RestockController`, `PricingController`, `AnalyticsController`, `ReceiptModalController`).
   - `util`: Currency formatting, date formatting, receipt text generator, database connection provider.
2. **Database Management**:
   - Use SQLite JDBC connection pool or single persistent connection with WAL mode (`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;`).
   - Initialize schema with sample supermarket items (Groceries, Dairy, Produce, Bakery, Beverages) if database is newly created.
3. **Threading**:
   - Long database queries in Analytics or large Restock imports should execute on JavaFX background threads (`Task<T>`) to keep the UI smooth and responsive.
