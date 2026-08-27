package com.siva.storybot.service;

import com.siva.storybot.dto.groq.EpisodeDetectResult;
import com.siva.storybot.dto.groq.GroqRequest;
import com.siva.storybot.dto.groq.GroqResponse;
import com.siva.storybot.enums.OwnerIntent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import jakarta.annotation.PostConstruct;
@Slf4j
@Service
@RequiredArgsConstructor
public class GroqService {

    private final WebClient.Builder webClientBuilder;

    // =========================================================
    // GROQ CONFIGURATION
    // =========================================================

    @Value("${groq.api.key:${GROQ_API_KEY:}}")
    private String apiKey;

    @Value("${groq.url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqUrl;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    // Owner command AI fallback
    @Value("${groq.intent.enabled:false}")
    private boolean intentEnabled;

    // Episode detection AI fallback
    @Value("${groq.episode-detection.enabled:false}")
    private boolean episodeDetectionEnabled;

    @PostConstruct
    public void initGroq(){

        if(isGroqConfigured()){

            log.info("""
                
                =====================================
                GROQ CONFIGURED
                
                Model : {}
                
                =====================================
                """,
                    model
            );

        }else{


            log.warn("""
                
                =====================================
                GROQ NOT CONFIGURED
                
                Running without AI fallback
                
                Local commands will work normally
                
                =====================================
                """);
        }
    }

    // =========================================================
    // OWNER INTENT
    // =========================================================

    /**
     * OWNER intent detection is local-first.
     * <p>
     * Core owner commands must never depend on Groq.
     * Groq is used only when local parsing cannot determine
     * the intended owner command.
     */
    public OwnerIntent detectIntent(String userMessage) {

        OwnerIntent localIntent = detectLocalIntent(userMessage);

        if (localIntent != OwnerIntent.UNKNOWN) {

            log.debug("Owner intent detected locally intent={} message={}", localIntent, userMessage);

            return localIntent;
        }

        if (!intentEnabled) {

            log.debug("Groq owner intent fallback disabled");

            return OwnerIntent.UNKNOWN;
        }

        if (!isGroqConfigured()) {

            log.warn("Groq owner intent fallback skipped because GROQ_API_KEY is not configured");

            return OwnerIntent.UNKNOWN;
        }

        String systemPrompt = """
                You are an owner/admin command intent classifier
                for a Telegram story bot.
                
                Return ONLY one of these exact values:
                
                GET_USERS
                GET_ACTIVE_USERS
                GET_EXPIRED_USERS
                GET_USER_DETAILS
                GET_HISTORY
                UPDATE_USER
                GLOBAL_TRIAL_ON
                GLOBAL_TRIAL_OFF
                UNKNOWN
                
                Examples:
                
                show users
                -> GET_USERS
                
                list all users
                -> GET_USERS
                
                show active users
                -> GET_ACTIVE_USERS
                
                subscribed users
                -> GET_ACTIVE_USERS
                
                show expired users
                -> GET_EXPIRED_USERS
                
                inactive users
                -> GET_EXPIRED_USERS
                
                user details
                -> GET_USER_DETAILS
                
                show user @name
                -> GET_USER_DETAILS
                
                payment history
                -> GET_HISTORY
                
                history @name
                -> GET_HISTORY
                
                make admin
                -> UPDATE_USER
                
                activate monthly
                -> UPDATE_USER
                
                trial user
                -> UPDATE_USER
                
                expire subscription
                -> UPDATE_USER
                
                enable global trial
                -> GLOBAL_TRIAL_ON
                
                disable global trial
                -> GLOBAL_TRIAL_OFF
                
                Never output:
                - explanation
                - SQL
                - code
                - punctuation
                - markdown
                - additional text
                """;

        String aiReply = executeGroq(systemPrompt, userMessage, Duration.ofSeconds(5), "owner-intent");

        if (aiReply == null || aiReply.isBlank()) {

            return OwnerIntent.UNKNOWN;
        }

        String normalizedReply = aiReply.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");

        log.info("Groq AI detected owner intent={}", normalizedReply);

        try {

            return OwnerIntent.valueOf(normalizedReply);

        } catch (IllegalArgumentException e) {

            return OwnerIntent.UNKNOWN;
        }
    }

    // =========================================================
    // EPISODE NUMBER AI DETECTION
    // =========================================================

    /**
     * Groq is ONLY a fallback.
     * <p>
     * EpisodeService should first try deterministic local regex.
     * <p>
     * Groq is useful when:
     * <p>
     * - metadata wording is unusual
     * - Tamil/English mixed metadata exists
     * - Episode number format is unclear
     * <p>
     * IMPORTANT:
     * <p>
     * Random numbers from temp filenames, timestamps,
     * Telegram IDs, user IDs etc. must NOT be treated as
     * episode numbers.
     */
    public EpisodeDetectResult detectEpisodeNumber(String metadata) {

        if (metadata == null || metadata.isBlank()) {

            return notDetected();
        }

        if (!episodeDetectionEnabled) {

            return notDetected();
        }

        if (!isGroqConfigured()) {

            log.warn("Groq episode detection skipped because GROQ_API_KEY is not configured");

            return notDetected();
        }

        String systemPrompt = """
                You extract episode numbers from Telegram audio metadata.
                
                Return ONLY ONE of these formats:
                
                1
                25
                101
                101-150
                UNKNOWN
                
                Rules:
                
                1. Detect episode numbers only when the metadata
                   reasonably indicates an episode, such as:
                
                   Ep 1
                   EP-2
                   Episode 25
                   Episode: 100
                   Part 15
                   Chapter 30
                   [101-150]
                
                2. Mixed-language text is allowed.
                   Tamil or other language text may appear around
                   the episode marker.
                
                3. NEVER infer an episode number from:
                   - Telegram user IDs
                   - timestamps
                   - generated filenames
                   - random numeric identifiers
                   - file sizes
                   - durations
                
                4. Example:
                
                   temp_audio_5999036520_1781621445.9794_DZHPb.m4a
                
                   MUST return:
                
                   UNKNOWN
                
                5. Example:
                
                   Title: Ep 5 - Tamil story name
                
                   MUST return:
                
                   5
                
                6. If a range is explicitly shown:
                
                   Episode 101-150
                
                   return:
                
                   101-150
                
                7. Do not explain your answer.
                
                8. Do not return JSON.
                
                9. If uncertain return exactly:
                
                   UNKNOWN
                """;

        String aiReply = executeGroq(systemPrompt, metadata, Duration.ofSeconds(6), "episode-detection");

        if (aiReply == null || aiReply.isBlank()) {

            return notDetected();
        }

        return normalizeEpisodeAiResult(aiReply);
    }

    public boolean isEpisodeDetectionEnabled() {

        return episodeDetectionEnabled && isGroqConfigured();
    }

    // =========================================================
    // NORMALIZE GROQ EPISODE RESULT
    // =========================================================

    private EpisodeDetectResult normalizeEpisodeAiResult(String aiReply) {

        if (aiReply == null || aiReply.isBlank()) {

            return notDetected();
        }

        String value = aiReply.trim().replace("`", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);

        if ("UNKNOWN".equals(value) || "NONE".equals(value) || "NULL".equals(value)) {

            return notDetected();
        }

        // Single episode
        if (value.matches("\\d{1,6}")) {

            try {

                int episode = Integer.parseInt(value);

                if (episode <= 0) {
                    return notDetected();
                }

                return new EpisodeDetectResult(true, String.valueOf(episode));

            } catch (NumberFormatException e) {

                return notDetected();
            }
        }

        // Episode range
        if (value.matches("\\d{1,6}-\\d{1,6}")) {

            String[] parts = value.split("-");

            try {

                int start = Integer.parseInt(parts[0]);

                int end = Integer.parseInt(parts[1]);

                if (start <= 0 || end <= 0 || start > end) {

                    return notDetected();
                }

                return new EpisodeDetectResult(true, start + "-" + end);

            } catch (Exception e) {

                return notDetected();
            }
        }

        return notDetected();
    }

    // =========================================================
    // COMMON GROQ REQUEST
    // =========================================================

    private String executeGroq(String systemPrompt, String userMessage, Duration timeout, String operation) {

        if(!isGroqConfigured()){

            log.debug(
                    "Groq skipped operation={} because configuration missing",
                    operation
            );

            return null;
        }
        try {

            GroqRequest request = GroqRequest.builder().model(model).messages(List.of(GroqRequest.Message.builder().role("system").content(systemPrompt).build(),

                    GroqRequest.Message.builder().role("user").content(userMessage).build())).build();

            WebClient webClient = webClientBuilder.build();

            GroqResponse response = webClient.post().uri(groqUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().bodyToMono(GroqResponse.class).timeout(timeout).block();

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0).getMessage() == null || response.getChoices().get(0).getMessage().getContent() == null) {

                return null;
            }

            return response.getChoices().get(0).getMessage().getContent().trim();

        } catch (WebClientResponseException e) {

            log.warn("Groq {} failed status={} message={}", operation, e.getStatusCode().value(), e.getStatusText());

            return null;

        } catch (Exception e) {

            log.warn("Groq {} failed reason={}", operation, e.getMessage());

            return null;
        }
    }

