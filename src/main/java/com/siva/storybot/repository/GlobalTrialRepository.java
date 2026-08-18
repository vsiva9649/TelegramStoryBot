package com.siva.storybot.repository;

import com.siva.storybot.entity.GlobalTrial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlobalTrialRepository
        extends JpaRepository<GlobalTrial, Long> {

    // =========================================
    // GET LATEST GLOBAL TRIAL CONFIG
    // =========================================

    Optional<GlobalTrial> findTopByOrderByIdDesc();
}