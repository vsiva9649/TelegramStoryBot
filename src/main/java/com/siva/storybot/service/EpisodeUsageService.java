package com.siva.storybot.service;

import com.siva.storybot.entity.EpisodeUsage;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.EpisodeLimitPolicy;
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

    /**
     * Backward-compatible aliases.
     * <p>
     * The actual numeric values live only in EpisodeLimitPolicy.
     * Existing code that still references these constants will continue
     * to compile while new code should use EpisodeLimitPolicy directly.
     */
    @Deprecated
    public static final int MAX_EPISODES_PER_HOUR = EpisodeLimitPolicy.STANDARD_USER.getPerHour();

    @Deprecated
    public static final int MAX_EPISODES_PER_DAY = EpisodeLimitPolicy.STANDARD_USER.getPerDay();

    private static final ZoneId LIMIT_ZONE = ZoneId.of("Asia/Kolkata");

    private final EpisodeUsageRepository episodeUsageRepository;

    /**
     * Backward-compatible standard USER quota.
     */
    @Transactional
    public synchronized EpisodeQuota getQuota(TelegramUser user) {
        return getQuota(user, EpisodeLimitPolicy.STANDARD_USER);
    }

    // =========================================================
    // QUOTA READ
    // =========================================================

    /**
     * Returns the user's current usage against the supplied policy.
     * <p>
     * USER + REWARD_TRIAL  -> 50/hour, 50/day
     * USER + STANDARD_USER -> 200/hour, 250/day
     * ADMIN/OWNER          -> unlimited regardless of supplied policy
     */
    @Transactional
    public synchronized EpisodeQuota getQuota(TelegramUser user, EpisodeLimitPolicy policy) {

        EpisodeLimitPolicy effectivePolicy = normalizePolicy(user, policy);

        if (effectivePolicy.hasUnlimitedHourlyAndDailyUsage()) {
            return unlimitedQuota();
        }

        EpisodeUsage usage = getOrCreateNormalizedUsage(user);

        return toQuota(usage, effectivePolicy);
    }

    /**
     * Backward-compatible standard USER recording.
     */
    @Transactional
    public synchronized EpisodeQuota recordEpisodeDelivered(TelegramUser user) {
        return recordEpisodeDelivered(user, EpisodeLimitPolicy.STANDARD_USER);
    }

    // =========================================================
    // RECORD DELIVERED EPISODE
    // =========================================================

    /**
     * Records one successfully delivered episode using the supplied policy.
     * <p>
     * The quota is checked before incrementing so a reward user cannot be
     * recorded above 50/hour or 50/day through this method.
     */
    @Transactional
    public synchronized EpisodeQuota recordEpisodeDelivered(TelegramUser user, EpisodeLimitPolicy policy) {

        EpisodeLimitPolicy effectivePolicy = normalizePolicy(user, policy);

        if (effectivePolicy.hasUnlimitedHourlyAndDailyUsage()) {
            return unlimitedQuota();
        }

        EpisodeUsage usage = getOrCreateNormalizedUsage(user);
        EpisodeQuota before = toQuota(usage, effectivePolicy);

        if (!before.allowed()) {
            return before;
        }

        usage.setHourlyCount(safeCount(usage.getHourlyCount()) + 1);
        usage.setDailyCount(safeCount(usage.getDailyCount()) + 1);
        usage.setUpdatedAt(LocalDateTime.now(LIMIT_ZONE));

        EpisodeUsage saved = episodeUsageRepository.save(usage);

        EpisodeQuota after = toQuota(saved, effectivePolicy);

        log.debug("Episode usage recorded telegramId={} policy={} hourly={}/{} daily={}/{}", user.getTelegramId(), effectivePolicy, saved.getHourlyCount(), effectivePolicy.getPerHour(), saved.getDailyCount(), effectivePolicy.getPerDay());

        return after;
    }

    private EpisodeUsage getOrCreateNormalizedUsage(TelegramUser user) {

        LocalDateTime now = LocalDateTime.now(LIMIT_ZONE);
        LocalDate today = now.toLocalDate();

        EpisodeUsage usage = episodeUsageRepository.findByTelegramUser(user).orElseGet(() -> EpisodeUsage.builder().telegramUser(user).usageDate(today).dailyCount(0).hourWindowStart(now).hourlyCount(0).updatedAt(now).build());

        boolean changed = usage.getId() == null;

        // New calendar day in Asia/Kolkata -> reset daily usage.
        if (usage.getUsageDate() == null || !today.equals(usage.getUsageDate())) {

            usage.setUsageDate(today);
            usage.setDailyCount(0);
            changed = true;
        }

        if (usage.getDailyCount() == null || usage.getDailyCount() < 0) {

            usage.setDailyCount(0);
            changed = true;
        }

        // Rolling 60-minute window.
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

    // =========================================================
    // USAGE NORMALIZATION
    // =========================================================

    private EpisodeQuota toQuota(EpisodeUsage usage, EpisodeLimitPolicy policy) {

        int hourlyUsed = safeCount(usage.getHourlyCount());
        int dailyUsed = safeCount(usage.getDailyCount());

        int hourlyRemaining = Math.max(0, policy.getPerHour() - hourlyUsed);

        int dailyRemaining = Math.max(0, policy.getPerDay() - dailyUsed);

        // Daily check first. For reward users both limits are 50, so once
        // 50 episodes are used today they cannot resume after an hourly reset.
        if (dailyRemaining <= 0) {
            return new EpisodeQuota(false, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "DAILY_LIMIT");
        }

        if (hourlyRemaining <= 0) {
            return new EpisodeQuota(false, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "HOURLY_LIMIT");
        }

        return new EpisodeQuota(true, hourlyUsed, hourlyRemaining, dailyUsed, dailyRemaining, "AVAILABLE");
    }

    // =========================================================
    // QUOTA CALCULATION
    // =========================================================

    private EpisodeLimitPolicy normalizePolicy(TelegramUser user, EpisodeLimitPolicy requestedPolicy) {

        if (user == null || user.getRole() != UserRole.USER) {
            return EpisodeLimitPolicy.ADMIN_OWNER;
        }

        if (requestedPolicy == null || requestedPolicy.hasUnlimitedHourlyAndDailyUsage()) {
            return EpisodeLimitPolicy.STANDARD_USER;
        }

        return requestedPolicy;
    }

    private EpisodeQuota unlimitedQuota() {
        return new EpisodeQuota(true, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, "UNLIMITED");
    }

    private int safeCount(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    public record EpisodeQuota(boolean allowed, int hourlyUsed, int hourlyRemaining, int dailyUsed, int dailyRemaining,
                               String reason) {
    }
}