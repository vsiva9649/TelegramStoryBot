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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final GlobalTrialService globalTrialService;

    private final GroqService groqService;

    private final StoryService storyService;

    private final EpisodeService episodeService;
    private final Map<Long, Long> searchStoryContext = new ConcurrentHashMap<>();
    private final Set<Long> activeEpisodeBatches = ConcurrentHashMap.newKeySet();

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
                    
                    Your free access or subscription
                    is currently unavailable.
                    
                    To continue listening to stories,
                    please activate a subscription.
                    
                    👑 Please Contact Admin
                    """);

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

            // =====================================
            // COMPLETE ACCESS CHECK
            //
            // OWNER / ADMIN
            // MANUAL FREE TRIAL
            // MONTHLY
            // YEARLY
            // LIFETIME
            // GLOBAL FREE TRIAL
            // =====================================

            boolean active = subscriptionService.hasAccess(telegramUser);

            Integer userMessageId = message.getMessageId();

            if (!active) {

                // Never keep an old selected story after access ends.
                // If a global trial is turned back ON later, the user
                // must explicitly select a story again.
                searchStoryContext.remove(chatId);

                sendSubscriptionRequiredMessage(bot, chatId, userMessageId);

                return;
            }

            // =====================================
            // CUSTOM EPISODE RANGE SEARCH
            //
            // IMPORTANT:
            // This is checked before OWNER commands so
            // OWNER can also type ranges like 1-50.
            // =====================================

            if (text.matches("\\d+\\s*-\\s*\\d+")) {

                handleEpisodeRangeSearch(bot, chatId, telegramUser, text);

                return;
            }

            // =====================================
            // USER-FACING STORY MENU ACTIONS
            //
            // These must work for USER, ADMIN and OWNER.
            // In particular, OWNER can use the same story/search
            // flow to verify forwarding and episode delivery.
            // =====================================

            if (text.equalsIgnoreCase("🏠 Main Menu")) {
                showMainMenu(bot, chatId);
                return;
            }

            if (text.equalsIgnoreCase("Tamil Stories")) {
                showTamilMenu(bot, chatId);
                return;
            }

            if (text.equalsIgnoreCase("Hindi Stories")) {
                searchStoryContext.remove(chatId);
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
            // OWNER MANAGEMENT COMMANDS
            // =====================================

            if (telegramUser.getRole() == UserRole.OWNER) {

                // A management command exits any previously selected
                // episode-search story. A range is handled above, so
                // repeated range searches still work while in search mode.
                searchStoryContext.remove(chatId);

                handleOwnerCommands(bot, chatId, text);

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


            // Episode range input is handled above for USER, ADMIN and OWNER.

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

            // Main menu means the previous episode search session is over.
            searchStoryContext.remove(chatId);

            TelegramUser user = telegramUserService.getUserByTelegramId(chatId);

            String trialInfo = "";

            if (user != null && !subscriptionService.hasActiveSubscription(user) && globalTrialService.hasGlobalTrialAccess(user)) {

                trialInfo = globalTrialService.getUserTrialExpiry(user).map(expiry -> """
                        
                        🎁 Free Trial Active
                        ⏳ Valid Until:
                        %s
                        """.formatted(expiry)).orElse("");
            }

            SendMessage sendMessage = new SendMessage();

            sendMessage.setChatId(String.valueOf(chatId));

            sendMessage.setText("""
                    🇮🇳 Welcome To Story Bot 🇮🇳
                    
                    📚 Your Story Adventure Starts Here
                    
                    Select your option from below 👇
                    %s
                    """.formatted(trialInfo));

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

            searchStoryContext.remove(chatId);

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

            searchStoryContext.remove(chatId);

            sendMessage(bot, chatId, """
                    ☎️ HELP & SUPPORT
                    
                    👑 Please Contact Admin
                    
                    💬 Contact Admin for:
                    
                    • Subscription
                    • Support
                    • Episode Issues
                    • Story Requests
                    """);

        } catch (Exception e) {

            log.error("showHelpMenu failed", e);
        }
    }

    private void showCompletedStories(TelegramLongPollingBot bot, Long chatId, int page) {

        try {

            searchStoryContext.remove(chatId);

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

            searchStoryContext.remove(chatId);

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


            TelegramUser user;

            // Refresh user metadata/role on button clicks too.
            // This also guarantees the configured owner ID is restored
            // to OWNER before access and forwarding checks.
            if (callbackQuery.getFrom() != null) {
                user = telegramUserService.saveOrUpdateUser(callbackQuery.getFrom(), chatId);
            } else {
                user = telegramUserService.getUserByTelegramId(chatId);
            }

            // =====================================
            // COMPLETE CALLBACK ACCESS CHECK
            // =====================================

            if (user == null) {

                sendSubscriptionRequiredMessage(bot, chatId, messageId);

                return;
            }

            boolean active = subscriptionService.hasAccess(user);

            if (!active) {

                searchStoryContext.remove(chatId);

                sendSubscriptionRequiredMessage(bot, chatId, messageId);

                return;
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
            // ACTIVE USERS PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("activeusers_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("activeusers_", ""));
                showUsersByAccessStatus(bot, chatId, true, page, messageId);
                return;
            }

            // =====================================
            // EXPIRED USERS PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("expiredusers_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("expiredusers_", ""));
                showUsersByAccessStatus(bot, chatId, false, page, messageId);
                return;
            }

            // =====================================
            // USERS PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("users_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("users_", ""));

                showUsers(bot, chatId, page, messageId);

                return;
            }

            // =====================================
            // HISTORY PAGINATION (OWNER)
            // =====================================

            if (data.startsWith("history_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

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

                // /stories is the OWNER library and can include inactive stories.
                // Never expose it to a normal USER/ADMIN through an old button.
                if (user.getRole() != UserRole.OWNER) {
                    showTamilMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("stories_", ""));

                showStories(bot, chatId, page);

                return;
            }

            // =====================================
            // STORY OPEN -> DIRECT CUSTOM SEARCH
            //
            // No episode pack/list screen is shown.
            // =====================================

            if (data.startsWith("story_")) {

                Long storyId = Long.parseLong(data.replace("story_", ""));

                openEpisodeSearch(bot, chatId, user, storyId);

                return;
            }

            // =====================================
            // OLD PACK CALLBACK COMPATIBILITY
            //
            // Existing Telegram messages may still
            // contain old pack buttons. Redirect them
            // to the new custom search instead.
            // =====================================

            if (data.startsWith("packs_") || data.startsWith("pack_")) {

                String[] split = data.split("_");

                Long storyId = Long.parseLong(split[1]);

                openEpisodeSearch(bot, chatId, user, storyId);

                return;
            }

            // =====================================
            // SEARCH AGAIN
            // =====================================

            if (data.startsWith("search_")) {

                Long storyId = Long.parseLong(data.replace("search_", ""));

                openEpisodeSearch(bot, chatId, user, storyId);

                return;
            }

            // =====================================
            // OLD SINGLE-EPISODE CALLBACK SUPPORT
            // =====================================

            if (data.startsWith("episode_")) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, """
                        ℹ️ Episode list buttons are no longer used.

                        Please select a story and enter a custom range
                        such as 1-50.
                        """);

                showTamilMenu(bot, chatId);

                return;
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

        String lowerText = text.toLowerCase().trim();

        String ownerCommand = normalizeOwnerCommand(text);

        String cleanedText = text.replace("@", "");

        String[] parts = cleanedText.split("\\s+");

        // =====================================
        // USAGE GUIDE
        // =====================================

        // =====================================
        // START
        // =====================================

        if ("/start".equals(ownerCommand)) {

            sendMessage(bot, chatId, """
                    👑 Welcome, Owner!
                    
                    🤖 StoryBot is ready.
                    
                    ━━━━━━━━━━━━━━
                    📚 FEATURES
                    ━━━━━━━━━━━━━━
                    
                    🎧 Audio Story Streaming
                       Users can listen to stories
                       directly inside Telegram
                    
                    🔍 Custom Episode Search
                       Select a story and enter a range
                       Example: 20-50
                       Maximum 50 episodes per search
                    
                    💳 Subscription System
                       FREE
                       MONTHLY
                       YEARLY
                       LIFETIME
                    
                    🌍 Global Free Trial
                       Enable free access for
                       all eligible users
                    
                    🧭 Owner Command Routing
                       Local-first, optional AI fallback
                    
                    ━━━━━━━━━━━━━━
                    👑 OWNER COMMANDS
                    ━━━━━━━━━━━━━━
                    
                    /stories
                    /usage
                    
                    /trailonsubscription 2026-08-31
                    
                    /trailoffsubscription
                    
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

        if ("/usage".equals(ownerCommand)) {

            sendMessage(bot, chatId, """
                    👑 OWNER USAGE GUIDE
                    
                    ━━━━━━━━━━━━━━
                    🌍 GLOBAL FREE TRIAL
                    ━━━━━━━━━━━━━━
                    
                    Enable:
                    
                    /trailonsubscription 2026-08-31
                    
                    Disable:
                    
                    /trailoffsubscription
                    
                    Global trial rules:
                    
                    • Maximum 7 days per user
                    
                    • Existing users start from
                      global campaign start time
                    
                    • New users start from
                      their joinedAt time
                    
                    • User trial never exceeds
                      global campaign end date
                    
                    • Paid subscriptions continue
                      independently
                    
                    • Manual user trials continue
                      independently
                    
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
                    🎁 INDIVIDUAL FREE TRIAL
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
                    
                    /deleteinactivestory
                    """);

            return;
        }

        // =====================================
        // CORE OWNER PANEL COMMANDS
        //
        // These are deterministic and MUST NOT depend on Groq.
        // Telegram can send /command@BotUsername, so ownerCommand
        // is normalized before matching.
        // =====================================

        if ("/users".equals(ownerCommand)) {
            showUsers(bot, chatId, 0, null);
            return;
        }

        if ("/activeusers".equals(ownerCommand)) {
            showUsersByAccessStatus(bot, chatId, true, 0, null);
            return;
        }

        if ("/expiredusers".equals(ownerCommand)) {
            showUsersByAccessStatus(bot, chatId, false, 0, null);
            return;
        }

        if ("/userdetails".equals(ownerCommand)) {

            TelegramUser targetUser = resolveOwnerTargetUser(text);

            if (targetUser == null) {
                sendUserDetailsHelp(bot, chatId);
            } else {
                showOwnerUserDetails(bot, chatId, targetUser);
            }

            return;
        }

        if ("/history".equals(ownerCommand)) {

            TelegramUser targetUser = resolveOwnerTargetUser(text);

            if (targetUser == null) {
                sendHistoryHelp(bot, chatId);
            } else {
                showUserHistory(bot, chatId, targetUser, 0, null);
            }

            return;
        }

        if ("/updateuser".equals(ownerCommand)) {
            sendUpdateUserHelp(bot, chatId);
            return;
        }

        // =====================================
        // STORIES
        // =====================================

        if ("/stories".equals(ownerCommand)) {

            showStories(bot, chatId, 0);

            return;
        }

        if ("/syncstories".equals(ownerCommand)) {

            syncStories(bot, chatId);

            return;
        }

        if ("/deleteinactivestory".equals(ownerCommand)) {

            deleteInactiveStories(bot, chatId);

            return;
        }


        // =====================================
        // GLOBAL FREE TRIAL ON
        //
        // Example:
        //
        // /trailonsubscription 2026-08-31
        // =====================================

        if ("/trailonsubscription".equals(ownerCommand)) {

            // =====================================
            // OWNER ONLY
            // =====================================

            if (!chatId.equals(telegramConfig.getOwnerId())) {

                sendMessage(bot, chatId, """
                        ❌ Access Denied
                        
                        Only Bot Owner can enable
                        Global Free Trial.
                        """);

                return;
            }

            String[] commandParts = text.trim().split("\\s+");

            // =====================================
            // REQUIRED:
            //
            // command + endDate
            // =====================================

            if (commandParts.length != 2) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Command
                        
                        Usage:
                        
                        /trailonsubscription 2026-08-31
                        
                        📅 Date format:
                        
                        yyyy-MM-dd
                        """);

                return;
            }

            try {

                LocalDate endDate = LocalDate.parse(commandParts[1]);

                // campaign valid until
                // end of selected day

                LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

                var globalTrial = globalTrialService.enableGlobalTrial(endDateTime, chatId);

                sendMessage(bot, chatId, """
                        ✅ GLOBAL FREE TRIAL ENABLED
                        
                        🎁 Trial Per User:
                        %d Days
                        
                        📅 Campaign Start:
                        %s
                        
                        ⏳ Campaign End:
                        %s
                        
                        ━━━━━━━━━━━━━━
                        
                        👥 Existing Users
                        
                        Trial starts from
                        campaign start time.
                        
                        🆕 New Users
                        
                        Trial starts from
                        user joinedAt time.
                        
                        ⚠️ User trial will never
                        exceed campaign end date.
                        
                        ✅ Paid subscriptions continue
                        with normal validity.
                        """.formatted(globalTrial.getTrialDays(),

                        globalTrial.getStartDate(),

                        globalTrial.getEndDate()));

            } catch (DateTimeParseException e) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Date Format
                        
                        Correct example:
                        
                        /trailonsubscription 2026-08-31
                        """);

            } catch (IllegalArgumentException e) {

                sendMessage(bot, chatId, """
                        ❌ Cannot Enable Global Trial
                        
                        %s
                        """.formatted(e.getMessage()));
            }

            return;
        }

        // =====================================
        // GLOBAL FREE TRIAL OFF
        // =====================================

        if ("/trailoffsubscription".equals(ownerCommand)) {

            if (!chatId.equals(telegramConfig.getOwnerId())) {

                sendMessage(bot, chatId, """
                        ❌ Access Denied
                        
                        Only Bot Owner can disable
                        Global Free Trial.
                        """);

                return;
            }

            boolean disabled = globalTrialService.disableGlobalTrial();

            if (!disabled) {

                sendMessage(bot, chatId, """
                        ℹ️ GLOBAL FREE TRIAL
                        
                        No active global free
                        trial is currently running.
                        """);

                return;
            }

            sendMessage(bot, chatId, """
                    ⛔ GLOBAL FREE TRIAL DISABLED
                    
                    Global free access has stopped.
                    
                    ✅ Monthly subscriptions
                    continue normally.
                    
                    ✅ Yearly subscriptions
                    continue normally.
                    
                    ✅ Lifetime subscriptions
                    continue normally.
                    
                    ✅ Individual manual trials
                    continue until their own expiry.
                    
                    🔒 Other users now require
                    an active subscription.
                    """);

            return;
        }

        // =====================================
        // UPDATE USER / SUBSCRIPTION
        // =====================================

        boolean wantsTrial = containsOwnerKeyword(text, "trial") || containsOwnerKeyword(text, "trail");
        boolean wantsActivate = containsOwnerKeyword(text, "activate");
        boolean wantsAdmin = containsOwnerKeyword(text, "admin");
        boolean wantsExpire = containsOwnerKeyword(text, "expire");

        if (wantsTrial || wantsActivate || wantsAdmin || wantsExpire) {

            TelegramUser targetUser = null;

            for (String part : parts) {

                String value = part.toLowerCase().replace("@", "");

                // SKIP RESERVED WORDS

                if (value.equals("trial") || value.equals("trail") || value.equals("activate") || value.equals("admin") || value.equals("expire") || value.equals("monthly") || value.equals("yearly") || value.equals("lifetime") || value.equals("make") || value.equals("history") || value.equals("show") || value.equals("subscription")) {

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

            if (wantsAdmin) {

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

            // =====================================
            // INDIVIDUAL FREE TRIAL
            // =====================================

            if (wantsTrial) {

                int trialDays = 7;

                for (int i = 0; i < parts.length; i++) {

                    String part = parts[i];

                    if (!part.matches("\\d+")) {
                        continue;
                    }

                    long numericValue;

                    try {

                        numericValue = Long.parseLong(part);

                    } catch (NumberFormatException e) {

                        continue;
                    }

                    // Telegram ID value - don't use as trial days
                    if (targetUser.getTelegramId() != null && numericValue == targetUser.getTelegramId()) {

                        continue;
                    }

                    // Remaining small numeric value can be trial days
                    if (numericValue >= 1 && numericValue <= 365) {

                        trialDays = (int) numericValue;

                        break;
                    }
                }

                if (trialDays <= 0 || trialDays > 365) {

                    sendMessage(bot, chatId, """
                            ❌ Invalid Trial Days
                            
                            Allowed range:
                            
                            1 - 365 days
                            """);

                    return;
                }

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.FREE, BillingType.MONTHLY, BigDecimal.ZERO, trialDays);

                sendMessage(bot, chatId, """
                        ✅ FREE TRIAL ACTIVATED
                        
                        👤 User:
                        @%s
                        
                        🎁 Plan:
                        FREE
                        
                        ⏳ Validity:
                        %s Days
                        """.formatted(targetUser.getUsername(), trialDays));

                return;
            }

            // MONTHLY

            if (containsOwnerKeyword(text, "monthly")) {

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

            if (containsOwnerKeyword(text, "yearly")) {

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

            if (containsOwnerKeyword(text, "lifetime")) {

                subscriptionService.createOrUpdateSubscription(targetUser, SubscriptionPlan.LIFETIME, BillingType.LIFETIME, new BigDecimal("4999"), 36500);

                sendMessage(bot, chatId, """
                        ✅ LIFETIME PLAN ACTIVATED
                        
                        👤 User :
                        @%s
                        """.formatted(targetUser.getUsername()));

                return;
            }

            // EXPIRE

            if (wantsExpire) {

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
        // LOCAL-FIRST / OPTIONAL GROQ INTENT FALLBACK
        //
        // GroqService checks deterministic local rules first.
        // The network is used only for wording that local rules
        // cannot classify, so an AI outage cannot break commands.
        // =====================================

        OwnerIntent intent = groqService.detectIntent(text);

        log.info("Detected owner intent={}", intent);

        switch (intent) {

            case GET_USERS -> showUsers(bot, chatId, 0, null);

            case GET_ACTIVE_USERS -> showUsersByAccessStatus(bot, chatId, true, 0, null);

            case GET_EXPIRED_USERS -> showUsersByAccessStatus(bot, chatId, false, 0, null);

            case GET_USER_DETAILS -> {

                TelegramUser targetUser = resolveOwnerTargetUser(text);

                if (targetUser == null) {
                    sendUserDetailsHelp(bot, chatId);
                } else {
                    showOwnerUserDetails(bot, chatId, targetUser);
                }
            }

            case GET_HISTORY -> {

                TelegramUser targetUser = resolveOwnerTargetUser(text);

                if (targetUser == null) {
                    sendHistoryHelp(bot, chatId);
                } else {
                    showUserHistory(bot, chatId, targetUser, 0, null);
                }
            }

            case UPDATE_USER -> sendUpdateUserHelp(bot, chatId);

            case GLOBAL_TRIAL_ON -> sendMessage(bot, chatId, """
                    🌍 GLOBAL FREE TRIAL

                    Please provide campaign end date.

                    Usage:
                    /trailonsubscription 2026-08-31

                    Every eligible user receives maximum 7 days free access.
                    User access never exceeds the campaign end date.
                    """);

            case GLOBAL_TRIAL_OFF -> {

                boolean disabled = globalTrialService.disableGlobalTrial();

                if (disabled) {
                    sendMessage(bot, chatId, """
                            ⛔ GLOBAL FREE TRIAL DISABLED

                            Global free access stopped.
                            Paid subscriptions and individual manual trials remain active.
                            """);
                } else {
                    sendMessage(bot, chatId, """
                            ℹ️ No active global free trial found.
                            """);
                }
            }

            default -> sendOwnerPanel(bot, chatId);
        }
    }

    // =========================================
    // OWNER COMMAND HELPERS
    // =========================================

    private boolean containsOwnerKeyword(String text, String keyword) {

        if (text == null || text.isBlank() || keyword == null || keyword.isBlank()) {
            return false;
        }

        for (String rawToken : text.trim().split("\\s+")) {

            if (rawToken == null || rawToken.isBlank()) {
                continue;
            }

            String token = rawToken.trim().toLowerCase();

            // @username is always a target value, never a command keyword.
            if (token.startsWith("@")) {
                continue;
            }

            if (token.startsWith("/")) {
                token = token.substring(1);
            }

            int botMentionIndex = token.indexOf('@');
            if (botMentionIndex > 0) {
                token = token.substring(0, botMentionIndex);
            }

            token = token.replaceAll("^[,.:;]+|[,.:;]+$", "");

            if (token.equals(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private String normalizeOwnerCommand(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        String firstToken = text.trim().toLowerCase().split("\\s+", 2)[0];

        int botMentionIndex = firstToken.indexOf('@');

        if (botMentionIndex > 0) {
            firstToken = firstToken.substring(0, botMentionIndex);
        }

        return firstToken;
    }

    private TelegramUser resolveOwnerTargetUser(String text) {

        if (text == null || text.isBlank()) {
            return null;
        }

        Set<String> reservedWords = Set.of(
                "show", "get", "find", "user", "users", "details", "detail",
                "history", "subscription", "subscriptions", "payment", "payments",
                "activate", "trial", "trail", "monthly", "yearly", "lifetime",
                "make", "admin", "expire", "expired", "active", "inactive",
                "update", "role", "plan", "please", "for", "of"
        );

        for (String rawPart : text.trim().split("\\s+")) {

            if (rawPart == null || rawPart.isBlank()) {
                continue;
            }

            String value = rawPart.trim();

            if (value.startsWith("/")) {
                continue;
            }

            value = value.replace("@", "").replaceAll("^[,.:;]+|[,.:;]+$", "");

            if (value.isBlank()) {
                continue;
            }

            if (value.matches("\\d+")) {

                try {
                    TelegramUser user = telegramUserService.getUserByTelegramId(Long.parseLong(value));
                    if (user != null) {
                        return user;
                    }
                } catch (NumberFormatException ignored) {
                    // Continue with the next token.
                }
            }

            String lowerValue = value.toLowerCase();

            if (reservedWords.contains(lowerValue)) {
                continue;
            }

            TelegramUser user = telegramUserService.getUserByUsername(value);

            if (user != null) {
                return user;
            }
        }

        return null;
    }

    private void sendUserDetailsHelp(TelegramLongPollingBot bot, Long chatId) throws Exception {

        sendMessage(bot, chatId, """
                👤 USER DETAILS

                Usage:
                /userdetails @username
                /userdetails 5999036520

                You can also type:
                show user @username
                """);
    }

    private void sendHistoryHelp(TelegramLongPollingBot bot, Long chatId) throws Exception {

        sendMessage(bot, chatId, """
                📜 SUBSCRIPTION HISTORY

                Usage:
                /history @username
                /history 5999036520

                You can also type:
                history @username
                """);
    }

    private void sendUpdateUserHelp(TelegramLongPollingBot bot, Long chatId) throws Exception {

        sendMessage(bot, chatId, """
                ⚙️ UPDATE USER

                Individual free trial:
                trial @username
                trial @username 15

                Paid plans:
                activate @username monthly
                activate @username yearly
                activate @username lifetime

                Role:
                make admin @username

                Expire current access:
                expire @username
                """);
    }

    private void sendOwnerPanel(TelegramLongPollingBot bot, Long chatId) throws Exception {

        sendMessage(bot, chatId, """
                👑 OWNER PANEL

                👥 Users
                /users
                /userdetails @username
                /activeusers
                /expiredusers

                💳 Subscription
                /history @username
                /updateuser

                🌍 Global Free Trial
                /trailonsubscription 2026-08-31
                /trailoffsubscription

                📚 Stories
                /stories
                /syncstories
                /deleteinactivestory

                ℹ️ Help
                /usage
                """);
    }

    private void showOwnerUserDetails(TelegramLongPollingBot bot, Long chatId, TelegramUser user) throws Exception {

        if (user == null) {
            sendUserDetailsHelp(bot, chatId);
            return;
        }

        boolean subscriptionActive = subscriptionService.hasActiveSubscription(user);
        boolean globalTrialActive = !subscriptionActive && globalTrialService.hasGlobalTrialAccess(user);

        String accessSource;

        if (user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN) {
            accessSource = "ROLE BYPASS";
        } else if (subscriptionActive) {
            accessSource = "SUBSCRIPTION";
        } else if (globalTrialActive) {
            accessSource = "GLOBAL TRIAL";
        } else {
            accessSource = "NO ACCESS";
        }

        List<Subscription> history = subscriptionService.getUserSubscriptionHistory(user);
        Subscription latest = history.isEmpty() ? null : history.get(0);

        String username = user.getUsername() == null || user.getUsername().isBlank()
                ? "No Username"
                : "@" + user.getUsername();

        String latestPlan = latest == null ? "-" : String.valueOf(latest.getPlan());
        String latestStatus = latest == null ? "-" : String.valueOf(latest.getStatus());
        String latestStart = latest == null ? "-" : String.valueOf(latest.getStartDate());
        String latestExpiry = latest == null ? "-" : String.valueOf(latest.getExpiryDate());

        sendMessage(bot, chatId, """
                👤 USER DETAILS

                🆔 Telegram ID: %s
                👤 Username: %s
                📝 Name: %s %s
                🎭 Role: %s

                🔐 Current Access: %s

                📦 Latest Plan: %s
                📌 Latest Status: %s
                📅 Start: %s
                ⏳ Expiry: %s

                🕒 Joined: %s
                🕒 Last Active: %s
                """.formatted(
                user.getTelegramId(),
                username,
                user.getFirstName() == null ? "" : user.getFirstName(),
                user.getLastName() == null ? "" : user.getLastName(),
                user.getRole(),
                accessSource,
                latestPlan,
                latestStatus,
                latestStart,
                latestExpiry,
                user.getJoinedAt(),
                user.getLastActiveAt()
        ));
    }

    private void showUsersByAccessStatus(
            TelegramLongPollingBot bot,
            Long chatId,
            boolean activeAccess,
            int page,
            Integer messageId) {

        try {

            int size = 10;

            List<TelegramUser> filteredUsers = telegramUserService.getAllNonOwnerUsers()
                    .stream()
                    .filter(user -> subscriptionService.hasAccess(user) == activeAccess)
                    .toList();

            if (filteredUsers.isEmpty()) {

                sendMessage(bot, chatId, activeAccess
                        ? "✅ No active users found."
                        : "✅ No expired/no-access users found.");

                return;
            }

            int totalPages = (int) Math.ceil((double) filteredUsers.size() / size);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int start = safePage * size;
            int end = Math.min(start + size, filteredUsers.size());

            StringBuilder builder = new StringBuilder();

            builder.append(activeAccess
                    ? "✅ ACTIVE USERS\n\n"
                    : "⛔ EXPIRED / NO-ACCESS USERS\n\n");

            for (TelegramUser user : filteredUsers.subList(start, end)) {

                builder.append("🆔 ").append(user.getTelegramId()).append("\n");
                builder.append("👤 ");
                builder.append(user.getUsername() == null || user.getUsername().isBlank()
                        ? "No Username"
                        : "@" + user.getUsername());
                builder.append("\n");
                builder.append("🎭 Role: ").append(user.getRole()).append("\n");
                builder.append("🕒 Last Active: ").append(user.getLastActiveAt()).append("\n");
                builder.append("━━━━━━━━━━━━━━\n");
            }

            builder.append("\n📄 Page ")
                    .append(safePage + 1)
                    .append(" / ")
                    .append(totalPages);

            String callbackPrefix = activeAccess ? "activeusers_" : "expiredusers_";
            List<InlineKeyboardButton> row = new ArrayList<>();

            if (safePage > 0) {
                InlineKeyboardButton previous = new InlineKeyboardButton();
                previous.setText("⬅️ Previous");
                previous.setCallbackData(callbackPrefix + (safePage - 1));
                row.add(previous);
            }

            InlineKeyboardButton indicator = new InlineKeyboardButton();
            indicator.setText((safePage + 1) + "/" + totalPages);
            indicator.setCallbackData("ignore");
            row.add(indicator);

            if (safePage < totalPages - 1) {
                InlineKeyboardButton next = new InlineKeyboardButton();
                next.setText("Next ➡️");
                next.setCallbackData(callbackPrefix + (safePage + 1));
                row.add(next);
            }

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(row));

            if (messageId != null) {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(messageId);
                edit.setText(builder.toString());
                edit.setReplyMarkup(keyboard);
                bot.execute(edit);
                return;
            }

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText(builder.toString());
            sendMessage.setReplyMarkup(keyboard);
            sendMessage.setProtectContent(true);
            bot.execute(sendMessage);

        } catch (Exception e) {
            log.error("showUsersByAccessStatus failed activeAccess={} page={}", activeAccess, page, e);
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

            searchStoryContext.remove(chatId);

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
    // OPEN CUSTOM EPISODE SEARCH
    //
    // Story selection goes directly here.
    // No episode pack/list is shown.
    // =========================================

    private void openEpisodeSearch(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser requestingUser,
            Long storyId
    ) {

        try {

            Story story = storyService.getStoryById(storyId);

            if (story == null) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, "❌ Story not found");

                return;
            }

            // Normal USER/ADMIN must not reopen an inactive story through
            // a stale Telegram button. OWNER can still inspect it from
            // the owner /stories library.
            boolean owner = requestingUser != null && requestingUser.getRole() == UserRole.OWNER;

            if (!owner && !Boolean.TRUE.equals(story.getActive())) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, """
                        ❌ This story is currently unavailable.

                        Please choose another active story.
                        """);

                return;
            }

            Integer latestEpisode = episodeService.getLatestEpisodeNumber(story);

            if (latestEpisode == null) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, "❌ No episodes available for this story");

                return;
            }

            searchStoryContext.put(chatId, storyId);

            sendMessage(bot, chatId, """
                    🎧 %s

                    🔍 Custom Episode Search

                    Available up to: EP %d

                    Enter the episode range you need.

                    Examples:
                    1-50
                    51-100
                    120-150

                    ⚠️ Maximum 50 episodes per search.
                    🎵 Audio files will be sent directly.
                    """.formatted(story.getTitle(), latestEpisode));

        } catch (Exception e) {

            log.error("openEpisodeSearch failed storyId={}", storyId, e);

            try {
                sendMessage(bot, chatId, "❌ Unable to open episode search");
            } catch (Exception ignore) {
            }
        }
    }

    // =========================================
    // HANDLE CUSTOM RANGE INPUT
    //
    // Works for USER, ADMIN and OWNER.
    // Maximum 50 episode numbers per request.
    // =========================================

    private void handleEpisodeRangeSearch(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser requestingUser,
            String text
    ) {

        try {

            Long storyId = searchStoryContext.get(chatId);

            if (storyId == null) {

                sendMessage(bot, chatId, """
                        ❌ Search Context Not Found

                        Please select a story first.
                        """);

                return;
            }

            String[] split = text.split("-");

            int start = Integer.parseInt(split[0].trim());
            int end = Integer.parseInt(split[1].trim());

            if (start <= 0 || end <= 0) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Episode Range

                        Episode numbers must be greater than 0.

                        Example: 20-50
                        """);

                return;
            }

            if (start > end) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Range

                        Start episode cannot be greater than end episode.

                        Example: 20-50
                        """);

                return;
            }

            long requestedCount = (long) end - start + 1;

            if (requestedCount > 50) {

                sendMessage(bot, chatId, """
                        ❌ Search Range Too Large

                        Maximum 50 episodes are allowed per search.

                        Examples:
                        1-50
                        51-100
                        101-150
                        """);

                return;
            }

            Story story = storyService.getStoryById(storyId);

            if (story == null) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, "❌ Story not found");

                return;
            }

            boolean owner = requestingUser != null && requestingUser.getRole() == UserRole.OWNER;

            if (!owner && !Boolean.TRUE.equals(story.getActive())) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, """
                        ❌ This story is currently unavailable.

                        Please choose another active story.
                        """);

                return;
            }

            // Run the audio batch outside Telegram's update handler thread.
            // This is important because OWNER/Admin commands (especially
            // global-trial OFF) must still be processed while a large batch
            // is being delivered. The worker re-checks access before every
            // audio, so a deactivation can stop the remaining files.
            if (!activeEpisodeBatches.add(chatId)) {

                sendMessage(bot, chatId, """
                        ⏳ Episodes are already being sent.

                        Please wait for the current batch to finish or stop
                        because of an access change before searching again.
                        """);

                return;
            }

            try {

                scheduler.execute(() -> {
                    try {
                        sendEpisodesByRange(bot, chatId, requestingUser, story, start, end);
                    } finally {
                        activeEpisodeBatches.remove(chatId);
                    }
                });

            } catch (RuntimeException e) {

                activeEpisodeBatches.remove(chatId);
                throw e;
            }

            // Keep the selected story in context so the user can
            // immediately type another range after the current batch ends.

        } catch (NumberFormatException e) {

            try {
                sendMessage(bot, chatId, "❌ Invalid range. Example: 1-50");
            } catch (Exception ignore) {
            }

        } catch (Exception e) {

            log.error("handleEpisodeRangeSearch failed chatId={} text={}", chatId, text, e);
        }
    }

    // =========================================
    // FORWARD PERMISSION
    //
    // USER        -> protected, cannot forward
    // ADMIN/OWNER -> unprotected, can forward
    // =========================================

    private boolean canForwardEpisodes(TelegramUser user) {

        return user != null
                && (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.OWNER);
    }

    // =========================================
    // CUSTOM RANGE SEARCH
    //
    // MAX 50 EPISODES
    // DIRECT AUDIO DELIVERY
    // NO EPISODE LIST
    // NO EPISODE BUTTONS
    // =========================================

    private void sendEpisodesByRange(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser requestingUser,
            Story story,
            int start,
            int end
    ) {

        try {

            // =====================================
            // VALIDATION
            // =====================================

            if (story == null) {

                sendMessage(bot, chatId, "❌ Story not found");

                return;
            }

            if (start <= 0 || end <= 0 || start > end) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Episode Range
                        
                        Example:
                        
                        20-50
                        """);

                return;
            }

            int requestedCount = end - start + 1;

            if (requestedCount > 50) {

                sendMessage(bot, chatId, """
                        ❌ Maximum 50 episodes
                        allowed per search.
                        
                        Example:
                        
                        1-50
                        51-100
                        """);

                return;
            }

            // =====================================
            // GET EPISODES
            // =====================================

            List<Episode> episodes = episodeService.getEpisodesByRange(story, start, end);

            if (episodes == null || episodes.isEmpty()) {

                sendMessage(bot, chatId, """
                        ❌ No episodes found
                        
                        Requested:
                        
                        EP %d - %d
                        """.formatted(start, end));

                return;
            }

            // =====================================
            // SEND START MESSAGE
            // =====================================

            sendMessage(bot, chatId, """
                    🔍 SEARCH RESULT
                    
                    🎧 %s
                    
                    📦 Range:
                    EP %d - %d
                    
                    🎵 Found:
                    %d Episodes
                    
                    Sending audio files...
                    """.formatted(story.getTitle(), start, end, episodes.size()));

            // =====================================
            // SEND EACH AUDIO DIRECTLY
            // =====================================

            int sentCount = 0;

            for (Episode episode : episodes) {

                // Re-check access before every audio.
                // If OWNER disables the global free trial while a
                // 50-episode batch is already being sent, a normal
                // USER must stop receiving files immediately.
                if (!subscriptionService.hasAccess(requestingUser)) {

                    log.info("Episode batch stopped because access ended telegramId={} storyId={} sentCount={}",
                            requestingUser != null ? requestingUser.getTelegramId() : null,
                            story.getId(),
                            sentCount);

                    sendMessage(bot, chatId, """
                            ⛔ Access Ended

                            Your free trial/subscription is no longer active.

                            Remaining episodes were not sent.
                            """);

                    return;
                }

                if (episode == null) {

                    continue;
                }

                if (episode.getTelegramFileId() == null || episode.getTelegramFileId().isBlank()) {

                    log.warn("Skipping episode without fileId episodeId={} episodeNo={}", episode.getId(), episode.getEpisodeNo());

                    continue;
                }

                SendAudio sendAudio = new SendAudio();

                sendAudio.setChatId(String.valueOf(chatId));

                sendAudio.setAudio(new InputFile(episode.getTelegramFileId()));

                sendAudio.setCaption("""
                        🎧 %s
                        
                        EP %s
                        """.formatted(story.getTitle(), episode.getEpisodeNo()));

                // USER content stays protected.
                // ADMIN / OWNER are allowed to forward the audio.
                sendAudio.setProtectContent(!canForwardEpisodes(requestingUser));

                bot.execute(sendAudio);

                sentCount++;
            }

            // =====================================
            // CHANGE STORY BUTTON
            //
            // Search context remains active, so the user can
            // simply type another range without reopening a list.
            // =====================================

            InlineKeyboardButton back = new InlineKeyboardButton();

            back.setText("🔙 Change Story");

            // Return to the public story categories. Do NOT use stories_0
            // because that callback belongs to the OWNER all-stories library.
            back.setCallbackData("lang_tamil");

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

            keyboard.setKeyboard(List.of(List.of(back)));

            SendMessage completed = new SendMessage();

            completed.setChatId(String.valueOf(chatId));

            completed.setText("""
                    ✅ Episodes Sent
                    
                    🎵 %d audio files delivered.
                    
                    Type another range (max 50)
                    or choose a different story.
                    """.formatted(sentCount));

            completed.setReplyMarkup(keyboard);

            completed.setProtectContent(!canForwardEpisodes(requestingUser));

            bot.execute(completed);

        } catch (Exception e) {

            log.error("sendEpisodesByRange failed storyId={} start={} end={}", story != null ? story.getId() : null, start, end, e);

            try {

                sendMessage(bot, chatId, """
                        ❌ Failed to send episodes.
                        
                        Please try again.
                        """);

            } catch (Exception ignore) {

            }
        }
    }

    // =========================================
    // SEND MESSAGE
    // =========================================

    private Message sendMessage(TelegramLongPollingBot bot, Long chatId, String text) throws Exception {

        SendMessage sendMessage = new SendMessage();

        sendMessage.setChatId(String.valueOf(chatId));

        sendMessage.setText(text);

        sendMessage.setProtectContent(true);

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

    private void syncStories(TelegramLongPollingBot bot, Long chatId) {

        try {

            List<Story> stories = storyService.getAllStories();

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, "❌ No stories found.");

                return;
            }

            int updated = 0;
            int deleted = 0;

            for (Story story : stories) {

                try {

                    GetChat getChat = new GetChat(String.valueOf(story.getTelegramChatId()));

                    var chat = bot.execute(getChat);

                    boolean completed = chat.getDescription() != null && chat.getDescription().toLowerCase().contains("completed");

                    story.setTitle(chat.getTitle());

                    story.setTelegramUsername(chat.getUserName());

                    story.setChatType(chat.getType());

                    story.setDescription(chat.getDescription());

                    story.setInviteLink(chat.getInviteLink());

                    story.setIsCompleted(completed);

                    story.setActive(true);

                    storyService.save(story);

                    updated++;

                } catch (Exception ex) {

                    log.warn("Channel deleted/not accessible chatId={}", story.getTelegramChatId());

                    story.setActive(false);

                    storyService.save(story);

                    deleted++;
                }
            }

            sendMessage(bot, chatId, """
                    ✅ Story Sync Completed
                    
                    📚 Total Stories : %d
                    ✅ Updated       : %d
                    ❌ Inactive      : %d
                    """.formatted(stories.size(), updated, deleted));

        } catch (Exception e) {

            log.error("syncStories failed", e);

            try {

                sendMessage(bot, chatId, "❌ Sync failed.");

            } catch (Exception ignore) {

            }
        }
    }

    private void deleteInactiveStories(TelegramLongPollingBot bot, Long chatId) {

        try {

            List<Story> stories = storyService.getInactiveStories();

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, """
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

            sendMessage(bot, chatId, """
                    ✅ Inactive Story Cleanup Completed
                    
                    🗑 Deleted Stories : %d
                    """.formatted(deleted));

        } catch (Exception e) {

            log.error("deleteInactiveStories failed", e);

            try {

                sendMessage(bot, chatId, "❌ Failed to delete inactive stories.");

            } catch (Exception ignore) {

            }
        }
    }
}