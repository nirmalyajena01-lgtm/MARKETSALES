# Empirical Concurrency & Persistence Challenge Report: Milestone 1

**Challenger:** `challenger_m1_1` (Empirical Challenger - critic, specialist)  
**Parent Agent:** `orchestrator_1` (ID: `9258cf3b-c2e1-4a39-9931-4a3c6469ec69`)  
**Date:** 2026-10-02  
**Target:** Milestone 1 SQLite Persistence Layer, `DatabaseManager`, Repositories, and Transaction Concurrency  
**Verdict:** **APPROVE**

---

## 1. Executive Summary

Milestone 1 persistence implementation was subjected to adversarial empirical stress testing under heavy multi-threaded concurrency, high-contention atomic stock decrement races, simulated mid-flight transaction crashes, foreign-key failures, and check-constraint violations. 

Testing evaluated both **shared-cache in-memory databases** (`:memory:`) and **file-based WAL mode databases** (`PRAGMA journal_mode=WAL`). Across all test scenarios:
- **Zero `SQLITE_BUSY` crashes** occurred.
- **Zero stock overselling** occurred (even under 100-thread racing contention).
- **Zero partial or orphaned records** remained after rollback.
- Full project test suite passed: **39 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`).

---

## 2. Test Scenarios & Empirical Results

### Scenario 1: Multi-Threaded Concurrency (DatabaseManager & ReentrantLock)

#### Test 1.1: Concurrent Writes on In-Memory Shared-Cache Database
- **Configuration:** 20 concurrent worker threads, each executing 25 write transactions (500 total write transactions).
- **Operations:** Thread-synchronized burst of stock increments and dynamic price updates using `db.executeInTransaction`.
- **Target:** Verify fair `ReentrantLock` serializes write transactions and prevents `SQLITE_BUSY` / `SQLITE_LOCKED`.
- **Measurement:**
  - Threads launched: 20
  - Total write operations: 500
  - Errors / `SQLITE_BUSY`: **0**
  - Expected stock delta: +500
  - Measured stock delta: +500 (Initial 65 -> Final 565)
- **Result:** **PASS**

#### Test 1.2: Concurrent Writes on File-Based Database in WAL Mode
- **Configuration:** Temporary SQLite database file on disk, WAL mode verified via `PRAGMA journal_mode;` -> `wal`. 16 concurrent threads each executing 25 transactions creating new items (400 total items).
- **Target:** Stress test SQLite WAL file locking on Windows filesystem.
- **Measurement:**
  - Threads: 16
  - Total items created: 400
  - Errors / `SQLITE_BUSY`: **0**
  - Database row count query `SELECT COUNT(*) FROM items WHERE code LIKE 'ITEM-T%'`: **400**
- **Result:** **PASS**

#### Test 1.3: Concurrent Readers and Writers Under Contention (File-Based WAL)
- **Configuration:** 8 writer threads continuously executing stock updates (240 total writes, 2ms sleep between writes), while 16 reader threads continuously query `findAll()` and scan the catalog (1ms sleep between reads).
- **Target:** Verify SQLite WAL mode allows concurrent readers without blocking writers or getting `SQLITE_BUSY`.
- **Measurement:**
  - Total writers: 8 threads (240 write transactions completed)
  - Total readers: 16 threads (100+ full-table scans completed concurrently)
  - `SQLITE_BUSY` or lock timeouts: **0**
  - Data integrity: Stock updated accurately from 80 to 320.
- **Result:** **PASS**

---

### Scenario 2: Atomic Stock Decrement Under High Contention

#### Test 2.1: 100-Thread Single-Item Race (Preventing Overselling)
- **Initial Inventory:** Item `RACE-ITEM` created with `current_stock = 50`.
- **Contention:** 100 threads launched simultaneously via `CountDownLatch` barrier, each requesting to decrement 1 unit (`decrementStock(conn, "RACE-ITEM", 1)`).
- **Invariants Checked:**
  - Exactly 50 threads must succeed (`true`).
  - Exactly 50 threads must be rejected (`false`).
  - Final inventory must be exactly 0 (never negative).
  - No `CHECK constraint failed: current_stock >= 0` exceptions.
- **Measurement:**
  - Successful decrements: **50**
  - Rejected decrements: **50**
  - Final stock: **0**
  - Unhandled exceptions: **0**
- **Result:** **PASS**

#### Test 2.2: Multi-Quantity Contention Race
- **Initial Inventory:** Item `MULTI-QTY-ITEM` with `current_stock = 33`.
- **Contention:** 20 threads simultaneously attempting to decrement 5 units each (total demand = 100 units; capacity = 30 units across 6 requests).
- **Invariants Checked:**
  - Exactly 6 threads succeed (6 × 5 = 30).
  - Exactly 14 threads fail.
  - Final stock must equal exactly 3 (33 - 30).
- **Measurement:**
  - Successful decrements: **6**
  - Rejected decrements: **14**
  - Final stock: **3**
- **Result:** **PASS**

#### Test 2.3: Storage-Level Constraint Verification
- **Test:** Direct raw SQL statement attempting `UPDATE items SET current_stock = -1`.
- **Result:** SQLite rejects the update with `SQLException: [SQLITE_CONSTRAINT_CHECK] CHECK constraint failed: current_stock >= 0`.
- **Test:** Negative argument validation in DAO: `decrementStock(conn, code, -5)` and `decrementStock(conn, code, 0)`.
- **Result:** Rejected immediately with `IllegalArgumentException`.

---

### Scenario 3: Transaction Rollback Integrity (Zero Partial Records)

#### Test 3.1: Simulated Mid-Flight Failure During Multi-Item Cart Checkout
- **Workflow:**
  1. Item 1 (`PROD-001`, stock=65) decremented by 5 -> stock becomes 60.
  2. Item 2 (`PROD-002`, stock=45) decremented by 10 -> stock becomes 35.
  3. Master transaction inserted into `sales_transactions` (`TX-FAIL-ROLLBACK-001`).
  4. 2 line items inserted into `sales_transaction_items`.
  5. Catastrophic unhandled exception thrown simulating out-of-memory or system abort.
- **Rollback Verification:**
  - `salesRepo.findBySerialNumber("TX-FAIL-ROLLBACK-001")`: **Empty (Optional.empty)**.
  - Query for line items in `sales_transaction_items`: **0 rows**.
  - `PROD-001` stock: **Restored to 65**.
  - `PROD-002` stock: **Restored to 45**.
- **Result:** **PASS**

#### Test 3.2: Rollback on Database Check Constraint Violation
- **Workflow:** Transaction inserts a valid item, then attempts an invalid `sales_transactions` record with `amount_tendered < total_amount`.
- **Rollback Verification:**
  - Exception thrown: `SQLException (CHECK constraint)`.
  - Item inserted earlier in the same transaction: **Rolled back and absent from DB**.
  - Invalid sales record: **Absent from DB**.
- **Result:** **PASS**

#### Test 3.3: Rollback on Foreign Key Constraint Violation
- **Workflow:** Transaction decrements stock of an existing item, then inserts a line item referencing a non-existent `transaction_id = 999999`.
- **Rollback Verification:**
  - Exception thrown: `SQLException (FOREIGN KEY constraint)`.
  - Item stock: **Rolled back to original value (65)**.
- **Result:** **PASS**

---

### Scenario 4: High-Throughput Mixed Workload Stress Harness

- **Setup:** 16 concurrent threads executing 320 mixed operations over a file-based database:
  - POS checkouts (atomic stock decrement, master header insert, line items batch insert)
  - Supply batch restocks (`incrementStock` + `logRestock`)
  - Dynamic price updates (`updateSellingPrice` + `logPriceChange`)
  - Concurrent catalog searches and analytics summaries
- **Integrity Balances:**
  - Final Stock balance equation: `initialStock - checkouts + (restocks * 2)`
  - Initial stock: 35
  - Checkouts completed: 53
  - Restocks completed: 67
  - Expected stock: `35 - 53 + (67 * 2) = 116` (dynamic calculation matched actual final stock)
  - Recorded sales transaction line items in SQLite exactly equaled successful checkout count.
  - Zero lock timeouts or concurrency faults.
- **Result:** **PASS**

---

## 3. Project-Wide Test Execution Summary

Command: `.\mvnw.cmd test`

| Test Class | Tests Run | Failures | Errors | Skipped | Time |
|---|---|---|---|---|---|
| `DatabaseManagerTest` | 6 | 0 | 0 | 0 | 0.88s |
| `EdgeCaseAndConstraintTest` | 8 | 0 | 0 | 0 | 0.26s |
| `RepositoryIntegrationTest` | 7 | 0 | 0 | 0 | 0.17s |
| `PersistenceConcurrencyAndStressTest` | 9 | 0 | 0 | 0 | 16.54s |
| `R3ProfitSnapshotInvarianceChallengerTest` | 9 | 0 | 0 | 0 | 0.14s |
| **Total** | **39** | **0** | **0** | **0** | **20.59s** |

**Build Result:** `BUILD SUCCESS`

---

## 4. Final Verdict

**Verdict:** **APPROVE**  
The Milestone 1 persistence layer, `DatabaseManager`, transactional boundaries, and DAO repositories satisfy all requirements for thread safety, SQLite concurrency without `SQLITE_BUSY`, atomic stock decrement without overselling, and complete rollback isolation. The codebase is solid for Milestone 2.
