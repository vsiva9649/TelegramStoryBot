package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiteShortService implements RewardLinkProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;

    // =========================================================
    // LITESHORT CONFIGURATION
    // =========================================================
    /**
     * Thread-safe in-memory round-robin counter.
     * <p>
     * Application restart will start rotation again
     * from API key slot 1.
     */
    private final AtomicInteger nextApiKeyIndex = new AtomicInteger(0);
    @Value("${reward.trial.liteshort.api-url:https://liteshort.com/api/}")
    private String apiUrl;
    /**
     * Multiple LiteShort API keys only.
     * <p>
     * Example:
     * <p>
     * reward.trial.liteshort.api-keys=
     * ${LITESHORT_API_KEYS:key1,key2,key3}
     * <p>
     * Rotation:
     * <p>
     * Link 1 -> key1
     * Link 2 -> key2
     * Link 3 -> key3
     * Link 4 -> key1
     * <p>
     * Existing valid pending reward links may be reused by
     * RewardTrialService. In that case LiteShort API is not
     * called and the round-robin position does not move.
     */
    @Value("${reward.trial.liteshort.api-keys:}")
    private String apiKeys;
    /**
     * LiteShort API query parameter containing the API key.
     * <p>
     * Example:
     * <p>
     * ?api=API_KEY
     */
    @Value("${reward.trial.liteshort.api-key-param:api}")
    private String apiKeyParam;
    /**
     * LiteShort API query parameter containing the
     * destination URL.
     * <p>
     * Example:
     * <p>
     * &url=https://t.me/...
     */
    @Value("${reward.trial.liteshort.url-param:url}")
    private String urlParam;

    // =========================================================
    // PROVIDER
    // =========================================================

    private static String asString(Object value) {

        return value == null ? "" : String.valueOf(value).trim();
    }

    // =========================================================
    // CONFIGURATION STATUS
    // =========================================================

    private static boolean isHttpUrl(String value) {

        if (value == null || value.isBlank()) {

            return false;
        }

        try {

            URI uri = URI.create(value);

            String scheme = uri.getScheme();

            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));

        } catch (Exception ex) {

            return false;
        }
    }

    private static String safeText(String text) {

        if (text == null) {

            return "";
        }

        String value = text.replace('\n', ' ').replace('\r', ' ').trim();

        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    // =========================================================
    // CREATE SHORT LINK
    // =========================================================

    private static String safeExceptionMessage(Exception ex) {

        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {

            return ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }

        return safeText(ex.getMessage());
    }

    // =========================================================
    // MULTIPLE API KEYS
    // =========================================================

    private static String safeHost(String url) {

        try {

            String host = URI.create(url).getHost();

            return host == null || host.isBlank() ? "unknown" : host;

        } catch (Exception ignore) {

            return "unknown";
        }
    }

    // =========================================================
    // ROUND ROBIN
    // =========================================================

    @Override
    public RewardLinkProviderType getProviderType() {

        return RewardLinkProviderType.LITESHORT;
    }

    @Override
    public boolean isConfigured() {

        return apiUrl != null && !apiUrl.isBlank() && !getConfiguredApiKeys().isEmpty() && apiKeyParam != null && !apiKeyParam.isBlank() && urlParam != null && !urlParam.isBlank();
    }

    // =========================================================
    // SHORT URL EXTRACTION
    // =========================================================

    public int getConfiguredApiKeyCount() {

        return getConfiguredApiKeys().size();
    }

    @Override
    public String shorten(String destinationUrl) {

        // -----------------------------------------------------
        // Destination validation
        // -----------------------------------------------------

        if (destinationUrl == null || destinationUrl.isBlank()) {

            throw new IllegalArgumentException("Destination URL is required");
        }

        // -----------------------------------------------------
        // Load multiple API keys
        // -----------------------------------------------------

        List<String> configuredKeys = getConfiguredApiKeys();

        if (apiUrl == null || apiUrl.isBlank()) {

            throw new IllegalStateException("LITESHORT_API_URL is not configured");
        }

        if (configuredKeys.isEmpty()) {

            throw new IllegalStateException("No LiteShort API keys are configured. " + "Configure reward.trial.liteshort.api-keys " + "or LITESHORT_API_KEYS.");
        }

        if (apiKeyParam == null || apiKeyParam.isBlank()) {

            throw new IllegalStateException("LiteShort API key parameter name is not configured");
        }

        if (urlParam == null || urlParam.isBlank()) {

            throw new IllegalStateException("LiteShort URL parameter name is not configured");
        }

        // -----------------------------------------------------
        // Select next API key using round robin
        // -----------------------------------------------------

        ApiKeySelection keySelection = selectApiKey(configuredKeys);

        // -----------------------------------------------------
        // API endpoint
        // -----------------------------------------------------

        URI endpoint;

        try {

            endpoint = URI.create(apiUrl.trim());

        } catch (Exception ex) {

            throw new IllegalStateException("Invalid LITESHORT_API_URL configuration", ex);
        }

        if (endpoint.getScheme() == null || endpoint.getHost() == null) {

            throw new IllegalStateException("Invalid LITESHORT_API_URL configuration");
        }

        log.info("Creating LiteShort link using API key slot {}/{} destinationHost={}", keySelection.slotNumber(), keySelection.totalKeys(), safeHost(destinationUrl));

        // -----------------------------------------------------
        // Call LiteShort API
        // -----------------------------------------------------

        Map<String, Object> response;

        try {

            response = webClientBuilder.build().get().uri(uriBuilder -> {

                uriBuilder.scheme(endpoint.getScheme()).host(endpoint.getHost()).path(endpoint.getPath()).queryParam(apiKeyParam.trim(), keySelection.apiKey()).queryParam(urlParam.trim(), destinationUrl);

                if (endpoint.getPort() > 0) {

                    uriBuilder.port(endpoint.getPort());
                }

                return uriBuilder.build();
            }).accept(MediaType.APPLICATION_JSON).retrieve().onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(body -> new IllegalStateException("LiteShort HTTP " + clientResponse.statusCode().value() + (body.isBlank() ? "" : ": " + safeText(body))))).bodyToMono(JSON_MAP_TYPE).block();

        } catch (IllegalStateException ex) {

            throw ex;

        } catch (Exception ex) {

            throw new IllegalStateException("Unable to call LiteShort API: " + safeExceptionMessage(ex), ex);
        }

        // -----------------------------------------------------
        // Response validation
        // -----------------------------------------------------

        if (response == null || response.isEmpty()) {

            throw new IllegalStateException("Empty response from LiteShort");
        }

        String status = asString(response.get("status"));

        /*
         * If status exists, require success.
         *
         * If status is omitted, the URL itself is
         * validated below.
         */
        if (!status.isBlank() && !"success".equalsIgnoreCase(status)) {

            throw new IllegalStateException("LiteShort error: " + extractError(response));
        }

        // -----------------------------------------------------
        // Extract short URL
        // -----------------------------------------------------

        String shortUrl = extractShortUrl(response);

        if (shortUrl.isBlank()) {

            throw new IllegalStateException("LiteShort response did not contain a short URL");
        }

        if (!isHttpUrl(shortUrl)) {

            throw new IllegalStateException("LiteShort returned an invalid short URL");
        }

        // -----------------------------------------------------
        // Success
        // -----------------------------------------------------

        log.info("LiteShort link created successfully " + "apiKeySlot={}/{} " + "destinationHost={} " + "shortHost={}", keySelection.slotNumber(), keySelection.totalKeys(), safeHost(destinationUrl), safeHost(shortUrl));

        return shortUrl;
    }

    // =========================================================
    // ERROR EXTRACTION
    // =========================================================

    private List<String> getConfiguredApiKeys() {

        Set<String> uniqueKeys = new LinkedHashSet<>();

        if (apiKeys == null || apiKeys.isBlank()) {

            return new ArrayList<>();
        }

        /*
         * Supported formats:
         *
         * key1,key2,key3
         *
         * key1;key2;key3
         *
         * or newline-separated keys.
         */
        String[] values = apiKeys.split("[,;\\r\\n]+");

        for (String value : values) {

            if (value == null) {
                continue;
            }

            String key = value.trim();

            if (!key.isBlank()) {

                uniqueKeys.add(key);
            }
        }

        /*
         * LinkedHashSet:
         *
         * 1. Keeps original key order.
         * 2. Automatically removes duplicate API keys.
         */
        return new ArrayList<>(uniqueKeys);
    }

    // =========================================================
    // COMMON HELPERS
    // =========================================================

    private ApiKeySelection selectApiKey(List<String> configuredKeys) {

        int total = configuredKeys.size();

        if (total <= 0) {

            throw new IllegalStateException("No LiteShort API keys are configured");
        }

        /*
         * Thread-safe sequence:
         *
         * 0 -> key1
         * 1 -> key2
         * 2 -> key3
         * 3 -> key1
         * 4 -> key2
         */
        int sequence = nextApiKeyIndex.getAndIncrement();

        int selectedIndex = Math.floorMod(sequence, total);

        return new ApiKeySelection(configuredKeys.get(selectedIndex), selectedIndex + 1, total);
    }

    private String extractShortUrl(Map<String, Object> response) {

        String value;

        // -----------------------------------------------------
        // Root response fields
        // -----------------------------------------------------

        value = asString(response.get("shortenedUrl"));

        if (isHttpUrl(value)) {
            return value;
        }

        value = asString(response.get("short_url"));

        if (isHttpUrl(value)) {
            return value;
        }

        value = asString(response.get("shorten_url"));

        if (isHttpUrl(value)) {
            return value;
        }

        value = asString(response.get("shortUrl"));

        if (isHttpUrl(value)) {
            return value;
        }

        value = asString(response.get("url"));

        if (isHttpUrl(value)) {
            return value;
        }

        // -----------------------------------------------------
        // result
        // -----------------------------------------------------

        Object result = response.get("result");

        if (result instanceof String text && isHttpUrl(text.trim())) {

            return text.trim();
        }

        if (result instanceof Map<?, ?> resultMap) {

            value = firstHttpUrl(resultMap.get("shortenedUrl"), resultMap.get("short_url"), resultMap.get("shorten_url"), resultMap.get("shortUrl"), resultMap.get("url"));

            if (!value.isBlank()) {

                return value;
            }
        }

        // -----------------------------------------------------
        // data
        // -----------------------------------------------------

        Object data = response.get("data");

        if (data instanceof Map<?, ?> dataMap) {

            value = firstHttpUrl(dataMap.get("shortenedUrl"), dataMap.get("short_url"), dataMap.get("shorten_url"), dataMap.get("shortUrl"), dataMap.get("url"));

            if (!value.isBlank()) {

                return value;
            }
        }

        return "";
    }

    private String firstHttpUrl(Object... values) {

        if (values == null) {

            return "";
        }

        for (Object value : values) {

            String text = asString(value);

            if (isHttpUrl(text)) {

                return text;
            }
        }

        return "";
    }

    private String extractError(Map<String, Object> response) {

        String message = asString(response.get("message"));

        if (!message.isBlank()) {

            return safeText(message);
        }

        String error = asString(response.get("error"));

        if (!error.isBlank()) {

            return safeText(error);
        }

        Object result = response.get("result");

        if (result instanceof String text && !text.isBlank()) {

            return safeText(text);
        }

        if (result instanceof Map<?, ?> resultMap) {

            message = asString(resultMap.get("message"));

            if (!message.isBlank()) {

                return safeText(message);
            }

            error = asString(resultMap.get("error"));

            if (!error.isBlank()) {

                return safeText(error);
            }
        }

        return "Unknown error";
    }

    private record ApiKeySelection(String apiKey, int slotNumber, int totalKeys) {
    }
}