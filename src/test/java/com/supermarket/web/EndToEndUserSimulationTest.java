package com.supermarket.web;

import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.service.OtpDeliveryService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-End User Simulation Test Suite.
 * Simulates complete user journeys for all operator roles (Cashier, Inventory Staff, Store Manager),
 * verifying every screen's API operations, authorization boundaries, checkout idempotency,
 * rate limiting, and security controls.
 */
class EndToEndUserSimulationTest {

    private static HttpServer server;
    private static int port;
    private static String baseUrl;
    private static WebServer webServer;
    private static TestOtpDeliveryService otpDelivery;

    static class TestOtpDeliveryService implements OtpDeliveryService {
        private final Map<String, String> latestOtps = new ConcurrentHashMap<>();

        @Override
        public void deliverOtp(String username, String destination, String otp) {
            latestOtps.put(username, otp);
        }

        public String getOtp(String username) {
            return latestOtps.get(username);
        }

        public void clear() {
            latestOtps.clear();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        System.setProperty("supermarket.seed.defaults", "true");
        DatabaseManager db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        otpDelivery = new TestOtpDeliveryService();
        webServer = new WebServer(db, otpDelivery);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/api/", webServer::handleApi);
        server.createContext("/", webServer::handleAsset);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // =========================================================================
    // STEP 1: ANONYMOUS ACCESS & SECURITY PERIMETER
    // =========================================================================

    @Test
    @DisplayName("Step 1: Anonymous access is strictly denied on business APIs, but public assets and health succeed")
    void testStep1AnonymousAccessAndPerimeter() throws Exception {
        // Business APIs reject anonymous requests
        assertThat(request("GET", "/api/items", null, null).status()).isEqualTo(401);
        assertThat(request("GET", "/api/analytics", null, null).status()).isEqualTo(401);
        assertThat(request("GET", "/api/price-history", null, null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/checkout", "amountTendered=100", null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/restock", "itemCode=MILK01&quantity=10", null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/price", "itemCode=MILK01&price=50", null).status()).isEqualTo(401);

        // Public health endpoint succeeds
        ApiResponse health = request("GET", "/api/health", null, null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"ok\"");

        // Setup status check succeeds
        ApiResponse setupStatus = request("GET", "/api/auth/setup-status", null, null);
        assertThat(setupStatus.status()).isEqualTo(200);
        assertThat(setupStatus.body()).contains("\"setupRequired\":false");

        // Favicon returns 200 with SVG and security headers
        HttpURLConnection favConn = (HttpURLConnection) URI.create(baseUrl + "/favicon.ico").toURL().openConnection();
        favConn.setRequestMethod("GET");
        assertThat(favConn.getResponseCode()).isEqualTo(200);
        assertThat(favConn.getContentType()).contains("image/svg+xml");
        assertThat(favConn.getHeaderField("Content-Security-Policy")).contains("style-src-elem").contains("https://fonts.googleapis.com");
        assertThat(favConn.getHeaderField("X-Content-Type-Options")).isEqualTo("nosniff");

        // Static HTML page returns 200 and strict CSP
        HttpURLConnection indexConn = (HttpURLConnection) URI.create(baseUrl + "/").toURL().openConnection();
        indexConn.setRequestMethod("GET");
        assertThat(indexConn.getResponseCode()).isEqualTo(200);
        assertThat(indexConn.getHeaderField("Content-Security-Policy"))
                .contains("style-src-elem 'self' 'unsafe-inline' https://fonts.googleapis.com")
                .contains("font-src 'self' https://fonts.gstatic.com data:")
                .contains("connect-src 'self' https:");
        assertThat(indexConn.getHeaderField("X-Frame-Options")).isEqualTo("DENY");
        assertThat(indexConn.getHeaderField("X-Content-Type-Options")).isEqualTo("nosniff");

        // Unknown API route returns 404 JSON
        ApiResponse unknown = request("GET", "/api/unknown-endpoint", null, null);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).contains("API route not found");
    }

    // =========================================================================
    // STEP 2: CASHIER PERSONA COMPLETE JOURNEY (POS & SALES)
    // =========================================================================

    @Test
    @DisplayName("Step 2: Cashier Complete Journey - Login, POS Cart, Shortfall Rejection, Successful Checkout & Idempotency")
    void testCashierCompleteJourney() throws Exception {
        // 2.1 Cashier fetches CAPTCHA
        ApiResponse capRes = request("GET", "/api/auth/captcha", null, null);
        assertThat(capRes.status()).isEqualTo(200);
        String capToken = extractJsonField(capRes.body(), "token");
        String capAnswer = webServer.getCaptchaCodeForTesting(capToken);

        // 2.2 Wrong password fails
        ApiResponse badLogin = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01",
                "password", "wrongpassword",
                "captchaToken", capToken,
                "captchaAnswer", capAnswer
        ), null);
        assertThat(badLogin.status()).isEqualTo(401);

        // 2.3 Successful Step 1 with new captcha
        capRes = request("GET", "/api/auth/captcha", null, null);
        capToken = extractJsonField(capRes.body(), "token");
        capAnswer = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse step1 = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01",
                "password", "pos123",
                "captchaToken", capToken,
                "captchaAnswer", capAnswer
        ), null);
        assertThat(step1.status()).isEqualTo(200);
        assertThat(step1.body()).contains("\"twoFactorRequired\":true");
        String twoFactorToken = extractJsonField(step1.body(), "twoFactorToken");

