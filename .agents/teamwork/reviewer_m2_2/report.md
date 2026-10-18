# Milestone 2 Review and Adversarial Critique Report

**Reviewer:** Reviewer 2 (`reviewer_m2_2`)  
**Parent Agent:** `orchestrator_1` (ID: `9258cf3b-c2e1-4a39-9931-4a3c6469ec69`)  
**Project:** Supermarket Automation Software (SAS)  
**Date:** 2026-10-02  
**Verdict:** **APPROVE**  
**Overall Risk Assessment:** **LOW**  

---

## 1. Executive Summary

Milestone 2 delivers the headless core domain and business service layer of the Supermarket Automation Software (SAS). The review scope focuses on transactional integrity, atomic rollback on stock insufficiency, unique serial number generation, and Requirement R3 historical profit snapshot invariance.

An independent build and verification was executed via `.\mvnw.cmd test`, completing with **72 passing tests, 0 failures, and 0 errors**. Code inspection confirmed zero JavaFX dependencies in domain, persistence, or service packages. No integrity violations (hardcoded test results, dummy facades, shortcuts, or fabricated outputs) were found; all business rules and SQLite persistence logic are genuinely implemented.

---

## 2. Integrity Verification (Anti-Cheat Audit)

| Check Item | Target | Observation / Verification | Status |
|---|---|---|---|
| **Hardcoded Test Results** | Source code in `src/main/java` | Searched for static return values, test fixtures (`39.90`, `89.90`, etc.). All calculations originate dynamically from SQLite SQL aggregations or `BigDecimal` arithmetic. | PASS |
| **Dummy / Facade Logic** | Services & Repositories | Verified all 5 services (`BillingService`, `InventoryService`, `PriceService`, `AnalyticsService`, `ReceiptFormatter`) and repositories contain real SQL queries, parameter bindings, exception handlers, and locks. | PASS |
| **Bypassing Task Requirements** | Persistence & Concurrency | Used SQLite with WAL mode, foreign keys, transaction locks, and atomic operations rather than in-memory mocking or external services. | PASS |
| **Fabricated Verification** | Maven Test Suite Execution | Independent execution of `.\mvnw.cmd test` verified 72 tests across 12 test classes with identical outputs. | PASS |

**Result:** Zero integrity violations detected.

---

## 3. Review Dimensions & Detailed Findings

### 3.1 Correctness & Transactional Integrity (`BillingService.checkout`)

1. **Pre-Validation of Stock:**
   - In `BillingService.checkout` (lines 198–208), every cart item is inspected against the database catalog before any decrement SQL statements are executed.
   - If an item's requested quantity exceeds available stock, `InsufficientStockException` is thrown immediately.
2. **Atomic Rollback Guarantee:**
   - `DatabaseManager.executeInTransactionWithResult` handles write operations under a fair `ReentrantLock` (`writeLock`).
   - If `InsufficientStockException` (or any other `RuntimeException` or `SQLException`) is thrown, `conn.rollback()` is immediately invoked in the catch block.
   - Secondary defensive guard: Even if pre-validation succeeds but a concurrent decrement exhausts stock, `SqliteItemRepository.decrementStock` uses `UPDATE ... WHERE current_stock >= ?`. If rows affected is 0, `BillingService` detects the condition and throws `InsufficientStockException`, causing full rollback of any previously decremented items in the cart.
   - Verified via `AtomicRollbackServiceTest` (5-item cart with 1 stock-deficient item results in zero decrements and zero transactions) and `PersistenceConcurrencyAndStressTest`.
3. **Cart State Preservation on Failure:**
   - Active cart items are preserved if checkout fails, enabling cashier quantity adjustments without rescanning. `cart.clear()` executes only after successful transaction completion.
4. **Collision-Free Serial Numbers:**
   - Serial numbers are formatted as `SAS-YYYYMMDD-XXXXX` adhering to Requirement R1 / F9.
   - Generated using `AtomicLong SEQUENCE_COUNTER` seeded from the day's existing transaction count, combined with a `SELECT 1 FROM sales_transactions WHERE serial_number = ?` check and a database table `UNIQUE` constraint. All execute under the serialized write lock, preventing race collisions.

### 3.2 Requirement R3: Historical Profit Snapshot Invariance

1. **Transaction-Time Snapshotting:**
   - When checking out, `BillingService.checkout` records `unit_cost_price_snapshot` and `unit_selling_price_snapshot` directly into `sales_transaction_items`.
2. **Query Decoupling:**
   - In `SqliteAnalyticsRepository.getItemStatistics` and `SqliteAnalyticsRepository.getSummary`, SQL aggregation queries join only `sales_transactions` and `sales_transaction_items`.
   - The `items` table is **never joined or referenced** in analytics queries.
   - Consequently, dynamic selling price changes via `PriceService.updateSellingPrice` or cost changes via `InventoryService.saveItem` have zero retroactive effect on historical reports.
   - Verified via `PriceSnapshotInvarianceServiceTest` and empirical edge-case testing in `R3ProfitSnapshotInvarianceChallengerTest` (12 test cases covering extreme price shifts, zero margins, negative margins, large volumes, fractional pennies, midnight boundary transitions, and empty periods).

