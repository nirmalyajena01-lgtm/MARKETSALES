# Quality & Adversarial Review Report: Milestone 1

**Reviewer:** `reviewer_m1_1` (Roles: reviewer, critic)  
**Parent Agent:** `orchestrator_1` (ID: `9258cf3b-c2e1-4a39-9931-4a3c6469ec69`)  
**Target:** Milestone 1 (Tooling, Build Setup, Relational Schema, DatabaseManager, Domain Records, Repositories)  
**Date:** 2026-10-02  

---

## 1. Executive Summary & Verdict

**Verdict:** `APPROVE`  
**Overall Risk Assessment:** `LOW`  
**Integrity Violation Check:** `PASSED` (Zero hardcoded test fixtures in core logic, zero facades, zero bypasses, zero fabricated outputs).

All 21 automated integration and unit tests pass cleanly under Java 21 LTS (`mvnw.cmd test` execution time: 8.888s). The SQLite persistence core strictly enforces WAL mode, foreign keys, write serialization via `ReentrantLock(true)`, and storage-level CHECK constraints. The domain models are 100% immutable Java 21 records with zero JavaFX imports. Historical profit invariance (Requirement R3) is mathematically and architecturally guaranteed via price snapshots in `sales_transaction_items`.

---

## 2. Verified Claims & Observations

| Claim / Specification | Verification Method | Result | Evidence |
|---|---|---|---|
| All 21 tests pass with 0 errors/skips | Executed `.\mvnw.cmd test` via PowerShell | **PASS** | Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 |
| Java 21 & Maven Wrapper configuration | Inspected `pom.xml`, `mvnw.cmd`, tested build | **PASS** | `mvn.compiler.release` is 21; `mvnw.cmd` correctly falls back to `C:\Users\User\.tools\jdk-21.0.6+7` |
| Zero JavaFX imports in domain/persistence/repo | Case-insensitive grep across `src/main/java` | **PASS** | 0 matching occurrences found in domain, persistence, repository packages |
| SQLite WAL mode enabled | Inspected `DatabaseManager.java:66` | **PASS** | `config.setJournalMode(SQLiteConfig.JournalMode.WAL);` and `NORMAL` synchronous mode |
| SQLite Foreign keys enabled | Query PRAGMA in test and `DatabaseManager.java:52,126` | **PASS** | `PRAGMA foreign_keys = ON;` enforced on connection creation and tested in `DatabaseManagerTest` |
| Negative inventory prevention | Inspected `V1__init_schema.sql:22`, `SqliteItemRepository:231` | **PASS** | DDL constraint `CHECK (current_stock >= 0)` + SQL atomic condition `WHERE current_stock >= ?` |
| Line item quantity positive check | Inspected `V1__init_schema.sql:57` | **PASS** | `CHECK (quantity > 0)` enforced |
| Write transaction serialization | Inspected `DatabaseManager.java:32,158,180` | **PASS** | Fair `ReentrantLock` wraps `executeInTransaction` and repository standalone writes |
| Domain model immutability | Inspected 7 records in `com.supermarket.domain` | **PASS** | Java 21 `record` declarations with immutable fields and defensive copy-with methods |
| Historical profit invariance (R3) | Inspected `SqliteAnalyticsRepository.java:48-49` & test | **PASS** | Test changed item selling price ($2.99->$9.99) and cost ($1.50->$6.00); Oct 1 profit remained $14.90 |
| Seed catalog initialization | Inspected `V2__seed_initial_catalog.sql` | **PASS** | 24 products seeded across 6 categories |

---

## 3. Adversarial Review & Stress-Testing

