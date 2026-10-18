# Milestone 1 Review & Adversarial Critic Report

**Reviewer:** Reviewer 2 (`reviewer_m1_2`)  
**Role:** Reviewer & Adversarial Critic  
**Date:** 2026-10-02  
**Target Milestone:** Milestone 1 (Tooling, Build Setup & Persistence Layer)  
**Project Root:** `C:\Users\User\.gemini\antigravity\scratch\supermarket-automation`  
**Verdict:** **`APPROVE`**

---

## 1. Executive Summary

Milestone 1 work completed by `worker_m1` was subjected to rigorous code inspection, test execution, and adversarial security and stress analysis. The deliverable includes the complete Maven project setup configured for OpenJDK 21 LTS and JavaFX 21, the SQLite DDL schema with database-level CHECK constraints and NOCASE collations, migration automation (`MigrationRunner`), 7 domain model records, 5 repository interfaces with 5 SQLite JDBC implementations, and 3 comprehensive automated test suites.

Independent execution of `.\mvnw.cmd test` completed cleanly with **21 tests run, 0 failures, 0 errors, 0 skipped**. No integrity violations, hardcoded test results, facade implementations, or SQL injection vectors were detected. Interface contracts match `PROJECT.md` specifications 100%. Requirement R3 (historical profit invariance via price snapshots) is strictly enforced at both schema and query levels.

---

## 2. Build and Test Verification

Independent verification was conducted using the project's local Maven wrapper on the host Windows environment:

- **Command Executed:** `.\mvnw.cmd test`
- **Working Directory:** `C:\Users\User\.gemini\antigravity\scratch\supermarket-automation`
- **Output:**
  ```text
  [INFO] Running com.supermarket.persistence.DatabaseManagerTest
  [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.941 s -- in com.supermarket.persistence.DatabaseManagerTest
  [INFO] Running com.supermarket.persistence.EdgeCaseAndConstraintTest
  [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.314 s -- in com.supermarket.persistence.EdgeCaseAndConstraintTest
  [INFO] Running com.supermarket.persistence.RepositoryIntegrationTest
  [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.204 s -- in com.supermarket.persistence.RepositoryIntegrationTest
  [INFO] 
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  8.901 s
  ```
- **Attestation:** All 21 tests execute against dynamic, ephemeral in-memory SQLite instances (`:memory:` with shared-cache URI anchor connections) and validate real DDL schemas, CRUD operations, transaction rollbacks, and mathematical price invariance.

---

## 3. Interface Conformance Analysis

Every interface was compared against the contracts published in `PROJECT.md`:

| Contract Defined in `PROJECT.md` | Implementation File | Status | Notes |
|---|---|---|---|
| `DatabaseManager` (`getInstance`, `getConnection`, `executeInTransaction`, `executeInTransactionWithResult`) | `com.supermarket.persistence.DatabaseManager` | **CONFORMS (100%)** | All methods present with exact signatures. Includes additional helper factory methods `createInMemory()` and `forDatabase()`. |
| `ItemRepository` (7 methods: `findByCode`, `findAll`, `search`, `save`, `decrementStock`, `incrementStock`, `updateSellingPrice`) | `com.supermarket.repository.ItemRepository` & `SqliteItemRepository` | **CONFORMS (100%)** | All 7 methods implemented. Provides overloaded variants accepting an explicit `Connection conn` to support transactional composition in `BillingService`. |
| `SalesTransactionRepository` (4 methods: `createTransaction`, `findBySerialNumber`, `findByDateRange`, `findItemsByTransactionId`) | `com.supermarket.repository.SalesTransactionRepository` & `SqliteSalesTransactionRepository` | **CONFORMS (100%)** | All 4 methods implemented with exact parameter types and return signatures. |
| `RestockAuditRepository` (2 methods: `logRestock`, `findAll`) | `com.supermarket.repository.RestockAuditRepository` & `SqliteRestockAuditRepository` | **CONFORMS (100%)** | Both methods implemented, plus `findByItemCode(code)` convenience query. |
| `PriceHistoryRepository` (2 methods: `logPriceChange`, `findByItemCode`) | `com.supermarket.repository.PriceHistoryRepository` & `SqlitePriceHistoryRepository` | **CONFORMS (100%)** | Both methods implemented, plus `findAll()` query. |
| `AnalyticsRepository` (2 methods: `getItemStatistics`, `getSummary`) | `com.supermarket.repository.AnalyticsRepository` & `SqliteAnalyticsRepository` | **CONFORMS (100%)** | Both methods implemented, accepting `(LocalDateTime start, LocalDateTime end)`. |

