package com.supermarket.web;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Cart;
import com.supermarket.domain.Item;
import com.supermarket.domain.Operator;
import com.supermarket.domain.PriceHistory;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ItemNotFoundException;
import com.supermarket.exception.PaymentShortfallException;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.IdempotencyRepository;
import com.supermarket.repository.OperatorRepository;
import com.supermarket.repository.SqliteIdempotencyRepository;
import com.supermarket.repository.SqliteOperatorRepository;
import com.supermarket.service.AnalyticsService;
import com.supermarket.service.BillingService;
import com.supermarket.service.InventoryService;
import com.supermarket.service.OtpDeliveryService;
import com.supermarket.service.PriceService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebServer {
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());

    public enum Permission {
        VIEW_CATALOG,
        CHECKOUT,
        RESTOCK,
        UPDATE_PRICE,
        VIEW_ANALYTICS
    }

    public enum Role {
        CASHIER(Set.of(Permission.VIEW_CATALOG, Permission.CHECKOUT), "Cashier 01"),
        STAFF(Set.of(Permission.VIEW_CATALOG, Permission.RESTOCK), "Inventory Staff"),
        MANAGER(Set.of(Permission.VIEW_CATALOG, Permission.CHECKOUT, Permission.RESTOCK, Permission.UPDATE_PRICE, Permission.VIEW_ANALYTICS), "Store Manager");

        private final Set<Permission> permissions;
        private final String displayName;

        Role(Set<Permission> permissions, String displayName) {
            this.permissions = permissions;
            this.displayName = displayName;
        }

        public boolean hasPermission(Permission permission) {
            return permissions.contains(permission);
        }

        public String displayName() {
            return displayName;
        }

        public static Role fromString(String roleName) {
            if (roleName == null || roleName.isBlank()) {
                throw new IllegalArgumentException("Role name cannot be null or blank");
            }
            String lower = roleName.trim().toLowerCase();
            if (lower.equals("manager") || lower.equals("admin") || lower.contains("store manager")) return MANAGER;
            if (lower.equals("staff") || lower.equals("stock") || lower.contains("inventory staff") || lower.contains("inventory")) return STAFF;
            if (lower.equals("cashier") || lower.contains("cashier")) return CASHIER;
            throw new IllegalArgumentException("Unknown operator role: " + roleName);
        }
    }

    public record UserProfile(
            String username,
            String passwordHash,
            String name,
            Role role,
            String desk,
            String phone,
            String maskedPhone,
            String initials
    ) {
        public static UserProfile fromOperator(Operator op) {
            return new UserProfile(
                    op.username(),
                    op.passwordHash(),
                    op.name(),
                    Role.fromString(op.role()),
                    op.desk(),
                    op.phone(),
                    op.maskedPhone(),
                    op.initials()
            );
        }
    }

    public record Session(
            String token,
            UserProfile user,
            long expiresAt
    ) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public record TwoFactorChallenge(
            String token,
            String username,
            String code,
            long expiresAt,
            AtomicInteger attemptsRemaining,
            UserProfile user,
            long lastSentAt,
            int resendCount,
            AtomicBoolean consumed
    ) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public record CaptchaChallenge(
            String token,
            String code,
            long expiresAt
    ) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public static final int HASH_ITERATIONS = 600000;
    private static final int LEGACY_HASH_ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String CSP_HEADER = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; style-src-elem 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: https:; connect-src 'self' https:;";

    private static final Map<String, List<Long>> FAILED_LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<String, List<Long>> CAPTCHA_REQUEST_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_CAPTCHA_REQUESTS_PER_MIN = 40;
    private static final long RATE_LIMIT_WINDOW_MS = 60 * 1000L;
    private static volatile String bootstrapSetupToken = null;
    private final Object setupLock = new Object();

    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return "pbkdf2-sha256$" + HASH_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    public static boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            if (storedHash.startsWith("pbkdf2-sha256$")) {
                String[] parts = storedHash.split("\\$");
                if (parts.length != 4) return false;
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expectedHash = Base64.getDecoder().decode(parts[3]);

                KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] actualHash = factory.generateSecret(spec).getEncoded();

                return MessageDigest.isEqual(expectedHash, actualHash);
            } else if (storedHash.contains(":")) {
                String[] parts = storedHash.split(":");
                if (parts.length != 2) return false;
                byte[] salt = Base64.getDecoder().decode(parts[0]);
                byte[] expectedHash = Base64.getDecoder().decode(parts[1]);

                KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, LEGACY_HASH_ITERATIONS, KEY_LENGTH);
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] actualHash = factory.generateSecret(spec).getEncoded();

                return MessageDigest.isEqual(expectedHash, actualHash);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean needsPasswordRehash(String storedHash) {
        if (storedHash == null || storedHash.isBlank()) return false;
        if (storedHash.contains(":") && !storedHash.startsWith("pbkdf2-sha256$")) return true;
        if (storedHash.startsWith("pbkdf2-sha256$")) {
            String[] parts = storedHash.split("\\$");
            if (parts.length == 4) {
                try {
                    return Integer.parseInt(parts[1]) < HASH_ITERATIONS;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    public static boolean isStrongPassword(String password) {
        if (password == null || password.length() < 10) return false;
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    private final Map<String, CaptchaChallenge> activeCaptchas = new ConcurrentHashMap<>();
    private final Map<String, TwoFactorChallenge> active2fa = new ConcurrentHashMap<>();
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    private final DatabaseManager database;
    private final InventoryService inventoryService;
    private final BillingService billingService;
    private final PriceService priceService;
    private final AnalyticsService analyticsService;
    private final OperatorRepository operatorRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final OtpDeliveryService otpDeliveryService;

    private static final class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    public WebServer(DatabaseManager database) {
        this(database, new OtpDeliveryService.LoggingOtpDeliveryService());
    }

    public WebServer(DatabaseManager database, OtpDeliveryService otpDeliveryService) {
        this.database = Objects.requireNonNull(database, "DatabaseManager cannot be null");
        this.inventoryService = new InventoryService(database);
        this.billingService = new BillingService(database);
        this.billingService.setTaxRate(new BigDecimal("0.05"));
        this.priceService = new PriceService(database);
        this.analyticsService = new AnalyticsService(database);
        this.operatorRepository = new SqliteOperatorRepository(database);
        this.idempotencyRepository = new SqliteIdempotencyRepository(database);
        this.otpDeliveryService = Objects.requireNonNullElseGet(otpDeliveryService, OtpDeliveryService.LoggingOtpDeliveryService::new);
        bootstrapDefaultOperatorsIfEmpty();
    }

    private void bootstrapDefaultOperatorsIfEmpty() {
        boolean seedDefaults = Boolean.parseBoolean(System.getProperty("supermarket.seed.defaults", "false"));
        if (seedDefaults && operatorRepository.count() == 0) {
            operatorRepository.save(new Operator(null, "cashier01", hashPassword("pos123"), "Priya Sharma", "Cashier", "Front Register", "+91 98765 43210", "+91 98*** **210", "PS"));
            operatorRepository.save(new Operator(null, "manager", hashPassword("admin123"), "Rajesh Kumar", "Store Manager", "Operations & Pricing", "+91 99887 76655", "+91 99*** **655", "RK"));
            operatorRepository.save(new Operator(null, "staff", hashPassword("staff123"), "Amit Verma", "Inventory Staff", "Stock & Warehouse", "+91 97112 23344", "+91 97*** **344", "AV"));
        } else if (operatorRepository.count() == 0 && bootstrapSetupToken == null) {
            String propToken = System.getProperty("supermarket.bootstrap.token");
            bootstrapSetupToken = (propToken != null && !propToken.isBlank()) ? propToken.trim() : UUID.randomUUID().toString().replace("-", "");
            System.out.println("================================================================================");
            System.out.println("INITIAL SETUP REQUIRED: No operator accounts exist in database.");
            System.out.println("One-time Administrator Setup Token: " + bootstrapSetupToken);
            System.out.println("Use this token to create the initial administrator at /api/auth/setup-admin");
            System.out.println("================================================================================");
        }
    }

    public static void launch(int port) throws IOException, InterruptedException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        DatabaseManager database = DatabaseManager.getInstance();
        MigrationRunner.migrate(database);
        WebServer application = new WebServer(database);
        server.createContext("/api/", application::handleApi);
        server.createContext("/", application::handleAsset);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Supermarket web app is running at http://127.0.0.1:" + port);
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("http://127.0.0.1:" + port));
            }
        } catch (Throwable ignored) {
        }
        new CountDownLatch(1).await();
    }

    void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (path.equals("/api/health") && method.equals("GET")) {
                sendJson(exchange, 200, object("status", "ok"));
            } else if (path.equals("/api/auth/setup-status") && method.equals("GET")) {
                sendJson(exchange, 200, object("setupRequired", operatorRepository.count() == 0));
            } else if (path.equals("/api/auth/setup-admin") && method.equals("POST")) {
                handleSetupAdmin(exchange);
            } else if (path.equals("/api/auth/captcha") && method.equals("GET")) {
                handleCaptcha(exchange);
            } else if (path.equals("/api/auth/login-step1") && method.equals("POST")) {
                handleLoginStep1(exchange);
            } else if (path.equals("/api/auth/verify-2fa") && method.equals("POST")) {
                handleVerify2fa(exchange);
            } else if (path.equals("/api/auth/resend-2fa") && method.equals("POST")) {
                handleResend2fa(exchange);
            } else if (path.equals("/api/auth/session") && method.equals("GET")) {
                handleSession(exchange);
            } else if (path.equals("/api/auth/logout") && method.equals("POST")) {
                handleLogout(exchange);
            } else if (path.equals("/api/items") && method.equals("GET")) {
                Session session = requireAuthorizedSession(exchange, Permission.VIEW_CATALOG);
                if (session == null) return;
                boolean includeCost = session.user().role().hasPermission(Permission.VIEW_ANALYTICS)
                        || session.user().role().hasPermission(Permission.UPDATE_PRICE);
                List<Map<String, Object>> items = inventoryService.getAllItems().stream()
                        .map(item -> itemJson(item, includeCost))
                        .toList();
                sendJson(exchange, 200, items);
            } else if (path.equals("/api/analytics") && method.equals("GET")) {
                Session session = requireAuthorizedSession(exchange, Permission.VIEW_ANALYTICS);
                if (session == null) return;
                Map<String, List<String>> query = parsePairs(exchange.getRequestURI().getRawQuery());
                LocalDate today = LocalDate.now();
                LocalDate from = LocalDate.parse(first(query, "from", today.toString()));
                LocalDate to = LocalDate.parse(first(query, "to", today.toString()));
                sendJson(exchange, 200, reportJson(analyticsService.generateReport(from, to)));
            } else if (path.equals("/api/price-history") && method.equals("GET")) {
                Session session = requireAuthorizedSession(exchange, Permission.VIEW_ANALYTICS);
                if (session == null) return;
                List<Map<String, Object>> history = priceService.getAllPriceHistories().stream()
                        .map(WebServer::priceHistoryJson)
                        .toList();
                sendJson(exchange, 200, history);
            } else if (path.equals("/api/checkout") && method.equals("POST")) {
                Session session = requireAuthorizedSession(exchange, Permission.CHECKOUT);
                if (session == null) return;
                checkout(exchange, session);
            } else if (path.equals("/api/restock") && method.equals("POST")) {
                Session session = requireAuthorizedSession(exchange, Permission.RESTOCK);
                if (session == null) return;
                restock(exchange, session);
            } else if (path.equals("/api/price") && method.equals("POST")) {
                Session session = requireAuthorizedSession(exchange, Permission.UPDATE_PRICE);
                if (session == null) return;
                updatePrice(exchange, session);
            } else if (path.equals("/api/admin/backup") && method.equals("GET")) {
                Session session = requireAuthorizedSession(exchange, Permission.VIEW_ANALYTICS);
                if (session == null) return;
                handleBackup(exchange, session);
            } else if (path.startsWith("/api/")) {
                sendJson(exchange, 404, object("error", "API route not found"));
            }
        } catch (PayloadTooLargeException large) {
            sendJson(exchange, 413, object("error", large.getMessage()));
        } catch (InsufficientStockException stock) {
            sendJson(exchange, 409, object("error", stock.getMessage()));
        } catch (ItemNotFoundException notFound) {
            sendJson(exchange, 404, object("error", notFound.getMessage()));
        } catch (ValidationException | PaymentShortfallException | IllegalArgumentException | DateTimeParseException badReq) {
            sendJson(exchange, 400, object("error", message(badReq)));
        } catch (Exception err) {
            LOGGER.log(Level.SEVERE, "Internal server error on route " + path, err);
            sendJson(exchange, 500, object("error", "An internal error occurred. Please try again later."));
        }
    }

    private void handleSetupAdmin(HttpExchange exchange) throws IOException {
        synchronized (setupLock) {
            if (operatorRepository.count() > 0) {
                sendJson(exchange, 403, object("error", "First-admin setup has already been completed."));
                return;
            }
            Map<String, List<String>> form = readForm(exchange);
            String setupToken = first(form, "setupToken", exchange.getRequestHeaders().getFirst("X-Setup-Token"));
            if (bootstrapSetupToken != null && !bootstrapSetupToken.equals(setupToken)) {
                sendJson(exchange, 401, object("error", "Invalid or missing administrator setup token."));
                return;
            }

            String username = required(form, "username").trim().toLowerCase();
            String password = required(form, "password");
            String name = first(form, "name", "Store Administrator").trim();
            String phone = first(form, "phone", "+91 99999 00000").trim();

            if (!isStrongPassword(password)) {
                sendJson(exchange, 400, object("error", "Password must be at least 10 characters and include uppercase, lowercase, numbers, and symbols."));
                return;
            }

            StringBuilder initials = new StringBuilder();
            String[] words = name.split("\\s+");
            for (String w : words) {
                if (!w.isBlank()) initials.append(Character.toUpperCase(w.charAt(0)));
                if (initials.length() >= 2) break;
            }
            if (initials.isEmpty()) initials.append("AD");

            String maskedPhone = phone.length() > 6 ? phone.substring(0, 3) + " *** **" + phone.substring(phone.length() - 3) : phone;
            operatorRepository.save(new Operator(null, username, hashPassword(password), name, "Store Manager", "Store Management", phone, maskedPhone, initials.toString()));
            bootstrapSetupToken = null;
            sendJson(exchange, 200, object("success", true, "message", "Initial administrator account configured successfully."));
        }
    }

    private Session getAuthenticatedSession(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        Session session = activeSessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            activeSessions.remove(token);
            return null;
        }
        return session;
    }

    private Session requireAuthorizedSession(HttpExchange exchange, Permission requiredPermission) throws IOException {
        Session session = getAuthenticatedSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, object("error", "Unauthorized: Valid session required"));
            return null;
        }
        if (requiredPermission != null && !session.user().role().hasPermission(requiredPermission)) {
            sendJson(exchange, 403, object("error", "Forbidden: Insufficient privileges"));
            return null;
        }
        return session;
    }

    private void pruneExpiredEntries() {
        activeCaptchas.entrySet().removeIf(e -> e.getValue().isExpired());
        active2fa.entrySet().removeIf(e -> e.getValue().isExpired());
        activeSessions.entrySet().removeIf(e -> e.getValue().isExpired());
        try {
            idempotencyRepository.purgeOlderThanDays(7);
        } catch (Exception ignored) {}

        pruneBoundedMap(activeCaptchas, 1000);
        pruneBoundedMap(active2fa, 1000);
        pruneBoundedMap(activeSessions, 10000);
    }

    private static <K, V> void pruneBoundedMap(Map<K, V> map, int maxSize) {
        if (map.size() > maxSize) {
            var it = map.keySet().iterator();
            int toRemove = map.size() - maxSize;
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }
    }

    private static boolean isRateLimited(String key) {
        long now = System.currentTimeMillis();
        List<Long> attempts = FAILED_LOGIN_ATTEMPTS.get(key);
        if (attempts == null) return false;
        synchronized (attempts) {
            attempts.removeIf(time -> now - time > RATE_LIMIT_WINDOW_MS);
            return attempts.size() >= MAX_FAILED_ATTEMPTS;
        }
    }

    private static void recordFailedAttempt(String key) {
        long now = System.currentTimeMillis();
        FAILED_LOGIN_ATTEMPTS.compute(key, (k, list) -> {
            List<Long> attempts = list == null ? new ArrayList<>() : list;
            synchronized (attempts) {
                attempts.removeIf(time -> now - time > RATE_LIMIT_WINDOW_MS);
                attempts.add(now);
            }
            return attempts;
        });
        pruneRateLimits(FAILED_LOGIN_ATTEMPTS, 5000);
    }

    private static void pruneRateLimits(Map<String, List<Long>> map, int maxSize) {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(time -> now - time > RATE_LIMIT_WINDOW_MS);
                return entry.getValue().isEmpty();
            }
        });
        pruneBoundedMap(map, maxSize);
    }

    private static void resetFailedAttempts(String key) {
        FAILED_LOGIN_ATTEMPTS.remove(key);
    }

    private static boolean isCaptchaRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        List<Long> timestamps = CAPTCHA_REQUEST_TIMESTAMPS.computeIfAbsent(clientIp, k -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.removeIf(time -> now - time > RATE_LIMIT_WINDOW_MS);
            if (timestamps.size() >= MAX_CAPTCHA_REQUESTS_PER_MIN) {
                return true;
            }
            timestamps.add(now);
        }
        pruneRateLimits(CAPTCHA_REQUEST_TIMESTAMPS, 5000);
        return false;
    }

    private static String computePayloadFingerprint(String username, String amountTendered, List<String> codes, List<String> quantities) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(username).append(":").append(amountTendered).append(":");
            List<String> paired = new ArrayList<>();
            for (int i = 0; i < codes.size(); i++) {
                paired.add(codes.get(i) + "=" + (i < quantities.size() ? quantities.get(i) : ""));
            }
            paired.sort(String::compareTo);
            for (String p : paired) {
                sb.append(p).append(";");
            }
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return username + ":" + amountTendered + ":" + codes.size();
        }
    }

    private void checkout(HttpExchange exchange, Session session) throws IOException {
        pruneExpiredEntries();
        Map<String, List<String>> form = readForm(exchange);
        String idempotencyKey = first(form, "idempotencyKey", exchange.getRequestHeaders().getFirst("Idempotency-Key"));

        List<String> codes = form.getOrDefault("itemCode", List.of());
        List<String> quantities = form.getOrDefault("quantity", List.of());
        if (codes.isEmpty() || codes.size() != quantities.size()) {
            throw new IllegalArgumentException("Add at least one item to the cart");
        }
        String amountTendered = required(form, "amountTendered");

        String payloadFingerprint = computePayloadFingerprint(session.user().username(), amountTendered, codes, quantities);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRepository.Record> cached = idempotencyRepository.findByKey(idempotencyKey);
            if (cached.isPresent()) {
                IdempotencyRepository.Record rec = cached.get();
                if (!rec.username().equals(session.user().username()) || !rec.payloadFingerprint().equals(payloadFingerprint)) {
                    sendJson(exchange, 409, object("error", "Idempotency key collision with different request payload."));
                    return;
                }
                sendText(exchange, 200, rec.receiptJson(), "application/json; charset=utf-8");
                return;
            }
        }

        Cart cart = billingService.createCart();
        for (int index = 0; index < codes.size(); index++) {
            billingService.addItemToCart(cart, codes.get(index), Integer.parseInt(quantities.get(index)));
        }
        Receipt receipt = billingService.checkout(
                cart,
                new BigDecimal(amountTendered),
                session.user().name()
        );

        String receiptJsonStr = toJson(receiptJson(receipt));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                idempotencyRepository.save(idempotencyKey, session.user().username(), payloadFingerprint, receiptJsonStr);
            } catch (Exception ex) {
                Optional<IdempotencyRepository.Record> existing = idempotencyRepository.findByKey(idempotencyKey);
                if (existing.isPresent()) {
                    sendText(exchange, 200, existing.get().receiptJson(), "application/json; charset=utf-8");
                    return;
                }
            }
        }

        sendText(exchange, 200, receiptJsonStr, "application/json; charset=utf-8");
    }

    private void restock(HttpExchange exchange, Session session) throws IOException {
        Map<String, List<String>> form = readForm(exchange);
        String code = required(form, "itemCode");
        int quantity = Integer.parseInt(required(form, "quantity"));
        inventoryService.restockItem(
                code,
                quantity,
                first(form, "notes", ""),
                session.user().name()
        );
        boolean includeCost = session.user().role().hasPermission(Permission.VIEW_ANALYTICS)
                || session.user().role().hasPermission(Permission.UPDATE_PRICE);
        sendJson(exchange, 200, itemJson(inventoryService.getItemByCode(code), includeCost));
    }

    private void updatePrice(HttpExchange exchange, Session session) throws IOException {
        Map<String, List<String>> form = readForm(exchange);
        String code = required(form, "itemCode");
        BigDecimal price = new BigDecimal(required(form, "price"));
        priceService.updateSellingPrice(code, price, session.user().name());
        sendJson(exchange, 200, object(
                "item", itemJson(inventoryService.getItemByCode(code)),
                "belowCost", priceService.isSellingPriceBelowCost(code, price)
        ));
    }

    private static String generateCaptchaSvg(String code) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' width='160' height='46' viewBox='0 0 160 46'>");
        svg.append("<rect width='160' height='46' fill='#f4f5f7' rx='6'/>");
        svg.append("<line x1='10' y1='12' x2='150' y2='34' stroke='#d1d5db' stroke-width='1.5'/>");
        svg.append("<line x1='15' y1='36' x2='145' y2='10' stroke='#d1d5db' stroke-width='1.5'/>");
        int x = 18;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            int y = 28 + (i % 2 == 0 ? -2 : 3);
            svg.append("<text x='").append(x).append("' y='").append(y)
               .append("' font-family='monospace' font-size='22' font-weight='bold' fill='#1f2937'>")
               .append(ch).append("</text>");
            x += 26;
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private void handleCaptcha(HttpExchange exchange) throws IOException {
        pruneExpiredEntries();
        String clientIp = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress().getHostAddress() : "client";
        if (isCaptchaRateLimited(clientIp)) {
            sendJson(exchange, 429, object("error", "Too many captcha requests. Please wait a minute before requesting another."));
            return;
        }

        String token = UUID.randomUUID().toString();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        String code = sb.toString();
        long expiresAt = System.currentTimeMillis() + (3 * 60 * 1000L);
        activeCaptchas.put(token, new CaptchaChallenge(token, code, expiresAt));

        String svg = generateCaptchaSvg(code);
        sendJson(exchange, 200, object(
                "token", token,
                "svg", svg
        ));
    }

    private void handleLoginStep1(HttpExchange exchange) throws IOException {
        pruneExpiredEntries();
        String clientIp = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress().getHostAddress() : "client";

        Map<String, List<String>> form = readForm(exchange);
        String username = required(form, "username").trim().toLowerCase();
        String password = required(form, "password");
        String captchaToken = first(form, "captchaToken", "");
        String captchaAnswer = first(form, "captchaAnswer", "").trim();

        if (isRateLimited("ip:" + clientIp) || isRateLimited("user:" + username)) {
            sendJson(exchange, 429, object("error", "Too many failed attempts. Please wait 1 minute before trying again."));
            return;
        }

        // Enforce Captcha challenge: single-use, matching, unexpired
        CaptchaChallenge captcha = activeCaptchas.remove(captchaToken);
        if (captcha == null || captcha.isExpired() || !captcha.code().equalsIgnoreCase(captchaAnswer)) {
            recordFailedAttempt("ip:" + clientIp);
            recordFailedAttempt("user:" + username);
            sendJson(exchange, 400, object("error", "Invalid or expired Captcha code. Please try again."));
            return;
        }

        // Validate user credentials against persistent database record via PBKDF2
        Operator op = operatorRepository.findByUsername(username).orElse(null);
        if (op == null || !verifyPassword(password, op.passwordHash())) {
            recordFailedAttempt("ip:" + clientIp);
            recordFailedAttempt("user:" + username);
            sendJson(exchange, 401, object("error", "Invalid operator ID or password."));
            return;
        }

        if (needsPasswordRehash(op.passwordHash())) {
            try {
                operatorRepository.updatePasswordHash(op.id(), hashPassword(password));
                LOGGER.info("Upgraded PBKDF2 iterations for operator " + op.username() + " to " + HASH_ITERATIONS);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to upgrade password hash for " + op.username(), ex);
            }
        }

        resetFailedAttempts("ip:" + clientIp);
        resetFailedAttempts("user:" + username);
        UserProfile user = UserProfile.fromOperator(op);

        // Generate dynamic single-use 2FA challenge
        String twoFactorToken = UUID.randomUUID().toString();
        String otp = String.format("%06d", RANDOM.nextInt(900000) + 100000);
        long expiresAt = System.currentTimeMillis() + (5 * 60 * 1000L);

        // Deliver OTP through pluggable gateway
        try {
            otpDeliveryService.deliverOtp(user.username(), user.phone(), otp);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to deliver OTP to " + user.phone(), ex);
            sendJson(exchange, 503, object("error", "Verification service temporarily unavailable. Please try again later."));
            return;
        }

        active2fa.put(twoFactorToken, new TwoFactorChallenge(
                twoFactorToken, username, otp, expiresAt, new AtomicInteger(3), user, System.currentTimeMillis(), 0, new AtomicBoolean(false)
        ));

        sendJson(exchange, 200, object(
                "success", true,
                "twoFactorRequired", true,
                "twoFactorToken", twoFactorToken,
                "otp", otp,
                "username", user.username(),
                "name", user.name(),
                "role", user.role().displayName(),
                "desk", user.desk(),
                "maskedPhone", user.maskedPhone(),
                "message", "Two-step verification code: " + otp
        ));
    }

    private void handleVerify2fa(HttpExchange exchange) throws IOException {
        pruneExpiredEntries();
        Map<String, List<String>> form = readForm(exchange);
        String twoFactorToken = required(form, "twoFactorToken");
        String code = required(form, "code").trim().replaceAll("\\s+", "");

        TwoFactorChallenge challenge = active2fa.get(twoFactorToken);
        if (challenge == null) {
            sendJson(exchange, 400, object("error", "2FA session expired. Please sign in again."));
            return;
        }
        if (challenge.isExpired()) {
            active2fa.remove(twoFactorToken);
            sendJson(exchange, 400, object("error", "Verification code expired. Please request a new one."));
            return;
        }
        if (challenge.attemptsRemaining().decrementAndGet() < 0) {
            active2fa.remove(twoFactorToken);
            sendJson(exchange, 400, object("error", "Too many failed attempts. Verification session invalidated."));
            return;
        }
        if (!challenge.code().equals(code)) {
            sendJson(exchange, 400, object("error", "Incorrect verification code. Please check and re-enter."));
            return;
        }

        // Atomically consume the challenge so concurrent requests cannot both succeed
        if (!challenge.consumed().compareAndSet(false, true)) {
            sendJson(exchange, 409, object("error", "Verification code already consumed."));
            return;
        }

        // Successfully verified: challenge is single-use and consumed immediately
        active2fa.remove(twoFactorToken);
        String sessionToken = UUID.randomUUID().toString();
        long sessionExpiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        activeSessions.put(sessionToken, new Session(sessionToken, challenge.user(), sessionExpiresAt));

        sendJson(exchange, 200, object(
                "success", true,
                "sessionToken", sessionToken,
                "user", object(
                        "username", challenge.user().username(),
                        "name", challenge.user().name(),
                        "role", challenge.user().role().displayName(),
                        "desk", challenge.user().desk(),
                        "phone", challenge.user().phone(),
                        "maskedPhone", challenge.user().maskedPhone(),
                        "initials", challenge.user().initials()
                )
        ));
    }

    private void handleResend2fa(HttpExchange exchange) throws IOException {
        pruneExpiredEntries();
        Map<String, List<String>> form = readForm(exchange);
        String twoFactorToken = required(form, "twoFactorToken");
        TwoFactorChallenge challenge = active2fa.get(twoFactorToken);
        if (challenge == null || challenge.isExpired() || challenge.attemptsRemaining().get() <= 0) {
            if (challenge != null) active2fa.remove(twoFactorToken);
            sendJson(exchange, 400, object("error", "Session expired or locked. Please sign in again."));
            return;
        }

        long now = System.currentTimeMillis();
        if (now - challenge.lastSentAt() < 30_000L) {
            sendJson(exchange, 429, object("error", "Please wait at least 30 seconds before requesting a new code."));
            return;
        }

        if (challenge.resendCount() >= 3) {
            sendJson(exchange, 429, object("error", "Maximum resend limit reached for this verification session."));
            return;
        }

        String newOtp = String.format("%06d", RANDOM.nextInt(900000) + 100000);
        long expiresAt = now + (5 * 60 * 1000L);

        try {
            otpDeliveryService.deliverOtp(challenge.username(), challenge.user().phone(), newOtp);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to resend verification code to " + challenge.user().phone(), ex);
            sendJson(exchange, 503, object("error", "Verification service temporarily unavailable. Please try again later."));
            return;
        }

        active2fa.put(twoFactorToken, new TwoFactorChallenge(
                twoFactorToken,
                challenge.username(),
                newOtp,
                expiresAt,
                new AtomicInteger(3),
                challenge.user(),
                now,
                challenge.resendCount() + 1,
                new AtomicBoolean(false)
        ));

        sendJson(exchange, 200, object(
                "success", true,
                "otp", newOtp,
                "message", "New verification code: " + newOtp
        ));
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        Session session = getAuthenticatedSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, object("authenticated", false));
            return;
        }
        UserProfile user = session.user();
        sendJson(exchange, 200, object(
                "authenticated", true,
                "user", object(
                        "username", user.username(),
                        "name", user.name(),
                        "role", user.role().displayName(),
                        "desk", user.desk(),
                        "maskedPhone", user.maskedPhone(),
                        "initials", user.initials()
                )
        ));
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7).trim() : "";
        if (!token.isBlank()) {
            activeSessions.remove(token);
        }
        sendJson(exchange, 200, object("success", true, "message", "Signed out successfully."));
    }

    private void handleBackup(HttpExchange exchange, Session session) throws IOException {
        if (!session.user().role().hasPermission(Permission.VIEW_ANALYTICS)) {
            sendJson(exchange, 403, object("error", "Forbidden: Manager privilege required for database backup"));
            return;
        }
        File tempBackup = File.createTempFile("supermarket_backup_", ".db");
        tempBackup.deleteOnExit();
        try {
            database.getWriteLock().lock();
            try (Connection conn = database.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + tempBackup.getAbsolutePath().replace('\\', '/') + "'");
            } finally {
                database.getWriteLock().unlock();
            }
            byte[] backupBytes = java.nio.file.Files.readAllBytes(tempBackup.toPath());
            String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now());
            String filename = "supermarket-backup-" + timestamp + ".db";
            exchange.getResponseHeaders().set("Content-Type", "application/x-sqlite3");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            exchange.sendResponseHeaders(200, backupBytes.length);
            exchange.getResponseBody().write(backupBytes);
            exchange.close();
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to create database backup", ex);
            sendJson(exchange, 500, object("error", "Failed to generate database backup: " + ex.getMessage()));
        } finally {
            tempBackup.delete();
        }
    }

    void handleAsset(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath.contains("..") || rawPath.contains("\\")) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }

        if (rawPath.equals("/robots.txt")) {
            String robots = "User-agent: *\nDisallow: /api/\nAllow: /\n";
            sendText(exchange, 200, robots, "text/plain; charset=utf-8");
            return;
        }

        if (rawPath.equals("/sitemap.xml")) {
            String sitemap = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n  <url><loc>http://127.0.0.1:8080/</loc><changefreq>weekly</changefreq><priority>1.0</priority></url>\n</urlset>\n";
            sendText(exchange, 200, sitemap, "application/xml; charset=utf-8");
            return;
        }

        if (rawPath.equals("/favicon.ico") || rawPath.equals("/favicon.svg")) {
            String faviconSvg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'><rect width='32' height='32' rx='6' fill='#0e3a2f'/><path d='M6 8h4l3 13h12l2.5-9H10.5' stroke='#f1c768' stroke-width='2' fill='none' stroke-linecap='round' stroke-linejoin='round'/><circle cx='14' cy='24' r='2' fill='#f1c768'/><circle cx='23' cy='24' r='2' fill='#f1c768'/></svg>";
            byte[] iconBytes = faviconSvg.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy", CSP_HEADER);
            exchange.sendResponseHeaders(200, iconBytes.length);
            exchange.getResponseBody().write(iconBytes);
            exchange.close();
            return;
        }

        if (rawPath.equals("/") || rawPath.equals("/login") || rawPath.equals("/login.html") || rawPath.equals("/auth")) {
            rawPath = "/index.html";
        }

        byte[] bytes = null;
        try (InputStream content = WebServer.class.getResourceAsStream("/web" + rawPath)) {
            if (content != null) {
                bytes = content.readAllBytes();
            }
        }
        if (bytes == null) {
            File baseDir = new File("src/main/resources/web");
            File diskFile = new File(baseDir, rawPath).getCanonicalFile();
            if (diskFile.getPath().startsWith(baseDir.getCanonicalPath()) && diskFile.exists() && diskFile.isFile()) {
                bytes = java.nio.file.Files.readAllBytes(diskFile.toPath());
            }
        }

        if (bytes == null) {
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept != null && accept.contains("text/html")) {
                String html404 = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>404 - Page Not Found | Marketline POS</title>
                        <link rel="stylesheet" href="/app.css">
                        <link rel="icon" type="image/svg+xml" href="/favicon.svg">
                    </head>
                    <body style="display:flex;align-items:center;justify-content:center;min-height:100vh;background:#f8fafc;font-family:sans-serif;margin:0;padding:20px;">
                        <div style="background:#fff;border-radius:16px;padding:40px;max-width:480px;text-align:center;box-shadow:0 10px 25px rgba(0,0,0,0.05);border:1px solid #e2e8f0;">
                            <h1 style="font-size:4rem;margin:0;color:#0e3a2f;">404</h1>
                            <h2 style="font-size:1.5rem;color:#1e293b;margin:12px 0 8px;">Resource Not Found</h2>
                            <p style="color:#64748b;margin-bottom:24px;">The page or resource you requested does not exist in Marketline POS.</p>
                            <a href="/" style="display:inline-block;padding:12px 24px;background:#0e3a2f;color:#fff;text-decoration:none;border-radius:8px;font-weight:600;">Return to Dashboard</a>
                        </div>
                    </body>
                    </html>
                """;
                sendText(exchange, 404, html404, "text/html; charset=utf-8");
                return;
            }
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", contentType(rawPath));
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP_HEADER);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String getLatest2faOtpForTesting(String twoFactorToken) {
        TwoFactorChallenge challenge = active2fa.get(twoFactorToken);
        return challenge != null ? challenge.code() : null;
    }

    String getCaptchaCodeForTesting(String captchaToken) {
        CaptchaChallenge challenge = activeCaptchas.get(captchaToken);
        return challenge != null ? challenge.code() : null;
    }

    void setChallengeLastSentAtForTesting(String twoFactorToken, long timestamp) {
        TwoFactorChallenge challenge = active2fa.get(twoFactorToken);
        if (challenge != null) {
            active2fa.put(twoFactorToken, new TwoFactorChallenge(
                    challenge.token(),
                    challenge.username(),
                    challenge.code(),
                    challenge.expiresAt(),
                    challenge.attemptsRemaining(),
                    challenge.user(),
                    timestamp,
                    challenge.resendCount(),
                    challenge.consumed()
            ));
        }
    }

    private static Map<String, Object> itemJson(Item item) {
        return itemJson(item, false);
    }

    private static Map<String, Object> itemJson(Item item, boolean includeCostPrice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("code", item.code());
        map.put("name", item.name());
        map.put("category", item.category());
        if (includeCostPrice) {
            map.put("costPrice", item.costPrice());
        }
        map.put("price", item.currentSellingPrice());
        map.put("stock", item.currentStock());
        map.put("minStock", item.minStockThreshold());
        map.put("lowStock", item.isLowStock());
        return map;
    }

    private static Map<String, Object> reportJson(AnalyticsReport report) {
        List<Map<String, Object>> items = report.itemStatistics().stream()
                .map(WebServer::statisticJson)
                .toList();
        return object(
                "from", report.startDate(),
                "to", report.endDate(),
                "transactions", report.totalTransactions(),
                "itemsSold", report.totalItemsSold(),
                "revenue", report.totalRevenue(),
                "cost", report.totalCost(),
                "profit", report.totalNetProfit(),
                "margin", report.profitMarginPercentage(),
                "items", items
        );
    }

    private static Map<String, Object> statisticJson(SalesStatistic statistic) {
        return object(
                "code", statistic.itemCode(),
                "name", statistic.itemName(),
                "quantity", statistic.totalQuantitySold(),
                "revenue", statistic.totalRevenue(),
                "cost", statistic.totalCost(),
                "profit", statistic.netProfit()
        );
    }

    private static Map<String, Object> priceHistoryJson(PriceHistory history) {
        return object(
                "itemCode", history.itemCode(),
                "oldPrice", history.oldPrice(),
                "newPrice", history.newPrice(),
                "changedBy", history.changedBy(),
                "timestamp", history.timestamp()
        );
    }

    private static Map<String, Object> receiptJson(Receipt receipt) {
        List<Map<String, Object>> lines = receipt.lineItems().stream()
                .map(WebServer::receiptLineJson)
                .toList();
        return object(
                "serial", receipt.serialNumber(),
                "timestamp", receipt.timestamp(),
                "cashier", receipt.cashierName(),
                "subtotal", receipt.subtotal(),
                "tax", receipt.tax(),
                "total", receipt.totalAmount(),
                "tendered", receipt.amountTendered(),
                "change", receipt.changeDue(),
                "lines", lines
        );
    }

    private static Map<String, Object> receiptLineJson(ReceiptLineItem line) {
        return object(
                "code", line.itemCode(),
                "name", line.itemName(),
                "quantity", line.quantity(),
                "unitPrice", line.unitSellingPriceSnapshot(),
                "subtotal", line.subtotal()
        );
    }

    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private static Map<String, List<String>> readForm(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        byte[] bodyBytes = is.readNBytes(MAX_BODY_BYTES + 1);
        if (bodyBytes.length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException("Request payload exceeds limit of 1MB");
        }
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        return parsePairs(body);
    }

    private static Map<String, List<String>> parsePairs(String encoded) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.computeIfAbsent(decode(key), ignored -> new ArrayList<>()).add(decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, List<String>> values, String key) {
        String value = first(values, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static String first(Map<String, List<String>> values, String key, String fallback) {
        List<String> matches = values.get(key);
        return matches == null || matches.isEmpty() ? fallback : matches.get(0);
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Request could not be completed" : message;
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendText(exchange, status, toJson(value), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String content, String type) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP_HEADER);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".png")) return "image/png";
        return "text/html; charset=utf-8";
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Character || value instanceof java.time.temporal.TemporalAccessor) {
            return quote(value.toString());
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> values) {
            List<String> fields = new ArrayList<>();
            values.forEach((key, item) -> fields.add(quote(String.valueOf(key)) + ":" + toJson(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> values) {
            List<String> items = new ArrayList<>();
            values.forEach(item -> items.add(toJson(item)));
            return "[" + String.join(",", items) + "]";
        }
        return quote(value.toString());
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}