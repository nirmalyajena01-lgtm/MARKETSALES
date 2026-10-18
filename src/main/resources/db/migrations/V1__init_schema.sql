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