---

## 4. SQL Safety, Query Parameterization & Resource Management

A line-by-line inspection of all SQL statements was conducted across all 5 SQLite repository classes and the persistence layer:

1. **SQL Injection Defense:**
   - **Zero String Concatenation of Parameters:** All dynamic inputs (item codes, search queries, categories, prices, quantities, serial numbers, cashier names, timestamps) are bound exclusively via positional `?` placeholders on `PreparedStatement`.
   - **Search Query Wildcard Handling:** In `SqliteItemRepository.search`:
     ```java
     sql.append("AND (code LIKE ? OR name LIKE ?) ");
     String wildcard = "%" + query.trim() + "%";
     params.add(wildcard);
     params.add(wildcard);
     ```
     Wildcards are bound as parameter values using `ps.setObject(...)`, preventing SQL escape injection via user-supplied search terms.

2. **Resource Leak Prevention:**
   - All `Connection` instances obtained internally from `DatabaseManager` are wrapped in `try-with-resources`.
   - All `PreparedStatement`, `Statement`, and `ResultSet` instances are closed via `try-with-resources`.
   - For transactional repository overloads where a caller supplies an open `Connection conn`, statements and result sets are closed locally, leaving the connection's transaction lifecycle to the outer transaction manager.

3. **Concurrency & Thread Safety:**
   - SQLite embedded databases in file mode utilize Write-Ahead Logging (`WAL` mode) and synchronous `NORMAL`.
   - `DatabaseManager` enforces write serialization using a fair `ReentrantLock` (`writeLock = new ReentrantLock(true)`), preventing SQLite `busy` or `database locked` exceptions across application threads.
   - A busy timeout of 5000ms is explicitly configured on the datasource and connections (`PRAGMA busy_timeout = 5000;`).

---

## 5. Schema Constraints & Price Snapshotting (Requirement R3)

1. **Schema DDL Verification (`V1__init_schema.sql`):**
   - In table `sales_transaction_items`:
     - `unit_cost_price_snapshot REAL NOT NULL CHECK (unit_cost_price_snapshot >= 0.0)`
     - `unit_selling_price_snapshot REAL NOT NULL CHECK (unit_selling_price_snapshot >= 0.0)`
     - `subtotal REAL NOT NULL CHECK (subtotal >= 0.0)`
     - `quantity INTEGER NOT NULL CHECK (quantity > 0)`
   - In table `items`:
     - `current_stock INTEGER NOT NULL CHECK (current_stock >= 0)`
     - `cost_price REAL NOT NULL CHECK (cost_price >= 0.0)`
     - `current_selling_price REAL NOT NULL CHECK (current_selling_price >= 0.0)`
     - `code TEXT NOT NULL UNIQUE COLLATE NOCASE`
   - In table `sales_transactions`:
     - `serial_number TEXT NOT NULL UNIQUE`
     - `amount_tendered REAL NOT NULL CHECK (amount_tendered >= total_amount)`
     - `change_amount REAL NOT NULL CHECK (change_amount >= 0.0)`

