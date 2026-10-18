# SQLite Persistence Architecture & Data Model Specification
**Supermarket Automation Software (SAS)**
**Document Version:** 1.0.0
**Author:** Persistence Architecture Explorer (`explorer_schema_survey`)
**Date:** 2026-10-02

---

## 1. Executive Summary & Architectural Overview

The Supermarket Automation Software (SAS) requires a robust, zero-administration, self-contained persistence engine capable of running seamlessly on Windows desktop environments. The chosen persistence engine is **SQLite 3** operated via the **Xerial SQLite JDBC** driver (`org.xerial:sqlite-jdbc`).

This document specifies the complete persistence architecture, database schema, transactional semantics, connection lifecycle management, automated schema migrations, and Data Access Object (DAO) patterns necessary to satisfy all operational and statistical requirements of SAS.

### Core Architectural Objectives
1. **ACID Transaction Guarantees & Atomic Stock Decrement:** POS sales checkout must atomically persist the bill master record, save all line item snapshots, and decrement warehouse inventory within a single database transaction. If any item has insufficient stock, or if a database constraint fails, the entire transaction must roll back cleanly with zero inventory leakage.
2. **Strict Price & Profit Snapshot Invariance:** In accordance with Requirement R3, historical profit analytics must never be corrupted or altered retroactively when an item's selling price or cost price changes. Each sale line records immutable snapshots of `unit_cost_price_snapshot` and `unit_selling_price_snapshot`.
3. **High-Performance Concurrency via WAL Mode:** Utilizing SQLite's **Write-Ahead Logging (WAL)** mode enables concurrent non-blocking reads (e.g. inventory lookups, analytics reports) alongside write operations (e.g. checkout billing, restock logging) without lock contention.
4. **Defensive Database Constraints:** Database-level `CHECK` constraints (e.g., `CHECK (current_stock >= 0)`) provide a second layer of defense, making negative inventory physically impossible at the storage engine level.
5. **Zero-Configuration Startup & Migration:** The application automatically provisions the database file, applies baseline PRAGMAs, creates tables via an embedded migration runner, and populates initial catalog seed data upon first launch.

---

## 2. Complete SQLite Relational Data Model & DDL Schema

### 2.1 Entity Relationship Diagram (Textual Representation)

```
+------------------------+             1:N              +------------------------------+
|   sales_transactions   |-----------------------------<|   sales_transaction_items    |
+------------------------+                              +------------------------------+
| PK id                  |                              | PK id                        |
|    serial_number (UQ)  |                              | FK transaction_id            |
|    total_amount        |                              |    item_code                 |
|    amount_tendered     |                              |    item_name                 |
|    change_amount       |                              |    quantity                  |
|    cashier_name        |                              |    unit_cost_price_snapshot  |
|    transaction_timestamp|                             |    unit_selling_price_snapshot|
+------------------------+                              |    subtotal                  |
                                                        +------------------------------+
                                                                       |
                                                                       | (Logical reference
                                                                       |  by item_code)
+------------------------+                                             v
|         items          |<--------------------------------------------+
+------------------------+
| PK id                  |             1:N              +------------------------------+
|    code (UQ, NOCASE)   |-----------------------------<|      restock_audit_log       |
|    name                |                              +------------------------------+
|    category            |                              | PK id                        |
|    cost_price          |                              |    item_code                 |
|    current_selling_price|                             |    quantity_added            |
|    current_stock       |                              |    previous_stock            |
|    min_stock_threshold |                              |    new_stock                 |
|    created_at          |                              |    batch_notes               |
|    updated_at          |                              |    performed_by              |
+------------------------+                              |    timestamp                 |
            |                                           +------------------------------+
            |                          1:N              +------------------------------+
            +------------------------------------------<|      price_history_log       |
                                                        +------------------------------+
                                                        | PK id                        |
                                                        |    item_code                 |
                                                        |    old_price                 |
                                                        |    new_price                 |
                                                        |    changed_by                |
                                                        |    timestamp                 |
                                                        +------------------------------+
```

---

### 2.2 Comprehensive DDL Script (`schema.sql`)