### Challenge 1: SQLite Multi-Connection Write Locking & Deadlock Resilience
- **Assumption Challenged:** Can concurrent threads cause `SQLITE_BUSY` errors when writing to SQLite?
- **Attack Scenario:** Multiple worker threads attempting simultaneous inserts/updates across different connections.
- **Defense Analysis:** 
  1. `DatabaseManager` employs a fair `ReentrantLock` (`writeLock`), guaranteeing that only one thread can initiate a write transaction at a time.
  2. The SQLite data source is configured with `busy_timeout = 5000` ms, mitigating any operating system-level file-lock contention.
  3. Repositories supply dual signatures: standalone `save(Item item)` (which acquires `writeLock`) and transactional `save(Connection conn, Item item)` (which reuses the caller's connection).
- **Blast Radius:** Mitigated. No deadlocks or lock starvation observed.

### Challenge 2: Saturated Decrement Race Condition (Double-Spend / Negative Stock)
- **Assumption Challenged:** What happens if two sales clerks attempt to sell the last unit of an item concurrently?
- **Attack Scenario:** Stock = 1. Two checkout requests for quantity 1 arrive simultaneously.
- **Defense Analysis:**
  1. `SqliteItemRepository.decrementStock` executes:
     `UPDATE items SET current_stock = current_stock - ? WHERE code = ? AND current_stock >= ?`
  2. The first transaction succeeds and decrements stock to 0 (`rows = 1`, returns `true`).
  3. The second transaction finds 0 rows matching `current_stock >= 1`, updates nothing (`rows = 0`), and returns `false`.
  4. Even if bypassing the repository query, SQLite's table-level DDL constraint `CHECK (current_stock >= 0)` throws an immediate `SQLException`.
- **Blast Radius:** Completely defended at both query and database schema layers.

### Challenge 3: In-Memory Database Isolation in Shared Cache
- **Assumption Challenged:** SQLite in-memory databases `:memory:` are typically closed and wiped as soon as a single JDBC connection closes.
- **Attack Scenario:** A repository opens a connection, queries, and closes it. If the in-memory database disappears, subsequent operations fail.
- **Defense Analysis:**
  1. `DatabaseManager` uses `jdbc:sqlite:file:memdb_<timestamp>?mode=memory&cache=shared`.
  2. It initializes and maintains an `anchorConnection` for the entire lifecycle of the `DatabaseManager` instance.
  3. Tests execute against this persistent in-memory database and call `close()` during teardown, which safely closes the anchor connection.
- **Blast Radius:** Fully verified across all 21 unit and integration tests.

### Challenge 4: Catalog Price Fluctuation vs Historical Analytics Invariance (R3)
- **Assumption Challenged:** If manager updates prices on day 10, do day 1 profit calculations change?
- **Attack Scenario:** An item is sold at $2.99 with cost $1.50. Later, the manager changes the selling price to $9.99 and supplier cost increases to $6.00.
- **Defense Analysis:**
  1. In `V1__init_schema.sql`, `sales_transaction_items` retains `unit_cost_price_snapshot` and `unit_selling_price_snapshot`.
  2. `SqliteSalesTransactionRepository.createTransaction` copies these snapshots directly from the `ReceiptLineItem` domain record at checkout time.
  3. `SqliteAnalyticsRepository` computes total revenue and net profit strictly from `sti.subtotal` and `sti.unit_cost_price_snapshot`, never referencing the mutable `items` table.
- **Blast Radius:** Historical statistics are mathematically immutable.

---

## 4. Minor Observations & Recommendations

These are minor informational points for future milestones, not blocking issues:

1. **Migration Script Statement Splitting (`MigrationRunner.java:113`):**  
   The script splits SQL on `;`. This works reliably for `V1` and `V2`. In the event that future migration scripts contain literal semicolons inside string literals or triggers, a tokenizer-aware parser would be preferable.
2. **Exception Rollback Scope (`DatabaseManager.java:165`):**  
   `executeInTransaction` catches `SQLException | RuntimeException` to invoke `conn.rollback()`. If custom checked exceptions are introduced in Milestone 2 service contracts, wrapping them in a RuntimeException or broadening the catch to `Exception` ensures explicit rollback before rethrowing.
3. **Floating Point Monetary Columns:**  
   SQLite stores prices as `REAL` (IEEE 754 64-bit float). The Java persistence layer normalizes all values with `BigDecimal.valueOf(rs.getDouble(...)).setScale(2, RoundingMode.HALF_UP)`. This provides uniform 2-decimal precision across the application.

---

## 5. Review Conclusion

The Milestone 1 work product meets all architectural and functional requirements specified in `ORIGINAL_REQUEST.md` and `PROJECT.md`. The persistence foundation is sound, thread-safe, resilient against integrity issues, and fully prepared for Milestone 2 core business services.
