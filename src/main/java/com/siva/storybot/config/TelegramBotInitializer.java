package com.siva.storybot.config;

import com.siva.storybot.bot.StoryTelegramBot;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TelegramBotInitializer {

    private final StoryTelegramBot storyTelegramBot;

    @PostConstruct
    public void init() {

        try {

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            botsApi.registerBot(storyTelegramBot);

            log.info("Telegram bot registered successfully");

        } catch (Exception e) {

            log.error("Telegram bot registration failed", e);
        }
    }
}