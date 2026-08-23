package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RewardLinkProviderResolver {

    private final Map<RewardLinkProviderType, RewardLinkProvider> providers = new EnumMap<>(RewardLinkProviderType.class);

    @Value("${reward.trial.link-provider:SHRTFLY}")
    private String configuredProvider;

    public RewardLinkProviderResolver(List<RewardLinkProvider> providerList) {

        if (providerList == null) {
            return;
        }

        for (RewardLinkProvider provider : providerList) {

            if (provider == null || provider.getProviderType() == null) {
                continue;
            }

            RewardLinkProvider previous = providers.put(provider.getProviderType(), provider);

            if (previous != null) {
                throw new IllegalStateException("Multiple reward provider implementations found for " + provider.getProviderType());
            }
        }
    }

    public RewardLinkProviderType getCurrentProviderType() {

        return RewardLinkProviderType.fromConfigValue(configuredProvider).orElseThrow(() -> new IllegalStateException("Invalid reward.trial.link-provider: " + configuredProvider));
    }

    public RewardLinkProvider getCurrentProvider() {

        RewardLinkProviderType providerType = getCurrentProviderType();

        RewardLinkProvider provider = providers.get(providerType);

        if (provider == null) {
            throw new IllegalStateException("Reward provider implementation not found: " + providerType);
        }

        return provider;
    }

    public boolean isCurrentProviderConfigured() {

        try {

            RewardLinkProvider provider = getCurrentProvider();

            return provider.isConfigured();

        } catch (Exception e) {

            log.warn("Reward provider configuration unavailable: {}", e.getMessage());

            return false;
        }
    }
}