```sql
-- ============================================================================
-- SUPERMARKET AUTOMATION SOFTWARE (SAS) - DDL SCHEMA DEFINITION
-- SQLite 3 Database Schema (WAL Enabled, Foreign Keys Enforced)
-- ============================================================================

-- 1. Schema Migration Tracking Table
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 2. Items Catalog Table
-- Stores supermarket product inventory, pricing, and thresholds.
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE COLLATE NOCASE,
    name TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'General',
    cost_price REAL NOT NULL CHECK (cost_price >= 0.0),
    current_selling_price REAL NOT NULL CHECK (current_selling_price >= 0.0),
    current_stock INTEGER NOT NULL CHECK (current_stock >= 0),
    min_stock_threshold INTEGER NOT NULL DEFAULT 5 CHECK (min_stock_threshold >= 0),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Performance Indexes for Items
CREATE INDEX IF NOT EXISTS idx_items_code ON items(code);
CREATE INDEX IF NOT EXISTS idx_items_name ON items(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_items_category ON items(category);
CREATE INDEX IF NOT EXISTS idx_items_low_stock ON items(current_stock, min_stock_threshold);

-- 3. Sales Transactions Master Table
-- Stores completed bill headers, timestamps, and payment details.
CREATE TABLE IF NOT EXISTS sales_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_number TEXT NOT NULL UNIQUE,
    total_amount REAL NOT NULL CHECK (total_amount >= 0.0),
    amount_tendered REAL NOT NULL CHECK (amount_tendered >= total_amount),
    change_amount REAL NOT NULL CHECK (change_amount >= 0.0),
    cashier_name TEXT NOT NULL DEFAULT 'Cashier',
    transaction_timestamp TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Indexes for Sales Transactions
CREATE INDEX IF NOT EXISTS idx_sales_serial ON sales_transactions(serial_number);
CREATE INDEX IF NOT EXISTS idx_sales_timestamp ON sales_transactions(transaction_timestamp);

-- 4. Sales Transaction Items Table (Line Items)
-- Stores immutable snapshots of prices at the moment of sale.
CREATE TABLE IF NOT EXISTS sales_transaction_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id INTEGER NOT NULL,
    item_code TEXT NOT NULL,
    item_name TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_cost_price_snapshot REAL NOT NULL CHECK (unit_cost_price_snapshot >= 0.0),
    unit_selling_price_snapshot REAL NOT NULL CHECK (unit_selling_price_snapshot >= 0.0),
    subtotal REAL NOT NULL CHECK (subtotal >= 0.0),
    FOREIGN KEY (transaction_id) REFERENCES sales_transactions(id) ON DELETE CASCADE
);

-- Indexes for Sales Transaction Items
CREATE INDEX IF NOT EXISTS idx_sales_items_tx_id ON sales_transaction_items(transaction_id);
CREATE INDEX IF NOT EXISTS idx_sales_items_item_code ON sales_transaction_items(item_code);

-- 5. Restock Audit Log Table
-- Tracks batch additions to stock for supplier audits.
CREATE TABLE IF NOT EXISTS restock_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_code TEXT NOT NULL,
    quantity_added INTEGER NOT NULL CHECK (quantity_added > 0),
    previous_stock INTEGER NOT NULL CHECK (previous_stock >= 0),
    new_stock INTEGER NOT NULL CHECK (new_stock >= previous_stock),
    batch_notes TEXT,
    performed_by TEXT NOT NULL DEFAULT 'Staff',
    timestamp TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Indexes for Restock Audit Log
CREATE INDEX IF NOT EXISTS idx_restock_item_code ON restock_audit_log(item_code);
CREATE INDEX IF NOT EXISTS idx_restock_timestamp ON restock_audit_log(timestamp);

-- 6. Price History Log Table
-- Tracks manager dynamic price adjustments over time.
CREATE TABLE IF NOT EXISTS price_history_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_code TEXT NOT NULL,
    old_price REAL NOT NULL CHECK (old_price >= 0.0),
    new_price REAL NOT NULL CHECK (new_price >= 0.0),
    changed_by TEXT NOT NULL DEFAULT 'Manager',
    timestamp TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Indexes for Price History Log
CREATE INDEX IF NOT EXISTS idx_price_history_item_code ON price_history_log(item_code);
CREATE INDEX IF NOT EXISTS idx_price_history_timestamp ON price_history_log(timestamp);
```

---

### 2.3 Detailed Field Specifications & Domain Rationale

