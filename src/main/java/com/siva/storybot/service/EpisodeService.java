package com.siva.storybot.service;

import com.siva.storybot.dto.groq.EpisodeDetectResult;
import com.siva.storybot.entity.Episode;
import com.siva.storybot.entity.Story;
import com.siva.storybot.repository.EpisodeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeService {

    // =========================================================
    // DEPENDENCIES
    // =========================================================

    /**
     * Explicit episode markers.
     * <p>
     * Supports:
     * <p>
     * Ep 1
     * EP1
     * Ep-1
     * EP: 1
     * Episode 25
     * Episode-25
     * Part 10
     * Chapter 50
     * <p>
     * Also supports explicit ranges:
     * <p>
     * Episode 101-150
     */
    private static final Pattern EXPLICIT_EPISODE_PATTERN = Pattern.compile("(?i)(?:^|\\b)" + "(?:ep|episode|part|chapter)" + "\\s*[-:#.]?\\s*" + "(\\d{1,6})" + "(?:\\s*[-–]\\s*(\\d{1,6}))?" + "\\b");
    /**
     * Explicit bracket range.
     * <p>
     * Example:
     * <p>
     * [101-150]
     */
    private static final Pattern BRACKET_RANGE_PATTERN = Pattern.compile("\\[(\\d{1,6})\\s*[-–]\\s*(\\d{1,6})\\]");

    // =========================================================
    // SAFE EPISODE PATTERNS
    // =========================================================
    /**
     * Exact numeric title only.
     * <p>
     * Example:
     * <p>
     * Audio title = "25"
     * <p>
     * This pattern is NEVER applied to generated filenames.
     */
    private static final Pattern EXACT_NUMBER_PATTERN = Pattern.compile("^\\s*(\\d{1,6})\\s*$");
    private final EpisodeRepository episodeRepository;
    private final GroqService groqService;

    // =========================================================
    // SAVE EPISODE
    // =========================================================

    public Episode saveEpisode(Story story, Message message) {

        try {

            if (story == null) {

                throw new IllegalArgumentException("Story is required");
            }

            if (message == null || !message.hasAudio()) {

                throw new IllegalArgumentException("Telegram audio message is required");
            }

            Audio audio = message.getAudio();

            // =================================================
            // TELEGRAM AUDIO METADATA
            // =================================================

            String audioTitle = cleanText(audio.getTitle());

            String performer = cleanText(audio.getPerformer());

            String caption = cleanText(message.getCaption());

            String fileName = cleanText(audio.getFileName());

            log.info("""
                    
                    EPISODE METADATA
                    
                    STORY      : {}
                    TITLE      : {}
                    PERFORMER  : {}
                    CAPTION    : {}
                    FILE NAME  : {}
                    MESSAGE ID : {}
                    
                    """, story.getTitle(), audioTitle, performer, caption, fileName, message.getMessageId());

            // =================================================
            // LOCAL REGEX DETECTION
            // =================================================

            EpisodeDetectResult result = detectEpisodeLocally(audioTitle, caption, fileName);

            // =================================================
            // GROQ FALLBACK
            //
            // Only runs if deterministic detection fails.
            // =================================================

            if (!result.detected() && groqService.isEpisodeDetectionEnabled()) {

                String aiMetadata = buildGroqMetadata(story, audioTitle, performer, caption, fileName);

                EpisodeDetectResult aiResult = groqService.detectEpisodeNumber(aiMetadata);

                if (aiResult.detected()) {

                    result = aiResult;

                    log.info("Groq detected episode={} story={}", result.value(), story.getTitle());
                }
            }

            // =================================================
            // FINAL EPISODE NUMBER
            // =================================================

            String episodeNo = result.detected() ? result.value() : null;

            Integer episodeNoNumeric = result.detected() ? extractNumericValue(episodeNo) : null;

            boolean detected = result.detected();

            // =================================================
            // HUMAN-READABLE TITLE
            // =================================================

            String episodeTitle = resolveEpisodeTitle(story, audioTitle, caption, fileName, episodeNo, message.getMessageId());

            // =================================================
            // SAVE
            // =================================================

            Episode episode = Episode.builder().story(story).episodeNo(episodeNo).episodeNoNumeric(episodeNoNumeric).title(episodeTitle).telegramFileId(audio.getFileId()).telegramMessageId(Long.valueOf(message.getMessageId())).durationSeconds(audio.getDuration()).fileSize(audio.getFileSize()).uploadedAt(LocalDateTime.now()).isEpisodeDetected(detected).build();

            Episode saved = episodeRepository.save(episode);

            log.info("""
                    
                    EPISODE SAVED
                    
                    STORY      : {}
                    TITLE      : {}
                    EPISODE NO : {}
                    DETECTED   : {}
                    
                    """, story.getTitle(), episodeTitle, episodeNo, detected);

            return saved;

        } catch (Exception e) {

            log.error("saveEpisode failed", e);

            throw e;
        }
    }

    // =========================================================
    // LOCAL EPISODE DETECTOR
    // =========================================================

    private EpisodeDetectResult detectEpisodeLocally(String audioTitle, String caption, String fileName) {

        // =====================================================
        // 1. AUDIO TITLE
        //
        // Highest priority.
        // =====================================================

        EpisodeDetectResult result = detectFromHumanText(audioTitle, true);

        if (result.detected()) {

            log.info("Episode detected from AUDIO TITLE episode={}", result.value());

            return result;
        }

        // =====================================================
        // 2. MESSAGE CAPTION
        // =====================================================

        result = detectFromHumanText(caption, true);

        if (result.detected()) {

            log.info("Episode detected from CAPTION episode={}", result.value());

            return result;
        }

        // =====================================================
        // 3. FILE NAME
        //
        // IMPORTANT:
        //
        // Generated filenames can contain timestamps,
        // Telegram IDs and random numbers.
        //
        // Therefore generic numeric matching is NOT allowed.
        //
        // File name must explicitly contain:
        //
        // EP / EPISODE / PART / CHAPTER
        // or [x-y]
        // =====================================================

        result = detectFromFileName(fileName);

        if (result.detected()) {

            log.info("Episode detected from FILE NAME episode={}", result.value());

            return result;
        }

        return notDetected();
    }

    // =========================================================
    // HUMAN TEXT DETECTION
    // =========================================================

    private EpisodeDetectResult detectFromHumanText(String text, boolean allowExactNumber) {

        if (text == null || text.isBlank()) {

            return notDetected();
        }

        String cleaned = normalizeHumanText(text);

        // Explicit EP / Episode / Part / Chapter
        EpisodeDetectResult result = extractExplicitEpisode(cleaned);

        if (result.detected()) {

            return result;
        }

        // [101-150]
        result = extractBracketRange(cleaned);

        if (result.detected()) {

            return result;
        }

        // Exact title = "25"
        //
        // Allowed only for actual title/caption.
        if (allowExactNumber) {

            Matcher matcher = EXACT_NUMBER_PATTERN.matcher(cleaned);

            if (matcher.matches()) {

                return createSingleEpisodeResult(matcher.group(1));
            }
        }

        return notDetected();
    }

    // =========================================================
    // FILE NAME DETECTION
    // =========================================================

    private EpisodeDetectResult detectFromFileName(String fileName) {

        if (fileName == null || fileName.isBlank()) {

            return notDetected();
        }

        String cleaned = normalizeFileName(fileName);

        EpisodeDetectResult result = extractExplicitEpisode(cleaned);

        if (result.detected()) {

            return result;
        }

        // Explicit [101-150] filename is safe.
        result = extractBracketRange(cleaned);

        if (result.detected()) {

            return result;
        }

        /*
         * DO NOT add generic SINGLE_PATTERN here.
         *
         * Example:
         *
         * temp_audio_5999036520_1781621445.9794_DZHPb.m4a
         *
         * 9794 is NOT an episode number.
         */

        return notDetected();
    }

    // =========================================================
    // EXPLICIT EPISODE
    // =========================================================

    private EpisodeDetectResult extractExplicitEpisode(String text) {

        if (text == null || text.isBlank()) {

            return notDetected();
        }

        Matcher matcher = EXPLICIT_EPISODE_PATTERN.matcher(text);

        if (!matcher.find()) {

            return notDetected();
        }

        String first = matcher.group(1);

        String second = matcher.group(2);

        return createEpisodeResult(first, second);
    }

    // =========================================================
    // BRACKET RANGE
    // =========================================================

    private EpisodeDetectResult extractBracketRange(String text) {

        if (text == null || text.isBlank()) {

            return notDetected();
        }

        Matcher matcher = BRACKET_RANGE_PATTERN.matcher(text);

        if (!matcher.find()) {

            return notDetected();
        }

        return createEpisodeResult(matcher.group(1), matcher.group(2));
    }

    // =========================================================
    // VALIDATE EPISODE VALUE
    // =========================================================

    private EpisodeDetectResult createEpisodeResult(String first, String second) {

        try {

            int start = Integer.parseInt(first);

            if (start <= 0) {

                return notDetected();
            }

            // Single episode
            if (second == null || second.isBlank()) {

                return new EpisodeDetectResult(true, String.valueOf(start));
            }

            int end = Integer.parseInt(second);

            if (end <= 0 || start > end) {

                return notDetected();
            }

            return new EpisodeDetectResult(true, start + "-" + end);

        } catch (Exception e) {

            return notDetected();
        }
    }

    private EpisodeDetectResult createSingleEpisodeResult(String value) {

        return createEpisodeResult(value, null);
    }

    private EpisodeDetectResult notDetected() {

        return new EpisodeDetectResult(false, null);
    }

    // =========================================================
    // EPISODE TITLE
    // =========================================================

    private String resolveEpisodeTitle(Story story, String audioTitle, String caption, String fileName, String episodeNo, Integer messageId) {

        // =====================================================
        // 1. Telegram embedded Audio Title
        // =====================================================

        if (audioTitle != null && !audioTitle.isBlank()) {

            return audioTitle.trim();
        }

        // =====================================================
        // 2. Telegram Caption
        // =====================================================

        if (caption != null && !caption.isBlank()) {

            String firstLine = firstLine(caption);

            if (!firstLine.isBlank()) {

                return firstLine;
            }
        }

        // =====================================================
        // 3. Filename only if it is NOT generated temp filename
        // =====================================================

        if (fileName != null && !fileName.isBlank() && !isTemporaryFileName(fileName)) {

            return removeFileExtension(fileName);
        }

        // =====================================================
        // 4. Episode detected but no title
        // =====================================================

        if (episodeNo != null && !episodeNo.isBlank()) {

            return story.getTitle() + " - Ep " + episodeNo;
        }

        // =====================================================
        // 5. Safe fallback
        // =====================================================

        return story.getTitle() + " - Audio " + messageId;
    }

    // =========================================================
    // GROQ METADATA
    // =========================================================

    private String buildGroqMetadata(Story story, String audioTitle, String performer, String caption, String fileName) {

        return """
                Telegram audio metadata:
                
                Story / Channel:
                %s
                
                Audio Title:
                %s
                
                Performer:
                %s
                
                Caption:
                %s
                
                File Name:
                %s
                
                Important:
                The filename may be automatically generated.
                Do not treat random filename numbers, timestamps,
                Telegram IDs or user IDs as episode numbers.
                """.formatted(safeAiValue(story != null ? story.getTitle() : null), safeAiValue(audioTitle), safeAiValue(performer), safeAiValue(caption), safeAiValue(fileName));
    }

    // =========================================================
    // NORMALIZE
    // =========================================================

    private String normalizeHumanText(String input) {

        if (input == null) {
            return "";
        }

        return input.replace('_', ' ').replace('–', '-').replace('—', '-').replaceAll("\\s+", " ").trim();
    }

    private String normalizeFileName(String input) {

        if (input == null) {
            return "";
        }

        return input.replace('_', ' ').replace('–', '-').replace('—', '-').replaceAll("\\s+", " ").trim();
    }

    // =========================================================
    // NUMERIC VALUE FOR SORTING
    // =========================================================

    private Integer extractNumericValue(String episodeNo) {

        try {

            if (episodeNo == null || episodeNo.isBlank()) {

                return null;
            }

            String first = episodeNo.split("-")[0].trim();

            if (!first.matches("\\d+")) {

                return null;
            }

            int value = Integer.parseInt(first);

            return value > 0 ? value : null;

        } catch (Exception e) {

            return null;
        }
    }

    // =========================================================
    // AI RETRY CRON
    //
    // Episodes still undetected are periodically retried.
    // =========================================================

    @Scheduled(cron = "${groq.episode-detection.cron:0 0 */6 * * *}")
    public void detectEpisodesUsingAi() {

        try {

            if (!groqService.isEpisodeDetectionEnabled()) {

                return;
            }

            List<Episode> episodes = episodeRepository.findByIsEpisodeDetectedFalse();

            if (episodes == null || episodes.isEmpty()) {

                return;
            }

            /*
             * Avoid sending unlimited AI requests
             * in one cron execution.
             */
            int checked = 0;
            int updated = 0;

            for (Episode episode : episodes) {

                if (checked >= 50) {
                    break;
                }

                checked++;

                if (episode == null || episode.getTitle() == null || episode.getTitle().isBlank()) {

                    continue;
                }

                String metadata = """
                        Stored Telegram episode:
                        
                        Story:
                        %s
                        
                        Title:
                        %s
                        
                        Extract episode number only when the
                        title reasonably identifies an episode.
                        """.formatted(episode.getStory() != null ? safeAiValue(episode.getStory().getTitle()) : "Unknown",

                        safeAiValue(episode.getTitle()));

                EpisodeDetectResult result = groqService.detectEpisodeNumber(metadata);

                if (!result.detected()) {

                    continue;
                }

                episode.setEpisodeNo(result.value());

                episode.setEpisodeNoNumeric(extractNumericValue(result.value()));

                episode.setIsEpisodeDetected(true);

                episodeRepository.save(episode);

                updated++;

                log.info("AI retry updated episodeId={} episodeNo={}", episode.getId(), result.value());
            }

            if (checked > 0) {

                log.info("Episode AI retry completed checked={} updated={}", checked, updated);
            }

        } catch (Exception e) {

            log.error("AI episode retry failed", e);
        }
    }

    // =========================================================
    // GET EPISODES
    // =========================================================

    public Page<Episode> getEpisodes(Story story, int page, int size) {

        return episodeRepository.findByStoryOrderByEpisodeNoNumericDesc(story, PageRequest.of(page, size));
    }

    // =========================================================
    // GET EPISODE
    // =========================================================

    public Episode getEpisodeById(Long id) {

        return episodeRepository.findById(id).orElse(null);
    }

    // =========================================================
    // RANGE SEARCH
    // =========================================================

    public List<Episode> getEpisodesByRange(Story story, int start, int end) {

        if (story == null || start <= 0 || end < start) {

            return List.of();
        }

        return episodeRepository.findTop50ByStoryAndEpisodeNoNumericBetweenOrderByEpisodeNoNumericAsc(story, start, end);
    }

    // =========================================================
    // LATEST EPISODE NUMBER
    // =========================================================

    public Integer getLatestEpisodeNumber(Story story) {

        try {

            if (story == null) {

                return null;
            }

            return episodeRepository.findByStory(story).stream().map(Episode::getEpisodeNoNumeric).filter(number -> number != null && number > 0).max(Integer::compareTo).orElse(null);

        } catch (Exception e) {

            log.error("getLatestEpisodeNumber failed", e);

            return null;
        }
    }

    // =========================================================
    // SMALL HELPERS
    // =========================================================

    private String cleanText(String value) {

        if (value == null) {
            return null;
        }

        String cleaned = value.trim();

        return cleaned.isBlank() ? null : cleaned;
    }

    private String firstLine(String value) {

        if (value == null || value.isBlank()) {

            return "";
        }

        String[] lines = value.split("\\R", 2);

        return lines[0].trim();
    }

    private boolean isTemporaryFileName(String fileName) {

        if (fileName == null || fileName.isBlank()) {

            return false;
        }

        String lower = fileName.trim().toLowerCase(Locale.ROOT);

        return lower.startsWith("temp_audio_") || lower.startsWith("temp-audio-");
    }

    private String removeFileExtension(String fileName) {

        if (fileName == null || fileName.isBlank()) {

            return "";
        }

        String value = fileName.trim();

        int dot = value.lastIndexOf('.');

        if (dot > 0) {

            return value.substring(0, dot);
        }

        return value;
    }

    private String safeAiValue(String value) {

        if (value == null || value.isBlank()) {

            return "Not available";
        }

        return value.trim();
    }
}