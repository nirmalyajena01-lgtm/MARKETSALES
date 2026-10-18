# Forensic Audit Report: Milestone 1 (Tooling, Build Setup & Persistence Core)

**Target Work Product**: `src/main/resources/db/migrations/`, `src/main/java/com/supermarket/`, `src/test/java/com/supermarket/persistence/`  
**Profile**: General Project (Integrity Forensics)  
**Integrity Mode**: Development Mode (from `ORIGINAL_REQUEST.md`)  
**Verdict**: **CLEAN**

---

## Executive Summary
Milestone 1 implements the complete foundation for the Supermarket Automation Software (SAS) desktop application:
1. Java 21 LTS build configuration via Maven Wrapper (`mvnw.cmd`) and `pom.xml`.
2. Relational SQLite 3 schema migrations (`V1__init_schema.sql` and `V2__seed_initial_catalog.sql`) enforcing check constraints, foreign keys, and collation.
3. Thread-safe persistence infrastructure (`DatabaseManager` and `MigrationRunner`).
4. 7 pure immutable domain records in `com.supermarket.domain` without external UI framework leakage.
5. 5 SQLite JDBC repositories executing authentic parameterized SQL statements.
6. 21 automated unit and integration tests executing real SQL transactions against real SQLite databases with genuine assertions.

All checks passed empirically with zero integrity violations detected.

---

## Phase Results

| Check # | Check Name | Mode | Status | Details |
|---|---|---|---|---|
| 1 | Authentic Persistence Implementation | Dev / Demo / Bench | **PASS** | `DatabaseManager`, `MigrationRunner`, and all 5 repositories execute genuine SQLite JDBC queries against real tables; zero mocks or fakes in production code. |
| 2 | Genuine Test Verification | Dev / Demo / Bench | **PASS** | All 21 tests in `src/test/java/com/supermarket/persistence/` assert real database state (e.g., table counts, rollback effects, constraint violations, cascade deletes). No tautological assertions (`true == true`). |
| 3 | Invariance Snapshot Integrity | Dev / Demo / Bench | **PASS** | `sales_transaction_items` stores `unit_cost_price_snapshot` and `unit_selling_price_snapshot`. `SqliteAnalyticsRepository` queries these snapshot columns directly without joining to `items`, mathematically guaranteeing Requirement R3. |
| 4 | Clean Build & Test Execution | Dev / Demo / Bench | **PASS** | `.\mvnw.cmd test` executed cleanly on Windows (exit code 0); 21 tests passed in 5.487s. |
| 5 | Prohibited Pattern Scan | Dev / Demo / Bench | **PASS** | Zero hardcoded test outputs, zero facade methods, zero pre-populated verification artifacts, zero third-party delegation of core logic. |

---

## Detailed Forensic Evidence

### 1. Authentic Implementation Verification
- **`DatabaseManager.java`**: Uses `org.sqlite.SQLiteDataSource` with `PRAGMA foreign_keys = ON;`, `PRAGMA busy_timeout = 5000;`, and `SQLiteConfig.JournalMode.WAL` for file databases. For in-memory testing, it generates isolated shared-cache database URLs (`jdbc:sqlite:file:memdb_<nano>_<counter>?mode=memory&cache=shared`) with an active anchor connection, guaranteeing complete test isolation without disk pollution.
- **`MigrationRunner.java`**: Reads migration scripts directly from classpath resources (`/db/migrations/V1__init_schema.sql` and `/db/migrations/V2__seed_initial_catalog.sql`), parses statements, executes DDL/DML, and records applied migrations in `schema_version`.
- **5 Repositories**:
  - `SqliteItemRepository`: Executes parameterized CRUD queries, conditional atomic decrement (`WHERE code = ? AND current_stock >= ?`), increment, and search queries with wildcard parameters.
  - `SqliteSalesTransactionRepository`: Uses JDBC batch statements to persist line items linked by generated transaction keys.
  - `SqliteRestockAuditRepository`: Inserts and reads restock audit history ordered chronologically.
  - `SqlitePriceHistoryRepository`: Records price modifications and provides historical audit querying.
  - `SqliteAnalyticsRepository`: Aggregates historical revenue, costs, and profit over arbitrary date intervals.

### 2. Invariance Snapshot Integrity (Requirement R3)
In `SqliteAnalyticsRepository.java` (lines 42–55 and 90–100):
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
**Forensic Observation**: The query joins `sales_transaction_items` solely to `sales_transactions`. The catalog table `items` is **NOT** joined. Hence, modifying `items.current_selling_price` or `items.cost_price` at any point in the future has mathematically zero impact on past transactions or analytics.

This was empirically proven by `RepositoryIntegrationTest.testPriceSnapshotInvarianceForAnalytics`:
- Initial price: $2.99 selling, $1.50 cost (revenue: $29.90, profit: $14.90).
- Catalog price altered to: $9.99 selling, $6.00 cost.
- Historical analytics query re-executed: returned exactly $29.90 revenue and $14.90 profit.

### 3. Automated Test Execution Evidence
Execution Command: `.\mvnw.cmd test`  
Project Root: `C:\Users\User\.gemini\antigravity\scratch\supermarket-automation`  
Exit Code: `0`

```
[INFO] Scanning for projects...
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.supermarket.persistence.DatabaseManagerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.826 s -- in com.supermarket.persistence.DatabaseManagerTest
[INFO] Running com.supermarket.persistence.EdgeCaseAndConstraintTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.243 s -- in com.supermarket.persistence.EdgeCaseAndConstraintTest
[INFO] Running com.supermarket.persistence.RepositoryIntegrationTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.192 s -- in com.supermarket.persistence.RepositoryIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  5.487 s
[INFO] Finished at: 2026-10-03T01:09:47+05:30
[INFO] ------------------------------------------------------------------------
```

### 4. Adversarial Review & Edge Cases Evaluated
1. **Concurrency and Locking**: SQLite default file locking can trigger `SQLITE_BUSY` under high concurrent write contention. `DatabaseManager` mitigates this with a fair `ReentrantLock(true)` write lock and `PRAGMA busy_timeout = 5000;`.
2. **Negative Stock Prevention**: Tested at both the application logic level (conditional `WHERE current_stock >= ?` returning false) and database DDL level (`CHECK (current_stock >= 0)` raising `SQLException`).
3. **Data Integrity on Parent Deletion**: Foreign key cascading delete was validated in `testForeignKeyCascadingDelete`; deleting a sales transaction automatically purges associated line items.
4. **Collation & Case Sensitivity**: Verified `COLLATE NOCASE` on `items.code`; lookups with "PROD-001", "prod-001", and "PrOd-001" all resolve to the same record.

---

## Conclusion
The Milestone 1 deliverables strictly conform to `ORIGINAL_REQUEST.md` and the master architecture specification in `PROJECT.md`. No shortcuts, facades, or fabricated outputs exist. The persistence core is genuine, robust, and ready for Milestone 2 service layer development.
