# Empirical Challenge Report: Requirement R3 Profit Snapshot Invariance, Numeric Boundaries & Date Range Filtering

**Challenger:** Challenger 2 (`challenger_m1_2`)  
**Milestone:** Milestone 1 (Tooling, Build Setup & Persistence Layer)  
**Target:** `AnalyticsRepository`, `SqliteAnalyticsRepository`, `SalesTransactionRepository`, `ItemRepository`, SQLite Schema (`V1__init_schema.sql`)  
**Verdict:** **APPROVE**  
**Date:** 2026-10-02  

---

## 1. Executive Summary

As Challenger 2, an empirical adversarial stress test suite was designed, implemented, and executed to independently challenge Requirement R3:
> *"The statistics must show the quantity sold, the price realized (revenue), and the net profit (realized revenue minus cost price). Updating an item's selling price in the future must never corrupt or retroactively alter historical profit statistics (guaranteed via transaction-time price snapshotting)."* (ORIGINAL_REQUEST.md §R3)

A dedicated test suite `com.supermarket.persistence.R3ProfitSnapshotInvarianceChallengerTest` containing 12 comprehensive stress test scenarios was written and executed via Microsoft OpenJDK 21 and the Apache Maven wrapper (`.\mvnw.cmd test`).

### Overall Results
- **Total Challenger Tests:** 12
- **Passed:** 12 (100%)
- **Failed:** 0
- **Regression Impact on Existing Suites:** Zero (Entire project test suite now passes with 42/42 tests clean).
- **Execution Speed:** Challenger suite executed in ~1.2s; full 42-test project suite executed in 20.0s.

---

## 2. Test Scenarios & Empirical Evidence

### Test Scenario 1: Multi-Transaction Historical Invariance Across Catalog Mutations
- **Method:** `testMultiTransactionSnapshotInvarianceAcrossCatalogMutations`
- **Setup:**
  - 3 distinct sales transactions across 2 calendar days (2026-10-01 and 2026-10-02) involving 5 distinct catalog items (`PROD-001`, `DAIRY-001`, `BAKE-001`, `BEV-001`, `BEV-002`).
  - Baseline Day 1: 1 tx, 13 items sold, Revenue = $24.17, Cost = $13.20, Profit = $10.97.
  - Baseline Day 2: 2 tx, 14 items sold, Revenue = $31.47, Cost = $17.50, Profit = $13.97.
- **Adversarial Mutation:**
  - `PROD-001` selling price modified from $1.49 to $12.99 (+771%), cost price modified from $0.80 to $7.50 (+837%).
  - `DAIRY-001` selling price modified from $1.89 to $0.99 (-48%), cost price modified from $1.20 to $0.50 (-58%).
  - `BAKE-001` selling price modified from $2.29 to $50.00 (+2083%), cost price from $1.10 to $30.00 (+2627%).
  - `BEV-001` selling price modified from $3.99 to $0.05 (-98.7%).
  - `BEV-002` selling price modified from $8.99 to $99.99 (+1012%).
- **Empirical Observation:**
  - Day 1 re-query: Revenue = **$24.17**, Cost = **$13.20**, Profit = **$10.97** (Identical to 0.0001 precision).
  - Day 2 re-query: Revenue = **$31.47**, Cost = **$17.50**, Profit = **$13.97** (Identical to 0.0001 precision).
  - Combined Day 1–2 re-query: Revenue = **$55.64**, Cost = **$30.70**, Profit = **$24.94** (Identical).
  - Per-item breakdowns in `getItemStatistics` matched baseline item-for-item without deviation.