        // Verify OTP was dispatched via gateway
        String otp = otpDelivery.getOtp("cashier01");
        assertThat(otp).isNotNull().matches("\\d{6}");

        // 2.4 Verify 2FA
        ApiResponse step2 = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken,
                "code", otp
        ), null);
        assertThat(step2.status()).isEqualTo(200);
        String sessionToken = extractJsonField(step2.body(), "sessionToken");
        String authHeader = "Bearer " + sessionToken;

        // 2.5 Session check
        ApiResponse sessionCheck = request("GET", "/api/auth/session", null, authHeader);
        assertThat(sessionCheck.status()).isEqualTo(200);
        assertThat(sessionCheck.body()).contains("\"authenticated\":true").contains("Priya Sharma");

        // 2.6 Catalog fetch: Cashier CAN see items, but wholesale cost must be omitted
        ApiResponse catalogRes = request("GET", "/api/items", null, authHeader);
        assertThat(catalogRes.status()).isEqualTo(200);
        assertThat(catalogRes.body()).contains("Fresh Whole Milk 1L").doesNotContain("\"costPrice\":");

        // 2.7 POS Checkout: Shortfall check
        ApiResponse shortfall = request("POST", "/api/checkout",
                "amountTendered=10.00&cashier=Priya+Sharma&itemCode=DAIRY-001&quantity=1",
                authHeader);
        assertThat(shortfall.status()).isEqualTo(400);
        assertThat(shortfall.body()).contains("is insufficient for total payable");

        // 2.8 Successful POS Checkout with Idempotency Key
        String idempotencyKey = "TEST-IDEMPOTENCY-" + System.currentTimeMillis();
        ApiResponse checkoutRes = request("POST", "/api/checkout",
                "amountTendered=500.00&cashier=Priya+Sharma&idempotencyKey=" + idempotencyKey + "&itemCode=DAIRY-001&quantity=2",
                authHeader,
                Map.of("Idempotency-Key", idempotencyKey));
        assertThat(checkoutRes.status()).isEqualTo(200);
        assertThat(checkoutRes.body()).contains("\"serial\":").contains("\"lines\":").contains("Fresh Whole Milk 1L");

        String originalSerial = extractJsonField(checkoutRes.body(), "serial");

        // 2.9 Idempotency Verification: Resubmitting identical transaction returns the exact cached receipt
        ApiResponse idempotentRes = request("POST", "/api/checkout",
                "amountTendered=500.00&cashier=Priya+Sharma&idempotencyKey=" + idempotencyKey + "&itemCode=DAIRY-001&quantity=2",
                authHeader,
                Map.of("Idempotency-Key", idempotencyKey));
        assertThat(idempotentRes.status()).isEqualTo(200);
        assertThat(extractJsonField(idempotentRes.body(), "serial")).isEqualTo(originalSerial);

        // 2.10 Role Privilege Enforcement: Cashier is denied manager/staff actions
        assertThat(request("POST", "/api/restock", "itemCode=DAIRY-001&quantity=10", authHeader).status()).isEqualTo(403);
        assertThat(request("POST", "/api/price", "itemCode=DAIRY-001&price=65.00", authHeader).status()).isEqualTo(403);
        assertThat(request("GET", "/api/analytics", null, authHeader).status()).isEqualTo(403);

        // 2.11 Sign out
        ApiResponse logout = request("POST", "/api/auth/logout", "", authHeader);
        assertThat(logout.status()).isEqualTo(200);

        // 2.12 Post-logout session is completely revoked
        assertThat(request("GET", "/api/auth/session", null, authHeader).status()).isEqualTo(401);
    }

    // =========================================================================
    // STEP 3: INVENTORY STAFF PERSONA COMPLETE JOURNEY (RESTOCKING)
    // =========================================================================

    @Test
    @DisplayName("Step 3: Staff Complete Journey - Login, Restock Item, Permission Enforcement & Logout")
    void testStaffCompleteJourney() throws Exception {
        String staffAuth = loginAs("staff", "staff123");

        // 3.1 Verify Staff session
        ApiResponse session = request("GET", "/api/auth/session", null, staffAuth);
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.body()).contains("Amit Verma").contains("Inventory Staff");

        // 3.2 Staff can view catalog
        ApiResponse catalog = request("GET", "/api/items", null, staffAuth);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.body()).contains("White Sandwich Bread 700g");

        // 3.3 Staff performs restock
        ApiResponse restock = request("POST", "/api/restock",
                "itemCode=BAKE-001&quantity=25&operator=Amit+Verma&notes=Morning+Delivery",
                staffAuth);
        assertThat(restock.status()).isEqualTo(200);
        assertThat(restock.body()).contains("\"code\":\"BAKE-001\"").contains("\"stock\":65");

        // 3.4 Staff is denied pricing and analytics
        assertThat(request("POST", "/api/price", "itemCode=BAKE-001&price=99.00", staffAuth).status()).isEqualTo(403);
        assertThat(request("GET", "/api/analytics", null, staffAuth).status()).isEqualTo(403);

        // 3.5 Staff signs out
        assertThat(request("POST", "/api/auth/logout", "", staffAuth).status()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/session", null, staffAuth).status()).isEqualTo(401);
    }

    // =========================================================================
    // STEP 4: STORE MANAGER PERSONA COMPLETE JOURNEY (FULL ADMIN)
    // =========================================================================

    @Test
    @DisplayName("Step 4: Manager Complete Journey - Full Access, Cost Disclosure, Price Change Audit & Analytics")
    void testManagerCompleteJourney() throws Exception {
        String managerAuth = loginAs("manager", "admin123");

        // 4.1 Verify Manager session
        ApiResponse session = request("GET", "/api/auth/session", null, managerAuth);
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.body()).contains("Rajesh Kumar").contains("Store Manager");

        // 4.2 Manager views catalog WITH confidential wholesale cost disclosed
        ApiResponse catalog = request("GET", "/api/items", null, managerAuth);
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.body()).contains("\"costPrice\":");

        // 4.3 Manager updates product selling price
        ApiResponse priceUpdate = request("POST", "/api/price",
                "itemCode=PROD-002&price=260.00&manager=Rajesh+Kumar&reason=Market+Rate+Adjustment",
                managerAuth);
        assertThat(priceUpdate.status()).isEqualTo(200);
        assertThat(priceUpdate.body()).contains("\"item\":").contains("260.00");

        // 4.4 Manager inspects price audit history
        ApiResponse history = request("GET", "/api/price-history", null, managerAuth);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body()).contains("PROD-002").contains("260.00").contains("Rajesh Kumar");

        // 4.5 Manager generates sales analytics report
        LocalDate today = LocalDate.now();
        ApiResponse analytics = request("GET", "/api/analytics?from=" + today.minusDays(30) + "&to=" + today, null, managerAuth);
        assertThat(analytics.status()).isEqualTo(200);
        assertThat(analytics.body()).contains("\"revenue\":").contains("\"transactions\":").contains("\"items\":");

        // 4.6 Manager performs restock and checkout
        assertThat(request("POST", "/api/restock", "itemCode=PROD-002&quantity=10", managerAuth).status()).isEqualTo(200);
        assertThat(request("POST", "/api/checkout", "amountTendered=500.00&itemCode=PROD-002&quantity=1", managerAuth).status()).isEqualTo(200);

        // 4.7 Manager signs out
        assertThat(request("POST", "/api/auth/logout", "", managerAuth).status()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/session", null, managerAuth).status()).isEqualTo(401);
    }

    // =========================================================================
    // STEP 5: SECURITY RESILIENCE & ABUSE PREVENTION
    // =========================================================================

    @Test
    @DisplayName("Step 5: Security Hardening - Captcha Single-use, 2FA Challenge Expiry & Abuse Resistance")
    void testSecurityHardeningAndAbuseResistance() throws Exception {
        // 5.1 Captcha single-use enforcement
        ApiResponse capRes = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(capRes.body(), "token");
        String capAnswer = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse login1 = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01", "password", "pos123",
                "captchaToken", capToken, "captchaAnswer", capAnswer
        ), null);
        assertThat(login1.status()).isEqualTo(200);

        // Reusing the same CAPTCHA token must fail immediately
        ApiResponse reusedCaptcha = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01", "password", "pos123",
                "captchaToken", capToken, "captchaAnswer", capAnswer
        ), null);
        assertThat(reusedCaptcha.status()).isEqualTo(400);
        assertThat(reusedCaptcha.body()).contains("Invalid or expired Captcha");

        // 5.2 Invalid 2FA code fails and consumes an attempt
        String twoFactorToken = extractJsonField(login1.body(), "twoFactorToken");
        ApiResponse badOtp = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken, "code", "000000"
        ), null);
        assertThat(badOtp.status()).isEqualTo(400);
        assertThat(badOtp.body()).contains("Incorrect verification code");

        // Recheck resend without cooldown -> rejected with 429
        ApiResponse quickResend = request("POST", "/api/auth/resend-2fa", form(
                "twoFactorToken", twoFactorToken
        ), null);
        assertThat(quickResend.status()).isEqualTo(429);
        assertThat(quickResend.body()).contains("Please wait at least 30 seconds");

        // 5.3 Verify correct OTP consumes the challenge
        String validOtp = otpDelivery.getOtp("cashier01");
        ApiResponse goodOtp = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken, "code", validOtp
        ), null);
        assertThat(goodOtp.status()).isEqualTo(200);

        // Reusing the consumed challenge token is rejected
        ApiResponse consumedChallenge = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken, "code", validOtp
        ), null);
        assertThat(consumedChallenge.status()).isEqualTo(400);
        assertThat(consumedChallenge.body()).contains("2FA session expired");
    }

    // =========================================================================
    // TEST HELPERS
    // =========================================================================

    private static String loginAs(String username, String password) throws Exception {
        ApiResponse capRes = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(capRes.body(), "token");
        String capAnswer = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse step1 = request("POST", "/api/auth/login-step1", form(
                "username", username,
                "password", password,
                "captchaToken", capToken,
                "captchaAnswer", capAnswer
        ), null);
        assertThat(step1.status()).isEqualTo(200);
        String twoFactorToken = extractJsonField(step1.body(), "twoFactorToken");
        String otp = otpDelivery.getOtp(username);

        ApiResponse step2 = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken,
                "code", otp
        ), null);
        assertThat(step2.status()).isEqualTo(200);
        return "Bearer " + extractJsonField(step2.body(), "sessionToken");
    }

    private static String form(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append("&");
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private record ApiResponse(int status, String body) {}

    private static ApiResponse request(String method, String path, String body, String authHeader) throws Exception {
        return request(method, path, body, authHeader, Map.of());
    }

    private static ApiResponse request(String method, String path, String body, String authHeader, Map<String, String> extraHeaders) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        conn.setRequestMethod(method);
        if (authHeader != null && !authHeader.isBlank()) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        for (var entry : extraHeaders.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String responseBody = stream != null ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
        return new ApiResponse(status, responseBody);
    }

    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start != -1) {
            start += pattern.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        }
        pattern = "\"" + field + "\":";
        start = json.indexOf(pattern);
        if (start != -1) {
            start += pattern.length();
            int end = start;
            while (end < json.length() && (Character.isLetterOrDigit(json.charAt(end)) || json.charAt(end) == '.')) {
                end++;
            }
            return json.substring(start, end);
        }
        return null;
    }
}