    private boolean isGroqConfigured() {

        return apiKey != null && !apiKey.isBlank() && groqUrl != null && !groqUrl.isBlank() && model != null && !model.isBlank();
    }

    private EpisodeDetectResult notDetected() {

        return new EpisodeDetectResult(false, null);
    }

    // =========================================================
    // LOCAL OWNER INTENT
    // =========================================================

    public OwnerIntent detectLocalIntent(String userMessage) {

        if (userMessage == null || userMessage.isBlank()) {

            return OwnerIntent.UNKNOWN;
        }

        String normalized = userMessage.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        String firstToken = normalized.split(" ", 2)[0];

        // Telegram may send /command@BotUsername.
        int botMentionIndex = firstToken.indexOf('@');

        if (botMentionIndex > 0) {

            firstToken = firstToken.substring(0, botMentionIndex);
        }

        return switch (firstToken) {

            case "/users" -> OwnerIntent.GET_USERS;

            case "/activeusers" -> OwnerIntent.GET_ACTIVE_USERS;

            case "/expiredusers" -> OwnerIntent.GET_EXPIRED_USERS;

            case "/userdetails" -> OwnerIntent.GET_USER_DETAILS;

            case "/history" -> OwnerIntent.GET_HISTORY;

            case "/updateuser" -> OwnerIntent.UPDATE_USER;

            case "/trailonsubscription" -> OwnerIntent.GLOBAL_TRIAL_ON;

            case "/trailoffsubscription" -> OwnerIntent.GLOBAL_TRIAL_OFF;

            default -> detectLocalNaturalLanguageIntent(normalized);
        };
    }