### Test Scenario 2: Inverted Margin (Catalog Negative Margin Mutation)
- **Method:** `testExtremeCatalogPriceMutationsZeroAndNegativeMarginInCatalog`
- **Setup:** Transaction recorded on 2026-10-01 for 10 units at selling price $10.00 and cost $4.00 (Revenue $100.00, Cost $40.00, Profit $60.00).
- **Adversarial Mutation:** The catalog item was subsequently modified to Cost $50.00 and Selling $2.00 (a severe catalog loss of -$48.00/unit).
- **Empirical Observation:** Historical analytics query yielded Revenue = **$100.00**, Cost = **$40.00**, Net Profit = **$60.00**. Historical profit remained completely insulated from subsequent negative catalog margins.

### Test Scenario 3: Large Quantities & High Revenue (Numeric Scaling)
- **Method:** `testNumericBoundariesLargeQuantitiesAndHighRevenues`
- **Setup:** Single wholesale order line of 1,000,000 units at unit selling price $99.99 and unit cost $45.50.
- **Expected:** Revenue = $99,990,000.00, Cost = $45,500,000.00, Net Profit = $54,490,000.00.
- **Empirical Observation:**
  - `AnalyticsSummary`: items sold = 1,000,000, Revenue = **$99,990,000.00**, Cost = **$45,500,000.00**, Profit = **$54,490,000.00**.
  - `SalesStatistic`: exact match down to 2 decimal places. Zero IEEE-754 precision distortion or integer overflow.

### Test Scenario 4: High-Volume Fractional Cents vs Pure BigDecimal Oracle
- **Method:** `testNumericBoundariesFractionalDollarAmountsAndPennies`
- **Setup:** 40 transactions with irregular fractional dollar prices ($0.01, $0.05, $0.13, $0.47, $1.19, $2.33, $5.67, $11.89) and irregular unit selling prices.
- **Oracle Comparison:** An independent pure Java `BigDecimal` arithmetic oracle computed exact cumulative sums.
- **Empirical Observation:**
  - SQLite `SUM(sti.subtotal)` and `ROUND(..., 2)` matched `oracleTotalRevenue` exactly to the penny ($0.00 difference).
  - SQLite `SUM(sti.quantity * sti.unit_cost_price_snapshot)` matched `oracleTotalCost` exactly ($0.00 difference).
  - SQLite `net_profit` matched `oracleTotalProfit` exactly ($0.00 difference).

### Test Scenario 5: Negative Profit / Promotional Loss-Leaders
- **Method:** `testNegativeProfitLossLeaderItemHandling`
- **Setup:** Promotional sale of 10 units at $1.00 selling price against $2.50 cost (Loss of $1.50 per unit).
- **Empirical Observation:**
  - Revenue = **$10.00**, Cost = **$25.00**, Net Profit = **-$15.00**.
  - `getProfitMarginPercentage()` returned **-150.0%**.
  - The persistence layer correctly handles negative net profits without constraint violation or math exceptions.

### Test Scenario 6: Exact Same Timestamp Pinpoint Range (`start == end`)
- **Method:** `testExactSameTimestampDateRangeBoundary`
- **Setup:** Transaction at `2026-10-05T14:30:00`.
- **Empirical Observation:**
  - Query with `start = 2026-10-05T14:30:00` and `end = 2026-10-05T14:30:00` returned exactly 1 transaction with exact totals ($15.00 revenue, $10.00 profit).
  - Query 1 second earlier (`14:29:59`) returned 0 transactions ($0.00 revenue).
  - Query 1 second later (`14:30:01`) returned 0 transactions ($0.00 revenue).

### Test Scenario 7: Midnight Boundary Filtering
- **Method:** `testMidnightBoundariesDateRangeFiltering`
- **Setup:**
  - TX 1: `2026-10-05T23:59:59` (Last second of Day 1)
  - TX 2: `2026-10-06T00:00:00` (First second of Day 2)
  - TX 3: `2026-10-06T00:00:01` (Second 1 of Day 2)
