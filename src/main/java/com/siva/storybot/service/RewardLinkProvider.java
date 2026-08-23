package com.siva.storybot.service;

import com.siva.storybot.enums.RewardLinkProviderType;

public interface RewardLinkProvider {

    RewardLinkProviderType getProviderType();

    boolean isConfigured();

    String shorten(String destinationUrl);
}