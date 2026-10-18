# Empirical Challenge Report: BillingService, Concurrency & Atomic Rollback (Milestone 2)

**Agent:** `challenger_m2_1` (Challenger 1)  
**Parent Agent:** `orchestrator_1` (ID: `9258cf3b-c2e1-4a39-9931-4a3c6469ec69`)  
**Target:** Milestone 2 Core Business Services (`BillingService`, `DatabaseManager`, Concurrency, Atomic Rollback)  
**Date:** 2026-10-02  
**Verdict:** **APPROVE**  

---

## 1. Executive Summary

As Empirical Challenger 1 for Milestone 2, we conducted exhaustive adversarial stress testing of `BillingService`, multi-threaded checkout concurrency, inventory race conditions, atomic transactional rollback, and payment shortfall error handling.

A dedicated adversarial stress test suite, `BillingConcurrencyAndRollbackStressTest.java` (containing 9 high-contention test scenarios), was engineered and executed directly against both in-memory SQLite and file-based SQLite with WAL mode.

### Key Empirical Findings:
1. **Overselling Prevention (100% Guarantee):** In high-contention checkout tests with 50 concurrent threads competing for 15 available units of stock, exactly 15 transactions succeeded and exactly 35 failed with `InsufficientStockException`. The inventory decremented to exactly 0 and never dropped below zero (0% overselling rate). All 15 generated transaction serial numbers were unique with zero collisions.
2. **Multi-Item Atomic Rollback (100% Invariance):** In 6-item carts where the final item failed inventory validation (and in simulated mid-transaction decrement failures where the 1st item was decremented before the 2nd item failed), full SQLite transaction rollback occurred. Prior decremented items were 100% restored to their original inventory levels. Zero sales transactions and zero line items were persisted. Cart items in memory remained intact for cashier adjustment.
3. **Payment Shortfall Invariance:** Sub-penny deficit ($0.01), zero tendered ($0.00), negative tendered (-$10.00), and large shortfall scenarios strictly threw `PaymentShortfallException` before any database connection or transaction was opened. Zero database rows were mutated, and carts were preserved.
4. **Mixed High-Contention Workload on SQLite WAL:** A 40-thread combined stress test simultaneously executing checkouts, employee restocks (`InventoryService.restockItem`), and manager price updates (`PriceService.updateSellingPrice`) ran without any deadlocks, data corruption, or unhandled `SQLITE_BUSY` errors. The physical stock conservation law (`initialStock + restocked - sold == finalStock`) was maintained down to the exact unit.

---

## 2. Test Matrix and Quantitative Results

| # | Test Method | Scenario Description | Concurrency / Threads | Expected Behavior | Actual Behavior | Result |
|---|---|---|---|---|---|---|
| 1 | `testConcurrentCheckoutSingleItemPreventOverselling` | High contention checkout on 1 item with stock=15 | 50 threads | Exactly 15 succeed, 35 fail with `InsufficientStockException`, stock reaches 0 | 15 succeeded, 35 rejected, stock = 0, 15 unique serials | **PASS** |
| 2 | `testConcurrentMultiItemCheckoutUnderContention` | Heterogeneous multi-item carts (3 types) competing for 3 limited items | 36 threads | Stock conservation holds for all 3 items, no negative stock | Final stocks >= 0; initial = final + sold for all items | **PASS** |
| 3 | `testAtomicRollbackWhenLastItemFailsInMultiItemCart` | 6-item cart where last item has stock depleted before checkout | 1 thread | Throws `InsufficientStockException`, zero stock decremented for items 1-5, 0 transactions | 100% rollback, items 1-5 stock untouched, cart preserved | **PASS** |
| 4 | `testAtomicRollbackWhenMidTransactionDecrementFails` | Fault injection: Item 1 decrements in DB, Item 2 decrement fails | 1 thread | Throws `InsufficientStockException`, Item 1 stock rolled back | Item 1 restored to initial stock 10, zero transactions committed | **PASS** |
| 5 | `testPaymentShortfallPreservesDatabaseState` | $0.01 shortfall, $0.00 tendered, negative tendered, large shortfall | 4 cases | Throws `PaymentShortfallException`, zero DB mutation, cart preserved | Correct exception properties, 0 transactions, stock unchanged | **PASS** |
| 6 | `testConcurrentCheckoutsAndRestocksIntegrity` | Interleaved checkouts (buying 2 each) and restocks (adding 4 each) | 30 threads (20 checkout + 10 restock) | Invariant: `initial + restocked - sold == final`, no deadlocks | Conservation satisfied, restock audit log has 10 entries | **PASS** |
| 7 | `testFileBasedSqliteConcurrentCheckoutStress` | Real disk file SQLite with WAL mode, stock=20 | 40 checkout threads | Exactly 20 succeed, 20 rejected, stock = 0 | 20 succeeded, 20 rejected, final stock = 0 | **PASS** |
| 8 | `testHighContentionFullWorkloadOnFileBasedWALDatabase` | Mixed stress: 25 checkouts + 10 restocks + 5 price updates on disk WAL DB | 40 threads (16 worker pool) | Zero corruption, exact conservation, all audit logs intact | Conservation holds, 5 price audits, 10 restock audits | **PASS** |
| 9 | `testZeroStockAndNonExistentItemRollback` | Item with 0 stock added/checked out; non-existent ghost item | 2 cases | Throws `InsufficientStockException` / `ItemNotFoundException`, rollback | Stock and transactions unchanged | **PASS** |

