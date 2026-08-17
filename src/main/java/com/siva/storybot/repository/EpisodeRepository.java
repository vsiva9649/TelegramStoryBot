package com.siva.storybot.repository;

import com.siva.storybot.entity.Episode;
import com.siva.storybot.entity.Story;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {

    Page<Episode> findByStoryOrderByEpisodeNoNumericDesc(Story story, Pageable pageable);

    List<Episode> findByIsEpisodeDetectedFalse();

    List<Episode> findByStory(Story story);

}