package com.supermarket.service;

import com.supermarket.domain.Cart;
import com.supermarket.domain.CartItem;
import com.supermarket.domain.CartTotals;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.PaymentShortfallException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core business service for POS billing, cart management, totals computation,
 * and atomic checkout transactions.
 */
public class BillingService {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final AtomicLong SEQUENCE_COUNTER = new AtomicLong(0);

    private final DatabaseManager db;
    private final ItemRepository itemRepository;
    private final SalesTransactionRepository salesTransactionRepository;
    private BigDecimal taxRate = BigDecimal.ZERO;

    public BillingService(DatabaseManager db, ItemRepository itemRepository, SalesTransactionRepository salesTransactionRepository) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
        this.itemRepository = Objects.requireNonNull(itemRepository, "ItemRepository cannot be null");
        this.salesTransactionRepository = Objects.requireNonNull(salesTransactionRepository, "SalesTransactionRepository cannot be null");
    }

    public BillingService(DatabaseManager db) {
        this(db, new SqliteItemRepository(db), new SqliteSalesTransactionRepository(db));
    }

    public BillingService() {
        this(DatabaseManager.getInstance());
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = Objects.requireNonNullElse(taxRate, BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Creates a new empty cart.
     */
    public Cart createCart() {
        return new Cart();
    }

    /**
     * Adds an item to the cart, consolidating quantities if the item is already present.
     * Validates that requested quantity is positive and that sufficient stock exists.
     */
    public CartItem addItemToCart(Cart cart, String itemCode, int quantity) {
        Objects.requireNonNull(cart, "Cart cannot be null");
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        if (quantity <= 0) {
            throw new ValidationException("Item quantity must be strictly positive: " + quantity);
        }

        String code = itemCode.trim();
        Item item = itemRepository.findByCode(code)
                .orElseThrow(() -> new ItemNotFoundException(code));

        int existingQty = cart.getItem(code).map(CartItem::quantity).orElse(0);
        int totalRequested = existingQty + quantity;

        if (item.currentStock() < totalRequested) {
            throw new InsufficientStockException(item.code(), item.name(), totalRequested, item.currentStock());
        }

        CartItem lineItem = new CartItem(item.code(), item.name(), item.currentSellingPrice(), quantity);
        cart.addItem(lineItem);
        return cart.getItem(code).orElse(lineItem);
    }

    /**
     * Removes an item completely from the active cart.
     */
    public void removeItemFromCart(Cart cart, String itemCode) {
        Objects.requireNonNull(cart, "Cart cannot be null");
        if (itemCode != null && !itemCode.isBlank()) {
            cart.removeItem(itemCode.trim());
        }
    }

    /**
     * Updates an existing cart item's quantity. If newQuantity is 0, removes the item.
     * Validates that sufficient stock exists for the new quantity.
     */
    public void updateCartItemQuantity(Cart cart, String itemCode, int newQuantity) {
        Objects.requireNonNull(cart, "Cart cannot be null");
        if (itemCode == null || itemCode.isBlank()) {
            throw new ValidationException("Item code cannot be null or blank");
        }
        if (newQuantity < 0) {
            throw new ValidationException("Cart item quantity cannot be negative: " + newQuantity);
        }

        String code = itemCode.trim();
        if (newQuantity == 0) {
            cart.removeItem(code);
            return;
        }

        Item item = itemRepository.findByCode(code)
                .orElseThrow(() -> new ItemNotFoundException(code));

        if (item.currentStock() < newQuantity) {
            throw new InsufficientStockException(item.code(), item.name(), newQuantity, item.currentStock());
        }

        cart.updateQuantity(code, newQuantity);
    }

    /**
     * Clears all items from the active cart.
     */
    public void clearCart(Cart cart) {
        Objects.requireNonNull(cart, "Cart cannot be null");
        cart.clear();
    }

    /**
     * Computes current totals for the active cart with default zero tendered.
     */
    public CartTotals computeTotals(Cart cart) {
        return computeTotals(cart, BigDecimal.ZERO);
    }

    /**
     * Computes current totals for the active cart given an amount tendered.
     */
    public CartTotals computeTotals(Cart cart, BigDecimal cashTendered) {
        Objects.requireNonNull(cart, "Cart cannot be null");
        return cart.computeTotals(taxRate, cashTendered);
    }

    /**
     * Executes an atomic checkout transaction:
     * 1. Validates payment sufficiency (cash tendered >= total payable).
     * 2. Within a single atomic SQLite transaction:
     *    - Validates available stock for ALL items in cart.
     *    - If any item has insufficient stock, throws InsufficientStockException,
     *      causing full rollback (zero stock decremented).
     *    - Atomically decrements stock for all items.
     *    - Generates a collision-free sequential serial number (SAS-YYYYMMDD-XXXXX).
     *    - Records master SalesTransaction.
     *    - Fetches current catalog price snapshots and records immutable line items.
     * 3. Clears cart and returns complete Receipt.
     */
    public Receipt checkout(Cart cart, BigDecimal cashTendered, String cashierName)
            throws InsufficientStockException, PaymentShortfallException {
        Objects.requireNonNull(cart, "Cart cannot be null");
        if (cart.isEmpty()) {
            throw new ValidationException("Cannot checkout: cart is empty");
        }

        String cashier = (cashierName == null || cashierName.isBlank()) ? "Cashier-1" : cashierName.trim();

        Receipt receipt;
        try {
            receipt = db.executeInTransactionWithResult(conn -> {
                BigDecimal subtotal = BigDecimal.ZERO;
                List<ReceiptLineItem> lineItems = new ArrayList<>();

                for (CartItem ci : cart.getItems()) {
                    Optional<Item> itemOpt = itemRepository.findByCode(conn, ci.itemCode());
                    if (itemOpt.isEmpty()) {
                        throw new ItemNotFoundException(ci.itemCode());
                    }
                    Item item = itemOpt.get();
                    if (item.currentStock() < ci.quantity()) {
                        throw new InsufficientStockException(item.code(), item.name(), ci.quantity(), item.currentStock());
                    }

                    BigDecimal costSnapshot = item.costPrice();
                    BigDecimal sellingSnapshot = item.currentSellingPrice();
                    BigDecimal lineSubtotal = sellingSnapshot.multiply(BigDecimal.valueOf(ci.quantity()))
                            .setScale(2, RoundingMode.HALF_UP);
                    subtotal = subtotal.add(lineSubtotal);

                    lineItems.add(new ReceiptLineItem(
                            null,
                            null,
                            ci.itemCode(),
                            item.name(),
                            ci.quantity(),
                            costSnapshot,
                            sellingSnapshot,
                            lineSubtotal
                    ));
                }

                BigDecimal tax = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal totalPayable = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);

                if (cashTendered == null || cashTendered.compareTo(totalPayable) < 0) {
                    throw new PaymentShortfallException(totalPayable, cashTendered != null ? cashTendered : BigDecimal.ZERO);
                }

                for (CartItem ci : cart.getItems()) {
                    boolean decremented = itemRepository.decrementStock(conn, ci.itemCode(), ci.quantity());
                    if (!decremented) {
                        Item item = itemRepository.findByCode(conn, ci.itemCode())
                                .orElseThrow(() -> new ItemNotFoundException(ci.itemCode()));
                        throw new InsufficientStockException(item.code(), item.name(), ci.quantity(), item.currentStock());
                    }
                }

                String serialNumber = generateSerialNumber(conn);
                BigDecimal changeDue = cashTendered.subtract(totalPayable).setScale(2, RoundingMode.HALF_UP);

                SalesTransaction tx = new SalesTransaction(
                        null,
                        serialNumber,
                        totalPayable,
                        cashTendered,
                        changeDue,
                        cashier,
                        LocalDateTime.now()
                );

                salesTransactionRepository.createTransaction(conn, tx, lineItems);

                return new Receipt(tx, lineItems, subtotal, tax);
            });
        } catch (SQLException e) {
            throw new RuntimeException("Database error during checkout transaction", e);
        }

        cart.clear();
        return receipt;
    }

    private synchronized String generateSerialNumber(Connection conn) throws SQLException {
        String today = LocalDate.now().format(BASIC_DATE);
        String prefix = "SAS-" + today + "-";

        long maxSeq = 0;
        String query = "SELECT serial_number FROM sales_transactions WHERE serial_number LIKE ? ORDER BY serial_number DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String lastSerial = rs.getString(1);
                    if (lastSerial != null && lastSerial.length() > prefix.length()) {
                        try {
                            maxSeq = Long.parseLong(lastSerial.substring(prefix.length()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        while (true) {
            long current = SEQUENCE_COUNTER.get();
            long next = Math.max(current, maxSeq) + 1;
            if (SEQUENCE_COUNTER.compareAndSet(current, next)) {
                return String.format("%s%05d", prefix, next);
            }
        }
    }
}
