package com.siva.storybot.dto.groq;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GroqRequest {

    private String model;

    private List<Message> messages;

    @Data
    @Builder
    public static class Message {

        private String role;

        private String content;
    }
}