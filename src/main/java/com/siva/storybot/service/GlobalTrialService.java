package com.siva.storybot.service;

import com.siva.storybot.entity.GlobalTrial;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.repository.GlobalTrialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalTrialService {

    private static final int DEFAULT_TRIAL_DAYS = 7;

    private final GlobalTrialRepository globalTrialRepository;

    // =========================================
    // ENABLE GLOBAL TRIAL
    //
    // IMPORTANT:
    // - Repeated ON for the same active campaign
    //   is idempotent: no duplicate row.
    // - OFF -> ON reuses the latest config row.
    // - Existing legacy duplicate ACTIVE rows are
    //   disabled so only one campaign is active.
    // =========================================

    @Transactional
    public synchronized GlobalTrial enableGlobalTrial(LocalDateTime endDate, Long enabledBy) {

        LocalDateTime now = LocalDateTime.now();

        if (endDate == null) {
            throw new IllegalArgumentException("Global trial end date is required");
        }

        if (!endDate.isAfter(now)) {
            throw new IllegalArgumentException("Global trial end date must be in future");
        }

        // Prefer an existing row for the exact same campaign end date.
        // This makes same-date reactivation idempotent even if another
        // campaign configuration was created in between.
        Optional<GlobalTrial> sameCampaignOptional = globalTrialRepository.findTopByEndDateOrderByIdDesc(endDate);

        Optional<GlobalTrial> targetOptional = sameCampaignOptional.isPresent() ? sameCampaignOptional : globalTrialRepository.findTopByOrderByIdDesc();

        if (targetOptional.isPresent()) {

            GlobalTrial latest = targetOptional.get();

            // Same campaign is already ON.
            // Do not reset a healthy startDate and do not insert another row.
            // If legacy data is malformed, repair this row instead of
            // returning an "enabled" campaign that grants no access.
            boolean sameActiveCampaign = Boolean.TRUE.equals(latest.getEnabled()) && Objects.equals(latest.getEndDate(), endDate);

            boolean healthySameCampaign = sameActiveCampaign && latest.getStartDate() != null && latest.getTrialDays() != null && latest.getTrialDays() > 0;

            if (healthySameCampaign) {

                disableOtherActiveTrials(latest.getId());

                log.info("Global trial already enabled; returning existing config id={} startDate={} endDate={}", latest.getId(), latest.getStartDate(), latest.getEndDate());

                return latest;
            }

            boolean wasAlreadyEnabled = Boolean.TRUE.equals(latest.getEnabled());
            boolean sameEndDate = Objects.equals(latest.getEndDate(), endDate);

            // Remove any legacy duplicate ACTIVE rows first.
            disableAllActiveTrials();

            latest.setEnabled(true);
            latest.setTrialDays(DEFAULT_TRIAL_DAYS);
            latest.setEndDate(endDate);
            latest.setEnabledBy(enabledBy);

            // SAME campaign re-activation (OFF -> ON with same end date):
            // preserve the original startDate so users do not receive
            // a fresh/restarted 7-day trial by toggling OFF and ON.
            //
            // A different end date after OFF is treated as a new campaign
            // window, so start from NOW while still reusing the same config row.
            if (latest.getStartDate() == null || (!wasAlreadyEnabled && !sameEndDate)) {
                latest.setStartDate(now);
            }

            GlobalTrial saved = globalTrialRepository.save(latest);

            log.info("""
                    Global trial enabled/re-activated
                    id={}
                    trialDays={}
                    startDate={}
                    endDate={}
                    enabledBy={}
                    """, saved.getId(), saved.getTrialDays(), saved.getStartDate(), saved.getEndDate(), saved.getEnabledBy());

            return saved;
        }

        // First ever global trial configuration.
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
    //
    // Disable ALL enabled rows, not only the latest.
    // This also cleans up old duplicate ACTIVE rows.
    // =========================================

    @Transactional
    public synchronized boolean disableGlobalTrial() {

        List<GlobalTrial> activeTrials = globalTrialRepository.findAllByEnabledTrueOrderByIdDesc();

        if (activeTrials.isEmpty()) {
            return false;
        }

        activeTrials.forEach(trial -> trial.setEnabled(false));

        globalTrialRepository.saveAll(activeTrials);

        log.info("Global trial disabled activeRows={}", activeTrials.size());

        return true;
    }

    // =========================================
    // GET CURRENT ACTIVE GLOBAL TRIAL
    // =========================================

    @Transactional
    public Optional<GlobalTrial> getActiveGlobalTrial() {

        List<GlobalTrial> activeTrials = globalTrialRepository.findAllByEnabledTrueOrderByIdDesc();

        if (activeTrials.isEmpty()) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        GlobalTrial selected = null;
        List<GlobalTrial> rowsToDisable = new java.util.ArrayList<>();

        // Rows are newest first. Keep the newest VALID, non-expired row.
        // Disable malformed, expired and duplicate ACTIVE rows.
        for (GlobalTrial trial : activeTrials) {

            boolean invalid = trial.getStartDate() == null || trial.getEndDate() == null || trial.getTrialDays() == null || trial.getTrialDays() <= 0;

            if (invalid) {
                trial.setEnabled(false);
                rowsToDisable.add(trial);

                log.warn("Invalid global trial disabled id={} startDate={} endDate={} trialDays={}", trial.getId(), trial.getStartDate(), trial.getEndDate(), trial.getTrialDays());

                continue;
            }

            if (!now.isBefore(trial.getEndDate())) {
                trial.setEnabled(false);
                rowsToDisable.add(trial);

                log.info("Global trial auto-expired id={} endDate={}", trial.getId(), trial.getEndDate());

                continue;
            }

            if (selected == null) {
                selected = trial;
                continue;
            }

            // Any additional valid ACTIVE row is a legacy duplicate.
            trial.setEnabled(false);
            rowsToDisable.add(trial);
        }

        if (!rowsToDisable.isEmpty()) {
            globalTrialRepository.saveAll(rowsToDisable);
        }

        if (selected != null && activeTrials.size() > 1) {
            log.warn("Global trial active-row cleanup completed totalActiveRows={} disabledRows={} keepingId={}", activeTrials.size(), rowsToDisable.size(), selected.getId());
        }

        return Optional.ofNullable(selected);
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

    // =========================================
    // INTERNAL: DISABLE ALL ACTIVE ROWS
    // =========================================

    private void disableAllActiveTrials() {

        List<GlobalTrial> activeTrials = globalTrialRepository.findAllByEnabledTrueOrderByIdDesc();

        if (activeTrials.isEmpty()) {
            return;
        }

        activeTrials.forEach(trial -> trial.setEnabled(false));
        globalTrialRepository.saveAll(activeTrials);
    }

    // =========================================
    // INTERNAL: KEEP ONLY ONE ACTIVE ROW
    // =========================================

    private void disableOtherActiveTrials(Long keepId) {

        if (keepId == null) {
            return;
        }

        List<GlobalTrial> activeTrials = globalTrialRepository.findAllByEnabledTrueOrderByIdDesc();

        List<GlobalTrial> duplicates = activeTrials.stream().filter(trial -> !Objects.equals(trial.getId(), keepId)).toList();

        if (duplicates.isEmpty()) {
            return;
        }

        duplicates.forEach(trial -> trial.setEnabled(false));
        globalTrialRepository.saveAll(duplicates);

        log.warn("Disabled {} duplicate active global trial row(s); keeping id={}", duplicates.size(), keepId);
    }
}