- **Empirical Observation:**
  - Day 1 range `[2026-10-05T00:00:00, 2026-10-05T23:59:59]`: Returned exactly TX 1 ($10.00). Excluded TX 2 and TX 3.
  - Day 2 range `[2026-10-06T00:00:00, 2026-10-06T23:59:59]`: Returned exactly TX 2 and TX 3 ($50.00). Excluded TX 1.
  - Bridge range `[2026-10-05T23:59:59, 2026-10-06T00:00:00]`: Returned exactly TX 1 and TX 2 ($30.00).

### Test Scenario 8: Gap Periods, Empty Ranges, and Inverted Ranges
- **Method:** `testDateRangesWithNoTransactionsAndGapPeriods`
- **Setup:** Transactions recorded on Day 1 and Day 3; Day 2 had no activity.
- **Empirical Observation:**
  - Gap Day 2 query: `getItemStatistics` returned empty list `[]`; `getSummary` returned `0` transactions, `0` items, `$0.00` revenue, `$0.00` cost, `$0.00` profit, and `0.0%` margin.
  - Inverted date range (`start > end`): returned clean empty list and zero summary without SQL error.

### Test Scenario 9: Parity Between Sales Repository and Analytics Repository
- **Method:** `testParityBetweenSalesRepositoryAndAnalyticsRepositoryDateRange`
- **Setup:** 5 transactions across a single day.
- **Empirical Observation:** Sum of `SalesTransaction.totalAmount()` across `salesRepo.findByDateRange(start, end)` matched `analyticsRepo.getSummary(start, end).totalRevenue()` to the penny.

### Test Scenario 10: Multi-Line Item Consolidation within Single Transaction
- **Method:** `testMultipleLineItemsSameProductInSingleTransaction`
- **Setup:** Customer purchased 3 units of milk and later added 7 more units of milk in the same cart transaction (2 separate line items for `DAIRY-001`).
- **Empirical Observation:** `getItemStatistics` grouped both lines under `DAIRY-001`, yielding totalQuantitySold = 10, totalRevenue = $20.00, totalCost = $12.00, netProfit = $8.00.

### Test Scenario 11: Zero-Price Promotional Items (Giveaways)
- **Method:** `testZeroPriceFreeSampleItem`
- **Setup:** Transaction with a free promotional sample (unit selling price $0.00, subtotal $0.00, cost $0.75, quantity 4).
- **Empirical Observation:** Revenue = $0.00, Cost = $3.00, Net Profit = -$3.00. `getProfitMarginPercentage()` safely returned `0.0%` with zero `ArithmeticException` (division by zero prevented).

### Test Scenario 12: High-Volume Monthly Aggregation Performance
- **Method:** `testHighVolumeTransactionStressAndPerformance`
- **Setup:** 100 sales transactions distributed across 30 days.
- **Empirical Observation:** Analytics summary and itemized aggregation across the 30-day window executed in **14 milliseconds**, well within the sub-second threshold.

---

## 3. Findings & Assessment

1. **Snapshot Invariance Architecture:**
   The SQLite relational architecture is sound. `sales_transaction_items` stores `unit_cost_price_snapshot`, `unit_selling_price_snapshot`, and `subtotal` directly. `SqliteAnalyticsRepository` queries only `sales_transaction_items` joined with `sales_transactions`, and never performs joins against the mutable `items` table. Catalog price updates through `ItemRepository` have zero retroactive impact on historical sales data.

2. **Numeric Precision:**
   SQLite `REAL` columns mapped through Java `BigDecimal.valueOf(rs.getDouble(...)).setScale(2, RoundingMode.HALF_UP)` behave deterministically across cents, large volumes up to $100M, and loss leaders with negative profits.

3. **Date Filtering Boundary Precision:**
   ISO-8601 string formatting (`DateTimeFormatter.ISO_LOCAL_DATE_TIME`) provides clean lexicographical range queries in SQLite for seconds-level timestamps, properly handling midnight crossovers and pinpoint matches.

---

## 4. Verdict

**APPROVE** — Requirement R3 profit snapshot invariance, numeric boundaries, and date filtering have been empirically stress-tested and verified with 100% pass rate.
