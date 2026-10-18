UPDATE items
SET cost_price = ROUND(cost_price * 83, 2),
    current_selling_price = ROUND(current_selling_price * 83, 2);

UPDATE sales_transactions
SET total_amount = ROUND(total_amount * 83, 2),
    amount_tendered = ROUND(amount_tendered * 83, 2),
    change_amount = ROUND(change_amount * 83, 2);

UPDATE sales_transaction_items
SET unit_cost_price_snapshot = ROUND(unit_cost_price_snapshot * 83, 2),
    unit_selling_price_snapshot = ROUND(unit_selling_price_snapshot * 83, 2),
    subtotal = ROUND(subtotal * 83, 2);

UPDATE price_history_log
SET old_price = ROUND(old_price * 83, 2),
    new_price = ROUND(new_price * 83, 2);