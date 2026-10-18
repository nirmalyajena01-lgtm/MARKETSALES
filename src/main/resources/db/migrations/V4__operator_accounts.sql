-- ============================================================================
-- V4: Operator Accounts Table for Authentication & RBAC
-- ============================================================================

CREATE TABLE operators (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    desk TEXT,
    phone TEXT,
    masked_phone TEXT,
    initials TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_operators_username ON operators(username);