---

## 3. Deep-Dive Adversarial Stress Analysis

### 3.1 Race Condition & Overselling Defense Architecture
In `BillingService.checkout`:
```java
receipt = db.executeInTransactionWithResult(conn -> {
    // Step 1: Pre-validate stock for ALL items before any decrements occur
    for (CartItem ci : cart.getItems()) {
        Optional<Item> itemOpt = itemRepository.findByCode(conn, ci.itemCode());
        if (itemOpt.isEmpty()) {
            throw new ItemNotFoundException(ci.itemCode());
        }
        Item item = itemOpt.get();
        if (item.currentStock() < ci.quantity()) {
            throw new InsufficientStockException(item.code(), item.name(), ci.quantity(), item.currentStock());
        }
    }

    // Step 2: Atomically decrement stock
    for (CartItem ci : cart.getItems()) {
        boolean decremented = itemRepository.decrementStock(conn, ci.itemCode(), ci.quantity());
        if (!decremented) {
            Item item = itemRepository.findByCode(conn, ci.itemCode())
                    .orElseThrow(() -> new ItemNotFoundException(ci.itemCode()));
            throw new InsufficientStockException(item.code(), item.name(), ci.quantity(), item.currentStock());
        }
    }
...
```
In `DatabaseManager`:
- Transactions are guarded by a fair `ReentrantLock writeLock = new ReentrantLock(true);`.
- In `SqliteItemRepository`:
  ```sql
  UPDATE items SET current_stock = current_stock - ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') 
  WHERE code = ? AND current_stock >= ?
  ```
- Combined with SQLite table constraint `current_stock INTEGER NOT NULL CHECK (current_stock >= 0)`, the system provides a three-tiered defense against overselling:
  1. Application-level write synchronization (`writeLock`).
  2. Conditional SQL atomic decrement (`WHERE current_stock >= ?`).
  3. Database-level schema constraint (`CHECK (current_stock >= 0)`).

### 3.2 Mid-Transaction Rollback Verification
We tested the most challenging edge case: what happens if an item is decremented in SQLite, but a subsequent item in the cart cannot be decremented?
By injecting a proxy repository where `decrementStock` on the 2nd item returned `false`, `BillingService` threw `InsufficientStockException`.
Inside `DatabaseManager.executeInTransactionWithResult`:
```java
catch (SQLException | RuntimeException e) {
    conn.rollback();
    throw e;
}
```
Our test verified that Item 1 (which had already executed `UPDATE items SET current_stock = current_stock - 3` on the connection) was restored to its exact original stock upon rollback. No intermediate dirty writes leaked into the database.

### 3.3 Payment Shortfall
`BillingService.checkout` performs the payment check prior to acquiring the database transaction:
```java
CartTotals totals = computeTotals(cart, cashTendered);
if (!totals.isPaidInFull()) {
    throw new PaymentShortfallException(totals.totalPayable(), cashTendered);
}
```
This guarantees that short payments fail fast with zero database overhead, avoiding unnecessary locks or transaction rollbacks, while retaining cart state for the cashier.

---

## 4. Test Execution Output

Command executed:
```powershell
.\mvnw.cmd test "-Dtest=BillingConcurrencyAndRollbackStressTest,BillingServiceTest,AtomicRollbackServiceTest,InventoryServiceTest"
```

Verbatim execution log:
```
[INFO] Running com.supermarket.service.BillingConcurrencyAndRollbackStressTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.954 s -- in com.supermarket.service.BillingConcurrencyAndRollbackStressTest
[INFO] Running com.supermarket.service.BillingServiceTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.165 s -- in com.supermarket.service.BillingServiceTest
[INFO] Running com.supermarket.service.AtomicRollbackServiceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.052 s -- in com.supermarket.service.AtomicRollbackServiceTest
[INFO] Running com.supermarket.service.InventoryServiceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.123 s -- in com.supermarket.service.InventoryServiceTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  8.166 s
[INFO] Finished at: 2026-10-03T01:36:51+05:30
[INFO] ------------------------------------------------------------------------
```

---

## 5. Final Recommendation

All Milestone 2 tasks assigned to Challenger 1 (checkout concurrency, overselling prevention, zero stock termination, atomic multi-item rollback, and payment shortfall preservation) have been empirically verified and pass all stress criteria.

**Verdict:** **APPROVE**