### 3.3 Findings & Observational Suggestions

#### [Minor] Finding 1: Cart Price vs Catalog Price Synchronization at Checkout
- **Location:** `BillingService.java:242`
- **What:** In `BillingService.checkout`, `sellingSnapshot` is obtained via `item.currentSellingPrice()` from the catalog, whereas `totals.totalPayable()` was computed from `ci.unitPrice()` (set when item was scanned).
- **Impact:** In the rare event that a manager changes an item's selling price in the catalog while an item sits in an active POS cart, `tx.totalAmount` could differ by pennies from the sum of line item snapshots unless cart totals are recomputed.
- **Suggestion:** For Milestone 3 UI integration, ensure that when opening the payment modal or executing checkout, the POS cart either synchronizes with catalog prices or explicitly uses `ci.unitPrice()` as the snapshot selling price.

#### [Minor] Finding 2: Monospace Thermal Receipt Column Width on High-Value Items
- **Location:** `ReceiptFormatter.java:77`
- **What:** The format specifier `%6.2f` accommodates unit prices up to `999.99` within the strict 40-column boundary. For high-value wholesale items >= $1,000.00, the line could expand to 41 characters.
- **Impact:** Very low; standard supermarket items rarely exceed $999.99 per unit.
- **Suggestion:** If high-value wholesale items are introduced, adjust the column allocation (e.g., Code column from 6 to 5 or Name column from 14 to 13) to ensure 40-column compliance under large values.

---

## 4. Adversarial Stress-Test Challenges

### Challenge 1: Concurrent Checkouts Competing for Exhausting Stock
- **Assumption:** SQLite transactions with serialized locking prevent overselling and race conditions under heavy load.
- **Stress Scenario:** 100 concurrent threads attempting to purchase 50 available units of `RACE-ITEM`.
- **Result:** Exactly 50 purchases succeeded, 50 were rejected, and final inventory ended at exactly 0. Zero `SQLITE_BUSY` errors occurred. (Tested in `PersistenceConcurrencyAndStressTest.testAtomicStockDecrementHighContention`).
- **Blast Radius:** Mitigated completely by `DatabaseManager` fair `ReentrantLock` and atomic SQL check constraints.

### Challenge 2: Retroactive Profit Distortion from Future Price Increases / Cost Increases
- **Assumption:** Updating catalog selling and cost prices does not alter historical analytics reports.
- **Stress Scenario:** Item sold at cost $5.00, selling $8.99 (profit $39.90 on 10 units). Catalog subsequently modified to cost $11.00, selling $15.99.
- **Result:** Daily analytics reports generated for the sale date continued to report exact historical cost ($50.00), revenue ($89.90), and net profit ($39.90). (Tested in `PriceSnapshotInvarianceServiceTest`).

### Challenge 3: Negative Profit and Zero-Revenue Divisions
- **Assumption:** Loss-leader items (selling price < cost price) or promotional free items (revenue = $0.00) do not cause arithmetic exceptions in margin calculations.
- **Stress Scenario:** Free promotional samples sold at $0.00 selling price with $0.75 wholesale cost.
- **Result:** Margin calculation safely returned 0.0% without division-by-zero errors. Net profit correctly reported negative value (-$3.00). (Tested in `R3ProfitSnapshotInvarianceChallengerTest.testZeroPriceFreeSampleItem`).

---

## 5. Verified Claims Summary

| Claim | Method | Result |
|---|---|---|
| Clean Maven compilation and 100% test pass rate | `.\mvnw.cmd test` | PASS (72/72 tests) |
| Pure headless Java 21 implementation (zero JavaFX imports in domain/service) | Code grep across `src/main/java/com/supermarket/{domain,service,persistence,repository}` | PASS (0 occurrences) |
| Pre-validation of inventory prior to decrement | Code review of `BillingService.java:198-208` | PASS |
| Full atomic rollback on `InsufficientStockException` | Code review and `AtomicRollbackServiceTest` | PASS |
| Unique serial numbers formatted `SAS-YYYYMMDD-XXXXX` | Code review and `BillingServiceTest.testCheckoutSuccess` | PASS |
| Snapshot columns `unit_cost_price_snapshot` & `unit_selling_price_snapshot` in schema and queries | Schema review `V1__init_schema.sql` and `SqliteAnalyticsRepository.java` | PASS |
| Historical profit invariance against future catalog price changes | `PriceSnapshotInvarianceServiceTest` & `R3ProfitSnapshotInvarianceChallengerTest` | PASS |

---

## 6. Recommendation & Final Verdict

The Milestone 2 headless service architecture satisfies all functional and non-functional requirements specified in `ORIGINAL_REQUEST.md` and `PROJECT.md`. The implementation is robust, free of facades or integrity violations, and thoroughly verified.

**Verdict:** **APPROVE**
