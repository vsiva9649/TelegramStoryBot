package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.*;

@Slf4j
@Service
@Order(20)
@RequiredArgsConstructor
public class ShrtFlyService implements RewardLinkProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;

    // =========================================================
    // SHRTFLY CONFIGURATION
    // =========================================================
    @Value("${reward.trial.shrtfly.api-url:https://shrtfly.com/api}")
    private String apiUrl;
    /**
     * Multiple ShrtFly API keys only.
     * <p>
     * Example:
     * <p>
     * reward.trial.shrtfly.api-keys=
     * ${SHRTFLY_API_KEYS:key1,key2,key3}
     * <p>
     * RewardLinkProviderResolver owns rotation and explicitly selects the
     * key index for this provider.
     */
    @Value("${reward.trial.shrtfly.api-keys:}")
    private String apiKeys;
    @Value("${reward.trial.shrtfly.link-type:1}")
    private int linkType;

    // =========================================================
    // PROVIDER
    // =========================================================

    private static String extractError(Map<String, Object> response, Object resultObject) {

        if (resultObject instanceof String resultText && !resultText.isBlank()) {

            return safeErrorBody(resultText);
        }

        if (resultObject instanceof Map<?, ?> resultMap) {

            String nestedMessage = asString(resultMap.get("message"));

            if (!nestedMessage.isBlank()) {

                return safeErrorBody(nestedMessage);
            }

            String nestedError = asString(resultMap.get("error"));

            if (!nestedError.isBlank()) {

                return safeErrorBody(nestedError);
            }
        }

        String message = asString(response.get("message"));

        if (!message.isBlank()) {

            return safeErrorBody(message);
        }

        String error = asString(response.get("error"));

        if (!error.isBlank()) {

            return safeErrorBody(error);
        }

        return "Unknown error";
    }

    // =========================================================
    // CONFIGURATION STATUS
    // =========================================================

    private static String asString(Object value) {

        return value == null ? "" : String.valueOf(value).trim();
    }

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

    // =========================================================
    // CREATE SHORT LINK
    // =========================================================

    private static String safeErrorBody(String body) {

        if (body == null) {
            return "";
        }

        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();

        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }

    // =========================================================
    // MULTIPLE API KEY CONFIGURATION
    // =========================================================

    private static String safeExceptionMessage(Exception ex) {

        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {

            return ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }

        return safeErrorBody(ex.getMessage());
    }

    // =========================================================
    // ROUND ROBIN
    // =========================================================

    private static String safeHost(String url) {

        try {

            String host = URI.create(url).getHost();

            return host == null || host.isBlank() ? "unknown" : host;

        } catch (Exception ignore) {

            return "unknown";
        }
    }

    @Override
    public RewardLinkProviderType getProviderType() {

        return RewardLinkProviderType.SHRTFLY;
    }

    // =========================================================
    // SHRTFLY ERROR PARSING
    // =========================================================

    @Override
    public boolean isConfigured() {

        return apiUrl != null && !apiUrl.isBlank() && !getConfiguredApiKeys().isEmpty();
    }

    // =========================================================
    // COMMON HELPERS
    // =========================================================

    @Override
    public int getConfiguredApiKeyCount() {

        return getConfiguredApiKeys().size();
    }

    @Override
    public String shorten(String destinationUrl, int apiKeyIndex) {

        // -----------------------------------------------------
        // Destination validation
        // -----------------------------------------------------

        if (destinationUrl == null || destinationUrl.isBlank()) {

            throw new IllegalArgumentException("Destination URL is required");
        }

        // -----------------------------------------------------
        // Load configured multiple API keys
        // -----------------------------------------------------

        List<String> configuredKeys = getConfiguredApiKeys();

        if (apiUrl == null || apiUrl.isBlank()) {

            throw new IllegalStateException("SHRTFLY_API_URL is not configured");
        }

        if (configuredKeys.isEmpty()) {

            throw new IllegalStateException("No ShrtFly API keys are configured. " + "Configure reward.trial.shrtfly.api-keys " + "or SHRTFLY_API_KEYS.");
        }

        // -----------------------------------------------------
        // Select the exact API key requested by the central resolver
        // -----------------------------------------------------

        ApiKeySelection keySelection = selectApiKey(configuredKeys, apiKeyIndex);

        // -----------------------------------------------------
        // API endpoint
        // -----------------------------------------------------

        URI endpoint;

        try {

            endpoint = URI.create(apiUrl.trim());

        } catch (Exception ex) {

            throw new IllegalStateException("Invalid SHRTFLY_API_URL configuration", ex);
        }

        if (endpoint.getScheme() == null || endpoint.getHost() == null) {

            throw new IllegalStateException("Invalid SHRTFLY_API_URL configuration");
        }

        log.info("Creating ShrtFly link using API key slot {}/{} destinationHost={}", keySelection.slotNumber(), keySelection.totalKeys(), safeHost(destinationUrl));

        // -----------------------------------------------------
        // Call ShrtFly API
        // -----------------------------------------------------

        Map<String, Object> response;

        try {

            response = webClientBuilder.build().get().uri(uriBuilder -> {

                uriBuilder.scheme(endpoint.getScheme()).host(endpoint.getHost()).path(endpoint.getPath()).queryParam("api", keySelection.apiKey()).queryParam("url", destinationUrl).queryParam("type", linkType).queryParam("format", "json");

                if (endpoint.getPort() > 0) {

                    uriBuilder.port(endpoint.getPort());
                }

                return uriBuilder.build();
            }).accept(MediaType.APPLICATION_JSON).retrieve().onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(body -> new IllegalStateException("ShrtFly HTTP " + clientResponse.statusCode().value() + (body.isBlank() ? "" : ": " + safeErrorBody(body))))).bodyToMono(JSON_MAP_TYPE).block();

        } catch (IllegalStateException ex) {

            throw ex;

        } catch (Exception ex) {

            throw new IllegalStateException("Unable to call ShrtFly API: " + safeExceptionMessage(ex), ex);
        }

        // -----------------------------------------------------
        // Response validation
        // -----------------------------------------------------

        if (response == null || response.isEmpty()) {

            throw new IllegalStateException("Empty response from ShrtFly");
        }

        String status = asString(response.get("status"));

        Object resultObject = response.get("result");

        if (!"success".equalsIgnoreCase(status)) {

            String error = extractError(response, resultObject);

            throw new IllegalStateException("ShrtFly error: " + error);
        }

        // -----------------------------------------------------
        // Extract shorten_url
        // -----------------------------------------------------

        String shortUrl = "";

        if (resultObject instanceof Map<?, ?> resultMap) {

            shortUrl = asString(resultMap.get("shorten_url"));

        } else if (resultObject != null) {

            shortUrl = asString(resultObject);
        }

        /*
         * Support response where shorten_url
         * exists directly in root JSON.
         */
        if (shortUrl.isBlank()) {

            shortUrl = asString(response.get("shorten_url"));
        }

        if (shortUrl.isBlank()) {

            throw new IllegalStateException("ShrtFly response did not contain shorten_url");
        }

        if (!isHttpUrl(shortUrl)) {

            throw new IllegalStateException("ShrtFly returned an invalid shorten_url");
        }

        // -----------------------------------------------------
        // Success
        // -----------------------------------------------------

        log.info("ShrtFly link created successfully " + "apiKeySlot={}/{} " + "destinationHost={} " + "shortHost={}", keySelection.slotNumber(), keySelection.totalKeys(), safeHost(destinationUrl), safeHost(shortUrl));

        return shortUrl;
    }

    private List<String> getConfiguredApiKeys() {

        Set<String> uniqueKeys = new LinkedHashSet<>();

        if (apiKeys == null || apiKeys.isBlank()) {

            return new ArrayList<>();
        }

        /*
         * Supports:
         *
         * key1,key2,key3
         *
         * key1;key2;key3
         *
         * or line-separated values.
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

        return new ArrayList<>(uniqueKeys);
    }

    private ApiKeySelection selectApiKey(List<String> configuredKeys, int apiKeyIndex) {

        int total = configuredKeys.size();

        if (total <= 0) {
            throw new IllegalStateException("No ShrtFly API keys are configured");
        }

        if (apiKeyIndex < 0 || apiKeyIndex >= total) {
            throw new IllegalArgumentException(
                    "Invalid ShrtFly API key index " + apiKeyIndex + " for " + total + " configured keys");
        }

        return new ApiKeySelection(
                configuredKeys.get(apiKeyIndex),
                apiKeyIndex + 1,
                total
        );
    }

    private record ApiKeySelection(String apiKey, int slotNumber, int totalKeys) {
    }
}