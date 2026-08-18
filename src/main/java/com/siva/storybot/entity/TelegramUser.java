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
@Table(name = "telegram_users", indexes = {@Index(name = "idx_telegram_users_telegram_id", columnList = "telegramId"), @Index(name = "idx_telegram_users_username", columnList = "username")})
public class TelegramUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =========================================
    // TELEGRAM USER ID
    // =========================================

    @Column(unique = true, nullable = false)
    private Long telegramId;

    // =========================================
    // CHAT ID
    // =========================================

    @Column(nullable = false)
    private Long chatId;

    // =========================================
    // TELEGRAM PROFILE
    // =========================================

    private String username;

    private String firstName;

    private String lastName;

    private String languageCode;

    private Boolean premiumUser;

    private Boolean bot;

    // =========================================
    // USER ACTIVITY
    // =========================================

    // First time user entered StoryBot
    @Column(nullable = false)
    private LocalDateTime joinedAt;

    // Last interaction with StoryBot
    @Column(nullable = false)
    private LocalDateTime lastActiveAt;

    // =========================================
    // ROLE
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    // =========================================
    // ENTITY DEFAULTS
    // =========================================

    @PrePersist
    public void prePersist() {

        LocalDateTime now = LocalDateTime.now();

        if (joinedAt == null) {
            joinedAt = now;
        }

        if (lastActiveAt == null) {
            lastActiveAt = now;
        }

        if (role == null) {
            role = UserRole.USER;
        }

        if (premiumUser == null) {
            premiumUser = false;
        }

        if (bot == null) {
            bot = false;
        }
    }
}