package com.siva.storybot.dto.story;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ChatFullInfo {

    private String title;

    private String username;

    private String chatType;

    private String description;

    private String inviteLink;
}
