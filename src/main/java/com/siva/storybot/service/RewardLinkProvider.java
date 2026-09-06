package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;

/**
 * A monetized short-link provider used by the reward trial flow.
 *
 * The resolver owns the global provider + API-key rotation. Provider
 * implementations only execute the explicitly requested API key slot.
 */
public interface RewardLinkProvider {

    RewardLinkProviderType getProviderType();

    boolean isConfigured();

    /**
     * Number of configured API keys available for this provider.
     */
    int getConfiguredApiKeyCount();

    /**
     * Create a short URL using the exact zero-based API-key index selected by
     * RewardLinkProviderResolver.
     */
    String shorten(String destinationUrl, int apiKeyIndex);
}
