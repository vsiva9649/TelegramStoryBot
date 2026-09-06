package com.siva.storybot.repository;

import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.entity.UserStoryAccess;
import com.siva.storybot.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserStoryAccessRepository extends JpaRepository<UserStoryAccess, Long> {

    Optional<UserStoryAccess> findByTelegramUserAndStory(
            TelegramUser telegramUser,
            Story story);

    boolean existsByTelegramUserAndStoryAndActiveTrue(
            TelegramUser telegramUser,
            Story story);

    boolean existsByTelegramUserAndStoryAndActiveTrueAndGrantedByRole(
            TelegramUser telegramUser,
            Story story,
            UserRole grantedByRole);

    List<UserStoryAccess> findAllByTelegramUserAndActiveTrue(
            TelegramUser telegramUser);

    long countByTelegramUserAndActiveTrue(TelegramUser telegramUser);

    void deleteAllByStory(Story story);
}
