package com.supermarket.service;

import com.supermarket.domain.Cart;
import com.supermarket.domain.CartItem;
import com.supermarket.domain.CartTotals;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.PaymentShortfallException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingServiceTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        billingService = new BillingService(db, itemRepo, salesRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Adding items to cart aggregates quantities and recalculates live subtotal")
    void testAddItemsAndAggregateQuantity() {
        Cart cart = billingService.createCart();
        assertThat(cart.isEmpty()).isTrue();

        // DAIRY-001 selling price is ₹156.87, initial stock 50
        CartItem item1 = billingService.addItemToCart(cart, "DAIRY-001", 2);
        assertThat(item1.quantity()).isEqualTo(2);
        assertThat(item1.subtotal()).isEqualByComparingTo(BigDecimal.valueOf(313.74));
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(313.74));

        // Add 3 more of same item -> consolidated into 5
        CartItem item2 = billingService.addItemToCart(cart, "DAIRY-001", 3);
        assertThat(item2.quantity()).isEqualTo(5);
        assertThat(item2.subtotal()).isEqualByComparingTo(BigDecimal.valueOf(784.35));
        assertThat(cart.getDistinctItemCount()).isEqualTo(1);
        assertThat(cart.getTotalItemQuantity()).isEqualTo(5);
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(784.35));
    }

    @Test
    @DisplayName("Removing item from cart updates cart count and subtotal")
    void testRemoveItemFromCart() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "DAIRY-001", 2);
        billingService.addItemToCart(cart, "BAKE-001", 1);

        assertThat(cart.getDistinctItemCount()).isEqualTo(2);
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(500.49));

        billingService.removeItemFromCart(cart, "DAIRY-001");
        assertThat(cart.getDistinctItemCount()).isEqualTo(1);
        assertThat(cart.getItem("DAIRY-001")).isEmpty();
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(186.75));
    }

    @Test
    @DisplayName("Updating cart item quantity recalculates subtotals or removes when zero")
    void testUpdateCartItemQuantity() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "BEV-001", 5);

        billingService.updateCartItemQuantity(cart, "BEV-001", 2);
        assertThat(cart.getItem("BEV-001").orElseThrow().quantity()).isEqualTo(2);
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(214.14));

        billingService.updateCartItemQuantity(cart, "BEV-001", 0);
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Clear cart empties all items and resets totals")
    void testClearCart() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "BEV-001", 2);
        billingService.addItemToCart(cart, "PROD-001", 3);

        assertThat(cart.isEmpty()).isFalse();
        billingService.clearCart(cart);

        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getDistinctItemCount()).isEqualTo(0);
        assertThat(cart.getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Adding non-existent item throws ItemNotFoundException")
    void testAddNonExistentItemThrows() {
        Cart cart = billingService.createCart();
        assertThatThrownBy(() -> billingService.addItemToCart(cart, "UNKNOWN-999", 1))
                .isInstanceOf(ItemNotFoundException.class)
                .hasMessageContaining("UNKNOWN-999");
    }

    @Test
    @DisplayName("Adding invalid or non-positive quantity throws ValidationException")
    void testAddInvalidQuantityThrows() {
        Cart cart = billingService.createCart();
        assertThatThrownBy(() -> billingService.addItemToCart(cart, "DAIRY-001", 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> billingService.addItemToCart(cart, "DAIRY-001", -3))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> billingService.addItemToCart(cart, "   ", 1))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Adding item exceeding available stock throws InsufficientStockException")
    void testAddExceedingStockThrows() {
        Cart cart = billingService.createCart();
        // BEV-002 Dark Roast Coffee has stock 18
        assertThatThrownBy(() -> billingService.addItemToCart(cart, "BEV-002", 19))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Requested: 19")
                .hasMessageContaining("Available: 18");
    }

    @Test
    @DisplayName("Checkout with empty cart throws ValidationException")
    void testCheckoutEmptyCartThrows() {
        Cart cart = billingService.createCart();
        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(50.00), "Cashier-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Checkout with cash tendered less than total payable throws PaymentShortfallException")
    void testCheckoutPaymentShortfallThrows() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "DAIRY-001", 2); // total = 3.78

        assertThatThrownBy(() -> billingService.checkout(cart, BigDecimal.valueOf(3.00), "Cashier-1"))
                .isInstanceOf(PaymentShortfallException.class)
                .hasMessageContaining("Payment tendered (₹3.00) is insufficient");
    }

    @Test
    @DisplayName("Checkout successfully decrements inventory, creates receipt, and clears cart")
    void testCheckoutSuccess() {
        Cart cart = billingService.createCart();
        // DAIRY-001 stock is 50; PROD-001 stock is 65.
        billingService.addItemToCart(cart, "DAIRY-001", 3);
        billingService.addItemToCart(cart, "PROD-001", 2);

        CartTotals totals = billingService.computeTotals(cart, BigDecimal.valueOf(2000.00));
        assertThat(totals.totalPayable()).isEqualByComparingTo(BigDecimal.valueOf(751.15));
        assertThat(totals.changeDue()).isEqualByComparingTo(BigDecimal.valueOf(1248.85));

        Receipt receipt = billingService.checkout(cart, BigDecimal.valueOf(2000.00), "Alice");

        // Verify receipt
        assertThat(receipt).isNotNull();
        assertThat(receipt.serialNumber()).startsWith("SAS-");
        assertThat(receipt.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(751.15));
        assertThat(receipt.amountTendered()).isEqualByComparingTo(BigDecimal.valueOf(2000.00));
        assertThat(receipt.changeDue()).isEqualByComparingTo(BigDecimal.valueOf(1248.85));
        assertThat(receipt.cashierName()).isEqualTo("Alice");
        assertThat(receipt.lineItems()).hasSize(2);

        // Verify cart is cleared
        assertThat(cart.isEmpty()).isTrue();

        // Verify inventory decremented in database
        Item updatedMilk = itemRepo.findByCode("DAIRY-001").orElseThrow();
        assertThat(updatedMilk.currentStock()).isEqualTo(47); // 50 - 3

        Item updatedBananas = itemRepo.findByCode("PROD-001").orElseThrow();
        assertThat(updatedBananas.currentStock()).isEqualTo(63); // 65 - 2

        // Verify transaction exists in repository
        Optional<SalesTransaction> tx = salesRepo.findBySerialNumber(receipt.serialNumber());
        assertThat(tx).isPresent();
        assertThat(tx.get().totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(751.15));
    }

    @Test
    @DisplayName("Exact change payment tendered results in zero change due")
    void testCheckoutExactPayment() {
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "BAKE-001", 2);

        Receipt receipt = billingService.checkout(cart, BigDecimal.valueOf(373.50), "Bob");
        assertThat(receipt.changeDue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receipt.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(373.50));
    }
}
