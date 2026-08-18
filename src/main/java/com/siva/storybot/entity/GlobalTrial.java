package com.siva.storybot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "global_trials", indexes = {@Index(name = "idx_global_trial_enabled", columnList = "enabled")})
public class GlobalTrial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =========================================
    // GLOBAL TRIAL STATUS
    // =========================================

    @Column(nullable = false)
    private Boolean enabled;

    // =========================================
    // DAYS PER USER
    // =========================================

    @Column(nullable = false)
    private Integer trialDays;

    // =========================================
    // CAMPAIGN START / END
    // =========================================

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    // =========================================
    // OWNER WHO ENABLED IT
    // =========================================

    private Long enabledBy;

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

        if (enabled == null) {
            enabled = false;
        }

        if (trialDays == null) {
            trialDays = 7;
        }

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {

        updatedAt = LocalDateTime.now();
    }
}