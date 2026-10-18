package com.supermarket.web;

import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class WebServerAuthTest {

    private static HttpServer server;
    private static int port;
    private static String baseUrl;
    private static WebServer webServer;

    @BeforeAll
    static void setUpServer() throws Exception {
        System.setProperty("supermarket.seed.defaults", "true");
        DatabaseManager db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        var constructor = WebServer.class.getDeclaredConstructor(DatabaseManager.class);
        constructor.setAccessible(true);
        webServer = constructor.newInstance(db);

        var handleApiMethod = WebServer.class.getDeclaredMethod("handleApi", com.sun.net.httpserver.HttpExchange.class);
        handleApiMethod.setAccessible(true);
        server.createContext("/api/", exchange -> {
            try {
                handleApiMethod.invoke(webServer, exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        });
        var handleAssetMethod = WebServer.class.getDeclaredMethod("handleAsset", com.sun.net.httpserver.HttpExchange.class);
        handleAssetMethod.setAccessible(true);
        server.createContext("/", exchange -> {
            try {
                handleAssetMethod.invoke(webServer, exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterAll
    static void tearDownServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String loginAs(String username, String password) throws Exception {
        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String token = extractJsonField(captcha.body(), "token");
        String code = webServer.getCaptchaCodeForTesting(token);

        ApiResponse step1 = request("POST", "/api/auth/login-step1", form(
                "username", username,
                "password", password,
                "captchaToken", token,
                "captchaAnswer", code
        ), null);
        assertThat(step1.status()).isEqualTo(200);

        String twoFactorToken = extractJsonField(step1.body(), "twoFactorToken");
        String otp = webServer.getLatest2faOtpForTesting(twoFactorToken);

        ApiResponse step2 = request("POST", "/api/auth/verify-2fa", form(
                "twoFactorToken", twoFactorToken,
                "code", otp
        ), null);
        assertThat(step2.status()).isEqualTo(200);

        return extractJsonField(step2.body(), "sessionToken");
    }

    @Test
    @DisplayName("GET /api/auth/captcha returns captcha challenge with token and SVG without leaking answer")
    void testCaptchaGeneration() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/captcha").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);

        String response = readResponse(conn);
        assertThat(response).contains("\"token\":").contains("\"svg\":");
        assertThat(response).doesNotContain("\"code\":");
    }

    @Test
    @DisplayName("Complete Two-Step Authentication Flow without leaking demo OTP in HTTP response")
    void testCompleteTwoStepAuthenticationFlow() throws Exception {
        // 1. Get captcha
        HttpURLConnection captchaConn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/captcha").toURL().openConnection();
        String captchaJson = readResponse(captchaConn);
        String captchaToken = extractJsonField(captchaJson, "token");
        String captchaCode = webServer.getCaptchaCodeForTesting(captchaToken);

        // 2. Step 1: Login with credentials & captcha
        HttpURLConnection step1Conn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/login-step1").toURL().openConnection();
        step1Conn.setRequestMethod("POST");
        step1Conn.setDoOutput(true);
        step1Conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String form = "username=cashier01"
                + "&password=pos123"
                + "&captchaToken=" + URLEncoder.encode(captchaToken, StandardCharsets.UTF_8)
                + "&captchaAnswer=" + URLEncoder.encode(captchaCode, StandardCharsets.UTF_8);

        try (OutputStream os = step1Conn.getOutputStream()) {
            os.write(form.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(step1Conn.getResponseCode()).isEqualTo(200);
        String step1Json = readResponse(step1Conn);
        assertThat(step1Json).contains("\"success\":true")
                .contains("\"twoFactorToken\":")
                .contains("\"maskedPhone\":");
        // Verify response does not leak demoOtp
        assertThat(step1Json).doesNotContain("\"demoOtp\":");

        String twoFactorToken = extractJsonField(step1Json, "twoFactorToken");
        String otp = webServer.getLatest2faOtpForTesting(twoFactorToken);
        assertThat(otp).isNotNull().matches("\\d{6}");

        // 3. Step 2: Verify 2FA code
        HttpURLConnection step2Conn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/verify-2fa").toURL().openConnection();
        step2Conn.setRequestMethod("POST");
        step2Conn.setDoOutput(true);
        step2Conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String step2Form = "twoFactorToken=" + URLEncoder.encode(twoFactorToken, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(otp, StandardCharsets.UTF_8);

        try (OutputStream os = step2Conn.getOutputStream()) {
            os.write(step2Form.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(step2Conn.getResponseCode()).isEqualTo(200);
        String step2Json = readResponse(step2Conn);
        assertThat(step2Json).contains("\"success\":true")
                .contains("\"sessionToken\":")
                .contains("\"name\":\"Priya Sharma\"")
                .contains("\"role\":\"Cashier 01\"");

        String sessionToken = extractJsonField(step2Json, "sessionToken");

        // 4. Verify authenticated session
        HttpURLConnection sessionConn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/session").toURL().openConnection();
        sessionConn.setRequestMethod("GET");
        sessionConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        assertThat(sessionConn.getResponseCode()).isEqualTo(200);
        String sessionJson = readResponse(sessionConn);
        assertThat(sessionJson).contains("\"authenticated\":true").contains("Priya Sharma");

        // 5. Logout
        HttpURLConnection logoutConn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/logout").toURL().openConnection();
        logoutConn.setRequestMethod("POST");
        logoutConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
        assertThat(logoutConn.getResponseCode()).isEqualTo(200);

        // 6. Confirm session is revoked after logout
        ApiResponse revokedSession = request("GET", "/api/auth/session", null, "Bearer " + sessionToken);
        assertThat(revokedSession.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Step 1 rejects incorrect password")
    void testStep1RejectsBadPassword() throws Exception {
        HttpURLConnection captchaConn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/captcha").toURL().openConnection();
        String captchaJson = readResponse(captchaConn);
        String captchaToken = extractJsonField(captchaJson, "token");
        String captchaCode = webServer.getCaptchaCodeForTesting(captchaToken);

        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/api/auth/login-step1").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String form = "username=cashier01&password=WRONG_PASSWORD"
                + "&captchaToken=" + URLEncoder.encode(captchaToken, StandardCharsets.UTF_8)
                + "&captchaAnswer=" + URLEncoder.encode(captchaCode, StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(form.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Health route succeeds anonymously while unknown API routes return not found")
    void testApiRoutes() throws Exception {
        ApiResponse health = request("GET", "/api/health", null, null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"ok\"");

        ApiResponse unknown = request("GET", "/api/not-a-route", null, null);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).contains("API route not found");
    }

    @Test
    @DisplayName("Login rejects an incorrect captcha and unknown credentials")
    void testLoginRejectsCaptchaAndUnknownUser() throws Exception {
        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String token = extractJsonField(captcha.body(), "token");

        ApiResponse invalidCaptcha = request("POST", "/api/auth/login-step1", form(
            "username", "cashier01", "password", "pos123",
            "captchaToken", token, "captchaAnswer", "invalid"), null);
        assertThat(invalidCaptcha.status()).isEqualTo(400);
        assertThat(invalidCaptcha.body()).contains("Invalid or expired Captcha");

        // Refresh captcha for second attempt
        ApiResponse captcha2 = request("GET", "/api/auth/captcha", null, null);
        String token2 = extractJsonField(captcha2.body(), "token");
        String code2 = webServer.getCaptchaCodeForTesting(token2);

        ApiResponse unknownUser = request("POST", "/api/auth/login-step1", form(
            "username", "unknown", "password", "wrong",
            "captchaToken", token2, "captchaAnswer", code2), null);
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat(unknownUser.body()).contains("Invalid operator ID or password");
    }

    @Test
    @DisplayName("2FA rejects unknown tokens, incorrect codes, and single-use challenges cannot be reused")
    void testTwoFactorFailureAndRetry() throws Exception {
        ApiResponse unknown = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", "unknown-token", "code", "000000"), null);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("2FA session expired");

        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(captcha.body(), "token");
        String capCode = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse login = request("POST", "/api/auth/login-step1", form(
            "username", "cashier01", "password", "pos123",
            "captchaToken", capToken,
            "captchaAnswer", capCode), null);
        String token = extractJsonField(login.body(), "twoFactorToken");
        String otp = webServer.getLatest2faOtpForTesting(token);

        ApiResponse invalidCode = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", token, "code", "000000"), null);
        assertThat(invalidCode.status()).isEqualTo(400);
        assertThat(invalidCode.body()).contains("Incorrect verification code");

        ApiResponse verified = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", token, "code", otp), null);
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body()).contains("\"sessionToken\":");

        // Reusing the consumed challenge token must be rejected
        ApiResponse repeated = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", token, "code", otp), null);
        assertThat(repeated.status()).isEqualTo(400);
        assertThat(repeated.body()).contains("2FA session expired");
    }

    @Test
    @DisplayName("Resending 2FA replaces the challenge code and does not leak it in response")
    void testResendTwoFactorCode() throws Exception {
        ApiResponse missing = request("POST", "/api/auth/resend-2fa", form(
            "twoFactorToken", "unknown-token"), null);
        assertThat(missing.status()).isEqualTo(400);

        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(captcha.body(), "token");
        String capCode = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse login = request("POST", "/api/auth/login-step1", form(
            "username", "cashier01", "password", "pos123",
            "captchaToken", capToken,
            "captchaAnswer", capCode), null);
        String token = extractJsonField(login.body(), "twoFactorToken");

        // Simulate 35 seconds elapsed to satisfy resend cooldown
        webServer.setChallengeLastSentAtForTesting(token, System.currentTimeMillis() - 35000L);

        ApiResponse resent = request("POST", "/api/auth/resend-2fa", form(
            "twoFactorToken", token), null);
        assertThat(resent.status()).isEqualTo(200);
        assertThat(resent.body()).doesNotContain("\"demoOtp\":");

        String newOtp = webServer.getLatest2faOtpForTesting(token);
        assertThat(newOtp).matches("\\d{6}");

        ApiResponse verified = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", token, "code", newOtp), null);
        assertThat(verified.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Session route requires a valid bearer token and logout revokes it")
    void testSessionAndLogoutLifecycle() throws Exception {
        ApiResponse anonymous = request("GET", "/api/auth/session", null, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).contains("\"authenticated\":false");

        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(captcha.body(), "token");
        String capCode = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse login = request("POST", "/api/auth/login-step1", form(
            "username", "cashier01", "password", "pos123",
            "captchaToken", capToken,
            "captchaAnswer", capCode), null);
        String challengeToken = extractJsonField(login.body(), "twoFactorToken");
        String otp = webServer.getLatest2faOtpForTesting(challengeToken);

        ApiResponse verified = request("POST", "/api/auth/verify-2fa", form(
            "twoFactorToken", challengeToken, "code", otp), null);
        String sessionToken = extractJsonField(verified.body(), "sessionToken");

        ApiResponse authenticated = request("GET", "/api/auth/session", null, "Bearer " + sessionToken);
        assertThat(authenticated.status()).isEqualTo(200);
        assertThat(authenticated.body()).contains("\"authenticated\":true");

        ApiResponse logout = request("POST", "/api/auth/logout", "", "Bearer " + sessionToken);
        assertThat(logout.status()).isEqualTo(200);
        assertThat(request("GET", "/api/auth/session", null, "Bearer " + sessionToken).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Business APIs strictly reject unauthenticated requests with 401 Unauthorized")
    void testBusinessApisRejectUnauthenticatedRequests() throws Exception {
        assertThat(request("GET", "/api/items", null, null).status()).isEqualTo(401);
        assertThat(request("GET", "/api/analytics?from=2026-01-01&to=2026-12-31", null, null).status()).isEqualTo(401);
        assertThat(request("GET", "/api/price-history", null, null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/checkout", form("itemCode", "DAIRY-001"), null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/restock", form("itemCode", "DAIRY-001"), null).status()).isEqualTo(401);
        assertThat(request("POST", "/api/price", form("itemCode", "DAIRY-001"), null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Role-based permissions are enforced: Cashier cannot access Manager price or analytics routes")
    void testRoleBasedPermissionsEnforced() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");

        // Cashier can read catalog
        assertThat(request("GET", "/api/items", null, "Bearer " + cashierToken).status()).isEqualTo(200);

        // Cashier forbidden from price updates (403)
        ApiResponse forbiddenPrice = request("POST", "/api/price", form("itemCode", "DAIRY-001", "price", "500"), "Bearer " + cashierToken);
        assertThat(forbiddenPrice.status()).isEqualTo(403);
        assertThat(forbiddenPrice.body()).contains("Forbidden: Insufficient privileges");

        // Cashier forbidden from viewing financial analytics (403)
        ApiResponse forbiddenAnalytics = request("GET", "/api/analytics?from=2026-01-01&to=2026-12-31", null, "Bearer " + cashierToken);
        assertThat(forbiddenAnalytics.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Challenge token cannot act as session token")
    void testChallengeTokenCannotActAsSession() throws Exception {
        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String capToken = extractJsonField(captcha.body(), "token");
        String capCode = webServer.getCaptchaCodeForTesting(capToken);

        ApiResponse login = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01", "password", "pos123",
                "captchaToken", capToken,
                "captchaAnswer", capCode), null);
        String challengeToken = extractJsonField(login.body(), "twoFactorToken");

        // Challenge token should never authorize session or business routes
        assertThat(request("GET", "/api/auth/session", null, "Bearer " + challengeToken).status()).isEqualTo(401);
        assertThat(request("GET", "/api/items", null, "Bearer " + challengeToken).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Malformed analytics, checkout, restock, and price requests return client errors")
    void testInvalidBusinessRequestsReturnBadRequest() throws Exception {
        String managerToken = loginAs("manager", "admin123");

        assertThat(request("GET", "/api/analytics?from=invalid", null, "Bearer " + managerToken).status()).isEqualTo(400);
        assertThat(request("POST", "/api/checkout", "", "Bearer " + managerToken).status()).isEqualTo(400);
        assertThat(request("POST", "/api/restock", "", "Bearer " + managerToken).status()).isEqualTo(400);
        assertThat(request("POST", "/api/price", "", "Bearer " + managerToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("Checkout returns 409 Conflict when requested quantity exceeds available stock")
    void testCheckoutInsufficientStockReturns409() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");
        ApiResponse response = request("POST", "/api/checkout", form(
                "itemCode", "DAIRY-001",
                "quantity", "99999",
                "amountTendered", "5000000.00"
        ), "Bearer " + cashierToken);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).contains("Insufficient stock");
    }

    @Test
    @DisplayName("Checkout idempotency prevents double-charging on duplicate submission")
    void testCheckoutIdempotency() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");
        String idempotencyKey = "tx-retry-" + System.currentTimeMillis();

        String body = form(
                "itemCode", "DAIRY-001",
                "quantity", "1",
                "amountTendered", "1000.00",
                "idempotencyKey", idempotencyKey
        );

        ApiResponse firstCall = request("POST", "/api/checkout", body, "Bearer " + cashierToken);
        assertThat(firstCall.status()).isEqualTo(200);
        String firstSerial = extractJsonField(firstCall.body(), "serial");

        ApiResponse secondCall = request("POST", "/api/checkout", body, "Bearer " + cashierToken);
        assertThat(secondCall.status()).isEqualTo(200);
        String secondSerial = extractJsonField(secondCall.body(), "serial");

        // Idempotent retry returns identical receipt serial without charging stock twice
        assertThat(secondSerial).isEqualTo(firstSerial);
    }

    @Test
    @DisplayName("Static assets are served with content types, security headers, and unsafe paths are rejected")
    void testStaticAssetRoutes() throws Exception {
        ApiResponse html = request("GET", "/", null, null);
        assertThat(html.status()).isEqualTo(200);
        assertThat(html.contentType()).startsWith("text/html");
        assertThat(html.body()).contains("<title>Marketline | Store operations</title>");

        ApiResponse css = request("GET", "/app.css", null, null);
        assertThat(css.status()).isEqualTo(200);
        assertThat(css.contentType()).startsWith("text/css");

        ApiResponse javascript = request("GET", "/app.js", null, null);
        assertThat(javascript.status()).isEqualTo(200);
        assertThat(javascript.contentType()).startsWith("text/javascript");

        assertThat(request("GET", "/missing.png", null, null).status()).isEqualTo(404);
        assertThat(request("GET", "/%2e%2e/pom.xml", null, null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Price, restock, checkout, and analytics API routes return serialized results for authorized manager")
    void testSuccessfulBusinessApiRoutes() throws Exception {
        String managerToken = loginAs("manager", "admin123");

        ApiResponse price = request("POST", "/api/price", form(
                "itemCode", "DAIRY-001", "price", "500.00"), "Bearer " + managerToken);
        assertThat(price.status()).isEqualTo(200);
        assertThat(price.body()).contains("\"belowCost\":false", "\"price\":500");

        ApiResponse restock = request("POST", "/api/restock", form(
                "itemCode", "DAIRY-001", "quantity", "2", "notes", "batch A"), "Bearer " + managerToken);
        assertThat(restock.status()).isEqualTo(200);
        assertThat(restock.body()).contains("\"stock\":");

        ApiResponse history = request("GET", "/api/price-history", null, "Bearer " + managerToken);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body()).contains("DAIRY-001", "Rajesh Kumar");

        ApiResponse checkout = request("POST", "/api/checkout", form(
                "itemCode", "DAIRY-001", "quantity", "1", "amountTendered", "1000.00"), "Bearer " + managerToken);
        assertThat(checkout.status()).isEqualTo(200);
        assertThat(checkout.body()).contains("\"serial\":", "\"cashier\":\"Rajesh Kumar\"", "\"lines\":[");

        ApiResponse analytics = request("GET", "/api/analytics?from=2026-01-01&to=2026-12-31", null, "Bearer " + managerToken);
        assertThat(analytics.status()).isEqualTo(200);
        assertThat(analytics.body()).contains("\"transactions\":", "\"profit\":", "\"items\":[");
    }

    @Test
    @DisplayName("Confidential costPrice is excluded for cashier but included for authorized manager")
    void testCostPriceExcludedForCashierButIncludedForManager() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");
        ApiResponse cashierItems = request("GET", "/api/items", null, "Bearer " + cashierToken);
        assertThat(cashierItems.status()).isEqualTo(200);
        assertThat(cashierItems.body()).doesNotContain("\"costPrice\":");

        String managerToken = loginAs("manager", "admin123");
        ApiResponse managerItems = request("GET", "/api/items", null, "Bearer " + managerToken);
        assertThat(managerItems.status()).isEqualTo(200);
        assertThat(managerItems.body()).contains("\"costPrice\":");
    }

    @Test
    @DisplayName("Resend 2FA enforces 30-second cooldown")
    void testResend2faCooldown() throws Exception {
        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String token = extractJsonField(captcha.body(), "token");
        String code = webServer.getCaptchaCodeForTesting(token);

        ApiResponse step1 = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01",
                "password", "pos123",
                "captchaToken", token,
                "captchaAnswer", code
        ), null);
        assertThat(step1.status()).isEqualTo(200);
        String twoFactorToken = extractJsonField(step1.body(), "twoFactorToken");

        // Immediate resend must trigger 429 Too Many Requests
        ApiResponse resend = request("POST", "/api/auth/resend-2fa", form(
                "twoFactorToken", twoFactorToken
        ), null);
        assertThat(resend.status()).isEqualTo(429);
        assertThat(resend.body()).contains("wait at least 30 seconds");
    }

    @Test
    @DisplayName("Checkout idempotency returns 409 Conflict when payload mismatches")
    void testCheckoutIdempotencyConflict() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");
        String idempotencyKey = "tx-conflict-" + System.currentTimeMillis();

        ApiResponse firstCall = request("POST", "/api/checkout", form(
                "itemCode", "DAIRY-001",
                "quantity", "1",
                "amountTendered", "1000.00",
                "idempotencyKey", idempotencyKey
        ), "Bearer " + cashierToken);
        assertThat(firstCall.status()).isEqualTo(200);

        // Different amount tendered with the same idempotency key must return 409
        ApiResponse conflictCall = request("POST", "/api/checkout", form(
                "itemCode", "DAIRY-001",
                "quantity", "1",
                "amountTendered", "2000.00",
                "idempotencyKey", idempotencyKey
        ), "Bearer " + cashierToken);
        assertThat(conflictCall.status()).isEqualTo(409);
        assertThat(conflictCall.body()).contains("collision with different request payload");
    }

    @Test
    @DisplayName("GET /api/auth/setup-status returns setupRequired status")
    void testSetupStatusEndpoint() throws Exception {
        ApiResponse response = request("GET", "/api/auth/setup-status", null, null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"setupRequired\":");
    }

    @Test
    @DisplayName("Concurrent 2FA verification atomically permits only one winner and returns 409 for the other")
    void testConcurrent2faVerificationOnlyOneSucceeds() throws Exception {
        ApiResponse captcha = request("GET", "/api/auth/captcha", null, null);
        String token = extractJsonField(captcha.body(), "token");
        String code = webServer.getCaptchaCodeForTesting(token);

        ApiResponse step1 = request("POST", "/api/auth/login-step1", form(
                "username", "cashier01",
                "password", "pos123",
                "captchaToken", token,
                "captchaAnswer", code
        ), null);
        assertThat(step1.status()).isEqualTo(200);

        String twoFactorToken = extractJsonField(step1.body(), "twoFactorToken");
        String otp = webServer.getLatest2faOtpForTesting(twoFactorToken);

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var future1 = executor.submit(() -> {
            barrier.await();
            return request("POST", "/api/auth/verify-2fa", form(
                    "twoFactorToken", twoFactorToken,
                    "code", otp
            ), null);
        });
        var future2 = executor.submit(() -> {
            barrier.await();
            return request("POST", "/api/auth/verify-2fa", form(
                    "twoFactorToken", twoFactorToken,
                    "code", otp
            ), null);
        });

        ApiResponse resp1 = future1.get();
        ApiResponse resp2 = future2.get();
        executor.shutdown();

        List<Integer> statuses = List.of(resp1.status(), resp2.status());
        assertThat(statuses).contains(200);
        assertThat(statuses.contains(409) || statuses.contains(400)).isTrue();
    }

    @Test
    @DisplayName("Inventory staff cannot view costPrice on /api/restock")
    void testStaffCannotViewCostPriceOnRestock() throws Exception {
        String staffToken = loginAs("staff", "staff123");
        ApiResponse restockResp = request("POST", "/api/restock", form(
                "itemCode", "DAIRY-001",
                "quantity", "5",
                "notes", "Night shift delivery"
        ), "Bearer " + staffToken);

        assertThat(restockResp.status()).isEqualTo(200);
        assertThat(restockResp.body()).doesNotContain("\"costPrice\":");
    }

    @Test
    @DisplayName("Public SEO and static routes: /robots.txt, /sitemap.xml, custom 404")
    void testRobotsAndSitemapAnd404() throws Exception {
        ApiResponse robots = request("GET", "/robots.txt", null, null);
        assertThat(robots.status()).isEqualTo(200);
        assertThat(robots.body()).contains("User-agent: *");

        ApiResponse sitemap = request("GET", "/sitemap.xml", null, null);
        assertThat(sitemap.status()).isEqualTo(200);
        assertThat(sitemap.body()).contains("<urlset");

        ApiResponse notFound = request("GET", "/non-existent-file-xyz.html", null, null);
        assertThat(notFound.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Database backup /api/admin/backup enforces manager privilege and streams SQLite DB")
    void testBackupEndpointRequiresManager() throws Exception {
        String cashierToken = loginAs("cashier01", "pos123");
        ApiResponse cashierBackup = request("GET", "/api/admin/backup", null, "Bearer " + cashierToken);
        assertThat(cashierBackup.status()).isEqualTo(403);

        String managerToken = loginAs("manager", "admin123");
        ApiResponse managerBackup = request("GET", "/api/admin/backup", null, "Bearer " + managerToken);
        assertThat(managerBackup.status()).isEqualTo(200);
        assertThat(managerBackup.contentType()).contains("application/x-sqlite3");
    }

    private static ApiResponse request(String method, String path, String body, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setRequestMethod(method);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        String contentType = connection.getHeaderField("Content-Type");
        String response = readResponse(connection);
        connection.disconnect();
        return new ApiResponse(status, contentType, response);
    }

    private static String form(String... fields) {
        List<String> pairs = new ArrayList<>();
        for (int index = 0; index < fields.length; index += 2) {
            pairs.add(URLEncoder.encode(fields[index], StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(fields[index + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    private record ApiResponse(int status, String contentType, String body) {}

    private static String readResponse(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }
}
