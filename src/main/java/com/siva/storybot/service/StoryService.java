package com.siva.storybot.service;

import com.siva.storybot.entity.Story;
import com.siva.storybot.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryService {


    private final StoryRepository storyRepository;

    // =========================================
    // FIND OR CREATE STORY
    // =========================================
    public Story findOrCreateStory(Long telegramChatId, String title, String telegramUsername, String chatType, String description, String inviteLink) {

        try {

            // =====================================
            // COMPLETED CHECK
            // =====================================

            boolean isCompleted = description != null && description.toLowerCase().contains("completed");

            Story existingStory = storyRepository.findByTelegramChatId(telegramChatId).orElse(null);

            // =====================================
            // UPDATE EXISTING STORY
            // =====================================

            if (existingStory != null) {

                existingStory.setTitle(title);

                existingStory.setTelegramUsername(telegramUsername);

                existingStory.setChatType(chatType);

                existingStory.setDescription(description);

                existingStory.setInviteLink(inviteLink);

                existingStory.setIsCompleted(isCompleted);

                storyRepository.save(existingStory);

                log.info("Story updated title={} completed={}", title, isCompleted);

                return existingStory;
            }

            // =====================================
            // CREATE NEW STORY
            // =====================================

            Story story = Story.builder()

                    .telegramChatId(telegramChatId)

                    .title(title)

                    .telegramUsername(telegramUsername)

                    .chatType(chatType)

                    .description(description)

                    .inviteLink(inviteLink)

                    .isCompleted(isCompleted)

                    .active(true)

                    .createdAt(LocalDateTime.now())

                    .build();

            storyRepository.save(story);

            log.info("Story created title={} completed={}", title, isCompleted);

            return story;

        } catch (Exception e) {

            log.error("findOrCreateStory failed", e);

            throw e;
        }
    }

    // =====================================
    // GET STORIES
    // =====================================
    public Page<Story> getStories(int page, int size) {

        return storyRepository.findAll(

                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    // =====================================
    // GET STORY BY ID
    // =====================================
    public Story getStoryById(Long id) {

        return storyRepository.findById(id).orElse(null);
    }

    // =====================================
    // COMPLETED STORIES
    // =====================================
    public Page<Story> getCompletedStories(int page, int size) {

        return storyRepository.getCompletedStories(

                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    // =====================================
    // ONGOING STORIES
    // =====================================

    public Page<Story> getOnGoingStories(int page, int size) {

        return storyRepository.getOnGoingStories(

                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    public List<Story> getAllStories() {

        return storyRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    public Story save(Story story) {

        return storyRepository.save(story);
    }

    public List<Story> getInactiveStories() {

        return storyRepository.findByActiveFalse();
    }

    @Transactional
    public void deleteStory(Story story) {

        storyRepository.delete(story);
    }
}