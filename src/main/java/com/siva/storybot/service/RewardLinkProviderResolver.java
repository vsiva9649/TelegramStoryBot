package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central provider + API-key rotation for reward links.
 *
 * Rotation is PROVIDER FIRST, then KEY:
 *
 * LITESHORT[key1]
 * LITESHORT[key2]
 * LITESHORT[key3]
 * SHRTFLY[key1]
 * SHRTFLY[key2]
 * SHRTFLY[key3]
 * ...then repeat from LITESHORT[key1].
 *
 * If a slot fails, the next slot is tried immediately in the same request.
 * The cursor is advanced for every attempted slot, so after a fallback success
 * the next request naturally continues from the following slot.
 *
 * Future providers are easy to add: implement RewardLinkProvider, give the
 * bean an @Order after the existing providers, add its enum value, and it is
 * automatically included when reward.trial.link-providers=ALL.
 */
@Slf4j
@Service
public class RewardLinkProviderResolver {

    private final Map<RewardLinkProviderType, RewardLinkProvider> providersByType =
            new EnumMap<>(RewardLinkProviderType.class);

    /**
     * Spring injects List beans in @Order order.
     * Current order:
     *   LiteShortService -> @Order(10)
     *   ShrtFlyService   -> @Order(20)
     */
    private final List<RewardLinkProvider> providerOrder;

    /**
     * ALL = every configured RewardLinkProvider bean.
     * You can also explicitly set: LITESHORT,SHRTFLY
     */
    @Value("${reward.trial.link-providers:ALL}")
    private String configuredProviders;

    /**
     * Index of the next provider+key slot to try.
     * Single JVM state only; it resets after application restart.
     */
    private int nextSlotIndex = 0;

    public RewardLinkProviderResolver(List<RewardLinkProvider> providerList) {

        List<RewardLinkProvider> ordered = new ArrayList<>();

        if (providerList != null) {
            for (RewardLinkProvider provider : providerList) {
                if (provider == null || provider.getProviderType() == null) {
                    continue;
                }

                RewardLinkProvider previous =
                        providersByType.put(provider.getProviderType(), provider);

                if (previous != null) {
                    throw new IllegalStateException(
                            "Multiple reward provider implementations found for "
                                    + provider.getProviderType());
                }

                ordered.add(provider);
            }
        }

        this.providerOrder = List.copyOf(ordered);
    }

    /**
     * Create a short URL using the configured rotation.
     *
     * Example with 2 keys each:
     *   request 1 -> LITESHORT key1
     *   request 2 -> LITESHORT key2
     *   request 3 -> SHRTFLY key1
     *   request 4 -> SHRTFLY key2
     *   request 5 -> LITESHORT key1
     *
     * Failure example:
     *   LITESHORT key2 fails -> SHRTFLY key1 is tried immediately.
     *   If SHRTFLY key1 succeeds, the next request starts at SHRTFLY key2.
     */
    public synchronized ShortenResult shortenWithFailover(String destinationUrl) {

        if (destinationUrl == null || destinationUrl.isBlank()) {
            throw new IllegalArgumentException("Destination URL is required");
        }

        List<ProviderKeySlot> slots = buildProviderKeySlots();

        if (slots.isEmpty()) {
            throw new IllegalStateException(
                    "No configured reward provider/API-key combinations are available");
        }

        RuntimeException lastException = null;

        // Try every available slot at most once for this request.
        for (int attemptNumber = 0; attemptNumber < slots.size(); attemptNumber++) {

            int currentIndex = Math.floorMod(nextSlotIndex, slots.size());
            ProviderKeySlot slot = slots.get(currentIndex);

            // Advance BEFORE calling the external provider.
            // Therefore success or failure both naturally move the rotation.
            nextSlotIndex = (currentIndex + 1) % slots.size();

            RewardLinkProvider provider = slot.provider();
            RewardLinkProviderType providerType = provider.getProviderType();
            int apiKeySlotNumber = slot.apiKeyIndex() + 1;
            int providerApiKeyCount = provider.getConfiguredApiKeyCount();

            try {
                log.info(
                        "Trying reward link provider={} apiKeySlot={}/{} rotationSlot={}/{}",
                        providerType,
                        apiKeySlotNumber,
                        providerApiKeyCount,
                        currentIndex + 1,
                        slots.size()
                );

                String shortUrl = provider.shorten(
                        destinationUrl,
                        slot.apiKeyIndex()
                );

                if (shortUrl == null || shortUrl.isBlank()) {
                    throw new IllegalStateException(
                            providerType + " returned an empty short URL");
                }

                log.info(
                        "Reward link provider success provider={} apiKeySlot={}/{}",
                        providerType,
                        apiKeySlotNumber,
                        providerApiKeyCount
                );

                return new ShortenResult(
                        shortUrl,
                        providerType,
                        slot.apiKeyIndex(),
                        apiKeySlotNumber,
                        providerApiKeyCount
                );

            } catch (RuntimeException ex) {
                lastException = ex;

                log.warn(
                        "Reward link provider failed provider={} apiKeySlot={}/{} error={}",
                        providerType,
                        apiKeySlotNumber,
                        providerApiKeyCount,
                        safeError(ex)
                );
            }
        }

        throw new IllegalStateException(
                "All configured reward link provider/API-key combinations failed",
                lastException
        );
    }

