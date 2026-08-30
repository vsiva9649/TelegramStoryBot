package com.siva.storybot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "stories")
public class Story {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Telegram Group / Channel ID
    @Column(unique = true, nullable = false)
    private Long telegramChatId;

    // Story Name
    @Column(nullable = false)
    private String title;

    // Active Story
    private Boolean active;

    private LocalDateTime createdAt;

    private String telegramUsername;

    private String chatType;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String inviteLink;

    private Boolean isCompleted; //description = completed

    // Telegram photo file_id used as the story cover/icon in the bot UI.
    // ADMIN / OWNER can manage this through /addstoryicon and /removestoryicon.
    @Column(name = "story_icon_file_id")
    private String storyIconFileId;

    @OneToMany(
            mappedBy = "story",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<Episode> episodes = new ArrayList<>();
}