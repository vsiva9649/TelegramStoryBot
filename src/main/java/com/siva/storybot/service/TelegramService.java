package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.dto.story.ChatFullInfo;
import com.siva.storybot.entity.Episode;
import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.Subscription;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
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
import java.time.format.DateTimeFormatter;
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

    private static final int AUTO_DELETE_HOURS = 24;
    private static final int USERS_PAGE_SIZE = 50;
    private static final DateTimeFormatter USER_MANAGEMENT_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a");
    private final TelegramConfig telegramConfig;
    private final TelegramUserService telegramUserService;
    private final SubscriptionService subscriptionService;
    private final GlobalTrialService globalTrialService;
    private final GroqService groqService;
    private final StoryService storyService;
    private final StoryAccessService storyAccessService;
    private final EpisodeService episodeService;
    private final EpisodeUsageService episodeUsageService;
    private final RewardTrialService rewardTrialService;
    private final TelegramRoleCommandRegistrar telegramRoleCommandRegistrar;
    private final Map<Long, Long> searchStoryContext = new ConcurrentHashMap<>();

    // ADMIN / OWNER story icon upload state: private chat id -> selected story id
    private final Map<Long, Long> storyIconUploadContext = new ConcurrentHashMap<>();

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

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText("""
                    🔒 Subscription Required
                    
                    Your free access or subscription
                    is currently unavailable.
                    
                    To continue listening to stories,
                    please activate a subscription.
                    
                    👑 Please Contact Admin
                    """);

            if (rewardTrialService.isEnabled()) {
                InlineKeyboardButton reward = new InlineKeyboardButton();
                reward.setText("🎁 Get 1 Hour Free");
                reward.setCallbackData("reward_start");

                InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
                keyboard.setKeyboard(List.of(List.of(reward)));
                sendMessage.setReplyMarkup(keyboard);
            }

            executeSendMessage(bot, chatId, sendMessage);

            if (userMessageId != null) {
                autoDeleteUserMessage(bot, chatId, userMessageId);
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
                            TITLE        : {}
                            PERFORMER    : {}
                            FILE NAME    : {}
                            CAPTION      : {}
                            DURATION     : {}
                            FILE SIZE    : {}
                            MIME TYPE    : {}
                            
                            """, chat.getTitle(), audio.getFileId(), audio.getTitle(), audio.getPerformer(), audio.getFileName(), message.getCaption(), audio.getDuration(), audio.getFileSize(), audio.getMimeType());

                    Story story = storyService.findOrCreateStory(

                            chatId,

                            chatFullInfo.getTitle() != null ? chatFullInfo.getTitle() : chat.getTitle(),

                            chatFullInfo.getUsername() != null ? chatFullInfo.getUsername() : chat.getUserName(),

                            chatFullInfo.getChatType() != null ? chatFullInfo.getChatType() : chatType,

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

            var telegramApiUser = message.getFrom();

            if (telegramApiUser == null) {

                log.warn("Telegram user is null");

                return;
            }

            TelegramUser telegramUser = telegramUserService.saveOrUpdateUser(telegramApiUser, chatId);

            // =====================================
            // NORMAL USER INPUT POLICY
            //
            // Telegram does not provide a Bot API switch to hide the native
            // attachment/poll UI in a private chat. Enforce the rule on the
            // server instead: USER may send text only. Any photo, document,
            // audio, voice, video, sticker, poll, location, contact, etc. is
            // deleted immediately and is never processed by the bot.
            //
            // ADMIN / OWNER are not restricted by this input policy.
            // =====================================

            if (!message.hasText()) {

                // ADMIN / OWNER: when a story was selected through
                // /addstoryicon (or legacy /storyicon), the next media input
                // is routed to the icon-upload handler. The handler accepts
                // only Telegram photos and gives a clear message otherwise.
                if (isAdminOrOwner(telegramUser)
                        && storyIconUploadContext.containsKey(chatId)) {

                    handleStoryIconUpload(bot, chatId, telegramUser, message);
                    return;
                }

                if (isNormalUser(telegramUser)) {
                    rejectNonTextUserMessage(bot, chatId, message);
                } else {
                    log.info("Ignoring non-text private message from privileged user telegramId={} role={}", telegramUser.getTelegramId(), telegramUser.getRole());
                }

                return;
            }

            String text = message.getText().trim();

            // =====================================
            // REWARDED 1-HOUR FREE ACCESS
            //
            // This must be handled BEFORE the normal access gate,
            // otherwise an expired USER would never be able to request
            // the reward link.
            // =====================================

            if (text.equalsIgnoreCase("🎁 Get 1 Hour Free") || text.equalsIgnoreCase("/reward") || text.equalsIgnoreCase("/freehour")) {

                handleRewardTrialRequest(bot, chatId, telegramUser);
                return;
            }

            // =====================================
            // SHORT-LINK PROVIDER -> DIRECT TELEGRAM DEEP LINK
            //
            // The provider final destination is:
            // https://t.me/<bot>?start=rw_<ONE_TIME_TOKEN>
            //
            // This handler runs BEFORE the normal access gate so an expired
            // USER can return from the short-link provider and claim the reward. The token is
            // DB-locked, one-time, time-limited, and bound to the Telegram
            // account that originally requested it.
            // =====================================

            String startPayload = extractStartPayload(text);

            if (rewardTrialService.isRewardStartPayload(startPayload)) {
                handleDirectRewardClaim(bot, chatId, telegramUser, startPayload);
                return;
            }

            // Legacy deep-link payloads are kept only for compatibility with
            // old messages. They never activate access by themselves.
            if ("reward_success".equalsIgnoreCase(startPayload)) {
                handleRewardSuccessReturn(bot, chatId, telegramUser);
                return;
            }

            if ("reward_retry".equalsIgnoreCase(startPayload)) {
                handleRewardTrialRequest(bot, chatId, telegramUser);
                return;
            }

            // =====================================
            // PUBLIC STORY CATALOG
            //
            // Everyone can browse story names even when subscription/story
            // access is not active. Authorization is enforced only when the
            // user actually requests episode audio.
            // =====================================

            Integer userMessageId = message.getMessageId();

            boolean episodeSearchInput = text.matches("\\d+")
                    || text.matches("(?i)\\d+\\s*(?:-|to)\\s*\\d+");

            // If a story is already selected, an episode request is always
            // sent to the episode handler. That handler decides whether the
            // user has subscription/reward + story permission.
            if (episodeSearchInput && searchStoryContext.containsKey(chatId)) {
                handleEpisodeRangeSearch(bot, chatId, telegramUser, text);
                return;
            }

            String privateCommand = normalizeOwnerCommand(text);

            // =====================================
            // ROLE-AWARE PANEL / START
            //
            // /start and /panel open the correct screen for the current role.
            // The normal "Main Menu" reply button always opens the story menu.
            // =====================================

            if ("/start".equals(privateCommand)
                    || "/panel".equals(privateCommand)
                    || "/ownerpanel".equals(privateCommand)
                    || "/adminpanel".equals(privateCommand)) {

                searchStoryContext.remove(chatId);
                storyIconUploadContext.remove(chatId);
                telegramRoleCommandRegistrar.syncCommandsForUser(bot, telegramUser);

                if (telegramUser.getRole() == UserRole.OWNER) {
                    sendOwnerPanel(bot, chatId);
                } else if (telegramUser.getRole() == UserRole.ADMIN) {
                    sendAdminPanel(bot, chatId);
                } else {
                    showMainMenu(bot, chatId);
                }

                return;
            }

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
                showOnGoingStories(bot, chatId, telegramUser, 0);
                return;
            }

            if (text.equalsIgnoreCase("✅ Completed Stories")) {
                showCompletedStories(bot, chatId, telegramUser, 0);
                return;
            }

            if (text.equalsIgnoreCase("🆘 Help") || "/help".equals(privateCommand)) {
                if (telegramUser.getRole() == UserRole.OWNER) {
                    sendOwnerUsageGuide(bot, chatId);
                } else if (telegramUser.getRole() == UserRole.ADMIN) {
                    sendAdminUsageGuide(bot, chatId);
                } else {
                    showHelpMenu(bot, chatId);
                }
                return;
            }

            if ("/usage".equals(privateCommand)) {
                if (telegramUser.getRole() == UserRole.OWNER) {
                    sendOwnerUsageGuide(bot, chatId);
                } else if (telegramUser.getRole() == UserRole.ADMIN) {
                    sendAdminUsageGuide(bot, chatId);
                } else {
                    showHelpMenu(bot, chatId);
                }
                return;
            }

            // =====================================
            // COMPLETE ACCESS CHECK
            //
            // Non-browse actions still require an active access source.
            // Episode requests are checked separately above.
            // =====================================

            boolean active = subscriptionService.hasAccess(telegramUser);

            if (!active) {
                sendSubscriptionRequiredMessage(bot, chatId, userMessageId);
                return;
            }

            // =====================================
            // STORY ICON MANAGEMENT - ADMIN / OWNER ONLY
            //
            // /addstoryicon    -> upload / replace a story icon
            // /removestoryicon -> remove a story icon
            // /storyicon       -> legacy alias for /addstoryicon
            // =====================================

            if (isAddStoryIconCommand(text)) {

                if (!isAdminOrOwner(telegramUser)) {
                    sendMessage(bot, chatId, "❌ Admin access required.");
                    return;
                }

                storyIconUploadContext.remove(chatId);
                showAddStoryIconSelection(bot, chatId, telegramUser, 0);
                return;
            }

            if (isRemoveStoryIconCommand(text)) {

                if (!isAdminOrOwner(telegramUser)) {
                    sendMessage(bot, chatId, "❌ Admin access required.");
                    return;
                }

                storyIconUploadContext.remove(chatId);
                showRemoveStoryIconSelection(bot, chatId, telegramUser, 0);
                return;
            }

            // =====================================
            // STORY ACCESS MANAGEMENT - OWNER / ADMIN
            //
            // OWNER -> can manage ADMIN and USER mappings.
            // ADMIN -> can manage USER mappings, but only for stories
            //          assigned to that ADMIN by OWNER.
            // =====================================

            if (isStoryAccessCommand(text)) {

                if (!isAdminOrOwner(telegramUser)) {
                    sendMessage(bot, chatId, "❌ Admin access required.");
                    return;
                }

                searchStoryContext.remove(chatId);
                handleStoryAccessCommand(bot, chatId, telegramUser, text);
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

            DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy • hh:mm a");

            if (user != null && globalTrialService.hasGlobalTrialAccess(user)) {

                trialInfo = globalTrialService.getUserTrialExpiry(user).map(expiry -> """
                        
                        🎁 Global Free Trial Active
                        📚 ALL active stories are available
                        ⏳ Valid Until: %s
                        """.formatted(expiry.format(displayFormatter))).orElse("");

            } else if (user != null && rewardTrialService.hasActiveRewardTrial(user)) {

                trialInfo = rewardTrialService.getRewardExpiry(user).map(expiry -> """
                        
                        🎁 1 Hour Reward Access Active
                        ⏳ Valid Until: %s
                        """.formatted(expiry.format(displayFormatter))).orElse("");
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

            if (isNormalUser(user) && rewardTrialService.isEnabled() && !rewardTrialService.hasActiveRewardTrial(user) && !subscriptionService.hasActiveSubscription(user) && !globalTrialService.hasGlobalTrialAccess(user)) {

                KeyboardRow rewardRow = new KeyboardRow();
                rewardRow.add("🎁 Get 1 Hour Free");
                rows.add(rewardRow);
            }

            keyboard.setKeyboard(rows);

            sendMessage.setReplyMarkup(keyboard);

            executeSendMessage(bot, chatId, sendMessage);

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

            executeSendMessage(bot, chatId, sendMessage);

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

    private void showCompletedStories(TelegramLongPollingBot bot, Long chatId, TelegramUser requestingUser, int page) {

        try {

            searchStoryContext.remove(chatId);

            int size = 10;

            Page<Story> stories = storyAccessService.getCompletedStories(requestingUser, page, size);

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

    private void showOnGoingStories(TelegramLongPollingBot bot, Long chatId, TelegramUser requestingUser, int page) {

        try {

            searchStoryContext.remove(chatId);

            int size = 10;

            Page<Story> stories = storyAccessService.getOnGoingStories(requestingUser, page, size);

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

            // Keep row numbers continuous across pagination.
            // Page 1 => 01-10, Page 2 => 11-20, Page 3 => 21-30, ...
            int count = stories.getNumber() * stories.getSize() + 1;

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

            executeSendMessage(bot, chatId, sendMessage);

        } catch (Exception e) {

            log.error("showStoryList failed", e);
        }
    }

    // =========================================
    // REWARD STORY SELECTION
    //
    // After the short-link reward is activated, show ALL active story names.
    // The first story chosen is stored on RewardTrial.selectedStory and is the
    // only story allowed for that 1-hour reward.
    // =========================================

    private void showRewardStorySelection(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser user,
            int page) {

        try {
            searchStoryContext.remove(chatId);

            int size = 10;
            Page<Story> stories = storyService.getActiveStories(page, size);

            if (stories.isEmpty()) {
                sendMessage(bot, chatId, "❌ No active stories available now.");
                return;
            }

            Story selectedRewardStory = rewardTrialService
                    .getSelectedRewardStory(user)
                    .orElse(null);

            boolean rewardActive = rewardTrialService.hasActiveRewardTrial(user);

            StringBuilder builder = new StringBuilder();

            if (rewardActive && selectedRewardStory == null) {
                builder.append("🎁 CHOOSE ONE STORY FOR YOUR 1-HOUR REWARD\n\n");
                builder.append("Select any story below.\n");
                builder.append("The first story you choose will be available for this reward period.\n\n");
            } else if (rewardActive) {
                builder.append("🎁 YOUR 1-HOUR REWARD STORY\n\n");
                builder.append("Selected: ")
                        .append(selectedRewardStory != null ? selectedRewardStory.getTitle() : "-")
                        .append("\n\n");
                builder.append("You may browse all stories, but this reward can play episodes only from the selected story.\n");
                builder.append("To change the reward story, the current reward must end and you must complete a NEW reward link.\n\n");
            } else {
                builder.append("📚 STORY LIBRARY\n\n");
                builder.append("You can browse all active stories. Episode access is checked when you request audio.\n\n");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            int count = stories.getNumber() * stories.getSize() + 1;

            for (Story story : stories) {
                boolean selected = selectedRewardStory != null
                        && selectedRewardStory.getId() != null
                        && selectedRewardStory.getId().equals(story.getId());

                String status = Boolean.TRUE.equals(story.getIsCompleted()) ? "✅" : "🔥";
                String selectedMark = selected ? "🎁 " : "";

                builder.append(String.format("%02d", count++))
                        .append(" - ")
                        .append(selectedMark)
                        .append(story.getTitle())
                        .append("\n");

                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(selectedMark + status + " " + story.getTitle());
                button.setCallbackData("story_" + story.getId());
                rows.add(List.of(button));
            }

            List<InlineKeyboardButton> nav = new ArrayList<>();

            if (stories.hasPrevious()) {
                InlineKeyboardButton previous = new InlineKeyboardButton();
                previous.setText("⬅️ Prev");
                previous.setCallbackData("reward_stories_" + (stories.getNumber() - 1));
                nav.add(previous);
            }

            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("📄 " + (stories.getNumber() + 1) + "/" + stories.getTotalPages());
            pageButton.setCallbackData("ignore");
            nav.add(pageButton);

            if (stories.hasNext()) {
                InlineKeyboardButton next = new InlineKeyboardButton();
                next.setText("Next ➡️");
                next.setCallbackData("reward_stories_" + (stories.getNumber() + 1));
                nav.add(next);
            }

            rows.add(nav);

            if (rewardActive && selectedRewardStory != null) {
                InlineKeyboardButton changeStory = new InlineKeyboardButton();
                changeStory.setText("🔄 Change Reward Story");
                changeStory.setCallbackData("reward_change_story");
                rows.add(List.of(changeStory));
            }

            InlineKeyboardButton home = new InlineKeyboardButton();
            home.setText("🏠 Main Menu");
            home.setCallbackData("main_menu");
            rows.add(List.of(home));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(rows);

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText(builder.toString());
            message.setReplyMarkup(keyboard);
            executeSendMessage(bot, chatId, message);

        } catch (Exception e) {
            log.error("showRewardStorySelection failed chatId={}", chatId, e);

            try {
                sendMessage(bot, chatId, "❌ Unable to load stories right now.");
            } catch (Exception ignore) {
            }
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

            String data = callbackQuery.getData();

            log.info("Callback received={}", data);

            // =====================================
            // REWARD CALLBACKS MUST BYPASS ACCESS GATE
            // =====================================

            if ("reward_start".equals(data)) {
                if (user == null) {
                    sendSubscriptionRequiredMessage(bot, chatId, messageId);
                    return;
                }

                handleRewardTrialRequest(bot, chatId, user);
                return;
            }

            if ("reward_change_story".equals(data)) {
                handleRewardStoryChangeRequest(bot, chatId, user);
                return;
            }

            if ("reward_change_confirm".equals(data)) {
                handleRewardStoryChangeConfirm(bot, chatId, user);
                return;
            }

            if ("reward_change_cancel".equals(data)) {
                if (user != null && rewardTrialService.hasActiveRewardTrial(user)) {
                    showRewardStorySelection(bot, chatId, user, 0);
                } else {
                    showMainMenu(bot, chatId);
                }
                return;
            }

            // =====================================
            // PUBLIC CALLBACKS / STORY CATALOG
            //
            // Story names and story selection are browseable without an
            // active subscription. Episode delivery is checked later when
            // the user submits an episode/range.
            // =====================================

            if (user == null) {
                sendSubscriptionRequiredMessage(bot, chatId, messageId);
                return;
            }

            if ("ignore".equals(data)) {
                return;
            }

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

            if (data.equals("main_menu")) {
                showMainMenu(bot, chatId);
                return;
            }

            if (data.equals("role_panel")) {
                telegramRoleCommandRegistrar.syncCommandsForUser(bot, user);

                if (user.getRole() == UserRole.OWNER) {
                    sendOwnerPanel(bot, chatId);
                } else if (user.getRole() == UserRole.ADMIN) {
                    sendAdminPanel(bot, chatId);
                } else {
                    showMainMenu(bot, chatId);
                }
                return;
            }

            if (data.equals("help_menu")) {
                showHelpMenu(bot, chatId);
                return;
            }

            if (data.startsWith("completed_stories_")) {
                int page = Integer.parseInt(data.replace("completed_stories_", ""));
                showCompletedStories(bot, chatId, user, page);
                return;
            }

            if (data.startsWith("ongoing_stories_")) {
                int page = Integer.parseInt(data.replace("ongoing_stories_", ""));
                showOnGoingStories(bot, chatId, user, page);
                return;
            }

            if (data.startsWith("reward_stories_")) {
                int page = Integer.parseInt(data.replace("reward_stories_", ""));
                showRewardStorySelection(bot, chatId, user, page);
                return;
            }

            if (data.startsWith("story_")) {
                Long storyId = Long.parseLong(data.replace("story_", ""));
                openEpisodeSearch(bot, chatId, user, storyId);
                return;
            }

            if (data.startsWith("packs_") || data.startsWith("pack_")) {
                String[] split = data.split("_");
                Long storyId = Long.parseLong(split[1]);
                openEpisodeSearch(bot, chatId, user, storyId);
                return;
            }

            if (data.startsWith("search_")) {
                Long storyId = Long.parseLong(data.replace("search_", ""));
                openEpisodeSearch(bot, chatId, user, storyId);
                return;
            }

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

            // =====================================
            // NON-BROWSE CALLBACK ACCESS CHECK
            // =====================================

            boolean active = subscriptionService.hasAccess(user);

            if (!active) {
                sendSubscriptionRequiredMessage(bot, chatId, messageId);
                return;
            }

            // =====================================
            // STORY ACCESS MANAGEMENT - OWNER / ADMIN
            // =====================================

            if (data.startsWith("storyaccess_page_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String payload = data.replace("storyaccess_page_", "");
                String[] split = payload.split("_");

                if (split.length < 2 || split.length > 3) {
                    sendMessage(bot, chatId, "❌ Invalid story access page.");
                    return;
                }

                Long targetTelegramId = Long.parseLong(split[0]);
                int page = Integer.parseInt(split[1]);
                Integer returnUsersPage = split.length == 3
                        ? Integer.parseInt(split[2])
                        : null;

                showStoryAccessManagement(
                        bot,
                        chatId,
                        user,
                        targetTelegramId,
                        page,
                        returnUsersPage,
                        messageId);

                return;
            }

            if (data.startsWith("storyaccess_toggle_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String payload = data.replace("storyaccess_toggle_", "");
                String[] split = payload.split("_");

                if (split.length < 3 || split.length > 4) {
                    sendMessage(bot, chatId, "❌ Invalid story access action.");
                    return;
                }

                Long targetTelegramId = Long.parseLong(split[0]);
                Long storyId = Long.parseLong(split[1]);
                int page = Integer.parseInt(split[2]);
                Integer returnUsersPage = split.length == 4
                        ? Integer.parseInt(split[3])
                        : null;

                TelegramUser targetUser = telegramUserService.getUserByTelegramId(targetTelegramId);
                Story story = storyService.getStoryById(storyId);

                if (targetUser == null || story == null) {
                    sendMessage(bot, chatId, "❌ User or story not found.");
                    return;
                }

                try {
                    boolean enabled = storyAccessService.toggleStoryAccess(user, targetUser, story);

                    String displayUser = targetUser.getUsername() == null || targetUser.getUsername().isBlank()
                            ? String.valueOf(targetUser.getTelegramId())
                            : "@" + targetUser.getUsername();

                    sendMessage(
                            bot,
                            chatId,
                            (enabled ? "✅ Granted: " : "❌ Revoked: ")
                                    + story.getTitle()
                                    + " → "
                                    + displayUser);

                    showStoryAccessManagement(
                            bot,
                            chatId,
                            user,
                            targetTelegramId,
                            page,
                            returnUsersPage,
                            messageId);

                } catch (SecurityException | IllegalArgumentException e) {
                    sendMessage(bot, chatId, "❌ " + e.getMessage());
                }

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
            // ADMIN ROLE APPROVE / DISAPPROVE (OWNER)
            // callback format:
            // admin_approve_<telegramId>_<page>
            // admin_disapprove_<telegramId>_<page>
            // =====================================

            if (data.startsWith("admin_approve_") || data.startsWith("admin_disapprove_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String[] split = data.split("_");

                if (split.length != 4) {
                    sendMessage(bot, chatId, "❌ Invalid admin role action.");
                    return;
                }

                boolean approve = "approve".equals(split[1]);
                Long targetTelegramId = Long.parseLong(split[2]);
                int sourcePage = Integer.parseInt(split[3]);

                TelegramUser targetUser = telegramUserService.getUserByTelegramId(targetTelegramId);

                if (targetUser == null) {
                    sendMessage(bot, chatId, "❌ User not found.");
                    return;
                }

                if (targetUser.getRole() == UserRole.OWNER) {
                    sendMessage(bot, chatId, "❌ OWNER role cannot be changed here.");
                    return;
                }

                UserRole newRole = approve ? UserRole.ADMIN : UserRole.USER;
                targetUser = telegramUserService.updateUserRole(targetUser, newRole);
                telegramRoleCommandRegistrar.syncCommandsForUser(bot, targetUser);

                // Stay on the selected user's management screen so the
                // role action is immediately reflected without losing context.
                showUserDetailsScreen(
                        bot,
                        chatId,
                        targetUser.getTelegramId(),
                        sourcePage,
                        messageId);

                String displayUser = targetUser.getUsername() == null || targetUser.getUsername().isBlank()
                        ? String.valueOf(targetUser.getTelegramId())
                        : "@" + targetUser.getUsername();

                String roleMessage = "✅ " + displayUser + " role changed to " + newRole + ".";

                if (newRole == UserRole.ADMIN) {
                    roleMessage += "\n\n📚 ADMIN can access only OWNER-assigned stories.\nUse /storyaccess " + displayUser + " to assign them.";
                }

                sendMessage(bot, chatId, roleMessage);

                return;
            }

            // =====================================
            // USER MANAGEMENT DETAILS (OWNER)
            // callback format: manageuser_<telegramId>_<usersPage>
            // =====================================

            if (data.startsWith("manageuser_")) {

                if (user.getRole() != UserRole.OWNER) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String payload = data.replace("manageuser_", "");
                String[] split = payload.split("_");

                if (split.length != 2) {
                    sendMessage(bot, chatId, "❌ Invalid user selection.");
                    return;
                }

                Long targetTelegramId = Long.parseLong(split[0]);
                int sourcePage = Integer.parseInt(split[1]);

                showUserDetailsScreen(
                        bot,
                        chatId,
                        targetTelegramId,
                        sourcePage,
                        messageId);

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
            // STORY ICON MANAGEMENT - ADMIN / OWNER ONLY
            // =====================================

            // ADD / REPLACE ICON pagination
            if (data.startsWith("addstoryicon_page_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("addstoryicon_page_", ""));
                showAddStoryIconSelection(bot, chatId, user, page);
                return;
            }

            // ADD / REPLACE ICON story selection
            if (data.startsWith("addstoryicon_select_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                Long storyId = Long.parseLong(data.replace("addstoryicon_select_", ""));
                prepareStoryIconUpload(bot, chatId, user, storyId);
                return;
            }

            // REMOVE ICON pagination
            if (data.startsWith("removestoryicon_page_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("removestoryicon_page_", ""));
                showRemoveStoryIconSelection(bot, chatId, user, page);
                return;
            }

            // REMOVE ICON story selection -> confirmation screen.
            if (data.startsWith("removestoryicon_select_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String payload = data.replace("removestoryicon_select_", "");
                String[] split = payload.split("_", 2);

                Long storyId = Long.parseLong(split[0]);
                int page = split.length > 1 ? Integer.parseInt(split[1]) : 0;

                showRemoveStoryIconConfirmation(bot, chatId, user, storyId, page);
                return;
            }

            // Confirm icon removal.
            if (data.startsWith("removestoryicon_confirm_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                String payload = data.replace("removestoryicon_confirm_", "");
                String[] split = payload.split("_", 2);

                Long storyId = Long.parseLong(split[0]);
                int page = split.length > 1 ? Integer.parseInt(split[1]) : 0;

                removeStoryIcon(bot, chatId, user, storyId, page);
                return;
            }

            // Cancel icon removal and return to the previous page.
            if (data.startsWith("removestoryicon_cancel_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("removestoryicon_cancel_", ""));
                showRemoveStoryIconSelection(bot, chatId, user, page);
                return;
            }

            // =====================================
            // LEGACY /storyicon CALLBACK SUPPORT
            // =====================================

            if (data.startsWith("storyicon_page_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                int page = Integer.parseInt(data.replace("storyicon_page_", ""));
                showAddStoryIconSelection(bot, chatId, user, page);
                return;
            }

            if (data.startsWith("storyicon_select_")) {

                if (!isAdminOrOwner(user)) {
                    showMainMenu(bot, chatId);
                    return;
                }

                Long storyId = Long.parseLong(data.replace("storyicon_select_", ""));
                prepareStoryIconUpload(bot, chatId, user, storyId);
                return;
            }

            // Public story/open/search callbacks are intentionally handled
            // before the non-browse access gate near the top of this method.

        } catch (Exception e) {

            log.error("handleCallbackQuery failed", e);
        }
    }

    // =========================================
    // REWARD STORY CHANGE
    // =========================================

    private void handleRewardStoryChangeRequest(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser user) {

        try {
            searchStoryContext.remove(chatId);

            if (user == null || !isNormalUser(user)) {
                sendMessage(bot, chatId, "❌ Reward story change is available only for normal USER accounts.");
                return;
            }

            // GLOBAL FREE TRIAL means every active story is already playable.
            // Do not waste a new monetized reward link.
            if (globalTrialService.hasGlobalTrialAccess(user)) {
                sendMessage(bot, chatId, """
                        🎁 Global Free Trial is active.

                        ✅ You can use ALL active stories during the global trial.
                        No new reward link is required to change stories.
                        """);
                showTamilMenu(bot, chatId);
                return;
            }

            // If an individual/manual/paid subscription became active while an
            // old reward row still exists, subscription access takes priority.
            if (subscriptionService.hasActiveSubscription(user)) {
                sendMessage(bot, chatId, """
                        ✅ Your subscription/manual access is active.

                        Reward-story replacement is not required.
                        Your normal story permissions are now used.
                        """);
                showTamilMenu(bot, chatId);
                return;
            }

            var activeReward = rewardTrialService.getActiveRewardTrial(user);

            if (activeReward.isEmpty()) {
                sendMessage(bot, chatId, """
                        ℹ️ Your previous 1-hour reward is no longer active.

                        Request a new reward link to choose another story.
                        """);
                handleRewardTrialRequest(bot, chatId, user);
                return;
            }

            Story selectedStory = activeReward.get().getSelectedStory();

            if (selectedStory == null) {
                sendMessage(bot, chatId, """
                        🎁 Your current reward does not have a story selected yet.

                        Choose any story below. You do not need a new link.
                        """);
                showRewardStorySelection(bot, chatId, user, 0);
                return;
            }

            String expiry = activeReward.get().getExpiresAt() == null
                    ? "soon"
                    : activeReward.get().getExpiresAt()
                            .format(DateTimeFormatter.ofPattern("dd-MM-yyyy • hh:mm a"));

            InlineKeyboardButton confirm = new InlineKeyboardButton();
            confirm.setText("✅ Expire & Get New Link");
            confirm.setCallbackData("reward_change_confirm");

            InlineKeyboardButton cancel = new InlineKeyboardButton();
            cancel.setText("❌ Keep Current Story");
            cancel.setCallbackData("reward_change_cancel");

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(
                    List.of(confirm),
                    List.of(cancel)
            ));

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("""
                    🔄 CHANGE REWARD STORY

                    Current Story:
                    🎧 %s

                    Current Reward Valid Until:
                    ⏳ %s

                    ⚠️ To choose a DIFFERENT story:
                    1. Your current reward will be expired.
                    2. A NEW ad/reward link will be created.
                    3. Complete the new link successfully.
                    4. A fresh 60-minute reward will activate.
                    5. Then choose your new story.

                    If the new link cannot be created, your current reward will remain active.
                    """.formatted(selectedStory.getTitle(), expiry));
            message.setReplyMarkup(keyboard);
            executeSendMessage(bot, chatId, message);

        } catch (Exception e) {
            log.error("handleRewardStoryChangeRequest failed telegramId={}", user != null ? user.getTelegramId() : null, e);

            try {
                sendMessage(bot, chatId, "❌ Unable to start story change right now.");
            } catch (Exception ignore) {
            }
        }
    }

    private void handleRewardStoryChangeConfirm(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser user) {

        try {
            searchStoryContext.remove(chatId);

            if (user == null || !isNormalUser(user)) {
                sendMessage(bot, chatId, "❌ Reward story change is available only for normal USER accounts.");
                return;
            }

            if (!rewardTrialService.isEnabled()) {
                sendMessage(bot, chatId, "❌ Reward links are currently unavailable. Your current reward was not changed.");
                return;
            }

            // Re-check at confirmation time. A global trial/subscription could
            // have become active after the confirmation screen was shown.
            if (globalTrialService.hasGlobalTrialAccess(user)) {
                sendMessage(bot, chatId, """
                        🎁 Global Free Trial is active now.

                        ✅ ALL active stories are available.
                        Your reward does not need to be replaced.
                        """);
                showTamilMenu(bot, chatId);
                return;
            }

            if (subscriptionService.hasActiveSubscription(user)) {
                sendMessage(bot, chatId, """
                        ✅ Your subscription/manual access is active now.

                        No new reward link is required.
                        """);
                showTamilMenu(bot, chatId);
                return;
            }

            RewardTrialService.RewardLinkResult result =
                    rewardTrialService.createStoryChangeRewardLink(user);

            InlineKeyboardButton openAds = new InlineKeyboardButton();
            openAds.setText("▶️ Complete Ads & Unlock New Story");
            openAds.setUrl(result.shortUrl());

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(List.of(openAds)));

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("""
                    ✅ OLD REWARD ENDED

                    A new reward link is ready.

                    1. Complete the new ad-link steps.
                    2. Return to this same Telegram account.
                    3. A fresh 60-minute reward will activate.
                    4. Then choose a different story.

                    ⏳ New link expires in %d minutes.

                    ⚠️ Until the new link is completed successfully,
                    there is no active 1-hour reward access.
                    """.formatted(RewardTrialService.REWARD_LINK_MINUTES));
            message.setReplyMarkup(keyboard);
            executeSendMessage(bot, chatId, message);

        } catch (IllegalStateException e) {
            log.warn("Reward story-change confirmation rejected telegramId={} reason={}",
                    user != null ? user.getTelegramId() : null,
                    e.getMessage());

            try {
                sendMessage(bot, chatId, "❌ " + e.getMessage());
            } catch (Exception ignore) {
            }
        } catch (Exception e) {
            log.error("handleRewardStoryChangeConfirm failed telegramId={}", user != null ? user.getTelegramId() : null, e);

            try {
                sendMessage(bot, chatId, """
                        ❌ Unable to create the replacement reward link.

                        Your current reward remains active if the replacement link was not created successfully.
                        """);
            } catch (Exception ignore) {
            }
        }
    }

    // =========================================
    // REWARDED 1-HOUR FREE ACCESS
    // =========================================

    private void handleRewardTrialRequest(TelegramLongPollingBot bot, Long chatId, TelegramUser user) {

        try {
            searchStoryContext.remove(chatId);

            if (user == null) {
                sendMessage(bot, chatId, "❌ User not found. Please send /start again.");
                return;
            }

            if (!isNormalUser(user)) {
                sendMessage(bot, chatId, """
                        ℹ️ Reward trial is only for normal USER accounts.

                        OWNER uses all stories. ADMIN can listen only to stories
                        assigned by OWNER.
                        """);
                return;
            }

            if (!rewardTrialService.isEnabled()) {
                sendMessage(bot, chatId, """
                        🚧 1 Hour Free Access is currently unavailable.
                        
                        Please contact the admin for access.
                        """);
                return;
            }

            if (rewardTrialService.hasActiveRewardTrial(user)) {
                String expiry = rewardTrialService.getRewardExpiry(user).map(value -> value.format(DateTimeFormatter.ofPattern("dd-MM-yyyy • hh:mm a"))).orElse("soon");

                sendMessage(bot, chatId, """
                        ✅ Your 1 Hour Free Access is already active.
                        
                        ⏳ Valid Until: %s
                        """.formatted(expiry));

                showRewardStorySelection(bot, chatId, user, 0);
                return;
            }

            // A paid/manual/global access period should not be extended by
            // claiming a reward in parallel. Reward can be requested after
            // the current access source ends.
            if (subscriptionService.hasActiveSubscription(user) || globalTrialService.hasGlobalTrialAccess(user)) {

                sendMessage(bot, chatId, """
                        ✅ You already have active access.
                        
                        The 1-hour reward can be requested after your current access expires.
                        """);
                return;
            }

            RewardTrialService.RewardLinkResult result = rewardTrialService.createOrReuseRewardLink(user);

            InlineKeyboardButton openAds = new InlineKeyboardButton();
            openAds.setText("▶️ Complete Ads & Unlock 1 Hour");
            openAds.setUrl(result.shortUrl());

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(List.of(openAds)));

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("""
                    🎁 Get 1 Hour Free Access
                    
                    1. Tap the button below.
                    2. Complete all required ad-link steps.
                    3. After the final step, Telegram will open this bot.
                    4. If Telegram shows a Start button, tap Start.
                    5. The same Telegram account will receive exactly 60 minutes of access.
                    
                    🔐 This reward link is one-time and expires in %d minutes.
                    🔒 Forwarding the link to another account will not activate it.
                    """.formatted(RewardTrialService.REWARD_LINK_MINUTES));
            message.setReplyMarkup(keyboard);

            executeSendMessage(bot, chatId, message);

        } catch (Exception e) {
            log.error("handleRewardTrialRequest failed telegramId={}", user != null ? user.getTelegramId() : null, e);

            try {
                sendMessage(bot, chatId, """
                        ❌ Unable to create the 1-hour reward link right now.
                        
                        Please try again later or contact the admin.
                        """);
            } catch (Exception ignore) {
            }
        }
    }

    private void handleDirectRewardClaim(TelegramLongPollingBot bot, Long chatId, TelegramUser user, String startPayload) {
        try {
            searchStoryContext.remove(chatId);

            if (user == null) {
                sendMessage(bot, chatId, "❌ User not found. Please open the reward link again.");
                return;
            }

            if (!isNormalUser(user)) {
                sendMessage(bot, chatId, "ℹ️ Reward trial is only for normal USER accounts. ADMIN story access is controlled by OWNER mappings.");
                showMainMenu(bot, chatId);
                return;
            }

            // Do not stack a reward on top of manual/paid/global access if the
            // user's access situation changed after the short link was created.
            if (subscriptionService.hasActiveSubscription(user) || globalTrialService.hasGlobalTrialAccess(user)) {

                sendMessage(bot, chatId, """
                        ✅ You already have active access.
                        
                        The 1-hour reward was not started, so your reward time is not wasted.
                        """);
                showMainMenu(bot, chatId);
                return;
            }

            RewardTrialService.ActivationResult result = rewardTrialService.activateByStartPayload(startPayload, user);

            if (!result.success()) {
                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText("""
                        ❌ Unable to activate the 1-hour reward.
                        
                        %s
                        """.formatted(result.message()));

                if (rewardTrialService.isEnabled()) {
                    InlineKeyboardButton retry = new InlineKeyboardButton();
                    retry.setText("🎁 Get New 1 Hour Link");
                    retry.setCallbackData("reward_start");

                    InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
                    keyboard.setKeyboard(List.of(List.of(retry)));
                    message.setReplyMarkup(keyboard);
                }

                executeSendMessage(bot, chatId, message);
                return;
            }

            String expiry = result.accessExpiresAt() == null ? "soon" : result.accessExpiresAt().format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a"));

            String heading = result.alreadyActive() ? "✅ Your 1 Hour Free Access is already active!" : "🎉 1 Hour Free Access Activated!";

            sendMessage(bot, chatId, """
                    %s
                    
                    ✅ Reward verified through your one-time Telegram link.
                    ⏳ Valid until: %s
                    
                    Choose ONE story below for this reward period.
                    """.formatted(heading, expiry));

            showRewardStorySelection(bot, chatId, user, 0);

        } catch (Exception e) {
            log.error("handleDirectRewardClaim failed telegramId={}", user != null ? user.getTelegramId() : null, e);

            try {
                sendMessage(bot, chatId, """
                        ❌ Unable to activate the reward right now.
                        
                        Please request a new 1-hour link and try again.
                        """);
            } catch (Exception ignore) {
            }
        }
    }

    private void handleRewardSuccessReturn(TelegramLongPollingBot bot, Long chatId, TelegramUser user) {
        try {
            searchStoryContext.remove(chatId);

            if (user == null) {
                sendMessage(bot, chatId, "❌ User not found. Please send /start again.");
                return;
            }

            if (!isNormalUser(user)) {
                sendMessage(bot, chatId, "ℹ️ Reward trial is only for normal USER accounts. ADMIN story access is controlled by OWNER mappings.");
                showMainMenu(bot, chatId);
                return;
            }

            var activeReward = rewardTrialService.getActiveRewardTrial(user);

            if (activeReward.isEmpty()) {
                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText("""
                        ❌ No active 1-hour reward was found.
                        
                        The web return link itself cannot activate access.
                        Please complete a valid reward link and try again.
                        """);

                if (rewardTrialService.isEnabled()) {
                    InlineKeyboardButton retry = new InlineKeyboardButton();
                    retry.setText("🎁 Get 1 Hour Free");
                    retry.setCallbackData("reward_start");

                    InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
                    keyboard.setKeyboard(List.of(List.of(retry)));
                    message.setReplyMarkup(keyboard);
                }

                executeSendMessage(bot, chatId, message);
                return;
            }

            String expiry = activeReward.get().getExpiresAt().format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a"));

            sendMessage(bot, chatId, """
                    🎉 1 Hour Free Access is Active!
                    
                    ✅ Reward verified successfully.
                    ⏳ Valid until: %s
                    
                    Choose ONE story below for this reward period.
                    """.formatted(expiry));

            showRewardStorySelection(bot, chatId, user, 0);

        } catch (Exception e) {
            log.error("handleRewardSuccessReturn failed telegramId={}", user != null ? user.getTelegramId() : null, e);
        }
    }

    private String extractStartPayload(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0];

        int mentionIndex = command.indexOf('@');
        if (mentionIndex >= 0) {
            command = command.substring(0, mentionIndex);
        }

        if (!"/start".equalsIgnoreCase(command) || parts.length < 2) {
            return "";
        }

        return parts[1].trim();
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

        if (isOwnerPanelCommand(ownerCommand)) {

            // /start, /panel and /ownerpanel all open the same
            // deterministic OWNER management panel.
            searchStoryContext.remove(chatId);
            storyIconUploadContext.remove(chatId);
            sendOwnerPanel(bot, chatId);

            return;
        }

        if ("/usage".equals(ownerCommand)) {
            sendOwnerUsageGuide(bot, chatId);
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
            } else if (targetUser.getRole() == UserRole.OWNER) {
                sendMessage(bot, chatId, "ℹ️ OWNER has full access and is not managed through the user details screen.");
            } else {
                // Use the SAME interactive USER DETAILS screen used by /users.
                // Direct command starts with return page 0.
                showUserDetailsScreen(
                        bot,
                        chatId,
                        targetUser.getTelegramId(),
                        0,
                        null);
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
        // /trialonsubscription 2026-08-31
        // =====================================

        if ("/trialonsubscription".equals(ownerCommand) || "/trailonsubscription".equals(ownerCommand)) {

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
                        
                        /trialonsubscription 2026-08-31
                        
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
                        
                        /trialonsubscription 2026-08-31
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

        if ("/trialoffsubscription".equals(ownerCommand) || "/trailoffsubscription".equals(ownerCommand)) {

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
        // DIRECT INDIVIDUAL ACCESS COMMANDS
        // =====================================

        if ("/trial".equals(ownerCommand) || "/trail".equals(ownerCommand)) {
            handleIndividualTrialCommand(bot, chatId, text);
            return;
        }

        if ("/activate".equals(ownerCommand)) {
            handlePaidPlanActivationCommand(bot, chatId, text);
            return;
        }

        if ("/expire".equals(ownerCommand)) {
            handleExpireUserCommand(bot, chatId, text);
            return;
        }

        // =====================================
// ADMIN APPROVE COMMAND
//
// Example:
// /approveAdmin @john
// =====================================

        if ("/approveadmin".equals(ownerCommand)) {


            if (!chatId.equals(telegramConfig.getOwnerId())) {

                sendMessage(bot, chatId, """
                ❌ Access Denied
                
                Only OWNER can approve admin.
                """);

                return;
            }


            TelegramUser targetUser = resolveOwnerTargetUser(text);


            if(targetUser == null){

                sendMessage(bot, chatId, """
                ❌ User not found
                
                Usage:
                
                /approveAdmin @username
                """);

                return;
            }


            if(targetUser.getRole() == UserRole.OWNER){

                sendMessage(bot, chatId,
                        "❌ OWNER role cannot be changed.");

                return;
            }


            targetUser = telegramUserService.updateUserRole(
                    targetUser,
                    UserRole.ADMIN
            );
            telegramRoleCommandRegistrar.syncCommandsForUser(bot, targetUser);


            sendMessage(bot, chatId, """
            ✅ ADMIN APPROVED

            📚 Assign stories with /storyaccess @username
            
            👤 User:
            @%s
            
            🎭 Role:
            ADMIN
            """.formatted(
                    targetUser.getUsername()
            ));


            return;
        }



// =====================================
// ADMIN DISAPPROVE COMMAND
//
// Example:
// /disApproveAdmin @john
// =====================================


        if ("/disapproveadmin".equals(ownerCommand)) {


            if (!chatId.equals(telegramConfig.getOwnerId())) {

                sendMessage(bot, chatId, """
                ❌ Access Denied
                
                Only OWNER can remove admin.
                """);

                return;
            }


            TelegramUser targetUser = resolveOwnerTargetUser(text);


            if(targetUser == null){

                sendMessage(bot, chatId, """
                ❌ User not found
                
                Usage:
                
                /disApproveAdmin @username
                """);

                return;
            }


            if(targetUser.getRole() == UserRole.OWNER){

                sendMessage(bot, chatId,
                        "❌ OWNER role cannot be changed.");

                return;
            }



            targetUser = telegramUserService.updateUserRole(
                    targetUser,
                    UserRole.USER
            );
            telegramRoleCommandRegistrar.syncCommandsForUser(bot, targetUser);


            sendMessage(bot, chatId, """
            ❌ ADMIN DISAPPROVED
            
            👤 User:
            @%s
            
            🎭 New Role:
            USER
            """.formatted(
                    targetUser.getUsername()
            ));


            return;
        }

        // =====================================
        // UPDATE USER / SUBSCRIPTION
        // =====================================

        boolean wantsTrial = containsOwnerKeyword(text, "trial") || containsOwnerKeyword(text, "trail");
        boolean wantsActivate = containsOwnerKeyword(text, "activate");
        boolean wantsDisapproveAdmin = containsOwnerKeyword(text, "disapprove")
                || containsOwnerKeyword(text, "revoke")
                || (containsOwnerKeyword(text, "remove") && containsOwnerKeyword(text, "admin"));
        boolean wantsAdmin = containsOwnerKeyword(text, "admin") && !wantsDisapproveAdmin;
        boolean wantsExpire = containsOwnerKeyword(text, "expire");

        if (wantsTrial || wantsActivate || wantsAdmin || wantsDisapproveAdmin || wantsExpire) {

            TelegramUser targetUser = null;

            for (String part : parts) {

                String value = part.toLowerCase().replace("@", "");

                // SKIP RESERVED WORDS

                if (value.equals("trial") || value.equals("trail") || value.equals("activate") || value.equals("admin") || value.equals("disapprove") || value.equals("revoke") || value.equals("remove") || value.equals("expire") || value.equals("monthly") || value.equals("yearly") || value.equals("lifetime") || value.equals("make") || value.equals("history") || value.equals("show") || value.equals("subscription")) {

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

            // DISAPPROVE / REVOKE ADMIN -> USER

            if (wantsDisapproveAdmin) {

                if (!chatId.equals(telegramConfig.getOwnerId())) {

                    log.warn("Unauthorized admin role revoke attempt chatId={}", chatId);

                    sendMessage(bot, chatId, """
                            ❌ Access Denied

                            Only Bot Owner can remove
                            ADMIN role.
                            """);

                    return;
                }

                if (targetUser.getRole() == UserRole.OWNER) {
                    sendMessage(bot, chatId, "❌ OWNER role cannot be changed.");
                    return;
                }

                targetUser = telegramUserService.updateUserRole(targetUser, UserRole.USER);
                telegramRoleCommandRegistrar.syncCommandsForUser(bot, targetUser);

                sendMessage(bot, chatId, """
                        ✅ ADMIN DISAPPROVED

                        👤 User :
                        @%s

                        🎭 Role :
                        USER
                        """.formatted(targetUser.getUsername()));

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

                targetUser = telegramUserService.updateUserRole(targetUser, UserRole.ADMIN);
                telegramRoleCommandRegistrar.syncCommandsForUser(bot, targetUser);

                sendMessage(bot, chatId, """
                        ✅ ADMIN ROLE UPDATED

                        📚 Assign stories with /storyaccess @username
                        
                        👤 User :
                        @%s
                        
                        🎭 Role :
                        ADMIN
                        """.formatted(targetUser.getUsername()));

                return;
            }

            if (wantsTrial) {
                handleIndividualTrialCommand(bot, chatId, text);
                return;
            }

            if (wantsActivate) {
                handlePaidPlanActivationCommand(bot, chatId, text);
                return;
            }

            if (wantsExpire) {
                handleExpireUserCommand(bot, chatId, text);
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
                } else if (targetUser.getRole() == UserRole.OWNER) {
                    sendMessage(bot, chatId, "ℹ️ OWNER has full access and is not managed through the user details screen.");
                } else {
                    showUserDetailsScreen(
                            bot,
                            chatId,
                            targetUser.getTelegramId(),
                            0,
                            null);
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
                    /trialonsubscription 2026-08-31
                    
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

    private boolean isOwnerPanelCommand(String ownerCommand) {

        if (ownerCommand == null || ownerCommand.isBlank()) {
            return false;
        }

        return "/start".equals(ownerCommand)
                || "/panel".equals(ownerCommand)
                || "/ownerpanel".equals(ownerCommand);
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

        Set<String> reservedWords = Set.of("show", "get", "find", "user", "users", "details", "detail", "history", "subscription", "subscriptions", "payment", "payments", "activate", "trial", "trail", "monthly", "yearly", "lifetime", "make", "admin", "expire", "expired", "active", "inactive", "update", "role", "plan", "please", "for", "of");

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

                Open one user's interactive details screen.

                By username:
                /userdetails @username

                By Telegram ID:
                /userdetails 5999036520

                Example:
                /userdetails @shiva_143_sk

                The screen includes:
                • Approve / Remove Admin
                • Story Access
                • Subscription / reward details
                • Back to Users
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
                ⚙️ UPDATE USER SHORTCUTS

                🎁 Free Trial
                /trial @username
                /trial @username 15

                💳 Paid Plans
                /activate @username monthly
                /activate @username yearly
                /activate @username lifetime

                🎭 Admin Role
                /approveadmin @username
                /disapproveadmin @username

                📚 Story Access
                /storyaccess @username

                ⛔ Expire Subscription
                /expire @username

                📜 History
                /history @username
                """);
    }

    private void handleIndividualTrialCommand(
            TelegramLongPollingBot bot,
            Long chatId,
            String text) throws Exception {

        TelegramUser targetUser = resolveOwnerTargetUser(text);

        if (targetUser == null) {
            sendMessage(bot, chatId, """
                    🎁 INDIVIDUAL FREE TRIAL

                    Default 7 days:
                    /trial @username

                    Custom days (1-365):
                    /trial @username 15

                    Telegram ID also works:
                    /trial 6515281870 15

                    Example output:
                    ✅ FREE TRIAL ACTIVATED - 15 Days
                    """);
            return;
        }

        int trialDays = 7;
        Integer explicitDays = null;

        for (String token : text.trim().split("\\s+")) {
            String candidate = token.replaceAll("^[,.:;]+|[,.:;]+$", "");

            if (!candidate.matches("\\d+")) {
                continue;
            }

            long numericValue;
            try {
                numericValue = Long.parseLong(candidate);
            } catch (NumberFormatException e) {
                continue;
            }

            // When Telegram ID is used as the target, do not confuse it with days.
            if (targetUser.getTelegramId() != null && numericValue == targetUser.getTelegramId()) {
                continue;
            }

            explicitDays = numericValue > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) numericValue;
            break;
        }

        if (explicitDays != null) {
            trialDays = explicitDays;
        }

        if (trialDays < 1 || trialDays > 365) {
            sendMessage(bot, chatId, """
                    ❌ Trial days must be between 1 and 365.

                    Examples:
                    /trial @username
                    /trial @username 15
                    """);
            return;
        }

        Subscription subscription = subscriptionService.createOrUpdateSubscription(
                targetUser,
                SubscriptionPlan.FREE,
                BillingType.MONTHLY,
                BigDecimal.ZERO,
                trialDays);

        sendMessage(bot, chatId, """
                ✅ FREE TRIAL ACTIVATED

                👤 User: %s
                🎁 Plan: FREE TRIAL
                ⏳ Validity: %d Days
                📅 Start: %s
                🏁 Valid Until: %s
                """.formatted(
                formatTargetUser(targetUser),
                trialDays,
                subscription.getStartDate(),
                subscription.getExpiryDate()));
    }

    private void handlePaidPlanActivationCommand(
            TelegramLongPollingBot bot,
            Long chatId,
            String text) throws Exception {

        TelegramUser targetUser = resolveOwnerTargetUser(text);

        if (targetUser == null) {
            sendMessage(bot, chatId, """
                    💳 ACTIVATE SUBSCRIPTION

                    Monthly:
                    /activate @username monthly

                    Yearly:
                    /activate @username yearly

                    Lifetime:
                    /activate @username lifetime

                    Telegram ID also works:
                    /activate 6515281870 monthly
                    """);
            return;
        }

        String normalized = text.toLowerCase();
        SubscriptionPlan plan;
        BillingType billingType;
        BigDecimal amount;
        int validityDays;

        if (containsOwnerKeyword(normalized, "monthly")) {
            plan = SubscriptionPlan.MONTHLY;
            billingType = BillingType.MONTHLY;
            amount = new BigDecimal("299");
            validityDays = 30;
        } else if (containsOwnerKeyword(normalized, "yearly")) {
            plan = SubscriptionPlan.YEARLY;
            billingType = BillingType.YEARLY;
            amount = new BigDecimal("1999");
            validityDays = 365;
        } else if (containsOwnerKeyword(normalized, "lifetime")) {
            plan = SubscriptionPlan.LIFETIME;
            billingType = BillingType.LIFETIME;
            amount = new BigDecimal("4999");
            validityDays = 36500;
        } else {
            sendMessage(bot, chatId, """
                    ❌ Plan is required.

                    Examples:
                    /activate @username monthly
                    /activate @username yearly
                    /activate @username lifetime
                    """);
            return;
        }

        Subscription subscription = subscriptionService.createOrUpdateSubscription(
                targetUser,
                plan,
                billingType,
                amount,
                validityDays);

        sendMessage(bot, chatId, """
                ✅ SUBSCRIPTION ACTIVATED

                👤 User: %s
                📦 Plan: %s
                💰 Amount: %s
                📅 Start: %s
                🏁 Valid Until: %s
                """.formatted(
                formatTargetUser(targetUser),
                plan,
                amount,
                subscription.getStartDate(),
                subscription.getExpiryDate()));
    }

    private void handleExpireUserCommand(
            TelegramLongPollingBot bot,
            Long chatId,
            String text) throws Exception {

        TelegramUser targetUser = resolveOwnerTargetUser(text);

        if (targetUser == null) {
            sendMessage(bot, chatId, """
                    ⛔ EXPIRE USER ACCESS

                    Usage:
                    /expire @username
                    /expire 6515281870

                    Example output:
                    ✅ Current subscription expired
                    """);
            return;
        }

        subscriptionService.expireSubscription(targetUser);

        sendMessage(bot, chatId, """
                ⛔ SUBSCRIPTION EXPIRED

                👤 User: %s
                ✅ Current subscription access has been expired.

                ℹ️ Role-based ADMIN/OWNER access, active reward access,
                or an active global free trial follow their own rules.
                """.formatted(formatTargetUser(targetUser)));
    }

    private String formatTargetUser(TelegramUser user) {
        if (user == null) {
            return "-";
        }

        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return "@" + user.getUsername();
        }

        return String.valueOf(user.getTelegramId());
    }

    private void sendOwnerUsageGuide(TelegramLongPollingBot bot, Long chatId) throws Exception {
        sendMessage(bot, chatId, """
                👑 OWNER COMMAND GUIDE

                👥 USERS
                /users
                → Shows users, 50 per page.

                /userdetails @username
                → Shows role, access, plan and story count.
                Example: /userdetails @john

                /activeusers
                → Users with current access.

                /expiredusers
                → Users without current access.

                🎭 ADMIN ROLE
                /approveadmin @username
                → USER becomes ADMIN.
                Example: /approveadmin @john
                Output: ✅ ADMIN APPROVED

                /disapproveadmin @username
                → ADMIN becomes USER.
                Example: /disapproveadmin @john
                Output: ✅ ADMIN DISAPPROVED

                /storyaccess @username
                → Opens story grant/revoke screen.
                OWNER can manage USER and ADMIN.

                💳 USER ACCESS
                /trial @username
                → Gives default 7-day free trial.

                /trial @username 15
                → Gives 15-day free trial.

                /activate @username monthly
                → Activates MONTHLY plan for 30 days.

                /activate @username yearly
                → Activates YEARLY plan for 365 days.

                /activate @username lifetime
                → Activates LIFETIME plan.

                /expire @username
                → Expires current subscription rows.

                /history @username
                → Shows subscription history.

                /updateuser
                → Shows the short update command list.

                🌍 GLOBAL FREE TRIAL
                /trialonsubscription 2026-09-30
                → Enables campaign; max 7 days per eligible user,
                  never beyond campaign end date.

                /trialoffsubscription
                → Stops global free trial only.

                📚 STORIES
                /stories
                → Owner story library.

                /syncstories
                → Syncs configured Telegram story channels.

                /deleteinactivestory
                → Deletes inactive stories after safety checks.

                🖼 STORY ICONS
                /addstoryicon
                → Select story, then send a Telegram photo.

                /removestoryicon
                → Select story and remove its icon.

                🏠 PANEL
                /panel
                → Opens OWNER PANEL.

                ℹ️ Both @username and Telegram ID are accepted
                where a user target is required.
                """);
    }

    private void sendAdminUsageGuide(TelegramLongPollingBot bot, Long chatId) throws Exception {
        sendMessage(bot, chatId, """
                🛡️ ADMIN COMMAND GUIDE

                📚 STORY ACCESS
                /storyaccess @username
                → Opens USER story grant/revoke screen.

                Example:
                /storyaccess @john

                ADMIN rules:
                • Can manage USER only.
                • Can grant only stories OWNER assigned to this ADMIN.
                • Cannot manage another ADMIN or OWNER.

                🖼 STORY ICONS
                /addstoryicon
                → Shows only stories this ADMIN can manage.
                Select one and send the image as a Telegram photo.

                /removestoryicon
                → Shows only manageable stories and removes an icon.

                🏠 PANEL
                /panel
                → Opens ADMIN PANEL.

                📖 STORY LISTENING
                Send: Tamil Stories
                → Opens public story catalog.
                Episode access still follows the current access rules.

                ℹ️ /users, /trial, /activate, /expire,
                /approveadmin, global trial and story sync commands
                are OWNER-only.
                """);
    }

    private void sendAdminPanel(TelegramLongPollingBot bot, Long chatId) throws Exception {
        sendMessage(bot, chatId, """
                🛡️ ADMIN PANEL

                📚 USER STORY ACCESS
                /storyaccess @username
                → Grant/revoke only from your OWNER-assigned stories.

                🖼 STORY ICON MANAGEMENT
                /addstoryicon
                → Add/replace icon for your manageable stories.

                /removestoryicon
                → Remove icon from your manageable stories.

                📖 STORY LISTENING
                Tamil Stories
                → Browse the story catalog.

                ℹ️ HELP
                /usage
                → Examples + ADMIN permission rules.

                🏠 PANEL
                /panel
                → Open this ADMIN PANEL anytime.
                """);
    }

    private void sendOwnerPanel(TelegramLongPollingBot bot, Long chatId) throws Exception {

        sendMessage(bot, chatId, """
                👑 OWNER PANEL

                👥 USERS
                /users → 50 users/page
                /userdetails @username → user details
                /activeusers → current access users
                /expiredusers → no-current-access users

                🎭 ADMINS & STORY ACCESS
                /approveadmin @username → make ADMIN
                /disapproveadmin @username → make USER
                /storyaccess @username → grant/revoke stories

                💳 INDIVIDUAL ACCESS
                /trial @username → FREE 7 days
                /trial @username 15 → FREE 15 days
                /activate @username monthly → 30 days
                /activate @username yearly → 365 days
                /activate @username lifetime → lifetime
                /expire @username → expire subscription
                /history @username → subscription history
                /updateuser → show shortcuts

                🌍 GLOBAL FREE TRIAL
                /trialonsubscription 2026-09-30 → enable
                /trialoffsubscription → disable

                📚 STORIES
                /stories → owner library
                /syncstories → sync channels
                /deleteinactivestory → delete inactive

                🖼 STORY ICONS
                /addstoryicon → add/replace
                /removestoryicon → remove

                ℹ️ /usage → full examples + outputs
                🏠 /panel → reopen OWNER PANEL
                """);
    }

    private void showUsersByAccessStatus(TelegramLongPollingBot bot, Long chatId, boolean activeAccess, int page, Integer messageId) {

        try {

            int size = 10;

            List<TelegramUser> filteredUsers = telegramUserService.getAllNonOwnerUsers().stream().filter(user -> subscriptionService.hasAccess(user) == activeAccess).toList();

            if (filteredUsers.isEmpty()) {

                sendMessage(bot, chatId, activeAccess ? "✅ No active users found." : "✅ No expired/no-access users found.");

                return;
            }

            int totalPages = (int) Math.ceil((double) filteredUsers.size() / size);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int start = safePage * size;
            int end = Math.min(start + size, filteredUsers.size());

            StringBuilder builder = new StringBuilder();

            builder.append(activeAccess ? "✅ ACTIVE USERS\n\n" : "⛔ EXPIRED / NO-ACCESS USERS\n\n");

            for (TelegramUser user : filteredUsers.subList(start, end)) {

                builder.append("🆔 ").append(user.getTelegramId()).append("\n");
                builder.append("👤 ");
                builder.append(user.getUsername() == null || user.getUsername().isBlank() ? "No Username" : "@" + user.getUsername());
                builder.append("\n");
                builder.append("🎭 Role: ").append(user.getRole()).append("\n");
                builder.append("🕒 Last Active: ").append(user.getLastActiveAt()).append("\n");
                builder.append("━━━━━━━━━━━━━━\n");
            }

            builder.append("\n📄 Page ").append(safePage + 1).append(" / ").append(totalPages);

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
            executeSendMessage(bot, chatId, sendMessage);

        } catch (Exception e) {
            log.error("showUsersByAccessStatus failed activeAccess={} page={}", activeAccess, page, e);
        }
    }

    // =========================================
    // SHOW USERS (OWNER)
    // =========================================

    private void showUsers(TelegramLongPollingBot bot, Long chatId, int page, Integer messageId) {

        try {

            int requestedPage = Math.max(page, 0);

            Page<TelegramUser> users =
                    telegramUserService.getUsers(requestedPage, USERS_PAGE_SIZE);

            // If a stale callback points to a page that no longer exists
            // (for example after users are deleted), move to the last page.
            if (users.getTotalPages() > 0 && requestedPage >= users.getTotalPages()) {
                requestedPage = users.getTotalPages() - 1;
                users = telegramUserService.getUsers(requestedPage, USERS_PAGE_SIZE);
            }

            int currentPage = users.getTotalPages() == 0 ? 0 : users.getNumber();
            int totalPages = Math.max(users.getTotalPages(), 1);
            long totalUsers = users.getTotalElements();

            long from = totalUsers == 0
                    ? 0
                    : ((long) currentPage * USERS_PAGE_SIZE) + 1;
            long to = totalUsers == 0
                    ? 0
                    : Math.min(from + users.getNumberOfElements() - 1L, totalUsers);

            StringBuilder builder = new StringBuilder();

            builder.append("👑 USERS MANAGEMENT\n\n");
            builder.append("👥 Total Users: ").append(totalUsers).append("\n");
            builder.append("📄 Page: ").append(currentPage + 1).append(" / ").append(totalPages).append("\n");
            builder.append("📦 Showing: ").append(from).append(" - ").append(to).append("\n\n");

            if (users.isEmpty()) {
                builder.append("No users found.");
            } else {
                builder.append("Select a user to manage 👇");
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            int serialNumber = currentPage * USERS_PAGE_SIZE + 1;

            for (TelegramUser listedUser : users.getContent()) {

                InlineKeyboardButton userButton = new InlineKeyboardButton();

                String roleIcon = listedUser.getRole() == UserRole.ADMIN
                        ? "🛡️"
                        : "👤";

                userButton.setText(
                        serialNumber++
                                + ". "
                                + roleIcon
                                + " "
                                + getUserManagementDisplayName(listedUser)
                                + " · "
                                + listedUser.getRole());

                userButton.setCallbackData(
                        "manageuser_"
                                + listedUser.getTelegramId()
                                + "_"
                                + currentPage);

                // One user per row keeps usernames readable on mobile.
                rows.add(List.of(userButton));
            }

            List<InlineKeyboardButton> navigation = new ArrayList<>();

            if (users.hasPrevious()) {
                InlineKeyboardButton previous = new InlineKeyboardButton();
                previous.setText("⬅️ Previous");
                previous.setCallbackData("users_" + (currentPage - 1));
                navigation.add(previous);
            }

            InlineKeyboardButton indicator = new InlineKeyboardButton();
            indicator.setText((currentPage + 1) + "/" + totalPages);
            indicator.setCallbackData("ignore");
            navigation.add(indicator);

            if (users.hasNext()) {
                InlineKeyboardButton next = new InlineKeyboardButton();
                next.setText("Next ➡️");
                next.setCallbackData("users_" + (currentPage + 1));
                navigation.add(next);
            }

            rows.add(navigation);

            InlineKeyboardButton refresh = new InlineKeyboardButton();
            refresh.setText("🔄 Refresh");
            refresh.setCallbackData("users_" + currentPage);
            rows.add(List.of(refresh));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(rows);

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
            executeSendMessage(bot, chatId, sendMessage);

        } catch (Exception e) {
            log.error("showUsers failed page={}", page, e);
        }
    }

    // =========================================
    // SHOW SELECTED USER DETAILS (OWNER)
    // =========================================

    private void showUserDetailsScreen(
            TelegramLongPollingBot bot,
            Long chatId,
            Long targetTelegramId,
            int sourcePage,
            Integer messageId) {

        try {

            int safeSourcePage = Math.max(sourcePage, 0);

            TelegramUser targetUser =
                    telegramUserService.getUserByTelegramId(targetTelegramId);

            if (targetUser == null) {
                sendMessage(bot, chatId, "❌ User not found.");
                showUsers(bot, chatId, safeSourcePage, messageId);
                return;
            }

            if (targetUser.getRole() == UserRole.OWNER) {
                sendMessage(bot, chatId, "❌ OWNER account cannot be managed from the users list.");
                showUsers(bot, chatId, safeSourcePage, messageId);
                return;
            }

            boolean subscriptionActive = subscriptionService.hasActiveSubscription(targetUser);
            boolean rewardTrialActive = rewardTrialService.hasActiveRewardTrial(targetUser);
            boolean globalTrialActive = globalTrialService.hasGlobalTrialAccess(targetUser);

            String accessSource;

            // GLOBAL TRIAL has the highest temporary access priority for ADMIN/USER.
            if (globalTrialActive) {
                accessSource = "GLOBAL TRIAL";
            } else if (targetUser.getRole() == UserRole.ADMIN) {
                accessSource = "ADMIN + OWNER STORY MAPPING";
            } else if (subscriptionActive) {
                accessSource = "SUBSCRIPTION";
            } else if (rewardTrialActive) {
                accessSource = "REWARD TRIAL (1 HOUR)";
            } else {
                accessSource = "NO ACCESS";
            }

            long assignedStoryCount =
                    storyAccessService.getAssignedStoryCount(targetUser);

            String lastActive = targetUser.getLastActiveAt() == null
                    ? "-"
                    : targetUser.getLastActiveAt().format(USER_MANAGEMENT_DATE_TIME_FORMAT);

            String joinedAt = targetUser.getJoinedAt() == null
                    ? "-"
                    : targetUser.getJoinedAt().format(USER_MANAGEMENT_DATE_TIME_FORMAT);

            String fullName = ((targetUser.getFirstName() == null ? "" : targetUser.getFirstName())
                    + " "
                    + (targetUser.getLastName() == null ? "" : targetUser.getLastName())).trim();

            if (fullName.isBlank()) {
                fullName = "-";
            }

            // Keep subscription information on the SAME screen instead of having
            // a separate read-only /userdetails output.
            List<Subscription> subscriptionHistory =
                    subscriptionService.getUserSubscriptionHistory(targetUser);
            Subscription latestSubscription =
                    subscriptionHistory.isEmpty() ? null : subscriptionHistory.get(0);

            String latestPlan = latestSubscription == null
                    ? "-"
                    : String.valueOf(latestSubscription.getPlan());
            String latestStatus = latestSubscription == null
                    ? "-"
                    : String.valueOf(latestSubscription.getStatus());
            String latestStart = latestSubscription == null
                    ? "-"
                    : String.valueOf(latestSubscription.getStartDate());
            String latestExpiry = latestSubscription == null
                    ? "-"
                    : String.valueOf(latestSubscription.getExpiryDate());
            String rewardExpiry = rewardTrialService
                    .getRewardExpiry(targetUser)
                    .map(value -> value.format(USER_MANAGEMENT_DATE_TIME_FORMAT))
                    .orElse("-");

            String text = """
                    👤 USER DETAILS

                    👤 User: %s
                    🆔 Telegram ID: %s
                    📝 Name: %s
                    🎭 Role: %s

                    🔐 Current Access: %s
                    📚 Assigned Stories: %d

                    📦 Latest Plan: %s
                    📌 Latest Status: %s
                    📅 Start: %s
                    ⏳ Expiry: %s
                    🎁 Reward Expiry: %s

                    🕒 Joined: %s
                    🕒 Last Active: %s

                    Choose an action 👇
                    """.formatted(
                    getUserManagementDisplayName(targetUser),
                    targetUser.getTelegramId(),
                    fullName,
                    targetUser.getRole(),
                    accessSource,
                    assignedStoryCount,
                    latestPlan,
                    latestStatus,
                    latestStart,
                    latestExpiry,
                    rewardExpiry,
                    joinedAt,
                    lastActive);

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            InlineKeyboardButton roleButton = new InlineKeyboardButton();

            if (targetUser.getRole() == UserRole.ADMIN) {
                roleButton.setText("👤 Remove Admin Role");
                roleButton.setCallbackData(
                        "admin_disapprove_"
                                + targetUser.getTelegramId()
                                + "_"
                                + safeSourcePage);
            } else {
                roleButton.setText("🛡️ Approve as Admin");
                roleButton.setCallbackData(
                        "admin_approve_"
                                + targetUser.getTelegramId()
                                + "_"
                                + safeSourcePage);
            }

            rows.add(List.of(roleButton));

            InlineKeyboardButton storyAccess = new InlineKeyboardButton();
            storyAccess.setText("📚 Story Access (" + assignedStoryCount + ")");
            storyAccess.setCallbackData(
                    "storyaccess_page_"
                            + targetUser.getTelegramId()
                            + "_0_"
                            + safeSourcePage);
            rows.add(List.of(storyAccess));

            InlineKeyboardButton back = new InlineKeyboardButton();
            back.setText("⬅️ Back to Users");
            back.setCallbackData("users_" + safeSourcePage);
            rows.add(List.of(back));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(rows);

            if (messageId != null) {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(messageId);
                edit.setText(text);
                edit.setReplyMarkup(keyboard);
                bot.execute(edit);
                return;
            }

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText(text);
            sendMessage.setReplyMarkup(keyboard);
            executeSendMessage(bot, chatId, sendMessage);

        } catch (Exception e) {
            log.error(
                    "showUserDetailsScreen failed targetTelegramId={} sourcePage={}",
                    targetTelegramId,
                    sourcePage,
                    e);
            try {
                sendMessage(bot, chatId, "❌ Unable to load user details. Please try /users again.");
            } catch (Exception ignored) {
                // Original failure is already logged above.
            }
        }
    }

    private String getUserManagementDisplayName(TelegramUser user) {

        if (user == null) {
            return "Unknown User";
        }

        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return "@" + user.getUsername();
        }

        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName())
                + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();

        if (!fullName.isBlank()) {
            return fullName;
        }

        return String.valueOf(user.getTelegramId());
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

            executeSendMessage(bot, chatId, sendMessage);

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

            int count = stories.getNumber() * stories.getSize() + 1;

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

            executeSendMessage(bot, chatId, sendMessage);

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

    private void openEpisodeSearch(TelegramLongPollingBot bot, Long chatId, TelegramUser requestingUser, Long storyId) {

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

            // Reward users may browse every story. The FIRST playable story
            // they select during the active reward becomes the one reward-scoped
            // story. Selecting another story later does not replace it.
            boolean rewardOnlyAccess = isNormalUser(requestingUser)
                    && !subscriptionService.hasActiveSubscription(requestingUser)
                    && !globalTrialService.hasGlobalTrialAccess(requestingUser)
                    && rewardTrialService.hasActiveRewardTrial(requestingUser);

            RewardTrialService.RewardStorySelection rewardSelection = null;

            if (rewardOnlyAccess) {
                rewardSelection = rewardTrialService.selectStoryForActiveReward(
                        requestingUser,
                        story);
            }

            searchStoryContext.put(chatId, storyId);

            EpisodeLimitPolicy limitPolicy = getEpisodeLimitPolicy(requestingUser);

            String usageInfo;

            if (limitPolicy == EpisodeLimitPolicy.REWARD_TRIAL) {
                usageInfo = """
                        
                        🎁 1 Hour Reward Limits:
                        • Max %d per search
                        • Max %d per hour
                        • Max %d per day
                        """.formatted(limitPolicy.getPerSearch(), limitPolicy.getPerHour(), limitPolicy.getPerDay());
            } else if (limitPolicy == EpisodeLimitPolicy.STANDARD_USER) {
                usageInfo = """
                        
                        👤 USER Limits:
                        • Max %d per search
                        • Max %d per hour
                        • Max %d per day
                        """.formatted(limitPolicy.getPerSearch(), limitPolicy.getPerHour(), limitPolicy.getPerDay());
            } else {
                usageInfo = """
                        
                        👑 ADMIN / OWNER: Unlimited usage
                        """;
            }

            String rewardSelectionInfo = "";

            if (rewardSelection != null) {
                if (rewardSelection.selectedNow()) {
                    rewardSelectionInfo = "\n\n🎁 This story is now selected for your current 1-hour reward.";
                } else if (!rewardSelection.selectedStoryMatches()
                        && rewardSelection.selectedStory() != null) {
                    rewardSelectionInfo = "\n\n🎁 Current reward story: "
                            + rewardSelection.selectedStory().getTitle()
                            + "\nYou can browse this story, but episodes here require a NEW reward link to change the reward story.";
                }
            }

            String storyDetailsText = """
                    🎧 %s
                    
                    🔍 Custom Episode Search
                    
                    Available up to: EP %d
                    
                    Enter a single episode or a range.
                    
                    Examples:
                    10
                    10-15
                    10 to 15
                    
                    ⚠️ Maximum %d episodes per search.
                    🎵 Audio files will be sent directly.%s%s
                    """.formatted(
                    story.getTitle(),
                    latestEpisode,
                    limitPolicy.getPerSearch(),
                    usageInfo,
                    rewardSelectionInfo);

            // Only the presentation changes here. Episode/search/access logic above stays untouched.
            sendStoryDetailsWithIcon(bot, chatId, story, storyDetailsText);

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

    private void handleEpisodeRangeSearch(TelegramLongPollingBot bot, Long chatId, TelegramUser requestingUser, String text) {

        try {

            Long storyId = searchStoryContext.get(chatId);

            if (storyId == null) {

                sendMessage(bot, chatId, """
                        ❌ Search Context Not Found
                        
                        Please select a story first.
                        """);

                return;
            }

            String normalized = text == null ? "" : text.trim().toLowerCase();

            // Accept both range separators: "10-15" and "10 to 15".
            normalized = normalized.replaceAll("\\s*to\\s*", "-")
                    .replaceAll("\\s*-\\s*", "-");

            int start;
            int end;

            if (normalized.matches("\\d+")) {
                // Single episode search: 10 => start=10, end=10
                start = Integer.parseInt(normalized);
                end = start;
            } else if (normalized.matches("\\d+-\\d+")) {
                String[] split = normalized.split("-", 2);
                start = Integer.parseInt(split[0]);
                end = Integer.parseInt(split[1]);
            } else {
                sendMessage(bot, chatId, """
                        ❌ Invalid Episode Search

                        Supported formats:
                        10
                        10-15
                        10 to 15
                        """);
                return;
            }

            if (start <= 0 || end <= 0) {

                sendMessage(bot, chatId, """
                        ❌ Invalid Episode Range
                        
                        Episode numbers must be greater than 0.
                        
                        Examples: 10, 10-15, 10 to 15
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

            EpisodeLimitPolicy limitPolicy = getEpisodeLimitPolicy(requestingUser);
            int maxPerSearch = limitPolicy.getPerSearch();

            if (requestedCount > maxPerSearch) {

                sendMessage(bot, chatId, """
                        ❌ Search Range Too Large
                        
                        Maximum %d episodes are allowed per search.
                        
                        Examples:
                        1-50
                        51-100
                        101-150
                        """.formatted(maxPerSearch));

                return;
            }

            Story story = storyService.getStoryById(storyId);

            if (story == null) {

                searchStoryContext.remove(chatId);

                sendMessage(bot, chatId, "❌ Story not found");

                return;
            }

            // Story names are public, but audio requests require BOTH:
            // 1) an active access source, and
            // 2) permission for this exact story.
            if (!subscriptionService.hasAccess(requestingUser)) {
                sendSubscriptionRequiredMessage(bot, chatId, null);
                return;
            }

            if (!storyAccessService.hasEpisodeAccess(requestingUser, story)) {

                searchStoryContext.remove(chatId);

                String selectedRewardStory = rewardTrialService
                        .getSelectedRewardStory(requestingUser)
                        .map(Story::getTitle)
                        .orElse("");

                if (!selectedRewardStory.isBlank()
                        && rewardTrialService.hasActiveRewardTrial(requestingUser)) {
                    sendRewardStoryMismatchMessage(bot, chatId, requestingUser);
                } else {
                    sendMessage(bot, chatId, """
                            🔒 You don't have access to this story.

                            You can browse all story names, but episode access
                            is available only for stories assigned to you.

                            Please contact Admin / Owner for story access.
                            """);
                }

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
                sendMessage(bot, chatId, "❌ Invalid episode search. Use 10, 10-15, or 10 to 15.");
            } catch (Exception ignore) {
            }

        } catch (Exception e) {

            log.error("handleEpisodeRangeSearch failed chatId={} text={}", chatId, text, e);
        }
    }

    private void sendRewardStoryMismatchMessage(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser user) throws Exception {

        Story selectedStory = rewardTrialService
                .getSelectedRewardStory(user)
                .orElse(null);

        if (selectedStory == null) {
            sendMessage(bot, chatId, """
                    🔒 This story is not available for the current reward.

                    Please choose your reward story first.
                    """);
            return;
        }

        InlineKeyboardButton continueStory = new InlineKeyboardButton();
        continueStory.setText("🎧 Continue " + selectedStory.getTitle());
        continueStory.setCallbackData("story_" + selectedStory.getId());

        InlineKeyboardButton changeStory = new InlineKeyboardButton();
        changeStory.setText("🔄 Change Story - New Reward Link");
        changeStory.setCallbackData("reward_change_story");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(
                List.of(continueStory),
                List.of(changeStory)
        ));

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("""
                🔒 This story is not part of your current 1-hour reward.

                🎁 Current Reward Story:
                %s

                You can browse every story name.

                To listen to a DIFFERENT story, the current reward must end
                and you must complete a NEW reward link successfully.
                """.formatted(selectedStory.getTitle()));
        message.setReplyMarkup(keyboard);
        executeSendMessage(bot, chatId, message);
    }

    // =========================================
    // STORY ACCESS MANAGEMENT
    // OWNER -> ADMIN/USER
    // ADMIN -> USER only, within ADMIN assigned stories
    // =========================================

    private boolean isStoryAccessCommand(String text) {

        if (text == null || text.isBlank()) {
            return false;
        }

        return "/storyaccess".equals(normalizeOwnerCommand(text));
    }

    private void handleStoryAccessCommand(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser actor,
            String text) throws Exception {

        TelegramUser targetUser = resolveOwnerTargetUser(text);

        if (targetUser == null) {
            sendMessage(bot, chatId, """
                    📚 STORY ACCESS MANAGEMENT

                    Usage:
                    /storyaccess @username
                    /storyaccess 5999036520

                    OWNER:
                    • Can assign/revoke stories for ADMIN and USER.

                    ADMIN:
                    • Can assign/revoke stories for USER only.
                    • Can assign only stories given to the ADMIN by OWNER.
                    """);
            return;
        }

        showStoryAccessManagement(
                bot,
                chatId,
                actor,
                targetUser.getTelegramId(),
                0,
                null,
                null);
    }

    private void showStoryAccessManagement(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser actor,
            Long targetTelegramId,
            int page,
            Integer returnUsersPage,
            Integer messageId) throws Exception {

        if (!isAdminOrOwner(actor)) {
            sendMessage(bot, chatId, "❌ Admin access required.");
            return;
        }

        TelegramUser targetUser = telegramUserService.getUserByTelegramId(targetTelegramId);

        if (targetUser == null) {
            sendMessage(bot, chatId, "❌ User not found.");
            return;
        }

        if (targetUser.getRole() == UserRole.OWNER) {
            sendMessage(bot, chatId, "❌ OWNER has automatic access to all stories and cannot be mapped.");
            return;
        }

        if (actor.getRole() == UserRole.ADMIN && targetUser.getRole() != UserRole.USER) {
            sendMessage(bot, chatId, "❌ ADMIN can manage story access only for USER accounts.");
            return;
        }

        int size = 10;
        Page<Story> stories = storyAccessService.getAssignableStories(actor, page, size);

        String targetName = targetUser.getUsername() == null || targetUser.getUsername().isBlank()
                ? String.valueOf(targetUser.getTelegramId())
                : "@" + targetUser.getUsername();

        if (stories.isEmpty()) {

            String noStoriesMessage = actor.getRole() == UserRole.ADMIN
                    ? "❌ No stories are assigned to your ADMIN account. Ask OWNER to assign stories first."
                    : "❌ No active stories are available for assignment.";

            sendMessage(bot, chatId, noStoriesMessage);
            return;
        }

        StringBuilder builder = new StringBuilder();

        builder.append("📚 STORY ACCESS\n\n");
        builder.append("👤 Target: " ).append(targetName).append("\n");
        builder.append("🎭 Role: " ).append(targetUser.getRole()).append("\n");
        builder.append("✅ Assigned Stories: " )
                .append(storyAccessService.getAssignedStoryCount(targetUser))
                .append("\n\n");

        if (targetUser.getRole() == UserRole.ADMIN) {
            builder.append("ℹ️ ADMIN access is valid only for OWNER-granted stories.\n\n");
        }

        builder.append("Tap a story to grant/revoke access.\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (Story story : stories) {

            boolean assigned = storyAccessService.hasAssignedStoryAccess(targetUser, story);

            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText((assigned ? "✅ " : "➕ ") + story.getTitle());
            String toggleCallback =
                    "storyaccess_toggle_"
                            + targetUser.getTelegramId()
                            + "_"
                            + story.getId()
                            + "_"
                            + stories.getNumber();

            if (returnUsersPage != null) {
                toggleCallback += "_" + returnUsersPage;
            }

            button.setCallbackData(toggleCallback);

            rows.add(List.of(button));
        }

        List<InlineKeyboardButton> nav = new ArrayList<>();

        if (stories.hasPrevious()) {
            InlineKeyboardButton previous = new InlineKeyboardButton();
            previous.setText("⬅️ Prev");
            String previousCallback =
                    "storyaccess_page_"
                            + targetUser.getTelegramId()
                            + "_"
                            + (stories.getNumber() - 1);

            if (returnUsersPage != null) {
                previousCallback += "_" + returnUsersPage;
            }

            previous.setCallbackData(previousCallback);
            nav.add(previous);
        }

        InlineKeyboardButton pageButton = new InlineKeyboardButton();
        pageButton.setText("📄 " + (stories.getNumber() + 1) + "/" + stories.getTotalPages());
        pageButton.setCallbackData("ignore");
        nav.add(pageButton);

        if (stories.hasNext()) {
            InlineKeyboardButton next = new InlineKeyboardButton();
            next.setText("Next ➡️");
            String nextCallback =
                    "storyaccess_page_"
                            + targetUser.getTelegramId()
                            + "_"
                            + (stories.getNumber() + 1);

            if (returnUsersPage != null) {
                nextCallback += "_" + returnUsersPage;
            }

            next.setCallbackData(nextCallback);
            nav.add(next);
        }

        rows.add(nav);

        InlineKeyboardButton back = new InlineKeyboardButton();
        if (actor.getRole() == UserRole.OWNER) {
            if (returnUsersPage != null) {
                back.setText("⬅️ User Details");
                back.setCallbackData(
                        "manageuser_"
                                + targetUser.getTelegramId()
                                + "_"
                                + returnUsersPage);
            } else {
                back.setText("⬅️ Users");
                back.setCallbackData("users_0");
            }
        } else {
            back.setText("⬅️ Admin Panel");
            back.setCallbackData("role_panel");
        }
        rows.add(List.of(back));

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);

        if (messageId != null) {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(messageId);
            edit.setText(builder.toString());
            edit.setReplyMarkup(keyboard);
            bot.execute(edit);
            return;
        }

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(builder.toString());
        message.setReplyMarkup(keyboard);
        executeSendMessage(bot, chatId, message);
    }

    // =========================================
    // STORY ICON MANAGEMENT
    // ADMIN / OWNER ONLY
    // =========================================

    private boolean isAdminOrOwner(TelegramUser user) {

        return user != null
                && (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.OWNER);
    }

    /**
     * New explicit add/replace command.
     *
     * /storyicon is kept as a legacy alias so existing usage does not break.
     */
    private boolean isAddStoryIconCommand(String text) {

        if (text == null) {
            return false;
        }

        String value = text.trim().toLowerCase();

        return value.equals("/addstoryicon")
                || value.startsWith("/addstoryicon@")
                || value.equals("/storyicon")
                || value.startsWith("/storyicon@");
    }

    private boolean isRemoveStoryIconCommand(String text) {

        if (text == null) {
            return false;
        }

        String value = text.trim().toLowerCase();

        return value.equals("/removestoryicon")
                || value.startsWith("/removestoryicon@");
    }

    // =========================================
    // ADD / REPLACE STORY ICON - STORY LIST
    // =========================================

    private void showAddStoryIconSelection(TelegramLongPollingBot bot, Long chatId, TelegramUser actor, int page) throws Exception {

        try {

            int size = 10;
            Page<Story> stories = storyAccessService.getManageableStories(actor, page, size);

            if (stories.isEmpty()) {
                sendMessage(bot, chatId, "❌ No stories available.");
                return;
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            for (Story story : stories) {

                InlineKeyboardButton button = new InlineKeyboardButton();

                boolean hasIcon = story.getStoryIconFileId() != null
                        && !story.getStoryIconFileId().isBlank();

                String iconStatus = hasIcon ? "♻️" : "🖼️";

                button.setText(iconStatus + " " + story.getTitle());
                button.setCallbackData("addstoryicon_select_" + story.getId());

                rows.add(List.of(button));
            }

            List<InlineKeyboardButton> nav = new ArrayList<>();

            if (page > 0) {
                InlineKeyboardButton prev = new InlineKeyboardButton();
                prev.setText("⬅️ Prev");
                prev.setCallbackData("addstoryicon_page_" + (page - 1));
                nav.add(prev);
            }

            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("📄 " + (page + 1) + "/" + stories.getTotalPages());
            pageButton.setCallbackData("ignore");
            nav.add(pageButton);

            if (stories.hasNext()) {
                InlineKeyboardButton next = new InlineKeyboardButton();
                next.setText("Next ➡️");
                next.setCallbackData("addstoryicon_page_" + (page + 1));
                nav.add(next);
            }

            rows.add(nav);

            InlineKeyboardButton backToPanel = new InlineKeyboardButton();
            backToPanel.setText(actor.getRole() == UserRole.OWNER ? "⬅️ Owner Panel" : "⬅️ Admin Panel");
            backToPanel.setCallbackData("role_panel");
            rows.add(List.of(backToPanel));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(rows);

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("""
                    🖼️ Add / Replace Story Icon

                    Select a story below.

                    🖼️ = No icon yet
                    ♻️ = Existing icon will be replaced
                    """);
            message.setReplyMarkup(keyboard);

            executeSendMessage(bot, chatId, message);

        } catch (Exception e) {
            log.error("showAddStoryIconSelection failed", e);
            sendMessage(bot, chatId, "❌ Unable to load stories for icon upload.");
        }
    }

    // =========================================
    // PREPARE STORY ICON UPLOAD
    // =========================================

    private void prepareStoryIconUpload(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser actor,
            Long storyId) throws Exception {

        Story story = storyService.getStoryById(storyId);

        if (story == null) {
            storyIconUploadContext.remove(chatId);
            sendMessage(bot, chatId, "❌ Story not found.");
            return;
        }

        if (!storyAccessService.hasStoryAccess(actor, story)) {
            storyIconUploadContext.remove(chatId);
            sendMessage(bot, chatId, "❌ You do not have access to manage this story.");
            return;
        }

        storyIconUploadContext.put(chatId, storyId);

        boolean replacing = story.getStoryIconFileId() != null
                && !story.getStoryIconFileId().isBlank();

        sendMessage(bot, chatId, """
                🖼️ Story Icon Upload

                Story: %s
                Action: %s

                Send the story image/photo now.

                ℹ️ Please send it as a Telegram photo.
                """.formatted(
                story.getTitle(),
                replacing ? "Replace Existing Icon" : "Add New Icon"));
    }

    // =========================================
    // RECEIVE AND SAVE STORY ICON PHOTO
    // =========================================

    private void handleStoryIconUpload(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser telegramUser,
            Message message) throws Exception {

        try {

            if (!isAdminOrOwner(telegramUser)) {
                storyIconUploadContext.remove(chatId);
                sendMessage(bot, chatId, "❌ Admin access required.");
                return;
            }

            Long storyId = storyIconUploadContext.get(chatId);

            if (storyId == null) {
                return;
            }

            Story story = storyService.getStoryById(storyId);

            if (story == null) {
                storyIconUploadContext.remove(chatId);
                sendMessage(bot, chatId, "❌ Story not found.");
                return;
            }

            if (!storyAccessService.hasStoryAccess(telegramUser, story)) {
                storyIconUploadContext.remove(chatId);
                sendMessage(bot, chatId, "❌ You no longer have access to manage this story.");
                return;
            }

            if (!message.hasPhoto() || message.getPhoto() == null || message.getPhoto().isEmpty()) {
                sendMessage(bot, chatId, "❌ Please send the image as a Telegram photo.");
                return;
            }

            // Telegram returns different PhotoSize values for the same image.
            // The last entry is the largest one. Store only Telegram file_id.
            var photos = message.getPhoto();
            var largestPhoto = photos.get(photos.size() - 1);

            story.setStoryIconFileId(largestPhoto.getFileId());
            storyService.save(story);

            storyIconUploadContext.remove(chatId);

            sendMessage(bot, chatId, """
                    ✅ Story icon saved successfully.

                    Story: %s
                    """.formatted(story.getTitle()));

        } catch (Exception e) {
            log.error("handleStoryIconUpload failed", e);
            sendMessage(bot, chatId, "❌ Unable to save story icon.");
        }
    }

    // =========================================
    // REMOVE STORY ICON - STORY LIST
    // =========================================

    private void showRemoveStoryIconSelection(TelegramLongPollingBot bot, Long chatId, TelegramUser actor, int page) throws Exception {

        try {

            int size = 10;
            Page<Story> stories = storyAccessService.getManageableStories(actor, page, size);

            if (stories.isEmpty()) {
                sendMessage(bot, chatId, "❌ No stories available.");
                return;
            }

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            for (Story story : stories) {

                InlineKeyboardButton button = new InlineKeyboardButton();

                boolean hasIcon = story.getStoryIconFileId() != null
                        && !story.getStoryIconFileId().isBlank();

                String iconStatus = hasIcon ? "🗑️" : "➖";
                String suffix = hasIcon ? "" : " (No Icon)";

                button.setText(iconStatus + " " + story.getTitle() + suffix);
                button.setCallbackData("removestoryicon_select_" + story.getId() + "_" + page);

                rows.add(List.of(button));
            }

            List<InlineKeyboardButton> nav = new ArrayList<>();

            if (page > 0) {
                InlineKeyboardButton prev = new InlineKeyboardButton();
                prev.setText("⬅️ Prev");
                prev.setCallbackData("removestoryicon_page_" + (page - 1));
                nav.add(prev);
            }

            InlineKeyboardButton pageButton = new InlineKeyboardButton();
            pageButton.setText("📄 " + (page + 1) + "/" + stories.getTotalPages());
            pageButton.setCallbackData("ignore");
            nav.add(pageButton);

            if (stories.hasNext()) {
                InlineKeyboardButton next = new InlineKeyboardButton();
                next.setText("Next ➡️");
                next.setCallbackData("removestoryicon_page_" + (page + 1));
                nav.add(next);
            }

            rows.add(nav);

            InlineKeyboardButton backToPanel = new InlineKeyboardButton();
            backToPanel.setText(actor.getRole() == UserRole.OWNER ? "⬅️ Owner Panel" : "⬅️ Admin Panel");
            backToPanel.setCallbackData("role_panel");
            rows.add(List.of(backToPanel));

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(rows);

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("""
                    🗑️ Remove Story Icon

                    Select a story below.

                    🗑️ = Icon exists and can be removed
                    ➖ = Story currently has no icon
                    """);
            message.setReplyMarkup(keyboard);

            executeSendMessage(bot, chatId, message);

        } catch (Exception e) {
            log.error("showRemoveStoryIconSelection failed", e);
            sendMessage(bot, chatId, "❌ Unable to load stories for icon removal.");
        }
    }

    // =========================================
    // REMOVE STORY ICON - CONFIRMATION
    // =========================================

    private void showRemoveStoryIconConfirmation(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser actor,
            Long storyId,
            int page) throws Exception {

        Story story = storyService.getStoryById(storyId);

        if (story == null) {
            sendMessage(bot, chatId, "❌ Story not found.");
            return;
        }

        if (!storyAccessService.hasStoryAccess(actor, story)) {
            sendMessage(bot, chatId, "❌ You do not have access to manage this story.");
            return;
        }

        if (story.getStoryIconFileId() == null || story.getStoryIconFileId().isBlank()) {
            sendMessage(bot, chatId, """
                    ℹ️ This story does not have an icon.

                    Story: %s
                    """.formatted(story.getTitle()));
            return;
        }

        InlineKeyboardButton confirm = new InlineKeyboardButton();
        confirm.setText("✅ Yes, Remove Icon");
        confirm.setCallbackData("removestoryicon_confirm_" + storyId + "_" + page);

        InlineKeyboardButton cancel = new InlineKeyboardButton();
        cancel.setText("❌ Cancel");
        cancel.setCallbackData("removestoryicon_cancel_" + page);

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(
                List.of(confirm),
                List.of(cancel)));

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("""
                ⚠️ Remove Story Icon?

                Story: %s

                This removes only the saved story icon.
                Story and episode data will not be deleted.
                """.formatted(story.getTitle()));
        message.setReplyMarkup(keyboard);

        executeSendMessage(bot, chatId, message);
    }

    // =========================================
    // REMOVE SELECTED STORY ICON
    // =========================================

    private void removeStoryIcon(
            TelegramLongPollingBot bot,
            Long chatId,
            TelegramUser actor,
            Long storyId,
            int page) throws Exception {

        try {

            Story story = storyService.getStoryById(storyId);

            if (story == null) {
                sendMessage(bot, chatId, "❌ Story not found.");
                return;
            }

            if (!storyAccessService.hasStoryAccess(actor, story)) {
                sendMessage(bot, chatId, "❌ You do not have access to manage this story.");
                return;
            }

            if (story.getStoryIconFileId() == null || story.getStoryIconFileId().isBlank()) {
                sendMessage(bot, chatId, """
                        ℹ️ This story does not have an icon.

                        Story: %s
                        """.formatted(story.getTitle()));
                return;
            }

            story.setStoryIconFileId(null);
            storyService.save(story);

            // Only the icon file_id is removed. Story, episodes and all
            // subscription/access data remain unchanged.
            sendMessage(bot, chatId, """
                    ✅ Story icon removed successfully.

                    Story: %s
                    """.formatted(story.getTitle()));

            // Stay in the remove flow so multiple icons can be managed.
            showRemoveStoryIconSelection(bot, chatId, actor, Math.max(page, 0));

        } catch (Exception e) {
            log.error("removeStoryIcon failed storyId={}", storyId, e);
            sendMessage(bot, chatId, "❌ Unable to remove story icon.");
        }
    }

    // =========================================
    // STORY DETAILS WITH OPTIONAL ICON
    // =========================================

    private void sendStoryDetailsWithIcon(
            TelegramLongPollingBot bot,
            Long chatId,
            Story story,
            String detailsText) throws Exception {

        String fileId = story.getStoryIconFileId();

        if (fileId == null || fileId.isBlank()) {
            sendMessage(bot, chatId, detailsText);
            return;
        }

        try {

            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(String.valueOf(chatId));
            sendPhoto.setPhoto(new InputFile(fileId));
            sendPhoto.setCaption(detailsText);

            bot.execute(sendPhoto);

        } catch (Exception e) {

            // A stale/invalid Telegram file_id must never break story access.
            log.warn("Story icon send failed storyId={} - falling back to text", story.getId(), e);
            sendMessage(bot, chatId, detailsText);
        }
    }

    // =========================================
    // FORWARD PERMISSION
    //
    // USER        -> protected, cannot forward
    // ADMIN/OWNER -> unprotected, can forward
    // =========================================

    private boolean canForwardEpisodes(TelegramUser user) {

        return user != null && (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.OWNER);
    }

    private boolean isNormalUser(TelegramUser user) {
        return user != null && user.getRole() == UserRole.USER;
    }

    /**
     * Resolves the effective episode limit policy for the user's CURRENT
     * access source. All numeric limits live in EpisodeLimitPolicy.
     * <p>
     * Priority for normal USER accounts:
     * 1. Paid / manual subscription -> STANDARD_USER
     * 2. Global trial               -> STANDARD_USER
     * 3. Short-link 1-hour reward   -> REWARD_TRIAL
     * 4. No current access source   -> STANDARD_USER (access gate blocks use)
     * <p>
     * ADMIN / OWNER are unlimited.
     */
    private EpisodeLimitPolicy getEpisodeLimitPolicy(TelegramUser user) {

        if (user == null || user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.OWNER) {
            return EpisodeLimitPolicy.ADMIN_OWNER;
        }

        if (user.getRole() != UserRole.USER) {
            return EpisodeLimitPolicy.ADMIN_OWNER;
        }

        // Paid plans and individual/manual FREE trials are represented by
        // an active Subscription and always use the standard USER quota.
        if (subscriptionService.hasActiveSubscription(user)) {
            return EpisodeLimitPolicy.STANDARD_USER;
        }

        // Global trial takes priority if it is active while an old reward
        // row still exists. This keeps the standard USER quota.
        if (globalTrialService.hasGlobalTrialAccess(user)) {
            return EpisodeLimitPolicy.STANDARD_USER;
        }

        if (rewardTrialService.hasActiveRewardTrial(user)) {
            return EpisodeLimitPolicy.REWARD_TRIAL;
        }

        return EpisodeLimitPolicy.STANDARD_USER;
    }

    private void sendEpisodeLimitMessage(TelegramLongPollingBot bot, Long chatId, EpisodeUsageService.EpisodeQuota quota, EpisodeLimitPolicy policy, int sentInCurrentBatch) throws Exception {

        if (quota == null || policy == null) {
            return;
        }

        String accessType = policy == EpisodeLimitPolicy.REWARD_TRIAL ? "🎁 1 Hour Reward Access" : "👤 User Access";

        String batchInfo = sentInCurrentBatch > 0 ? "This batch delivered " + sentInCurrentBatch + " episode(s) before the limit was reached." : "";

        String limitMessage;

        if ("DAILY_LIMIT".equals(quota.reason())) {
            limitMessage = """
                    ⛔ Daily Episode Limit Reached
                    
                    %s
                    
                    📅 Today: %d / %d
                    ⏱ Hour: %d / %d
                    
                    %s
                    
                    You can continue after the daily counter resets.
                    """.formatted(accessType, quota.dailyUsed(), policy.getPerDay(), quota.hourlyUsed(), policy.getPerHour(), batchInfo);
        } else {
            limitMessage = """
                    ⛔ Hourly Episode Limit Reached
                    
                    %s
                    
                    ⏱ Current Hour: %d / %d
                    📅 Today: %d / %d
                    
                    %s
                    
                    You can continue after the hourly counter resets.
                    """.formatted(accessType, quota.hourlyUsed(), policy.getPerHour(), quota.dailyUsed(), policy.getPerDay(), batchInfo);
        }

        sendMessage(bot, chatId, limitMessage);
    }

    // =========================================
    // CUSTOM RANGE SEARCH
    //
    // MAX 50 EPISODES
    // DIRECT AUDIO DELIVERY
    // NO EPISODE LIST
    // NO EPISODE BUTTONS
    // =========================================

    private void sendEpisodesByRange(TelegramLongPollingBot bot, Long chatId, TelegramUser requestingUser, Story story, int start, int end) {

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

            EpisodeLimitPolicy initialLimitPolicy = getEpisodeLimitPolicy(requestingUser);
            int maxPerSearch = initialLimitPolicy.getPerSearch();

            if (requestedCount > maxPerSearch) {

                sendMessage(bot, chatId, """
                        ❌ Maximum %d episodes
                        allowed per search.
                        
                        Example:
                        
                        1-50
                        51-100
                        """.formatted(maxPerSearch));

                return;
            }

            // =====================================
            // USER HOURLY / DAILY LIMIT CHECK
            // ADMIN and OWNER are intentionally unlimited.
            // =====================================

            if (isNormalUser(requestingUser)) {
                EpisodeLimitPolicy policy = getEpisodeLimitPolicy(requestingUser);
                EpisodeUsageService.EpisodeQuota quota = episodeUsageService.getQuota(requestingUser, policy);

                if (!quota.allowed()) {
                    sendEpisodeLimitMessage(bot, chatId, quota, policy, 0);
                    return;
                }
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

                    log.info("Episode batch stopped because access ended telegramId={} storyId={} sentCount={}", requestingUser != null ? requestingUser.getTelegramId() : null, story.getId(), sentCount);

                    sendMessage(bot, chatId, """
                            ⛔ Access Ended
                            
                            Your free trial/subscription is no longer active.
                            
                            Remaining episodes were not sent.
                            """);

                    return;
                }

                if (!storyAccessService.hasEpisodeAccess(requestingUser, story)) {

                    log.info("Episode batch stopped because story access ended telegramId={} storyId={} sentCount={}", requestingUser != null ? requestingUser.getTelegramId() : null, story.getId(), sentCount);

                    searchStoryContext.remove(chatId);

                    sendMessage(bot, chatId, """
                            🔒 Story Access Ended

                            You no longer have episode access to this story.

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

                if (isNormalUser(requestingUser)) {
                    // Resolve the policy again before every audio so a change
                    // in access source while a batch is running is respected.
                    EpisodeLimitPolicy policy = getEpisodeLimitPolicy(requestingUser);
                    EpisodeUsageService.EpisodeQuota quota = episodeUsageService.getQuota(requestingUser, policy);

                    if (!quota.allowed()) {
                        log.info("Episode batch stopped by usage limit " + "telegramId={} policy={} reason={} " + "hourly={}/{} daily={}/{} sentCount={}", requestingUser.getTelegramId(), policy, quota.reason(), quota.hourlyUsed(), policy.getPerHour(), quota.dailyUsed(), policy.getPerDay(), sentCount);

                        sendEpisodeLimitMessage(bot, chatId, quota, policy, sentCount);
                        return;
                    }
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

                Message sentAudioMessage = bot.execute(sendAudio);

                if (isNormalUser(requestingUser)) {
                    if (sentAudioMessage != null && sentAudioMessage.getMessageId() != null) {
                        scheduleAutoDeleteBotMessage(bot, chatId, sentAudioMessage.getMessageId());
                    }

                    EpisodeLimitPolicy deliveredPolicy = getEpisodeLimitPolicy(requestingUser);
                    episodeUsageService.recordEpisodeDelivered(requestingUser, deliveredPolicy);
                }

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

            executeSendMessage(bot, chatId, completed);

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

        return executeSendMessage(bot, chatId, sendMessage);
    }

    // =========================================
    // CONTENT PROTECTION + AUTO DELETE
    //
    // USER        -> protected + delete bot content after 24 hours
    // ADMIN/OWNER -> unprotected + no auto delete
    // =========================================

    private Message executeSendMessage(TelegramLongPollingBot bot, Long chatId, SendMessage sendMessage) throws Exception {

        TelegramUser recipient = telegramUserService.getUserByTelegramId(chatId);
        boolean protectedContent = !canForwardEpisodes(recipient);

        sendMessage.setProtectContent(protectedContent);

        Message sentMessage = bot.execute(sendMessage);

        if (protectedContent && sentMessage != null && sentMessage.getMessageId() != null) {
            scheduleAutoDeleteBotMessage(bot, chatId, sentMessage.getMessageId());
        }

        return sentMessage;
    }

    // =========================================
    // PRIVATE USER INPUT FILTER
    //
    // The Telegram client may still display its native attachment button.
    // Bots cannot hide that UI in a private chat. This method enforces the
    // actual server-side rule for normal USER accounts.
    // =========================================

    private void rejectNonTextUserMessage(TelegramLongPollingBot bot, Long chatId, Message message) {

        Integer messageId = message != null ? message.getMessageId() : null;

        log.info("Blocked non-text private input telegramId={} chatId={} messageId={}", message != null && message.getFrom() != null ? message.getFrom().getId() : null, chatId, messageId);

        if (messageId != null) {
            try {
                bot.execute(new DeleteMessage(String.valueOf(chatId), messageId));
            } catch (Exception e) {
                log.warn("Failed to delete blocked private input chatId={} messageId={} reason={}", chatId, messageId, e.getMessage());
            }
        }

        try {
            sendMessage(bot, chatId, """
                    ✍️ Text messages only.
                    
                    Files, photos, audio, video, voice, stickers, polls,
                    locations and contacts are not accepted here.
                    """);
        } catch (Exception e) {
            log.warn("Failed to send text-only notice chatId={} reason={}", chatId, e.getMessage());
        }
    }

    private void scheduleAutoDeleteBotMessage(TelegramLongPollingBot bot, Long chatId, Integer messageId) {

        if (chatId == null || messageId == null) {
            return;
        }

        scheduler.schedule(() -> {
            try {
                bot.execute(new DeleteMessage(String.valueOf(chatId), messageId));
            } catch (Exception e) {
                log.debug("Auto delete skipped/failed chatId={} messageId={} reason={}", chatId, messageId, e.getMessage());
            }
        }, AUTO_DELETE_HOURS, TimeUnit.HOURS);
    }

    private void autoDeleteUserMessage(TelegramLongPollingBot bot, Long chatId, Integer userMessageId) {

        // Bot responses are already scheduled by executeSendMessage().
        // This helper only removes the triggering USER message in the
        // subscription-required flow. ADMIN/OWNER messages are never
        // auto-deleted.
        TelegramUser user = telegramUserService.getUserByTelegramId(chatId);

        if (canForwardEpisodes(user) || userMessageId == null) {
            return;
        }

        scheduler.schedule(() -> {
            try {
                bot.execute(new DeleteMessage(String.valueOf(chatId), userMessageId));
            } catch (Exception e) {
                log.debug("Auto delete user message skipped/failed chatId={} messageId={} reason={}", chatId, userMessageId, e.getMessage());
            }
        }, AUTO_DELETE_HOURS, TimeUnit.HOURS);
    }

    private void syncStories(TelegramLongPollingBot bot, Long chatId) {

        try {

            List<Story> stories = storyService.getAllStories();

            if (stories.isEmpty()) {

                sendMessage(bot, chatId, "❌ No stories found.");

                return;
            }

            int updated = 0;
            int inactive = 0;
            int temporaryErrors = 0;

            for (Story story : stories) {

                try {

                    GetChat getChat = new GetChat(String.valueOf(story.getTelegramChatId()));

                    var chat = bot.execute(getChat);

                    boolean completed = chat.getDescription() != null
                            && chat.getDescription().toLowerCase().contains("completed");

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

                    // IMPORTANT:
                    // Do NOT mark a story inactive for transient Telegram failures
                    // such as HTTP 429/flood control, timeout, DNS/network errors,
                    // or temporary Telegram API issues. With 30+ chats the old
                    // code could falsely deactivate valid stories during /syncstories.
                    if (isPermanentStoryUnavailable(ex)) {

                        log.warn("Story permanently unavailable; marking inactive chatId={} reason={}",
                                story.getTelegramChatId(), ex.getMessage());

                        story.setActive(false);
                        storyService.save(story);
                        inactive++;

                    } else {

                        temporaryErrors++;

                        log.warn("Story sync temporary failure; keeping current active state chatId={} reason={}",
                                story.getTelegramChatId(), ex.getMessage());
                    }
                }

                // Be gentle with Telegram when syncing a large library.
                // This also reduces 429/flood-control responses.
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            sendMessage(bot, chatId, """
                    ✅ Story Sync Completed

                    📚 Total Stories    : %d
                    ✅ Updated          : %d
                    ❌ Inactive         : %d
                    ⚠️ Temporary Errors : %d

                    Temporary API/network errors are NOT deactivated.
                    """.formatted(stories.size(), updated, inactive, temporaryErrors));

        } catch (Exception e) {

            log.error("syncStories failed", e);

            try {

                sendMessage(bot, chatId, "❌ Sync failed.");

            } catch (Exception ignore) {

            }
        }
    }

    /**
     * Returns true only when Telegram clearly says the bot can no longer
     * access that chat. Generic/transient exceptions must never deactivate
     * a valid story.
     */
    private boolean isPermanentStoryUnavailable(Exception exception) {

        Throwable current = exception;

        while (current != null) {

            String message = current.getMessage();

            if (message != null) {

                String normalized = message.toLowerCase();

                if (normalized.contains("chat not found")
                        || normalized.contains("bot was kicked")
                        || normalized.contains("bot is not a member")
                        || normalized.contains("bot was blocked")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
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
            int restored = 0;
            int skipped = 0;

            for (Story story : stories) {

                try {

                    // Re-check before destructive deletion. Older versions
                    // could mark valid stories inactive on 429/network errors.
                    GetChat getChat = new GetChat(String.valueOf(story.getTelegramChatId()));
                    var chat = bot.execute(getChat);

                    boolean completed = chat.getDescription() != null
                            && chat.getDescription().toLowerCase().contains("completed");

                    story.setTitle(chat.getTitle());
                    story.setTelegramUsername(chat.getUserName());
                    story.setChatType(chat.getType());
                    story.setDescription(chat.getDescription());
                    story.setInviteLink(chat.getInviteLink());
                    story.setIsCompleted(completed);
                    story.setActive(true);
                    storyService.save(story);

                    restored++;

                    log.info("Inactive story restored instead of deleted chatId={} title={}",
                            story.getTelegramChatId(), story.getTitle());

                } catch (Exception ex) {

                    if (isPermanentStoryUnavailable(ex)) {

                        log.info("Deleting confirmed unavailable story chatId={} title={}",
                                story.getTelegramChatId(), story.getTitle());

                        storyService.deleteStory(story);
                        deleted++;

                    } else {

                        // Never delete on a temporary Telegram/network error.
                        skipped++;

                        log.warn("Inactive cleanup temporary failure; keeping story chatId={} reason={}",
                                story.getTelegramChatId(), ex.getMessage());
                    }
                }

                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            sendMessage(bot, chatId, """
                    ✅ Inactive Story Cleanup Completed

                    ♻️ Restored Valid Stories : %d
                    🗑 Confirmed Deleted       : %d
                    ⚠️ Temporary Errors       : %d
                    """.formatted(restored, deleted, skipped));

        } catch (Exception e) {

            log.error("deleteInactiveStories failed", e);

            try {

                sendMessage(bot, chatId, "❌ Failed to clean inactive stories.");

            } catch (Exception ignore) {

            }
        }
    }
}
