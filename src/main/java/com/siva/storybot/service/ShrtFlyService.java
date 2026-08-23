package com.siva.storybot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShrtFlyService {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;

    @Value("${reward.trial.shrtfly.api-url:https://shrtfly.com/api}")
    private String apiUrl;

    @Value("${reward.trial.shrtfly.api-key:}")
    private String apiKey;

    // ShrtFly link type. 1 = mainstream in the current publisher API.
    // Monetization-plan selection is controlled in the ShrtFly dashboard.
    @Value("${reward.trial.shrtfly.link-type:1}")
    private int linkType;

    public boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Creates a ShrtFly short URL.
     *
     * IMPORTANT:
     * Spring Boot 4 / Spring Framework 7 uses Jackson 3 internally for WebClient.
     * Some dependencies in this project still bring Jackson 2 classes. Returning a
     * com.fasterxml.jackson.databind.JsonNode directly from bodyToMono(...) causes
     * Spring's Jackson 3 decoder to try to instantiate a Jackson 2 abstract JsonNode,
     * which results in CodecException / InvalidDefinitionException.
     *
     * Decode into plain Java Map values instead. This keeps the HTTP codec entirely
     * on Spring's configured Jackson version and avoids Jackson 2/3 type collisions.
     */
    public String shorten(String destinationUrl) {

        if (destinationUrl == null || destinationUrl.isBlank()) {
            throw new IllegalArgumentException("Destination URL is required");
        }

        if (!isConfigured()) {
            throw new IllegalStateException("SHRTFLY_API_KEY is not configured");
        }

        URI endpoint;
        try {
            endpoint = URI.create(apiUrl.trim());
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid SHRTFLY_API_URL configuration", ex);
        }

        if (endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalStateException("Invalid SHRTFLY_API_URL configuration");
        }

        Map<String, Object> response;

        try {
            response = webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> {
                        uriBuilder
                                .scheme(endpoint.getScheme())
                                .host(endpoint.getHost())
                                .path(endpoint.getPath())
                                .queryParam("api", apiKey)
                                .queryParam("url", destinationUrl)
                                .queryParam("type", linkType)
                                .queryParam("format", "json");

                        if (endpoint.getPort() > 0) {
                            uriBuilder.port(endpoint.getPort());
                        }

                        return uriBuilder.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> new IllegalStateException(
                                            "ShrtFly HTTP " + clientResponse.statusCode().value()
                                                    + (body.isBlank() ? "" : ": " + safeErrorBody(body))
                                    ))
                    )
                    .bodyToMono(JSON_MAP_TYPE)
                    .block();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to call ShrtFly API: " + safeExceptionMessage(ex),
                    ex
            );
        }

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Empty response from ShrtFly");
        }

        String status = asString(response.get("status"));
        Object resultObject = response.get("result");

        if (!"success".equalsIgnoreCase(status)) {
            String error = extractError(response, resultObject);
            throw new IllegalStateException("ShrtFly error: " + error);
        }

        String shortUrl = "";

        if (resultObject instanceof Map<?, ?> resultMap) {
            shortUrl = asString(resultMap.get("shorten_url"));
        } else if (resultObject != null) {
            // Defensive compatibility in case the provider returns the short URL
            // directly in result instead of an object.
            shortUrl = asString(resultObject);
        }

        if (shortUrl.isBlank()) {
            // Defensive fallback for alternate provider response shape.
            shortUrl = asString(response.get("shorten_url"));
        }

        if (shortUrl.isBlank()) {
            throw new IllegalStateException("ShrtFly response did not contain shorten_url");
        }

        if (!isHttpUrl(shortUrl)) {
            throw new IllegalStateException("ShrtFly returned an invalid shorten_url");
        }

        log.info(
                "ShrtFly link created successfully destinationHost={} shortHost={}",
                safeHost(destinationUrl),
                safeHost(shortUrl)
        );

        return shortUrl;
    }

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

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ex) {
            return false;
        }
    }

    private static String safeErrorBody(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }

    private static String safeExceptionMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }
        return safeErrorBody(ex.getMessage());
    }

    private static String safeHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignore) {
            return "unknown";
        }
    }
}
