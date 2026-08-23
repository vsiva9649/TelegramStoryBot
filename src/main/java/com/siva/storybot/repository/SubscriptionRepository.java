package com.siva.storybot.repository;

import com.siva.storybot.entity.Subscription;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByTelegramUser(TelegramUser telegramUser);

    List<Subscription> findByStatusAndExpiryDateBefore(SubscriptionStatus status, LocalDate expiryDate);

    List<Subscription> findByTelegramUserOrderByCreatedAtDesc(TelegramUser telegramUser);

    Optional<Subscription> findTopByTelegramUserAndStatusOrderByExpiryDateDesc(TelegramUser telegramUser, SubscriptionStatus status);

    List<Subscription> findAllByTelegramUserAndStatusOrderByExpiryDateDesc(
            TelegramUser telegramUser,
            SubscriptionStatus status
    );

}