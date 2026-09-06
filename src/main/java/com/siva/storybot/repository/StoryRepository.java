package com.siva.storybot.repository;

import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {

    Optional<Story> findByTelegramChatId(Long telegramChatId);

    Page<Story> findByActiveTrue(Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND s.isCompleted = true
            """)
    Page<Story> getCompletedStories(Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND (
                    s.isCompleted = false
                    OR s.isCompleted IS NULL
                  )
            """)
    Page<Story> getOnGoingStories(Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND s.isCompleted = true
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                  )
            """)
    Page<Story> getCompletedStoriesForUser(
            @Param("user") TelegramUser user,
            Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND (
                    s.isCompleted = false
                    OR s.isCompleted IS NULL
                  )
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                  )
            """)
    Page<Story> getOnGoingStoriesForUser(
            @Param("user") TelegramUser user,
            Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND s.isCompleted = true
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                      AND a.grantedByRole = :ownerRole
                  )
            """)
    Page<Story> getCompletedStoriesForAdmin(
            @Param("user") TelegramUser user,
            @Param("ownerRole") UserRole ownerRole,
            Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND (
                    s.isCompleted = false
                    OR s.isCompleted IS NULL
                  )
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                      AND a.grantedByRole = :ownerRole
                  )
            """)
    Page<Story> getOnGoingStoriesForAdmin(
            @Param("user") TelegramUser user,
            @Param("ownerRole") UserRole ownerRole,
            Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                  )
            ORDER BY s.id DESC
            """)
    List<Story> getActiveStoriesForUserList(@Param("user") TelegramUser user);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                      AND a.grantedByRole = :ownerRole
                  )
            """)
    Page<Story> getActiveStoriesForAdmin(
            @Param("user") TelegramUser user,
            @Param("ownerRole") UserRole ownerRole,
            Pageable pageable);

    @Query("""
            SELECT s
            FROM Story s
            WHERE s.active = true
              AND EXISTS (
                    SELECT a.id
                    FROM UserStoryAccess a
                    WHERE a.telegramUser = :user
                      AND a.story = s
                      AND a.active = true
                      AND a.grantedByRole = :ownerRole
                  )
            ORDER BY s.id DESC
            """)
    List<Story> getActiveStoriesForAdminList(
            @Param("user") TelegramUser user,
            @Param("ownerRole") UserRole ownerRole);

    List<Story> findByActiveFalse();
}
