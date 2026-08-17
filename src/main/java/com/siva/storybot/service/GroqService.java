package com.siva.storybot.service;

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

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroqService {

    private final WebClient.Builder webClientBuilder;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.url}")
    private String groqUrl;

    @Value("${groq.model}")
    private String model;

    public OwnerIntent detectIntent(String userMessage) {

        try {

            if (userMessage == null || userMessage.isBlank()) {

                return OwnerIntent.UNKNOWN;
            }

            String systemPrompt = """
                You are an admin command detector.

                Your job is ONLY to detect safe admin intents.

                NEVER generate:

                DELETE queries
                DROP queries
                TRUNCATE queries
                REMOVE commands
                UPDATE SQL
                INSERT SQL
                EXECUTE statements
                JAVA code
                DATABASE queries

                Allowed intents:

                GET_USERS
                GET_USER_DETAILS
                GET_HISTORY
                UPDATE_USER

                Examples:

                show users -> GET_USERS
                get all users -> GET_USERS

                user details -> GET_USER_DETAILS
                show user -> GET_USER_DETAILS

                payment history -> GET_HISTORY

                make admin -> UPDATE_USER
                activate subscription -> UPDATE_USER
                activate monthly -> UPDATE_USER
                trial user -> UPDATE_USER
                extend subscription -> UPDATE_USER

                Reply ONLY intent name.
                """;

            GroqRequest request = GroqRequest.builder()
                    .model(model)
                    .messages(
                            List.of(

                                    GroqRequest.Message.builder()
                                            .role("system")
                                            .content(systemPrompt)
                                            .build(),

                                    GroqRequest.Message.builder()
                                            .role("user")
                                            .content(userMessage)
                                            .build()
                            )
                    )
                    .build();

            WebClient webClient =
                    webClientBuilder.build();

            GroqResponse response = webClient
                    .post()
                    .uri(groqUrl)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + apiKey
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GroqResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (response == null
                    || response.getChoices() == null
                    || response.getChoices().isEmpty()
                    || response.getChoices().get(0).getMessage() == null
                    || response.getChoices().get(0).getMessage().getContent() == null) {

                return OwnerIntent.UNKNOWN;
            }

            String aiReply = response
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent()
                    .trim()
                    .toUpperCase();

            log.info(
                    "Groq AI detected intent={}",
                    aiReply
            );

            return switch (aiReply) {

                case "GET_USERS" ->
                        OwnerIntent.GET_USERS;

                case "GET_USER_DETAILS" ->
                        OwnerIntent.GET_USER_DETAILS;

                case "GET_HISTORY" ->
                        OwnerIntent.GET_HISTORY;

                case "UPDATE_USER" ->
                        OwnerIntent.UPDATE_USER;

                default ->
                        OwnerIntent.UNKNOWN;
            };

        } catch (Exception e) {

            log.error(
                    "Groq intent detection failed userMessage={} reason={}",
                    userMessage,
                    e.getMessage()
            );

            return OwnerIntent.UNKNOWN;
        }
    }
}
