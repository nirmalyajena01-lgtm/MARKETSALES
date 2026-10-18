-- ============================================================================
-- SUPERMARKET AUTOMATION SOFTWARE (SAS) - SEED DATA
-- V2: Realistic Initial Supermarket Inventory Catalog
-- ============================================================================

INSERT INTO items (code, name, category, cost_price, current_selling_price, current_stock, min_stock_threshold) VALUES
-- Produce
('PROD-001', 'Organic Bananas 1kg', 'Produce', 0.90, 1.69, 65, 15),
('PROD-002', 'Granny Smith Apples 1kg', 'Produce', 1.50, 2.99, 45, 10),
('PROD-003', 'Russet Potatoes 2kg', 'Produce', 1.80, 3.49, 35, 10),
('PROD-004', 'Baby Carrots 500g', 'Produce', 0.85, 1.79, 40, 8),
('PROD-005', 'Roma Tomatoes 1kg', 'Produce', 1.60, 2.89, 30, 8),

-- Dairy
('DAIRY-001', 'Fresh Whole Milk 1L', 'Dairy', 1.20, 1.89, 50, 10),
('DAIRY-002', 'Free Range Eggs 12pk', 'Dairy', 2.40, 3.99, 30, 5),
('DAIRY-003', 'Artisan Cheddar Cheese 250g', 'Dairy', 2.80, 4.79, 22, 6),
('DAIRY-004', 'Greek Style Yogurt 500g', 'Dairy', 1.95, 3.29, 25, 5),
('DAIRY-005', 'Salted Butter 250g', 'Dairy', 2.10, 3.49, 28, 6),

-- Bakery
('BAKE-001', 'White Sandwich Bread 700g', 'Bakery', 1.10, 2.25, 40, 8),
('BAKE-002', 'Whole Wheat Bread 700g', 'Bakery', 1.25, 2.49, 35, 8),
('BAKE-003', 'Butter Croissants 4pk', 'Bakery', 2.20, 3.99, 20, 5),
('BAKE-004', 'Plain Bagels 6pk', 'Bakery', 1.80, 3.19, 25, 5),

-- Beverages
('BEV-001', 'Sparkling Mineral Water 1.5L', 'Beverages', 0.60, 1.29, 80, 12),
('BEV-002', 'Dark Roast Coffee Beans 500g', 'Beverages', 5.00, 8.99, 18, 4),
('BEV-003', 'Organic Green Tea 50 bags', 'Beverages', 2.20, 4.19, 30, 6),
('BEV-004', 'Pure Orange Juice 1L', 'Beverages', 1.80, 2.99, 35, 8),
('BEV-005', 'Classic Cola 2L', 'Beverages', 1.10, 2.19, 50, 10),

-- Canned Goods
('CANN-001', 'Organic Tomato Soup 400g', 'Canned Goods', 0.95, 1.89, 45, 10),
('CANN-002', 'Baked Beans in Tomato Sauce 420g', 'Canned Goods', 0.80, 1.59, 50, 10),
('CANN-003', 'Chunk Light Tuna in Water 185g', 'Canned Goods', 1.30, 2.49, 40, 8),
('CANN-004', 'Sweet Whole Kernel Corn 400g', 'Canned Goods', 0.75, 1.49, 38, 8),

-- Household Items
('HOUS-001', 'Dishwashing Liquid Lemon 500ml', 'Household', 1.40, 2.69, 30, 6),
('HOUS-002', 'Ultra Paper Towels 2pk', 'Household', 1.70, 3.29, 25, 5),
('HOUS-003', 'Concentrated Laundry Detergent 2L', 'Household', 5.50, 9.99, 15, 4),
('HOUS-004', 'Antibacterial Surface Cleaner 750ml', 'Household', 1.90, 3.49, 20, 5);
