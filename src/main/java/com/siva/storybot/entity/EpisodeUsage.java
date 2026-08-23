package com.siva.storybot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "episode_usage",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_episode_usage_telegram_user", columnNames = "telegram_user_id")
        },
        indexes = {
                @Index(name = "idx_episode_usage_usage_date", columnList = "usage_date"),
                @Index(name = "idx_episode_usage_hour_window", columnList = "hour_window_start")
        }
)
public class EpisodeUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "telegram_user_id", nullable = false, unique = true)
    private TelegramUser telegramUser;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "daily_count", nullable = false)
    private Integer dailyCount;

    @Column(name = "hour_window_start", nullable = false)
    private LocalDateTime hourWindowStart;

    @Column(name = "hourly_count", nullable = false)
    private Integer hourlyCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (usageDate == null) {
            usageDate = now.toLocalDate();
        }
        if (dailyCount == null) {
            dailyCount = 0;
        }
        if (hourWindowStart == null) {
            hourWindowStart = now;
        }
        if (hourlyCount == null) {
            hourlyCount = 0;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
