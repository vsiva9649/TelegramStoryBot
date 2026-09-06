package com.siva.storybot.service;

import com.siva.storybot.bot.StoryTelegramBot;
import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.TelegramUserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramCommandService {

    private final StoryTelegramBot storyTelegramBot;
    private final TelegramConfig telegramConfig;
    private final TelegramUserRepository telegramUserRepository;
    private final TelegramRoleCommandRegistrar telegramRoleCommandRegistrar;

    @PostConstruct
    public void setupCommands() {
        log.info("=========================================");
        log.info("TELEGRAM ROLE COMMAND SETUP STARTED");
        log.info("=========================================");

        registerDefaultCommandsSafely();
        registerOwnerCommandsSafely();
        registerExistingAdminCommandsSafely();

        log.info("=========================================");
        log.info("TELEGRAM ROLE COMMAND SETUP COMPLETED");
        log.info("=========================================");
    }

    private void registerDefaultCommandsSafely() {
        try {
            telegramRoleCommandRegistrar.registerDefaultCommands(storyTelegramBot);
        } catch (Exception e) {
            log.error("Default Telegram command registration failed", e);
        }
    }

    private void registerOwnerCommandsSafely() {
        try {
            telegramRoleCommandRegistrar.registerOwnerCommands(
                    storyTelegramBot,
                    telegramConfig.getOwnerId());
        } catch (Exception e) {
            log.error("Owner Telegram command registration failed ownerId={}",
                    telegramConfig.getOwnerId(), e);
        }
    }

    private void registerExistingAdminCommandsSafely() {
        try {
            List<TelegramUser> admins = telegramUserRepository.findAllByRole(UserRole.ADMIN);

            for (TelegramUser admin : admins) {
                Long chatId = admin.getChatId() != null ? admin.getChatId() : admin.getTelegramId();
                if (chatId == null) {
                    continue;
                }

                try {
                    telegramRoleCommandRegistrar.registerAdminCommands(storyTelegramBot, chatId);
                } catch (Exception e) {
                    log.warn("Admin Telegram command registration failed telegramId={} reason={}",
                            admin.getTelegramId(), e.getMessage());
                }
            }

            log.info("Existing ADMIN Telegram command scopes processed count={}", admins.size());

        } catch (Exception e) {
            log.error("Existing ADMIN Telegram command registration failed", e);
        }
    }
}
