package com.siva.storybot.entity;

import com.siva.storybot.enums.RewardTrialStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "reward_trials",
        indexes = {
                @Index(name = "idx_reward_trial_user", columnList = "telegram_user_id"),
                @Index(name = "idx_reward_trial_status", columnList = "status"),
                @Index(name = "idx_reward_trial_expires", columnList = "expires_at"),
                @Index(name = "idx_reward_trial_selected_story", columnList = "selected_story_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reward_trial_token", columnNames = "token")
        }
)
public class RewardTrial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "telegram_user_id", nullable = false)
    private TelegramUser telegramUser;

    /**
     * Story selected for THIS reward activation.
     *
     * A reward user may browse every active story, but the first story selected
     * during the active reward is bound here. The 1-hour reward can deliver
     * episodes only from this one story. A new reward row starts with null and
     * can therefore select a different story next time.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "selected_story_id")
    private Story selectedStory;

    @Column(nullable = false, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RewardTrialStatus status;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "short_url", length = 1000)
    private String shortUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "link_expires_at", nullable = false)
    private LocalDateTime linkExpiresAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = RewardTrialStatus.PENDING;
        }
        if (provider == null || provider.isBlank()) {
            provider = "SHRTFLY";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