    private OwnerIntent detectLocalNaturalLanguageIntent(String normalized) {

        if (normalized.matches(".*\\b(active|subscribed|subscription active)\\b.*\\busers?\\b.*") || normalized.matches(".*\\busers?\\b.*\\b(active|subscribed)\\b.*")) {

            return OwnerIntent.GET_ACTIVE_USERS;
        }

        if (normalized.matches(".*\\b(expired|inactive|no access)\\b.*\\busers?\\b.*") || normalized.matches(".*\\busers?\\b.*\\b(expired|inactive)\\b.*")) {

            return OwnerIntent.GET_EXPIRED_USERS;
        }

        if (normalized.equals("users") || normalized.contains("show users") || normalized.contains("list users") || normalized.contains("all users") || normalized.contains("get users")) {

            return OwnerIntent.GET_USERS;
        }

        if (normalized.contains("user details") || normalized.startsWith("show user ") || normalized.startsWith("get user ") || normalized.matches("^user\\s+.+")) {

            return OwnerIntent.GET_USER_DETAILS;
        }

        if (normalized.contains("history")) {

            return OwnerIntent.GET_HISTORY;
        }

        if (normalized.contains("global") && (normalized.contains("trial") || normalized.contains("trail"))) {

            if (normalized.contains("off") || normalized.contains("disable") || normalized.contains("stop")) {

                return OwnerIntent.GLOBAL_TRIAL_OFF;
            }

            if (normalized.contains("on") || normalized.contains("enable") || normalized.contains("start")) {

                return OwnerIntent.GLOBAL_TRIAL_ON;
            }
        }

        if (normalized.contains("make admin") || normalized.contains("activate") || normalized.contains("expire") || normalized.startsWith("trial ") || normalized.startsWith("trail ") || normalized.contains("subscription")) {

            return OwnerIntent.UPDATE_USER;
        }

        return OwnerIntent.UNKNOWN;
    }
}