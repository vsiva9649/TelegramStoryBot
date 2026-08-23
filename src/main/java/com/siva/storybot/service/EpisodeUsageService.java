package com.siva.storybot.service;

import com.siva.storybot.entity.EpisodeUsage;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.EpisodeUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeUsageService {

    public static final int MAX_EPISODES_PER_HOUR = 200;
    public static final int MAX_EPISODES_PER_DAY = 250;
    private static final ZoneId LIMIT_ZONE = ZoneId.of("Asia/Kolkata");

    private final EpisodeUsageRepository episodeUsageRepository;

    public record EpisodeQuota(
            boolean allowed,
            int hourlyUsed,
            int hourlyRemaining,
            int dailyUsed,
            int dailyRemaining,
            String reason
    ) {
    }

    @Transactional
    public synchronized EpisodeQuota getQuota(TelegramUser user) {

        if (user == null || user.getRole() != UserRole.USER) {
            return new EpisodeQuota(true, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, "UNLIMITED");
        }

        EpisodeUsage usage = getOrCreateNormalizedUsage(user);

        return toQuota(usage);
    }

    @Transactional
    public synchronized EpisodeQuota recordEpisodeDelivered(TelegramUser user) {

        if (user == null || user.getRole() != UserRole.USER) {
            return new EpisodeQuota(true, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, "UNLIMITED");
        }

        EpisodeUsage usage = getOrCreateNormalizedUsage(user);
        EpisodeQuota before = toQuota(usage);

        if (!before.allowed()) {
            return before;
        }

        usage.setHourlyCount(usage.getHourlyCount() + 1);
        usage.setDailyCount(usage.getDailyCount() + 1);
        usage.setUpdatedAt(LocalDateTime.now(LIMIT_ZONE));

        EpisodeUsage saved = episodeUsageRepository.save(usage);

        EpisodeQuota after = toQuota(saved);

        log.debug(
                "Episode usage recorded telegramId={} hourly={}/{} daily={}/{}",
                user.getTelegramId(),
                saved.getHourlyCount(), MAX_EPISODES_PER_HOUR,
                saved.getDailyCount(), MAX_EPISODES_PER_DAY
        );

        return after;
    }

    private EpisodeUsage getOrCreateNormalizedUsage(TelegramUser user) {

        LocalDateTime now = LocalDateTime.now(LIMIT_ZONE);
        LocalDate today = now.toLocalDate();

        EpisodeUsage usage = episodeUsageRepository.findByTelegramUser(user)
                .orElseGet(() -> EpisodeUsage.builder()
                        .telegramUser(user)
                        .usageDate(today)
                        .dailyCount(0)
                        .hourWindowStart(now)
                        .hourlyCount(0)
                        .updatedAt(now)
                        .build());

        boolean changed = usage.getId() == null;

        if (usage.getUsageDate() == null || !today.equals(usage.getUsageDate())) {
            usage.setUsageDate(today);
            usage.setDailyCount(0);
            changed = true;
        }

        if (usage.getDailyCount() == null || usage.getDailyCount() < 0) {
            usage.setDailyCount(0);
            changed = true;
        }

        if (usage.getHourWindowStart() == null || !now.isBefore(usage.getHourWindowStart().plusHours(1))) {
            usage.setHourWindowStart(now);
            usage.setHourlyCount(0);
            changed = true;
        }

        if (usage.getHourlyCount() == null || usage.getHourlyCount() < 0) {
            usage.setHourlyCount(0);
            changed = true;
        }

        if (changed) {
            usage.setUpdatedAt(now);
            usage = episodeUsageRepository.save(usage);
        }

        return usage;
    }

    private EpisodeQuota toQuota(EpisodeUsage usage) {

        int hourlyUsed = usage.getHourlyCount() == null ? 0 : usage.getHourlyCount();
        int dailyUsed = usage.getDailyCount() == null ? 0 : usage.getDailyCount();

        int hourlyRemaining = Math.max(0, MAX_EPISODES_PER_HOUR - hourlyUsed);
        int dailyRemaining = Math.max(0, MAX_EPISODES_PER_DAY - dailyUsed);

        if (dailyRemaining <= 0) {
            return new EpisodeQuota(false, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "DAILY_LIMIT");
        }

        if (hourlyRemaining <= 0) {
            return new EpisodeQuota(false, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "HOURLY_LIMIT");
        }

        return new EpisodeQuota(true, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "AVAILABLE");
    }
}
