package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.TelegramUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramUserService {

    private final TelegramUserRepository telegramUserRepository;

    private final TelegramConfig telegramConfig;

    // =========================================
    // SAVE OR UPDATE TELEGRAM USER
    // =========================================

    @Transactional
    public TelegramUser saveOrUpdateUser(User telegramApiUser, Long chatId) {

        try {

            // =====================================
            // VALIDATION
            // =====================================

            if (telegramApiUser == null) {

                throw new IllegalArgumentException("Telegram API user cannot be null");
            }

            if (telegramApiUser.getId() == null) {

                throw new IllegalArgumentException("Telegram user ID cannot be null");
            }

            if (chatId == null) {

                throw new IllegalArgumentException("Telegram chat ID cannot be null");
            }

            Long telegramId = telegramApiUser.getId();

            TelegramUser existingUser = telegramUserRepository.findByTelegramId(telegramId).orElse(null);

            // =====================================
            // EXISTING USER
            // =====================================

            if (existingUser != null) {

                updateExistingUser(existingUser, telegramApiUser, chatId);

                // The configured bot owner must always retain OWNER role,
                // even if an older database row was created with USER/ADMIN.
                if (telegramConfig.getOwnerId() != null
                        && telegramId.equals(telegramConfig.getOwnerId())
                        && existingUser.getRole() != UserRole.OWNER) {

                    existingUser.setRole(UserRole.OWNER);
                }

                TelegramUser savedUser = telegramUserRepository.save(existingUser);

                log.debug("Existing Telegram user updated telegramId={} role={} joinedAt={} lastActiveAt={}", savedUser.getTelegramId(), savedUser.getRole(), savedUser.getJoinedAt(), savedUser.getLastActiveAt());

                return savedUser;
            }

            // =====================================
            // NEW USER ROLE
            // =====================================

            UserRole role = determineUserRole(telegramId);

            LocalDateTime now = LocalDateTime.now();

            // =====================================
            // CREATE NEW USER
            //
            // joinedAt is CRITICAL for global trial.
            // Never change it after creation.
            // =====================================

            TelegramUser newUser = TelegramUser.builder()

                    .telegramId(telegramId)

                    .chatId(chatId)

                    .username(telegramApiUser.getUserName())

                    .firstName(telegramApiUser.getFirstName())

                    .lastName(telegramApiUser.getLastName())

                    .languageCode(telegramApiUser.getLanguageCode())

                    .premiumUser(Boolean.TRUE.equals(telegramApiUser.getIsPremium()))

                    .bot(telegramApiUser.getIsBot())

                    .role(role)

                    .joinedAt(now)

                    .lastActiveAt(now)

                    .build();

            TelegramUser savedUser = telegramUserRepository.save(newUser);

            log.info("""
                    New Telegram user created
                    telegramId={}
                    username={}
                    role={}
                    joinedAt={}
                    """, savedUser.getTelegramId(), savedUser.getUsername(), savedUser.getRole(), savedUser.getJoinedAt());

            return savedUser;

        } catch (Exception e) {

            log.error("saveOrUpdateUser failed telegramId={}", telegramApiUser != null ? telegramApiUser.getId() : null, e);

            throw e;
        }
    }

    // =========================================
    // UPDATE EXISTING USER
    //
    // IMPORTANT:
    //
    // joinedAt MUST NOT be changed.
    // Global trial logic depends on joinedAt.
    // =========================================

    private void updateExistingUser(TelegramUser existingUser, User telegramApiUser, Long chatId) {

        existingUser.setChatId(chatId);

        existingUser.setUsername(telegramApiUser.getUserName());

        existingUser.setFirstName(telegramApiUser.getFirstName());

        existingUser.setLastName(telegramApiUser.getLastName());

        existingUser.setLanguageCode(telegramApiUser.getLanguageCode());

        existingUser.setPremiumUser(Boolean.TRUE.equals(telegramApiUser.getIsPremium()));

        existingUser.setBot(telegramApiUser.getIsBot());

        // =====================================
        // UPDATE ONLY LAST ACTIVE TIME
        // =====================================

        existingUser.setLastActiveAt(LocalDateTime.now());

        // =====================================
        // NEVER DO THIS
        // =====================================

        // existingUser.setJoinedAt(
        //         LocalDateTime.now()
        // );
    }

    // =========================================
    // DETERMINE USER ROLE
    // =========================================

    private UserRole determineUserRole(Long telegramId) {

        if (telegramId != null && telegramConfig.getOwnerId() != null && telegramId.equals(telegramConfig.getOwnerId())) {

            return UserRole.OWNER;
        }

        return UserRole.USER;
    }

    // =========================================
    // PAGINATION
    // =========================================

    public Page<TelegramUser> getUsers(int page, int size) {

        int safePage = Math.max(page, 0);

        int safeSize = Math.max(size, 1);

        return telegramUserRepository.findByRoleNotOrderByLastActiveAtDesc(UserRole.OWNER, PageRequest.of(safePage, safeSize));
    }

    // =========================================
    // ALL NON-OWNER USERS
    //
    // Used by owner active/expired access views.
    // =========================================

    public List<TelegramUser> getAllNonOwnerUsers() {

        return telegramUserRepository.findByRoleNotOrderByLastActiveAtDesc(UserRole.OWNER);
    }

    // =========================================
    // FIND USER BY TELEGRAM ID
    // =========================================

    public TelegramUser getUserByTelegramId(Long telegramId) {

        if (telegramId == null) {

            return null;
        }

        return telegramUserRepository.findByTelegramId(telegramId).orElse(null);
    }

    // =========================================
    // FIND USER BY USERNAME
    // =========================================

    public TelegramUser getUserByUsername(String username) {

        if (username == null || username.isBlank()) {

            return null;
        }

        String cleanedUsername = username.trim().replace("@", "");

        return telegramUserRepository.findByUsername(cleanedUsername).orElse(null);
    }

    // =========================================
    // UPDATE USER ROLE
    // =========================================

    @Transactional
    public TelegramUser updateUserRole(TelegramUser user, UserRole role) {

        if (user == null) {

            throw new IllegalArgumentException("Telegram user is required");
        }

        if (role == null) {

            throw new IllegalArgumentException("User role is required");
        }

        user.setRole(role);

        TelegramUser updatedUser = telegramUserRepository.save(user);

        log.info("User role updated telegramId={} role={}", updatedUser.getTelegramId(), updatedUser.getRole());

        return updatedUser;
    }
}