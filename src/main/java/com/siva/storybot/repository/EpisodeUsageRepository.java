package com.siva.storybot.repository;

import com.siva.storybot.entity.EpisodeUsage;
import com.siva.storybot.entity.TelegramUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EpisodeUsageRepository extends JpaRepository<EpisodeUsage, Long> {

    Optional<EpisodeUsage> findByTelegramUser(TelegramUser telegramUser);
}
