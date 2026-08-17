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
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramUserService {

    private final TelegramUserRepository telegramUserRepository;

    private final TelegramConfig telegramConfig;

    // =========================================
    // SAVE OR UPDATE USER
    // =========================================

    public TelegramUser saveOrUpdateUser(User telegramApiUser, Long chatId) {

        try {

            Long telegramId = telegramApiUser.getId();

            TelegramUser existingUser = telegramUserRepository.findByTelegramId(telegramId).orElse(null);

            // =====================================
            // UPDATE EXISTING USER
            // =====================================

            if (existingUser != null) {

                updateExistingUser(existingUser, telegramApiUser, chatId);

                log.info("Existing user updated telegramId={}", telegramId);

                return telegramUserRepository.save(existingUser);
            }

            // =====================================
            // CREATE NEW USER
            // =====================================

            UserRole role = UserRole.USER;

            if (telegramId.equals(telegramConfig.getOwnerId())) {

                role = UserRole.OWNER;
            }

            TelegramUser newUser = TelegramUser.builder().telegramId(telegramId).chatId(chatId).username(telegramApiUser.getUserName()).firstName(telegramApiUser.getFirstName()).lastName(telegramApiUser.getLastName()).languageCode(telegramApiUser.getLanguageCode()).premiumUser(telegramApiUser.getIsPremium()).bot(telegramApiUser.getIsBot()).role(role).joinedAt(LocalDateTime.now()).lastActiveAt(LocalDateTime.now()).build();

            log.info("New user created telegramId={} role={}", telegramId, role);

            return telegramUserRepository.save(newUser);

        } catch (Exception e) {

            log.error("saveOrUpdateUser failed", e);

            throw e;
        }
    }

    // =========================================
    // UPDATE USER
    // =========================================

    private void updateExistingUser(TelegramUser existingUser, User telegramApiUser, Long chatId) {

        existingUser.setChatId(chatId);

        existingUser.setUsername(telegramApiUser.getUserName());

        existingUser.setFirstName(telegramApiUser.getFirstName());

        existingUser.setLastName(telegramApiUser.getLastName());

        existingUser.setLanguageCode(telegramApiUser.getLanguageCode());

        existingUser.setPremiumUser(telegramApiUser.getIsPremium());

        existingUser.setBot(telegramApiUser.getIsBot());

        existingUser.setLastActiveAt(LocalDateTime.now());
    }

    // =========================================
    // PAGINATION
    // =========================================

    public Page<TelegramUser> getUsers(int page, int size) {

        return telegramUserRepository.findByRoleNotOrderByLastActiveAtDesc(UserRole.OWNER, PageRequest.of(page, size));
    }

    // =========================================
    // FIND USER
    // =========================================

    public TelegramUser getUserByTelegramId(Long telegramId) {

        return telegramUserRepository.findByTelegramId(telegramId).orElse(null);
    }

    public TelegramUser getUserByUsername(String username) {

        return telegramUserRepository.findByUsername(username).orElse(null);
    }

    // =========================================
    // UPDATE USER ROLE
    // =========================================

    public TelegramUser updateUserRole(TelegramUser user, UserRole role) {

        user.setRole(role);

        TelegramUser updatedUser = telegramUserRepository.save(user);

        log.info("User role updated telegramId={} role={}", user.getTelegramId(), role);

        return updatedUser;
    }
}

