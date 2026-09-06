package com.siva.storybot.service;

import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;

import java.util.List;

@Slf4j
@Service
public class TelegramRoleCommandRegistrar {

    public List<BotCommand> getUserCommands() {
        return List.of(
                new BotCommand("start", "Open Story Bot"),
                new BotCommand("panel", "Open your menu"),
                new BotCommand("reward", "Get 1 hour reward access"),
                new BotCommand("help", "Help and support")
        );
    }

    public List<BotCommand> getAdminCommands() {
        return List.of(
                new BotCommand("start", "Open admin panel"),
                new BotCommand("panel", "Open admin panel"),
                new BotCommand("storyaccess", "Manage USER story access"),
                new BotCommand("addstoryicon", "Add or replace story icon"),
                new BotCommand("removestoryicon", "Remove story icon"),
                new BotCommand("usage", "View admin command examples"),
                new BotCommand("help", "View admin help")
        );
    }

    public List<BotCommand> getOwnerCommands() {
        return List.of(
                new BotCommand("start", "Open owner panel"),
                new BotCommand("panel", "Open owner panel"),

                new BotCommand("users", "View users - 50 per page"),
                new BotCommand("userdetails", "View one user details"),
                new BotCommand("activeusers", "View users with current access"),
                new BotCommand("expiredusers", "View users without current access"),

                new BotCommand("approveadmin", "Promote USER to ADMIN"),
                new BotCommand("disapproveadmin", "Change ADMIN back to USER"),
                new BotCommand("storyaccess", "Manage USER or ADMIN story access"),

                new BotCommand("history", "View subscription history"),
                new BotCommand("trial", "Give individual free trial"),
                new BotCommand("activate", "Activate paid plan"),
                new BotCommand("expire", "Expire current subscription"),
                new BotCommand("updateuser", "Show user update shortcuts"),

                new BotCommand("trialonsubscription", "Enable global free trial"),
                new BotCommand("trialoffsubscription", "Disable global free trial"),

                new BotCommand("stories", "View owner story library"),
                new BotCommand("syncstories", "Sync story channels"),
                new BotCommand("deleteinactivestory", "Delete inactive stories"),
                new BotCommand("addstoryicon", "Add or replace story icon"),
                new BotCommand("removestoryicon", "Remove story icon"),

                new BotCommand("usage", "View all owner command examples"),
                new BotCommand("help", "View owner help")
        );
    }

    public void registerDefaultCommands(TelegramLongPollingBot bot) throws Exception {
        validateCommands("DEFAULT", getUserCommands());

        SetMyCommands request = new SetMyCommands();
        request.setCommands(getUserCommands());
        request.setScope(new BotCommandScopeDefault());
        bot.execute(request);

        log.info("Default USER Telegram commands registered count={}", getUserCommands().size());
    }

    public void registerOwnerCommands(TelegramLongPollingBot bot, Long ownerId) throws Exception {
        if (ownerId == null) {
            throw new IllegalStateException("Telegram ownerId is not configured");
        }

        registerChatCommands(bot, ownerId, getOwnerCommands(), "OWNER");
    }

    public void registerAdminCommands(TelegramLongPollingBot bot, Long chatId) throws Exception {
        registerChatCommands(bot, chatId, getAdminCommands(), "ADMIN");
    }

    public void registerUserCommands(TelegramLongPollingBot bot, Long chatId) throws Exception {
        registerChatCommands(bot, chatId, getUserCommands(), "USER");
    }

    public void syncCommandsForUser(TelegramLongPollingBot bot, TelegramUser user) {
        if (bot == null || user == null || user.getRole() == null) {
            return;
        }

        Long chatId = user.getChatId() != null ? user.getChatId() : user.getTelegramId();
        if (chatId == null) {
            return;
        }

        try {
            if (user.getRole() == UserRole.OWNER) {
                registerOwnerCommands(bot, chatId);
            } else if (user.getRole() == UserRole.ADMIN) {
                registerAdminCommands(bot, chatId);
            } else {
                registerUserCommands(bot, chatId);
            }
        } catch (Exception e) {
            log.warn("Unable to sync Telegram command menu telegramId={} role={} reason={}",
                    user.getTelegramId(), user.getRole(), e.getMessage());
        }
    }

    private void registerChatCommands(
            TelegramLongPollingBot bot,
            Long chatId,
            List<BotCommand> commands,
            String scopeName) throws Exception {

        if (chatId == null) {
            throw new IllegalArgumentException(scopeName + " chatId cannot be null");
        }

        validateCommands(scopeName, commands);

        SetMyCommands request = new SetMyCommands();
        request.setCommands(commands);
        request.setScope(new BotCommandScopeChat(String.valueOf(chatId)));
        bot.execute(request);

        log.info("{} Telegram commands registered chatId={} count={}", scopeName, chatId, commands.size());
    }

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

            if (command == null || !command.matches("^[a-z0-9_]{1,32}$")) {
                throw new IllegalArgumentException("Invalid Telegram command scope=" + scope + " command=" + command);
            }

            if (description == null || description.isBlank() || description.length() > 256) {
                throw new IllegalArgumentException("Invalid Telegram command description command=" + command);
            }
        }
    }
}
