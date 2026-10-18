package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain model representing an active POS cart holding line items.
 * Preserves item insertion order and provides calculation helpers.
 */
public class Cart {
    private final Map<String, CartItem> items = new LinkedHashMap<>();

    public synchronized void addItem(CartItem item) {
        Objects.requireNonNull(item, "CartItem cannot be null");
        String code = item.itemCode();
        CartItem existing = items.get(code);
        if (existing != null) {
            items.put(code, existing.withAddedQuantity(item.quantity()));
        } else {
            items.put(code, item);
        }
    }

    public synchronized void addItem(String itemCode, String itemName, BigDecimal unitPrice, int quantity) {
        addItem(new CartItem(itemCode, itemName, unitPrice, quantity));
    }

    public synchronized boolean removeItem(String itemCode) {
        if (itemCode == null) {
            return false;
        }
        return items.remove(itemCode) != null;
    }

    public synchronized void updateQuantity(String itemCode, int newQuantity) {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        if (newQuantity <= 0) {
            removeItem(itemCode);
            return;
        }
        CartItem existing = items.get(itemCode);
        if (existing != null) {
            items.put(itemCode, existing.withQuantity(newQuantity));
        }
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized List<CartItem> getItems() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }

    public synchronized Optional<CartItem> getItem(String itemCode) {
        if (itemCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(itemCode));
    }

    public synchronized boolean containsItem(String itemCode) {
        return itemCode != null && items.containsKey(itemCode);
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized int getDistinctItemCount() {
        return items.size();
    }

    public synchronized int getTotalItemQuantity() {
        int total = 0;
        for (CartItem item : items.values()) {
            total += item.quantity();
        }
        return total;
    }

    public synchronized BigDecimal getSubtotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : items.values()) {
            total = total.add(item.subtotal());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public synchronized CartTotals computeTotals(BigDecimal cashTendered) {
        return CartTotals.calculate(getSubtotal(), cashTendered);
    }

    public synchronized CartTotals computeTotals(BigDecimal taxRate, BigDecimal cashTendered) {
        return CartTotals.calculate(getSubtotal(), taxRate, cashTendered);
    }
}
