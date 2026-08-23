package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.entity.RewardTrial;
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

        return enabled && providerApproved && providerResolver.isCurrentProviderConfigured() && !getBotUsername().isBlank();
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

        RewardLinkProvider linkProvider = providerResolver.getCurrentProvider();

        RewardLinkProviderType providerType = linkProvider.getProviderType();

        String providerDatabaseValue = providerType.getDatabaseValue();

        LocalDateTime now = LocalDateTime.now();

        // -----------------------------------------------------
        // Existing ACTIVE reward
        // -----------------------------------------------------

        Optional<RewardTrial> active = getActiveRewardTrial(user);

        if (active.isPresent()) {

            throw new IllegalStateException("Reward access already active until " + active.get().getExpiresAt());
        }

        // -----------------------------------------------------
        // Check ALL pending rows
        //
        // Important during provider switching:
        //
        // SHRTFLY pending link must NOT be destroyed simply
        // because current provider becomes LITESHORT.
        // -----------------------------------------------------

        List<RewardTrial> pendingRows = rewardTrialRepository.findAllByTelegramUserAndStatusOrderByCreatedAtDesc(user, RewardTrialStatus.PENDING);

        for (RewardTrial pending : pendingRows) {

            boolean linkStillValid = pending.getLinkExpiresAt() != null && now.isBefore(pending.getLinkExpiresAt());

            boolean shortUrlAvailable = pending.getShortUrl() != null && !pending.getShortUrl().isBlank();

            String rowProvider = pending.getProvider() == null ? "" : pending.getProvider().trim();

            boolean sameProvider = providerDatabaseValue.equalsIgnoreCase(rowProvider);

            /*
             * Reuse only a pending link created by the
             * CURRENT provider.
             */
            if (sameProvider && linkStillValid && shortUrlAvailable) {

                log.info("Reusing pending reward link " + "provider={} " + "telegramId={} " + "rewardTrialId={} " + "linkExpiresAt={}", providerType, user.getTelegramId(), pending.getId(), pending.getLinkExpiresAt());

                return new RewardLinkResult(pending.getShortUrl(), pending.getLinkExpiresAt());
            }

            /*
             * Any genuinely expired/invalid pending row
             * can be expired.
             *
             * But a valid row belonging to ANOTHER provider
             * is left untouched so its existing URL can
             * still be completed by the user.
             */
            if (!linkStillValid || !shortUrlAvailable) {

                pending.setStatus(RewardTrialStatus.EXPIRED);

                pending.setUpdatedAt(now);

                rewardTrialRepository.save(pending);
            }
        }

        // -----------------------------------------------------
        // Generate new token
        // -----------------------------------------------------

        String token = newToken();

        LocalDateTime linkExpiresAt = now.plusMinutes(REWARD_LINK_MINUTES);

        RewardTrial rewardTrial = RewardTrial.builder().telegramUser(user).token(token).status(RewardTrialStatus.PENDING).provider(providerDatabaseValue).createdAt(now).updatedAt(now).linkExpiresAt(linkExpiresAt).build();

        rewardTrial = rewardTrialRepository.save(rewardTrial);

        // -----------------------------------------------------
        // Provider generates monetized short URL
        // -----------------------------------------------------

        try {

            String telegramDestination = buildTelegramClaimUrl(token);

            log.info("Creating reward link " + "provider={} " + "telegramId={} " + "rewardTrialId={} " + "destinationBot={}", providerType, user.getTelegramId(), rewardTrial.getId(), getBotUsername());

            String shortUrl = linkProvider.shorten(telegramDestination);

            if (shortUrl == null || shortUrl.isBlank()) {

                throw new IllegalStateException(providerType + " returned an empty short URL");
            }

            rewardTrial.setShortUrl(shortUrl);

            rewardTrial.setUpdatedAt(LocalDateTime.now());

            rewardTrialRepository.save(rewardTrial);

            log.info("Direct Telegram reward link created " + "provider={} " + "telegramId={} " + "rewardTrialId={} " + "linkExpiresAt={}", providerType, user.getTelegramId(), rewardTrial.getId(), linkExpiresAt);

            return new RewardLinkResult(shortUrl, linkExpiresAt);

        } catch (RuntimeException ex) {

            rewardTrial.setStatus(RewardTrialStatus.FAILED);

            rewardTrial.setUpdatedAt(LocalDateTime.now());

            rewardTrialRepository.save(rewardTrial);

            log.error("Unable to create reward link " + "provider={} " + "telegramId={} " + "rewardTrialId={}", providerType, user.getTelegramId(), rewardTrial.getId(), ex);

            throw ex;
        }
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

            return new ActivationResult(false, false, "OWNER / ADMIN accounts already have unrestricted access.", null);
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

        RewardLinkProvider provider = providerResolver.getCurrentProvider();

        if (!provider.isConfigured()) {

            throw new IllegalStateException(provider.getProviderType() + " reward provider is not configured");
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

    public record ActivationResult(boolean success, boolean alreadyActive, String message,
                                   LocalDateTime accessExpiresAt) {
    }
}