package com.siva.storybot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "episodes")
public class Episode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =====================================
    // STORY Mapping
    // =====================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    // =====================================
    // EPISODE NUMBER
    // =====================================

    // Example:
    // 7
    // 07
    // 101-150
    // 20-25

    private String episodeNo;

    // Used for sorting
    // Example:
    // 101-150 => 101
    // 20-25 => 20

    private Integer episodeNoNumeric;

    // =====================================
    // TITLE
    // =====================================

    private String title;

    // =====================================
    // TELEGRAM
    // =====================================

    @Column(columnDefinition = "TEXT")
    private String telegramFileId;

    private Long telegramMessageId;

    // =====================================
    // AUDIO META
    // =====================================

    private Integer durationSeconds;

    private Long fileSize;

    // =====================================
    // DETECTED ?
    // =====================================

    // REGEX / AI DETECTED

    private Boolean isEpisodeDetected;

    // =====================================
    // TIME
    // =====================================

    private LocalDateTime uploadedAt;
}
