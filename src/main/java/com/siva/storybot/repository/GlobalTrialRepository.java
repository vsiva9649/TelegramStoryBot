package com.siva.storybot.repository;

import com.siva.storybot.entity.GlobalTrial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalTrialRepository extends JpaRepository<GlobalTrial, Long> {

    // =========================================
    // GET LATEST GLOBAL TRIAL CONFIG
    // =========================================

    Optional<GlobalTrial> findTopByOrderByIdDesc();

    // Reuse an existing campaign row when the same end date is
    // activated again, even if another campaign was configured later.
    Optional<GlobalTrial> findTopByEndDateOrderByIdDesc(java.time.LocalDateTime endDate);

    // =========================================
    // GET LATEST ACTIVE GLOBAL TRIAL
    // =========================================

    Optional<GlobalTrial> findTopByEnabledTrueOrderByIdDesc();

    // =========================================
    // GET ALL ACTIVE ROWS
    //
    // Used to clean up legacy duplicate active
    // rows and to guarantee OFF really stops
    // every global trial record.
    // =========================================

    List<GlobalTrial> findAllByEnabledTrueOrderByIdDesc();
}
