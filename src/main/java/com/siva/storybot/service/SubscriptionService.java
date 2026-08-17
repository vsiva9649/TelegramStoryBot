package com.siva.storybot.service;

import com.siva.storybot.entity.Subscription;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.BillingType;
import com.siva.storybot.enums.SubscriptionPlan;
import com.siva.storybot.enums.SubscriptionStatus;
import com.siva.storybot.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    // =========================================
    // CHECK ACTIVE SUBSCRIPTION
    // =========================================

    public boolean hasActiveSubscription(TelegramUser telegramUser) {

        try {

            Subscription subscription = subscriptionRepository.findByTelegramUser(telegramUser).orElse(null);

            if (subscription == null) {

                return false;
            }

            if (Boolean.FALSE.equals(subscription.getPaymentDone())) {

                return false;
            }

            if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {

                return false;
            }

            if (subscription.getExpiryDate() != null && subscription.getExpiryDate().isBefore(LocalDate.now())) {

                subscription.setStatus(SubscriptionStatus.EXPIRED);

                subscription.setUpdatedAt(LocalDateTime.now());

                subscriptionRepository.save(subscription);

                return false;
            }

            return true;

        } catch (Exception e) {

            log.error("Subscription validation failed", e);

            return false;
        }
    }

    // =========================================
    // CREATE OR UPDATE SUBSCRIPTION
    // =========================================

    public Subscription createOrUpdateSubscription(TelegramUser telegramUser, SubscriptionPlan plan, BillingType billingType, BigDecimal amount, Integer validityDays) {

        try {

            Subscription subscription = subscriptionRepository.findByTelegramUser(telegramUser).orElse(null);

            LocalDate startDate = LocalDate.now();

            LocalDate expiryDate = startDate.plusDays(validityDays);

            // =====================================
            // NEW SUBSCRIPTION
            // =====================================

            if (subscription == null) {

                subscription = Subscription.builder().telegramUser(telegramUser).createdAt(LocalDateTime.now()).build();
            }

            // =====================================
            // UPDATE SUBSCRIPTION
            // =====================================

            subscription.setPlan(plan);

            subscription.setBillingType(billingType);

            subscription.setAmount(amount);

            subscription.setPaymentDone(true);

            subscription.setTrial(plan == SubscriptionPlan.FREE);

            subscription.setStatus(SubscriptionStatus.ACTIVE);

            subscription.setStartDate(startDate);

            subscription.setExpiryDate(expiryDate);

            subscription.setUpdatedAt(LocalDateTime.now());

            Subscription savedSubscription = subscriptionRepository.save(subscription);

            log.info("Subscription updated telegramId={}", telegramUser.getTelegramId());

            return savedSubscription;

        } catch (Exception e) {

            log.error("createOrUpdateSubscription failed", e);

            throw e;
        }
    }

    // =========================================
    // MANUAL EXPIRE
    // =========================================

    public void expireSubscription(TelegramUser telegramUser) {

        Subscription subscription = subscriptionRepository.findByTelegramUser(telegramUser).orElse(null);

        if (subscription == null) {

            return;
        }

        subscription.setStatus(SubscriptionStatus.EXPIRED);

        subscription.setUpdatedAt(LocalDateTime.now());

        subscriptionRepository.save(subscription);

        log.info("Subscription expired telegramId={}", telegramUser.getTelegramId());
    }

    // =========================================
    // AUTO EXPIRE SCHEDULER
    // EVERYDAY 12:00 AM
    // =========================================

    @Scheduled(cron = "0 0 0 * * *")
    public void autoExpireSubscriptions() {

        try {

            log.info("Auto subscription expiry scheduler started");

            List<Subscription> subscriptions = subscriptionRepository.findByStatusAndExpiryDateBefore(SubscriptionStatus.ACTIVE, LocalDate.now());

            if (subscriptions.isEmpty()) {

                log.info("No expired subscriptions found");

                return;
            }

            for (Subscription subscription : subscriptions) {

                subscription.setStatus(SubscriptionStatus.EXPIRED);

                subscription.setUpdatedAt(LocalDateTime.now());

                subscriptionRepository.save(subscription);

                log.info("Subscription auto expired telegramId={}", subscription.getTelegramUser().getTelegramId());
            }

            log.info("Auto subscription expiry completed count={}", subscriptions.size());

        } catch (Exception e) {

            log.error("Auto subscription expiry failed", e);
        }
    }

    public List<Subscription> getUserSubscriptionHistory(TelegramUser telegramUser) {

        return subscriptionRepository.findByTelegramUserOrderByCreatedAtDesc(telegramUser);
    }
}
