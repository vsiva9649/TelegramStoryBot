package com.siva.storybot.service;

import com.siva.storybot.bot.StoryTelegramBot;
import com.siva.storybot.config.TelegramConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramCommandService {

    // =========================================
    // DEPENDENCIES
    // =========================================

    private final StoryTelegramBot storyTelegramBot;

    private final TelegramConfig telegramConfig;

    // =========================================
    // INITIALIZE TELEGRAM COMMANDS
    // =========================================

    @PostConstruct
    public void setupCommands() {

        log.info("=========================================");
        log.info("TELEGRAM COMMAND SETUP STARTED");
        log.info("=========================================");

        registerDefaultCommandsSafely();

        registerOwnerCommandsSafely();

        log.info("=========================================");
        log.info("TELEGRAM COMMAND SETUP COMPLETED");
        log.info("=========================================");
    }

    // =========================================
    // SAFE DEFAULT COMMAND REGISTRATION
    // =========================================

    private void registerDefaultCommandsSafely() {

        try {

            registerDefaultCommands();

        } catch (Exception e) {

            log.error("Default Telegram command registration failed", e);
        }
    }

    // =========================================
    // SAFE OWNER COMMAND REGISTRATION
    // =========================================

    private void registerOwnerCommandsSafely() {

        try {

            registerOwnerCommands();

        } catch (Exception e) {

            log.error("Owner Telegram command registration failed ownerId={}", telegramConfig.getOwnerId(), e);
        }
    }

    // =========================================
    // DEFAULT USER COMMANDS
    // =========================================

    private void registerDefaultCommands() throws TelegramApiException {

        log.info("Registering default user commands");

        List<BotCommand> userCommands = List.of(

                new BotCommand("start", "Start the bot"),

                new BotCommand("help", "Help and support"));

        validateCommands("DEFAULT", userCommands);

        SetMyCommands request = buildDefaultCommands(userCommands);

        storyTelegramBot.execute(request);

        log.info("Default user commands registered successfully count={}", userCommands.size());
    }

    // =========================================
    // OWNER COMMANDS
    // =========================================

    private void registerOwnerCommands() throws TelegramApiException {

        Long ownerId = telegramConfig.getOwnerId();

        log.info("Registering owner commands ownerId={}", ownerId);

        if (ownerId == null) {

            throw new IllegalStateException("Telegram ownerId is not configured");
        }

        List<BotCommand> ownerCommands = List.of(

                // =================================
                // OWNER HOME
                // =================================

                new BotCommand("start", "Open owner panel"),

                new BotCommand("panel", "Open owner panel anytime"),

                // =================================
                // USER MANAGEMENT
                // =================================

                new BotCommand("users", "View all users"),

                new BotCommand("userdetails", "View user details"),

                new BotCommand("activeusers", "View users with current access"),

                new BotCommand("expiredusers", "View users without current access"),
                new BotCommand("approveadmin", "Approve user as admin"),

                new BotCommand("disapproveadmin", "Remove admin role"),

                // =================================
                // SUBSCRIPTION MANAGEMENT
                // =================================

                new BotCommand("history", "View subscription history"),

                new BotCommand("updateuser", "Update role or subscription"),

                // =================================
                // GLOBAL FREE TRIAL
                // =================================

                new BotCommand("trailonsubscription", "Enable global 7-day free trial"),

                new BotCommand("trailoffsubscription", "Disable global free trial"),

                // =================================
                // STORY MANAGEMENT
                // =================================

                new BotCommand("stories", "View all stories"),

                new BotCommand("syncstories", "Sync story channels"),

                new BotCommand("deleteinactivestory", "Delete inactive stories"),

                new BotCommand("addstoryicon", "Add or replace story icon"),

                new BotCommand("removestoryicon", "Remove story icon"),

                // =================================
                // HELP
                // =================================

                new BotCommand("usage", "View owner usage guide"));

        validateCommands("OWNER", ownerCommands);

        SetMyCommands request = buildOwnerCommands(ownerCommands);

        storyTelegramBot.execute(request);

        log.info("Owner commands registered successfully ownerId={} count={}", ownerId, ownerCommands.size());
    }

    // =========================================
    // COMMAND VALIDATION
    // =========================================

    private void validateCommands(String scope, List<BotCommand> commands) {

        if (commands == null || commands.isEmpty()) {

            throw new IllegalArgumentException("Telegram command list cannot be empty scope=" + scope);
        }

        for (BotCommand botCommand : commands) {

            if (botCommand == null) {

                throw new IllegalArgumentException("Telegram command cannot be null scope=" + scope);
            }

            String command = botCommand.getCommand();

            String description = botCommand.getDescription();

            // Telegram supports lowercase:
            // a-z
            // 0-9
            // _
            //
            // Length:
            // 1-32

            if (command == null || !command.matches("^[a-z0-9_]{1,32}$")) {

                throw new IllegalArgumentException("Invalid Telegram command" + " scope=" + scope + " command=" + command);
            }

            if (description == null || description.isBlank()) {

                throw new IllegalArgumentException("Telegram command description cannot be empty" + " command=" + command);
            }

            if (description.length() > 256) {

                throw new IllegalArgumentException("Telegram command description too long" + " command=" + command);
            }

            log.debug("Telegram command validation passed scope={} command={}", scope, command);
        }
    }

    // =========================================
    // BUILD DEFAULT COMMANDS
    // =========================================

    private SetMyCommands buildDefaultCommands(List<BotCommand> commands) {

        SetMyCommands setMyCommands = new SetMyCommands();

        setMyCommands.setCommands(commands);

        setMyCommands.setScope(new BotCommandScopeDefault());

        return setMyCommands;
    }

    // =========================================
    // BUILD OWNER COMMANDS
    // =========================================

    private SetMyCommands buildOwnerCommands(List<BotCommand> commands) {

        SetMyCommands setMyCommands = new SetMyCommands();

        setMyCommands.setCommands(commands);

        setMyCommands.setScope(

                new BotCommandScopeChat(

                        String.valueOf(telegramConfig.getOwnerId())));

        return setMyCommands;
    }
}