package com.siva.storybot.bot;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoryTelegramBot extends TelegramLongPollingBot {

    private final TelegramConfig telegramConfig;

    private final TelegramService telegramService;

    @Override
    public String getBotUsername() {

        return telegramConfig.getBotUsername();
    }

    @Override
    public String getBotToken() {

        return telegramConfig.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {

        telegramService.handleUpdate(update, this);
    }
}