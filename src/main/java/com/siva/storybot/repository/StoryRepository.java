package com.siva.storybot.repository;

import com.siva.storybot.entity.Story;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryRepository extends JpaRepository<Story, Long> {

    // =====================================
    // FIND BY CHAT ID
    // =====================================

    Optional<Story> findByTelegramChatId(Long telegramChatId);

    // =====================================
    // COMPLETED STORIES
    // =====================================
    @Query("""
    SELECT s
    FROM Story s
    WHERE s.active = true
      AND s.isCompleted = true
    """)
    Page<Story> getCompletedStories(Pageable pageable);

    // =====================================
    // ONGOING STORIES
    // =====================================
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

    List<Story> findByActiveFalse();
}