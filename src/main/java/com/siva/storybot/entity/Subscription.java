package com.siva.storybot.entity;

import com.siva.storybot.enums.BillingType;
import com.siva.storybot.enums.SubscriptionPlan;
import com.siva.storybot.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "subscriptions", indexes = {@Index(name = "idx_subscription_user", columnList = "telegram_user_id"), @Index(name = "idx_subscription_status", columnList = "status"), @Index(name = "idx_subscription_expiry", columnList = "expiryDate")})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =========================================
    // USER
    //
    // MANY subscription records can belong
    // to ONE Telegram user.
    // Required for subscription history.
    // =========================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telegram_user_id", nullable = false)
    private TelegramUser telegramUser;

    // =========================================
    // PLAN
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionPlan plan;

    // FREE / MONTHLY / YEARLY / LIFETIME

    // =========================================
    // BILLING
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingType billingType;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    // =========================================
    // PAYMENT
    // =========================================

    @Column(nullable = false)
    private Boolean paymentDone;

    // =========================================
    // STATUS
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status;

    // ACTIVE / EXPIRED / CANCELLED

    // =========================================
    // MANUAL USER TRIAL
    // =========================================

    @Column(nullable = false)
    private Boolean trial;

    // =========================================
    // VALIDITY
    // =========================================

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate expiryDate;

    // =========================================
    // AUDIT
    // =========================================

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {

        LocalDateTime now = LocalDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;

        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        if (paymentDone == null) {
            paymentDone = false;
        }

        if (trial == null) {
            trial = false;
        }
    }

    @PreUpdate
    public void preUpdate() {

        updatedAt = LocalDateTime.now();
    }
}