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
@Table(name = "telegram_users")
public class TelegramUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Telegram User ID
    @Column(unique = true, nullable = false)
    private Long telegramId;

    // Chat ID
    @Column(nullable = false)
    private Long chatId;

    // Username
    private String username;

    // First Name
    private String firstName;

    // Last Name
    private String lastName;

    // Language
    private String languageCode;

    // Telegram Premium User
    private Boolean premiumUser;

    // Is Telegram Bot
    private Boolean bot;

    // First Joined Time
    private LocalDateTime joinedAt;

    // Last Active Time
    private LocalDateTime lastActiveAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;
}