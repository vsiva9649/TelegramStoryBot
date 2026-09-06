package com.siva.storybot.service;

import com.siva.storybot.entity.Subscription;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.BillingType;
import com.siva.storybot.enums.SubscriptionPlan;
import com.siva.storybot.enums.SubscriptionStatus;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    private final GlobalTrialService globalTrialService;

    private final RewardTrialService rewardTrialService;

    // =========================================
    // COMPLETE ACCESS CHECK
    //
    // PRIORITY:
    //
    // 1. OWNER / ADMIN bypass subscription/trial checks only
    // 2. ACTIVE NORMAL SUBSCRIPTION
    // 3. MANUAL FREE TRIAL SUBSCRIPTION
    // 4. REWARDED 1-HOUR FREE ACCESS
    // 5. GLOBAL FREE TRIAL
    // 6. DENY
    // =========================================

    public boolean hasAccess(TelegramUser telegramUser) {

        try {

            if (telegramUser == null) {

                return false;
            }

            // =====================================
            // OWNER / ADMIN SUBSCRIPTION BYPASS
            //
            // IMPORTANT: Story-level authorization is enforced separately
            // by StoryAccessService. ADMIN does NOT automatically get all stories.
            // =====================================

            if (telegramUser.getRole() == UserRole.OWNER || telegramUser.getRole() == UserRole.ADMIN) {

                log.debug("Access granted telegramId={} source=ROLE_BYPASS role={}", telegramUser.getTelegramId(), telegramUser.getRole());

                return true;
            }

            // =====================================
            // NORMAL / MANUAL SUBSCRIPTION CHECK
            // =====================================

            if (hasActiveSubscription(telegramUser)) {

                log.debug("Access granted telegramId={} source=SUBSCRIPTION", telegramUser.getTelegramId());

                return true;
            }

            // =====================================
            // REWARDED 1-HOUR FREE ACCESS
            // =====================================

            if (rewardTrialService.hasActiveRewardTrial(telegramUser)) {

                log.debug("Access granted telegramId={} source=REWARD_TRIAL", telegramUser.getTelegramId());

                return true;
            }

            // =====================================
            // GLOBAL FREE TRIAL CHECK
            // =====================================

            boolean globalTrialAccess = globalTrialService.hasGlobalTrialAccess(telegramUser);

            log.debug("Access check telegramId={} source={} active={}", telegramUser.getTelegramId(), globalTrialAccess ? "GLOBAL_TRIAL" : "DENIED", globalTrialAccess);

            return globalTrialAccess;

        } catch (Exception e) {

            log.error("User access validation failed telegramId={}", telegramUser != null ? telegramUser.getTelegramId() : null, e);

            return false;
        }
    }

    // =========================================
    // CHECK ACTIVE NORMAL SUBSCRIPTION
    //
    // Includes:
    // FREE/manual trial
    // MONTHLY
    // YEARLY
    // LIFETIME
    // =========================================

    @Transactional
    public boolean hasActiveSubscription(TelegramUser telegramUser) {

        try {

            if (telegramUser == null) {
                return false;
            }

            List<Subscription> activeSubscriptions = subscriptionRepository.findAllByTelegramUserAndStatusOrderByExpiryDateDesc(telegramUser, SubscriptionStatus.ACTIVE);

            if (activeSubscriptions.isEmpty()) {
                return false;
            }

            LocalDate today = LocalDate.now();
            boolean accessGranted = false;

            for (Subscription subscription : activeSubscriptions) {

                if (subscription == null || subscription.getStatus() != SubscriptionStatus.ACTIVE) {
                    continue;
                }

                // Null or past expiry is never allowed to remain ACTIVE.
                if (subscription.getExpiryDate() == null || today.isAfter(subscription.getExpiryDate())) {

                    expireSubscriptionRecord(subscription);
                    continue;
                }

                boolean isTrial = Boolean.TRUE.equals(subscription.getTrial()) || subscription.getPlan() == SubscriptionPlan.FREE;

                // FREE/manual trials do not require payment.
                // Paid subscriptions require paymentDone=true.
                if (isTrial || Boolean.TRUE.equals(subscription.getPaymentDone())) {
                    accessGranted = true;
                    continue;
                }

                log.warn("Paid subscription payment incomplete telegramId={} subscriptionId={}", telegramUser.getTelegramId(), subscription.getId());
            }

            return accessGranted;

        } catch (Exception e) {

            log.error("Subscription validation failed telegramId={}", telegramUser != null ? telegramUser.getTelegramId() : null, e);

            return false;
        }
    }

    // =========================================
    // CREATE NEW SUBSCRIPTION
    //
    // IMPORTANT:
    //
    // We create a NEW ROW.
    //
    // Don't overwrite previous subscription
    // because we need subscription history.
    // =========================================

    @Transactional
    public Subscription createOrUpdateSubscription(TelegramUser telegramUser, SubscriptionPlan plan, BillingType billingType, BigDecimal amount, Integer validityDays) {

        try {

            // =====================================
            // VALIDATION
            // =====================================

            if (telegramUser == null) {

                throw new IllegalArgumentException("Telegram user is required");
            }

            if (plan == null) {

                throw new IllegalArgumentException("Subscription plan is required");
            }

            if (billingType == null) {

                throw new IllegalArgumentException("Billing type is required");
            }

            if (validityDays == null || validityDays <= 0) {

                throw new IllegalArgumentException("Validity days must be greater than 0");
            }

            // =====================================
            // EXPIRE CURRENT ACTIVE SUBSCRIPTION
            //
            // Then create fresh history row.
            // =====================================

            expireCurrentActiveSubscription(telegramUser);

            LocalDate startDate = LocalDate.now();

            // expiryDate is inclusive in hasActiveSubscription().
            // Therefore N validity days must end on start + (N - 1),
            // otherwise a 7-day trial would actually last 8 calendar days.
            LocalDate expiryDate = startDate.plusDays(validityDays - 1L);

            boolean trial = plan == SubscriptionPlan.FREE;

            // =====================================
            // PAYMENT STATUS
            //
            // FREE trial:
            // paymentDone = false
            //
            // paid plans:
            // paymentDone = true
            // =====================================

            boolean paymentDone = !trial;

            BigDecimal finalAmount = amount != null ? amount : BigDecimal.ZERO;

            Subscription subscription = Subscription.builder()

                    .telegramUser(telegramUser)

                    .plan(plan)

                    .billingType(billingType)

                    .amount(finalAmount)

                    .paymentDone(paymentDone)

                    .trial(trial)

                    .status(SubscriptionStatus.ACTIVE)

                    .startDate(startDate)

                    .expiryDate(expiryDate)

                    .createdAt(LocalDateTime.now())

                    .updatedAt(LocalDateTime.now())

                    .build();

            Subscription savedSubscription = subscriptionRepository.save(subscription);

            log.info("""
                    Subscription created
                    telegramId={}
                    subscriptionId={}
                    plan={}
                    trial={}
                    paymentDone={}
                    startDate={}
                    expiryDate={}
                    """, telegramUser.getTelegramId(), savedSubscription.getId(), savedSubscription.getPlan(), savedSubscription.getTrial(), savedSubscription.getPaymentDone(), savedSubscription.getStartDate(), savedSubscription.getExpiryDate());

            return savedSubscription;

        } catch (Exception e) {

            log.error("createOrUpdateSubscription failed telegramId={}", telegramUser != null ? telegramUser.getTelegramId() : null, e);

            throw e;
        }
    }

    // =========================================
    // EXPIRE CURRENT ACTIVE SUBSCRIPTION
    // =========================================

    @Transactional
    public void expireSubscription(TelegramUser telegramUser) {

        try {

            if (telegramUser == null) {
                return;
            }

            List<Subscription> activeSubscriptions = subscriptionRepository.findAllByTelegramUserAndStatusOrderByExpiryDateDesc(telegramUser, SubscriptionStatus.ACTIVE);

            if (activeSubscriptions.isEmpty()) {

                log.info("No active subscription found telegramId={}", telegramUser.getTelegramId());
                return;
            }

            activeSubscriptions.forEach(this::expireSubscriptionRecord);

            log.info("All active subscriptions manually expired telegramId={} count={}", telegramUser.getTelegramId(), activeSubscriptions.size());

        } catch (Exception e) {

            log.error("Manual subscription expiry failed telegramId={}", telegramUser != null ? telegramUser.getTelegramId() : null, e);

            throw e;
        }
    }

    // =========================================
    // INTERNAL:
    // EXPIRE CURRENT ACTIVE SUBSCRIPTION
    //
    // Used before creating another subscription
    // =========================================

    private void expireCurrentActiveSubscription(TelegramUser telegramUser) {

        if (telegramUser == null) {
            return;
        }

        List<Subscription> activeSubscriptions = subscriptionRepository.findAllByTelegramUserAndStatusOrderByExpiryDateDesc(telegramUser, SubscriptionStatus.ACTIVE);

        activeSubscriptions.forEach(this::expireSubscriptionRecord);
    }

    // =========================================
    // INTERNAL:
    // EXPIRE SINGLE SUBSCRIPTION
    // =========================================

    private void expireSubscriptionRecord(Subscription subscription) {

        if (subscription == null) {

            return;
        }

        subscription.setStatus(SubscriptionStatus.EXPIRED);

        subscription.setUpdatedAt(LocalDateTime.now());

        subscriptionRepository.save(subscription);
    }

    // =========================================
    // AUTO EXPIRE SCHEDULER
    //
    // EVERYDAY 12:00 AM
    // =========================================

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void autoExpireSubscriptions() {

        try {

            log.info("Auto subscription expiry scheduler started");

            LocalDate today = LocalDate.now();

            List<Subscription> subscriptions = subscriptionRepository.findByStatusAndExpiryDateBefore(SubscriptionStatus.ACTIVE, today);

            if (subscriptions == null || subscriptions.isEmpty()) {

                log.info("No expired subscriptions found");

                return;
            }

            LocalDateTime now = LocalDateTime.now();

            for (Subscription subscription : subscriptions) {

                subscription.setStatus(SubscriptionStatus.EXPIRED);

                subscription.setUpdatedAt(now);

                log.info("Subscription auto expired telegramId={} subscriptionId={} expiryDate={}", subscription.getTelegramUser().getTelegramId(), subscription.getId(), subscription.getExpiryDate());
            }

            // =====================================
            // SAVE IN ONE BATCH
            // =====================================

            subscriptionRepository.saveAll(subscriptions);

            log.info("Auto subscription expiry completed count={}", subscriptions.size());

        } catch (Exception e) {

            log.error("Auto subscription expiry failed", e);
        }
    }

    // =========================================
    // SUBSCRIPTION HISTORY
    // =========================================

    public List<Subscription> getUserSubscriptionHistory(TelegramUser telegramUser) {

        try {

            if (telegramUser == null) {

                return List.of();
            }

            return subscriptionRepository.findByTelegramUserOrderByCreatedAtDesc(telegramUser);

        } catch (Exception e) {

            log.error("Failed to get subscription history telegramId={}", telegramUser != null ? telegramUser.getTelegramId() : null, e);

            return List.of();
        }
    }
}