| Table | Field | Type | Constraints | Purpose / Rationale |
|---|---|---|---|---|
| `items` | `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Surrogate internal identifier |
| `items` | `code` | `TEXT` | `NOT NULL UNIQUE COLLATE NOCASE` | Barcode / SKU code. Case-insensitive matching allows scanner / keyboard input |
| `items` | `name` | `TEXT` | `NOT NULL` | Descriptive item display name (e.g. "Full Cream Milk 1L") |
| `items` | `category` | `TEXT` | `NOT NULL DEFAULT 'General'` | Product group for filtering and analytics grouping |
| `items` | `cost_price` | `REAL` | `NOT NULL CHECK (cost_price >= 0.0)` | Wholesale acquisition cost per unit |
| `items` | `current_selling_price`| `REAL` | `NOT NULL CHECK (current_selling_price >= 0.0)`| Active retail selling price per unit |
| `items` | `current_stock` | `INTEGER` | `NOT NULL CHECK (current_stock >= 0)` | Available physical stock. DB engine rejects any update reducing stock below 0 |
| `items` | `min_stock_threshold` | `INTEGER` | `NOT NULL DEFAULT 5 CHECK (...)` | Low-stock alert trigger level |
| `items` | `created_at` / `updated_at` | `TEXT` | `NOT NULL` | ISO-8601 UTC timestamp tracking item modification lifecycle |
| `sales_transactions` | `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Master bill primary key |
| `sales_transactions` | `serial_number` | `TEXT` | `NOT NULL UNIQUE` | Human-readable receipt serial (e.g., `TXN-20261002-0001`) |
| `sales_transactions` | `total_amount` | `REAL` | `NOT NULL CHECK (total_amount >= 0.0)` | Sum total of line subtotals |
| `sales_transactions` | `amount_tendered` | `REAL` | `NOT NULL CHECK (amount_tendered >= total_amount)`| Cash paid by customer. Constraint prevents underpayment |
| `sales_transactions` | `change_amount` | `REAL` | `NOT NULL CHECK (change_amount >= 0.0)` | Cash change returned (`amount_tendered - total_amount`) |
| `sales_transactions` | `cashier_name` | `TEXT` | `NOT NULL DEFAULT 'Cashier'` | Name of staff member operating POS register |
| `sales_transactions` | `transaction_timestamp`| `TEXT` | `NOT NULL` | ISO-8601 UTC timestamp used for filtering sales statistics |
| `sales_transaction_items` | `transaction_id` | `INTEGER` | `NOT NULL FK REFERENCES sales_transactions(id) ON DELETE CASCADE` | Associates item line with receipt master |
| `sales_transaction_items` | `item_code` | `TEXT` | `NOT NULL` | Code of sold item at time of transaction |
| `sales_transaction_items` | `item_name` | `TEXT` | `NOT NULL` | Name snapshot; ensures historical receipts match printed copy even if catalog renamed |
| `sales_transaction_items` | `quantity` | `INTEGER` | `NOT NULL CHECK (quantity > 0)` | Quantity purchased (strictly positive) |
| `sales_transaction_items` | `unit_cost_price_snapshot`| `REAL` | `NOT NULL CHECK (>= 0.0)` | Cost price at moment of sale (vital for R3 profit invariance) |
| `sales_transaction_items` | `unit_selling_price_snapshot`| `REAL` | `NOT NULL CHECK (>= 0.0)`| Selling price at moment of sale |
| `sales_transaction_items` | `subtotal` | `REAL` | `NOT NULL CHECK (subtotal >= 0.0)` | Line total (`quantity * unit_selling_price_snapshot`) |
| `restock_audit_log` | `quantity_added` | `INTEGER` | `NOT NULL CHECK (quantity_added > 0)` | Units received from supplier |
| `restock_audit_log` | `previous_stock` | `INTEGER` | `NOT NULL CHECK (previous_stock >= 0)` | Inventory before restock |
| `restock_audit_log` | `new_stock` | `INTEGER` | `NOT NULL CHECK (new_stock >= previous_stock)` | Inventory after restock (`previous_stock + quantity_added`) |
| `price_history_log` | `old_price` / `new_price` | `REAL` | `NOT NULL CHECK (>= 0.0)` | Audit trail of price changes with timestamp & user ID |

---

### 2.4 The Price Snapshot Invariance Guarantee (Requirement R3)

**The Invariance Problem:**
In standard naive database designs, line items store only a foreign key to `items(id)`. When an analytics query calculates profit over a past period, it queries:
`SELECT SUM(quantity * (items.current_selling_price - items.cost_price)) FROM line_items JOIN items ...`
If a manager changes the selling price of Milk from $3.00 to $4.50 next week, running a report for *last week* will erroneously calculate profit using the new $4.50 price, completely corrupting past business analytics and tax reports!

**The SAS Architectural Solution:**
SAS enforces transaction-time price snapshotting. When a sale occurs:
1. `unit_cost_price_snapshot = items.cost_price`
2. `unit_selling_price_snapshot = items.current_selling_price`
3. `subtotal = quantity * unit_selling_price_snapshot`

Analytics queries compute revenue and profit exclusively from `sales_transaction_items`:
$$\text{Revenue} = \sum (\text{subtotal})$$
$$\text{Cost of Goods Sold (COGS)} = \sum (\text{quantity} \times \text{unit\_cost\_price\_snapshot})$$
$$\text{Net Profit} = \text{Revenue} - \text{COGS} = \sum (\text{subtotal} - (\text{quantity} \times \text{unit\_cost\_price\_snapshot}))$$

Because these snapshot columns are written once and never updated, modifying `items.current_selling_price` or `items.cost_price` has **mathematically zero effect** on historical statistics.

---

## 3. Concurrency, ACID Transactions & Atomic Rollback Guarantees

### 3.1 SQLite Concurrency Characteristics & WAL Mode

SQLite is a serverless, file-based database. In default rollback journal mode, writing locks the entire database file, preventing concurrent reads. To deliver responsive desktop UI performance, SAS configures SQLite with **Write-Ahead Logging (WAL)**:

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
```

#### Key Advantages of WAL Mode in SAS:
1. **Concurrent Reads and Writes:** In WAL mode, writers append changes to the `-wal` file while readers continue accessing the main database file simultaneously. A sales clerk completing a sale will never block a manager viewing real-time inventory or analytics.
2. **Speed:** `PRAGMA synchronous = NORMAL` in WAL mode issues sync operations only during WAL checkpoints rather than on every transaction commit, drastically boosting write throughput while maintaining durability against application crashes.
3. **Foreign Key Enforcement:** By default, SQLite has foreign key constraints disabled for backward compatibility. Executing `PRAGMA foreign_keys = ON;` upon connection initialization ensures relational integrity (e.g. cascading deletes, invalid transaction references).
4. **Busy Timeout Handling:** `PRAGMA busy_timeout = 5000;` ensures that if a write lock is temporarily held, subsequent write attempts automatically retry up to 5,000 milliseconds before throwing `SQLITE_BUSY`.

---

### 3.2 Atomic POS Sales Checkout Transaction Flow

```
[POS Clerk Clicks 'Complete Sale']
               │
               ▼
   [Obtain Database Connection]
               │
               ▼
   [conn.setAutoCommit(false)]  ◄─── BEGIN TRANSACTION
               │
               ├──────────────────────────────────────────────┐
               ▼                                              ▼
   [For Each Cart Item: Validate Stock]             [Stock Insufficient?]
   UPDATE items                                               │
   SET current_stock = current_stock - ?,                     ▼
       updated_at = strftime(...)                   [conn.rollback()]
   WHERE code = ? AND current_stock >= ?;                     │
               │                                              ▼
         Rows affected == 1?                         [Throw InsufficientStockException]
               │ No                                  (Cart preserved, UI alert shown)
               ▼
       [conn.rollback()]
               │ Yes (all items decremented)
               ▼
   [Insert sales_transactions Master Record]
   (serial_number, total_amount, amount_tendered, change_amount, timestamp)
               │
               ▼
   [Retrieve Generated Transaction ID]
               │
               ▼
   [Batch Insert sales_transaction_items with Snapshots]
   (transaction_id, item_code, item_name, quantity, cost_snapshot, price_snapshot, subtotal)
               │
               ▼
   [conn.commit()]  ◄─── COMMIT TRANSACTION
               │
               ▼
   [Generate Receipt DTO & Display Thermal Preview]
