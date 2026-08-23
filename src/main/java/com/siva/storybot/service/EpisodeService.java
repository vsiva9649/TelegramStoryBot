package com.siva.storybot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siva.storybot.dto.groq.EpisodeDetectResult;
import com.siva.storybot.dto.groq.GroqRequest;
import com.siva.storybot.dto.groq.GroqResponse;
import com.siva.storybot.entity.Episode;
import com.siva.storybot.entity.Story;
import com.siva.storybot.repository.EpisodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeService {

    private static final Pattern EP_PATTERN = Pattern.compile("(?i)ep(?:isode)?\\s*(\\d+(?:-\\d+)?)");
    private static final Pattern BRACKET_RANGE_PATTERN = Pattern.compile("\\[(\\d+-\\d+)\\]");
    private static final Pattern RANGE_PATTERN = Pattern.compile("\\b(\\d+-\\d+)\\b");

    // =====================================
    // GROQ
    // =====================================
    private static final Pattern SINGLE_PATTERN = Pattern.compile("\\b(\\d{1,4})\\b");
    private final EpisodeRepository episodeRepository;
    private final WebClient.Builder webClientBuilder;

    // =====================================
    // PATTERNS
    // =====================================
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${groq.api.key}")
    private String apiKey;
    @Value("${groq.url}")
    private String groqUrl;
    @Value("${groq.model}")
    private String model;

    @Value("${groq.episode-detection.enabled:false}")
    private boolean groqEpisodeDetectionEnabled;

    // =====================================
    // SAVE EPISODE
    // =====================================

    public Episode saveEpisode(Story story, Message message) {

        try {

            Audio audio = message.getAudio();

            String fileName = audio.getFileName();

            String episodeNo = null;

            Integer episodeNoNumeric = null;

            boolean detected = false;

            // =================================
            // REGEX DETECT
            // =================================

            EpisodeDetectResult result = extractEpisode(fileName);

            if (result.detected()) {

                episodeNo = result.value();

                detected = true;

                episodeNoNumeric = extractNumericValue(episodeNo);

                log.info("Regex detected episode={}", episodeNo);
            }

            // =================================
            // SAVE
            // =================================

            Episode episode = Episode.builder().story(story).episodeNo(episodeNo).episodeNoNumeric(episodeNoNumeric).title(fileName).telegramFileId(audio.getFileId()).telegramMessageId(Long.valueOf(message.getMessageId())).durationSeconds(audio.getDuration()).fileSize(audio.getFileSize()).uploadedAt(LocalDateTime.now()).isEpisodeDetected(detected).build();

            episodeRepository.save(episode);

            log.info("Episode saved story={} episodeNo={} detected={}", story.getTitle(), episodeNo, detected);

            return episode;

        } catch (Exception e) {

            log.error("saveEpisode failed", e);

            throw e;
        }
    }

    // =====================================
    // REGEX DETECTOR
    // =====================================

    private EpisodeDetectResult extractEpisode(String fileName) {

        try {

            if (fileName == null || fileName.isBlank()) {

                return new EpisodeDetectResult(false, null);
            }

            String cleaned = normalize(fileName);

            EpisodeDetectResult result;

            result = extract(cleaned, EP_PATTERN);

            if (result.detected()) {
                return result;
            }

            result = extract(cleaned, BRACKET_RANGE_PATTERN);

            if (result.detected()) {
                return result;
            }

            result = extract(cleaned, RANGE_PATTERN);

            if (result.detected()) {
                return result;
            }

            result = extract(cleaned, SINGLE_PATTERN);

            return result;

        } catch (Exception e) {

            log.error("extractEpisode failed", e);

            return new EpisodeDetectResult(false, null);
        }
    }

    // =====================================
    // EXTRACT
    // =====================================

    private EpisodeDetectResult extract(String text, Pattern pattern) {

        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {

            return new EpisodeDetectResult(true, matcher.group(1));
        }

        return new EpisodeDetectResult(false, null);
    }

    // =====================================
    // NORMALIZE
    // =====================================

    private String normalize(String input) {

        return input.replaceAll("_", " ").replaceAll("\\.", " ").replaceAll("\\s+", " ").trim();
    }

    // =====================================
    // NUMERIC VALUE
    // =====================================

    private Integer extractNumericValue(String episodeNo) {

        try {

            if (episodeNo == null) {
                return null;
            }

            String first = episodeNo.split("-")[0];

            if (first.matches("\\d+")) {

                return Integer.parseInt(first);
            }

            return null;

        } catch (Exception e) {

            return null;
        }
    }

    // =====================================
    // AI CRON
    // =====================================

    @Scheduled(cron = "0 0 */6 * * *")
    public void detectEpisodesUsingAi() {

        try {

            if (!groqEpisodeDetectionEnabled) {
                log.debug("Groq episode detection is disabled");
                return;
            }

            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Groq episode detection skipped because GROQ_API_KEY is not configured");
                return;
            }

            List<Episode> episodes = episodeRepository.findByIsEpisodeDetectedFalse();

            if (episodes.isEmpty()) {

                return;
            }

            StringBuilder builder = new StringBuilder();

            for (Episode episode : episodes) {

                builder.append("""
                        
                        {
                          "id": %s,
                          "title": "%s"
                        }
                        """.formatted(episode.getId(), episode.getTitle()));
            }

            String prompt = """
                    Extract episode number.
                    
                    Return ONLY JSON ARRAY.
                    
                    Example:
                    
                    [
                      {
                        "id": 1,
                        "episodeNo": "101-150"
                      }
                    ]
                    """;

            GroqRequest request = GroqRequest.builder().model(model).messages(List.of(

                    GroqRequest.Message.builder().role("system").content(prompt).build(),

                    GroqRequest.Message.builder().role("user").content(builder.toString()).build())).build();

            WebClient webClient = webClientBuilder.build();

            GroqResponse response = webClient.post().uri(groqUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().bodyToMono(GroqResponse.class).block();

            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {

                return;
            }

            String aiJson = response.getChoices().get(0).getMessage().getContent();

            JsonNode json = objectMapper.readTree(aiJson);

            for (JsonNode node : json) {

                Long id = node.get("id").asLong();

                String episodeNo = node.get("episodeNo").asText();

                Episode episode = episodeRepository.findById(id).orElse(null);

                if (episode == null) {
                    continue;
                }

                episode.setEpisodeNo(episodeNo);

                episode.setEpisodeNoNumeric(extractNumericValue(episodeNo));

                episode.setIsEpisodeDetected(true);

                episodeRepository.save(episode);
            }

        } catch (Exception e) {

            log.error("AI detect failed", e);
        }
    }

    // =====================================
    // GET EPISODES
    // =====================================

    public Page<Episode> getEpisodes(Story story, int page, int size) {

        return episodeRepository.findByStoryOrderByEpisodeNoNumericDesc(story, PageRequest.of(page, size));
    }

    // =====================================
    // GET EPISODE
    // =====================================

    public Episode getEpisodeById(Long id) {

        return episodeRepository.findById(id).orElse(null);
    }

    // =====================================
    // RANGE SEARCH
    // =====================================

    public List<Episode> getEpisodesByRange(Story story, int start, int end) {

        if (story == null || start <= 0 || end < start) {
            return List.of();
        }

        return episodeRepository
                .findTop50ByStoryAndEpisodeNoNumericBetweenOrderByEpisodeNoNumericAsc(story, start, end);
    }

    // =====================================
    // LATEST EPISODE NUMBER
    // =====================================

    public Integer getLatestEpisodeNumber(Story story) {

        try {

            return episodeRepository.findByStory(story).stream().map(Episode::getEpisodeNoNumeric).filter(number -> number != null).max(Integer::compareTo).orElse(null);

        } catch (Exception e) {

            log.error("getLatestEpisodeNumber failed", e);

            return null;
        }
    }
}