    /**
     * Used only as the initial non-null provider value while the pending DB row
     * is created. After shortening succeeds, RewardTrialService replaces it
     * with the provider that actually created the short URL.
     */
    public RewardLinkProviderType getDefaultProviderType() {

        List<RewardLinkProvider> enabledProviders = getEnabledProvidersInOrder();

        if (enabledProviders.isEmpty()) {
            throw new IllegalStateException("No configured reward provider is available");
        }

        return enabledProviders.get(0).getProviderType();
    }

    public boolean hasConfiguredProvider() {

        try {
            return !buildProviderKeySlots().isEmpty();
        } catch (Exception ex) {
            log.warn("Reward provider configuration unavailable: {}", ex.getMessage());
            return false;
        }
    }

    public List<RewardLinkProviderType> getConfiguredProviderTypes() {

        List<RewardLinkProviderType> result = new ArrayList<>();

        for (RewardLinkProvider provider : getEnabledProvidersInOrder()) {
            result.add(provider.getProviderType());
        }

        return List.copyOf(result);
    }

    /**
     * Build the exact flat sequence.
     *
     * IMPORTANT: provider loop is OUTER; key loop is INNER.
     *
     * If LiteShort has 3 keys and ShrtFly has 2 keys:
     *   LITE k1, LITE k2, LITE k3, SHRT k1, SHRT k2
     */
    private List<ProviderKeySlot> buildProviderKeySlots() {

        List<RewardLinkProvider> enabledProviders = getEnabledProvidersInOrder();

        if (enabledProviders.isEmpty()) {
            return List.of();
        }

        List<ProviderKeySlot> slots = new ArrayList<>();

        for (RewardLinkProvider provider : enabledProviders) {

            int keyCount = provider.getConfiguredApiKeyCount();

            for (int apiKeyIndex = 0; apiKeyIndex < keyCount; apiKeyIndex++) {
                slots.add(new ProviderKeySlot(provider, apiKeyIndex));
            }
        }

        return slots;
    }

    private List<RewardLinkProvider> getEnabledProvidersInOrder() {

        if (configuredProviders == null
                || configuredProviders.isBlank()
                || "ALL".equalsIgnoreCase(configuredProviders.trim())) {

            return providerOrder.stream()
                    .filter(RewardLinkProvider::isConfigured)
                    .toList();
        }

        // If an explicit provider list is configured, preserve that exact order.
        Set<RewardLinkProviderType> requestedTypes = new LinkedHashSet<>();

        String[] values = configuredProviders.split("[,;\\r\\n]+");

        for (String value : values) {
            RewardLinkProviderType providerType = RewardLinkProviderType
                    .fromConfigValue(value)
                    .orElseThrow(() -> new IllegalStateException(
                            "Invalid reward.trial.link-providers value: " + value));

            requestedTypes.add(providerType);
        }

        List<RewardLinkProvider> result = new ArrayList<>();

        for (RewardLinkProviderType providerType : requestedTypes) {
            RewardLinkProvider provider = providersByType.get(providerType);

            if (provider == null) {
                log.warn("Reward provider implementation not found: {}", providerType);
                continue;
            }

            if (!provider.isConfigured()) {
                log.warn("Reward provider is not configured: {}", providerType);
                continue;
            }

            result.add(provider);
        }

        return result;
    }

    private static String safeError(RuntimeException ex) {

        if (ex == null) {
            return "unknown error";
        }

        String message = ex.getMessage();

        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }

        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }

    private record ProviderKeySlot(
            RewardLinkProvider provider,
            int apiKeyIndex
    ) {
    }

    public record ShortenResult(
            String shortUrl,
            RewardLinkProviderType providerType,
            int apiKeyIndex,
            int apiKeySlotNumber,
            int providerApiKeyCount
    ) {
    }
}