```

#### SQL Defenses Against Race Conditions & Inconsistencies:
1. **Atomic Conditional Decrement:**
   ```sql
   UPDATE items
   SET current_stock = current_stock - ?,
       updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
   WHERE code = ? AND current_stock >= ?;
   ```
   If another register or thread decremented stock concurrently such that remaining stock is lower than required, `executeUpdate()` returns `0`. The application detects `rowsAffected == 0` and immediately initiates rollback.
2. **Storage-Level Constraint Defense:**
   Even if application logic failed to perform the check, the database constraint `CHECK (current_stock >= 0)` causes SQLite to reject any decrement that would result in negative stock with a `SQLiteException (SQLITE_CONSTRAINT_CHECK)`.

---

### 3.3 Atomic Restock Audit Flow
When an employee enters new supply deliveries:
```
conn.setAutoCommit(false);
1. SELECT current_stock FROM items WHERE code = ? FOR UPDATE (logical lock)
2. UPDATE items SET current_stock = current_stock + ? WHERE code = ?
3. INSERT INTO restock_audit_log (item_code, quantity_added, previous_stock, new_stock, batch_notes, performed_by, timestamp)
   VALUES (?, ?, ?, ?, ?, ?, strftime(...));
conn.commit();
// If any step fails -> conn.rollback();
```

---

### 3.4 Atomic Dynamic Price Change Flow
When the manager updates an item's selling price:
```
conn.setAutoCommit(false);
1. SELECT current_selling_price FROM items WHERE code = ?
2. UPDATE items SET current_selling_price = ?, updated_at = strftime(...) WHERE code = ?
3. INSERT INTO price_history_log (item_code, old_price, new_price, changed_by, timestamp)
   VALUES (?, ?, ?, ?, strftime(...));
conn.commit();
// If any step fails -> conn.rollback();
```

---

## 4. Database Connection Management & DataSource Architecture

### 4.1 Connection Management Strategy: HikariCP vs SQLiteDataSource

In SQLite, while multiple connections can read concurrently in WAL mode, **only one connection may write at any given instant**.

There are two primary approaches in Java desktop applications:

#### Strategy A: Single Synchronized Writer + Read Pool (Recommended for Multi-threaded JavaFX)
- A dedicated write connection or a centralized `DatabaseManager` with a `ReentrantLock writeLock = new ReentrantLock(true);` serializing write transactions.
- Prevents `SQLITE_BUSY` exceptions from ever occurring at runtime.
- Allows read queries (table refreshes, searches, analytics reports) to execute concurrently on separate connections without waiting for writer completion.

#### Strategy B: HikariCP Connection Pool with SQLiteConfig
Using HikariCP provides connection pooling, connection recycling, and leak detection:
```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:sqlite:supermarket.db");
config.setMaximumPoolSize(5);
config.setMinimumIdle(1);
config.setConnectionTimeout(10000); // 10s wait for busy locks
config.setIdleTimeout(60000);
config.setMaxLifetime(1800000);

// Pass SQLite-specific PRAGMAs to SQLiteConfig
SQLiteConfig sqliteConfig = new SQLiteConfig();
sqliteConfig.enforceForeignKeys(true);
sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
sqliteConfig.setBusyTimeout(5000);

config.setDataSourceProperties(sqliteConfig.toProperties());
HikariDataSource dataSource = new HikariDataSource(config);
```

#### Strategy C: Zero-Dependency Singleton SQLite Connection Factory (Standalone OpenJDK friendly)
To keep the application ultra-lightweight and eliminate third-party pooling overhead when packaging an executable JAR, SAS implements a robust `DatabaseManager` singleton:

```java
package com.supermarket.persistence;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

public final class DatabaseManager {
    private static volatile DatabaseManager instance;
    private final SQLiteDataSource dataSource;
    private final ReentrantLock writeLock = new ReentrantLock(true);

    private DatabaseManager(String dbPath) {
        // Ensure parent directories exist
        File dbFile = new File(dbPath);
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);

