package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.entity.RewardTrial;
import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.RewardLinkProviderType;
import com.siva.storybot.enums.RewardTrialStatus;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.RewardTrialRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewardTrialService {

    public static final int REWARD_ACCESS_MINUTES = 60;

    public static final int REWARD_LINK_MINUTES = 20;

    public static final String START_PAYLOAD_PREFIX = "rw_";

    private final RewardTrialRepository rewardTrialRepository;

    private final RewardLinkProviderResolver providerResolver;

    private final TelegramConfig telegramConfig;

    @Value("${reward.trial.enabled:false}")
    private boolean enabled;

    @Value("${reward.trial.provider-approved:false}")
    private boolean providerApproved;

    public boolean isEnabled() {

        return enabled
                && providerApproved
                && providerResolver.hasConfiguredProvider()
                && !getBotUsername().isBlank();
    }

    /**
     * Existing token activation must NOT depend on the currently
     * selected short-link provider or its API availability.
     * <p>
     * Example:
     * <p>
     * User created link using SHRTFLY.
     * Admin switches to LITESHORT.
     * User finishes old valid SHRTFLY link.
     * <p>
     * The valid token must still activate.
     */
    private boolean isActivationEnabled() {

        return enabled && providerApproved && !getBotUsername().isBlank();
    }

    // =========================================================
    // FEATURE STATUS
    // =========================================================

    @Transactional
    public synchronized RewardLinkResult createOrReuseRewardLink(TelegramUser user) {

        validateLinkCreationFeature();

        if (user == null) {
            throw new IllegalArgumentException("Telegram user is required");
        }

        if (user.getRole() != UserRole.USER) {
            throw new IllegalStateException("Reward trial is available only for normal users");
        }

        LocalDateTime now = LocalDateTime.now();

        // -----------------------------------------------------
        // Existing ACTIVE reward
        // -----------------------------------------------------

        Optional<RewardTrial> active = getActiveRewardTrial(user);

        if (active.isPresent()) {
            throw new IllegalStateException(
                    "Reward access already active until " + active.get().getExpiresAt());
        }

        // -----------------------------------------------------
        // Reuse ANY valid pending link, regardless of provider.
        //
        // This is important with round-robin. A user pressing the
        // reward button repeatedly must not create LITESHORT and
        // SHRTFLY pending links at the same time.
        // -----------------------------------------------------

        List<RewardTrial> pendingRows =
                rewardTrialRepository.findAllByTelegramUserAndStatusOrderByCreatedAtDesc(
                        user, RewardTrialStatus.PENDING);

        RewardTrial reusablePending = null;

        for (RewardTrial pending : pendingRows) {

            boolean linkStillValid = pending.getLinkExpiresAt() != null
                    && now.isBefore(pending.getLinkExpiresAt());

            boolean shortUrlAvailable = pending.getShortUrl() != null
                    && !pending.getShortUrl().isBlank();

            if (linkStillValid && shortUrlAvailable && reusablePending == null) {
                reusablePending = pending;
                continue;
            }

            // Expire invalid rows and defensive duplicate valid pending rows.
            pending.setStatus(RewardTrialStatus.EXPIRED);
            pending.setUpdatedAt(now);
            rewardTrialRepository.save(pending);
        }

        if (reusablePending != null) {
            log.info(
                    "Reusing pending reward link provider={} telegramId={} rewardTrialId={} linkExpiresAt={}",
                    reusablePending.getProvider(),
                    user.getTelegramId(),
                    reusablePending.getId(),
                    reusablePending.getLinkExpiresAt()
            );

            return new RewardLinkResult(
                    reusablePending.getShortUrl(),
                    reusablePending.getLinkExpiresAt()
            );
        }

        // No reusable pending row: create a brand-new one.
        return createFreshRewardLink(user, now).result();
    }

    /**
     * User explicitly wants to switch the story attached to the current
     * 1-hour reward.
     *
     * Flow:
     * 1) Current ACTIVE reward must exist and already have a selected story.
     * 2) Create a brand-new PENDING short link first.
     * 3) Only after the new short link is successfully persisted, expire the
     *    old ACTIVE reward and invalidate every older PENDING link.
     * 4) The new reward row starts with selectedStory=null.
     * 5) User must complete the new short-link flow; successful Telegram claim
     *    activates a fresh 60-minute reward and the next selected story becomes
     *    the new reward story.
     *
     * If provider/link generation fails, the transaction rolls back and the
     * existing ACTIVE reward stays usable.
     */
    @Transactional
    public synchronized RewardLinkResult createStoryChangeRewardLink(TelegramUser user) {

        validateLinkCreationFeature();

        if (user == null) {
            throw new IllegalArgumentException("Telegram user is required");
        }

        if (user.getRole() != UserRole.USER) {
            throw new IllegalStateException("Reward trial is available only for normal users");
        }

        LocalDateTime now = LocalDateTime.now();

        List<RewardTrial> activeRows = rewardTrialRepository
                .findAllByTelegramUserAndStatusForUpdate(user, RewardTrialStatus.ACTIVE);

        RewardTrial currentActive = null;

        for (RewardTrial row : activeRows) {
            boolean stillActive = row.getExpiresAt() != null && now.isBefore(row.getExpiresAt());

            if (stillActive && currentActive == null) {
                currentActive = row;
            }
        }

        if (currentActive == null) {
            // Idempotency / double-click safety: if the first confirmation
            // already ended the old reward and created a replacement PENDING
            // link, return that same valid link instead of creating duplicates.
            List<RewardTrial> pendingRows = rewardTrialRepository
                    .findAllByTelegramUserAndStatusForUpdate(user, RewardTrialStatus.PENDING);

            for (RewardTrial pending : pendingRows) {
                boolean valid = pending.getLinkExpiresAt() != null
                        && now.isBefore(pending.getLinkExpiresAt())
                        && pending.getShortUrl() != null
                        && !pending.getShortUrl().isBlank();

                if (valid) {
                    return new RewardLinkResult(pending.getShortUrl(), pending.getLinkExpiresAt());
                }
            }

            // The old reward may have expired naturally while the confirmation
            // dialog was open. In that case simply issue a normal fresh link.
            return createFreshRewardLink(user, now).result();
        }

        if (currentActive.getSelectedStory() == null) {
            throw new IllegalStateException("No story is locked yet. Choose a story from the current reward instead.");
        }

        // Create replacement first. Provider/API failure must not destroy the
        // user's current reward. The transaction will roll back on failure.
        CreatedRewardLink replacement = createFreshRewardLink(user, now);

        LocalDateTime changedAt = LocalDateTime.now();

        // New link exists: now the old reward is intentionally ended.
        for (RewardTrial row : activeRows) {
            row.setStatus(RewardTrialStatus.EXPIRED);
            row.setUpdatedAt(changedAt);
        }

        if (!activeRows.isEmpty()) {
            rewardTrialRepository.saveAll(activeRows);
        }

        // Keep only the replacement PENDING row. Any previous pending token
        // must never be able to activate after a story-change request.
        List<RewardTrial> pendingRows = rewardTrialRepository
                .findAllByTelegramUserAndStatusForUpdate(user, RewardTrialStatus.PENDING);

        for (RewardTrial pending : pendingRows) {
            if (Objects.equals(pending.getId(), replacement.rewardTrial().getId())) {
                continue;
            }

            pending.setStatus(RewardTrialStatus.EXPIRED);
            pending.setUpdatedAt(changedAt);
        }

        rewardTrialRepository.saveAll(pendingRows);

        log.info(
                "Reward story-change link created telegramId={} oldRewardTrialId={} oldStoryId={} newRewardTrialId={} linkExpiresAt={}",
                user.getTelegramId(),
                currentActive.getId(),
                currentActive.getSelectedStory() != null ? currentActive.getSelectedStory().getId() : null,
                replacement.rewardTrial().getId(),
                replacement.result().linkExpiresAt()
        );

        return replacement.result();
    }

    /**
     * Creates one fresh PENDING reward row and one external short URL.
     * No ACTIVE-reward check is performed here; callers decide whether they are
     * creating a normal reward or replacing an existing reward.
     */
    private CreatedRewardLink createFreshRewardLink(TelegramUser user, LocalDateTime now) {

        RewardLinkProviderType initialProviderType = providerResolver.getDefaultProviderType();

        String token = newToken();
        LocalDateTime linkExpiresAt = now.plusMinutes(REWARD_LINK_MINUTES);

        RewardTrial rewardTrial = RewardTrial.builder()
                .telegramUser(user)
                .token(token)
                .status(RewardTrialStatus.PENDING)
                .provider(initialProviderType.getDatabaseValue())
                .createdAt(now)
                .updatedAt(now)
                .linkExpiresAt(linkExpiresAt)
                .build();

        rewardTrial = rewardTrialRepository.save(rewardTrial);

        String telegramDestination = buildTelegramClaimUrl(token);

        RewardLinkProviderResolver.ShortenResult result;

        try {
            result = providerResolver.shortenWithFailover(telegramDestination);
        } catch (RuntimeException ex) {
            rewardTrial.setStatus(RewardTrialStatus.FAILED);
            rewardTrial.setUpdatedAt(LocalDateTime.now());
            rewardTrialRepository.save(rewardTrial);

            log.error(
                    "Unable to create reward link using all configured provider/API-key combinations telegramId={} rewardTrialId={}",
                    user.getTelegramId(),
                    rewardTrial.getId(),
                    ex
            );

            throw ex;
        }

        rewardTrial.setProvider(result.providerType().getDatabaseValue());
        rewardTrial.setShortUrl(result.shortUrl());
        rewardTrial.setUpdatedAt(LocalDateTime.now());
        rewardTrialRepository.save(rewardTrial);

        log.info(
                "Direct Telegram reward link created provider={} apiKeySlot={}/{} telegramId={} rewardTrialId={} linkExpiresAt={}",
                result.providerType(),
                result.apiKeySlotNumber(),
                result.providerApiKeyCount(),
                user.getTelegramId(),
                rewardTrial.getId(),
                linkExpiresAt
        );

        RewardLinkResult rewardLinkResult = new RewardLinkResult(result.shortUrl(), linkExpiresAt);
        return new CreatedRewardLink(rewardTrial, rewardLinkResult);
    }

    @Transactional
    public synchronized ActivationResult activateByStartPayload(String startPayload, TelegramUser claimant) {

        if (!isActivationEnabled()) {

            return new ActivationResult(false, false, "Reward access is currently disabled.", null);
        }

        if (claimant == null || claimant.getTelegramId() == null) {

            return new ActivationResult(false, false, "Telegram user could not be verified.", null);
        }

        if (claimant.getRole() != UserRole.USER) {

            return new ActivationResult(false, false, "OWNER / ADMIN accounts do not require reward-trial access.", null);
        }

        String token = tokenFromStartPayload(startPayload);

        if (token.isBlank()) {

            return new ActivationResult(false, false, "Invalid reward link.", null);
        }

        Optional<RewardTrial> optional = rewardTrialRepository.findByTokenForUpdate(token);

        if (optional.isEmpty()) {

            return new ActivationResult(false, false, "Reward link was not found. Request a new link in the bot.", null);
        }

        RewardTrial rewardTrial = optional.get();

        LocalDateTime now = LocalDateTime.now();

        TelegramUser owner = rewardTrial.getTelegramUser();

        if (owner == null || owner.getTelegramId() == null) {

            rewardTrial.setStatus(RewardTrialStatus.EXPIRED);

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(rewardTrial);

            return new ActivationResult(false, false, "Reward owner could not be verified.", null);
        }

        // -----------------------------------------------------
        // TOKEN USER BINDING
        // -----------------------------------------------------

        if (!Objects.equals(owner.getTelegramId(), claimant.getTelegramId())) {

            log.warn("Reward token owner mismatch " + "rewardTrialId={} " + "expectedTelegramId={} " + "claimantTelegramId={}", rewardTrial.getId(), owner.getTelegramId(), claimant.getTelegramId());

            /*
             * Do NOT consume/expire token.
             * Legitimate owner can still use it.
             */
            return new ActivationResult(false, false, "This reward link belongs to a different Telegram account.", null);
        }

        // -----------------------------------------------------
        // VALID DIRECT PROVIDER
        //
        // BOTH are accepted:
        //
        // SHRTFLY_TELEGRAM
        // LITESHORT_TELEGRAM
        //
        // So switching provider never breaks an already-created
        // valid link.
        // -----------------------------------------------------

        String savedProvider = rewardTrial.getProvider() == null ? "" : rewardTrial.getProvider().trim();

        Optional<RewardLinkProviderType> rewardProvider = RewardLinkProviderType.fromDatabaseValue(savedProvider);

        if (rewardProvider.isEmpty()) {

            return new ActivationResult(false, false, "This is an old reward link. Please request a new one in Telegram.", null);
        }

        // -----------------------------------------------------
        // Already active
        // -----------------------------------------------------

        if (rewardTrial.getStatus() == RewardTrialStatus.ACTIVE) {

            if (rewardTrial.getExpiresAt() != null && now.isBefore(rewardTrial.getExpiresAt())) {

                return new ActivationResult(true, true, "Your 1-hour access is already active.", rewardTrial.getExpiresAt());
            }

            rewardTrial.setStatus(RewardTrialStatus.EXPIRED);

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(rewardTrial);

            return new ActivationResult(false, false, "This reward has expired. Request a new one in Telegram.", null);
        }

        // -----------------------------------------------------
        // Only pending token can activate
        // -----------------------------------------------------

        if (rewardTrial.getStatus() != RewardTrialStatus.PENDING) {

            return new ActivationResult(false, false, "This reward link has already been used or is no longer valid.", null);
        }

        // -----------------------------------------------------
        // Claim link expiry
        // -----------------------------------------------------

        if (rewardTrial.getLinkExpiresAt() == null || !now.isBefore(rewardTrial.getLinkExpiresAt())) {

            rewardTrial.setStatus(RewardTrialStatus.EXPIRED);

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(rewardTrial);

            return new ActivationResult(false, false, "This reward link expired. Request a new one in Telegram.", null);
        }

        // -----------------------------------------------------
        // Never stack active rewards
        // -----------------------------------------------------

        Optional<RewardTrial> existingActive = getActiveRewardTrial(claimant);

        if (existingActive.isPresent()) {

            RewardTrial active = existingActive.get();

            if (!Objects.equals(active.getId(), rewardTrial.getId())) {

                rewardTrial.setStatus(RewardTrialStatus.EXPIRED);

                rewardTrial.setUpdatedAt(now);

                rewardTrialRepository.save(rewardTrial);
            }

            return new ActivationResult(true, true, "Your 1-hour access is already active.", active.getExpiresAt());
        }

        // -----------------------------------------------------
        // Activate exactly 60 minutes
        // -----------------------------------------------------

        LocalDateTime accessExpiresAt = now.plusMinutes(REWARD_ACCESS_MINUTES);

        rewardTrial.setStatus(RewardTrialStatus.ACTIVE);

        rewardTrial.setActivatedAt(now);

        rewardTrial.setExpiresAt(accessExpiresAt);

        rewardTrial.setUpdatedAt(now);

        rewardTrialRepository.save(rewardTrial);

        log.info("Direct Telegram reward activated " + "provider={} " + "telegramId={} " + "rewardTrialId={} " + "expiresAt={}", rewardProvider.get(), claimant.getTelegramId(), rewardTrial.getId(), accessExpiresAt);

        return new ActivationResult(true, false, "1-hour free access activated successfully.", accessExpiresAt);
    }

    // =========================================================
    // CREATE / REUSE
    // =========================================================

    @Transactional
    public Optional<RewardTrial> getActiveRewardTrial(TelegramUser user) {

        if (user == null) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();

        List<RewardTrial> activeRows = rewardTrialRepository.findAllByTelegramUserAndStatusOrderByCreatedAtDesc(user, RewardTrialStatus.ACTIVE);

        RewardTrial selected = null;

        for (RewardTrial row : activeRows) {

            if (row.getExpiresAt() == null || !now.isBefore(row.getExpiresAt())) {

                row.setStatus(RewardTrialStatus.EXPIRED);

                row.setUpdatedAt(now);

                rewardTrialRepository.save(row);

                continue;
            }

            if (selected == null) {

                selected = row;

            } else {

                /*
                 * Defensive duplicate ACTIVE cleanup.
                 */
                row.setStatus(RewardTrialStatus.EXPIRED);

                row.setUpdatedAt(now);

                rewardTrialRepository.save(row);
            }
        }

        return Optional.ofNullable(selected);
    }

    // =========================================================
    // TELEGRAM CLAIM
    // =========================================================

    public boolean hasActiveRewardTrial(TelegramUser user) {

        return getActiveRewardTrial(user).isPresent();
    }

    /**
     * Bind the first story selected during the currently active reward.
     * This is reward-scoped access only; it does not create a permanent
     * UserStoryAccess mapping.
     */
    @Transactional
    public RewardStorySelection selectStoryForActiveReward(TelegramUser user, Story story) {

        if (user == null || story == null || user.getRole() != UserRole.USER) {
            return new RewardStorySelection(false, false, false, null);
        }

        LocalDateTime now = LocalDateTime.now();
        List<RewardTrial> activeRows = rewardTrialRepository
                .findAllByTelegramUserAndStatusForUpdate(user, RewardTrialStatus.ACTIVE);

        RewardTrial active = null;

        for (RewardTrial row : activeRows) {
            if (row.getExpiresAt() == null || !now.isBefore(row.getExpiresAt())) {
                row.setStatus(RewardTrialStatus.EXPIRED);
                row.setUpdatedAt(now);
                rewardTrialRepository.save(row);
                continue;
            }

            if (active == null) {
                active = row;
            } else {
                row.setStatus(RewardTrialStatus.EXPIRED);
                row.setUpdatedAt(now);
                rewardTrialRepository.save(row);
            }
        }

        if (active == null) {
            return new RewardStorySelection(false, false, false, null);
        }

        Story selected = active.getSelectedStory();

        if (selected == null) {
            active.setSelectedStory(story);
            active.setUpdatedAt(now);
            rewardTrialRepository.save(active);

            log.info(
                    "Reward story selected telegramId={} rewardTrialId={} storyId={}",
                    user.getTelegramId(), active.getId(), story.getId());

            return new RewardStorySelection(true, true, true, story);
        }

        boolean sameStory = Objects.equals(selected.getId(), story.getId());
        return new RewardStorySelection(true, false, sameStory, selected);
    }

    public boolean hasSelectedStoryAccess(TelegramUser user, Story story) {

        if (user == null || story == null) {
            return false;
        }

        return getActiveRewardTrial(user)
                .map(RewardTrial::getSelectedStory)
                .filter(Objects::nonNull)
                .map(selected -> Objects.equals(selected.getId(), story.getId()))
                .orElse(false);
    }

    public Optional<Story> getSelectedRewardStory(TelegramUser user) {
        return getActiveRewardTrial(user)
                .map(RewardTrial::getSelectedStory)
                .filter(Objects::nonNull);
    }

    // =========================================================
    // ACTIVE REWARD
    // =========================================================

    public Optional<LocalDateTime> getRewardExpiry(TelegramUser user) {

        return getActiveRewardTrial(user).map(RewardTrial::getExpiresAt);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireRewardRows() {

        LocalDateTime now = LocalDateTime.now();

        List<RewardTrial> activeExpired = rewardTrialRepository.findByStatusAndExpiresAtLessThanEqual(RewardTrialStatus.ACTIVE, now);

        for (RewardTrial row : activeExpired) {

            row.setStatus(RewardTrialStatus.EXPIRED);

            row.setUpdatedAt(now);
        }

        if (!activeExpired.isEmpty()) {

            rewardTrialRepository.saveAll(activeExpired);
        }

        List<RewardTrial> pendingExpired = rewardTrialRepository.findByStatusAndLinkExpiresAtLessThanEqual(RewardTrialStatus.PENDING, now);

        for (RewardTrial row : pendingExpired) {

            row.setStatus(RewardTrialStatus.EXPIRED);

            row.setUpdatedAt(now);
        }

        if (!pendingExpired.isEmpty()) {

            rewardTrialRepository.saveAll(pendingExpired);
        }
    }

    public boolean isRewardStartPayload(String startPayload) {

        if (startPayload == null) {
            return false;
        }

        return startPayload.startsWith(START_PAYLOAD_PREFIX) && !tokenFromStartPayload(startPayload).isBlank();
    }

    // =========================================================
    // AUTO EXPIRY
    // =========================================================

    private void validateLinkCreationFeature() {

        if (!enabled) {

            throw new IllegalStateException("Reward trial feature is disabled");
        }

        if (!providerApproved) {

            throw new IllegalStateException("Reward provider approval is required before enabling incentivized traffic");
        }

        if (!providerResolver.hasConfiguredProvider()) {

            throw new IllegalStateException(
                    "No configured reward provider/API-key combinations are available");
        }

        if (getBotUsername().isBlank()) {

            throw new IllegalStateException("telegram.bot.username is not configured");
        }
    }

    // =========================================================
    // START PAYLOAD
    // =========================================================

    private String buildTelegramClaimUrl(String token) {

        String payload = START_PAYLOAD_PREFIX + token;

        if (payload.length() > 64) {

            throw new IllegalStateException("Reward start payload exceeds Telegram limit");
        }

        return "https://t.me/" + getBotUsername() + "?start=" + payload;
    }

    // =========================================================
    // VALIDATION
    // =========================================================

    private String tokenFromStartPayload(String startPayload) {

        if (startPayload == null) {
            return "";
        }

        String payload = startPayload.trim();

        if (!payload.startsWith(START_PAYLOAD_PREFIX)) {

            return "";
        }

        String token = payload.substring(START_PAYLOAD_PREFIX.length()).trim();

        if (!token.matches("[a-f0-9]{32}")) {

            return "";
        }

        return token;
    }

    // =========================================================
    // TELEGRAM DESTINATION
    // =========================================================

    private String newToken() {

        return UUID.randomUUID().toString().replace("-", "").toLowerCase();
    }

    private String getBotUsername() {

        String value = telegramConfig.getBotUsername();

        if (value == null) {
            return "";
        }

        return value.replace("@", "").trim();
    }

    public record RewardLinkResult(String shortUrl, LocalDateTime linkExpiresAt) {
    }

    private record CreatedRewardLink(RewardTrial rewardTrial, RewardLinkResult result) {
    }

    public record ActivationResult(boolean success, boolean alreadyActive, String message,
                                   LocalDateTime accessExpiresAt) {
    }

    public record RewardStorySelection(
            boolean rewardActive,
            boolean selectedNow,
            boolean selectedStoryMatches,
            Story selectedStory) {
    }
}
