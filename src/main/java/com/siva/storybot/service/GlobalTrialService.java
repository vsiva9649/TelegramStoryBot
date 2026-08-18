package com.siva.storybot.service;

import com.siva.storybot.entity.GlobalTrial;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.repository.GlobalTrialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalTrialService {

    private static final int DEFAULT_TRIAL_DAYS = 7;

    private final GlobalTrialRepository globalTrialRepository;

    // =========================================
    // ENABLE GLOBAL TRIAL
    // =========================================

    @Transactional
    public GlobalTrial enableGlobalTrial(LocalDateTime endDate, Long enabledBy) {

        LocalDateTime now = LocalDateTime.now();

        if (endDate == null) {

            throw new IllegalArgumentException("Global trial end date is required");
        }

        if (!endDate.isAfter(now)) {

            throw new IllegalArgumentException("Global trial end date must be in future");
        }

        // =====================================
        // DISABLE PREVIOUS ACTIVE CAMPAIGN
        // =====================================

        globalTrialRepository.findTopByOrderByIdDesc().ifPresent(previous -> {

            if (Boolean.TRUE.equals(previous.getEnabled())) {

                previous.setEnabled(false);

                globalTrialRepository.save(previous);
            }
        });

        // =====================================
        // CREATE NEW CAMPAIGN
        // =====================================

        GlobalTrial trial = GlobalTrial.builder().enabled(true).trialDays(DEFAULT_TRIAL_DAYS).startDate(now).endDate(endDate).enabledBy(enabledBy).build();

        GlobalTrial saved = globalTrialRepository.save(trial);

        log.info("""
                Global trial enabled
                id={}
                trialDays={}
                startDate={}
                endDate={}
                enabledBy={}
                """, saved.getId(), saved.getTrialDays(), saved.getStartDate(), saved.getEndDate(), saved.getEnabledBy());

        return saved;
    }

    // =========================================
    // DISABLE GLOBAL TRIAL
    // =========================================

    @Transactional
    public boolean disableGlobalTrial() {

        Optional<GlobalTrial> optional = globalTrialRepository.findTopByOrderByIdDesc();

        if (optional.isEmpty()) {

            return false;
        }

        GlobalTrial trial = optional.get();

        if (!Boolean.TRUE.equals(trial.getEnabled())) {

            return false;
        }

        trial.setEnabled(false);

        globalTrialRepository.save(trial);

        log.info("Global trial disabled id={}", trial.getId());

        return true;
    }

    // =========================================
    // GET CURRENT ACTIVE GLOBAL TRIAL
    // =========================================

    @Transactional
    public Optional<GlobalTrial> getActiveGlobalTrial() {

        Optional<GlobalTrial> optional = globalTrialRepository.findTopByOrderByIdDesc();

        if (optional.isEmpty()) {

            return Optional.empty();
        }

        GlobalTrial trial = optional.get();

        if (!Boolean.TRUE.equals(trial.getEnabled())) {

            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();

        if (trial.getStartDate() == null || trial.getEndDate() == null) {

            return Optional.empty();
        }

        // =====================================
        // AUTO EXPIRE GLOBAL CAMPAIGN
        // =====================================

        if (!now.isBefore(trial.getEndDate())) {

            trial.setEnabled(false);

            globalTrialRepository.save(trial);

            log.info("Global trial auto-expired id={} endDate={}", trial.getId(), trial.getEndDate());

            return Optional.empty();
        }

        return Optional.of(trial);
    }

    // =========================================
    // USER GLOBAL TRIAL ACCESS
    // =========================================

    public boolean hasGlobalTrialAccess(TelegramUser user) {

        if (user == null) {
            return false;
        }

        Optional<GlobalTrial> optional = getActiveGlobalTrial();

        if (optional.isEmpty()) {
            return false;
        }

        GlobalTrial globalTrial = optional.get();

        LocalDateTime globalStart = globalTrial.getStartDate();

        LocalDateTime globalEnd = globalTrial.getEndDate();

        // =====================================
        // DETERMINE USER TRIAL START
        //
        // OLD USER:
        // campaign start
        //
        // NEW USER:
        // joinedAt
        // =====================================

        LocalDateTime userTrialStart;

        if (user.getJoinedAt() == null || user.getJoinedAt().isBefore(globalStart)) {

            userTrialStart = globalStart;

        } else {

            userTrialStart = user.getJoinedAt();
        }

        // =====================================
        // USER NORMAL TRIAL EXPIRY
        // =====================================

        LocalDateTime normalExpiry = userTrialStart.plusDays(globalTrial.getTrialDays());

        // =====================================
        // USER CANNOT CROSS GLOBAL END DATE
        // =====================================

        LocalDateTime effectiveExpiry = normalExpiry.isBefore(globalEnd) ? normalExpiry : globalEnd;

        LocalDateTime now = LocalDateTime.now();

        boolean active = !now.isBefore(userTrialStart) && now.isBefore(effectiveExpiry);

        log.debug("""
                Global trial access
                telegramId={}
                joinedAt={}
                userTrialStart={}
                normalExpiry={}
                globalEnd={}
                effectiveExpiry={}
                active={}
                """, user.getTelegramId(), user.getJoinedAt(), userTrialStart, normalExpiry, globalEnd, effectiveExpiry, active);

        return active;
    }

    // =========================================
    // USER EFFECTIVE TRIAL EXPIRY
    // =========================================

    public Optional<LocalDateTime> getUserTrialExpiry(TelegramUser user) {

        if (user == null) {

            return Optional.empty();
        }

        Optional<GlobalTrial> optional = getActiveGlobalTrial();

        if (optional.isEmpty()) {

            return Optional.empty();
        }

        GlobalTrial trial = optional.get();

        LocalDateTime trialStart;

        if (user.getJoinedAt() == null || user.getJoinedAt().isBefore(trial.getStartDate())) {

            trialStart = trial.getStartDate();

        } else {

            trialStart = user.getJoinedAt();
        }

        LocalDateTime normalExpiry = trialStart.plusDays(trial.getTrialDays());

        LocalDateTime effectiveExpiry = normalExpiry.isBefore(trial.getEndDate()) ? normalExpiry : trial.getEndDate();

        return Optional.of(effectiveExpiry);
    }
}