        this.dataSource = new SQLiteDataSource(config);
        this.dataSource.setUrl("jdbc:sqlite:" + dbPath);
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(getDefaultDatabasePath());
                }
            }
        }
        return instance;
    }

    public static DatabaseManager initialize(String dbPath) {
        synchronized (DatabaseManager.class) {
            instance = new DatabaseManager(dbPath);
            return instance;
        }
    }

    public static String getDefaultDatabasePath() {
        String userHome = System.getProperty("user.home");
        File appDir = new File(userHome, ".supermarket_automation");
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        return new File(appDir, "supermarket.db").getAbsolutePath();
    }

    public Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        // Ensure foreign keys are strictly enforced for every newly opened connection
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    public ReentrantLock getWriteLock() {
        return writeLock;
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}
```

---

## 5. Database Initialization & Automated Schema Migrations

### 5.1 Zero-Configuration Startup Lifecycle

When the application launches:
1. `DatabaseManager.getInstance()` locates or creates the SQLite file (`supermarket.db`).
2. `MigrationRunner.migrate()` inspects `schema_version`.
3. If `schema_version` does not exist or version is 0:
   - Applies `V1__init_schema.sql` (Creates all tables, indexes, constraints).
   - Applies `V2__seed_initial_catalog.sql` (Populates baseline supermarket inventory).
   - Records completed migrations into `schema_version`.
4. If version is up to date, startup completes in less than 25 milliseconds.

### 5.2 Pure-Java Migration Runner Implementation

```java
package com.supermarket.persistence.migration;

import com.supermarket.persistence.DatabaseManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

public class MigrationRunner {
    private static final Logger LOGGER = Logger.getLogger(MigrationRunner.class.getName());

