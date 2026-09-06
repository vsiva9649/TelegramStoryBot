package com.siva.storybot.entity;

import com.siva.storybot.enums.UserRole;
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
        name = "user_story_access",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_story_access_user_story",
                        columnNames = {"telegram_user_id", "story_id"})
        },
        indexes = {
                @Index(name = "idx_user_story_access_user_active", columnList = "telegram_user_id,active"),
                @Index(name = "idx_user_story_access_story_active", columnList = "story_id,active")
        })
public class UserStoryAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "telegram_user_id", nullable = false)
    private TelegramUser telegramUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id")
    private TelegramUser grantedBy;

    /**
     * Role of the actor at the moment this access was granted.
     *
     * This is intentionally persisted instead of checking grantedBy.role later.
     * ADMIN story access is valid only when the mapping was granted by OWNER.
     * Therefore a USER permission granted by an ADMIN cannot become an ADMIN
     * permission accidentally when that USER is promoted later.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "granted_by_role", nullable = false)
    private UserRole grantedByRole;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private LocalDateTime grantedAt;

    private LocalDateTime revokedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (active == null) {
            active = true;
        }

        if (grantedAt == null) {
            grantedAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
