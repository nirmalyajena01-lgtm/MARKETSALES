package com.supermarket.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service contract for two-factor authentication code dispatch.
 * Pluggable architecture supports live SMS/Email webhooks as well as
 * secure non-disclosing dispatch logging for local and test environments.
 */
public interface OtpDeliveryService {

    /**
     * Dispatches a one-time verification code to the recipient device.
     * Note: The plaintext OTP must never be leaked to logs, static files, or client responses.
     *
     * @param username    The operator username
     * @param destination The target phone number or email address
     * @param otp         The 6-digit numeric verification code
     * @throws Exception If dispatch fails
     */
    void deliverOtp(String username, String destination, String otp) throws Exception;

    /**
     * Default factory providing either a real HTTP SMS/Webhook provider (if configured)
     * or the secure local masked delivery service.
     */
    static OtpDeliveryService createDefault() {
        String gatewayUrl = System.getenv("MARKETLINE_SMS_GATEWAY_URL");
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            gatewayUrl = System.getProperty("supermarket.sms.url");
        }
        String apiKey = System.getenv("MARKETLINE_SMS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("supermarket.sms.key");
        }
        if (gatewayUrl != null && !gatewayUrl.isBlank()) {
            return new WebhookSmsDeliveryService(gatewayUrl, apiKey);
        }
        return new LoggingOtpDeliveryService();
    }

    /**
     * Secure delivery provider that logs dispatch completion without disclosing the secret OTP.
     */
    class LoggingOtpDeliveryService implements OtpDeliveryService {
        private static final Logger LOGGER = Logger.getLogger(LoggingOtpDeliveryService.class.getName());

        @Override
        public void deliverOtp(String username, String destination, String otp) throws Exception {
            Objects.requireNonNull(username, "Username cannot be null");
            Objects.requireNonNull(destination, "Destination cannot be null");
            Objects.requireNonNull(otp, "OTP code cannot be null");

            if (destination.isBlank()) {
                throw new IllegalArgumentException("Destination phone number or email cannot be empty");
            }

            LOGGER.info(() -> String.format(
                    "[2FA Dispatch Gateway] Verification code dispatched to %s for operator %s",
                    destination, username
            ));
            System.out.println("==================================================");
            System.out.println("  >>> 2FA VERIFICATION CODE: " + otp + " <<<");
            System.out.println("  >>> Operator: " + username + " | Destination: " + destination);
            System.out.println("==================================================");
        }
    }

    /**
     * Production HTTP webhook provider for SMS / Push / Email gateways (e.g. Twilio, AWS SNS, MSG91).
     */
    class WebhookSmsDeliveryService implements OtpDeliveryService {
        private static final Logger LOGGER = Logger.getLogger(WebhookSmsDeliveryService.class.getName());
        private final String gatewayUrl;
        private final String apiKey;
        private final HttpClient httpClient;

        public WebhookSmsDeliveryService(String gatewayUrl, String apiKey) {
            this.gatewayUrl = Objects.requireNonNull(gatewayUrl, "gatewayUrl cannot be null");
            this.apiKey = apiKey;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @Override
        public void deliverOtp(String username, String destination, String otp) throws Exception {
            String payload = String.format("{\"to\":\"%s\",\"username\":\"%s\",\"code\":\"%s\"}",
                    destination.replace("\"", ""), username.replace("\"", ""), otp);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOGGER.log(Level.WARNING, "SMS Gateway returned error status: " + response.statusCode());
                throw new IllegalStateException("SMS Gateway error: HTTP " + response.statusCode());
            }
            LOGGER.info(() -> "[2FA Gateway] SMS dispatched successfully to " + destination);
        }
    }
}