    public static void migrate() {
        DatabaseManager db = DatabaseManager.getInstance();
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Ensure schema_version table exists
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version INTEGER PRIMARY KEY,
                            description TEXT NOT NULL,
                            applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                        );
                    """);
                }

                int currentVersion = getCurrentVersion(conn);
                LOGGER.info("Current database schema version: " + currentVersion);

                // 2. Apply V1 if needed
                if (currentVersion < 1) {
                    LOGGER.info("Applying migration V1: Initial Schema");
                    executeSqlScript(conn, "/db/migration/V1__init_schema.sql");
                    recordVersion(conn, 1, "Initial Schema Creation");
                }

                // 3. Apply V2 if needed (Seed Data)
                if (currentVersion < 2) {
                    LOGGER.info("Applying migration V2: Catalog Seed Data");
                    executeSqlScript(conn, "/db/migration/V2__seed_initial_catalog.sql");
                    recordVersion(conn, 2, "Catalog Seed Data");
                }

                conn.commit();
                LOGGER.info("Database migrations applied successfully.");
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Migration failed, rolling back: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Fatal database initialization error", e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    private static int getCurrentVersion(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void recordVersion(Connection conn, int version, String description) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, description);
            ps.executeUpdate();
        }
    }

    private static void executeSqlScript(Connection conn, String resourcePath) throws Exception {
        InputStream in = MigrationRunner.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Migration script not found: " + resourcePath);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                sb.append(line).append("\n");
            }
        }
        String[] statements = sb.toString().split(";");
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                if (!sql.trim().isEmpty()) {
                    stmt.execute(sql.trim());
                }
            }
        }
    }
}
```

---

### 5.3 Initial Seed Catalog Data (`V2__seed_initial_catalog.sql`)

To guarantee immediate functional verification out of the box, standard supermarket inventory is seeded:

```sql
INSERT INTO items (code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold) VALUES
('PROD-001', 'Fresh Whole Milk 1L', 'Dairy', 1.20, 1.89, 50, 10),
('PROD-002', 'White Sandwich Bread 700g', 'Bakery', 1.10, 2.25, 40, 8),
('PROD-003', 'Free Range Eggs 12pk', 'Dairy', 2.40, 3.99, 30, 5),
('PROD-004', 'Organic Bananas 1kg', 'Produce', 0.90, 1.69, 65, 15),
('PROD-005', 'Granny Smith Apples 1kg', 'Produce', 1.50, 2.99, 45, 10),
('PROD-006', 'Basmati Rice 2kg', 'Grains', 3.00, 5.49, 25, 5),
('PROD-007', 'Extra Virgin Olive Oil 500ml', 'Oils', 4.50, 7.99, 20, 5),
('PROD-008', 'Sparkling Mineral Water 1.5L', 'Beverages', 0.60, 1.29, 80, 12),
('PROD-009', 'Dark Roast Coffee Beans 500g', 'Beverages', 5.00, 8.99, 18, 4),
('PROD-010', 'Artisan Cheddar Cheese 250g', 'Dairy', 2.80, 4.79, 22, 6);
```

---

## 6. DAO Interfaces, Entity Models & Repository Specifications

### 6.1 Domain Entity Models

```java
package com.supermarket.domain.model;

import java.time.LocalDateTime;

public record Item(
    Long id,
    String code,
    String name,
    String category,
    double costPrice,
    double currentSellingPrice,
    int currentStock,
    int minStockThreshold,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public boolean isLowStock() {
        return currentStock <= minStockThreshold;
    }
}

public record SalesTransaction(
    Long id,
    String serialNumber,
    double totalAmount,
    double amountTendered,
    double changeAmount,
    String cashierName,
    LocalDateTime timestamp
) {}

public record SalesTransactionItem(
    Long id,
    Long transactionId,
    String itemCode,
    String itemName,
    int quantity,
    double unitCostPriceSnapshot,
    double unitSellingPriceSnapshot,
    double subtotal
) {
    public double getProfit() {
        return subtotal - (quantity * unitCostPriceSnapshot);
    }
}

public record RestockLog(
    Long id,
    String itemCode,
    int quantityAdded,
    int previousStock,
    int newStock,
    String batchNotes,
    String performedBy,
    LocalDateTime timestamp
) {}

public record PriceHistoryEntry(
    Long id,
    String itemCode,
    double oldPrice,
    double newPrice,
    String changedBy,
    LocalDateTime timestamp
) {}

public record ItemSalesStat(
    String itemCode,
    String itemName,
    int totalQuantitySold,
    double totalRevenue,
    double totalCost,
    double netProfit
) {}

public record PeriodAnalyticsSummary(
    int totalTransactions,
    int totalItemsSold,
    double totalRevenue,
    double totalCost,
    double totalNetProfit
) {}
```

---

### 6.2 DAO Interfaces

```java
package com.supermarket.persistence.dao;

import com.supermarket.domain.model.*;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ItemDao {
    Optional<Item> findByCode(String code, Connection conn);
    Optional<Item> findById(long id, Connection conn);
    List<Item> findAll(Connection conn);
    List<Item> search(String query, String category, Connection conn);
    List<Item> findLowStock(Connection conn);
    long insert(Item item, Connection conn);
    void update(Item item, Connection conn);
    
    // Core transactional stock manipulation
    boolean decrementStock(String code, int quantity, Connection conn);
    void incrementStock(String code, int quantity, Connection conn);
    void updateSellingPrice(String code, double newPrice, Connection conn);
}

public interface SalesTransactionDao {
    long insertTransaction(SalesTransaction tx, Connection conn);
    void insertTransactionItem(SalesTransactionItem item, Connection conn);
    Optional<SalesTransaction> findById(long id, Connection conn);
    Optional<SalesTransaction> findBySerialNumber(String serialNumber, Connection conn);
    List<SalesTransaction> findByDateRange(LocalDateTime start, LocalDateTime end, Connection conn);
    List<SalesTransactionItem> findItemsByTransactionId(long transactionId, Connection conn);
}

public interface RestockAuditDao {
    void insert(RestockLog log, Connection conn);
    List<RestockLog> findByItemCode(String itemCode, Connection conn);
    List<RestockLog> findRecent(int limit, Connection conn);
}

public interface PriceHistoryDao {
    void insert(PriceHistoryEntry entry, Connection conn);
    List<PriceHistoryEntry> findByItemCode(String itemCode, Connection conn);
}

public interface AnalyticsDao {
    List<ItemSalesStat> getItemSalesStatistics(LocalDateTime start, LocalDateTime end, Connection conn);
    PeriodAnalyticsSummary getPeriodSummary(LocalDateTime start, LocalDateTime end, Connection conn);
}
```

---

### 6.3 Optimized Analytics SQL Queries (Fulfilling R3)

#### Query 1: Detailed Item-by-Item Statistics for Any Date Range
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
ORDER BY total_revenue DESC;
```

#### Query 2: Executive Period KPI Summary Cards
```sql
SELECT 
    COUNT(DISTINCT st.id) AS total_transactions,
    COALESCE(SUM(sti.quantity), 0) AS total_items_sold,
    ROUND(COALESCE(SUM(sti.subtotal), 0.0), 2) AS total_revenue,
    ROUND(COALESCE(SUM(sti.quantity * sti.unit_cost_price_snapshot), 0.0), 2) AS total_cost,
    ROUND(COALESCE(SUM(sti.subtotal - (sti.quantity * sti.unit_cost_price_snapshot)), 0.0), 2) AS total_net_profit
FROM sales_transactions st
LEFT JOIN sales_transaction_items sti ON st.id = sti.transaction_id
WHERE st.transaction_timestamp >= ? AND st.transaction_timestamp <= ?;
```

---

## 7. Transactional Service Layer Architecture

The Service Layer coordinates business invariants across DAOs and manages the explicit JDBC transaction boundary.

### 7.1 BillingService (Atomic POS Checkout)

```java
package com.supermarket.service;

import com.supermarket.domain.model.*;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.dao.ItemDao;
import com.supermarket.persistence.dao.SalesTransactionDao;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class BillingService {
    private final DatabaseManager dbManager;
    private final ItemDao itemDao;
    private final SalesTransactionDao salesDao;
    private final AtomicLong serialSequence = new AtomicLong(System.currentTimeMillis() % 100000);

    public BillingService(DatabaseManager dbManager, ItemDao itemDao, SalesTransactionDao salesDao) {
        this.dbManager = dbManager;
        this.itemDao = itemDao;
        this.salesDao = salesDao;
    }

    public record CartLine(Item item, int quantity) {}

    public record ReceiptResult(
        SalesTransaction transaction,
        List<SalesTransactionItem> items
    ) {}

    public ReceiptResult checkout(List<CartLine> cart, double amountTendered, String cashier) {
        if (cart == null || cart.isEmpty()) {
            throw new IllegalArgumentException("Cart cannot be empty");
        }

        // Calculate total amount
        double totalAmount = 0.0;
        for (CartLine line : cart) {
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be positive for " + line.item().name());
            }
            totalAmount += line.item().currentSellingPrice() * line.quantity();
        }
        totalAmount = Math.round(totalAmount * 100.0) / 100.0;

        if (amountTendered < totalAmount) {
            throw new IllegalArgumentException(
                String.format("Insufficient payment: Tendered $%.2f is less than total $%.2f", amountTendered, totalAmount)
            );
        }
        double changeAmount = Math.round((amountTendered - totalAmount) * 100.0) / 100.0;

        String serialNumber = generateSerialNumber();
        LocalDateTime now = LocalDateTime.now();

        // Lock to ensure serialized write transactions
        dbManager.getWriteLock().lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Atomically decrement stock for all items
                for (CartLine line : cart) {
                    boolean success = itemDao.decrementStock(line.item().code(), line.quantity(), conn);
                    if (!success) {
                        // Query current stock for detailed diagnostic error message
                        Optional<Item> current = itemDao.findByCode(line.item().code(), conn);
                        int available = current.map(Item::currentStock).orElse(0);
                        throw new InsufficientStockException(
                            String.format("Insufficient stock for item '%s' (%s). Requested: %d, Available: %d",
                                line.item().name(), line.item().code(), line.quantity(), available)
                        );
                    }
                }

                // 2. Insert Master Transaction
                SalesTransaction tx = new SalesTransaction(
                    null, serialNumber, totalAmount, amountTendered, changeAmount, cashier, now
                );
                long txId = salesDao.insertTransaction(tx, conn);

                // 3. Insert Line Items with Price Snapshots
                List<SalesTransactionItem> savedItems = new ArrayList<>();
                for (CartLine line : cart) {
                    double subtotal = Math.round(line.item().currentSellingPrice() * line.quantity() * 100.0) / 100.0;
                    SalesTransactionItem txItem = new SalesTransactionItem(
                        null,
                        txId,
                        line.item().code(),
                        line.item().name(),
                        line.quantity(),
                        line.item().costPrice(),             // Cost snapshot
                        line.item().currentSellingPrice(),   // Selling price snapshot
                        subtotal
                    );
                    salesDao.insertTransactionItem(txItem, conn);
                    savedItems.add(txItem);
                }

                // 4. Commit atomic transaction
                conn.commit();

                SalesTransaction completedTx = new SalesTransaction(
                    txId, serialNumber, totalAmount, amountTendered, changeAmount, cashier, now
                );
                return new ReceiptResult(completedTx, savedItems);

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Billing transaction failed: " + e.getMessage(), e);
        } finally {
            dbManager.getWriteLock().unlock();
        }
    }

    private String generateSerialNumber() {
        String datePrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = serialSequence.incrementAndGet();
        return String.format("TXN-%s-%04d", datePrefix, seq % 10000);
    }
}
```

---

### 7.2 InventoryService (Restock & Dynamic Price Updates)

```java
package com.supermarket.service;

import com.supermarket.domain.model.*;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.dao.ItemDao;
import com.supermarket.persistence.dao.PriceHistoryDao;
import com.supermarket.persistence.dao.RestockAuditDao;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

public class InventoryService {
    private final DatabaseManager dbManager;
    private final ItemDao itemDao;
    private final RestockAuditDao restockDao;
    private final PriceHistoryDao priceHistoryDao;

    public InventoryService(DatabaseManager dbManager, ItemDao itemDao, RestockAuditDao restockDao, PriceHistoryDao priceHistoryDao) {
        this.dbManager = dbManager;
        this.itemDao = itemDao;
        this.restockDao = restockDao;
        this.priceHistoryDao = priceHistoryDao;
    }

    public void restockItem(String itemCode, int quantityAdded, String batchNotes, String performedBy) {
        if (quantityAdded <= 0) {
            throw new IllegalArgumentException("Quantity added must be strictly positive");
        }

        dbManager.getWriteLock().lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Item item = itemDao.findByCode(itemCode, conn)
                    .orElseThrow(() -> new IllegalArgumentException("Item code not found: " + itemCode));

                int previousStock = item.currentStock();
                int newStock = previousStock + quantityAdded;

                itemDao.incrementStock(itemCode, quantityAdded, conn);

                RestockLog log = new RestockLog(
                    null, itemCode, quantityAdded, previousStock, newStock, batchNotes, performedBy, LocalDateTime.now()
                );
                restockDao.insert(log, conn);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Restock failed: " + e.getMessage(), e);
        } finally {
            dbManager.getWriteLock().unlock();
        }
    }

    public void updateItemPrice(String itemCode, double newSellingPrice, String changedBy) {
        if (newSellingPrice < 0.0) {
            throw new IllegalArgumentException("Selling price cannot be negative");
        }

        dbManager.getWriteLock().lock();
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Item item = itemDao.findByCode(itemCode, conn)
                    .orElseThrow(() -> new IllegalArgumentException("Item code not found: " + itemCode));

                double oldPrice = item.currentSellingPrice();
                itemDao.updateSellingPrice(itemCode, newSellingPrice, conn);

                PriceHistoryEntry history = new PriceHistoryEntry(
                    null, itemCode, oldPrice, newSellingPrice, changedBy, LocalDateTime.now()
                );
                priceHistoryDao.insert(history, conn);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Price update failed: " + e.getMessage(), e);
        } finally {
            dbManager.getWriteLock().unlock();
        }
    }
}
```

---

## 8. Edge Cases, Invariants, and Failure Mode Analysis

| Scenario / Edge Case | Failure Risk | Mitigation / Architectural Defense |
|---|---|---|
| **Concurrent checkout of final inventory unit** | Stock drops below zero; phantom sales | `UPDATE items ... WHERE code = ? AND current_stock >= ?` returns `0` rows affected. Transaction immediately rolls back with `InsufficientStockException`. Enforced by `CHECK (current_stock >= 0)`. |
| **Manager adjusts price while cart is open in POS** | Receipt price mismatch or stale totals | Cart stores item code and selling price at time item was scanned. When checkout begins, price snapshots are committed to the transaction record. Historic records remain isolated. |
| **Cash tendered < Total amount** | Incomplete payment | Java service checks `amountTendered >= totalAmount`. In addition, database constraint `CHECK (amount_tendered >= total_amount)` rejects invalid inserts. |
| **Application crash / Power loss mid-checkout** | Corrupt bill or lost inventory | SQLite WAL journal guarantees full ACID atomicity. Uncommitted transactions are automatically rolled back upon next connection open. Checkpoint recovers clean state. |
| **Renaming or removing an item from catalog** | Historical receipts cannot render item name | `sales_transaction_items` denormalizes and preserves `item_name` and `item_code` snapshots permanently. Past receipts render identically forever. |
| **Special characters in barcode / SKU** | SQL injection | All DAO operations strictly use parameter-bound `PreparedStatement`. No string concatenation is used in queries. |
| **Case differences in SKU input (e.g. `prod-001` vs `PROD-001`)** | Item lookup misses | `items.code` column is created with `COLLATE NOCASE`. Lookups match regardless of capitalization. |

---

## 9. Verification & Automated Test Strategy

To verify all persistence invariants before UI integration:

### 9.1 In-Memory SQLite Testing Setup
In automated JUnit 5 tests, use SQLite in-memory connections:
`jdbc:sqlite::memory:` or unique named in-memory databases `jdbc:sqlite:file:memdb1?mode=memory&cache=shared`.

### 9.2 Key Automated Test Cases to Implement:
1. **`testSuccessfulSaleDecrementsStockAndSavesReceipt()`**:
   - Given Milk with stock 10.
   - When selling 3 units.
   - Then `current_stock` equals 7, `sales_transactions` has 1 record, `sales_transaction_items` has 1 record with correct snapshots.
2. **`testInsufficientStockRollsBackEntireTransaction()`**:
   - Given Item A with stock 5, Item B with stock 2.
   - When attempting checkout of Item A (quantity 3) and Item B (quantity 5).
   - Then transaction throws `InsufficientStockException`.
   - Verify Item A stock remains unchanged at 5, Item B remains at 2, and no sales transaction record is written.
3. **`testPriceSnapshotInvariance()`**:
   - Item A has cost $1.00, selling price $2.00.
   - Sell 10 units on Day 1 (Profit = $10.00).
   - Manager updates selling price to $5.00 on Day 2.
   - Query analytics for Day 1.
   - Assert Day 1 profit is still exactly $10.00 (not $40.00).
4. **`testConcurrentRestockAndSale()`**:
   - Execute concurrent threads restocking and selling items.
   - Verify stock equations balance: `Final Stock == Initial Stock + Total Restocked - Total Sold`.
5. **`testCheckConstraintsRejectNegativeValues()`**:
   - Direct SQL execution attempting to insert negative stock or negative price triggers `SQLiteException` with constraint check failure.

---

## 10. Summary of File Deliverables for Implementer

| Deliverable Path | Type | Purpose |
|---|---|---|
| `src/main/resources/db/migration/V1__init_schema.sql` | SQL Resource | Baseline DDL tables, indexes, and CHECK constraints |
| `src/main/resources/db/migration/V2__seed_initial_catalog.sql` | SQL Resource | Standard supermarket item catalog for instant POS verification |
| `com.supermarket.persistence.DatabaseManager` | Java Class | Singleton SQLite connection provider, WAL pragmas, write lock |
| `com.supermarket.persistence.migration.MigrationRunner` | Java Class | Lightweight versioned SQL migration execution |
| `com.supermarket.domain.model.*` | Java Records | Immutable domain entities and analytics DTOs |
| `com.supermarket.persistence.dao.*` | Java Interfaces & Impls | JDBC DAOs (`ItemDao`, `SalesTransactionDao`, `RestockAuditDao`, `PriceHistoryDao`, `AnalyticsDao`) |
| `com.supermarket.service.*` | Java Classes | Transactional business orchestrators (`BillingService`, `InventoryService`, `AnalyticsService`) |
| `src/test/java/com/supermarket/persistence/*Test.java` | JUnit 5 Tests | Transactional rollback, inventory invariants, and analytics snapshot tests |
