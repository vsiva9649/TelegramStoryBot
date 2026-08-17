package com.siva.storybot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class TelegramConfig {

    // =====================================
    // BOT
    // =====================================

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    // =====================================
    // OWNER
    // =====================================

    @Value("${telegram.owner.id}")
    private Long ownerId;

    @Value("${telegram.owner.username}")
    private String ownerUsername;
}