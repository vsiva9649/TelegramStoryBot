package com.siva.storybot.dto.groq;

import lombok.Data;

import java.util.List;

@Data
public class GroqResponse {

    private List<Choice> choices;

    @Data
    public static class Choice {

        private Message message;
    }

    @Data
    public static class Message {

        private String content;
    }
}