package com.siva.storybot.repository;

import com.siva.storybot.entity.RewardTrial;
import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.RewardTrialStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RewardTrialRepository extends JpaRepository<RewardTrial, Long> {

    Optional<RewardTrial> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RewardTrial r where r.token = :token")
    Optional<RewardTrial> findByTokenForUpdate(@Param("token") String token);

    List<RewardTrial> findAllByTelegramUserAndStatusOrderByCreatedAtDesc(TelegramUser telegramUser, RewardTrialStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RewardTrial r where r.telegramUser = :user and r.status = :status order by r.createdAt desc")
    List<RewardTrial> findAllByTelegramUserAndStatusForUpdate(
            @Param("user") TelegramUser user,
            @Param("status") RewardTrialStatus status);

    @Modifying
    @Query("update RewardTrial r set r.selectedStory = null where r.selectedStory = :story")
    int clearSelectedStory(@Param("story") Story story);

    Optional<RewardTrial> findTopByTelegramUserAndStatusOrderByCreatedAtDesc(TelegramUser telegramUser, RewardTrialStatus status);

    List<RewardTrial> findByStatusAndExpiresAtLessThanEqual(RewardTrialStatus status, LocalDateTime expiresAt);

    List<RewardTrial> findByStatusAndLinkExpiresAtLessThanEqual(RewardTrialStatus status, LocalDateTime linkExpiresAt);
}
