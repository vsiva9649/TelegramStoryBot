package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.dto.story.ChatFullInfo;
import com.siva.storybot.entity.Episode;
import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.Subscription;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.BillingType;
import com.siva.storybot.enums.OwnerIntent;
import com.siva.storybot.enums.SubscriptionPlan;
import com.siva.storybot.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramService {

    private final TelegramConfig telegramConfig;

    private final TelegramUserService telegramUserService;

    private final SubscriptionService subscriptionService;

    private final GroqService groqService;

    private final StoryService storyService;

    private final EpisodeService episodeService;
    private final Map<Long, Long> searchStoryContext = new ConcurrentHashMap<>();

    // =========================================
    // SCHEDULER
    // =========================================

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    private ChatFullInfo loadChatFullDetails(TelegramLongPollingBot bot, Long chatId) {

        try {

            GetChat getChat = new GetChat(String.valueOf(chatId));

            var fullChat = bot.execute(getChat);

            log.info("""
                            
                            ===================================
                            CHAT FULL DETAILS
                            ===================================
                            
                            CHAT ID       : {}
                            TITLE         : {}
                            TYPE          : {}
                            USERNAME      : {}
                            DESCRIPTION   : {}
                            INVITE LINK   : {}
                            
                            ===================================
                            
                            """,

                    fullChat.getId(), fullChat.getTitle(), fullChat.getType(), fullChat.getUserName(), fullChat.getDescription(), fullChat.getInviteLink());

            return new ChatFullInfo(

                    fullChat.getTitle(),

                    fullChat.getUserName(),

                    fullChat.getType(),

                    fullChat.getDescription(),

                    fullChat.getInviteLink());

        } catch (Exception e) {

            log.error("loadChatFullDetails failed", e);

            return new ChatFullInfo(null, null, null, null, null);
        }
    }

    private void sendSubscriptionRequiredMessage(TelegramLongPollingBot bot, Long chatId, Integer userMessageId) {

        try {

            Message botMessage = sendMessage(bot, chatId, """
                    🔒 Subscription Required
                    
                    Welcome to Story Bot 📚
                    
                    To access stories you need
                    an active subscription.
                    
                    👑 Contact Admin
                    
                    👤 Username:
                    @%s
                    
                    🆔 Admin ID:
                    %s
                    """.formatted(telegramConfig.getOwnerUsername(), telegramConfig.getOwnerId()));

            if (userMessageId != null) {

                autoDeleteMessages(bot, chatId, userMessageId, botMessage.getMessageId());
            }

        } catch (Exception e) {

            log.error("sendSubscriptionRequiredMessage failed", e);
        }
    }

    // =========================================
    // MAIN UPDATE HANDLER
    // =========================================

    public void handleUpdate(Update update, TelegramLongPollingBot bot) {

        try {

            log.info("handleUpdate started");

            if (update == null) {

                log.warn("Update is null");

                return;
            }

            // =====================================
            // CALLBACK QUERY
            // =====================================

            if (update.hasCallbackQuery()) {

                log.info("Callback query detected");

                handleCallbackQuery(bot, update.getCallbackQuery());

                return;
            }

            // =====================================
            // MESSAGE SOURCE
            // =====================================

            Message message;

            if (update.hasMessage()) {

                message = update.getMessage();

                log.info("Normal message detected");

            } else if (update.hasChannelPost()) {

                message = update.getChannelPost();

                log.info("Channel post detected");

            } else {

                log.warn("Unsupported telegram update");

                return;
            }

            // =====================================
            // CHAT INFO
            // =====================================

            var chat = message.getChat();

            Long chatId = chat.getId();

            String chatType = chat.getType();

            log.info("CHAT INFO | id={} | title={} | type={}", chatId, chat.getTitle(), chatType);

            // =====================================
            // GROUP / CHANNEL HANDLER
            // =====================================

            if ("group".equals(chatType) || "supergroup".equals(chatType) || "channel".equals(chatType)) {

                ChatFullInfo chatFullInfo = loadChatFullDetails(bot, chatId);

                log.info("Group/channel mode activated");

                // AUDIO DETECTION

                if (message.hasAudio()) {

                    var audio = message.getAudio();

                    log.info("""
                            
                            AUDIO RECEIVED
                            
                            STORY GROUP : {}
                            FILE ID      : {}
                            FILE NAME    : {}
                            DURATION     : {}
                            FILE SIZE    : {}
                            MIME TYPE    : {}
                            
                            """, chat.getTitle(), audio.getFileId(), audio.getFileName(), audio.getDuration(), audio.getFileSize(), audio.getMimeType());

                    Story story = storyService.findOrCreateStory(

                            chatId,

                            chatFullInfo.getTitle(),

                            chatFullInfo.getUsername(),

                            chatFullInfo.getChatType(),

                            chatFullInfo.getDescription(),

                            chatFullInfo.getInviteLink());

                    log.info("""
                            
                            STORY READY
                            
                            STORY ID    : {}
                            STORY TITLE : {}
                            CHAT ID     : {}
                            
                            """, story.getId(), story.getTitle(), story.getTelegramChatId());

                    Episode episode = episodeService.saveEpisode(story, message);

                    log.info("""
                            
                            EPISODE SAVED
                            
                            EPISODE DB ID : {}
                            STORY         : {}
                            EPISODE NO    : {}
                            TITLE         : {}
                            FILE ID       : {}
                            MESSAGE ID    : {}
                            DURATION      : {}
                            FILE SIZE     : {}
                            
                            """, episode.getId(), story.getTitle(), episode.getEpisodeNo(), episode.getTitle(), episode.getTelegramFileId(), episode.getTelegramMessageId(), episode.getDurationSeconds(), episode.getFileSize());

                    return;
                }

                // DOCUMENT

                if (message.hasDocument()) {

                    var document = message.getDocument();

                    log.info("""
                            
                            DOCUMENT RECEIVED
                            
                            FILE ID   : {}
                            FILE NAME : {}
                            MIME TYPE : {}
                            
                            """, document.getFileId(), document.getFileName(), document.getMimeType());

                    return;
                }

                log.info("Ignoring non-audio group/channel message");

                return;
            }

            // =====================================
            // PRIVATE CHAT ONLY BELOW
            // =====================================

            log.info("Private chat detected");

            if (!message.hasText()) {

                log.warn("Private message has no text");

                return;
            }

            var telegramApiUser = message.getFrom();

            if (telegramApiUser == null) {

                log.warn("Telegram user is null");

                return;
            }

            String text = message.getText().trim();

            TelegramUser telegramUser = telegramUserService.saveOrUpdateUser(telegramApiUser, chatId);

            if (telegramUser.getRole() == UserRole.OWNER) {

                handleOwnerCommands(bot, chatId, text);

                return;
            }

            // =====================================
            // SUBSCRIPTION CHECK
            // =====================================

            boolean active = subscriptionService.hasActiveSubscription(telegramUser);

            Integer userMessageId = message.getMessageId();

            if (!active) {

                sendSubscriptionRequiredMessage(bot, chatId, userMessageId);

                return;
            }

            // =========================
            // START / MAIN MENU
            // =========================

            if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("🏠 Main Menu")) {

                showMainMenu(bot, chatId);

                return;
            }

            // =====================================
            // HELP COMMAND
            // =====================================

            if (text.equalsIgnoreCase("/help")) {

                showHelpMenu(bot, chatId);

                return;
            }

            if (text.equalsIgnoreCase("Tamil Stories")) {

                showTamilMenu(bot, chatId);

                return;
            }

            if (text.equalsIgnoreCase("Hindi Stories")) {

                sendMessage(bot, chatId, """
                        🎬 Hindi Stories
                        
                        🚧 Coming Soon
                        """);

                return;
            }

            if (text.equalsIgnoreCase("🔥 OnGoing Stories")) {

                showOnGoingStories(bot, chatId, 0);

                return;
            }

            if (text.equalsIgnoreCase("✅ Completed Stories")) {

                showCompletedStories(bot, chatId, 0);

                return;
            }

            if (text.equalsIgnoreCase("🆘 Help")) {

                showHelpMenu(bot, chatId);

                return;
            }


            // =====================================
            // RANGE SEARCH (e.g. 20-50)
            // =====================================

            if (text.matches("\\d+\\s*-\\s*\\d+")) {

                Long storyId = searchStoryContext.get(chatId);

                if (storyId == null) {

                    sendMessage(bot, chatId, """
                            ❌ Search Context Not Found
                            
                            Please open a story first,
                            then click 🔍 Search.
                            """);

                    return;
                }

                String[] split = text.split("-");

                int start = Integer.parseInt(split[0].trim());

                int end = Integer.parseInt(split[1].trim());

                if (start > end) {

                    sendMessage(bot, chatId, "❌ Invalid range. Example: 20-50");

                    return;
                }

                Story story = storyService.getStoryById(storyId);

                if (story == null) {

                    sendMessage(bot, chatId, "❌ Story not found");

                    searchStoryContext.remove(chatId);

                    return;
                }

                showEpisodesByRange(bot, chatId, story, start, end);
                searchStoryContext.remove(chatId);
                return;
            }

            // =====================================
            // DEFAULT — SHOW STORIES
            // =====================================

            showMainMenu(bot, chatId);

            log.info("handleUpdate completed");

        } catch (Exception e) {

            log.error("handleUpdate failed", e);
        }
    }

    private void showMainMenu(TelegramLongPollingBot bot, Long chatId) {

        try {

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText("""
                    🇮🇳 Welcome To Story Bot 🇮🇳
                    
                    📚 Your Story Adventure Starts Here
                    
                    Select your option from below 👇
                    """);

            // =====================================
            // FLOWER KEYBOARD
            // =====================================

            ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();

            keyboard.setResizeKeyboard(true);

            keyboard.setOneTimeKeyboard(false);

            keyboard.setSelective(true);

            keyboard.setInputFieldPlaceholder("Select Menu 👇");

            List<KeyboardRow> rows = new ArrayList<>();

            KeyboardRow row1 = new KeyboardRow();

            row1.add("Tamil Stories");

            row1.add("Hindi Stories");

            rows.add(row1);

            keyboard.setKeyboard(rows);

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showMainMenu failed", e);
        }
    }

    private void showTamilMenu(TelegramLongPollingBot bot, Long chatId) {

        try {

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText("""
                    🇮🇳 Tamil Stories
                    
                    Select your option 👇
                    """);

            // =====================================
            // FLOWER KEYBOARD
            // =====================================

            ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();

            keyboard.setResizeKeyboard(true);

            keyboard.setOneTimeKeyboard(false);

            keyboard.setSelective(true);

            keyboard.setInputFieldPlaceholder("Choose Option 👇");

            List<KeyboardRow> rows = new ArrayList<>();

            // =====================================
            // ROW 1
            // =====================================

            KeyboardRow row1 = new KeyboardRow();

            row1.add("🔥 OnGoing Stories");

            row1.add("✅ Completed Stories");

            rows.add(row1);

            // =====================================
            // ROW 2
            // =====================================

            KeyboardRow row2 = new KeyboardRow();

            row2.add("🆘 Help");

            row2.add("🏠 Main Menu");

            rows.add(row2);

            keyboard.setKeyboard(rows);

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showTamilMenu failed", e);
        }
    }

    private void showHelpMenu(TelegramLongPollingBot bot, Long chatId) {

        try {

            sendMessage(bot, chatId, """
                    ☎️ CONTACT US
                    
                    👑 Admin :
                    @%s
                    
                    🆔 Admin ID :
                    %s
                    
                    💬 Contact for:
                    
                    • Subscription
                    • Support
                    • Episode Issues
                    • Story Requests
                    """.formatted(telegramConfig.getOwnerUsername(), telegramConfig.getOwnerId()));

        } catch (Exception e) {

            log.error("showHelpMenu failed", e);
        }
    }

    private void showCompletedStories(TelegramLongPollingBot bot, Long chatId, int page) {

        try {

            int size = 10;

            Page<Story> stories = storyService.getCompletedStories(page, size);

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, """
                        ✅ Completed Stories
                        
                        🚧 No completed stories available now
                        """);

                return;
            }

            showStoryList(bot, chatId, stories, "📚 Pocket FM Completed Stories", "completed_stories_", page);

        } catch (Exception e) {

            log.error("showCompletedStories failed", e);
        }
    }

    private void showOnGoingStories(TelegramLongPollingBot bot, Long chatId, int page) {

        try {

            int size = 10;

            Page<Story> stories = storyService.getOnGoingStories(page, size);

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, """
                        📚 OnGoing Stories
                        
                        🚧 New ongoing stories soon available
                        """);

                return;
            }

            showStoryList(bot, chatId, stories, "📚 Pocket FM OnGoing Stories", "ongoing_stories_", page);

        } catch (Exception e) {

            log.error("showOnGoingStories failed", e);
        }
    }

    private void showStoryList(TelegramLongPollingBot bot, Long chatId, Page<Story> stories, String title, String callbackPrefix, int page) {

        try {

            StringBuilder builder = new StringBuilder();

            builder.append(title).append("\n\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            int count = 1;

            for (Story story : stories) {

                builder.append(String.format("%02d", count++)).append(" - ").append(story.getTitle()).append("\n");

                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setText("🎧 " + story.getTitle());

                button.setCallbackData("story_" + story.getId());

                rows.add(List.of(button));
            }

            // NAVIGATION

            List<InlineKeyboardButton> nav = new ArrayList<>();

            if (page > 0) {

                InlineKeyboardButton prev = new InlineKeyboardButton();

                prev.setText("⬅️ Prev");

                prev.setCallbackData(callbackPrefix + (page - 1));

                nav.add(prev);
            }

            InlineKeyboardButton home = new InlineKeyboardButton();

            home.setText("🏠 Main Menu");

            home.setCallbackData("main_menu");

            nav.add(home);

            if (stories.hasNext()) {

                InlineKeyboardButton next = new InlineKeyboardButton();

                next.setText("Next ➡️");

                next.setCallbackData(callbackPrefix + (page + 1));

                nav.add(next);
            }

            rows.add(nav);

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(rows);

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showStoryList failed", e);
        }
    }

    // =========================================
    // CALLBACK QUERY HANDLER
    // =========================================

    private void handleCallbackQuery(TelegramLongPollingBot bot, CallbackQuery callbackQuery) {

        try {

            Long chatId = callbackQuery.getMessage().getChatId();
            Integer messageId = callbackQuery.getMessage().getMessageId();


            TelegramUser user = telegramUserService.getUserByTelegramId(chatId);

            // =====================================
            // OWNER BYPASS
            // =====================================

            if (user != null && user.getRole() != UserRole.OWNER) {

                boolean active = subscriptionService.hasActiveSubscription(user);

                if (!active) {

                    sendSubscriptionRequiredMessage(bot, chatId, messageId);

                    return;
                }
            }

            String data = callbackQuery.getData();

            log.info("Callback received={}", data);
            if ("ignore".equals(data)) {

                return;
            }

            // =====================================
            // LANGUAGE MENU
            // =====================================

            if (data.equals("lang_tamil")) {

                showTamilMenu(bot, chatId);

                return;
            }

            if (data.equals("lang_hindi")) {

                sendMessage(bot, chatId, """
                        🎬 Hindi Stories
                        
                        🚧 Coming Soon
                        """);

                return;
            }

            // =====================================
            // MAIN MENU
            // =====================================

            if (data.equals("main_menu")) {

                showMainMenu(bot, chatId);

                return;
            }

            // =====================================
            // HELP
            // =====================================

            if (data.equals("help_menu")) {

                showHelpMenu(bot, chatId);

                return;
            }

            // =====================================
            // COMPLETED STORIES
            // =====================================

            if (data.startsWith("completed_stories_")) {

                int page = Integer.parseInt(data.replace("completed_stories_", ""));

                showCompletedStories(bot, chatId, page);

                return;
            }

            // =====================================
            // ONGOING STORIES
            // =====================================

            if (data.startsWith("ongoing_stories_")) {

                int page = Integer.parseInt(data.replace("ongoing_stories_", ""));

                showOnGoingStories(bot, chatId, page);

                return;
            }

            // =====================================
            // USERS PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("users_")) {

                int page = Integer.parseInt(data.replace("users_", ""));

                showUsers(bot, chatId, page, messageId);

                return;
            }

            // =====================================
            // HISTORY PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("history_")) {

                String[] split = data.split("_");

                Long telegramId = Long.parseLong(split[1]);

                int page = Integer.parseInt(split[2]);

                user = telegramUserService.getUserByTelegramId(telegramId);

                if (user != null) {

                    showUserHistory(bot, chatId, user, page, messageId);
                }

                return;
            }

            // =====================================
            // STORIES PAGINATION
            // =====================================

            if (data.startsWith("stories_")) {

                int page = Integer.parseInt(data.replace("stories_", ""));

                showStories(bot, chatId, page);

                return;
            }

            // =====================================
            // STORY OPEN
            // =====================================

            if (data.startsWith("story_")) {

                Long storyId = Long.parseLong(data.replace("story_", ""));

                showEpisodePacks(bot, chatId, storyId, 0);

                return;
            }

            // =====================================
            // PACK PAGE
            // =====================================

            if (data.startsWith("packs_")) {

                String[] split = data.split("_");

                Long storyId = Long.parseLong(split[1]);

                int page = Integer.parseInt(split[2]);

                showEpisodePacks(bot, chatId, storyId, page);

                return;
            }

            // =====================================
            // OPEN PACK
            // =====================================

            if (data.startsWith("pack_")) {

                String[] split = data.split("_");

                Long storyId = Long.parseLong(split[1]);

                int start = Integer.parseInt(split[2]);

                int end = Integer.parseInt(split[3]);

                showEpisodesInsidePack(bot, chatId, storyId, start, end);

                return;
            }

            // =====================================
            // SEARCH HINT
            // =====================================

            if (data.startsWith("search_")) {

                Long storyId = Long.parseLong(data.replace("search_", ""));

                searchStoryContext.put(chatId, storyId);

                sendMessage(bot, chatId, """
                        🔍 Episode Search
                        
                        Type a range in chat:
                        
                        20-50
                        100-120
                        """);

                return;
            }

            // =====================================
            // PLAY AUDIO
            // =====================================

            if (data.startsWith("episode_")) {

                Long episodeId = Long.parseLong(data.replace("episode_", ""));

                sendEpisodeAudio(bot, chatId, episodeId);
            }

        } catch (Exception e) {

            log.error("handleCallbackQuery failed", e);
        }
    }

    // =========================================
    // OWNER COMMANDS
    // =========================================

    private void handleOwnerCommands(TelegramLongPollingBot bot, Long chatId, String text) throws Exception {

        log.info("Handling owner command text={}", text);

        String lowerText = text.toLowerCase();

        String cleanedText = text.replace("@", "");

        String[] parts = cleanedText.split("\\s+");

        // =====================================
        // USAGE GUIDE
        // =====================================

        // =====================================
        // START
        // =====================================

        if (lowerText.equals("/start")) {

            sendMessage(bot, chatId, """
                    👑 Welcome, Owner!
                    
                    🤖 StoryBot is ready.
                    
                    ━━━━━━━━━━━━━━
                    📚 FEATURES
                    ━━━━━━━━━━━━━━
                    
                    🎧 Audio Story Streaming
                       Users can listen to stories
                       directly inside Telegram
                    
                    📦 Episode Pack System
                       Episodes grouped in packs
                       Easy navigation (EP 1-10, 11-20)
                    
                    🔍 Episode Search
                       Users type range to search
                       Example: 20-50
                    
                    💳 Subscription System
                       FREE / MONTHLY / YEARLY / LIFETIME
                       plans supported
                    
                    🤖 AI Intent Detection
                       Owner commands via natural language
                       Powered by Groq AI
                    
                    ━━━━━━━━━━━━━━
                    👑 OWNER COMMANDS
                    ━━━━━━━━━━━━━━
                    
                    /stories   → View all stories
                    /usage     → Full command guide
                    
                    ━━━━━━━━━━━━━━
                    ⚡ QUICK ACTIONS
                    ━━━━━━━━━━━━━━
                    
                    show users
                    trial @username
                    activate @username monthly
                    history @username
                    """);

            return;
        }

        if (lowerText.equals("/usage")) {

            sendMessage(bot, chatId, """
                    👑 OWNER USAGE GUIDE
                    
                    ━━━━━━━━━━━━━━
                    👥 USERS
                    ━━━━━━━━━━━━━━
                    
                    users
                    show users
                    get all users
                    
                    ━━━━━━━━━━━━━━
                    👤 USER DETAILS
                    ━━━━━━━━━━━━━━
                    
                    show user @username
                    
                    user 5999036520
                    
                    ━━━━━━━━━━━━━━
                    🎭 ROLE UPDATE
                    ━━━━━━━━━━━━━━
                    
                    make admin @username
                    
                    ━━━━━━━━━━━━━━
                    🎁 FREE TRIAL
                    ━━━━━━━━━━━━━━
                    
                    trial @username
                    
                    trial @username 15
                    
                    ━━━━━━━━━━━━━━
                    💳 MONTHLY PLAN
                    ━━━━━━━━━━━━━━
                    
                    activate @username monthly
                    
                    ━━━━━━━━━━━━━━
                    📅 YEARLY PLAN
                    ━━━━━━━━━━━━━━
                    
                    activate @username yearly
                    
                    ━━━━━━━━━━━━━━
                    ♾️ LIFETIME PLAN
                    ━━━━━━━━━━━━━━
                    
                    activate @username lifetime
                    
                    ━━━━━━━━━━━━━━
                    ❌ EXPIRE USER
                    ━━━━━━━━━━━━━━
                    
                    expire @username
                    
                    ━━━━━━━━━━━━━━
                    📜 HISTORY
                    ━━━━━━━━━━━━━━
                    
                    history @username
                    
                    show history @username
                    
                    history 5999036520
                    
                    ━━━━━━━━━━━━━━
                    📚 STORIES
                    ━━━━━━━━━━━━━━
                    
                    /stories
                    
                    /syncstories
                    """);

            return;
        }

        // =====================================
        // STORIES
        // =====================================

        if (lowerText.equals("/stories")) {

            showStories(bot, chatId, 0);

            return;
        }

        if (lowerText.equals("/syncstories")) {

            syncStories(bot, chatId);

            return;
        }

        if (lowerText.equals("/deleteinactivestory")) {

            deleteInactiveStories(bot, chatId);

            return;
        }

        // =====================================
        // UPDATE USER / SUBSCRIPTION
        // =====================================

        if (lowerText.contains("trial") || lowerText.contains("trail") || lowerText.contains("activate") || lowerText.contains("admin") || lowerText.contains("expire")) {

            TelegramUser targetUser = null;

            for (String part : parts) {

                String value = part.toLowerCase().replace("@", "");

                // SKIP RESERVED WORDS

                if (value.equals("trial") || value.equals("trail") || value.equals("activate") || value.equals("admin") || value.equals("expire") || value.equals("monthly") || value.equals("yearly") || value.equals("lifetime") || value.equals("make") || value.equals("history") || value.equals("show")) {

                    continue;
                }

                // FIND BY TELEGRAM ID

                if (value.matches("\\d+")) {

                    targetUser = telegramUserService.getUserByTelegramId(Long.parseLong(value));

                    if (targetUser != null) {
                        break;
                    }
                }

                // FIND BY USERNAME

                if (value.length() >= 4) {

                    targetUser = telegramUserService.getUserByUsername(value);

                    if (targetUser != null) {
                        break;
                    }
                }
            }

            // USER NOT FOUND

            if (targetUser == null) {

                sendMessage(bot, chatId, """
                        ❌ User not found
                        
                        Example:
                        
                        trial @username
                        activate @username monthly
                        """);

                return;
            }

            // MAKE ADMIN

            if (lowerText.contains("admin")) {

                // =====================================
                // ONLY OWNER CAN MAKE ADMIN
                // =====================================

                if (!chatId.equals(telegramConfig.getOwnerId())) {

                    log.warn("Unauthorized admin role update attempt chatId={}", chatId);

                    sendMessage(bot, chatId, """
                            ❌ Access Denied
                            
                            Only Bot Owner can assign
                            ADMIN role.
                            """);

                    return;
                }

                telegramUserService.updateUserRole(targetUser, UserRole.ADMIN);

                sendMessage(bot, chatId, """
                        ✅ ADMIN ROLE UPDATED
                        
                        👤 User :
                        @%s
                        
                        🎭 Role :
                        ADMIN
                        """.formatted(targetUser.getUsername()));

                return;
            }

            // FREE TRIAL

            if (lowerText.contains("trial") || lowerText.contains("trail")) {

                int trialDays = 7;

                for (String part : parts) {

                    if (part.matches("\\d+")) {

                        trialDays = Integer.parseInt(part);

                        break;
                    }
                }

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.FREE, BillingType.MONTHLY, BigDecimal.ZERO, trialDays);

                sendMessage(bot, chatId, """
                        ✅ FREE TRIAL ACTIVATED
                        
                        👤 User :
                        @%s
                        
                        ⏳ Validity :
                        %s Days
                        """.formatted(targetUser.getUsername(), trialDays));

                return;
            }

            // MONTHLY

            if (lowerText.contains("monthly")) {

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.MONTHLY, BillingType.MONTHLY, new BigDecimal("299"), 30);

                sendMessage(bot, chatId, """
                        ✅ MONTHLY PLAN ACTIVATED
                        
                        👤 User :
                        @%s
                        
                        📅 Validity :
                        30 Days
                        """.formatted(targetUser.getUsername()));

                return;
            }

            // YEARLY

            if (lowerText.contains("yearly")) {

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.YEARLY, BillingType.YEARLY, new BigDecimal("1999"), 365);

                sendMessage(bot, chatId, """
                        ✅ YEARLY PLAN ACTIVATED
                        
                        👤 User :
                        @%s
                        
                        📅 Validity :
                        365 Days
                        """.formatted(targetUser.getUsername()));

                return;
            }

            // LIFETIME

            if (lowerText.contains("lifetime")) {

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.LIFETIME, BillingType.LIFETIME, new BigDecimal("4999"), 36500);

                sendMessage(bot, chatId, """
                        ✅ LIFETIME PLAN ACTIVATED
                        
                        👤 User :
                        @%s
                        """.formatted(targetUser.getUsername()));

                return;
            }

            // EXPIRE

            if (lowerText.contains("expire")) {

                subscriptionService.expireSubscription(targetUser);

                sendMessage(bot, chatId, """
                        ❌ SUBSCRIPTION EXPIRED
                        
                        👤 User :
                        @%s
                        """.formatted(targetUser.getUsername()));

                return;
            }
        }

        // =====================================
        // GROQ AI INTENT DETECTION
        // =====================================

        OwnerIntent intent = groqService.detectIntent(text);

        log.info("Detected owner intent={}", intent);

        switch (intent) {

            // GET USERS

            case GET_USERS -> showUsers(bot, chatId, 0, null);

            // GET USER DETAILS

            case GET_USER_DETAILS -> sendMessage(bot, chatId, """
                    👤 USER DETAILS
                    
                    Send:
                    
                    Telegram ID
                    OR
                    Username
                    
                    Example:
                    
                    @username
                    5999036520
                    """);

            // HISTORY

            case GET_HISTORY -> {

                TelegramUser targetUser = null;

                for (String part : parts) {

                    String value = part.replace("@", "");

                    if (value.matches("\\d+")) {

                        targetUser = telegramUserService.getUserByTelegramId(Long.parseLong(value));

                        if (targetUser != null) break;
                    }

                    targetUser = telegramUserService.getUserByUsername(value);

                    if (targetUser != null) break;
                }

                if (targetUser != null) {

                    showUserHistory(bot, chatId, targetUser, 0, null);

                    return;
                }

                sendMessage(bot, chatId, """
                        📜 SUBSCRIPTION HISTORY
                        
                        Usage:
                        
                        history @username
                        
                        OR
                        
                        history 5999036520
                        """);
            }

            // UPDATE USER

            case UPDATE_USER -> sendMessage(bot, chatId, """
                    ⚙️ UPDATE USER
                    
                    Examples:
                    
                    trial @username
                    
                    activate @username monthly
                    
                    activate @username yearly
                    
                    make admin @username
                    
                    expire @username
                    """);

            // UNKNOWN

            default -> sendMessage(bot, chatId, """
                    👑 OWNER PANEL
                    
                    /users
                    /userdetails
                    /activeusers
                    /expiredusers
                    /history
                    /updateuser
                    /usage
                    /stories
                    /syncstories
                    /deleteInactiveStory
                    """);
        }
    }

    // =========================================
    // SHOW USERS (OWNER)
    // =========================================

    private void showUsers(TelegramLongPollingBot bot, Long chatId, int page, Integer messageId) {

        try {

            int size = 10;

            Page<TelegramUser> users = telegramUserService.getUsers(page, size);

            StringBuilder builder = new StringBuilder();

            builder.append("👑 USERS MANAGEMENT PANEL\n\n");

            users.forEach(user -> {

                builder.append("🆔 ").append(user.getTelegramId()).append("\n");

                builder.append("👤 ");

                if (user.getUsername() != null) {

                    builder.append("@").append(user.getUsername());

                } else {

                    builder.append("No Username");
                }

                builder.append("\n");

                builder.append("🎭 Role : ").append(user.getRole()).append("\n");

                builder.append("🕒 Last Active : ").append(user.getLastActiveAt()).append("\n");

                builder.append("━━━━━━━━━━━━━━\n");
            });

            builder.append("\n");

            builder.append("📄 Page ").append(page + 1).append(" / ").append(users.getTotalPages());

            // =====================================
            // INLINE BUTTONS
            // =====================================

            List<InlineKeyboardButton> row = new ArrayList<>();

            if (page > 0) {

                InlineKeyboardButton previous = new InlineKeyboardButton();

                previous.setText("⬅️ Previous");

                previous.setCallbackData("users_" + (page - 1));

                row.add(previous);
            }

            InlineKeyboardButton indicator = new InlineKeyboardButton();

            indicator.setText((page + 1) + "/" + users.getTotalPages());

            indicator.setCallbackData("ignore");

            row.add(indicator);

            if (users.hasNext()) {

                InlineKeyboardButton next = new InlineKeyboardButton();

                next.setText("Next ➡️");

                next.setCallbackData("users_" + (page + 1));

                row.add(next);
            }

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(List.of(row));

            // =====================================
            // EDIT
            // =====================================

            if (messageId != null) {

                EditMessageText edit = new EditMessageText();

                edit.setChatId(String.valueOf(chatId));

                edit.setMessageId(messageId);

                edit.setText(builder.toString());

                edit.setReplyMarkup(keyboard);

                bot.execute(edit);

                return;
            }

            // =====================================
            // NEW MESSAGE
            // =====================================

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showUsers failed", e);
        }
    }

    // =========================================
    // SHOW USER HISTORY (OWNER)
    // =========================================

    private void showUserHistory(TelegramLongPollingBot bot, Long chatId, TelegramUser user, int page, Integer messageId) {

        try {

            List<Subscription> subscriptions = subscriptionService.getUserSubscriptionHistory(user);

            if (subscriptions == null || subscriptions.isEmpty()) {

                sendMessage(bot, chatId, "❌ No subscription history found");

                return;
            }

            int size = 3;

            int totalPages = (int) Math.ceil((double) subscriptions.size() / size);

            if (page < 0) page = 0;

            if (page >= totalPages) page = totalPages - 1;

            int start = page * size;

            int end = Math.min(start + size, subscriptions.size());

            List<Subscription> currentPage = subscriptions.subList(start, end);

            StringBuilder builder = new StringBuilder();

            builder.append("📜 USER SUBSCRIPTION HISTORY\n\n");

            builder.append("👤 User : ");

            if (user.getUsername() != null) {

                builder.append("@").append(user.getUsername());

            } else {

                builder.append(user.getTelegramId());
            }

            builder.append("\n");

            builder.append("━━━━━━━━━━━━━━\n\n");

            for (Subscription subscription : currentPage) {

                builder.append("📦 Plan : ").append(subscription.getPlan()).append("\n");

                builder.append("💰 Amount : ₹").append(subscription.getAmount()).append("\n");

                builder.append("📅 Start : ").append(subscription.getStartDate()).append("\n");

                builder.append("⏳ Expiry : ").append(subscription.getExpiryDate()).append("\n");

                builder.append("📌 Status : ").append(subscription.getStatus()).append("\n");

                builder.append("🎁 Trial : ").append(subscription.getTrial()).append("\n");

                builder.append("━━━━━━━━━━━━━━\n");
            }

            builder.append("\n");

            builder.append("📄 Page ").append(page + 1).append(" / ").append(totalPages);

            List<InlineKeyboardButton> row = new ArrayList<>();

            if (page > 0) {

                InlineKeyboardButton previous = new InlineKeyboardButton();

                previous.setText("⬅️ Previous");

                previous.setCallbackData("history_" + user.getTelegramId() + "_" + (page - 1));

                row.add(previous);
            }

            if (totalPages > 1) {

                InlineKeyboardButton indicator = new InlineKeyboardButton();

                indicator.setText((page + 1) + " / " + totalPages);

                indicator.setCallbackData("ignore");

                row.add(indicator);
            }

            if (page < totalPages - 1) {

                InlineKeyboardButton next = new InlineKeyboardButton();

                next.setText("Next ➡️");

                next.setCallbackData("history_" + user.getTelegramId() + "_" + (page + 1));

                row.add(next);
            }

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            if (!row.isEmpty()) {

                keyboard.setKeyboard(List.of(row));
            }

            if (messageId != null) {

                EditMessageText edit = new EditMessageText();

                edit.setChatId(String.valueOf(chatId));

                edit.setMessageId(messageId);

                edit.setText(builder.toString());

                if (!row.isEmpty()) {

                    edit.setReplyMarkup(keyboard);
                }

                bot.execute(edit);

                return;
            }

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            if (!row.isEmpty()) {

                sendMessage.setReplyMarkup(keyboard);
            }

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showUserHistory failed", e);
        }
    }

    // =========================================
    // STORIES
    // =========================================

    private void showStories(TelegramLongPollingBot bot, Long chatId, int page) {

        try {

            int size = 10;

            Page<Story> stories = storyService.getStories(page, size);

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, "❌ No stories");

                return;
            }

            StringBuilder builder = new StringBuilder();

            builder.append("""
                    📚 STORIES LIBRARY
                    
                    ✅ → Completed Stories
                    🔥 → Ongoing Stories
                    
                    """);

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            int count = 1;

            for (Story story : stories) {

                // =====================================
                // STORY STATUS ICON
                // =====================================

                String icon = "🔥";

                if (Boolean.TRUE.equals(story.getIsCompleted())) {

                    icon = "✅";
                }

                // =====================================
                // TEXT LIST
                // =====================================

                builder.append(count++).append(". ").append(icon).append(" ").append(story.getTitle()).append("\n");

                // =====================================
                // BUTTON
                // =====================================

                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setText(icon + " " + story.getTitle());

                button.setCallbackData("story_" + story.getId());

                rows.add(List.of(button));
            }

            // NAVIGATION

            List<InlineKeyboardButton> nav = new ArrayList<>();

            if (page > 0) {

                InlineKeyboardButton previous = new InlineKeyboardButton();

                previous.setText("⬅️ Prev");

                previous.setCallbackData("stories_" + (page - 1));

                nav.add(previous);
            }

            InlineKeyboardButton pageBtn = new InlineKeyboardButton();

            pageBtn.setText("📄 " + (page + 1) + "/" + stories.getTotalPages());

            pageBtn.setCallbackData("ignore");

            nav.add(pageBtn);

            if (stories.hasNext()) {

                InlineKeyboardButton next = new InlineKeyboardButton();

                next.setText("Next ➡️");

                next.setCallbackData("stories_" + (page + 1));

                nav.add(next);
            }

            rows.add(nav);

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(rows);

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showStories failed", e);
        }
    }

    // =========================================
    // EPISODE PACKS
    // =========================================

    private void showEpisodePacks(TelegramLongPollingBot bot, Long chatId, Long storyId, int page) {

        try {

            Story story = storyService.getStoryById(storyId);

            if (story == null) {

                sendMessage(bot, chatId, "❌ Story not found");

                return;
            }

            Integer latest = episodeService.getLatestEpisodeNumber(story);

            if (latest == null) {

                sendMessage(bot, chatId, "❌ No episodes");

                return;
            }

            StringBuilder builder = new StringBuilder();

            builder.append("🎧 ").append(story.getTitle()).append("\n\n");

            builder.append("📦 Episode Packs\n\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            // =====================================
            // ASC — EP 1 first
            // Page 0 → 1-100
            // Page 1 → 101-200
            // =====================================

            int startIndex = 1 + (page * 100);

            int packs = 0;

            for (int start = startIndex; start <= latest; start += 10) {

                int end = Math.min(start + 9, latest);

                builder.append("EP ").append(start).append("-").append(end).append("\n");

                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setText("🎵 EP " + start + "-" + end);

                button.setCallbackData("pack_" + storyId + "_" + start + "_" + end);

                rows.add(List.of(button));

                packs++;

                if (packs == 10) break;
            }

            // =====================================
            // NAVIGATION
            // =====================================

            List<InlineKeyboardButton> nav = new ArrayList<>();

            // PREVIOUS

            if (page > 0) {

                InlineKeyboardButton previous = new InlineKeyboardButton();

                previous.setText("⬅️ Prev");

                previous.setCallbackData("packs_" + storyId + "_" + (page - 1));

                nav.add(previous);
            }

            // SEARCH

            InlineKeyboardButton search = new InlineKeyboardButton();

            search.setText("🔍 Search");

            search.setCallbackData("search_" + storyId);

            nav.add(search);

            // NEXT — more packs available?

            if (startIndex + 100 <= latest) {

                InlineKeyboardButton next = new InlineKeyboardButton();

                next.setText("Next ➡️");

                next.setCallbackData("packs_" + storyId + "_" + (page + 1));

                nav.add(next);
            }

            rows.add(nav);

            // BACK

            InlineKeyboardButton back = new InlineKeyboardButton();

            back.setText("🔙 Stories");

            back.setCallbackData("stories_0");

            rows.add(List.of(back));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(rows);

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showEpisodePacks failed", e);
        }
    }

    // =========================================
    // INSIDE PACK
    // =========================================

    private void showEpisodesInsidePack(TelegramLongPollingBot bot, Long chatId, Long storyId, int start, int end) {

        try {

            Story story = storyService.getStoryById(storyId);

            List<Episode> episodes = episodeService.getEpisodesByRange(story, start, end);

            if (episodes.isEmpty()) {

                sendMessage(bot, chatId, "❌ No episodes");

                return;
            }

            StringBuilder builder = new StringBuilder();

            builder.append("🎧 ").append(story.getTitle()).append("\n\n");

            builder.append("📦 EP ").append(start).append("-").append(end).append("\n\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            for (Episode episode : episodes) {

                String epNo = episode.getEpisodeNo() != null ? episode.getEpisodeNo() : "?";

                builder.append("EP ").append(epNo).append("\n");

                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setText("🎵 EP " + epNo);

                button.setCallbackData("episode_" + episode.getId());

                rows.add(List.of(button));
            }

            InlineKeyboardButton back = new InlineKeyboardButton();

            back.setText("🔙 Back");

            back.setCallbackData("story_" + storyId);

            rows.add(List.of(back));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(rows);

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showEpisodesInsidePack failed", e);
        }
    }

    // =========================================
    // RANGE SEARCH
    // =========================================

    private void showEpisodesByRange(TelegramLongPollingBot bot, Long chatId, Story story, int start, int end) {

        try {

            List<Episode> episodes = episodeService.getEpisodesByRange(story, start, end);

            if (episodes.isEmpty()) {

                sendMessage(bot, chatId, "❌ No episodes found");

                return;
            }

            StringBuilder builder = new StringBuilder();

            builder.append("🎧 ").append(story.getTitle()).append("\n\n");

            builder.append("🔍 SEARCH RESULT\n\n");

            builder.append("EP ").append(start).append("-").append(end).append("\n\n");

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            for (Episode episode : episodes) {

                String epNo = episode.getEpisodeNo();

                builder.append("EP ").append(epNo).append("\n");

                InlineKeyboardButton button = new InlineKeyboardButton();

                button.setText("🎵 EP " + epNo);

                button.setCallbackData("episode_" + episode.getId());

                rows.add(List.of(button));
            }

            // =====================================
            // SEARCH AGAIN + BACK — same row
            // =====================================

            InlineKeyboardButton searchAgain = new InlineKeyboardButton();

            searchAgain.setText("🔍 Search Again");

            searchAgain.setCallbackData("search_" + story.getId());

            InlineKeyboardButton back = new InlineKeyboardButton();

            back.setText("🔙 Stories");

            back.setCallbackData("stories_0");

            rows.add(List.of(searchAgain, back));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(rows);

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText(builder.toString());

            sendMessage.setReplyMarkup(keyboard);

            bot.execute(sendMessage);

        } catch (Exception e) {

            log.error("showEpisodesByRange failed", e);
        }
    }

    // =========================================
    // SEND EPISODE AUDIO
    // =========================================

    private void sendEpisodeAudio(TelegramLongPollingBot bot, Long chatId, Long episodeId) {

        try {

            Episode episode = episodeService.getEpisodeById(episodeId);

            if (episode == null) {

                sendMessage(bot, chatId, "❌ Episode not found");

                return;
            }

            TelegramUser user = telegramUserService.getUserByTelegramId(chatId);

            SendAudio sendAudio = new SendAudio();

            sendAudio.setChatId(String.valueOf(chatId));

            sendAudio.setAudio(new InputFile(episode.getTelegramFileId()));

            sendAudio.setCaption("🎧 EP " + episode.getEpisodeNo());

            // =====================================
            // PREVENT FORWARD FOR NORMAL USERS
            // OWNER & ADMIN CAN FORWARD
            // =====================================

            sendAudio.setProtectContent(shouldProtectContent(user));

            bot.execute(sendAudio);

        } catch (Exception e) {

            log.error("sendEpisodeAudio failed", e);
        }
    }

    // =========================================
    // SEND MESSAGE
    // =========================================

    private Message sendMessage(TelegramLongPollingBot bot, Long chatId, String text) throws Exception {

        SendMessage sendMessage = new SendMessage();

        sendMessage.setChatId(String.valueOf(chatId));

        sendMessage.setText(text);

        TelegramUser user = telegramUserService.getUserByTelegramId(chatId);

        sendMessage.setProtectContent(user == null || user.getRole() != UserRole.OWNER);

        return bot.execute(sendMessage);
    }

    // =========================================
    // AUTO DELETE
    // =========================================

    private void autoDeleteMessages(TelegramLongPollingBot bot, Long chatId, Integer userMessageId, Integer botMessageId) {

        scheduler.schedule(() -> {

            try {

                bot.execute(new DeleteMessage(String.valueOf(chatId), userMessageId));

                bot.execute(new DeleteMessage(String.valueOf(chatId), botMessageId));

            } catch (Exception e) {

                log.error("Auto delete failed", e);
            }

        }, 48, TimeUnit.HOURS); // MAX 48 hours — Telegram limit
    }

    // =========================================
    // CONTENT PROTECTION
    // OWNER & ADMIN -> Forward Allowed
    // OTHERS -> Forward Blocked
    // =========================================

    private boolean shouldProtectContent(TelegramUser user) {

        if (user == null) {
            return true;
        }

        return user.getRole() != UserRole.OWNER && user.getRole() != UserRole.ADMIN;
    }

    private void syncStories(TelegramLongPollingBot bot,
                             Long chatId) {

        try {

            List<Story> stories =
                    storyService.getAllStories();

            if (stories.isEmpty()) {

                sendMessage(bot,
                        chatId,
                        "❌ No stories found.");

                return;
            }

            int updated = 0;
            int deleted = 0;

            for (Story story : stories) {

                try {

                    GetChat getChat =
                            new GetChat(
                                    String.valueOf(
                                            story.getTelegramChatId()));

                    var chat =
                            bot.execute(getChat);

                    boolean completed =
                            chat.getDescription() != null &&
                                    chat.getDescription()
                                            .toLowerCase()
                                            .contains("completed");

                    story.setTitle(chat.getTitle());

                    story.setTelegramUsername(
                            chat.getUserName());

                    story.setChatType(
                            chat.getType());

                    story.setDescription(
                            chat.getDescription());

                    story.setInviteLink(
                            chat.getInviteLink());

                    story.setIsCompleted(
                            completed);

                    story.setActive(true);

                    storyService.save(story);

                    updated++;

                } catch (Exception ex) {

                    log.warn(
                            "Channel deleted/not accessible chatId={}",
                            story.getTelegramChatId());

                    story.setActive(false);

                    storyService.save(story);

                    deleted++;
                }
            }

            sendMessage(bot,
                    chatId,
                    """
                    ✅ Story Sync Completed
    
                    📚 Total Stories : %d
                    ✅ Updated       : %d
                    ❌ Inactive      : %d
                    """
                            .formatted(
                                    stories.size(),
                                    updated,
                                    deleted));

        } catch (Exception e) {

            log.error("syncStories failed", e);

            try {

                sendMessage(bot,
                        chatId,
                        "❌ Sync failed.");

            } catch (Exception ignore) {

            }
        }
    }

    private void deleteInactiveStories(TelegramLongPollingBot bot,
                                       Long chatId) {

        try {

            List<Story> stories = storyService.getInactiveStories();

            if (stories.isEmpty()) {

                sendMessage(bot,
                        chatId,
                        """
                        ✅ No inactive stories found.
                        """);

                return;
            }

            int deleted = 0;

            for (Story story : stories) {

                log.info("Deleting inactive story={}", story.getTitle());

                storyService.deleteStory(story);

                deleted++;
            }

            sendMessage(bot,
                    chatId,
                    """
                    ✅ Inactive Story Cleanup Completed
    
                    🗑 Deleted Stories : %d
                    """
                            .formatted(deleted));

        } catch (Exception e) {

            log.error("deleteInactiveStories failed", e);

            try {

                sendMessage(bot,
                        chatId,
                        "❌ Failed to delete inactive stories.");

            } catch (Exception ignore) {

            }
        }
    }
}