2. **Analytics Query Price Snapshot Invariance:**
   - In `SqliteAnalyticsRepository.getItemStatistics`:
     ```sql
     SELECT 
         sti.item_code,
         sti.item_name,
         SUM(sti.quantity) AS total_quantity_sold,
         ROUND(SUM(sti.subtotal), 2) AS total_revenue,
         ROUND(SUM(sti.quantity * sti.unit_cost_price_snapshot), 2) AS total_cost,
         ROUND(SUM(sti.subtotal - (sti.quantity * sti.unit_cost_price_snapshot)), 2) AS net_profit
     FROM sales_transaction_items sti
     JOIN sales_transactions st ON sti.transaction_id = st.id
     WHERE st.transaction_timestamp >= ? AND st.transaction_timestamp <= ?
     GROUP BY sti.item_code, sti.item_name
     ORDER BY total_revenue DESC
     ```
   - In `SqliteAnalyticsRepository.getSummary`:
     ```sql
     SELECT 
         COUNT(DISTINCT st.id) AS total_transactions,
         COALESCE(SUM(sti.quantity), 0) AS total_items_sold,
         ROUND(COALESCE(SUM(sti.subtotal), 0.0), 2) AS total_revenue,
         ROUND(COALESCE(SUM(sti.quantity * sti.unit_cost_price_snapshot), 0.0), 2) AS total_cost,
         ROUND(COALESCE(SUM(sti.subtotal - (sti.quantity * sti.unit_cost_price_snapshot)), 0.0), 2) AS total_net_profit
     FROM sales_transactions st
     LEFT JOIN sales_transaction_items sti ON st.id = sti.transaction_id
     WHERE st.transaction_timestamp >= ? AND st.transaction_timestamp <= ?
     ```
   - **Verification Finding:** The analytics queries compute revenue, cost, and net profit exclusively from `sales_transaction_items.unit_cost_price_snapshot` and `sales_transaction_items.subtotal`. They do **not** join against `items.cost_price` or `items.current_selling_price`.
   - In `RepositoryIntegrationTest.testPriceSnapshotInvarianceForAnalytics`, catalog selling prices were mutated from $2.99 to $9.99 (+234%) and cost prices from $1.50 to $6.00 (+300%). Historical sales analytics for transactions created prior to the update remained identical down to the cent ($29.90 revenue, $15.00 cost, $14.90 profit). Requirement R3 is mathematically guaranteed.

---

## 6. Edge Case Resilience

The implementation handles boundary conditions and hostile inputs effectively:
- **Zero & Negative Quantities:** `decrementStock` and `incrementStock` explicitly validate `quantity <= 0` and throw `IllegalArgumentException`.
- **Negative Stock Prevention:** Two layers of defense protect stock integrity:
  1. Application/SQL level: `UPDATE items SET current_stock = current_stock - ? ... WHERE code = ? AND current_stock >= ?`. If stock is insufficient, zero rows are affected and the method returns `false`.
  2. Database level: SQLite `CHECK (current_stock >= 0)` constraint in table definition.
- **Empty Periods in Analytics:** If no transactions fall within a requested date window, `getSummary` returns an `AnalyticsSummary` with zeroes rather than NULLs, and `getProfitMarginPercentage()` guards against division by zero by returning `0.0`.
- **Case-Insensitive Item Lookup:** Column `items.code` utilizes `COLLATE NOCASE`, permitting codes like `"prod-001"`, `"PROD-001"`, and `"PrOd-001"` to resolve to the same record and disallowing duplicate insertions differing only by case.
- **Transaction Cascading:** Foreign keys with `ON DELETE CASCADE` ensure line items are automatically pruned if a parent sales transaction is deleted.

---

## 7. Adversarial Review & Integrity Assessment

1. **Integrity Violations Check:**
   - Embedded hardcoded test values: **NONE**. All calculations are performed by SQLite aggregate functions or domain math methods.
   - Facade or dummy implementations: **NONE**. All 5 repositories execute genuine SQLite queries against real tables.
   - Shortcuts: **NONE**. The schema is complete with 6 tables, indices, migrations, seed data, and full domain entities.
   - Fabricated verification: **NONE**. Test execution was independently repeated and matched `worker_m1` logs exactly.

2. **Adversarial Stress Testing & Recommendations for Milestone 2:**
   - *Advisory for M2 (BillingService Transaction Composition):* When implementing `BillingService.checkout`, the service should invoke repository methods that accept `Connection conn` (e.g. `itemRepo.decrementStock(conn, code, qty)`, `salesRepo.createTransaction(conn, tx, items)`) within `databaseManager.executeInTransaction(conn -> ...)`. Invoking connection-less overloads (e.g. `save(item)`) inside `executeInTransaction` would spawn an independent connection outside the active rollback boundary.

---

## 8. Final Verdict

**Verdict: `APPROVE`**  
The Milestone 1 persistence layer, schema constraints, migration runner, domain models, and test fixtures are robust, fully tested, and ready for consumption by Milestone 2 (Core Business Services & Headless Logic).
