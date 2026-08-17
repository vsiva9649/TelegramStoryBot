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
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telegram_user_id", nullable = false, unique = true)
    private TelegramUser telegramUser;

    @Enumerated(EnumType.STRING)
    private SubscriptionPlan plan; //    FREE, MONTHLY, YEARLY, LIFETIME

    @Enumerated(EnumType.STRING)
    private BillingType billingType; //    MONTHLY, YEARLY, LIFETIME

    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    // Payment Completed ?
    private Boolean paymentDone;

    // SUBSCRIPTION STATUS
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status; //    ACTIVE, EXPIRED, CANCELLED

    // Trial User
    private Boolean trial;

    private LocalDate startDate;

    private LocalDate expiryDate;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}