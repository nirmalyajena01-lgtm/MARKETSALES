# Original User Request

## 2026-10-02T19:11:08Z

Build a complete, production-grade Supermarket Automation Software (SAS) desktop application featuring a modern JavaFX frontend and SQLite persistence engine, fulfilling all requirements for sales billing with receipt printing, inventory tracking with stock decrement, employee restock management, manager dynamic price changes, and period-based sales profit analytics.

Working directory: C:\Users\User\.gemini\antigravity\scratch\supermarket-automation
Integrity mode: development

## Requirements

### R1. Point of Sale & Bill Generation (Sales Clerk POS)
The system must provide an interactive billing interface where the sales clerk enters or scans item codes and quantities into an active cart with real-time subtotal and change computation. Completing a sales transaction must atomically decrement the item inventory in the database and generate a printable bill containing the transaction serial number, item name, code number, quantity, unit price, item price, and total amount payable.

### R2. Inventory Catalog & Supply Restock Management
The system must maintain the inventory of all supermarket items. The manager and staff must be able to query the inventory details. The inventory of an item must decrease whenever an item is sold. The software must also provide an employee interface to update item inventory when new supply batches arrive.

### R3. Sales Statistics & Profit Tracking Over Arbitrary Periods
The system must support printing and displaying sales statistics for every item the supermarket deals with for any selectable day or arbitrary date range. The statistics must show the quantity sold, the price realized (revenue), and the net profit (realized revenue minus cost price). Updating an item's selling price in the future must never corrupt or retroactively alter historical profit statistics (guaranteed via transaction-time price snapshotting).

### R4. Manager Dynamic Selling Price Management
The supermarket manager must be provided with an interface to change the selling price of any item as market prices vary on a day-to-day basis.

### R5. Professional JavaFX User Interface & Packaging
The desktop interface must feature a modern, responsive layout (POS tab, Inventory tab, Sales Analytics tab, Price Management tab, and a modal thermal-style receipt preview) styled with polished CSS. The project must be packaged using Maven with standard OpenJDK/JavaFX configuration for standalone execution on Windows.

## Acceptance Criteria

### Automated Verification
- [ ] Maven builds cleanly (`mvn clean test`) with passing automated unit tests covering stock decrement, atomic rollback on insufficient stock, and price snapshot invariance.
- [ ] Changing an item's selling price in the catalog leaves historical receipt item records unchanged, preserving exact past profit calculations.

### Functional Verification
- [ ] Completing a sale prints/previews a bill with unique serial number, itemized lines, and total amount.
- [ ] Selling an item immediately decrements its stock quantity in the inventory table.
- [ ] Employee restock increments item stock and registers an audit log entry.
- [ ] Sales statistics accurately filter by selected date range and report correct quantity sold, realized price, and profit.
- [ ] Standalone executable JAR or Maven launcher runs the JavaFX desktop application smoothly on Windows.
