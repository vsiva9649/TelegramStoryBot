package com.siva.storybot.enums;

import java.util.Arrays;
import java.util.Optional;

public enum RewardLinkProviderType {

    SHRTFLY("SHRTFLY", "SHRTFLY_TELEGRAM", "ShrtFly"),

    LITESHORT("LITESHORT", "LITESHORT_TELEGRAM", "LiteShort");

    /**
     * Value used by reward.trial.link-provider.
     */
    private final String configValue;

    /**
     * Value saved in reward_trials.provider for newly-created rows.
     */
    private final String databaseValue;

    /**
     * Human-readable name for logs/UI when needed.
     */
    private final String displayName;

    RewardLinkProviderType(String configValue, String databaseValue, String displayName) {
        this.configValue = configValue;
        this.databaseValue = databaseValue;
        this.displayName = displayName;
    }

    public static Optional<RewardLinkProviderType> fromConfigValue(String value) {

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = value.trim();

        return Arrays.stream(values()).filter(provider -> provider.configValue.equalsIgnoreCase(normalized) || provider.name().equalsIgnoreCase(normalized) || provider.databaseValue.equalsIgnoreCase(normalized)).findFirst();
    }

    /**
     * Accepts both the new DB marker (for example SHRTFLY_TELEGRAM)
     * and old/legacy values (for example SHRTFLY).
     * <p>
     * This keeps already-created reward rows valid after the provider
     * abstraction was introduced.
     */
    public static Optional<RewardLinkProviderType> fromDatabaseValue(String value) {

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = value.trim();

        return Arrays.stream(values()).filter(provider -> provider.databaseValue.equalsIgnoreCase(normalized) || provider.configValue.equalsIgnoreCase(normalized) || provider.name().equalsIgnoreCase(normalized)).findFirst();
    }

    public String getConfigValue() {
        return configValue;
    }

    public String getDatabaseValue() {
        return databaseValue;
    }

    public String getDisplayName() {
        return displayName;
    }
}