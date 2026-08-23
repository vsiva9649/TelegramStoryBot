package com.siva.storybot.service;

import com.siva.storybot.config.TelegramConfig;
import com.siva.storybot.entity.RewardTrial;
import com.siva.storybot.entity.TelegramUser;
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

    /**
     * Actual reward access duration.
     */
    public static final int REWARD_ACCESS_MINUTES = 60;

    /**
     * User must finish the ShrtFly flow and return
     * to Telegram within this time.
     */
    public static final int REWARD_LINK_MINUTES = 20;

    /**
     * Telegram /start payload:
     *
     * /start rw_<TOKEN>
     */
    public static final String START_PAYLOAD_PREFIX = "rw_";

    /**
     * Used to distinguish the current Telegram-direct
     * implementation from the previous web/ngrok flow.
     */
    private static final String PROVIDER =
            "SHRTFLY_TELEGRAM";

    private final RewardTrialRepository rewardTrialRepository;

    private final ShrtFlyService shrtFlyService;

    private final TelegramConfig telegramConfig;

    @Value("${reward.trial.enabled:false}")
    private boolean enabled;

    @Value("${reward.trial.provider-approved:false}")
    private boolean providerApproved;

    // =========================================================
    // RESPONSE RECORDS
    // =========================================================

    public record RewardLinkResult(
            String shortUrl,
            LocalDateTime linkExpiresAt
    ) {
    }

    public record ActivationResult(
            boolean success,
            boolean alreadyActive,
            String message,
            LocalDateTime accessExpiresAt
    ) {
    }

    // =========================================================
    // FEATURE STATUS
    // =========================================================

    /**
     * Determines whether the complete reward-link creation
     * feature is available.
     *
     * ShrtFly configuration is required here because this
     * method is normally used before creating a new short URL.
     */
    public boolean isEnabled() {

        return enabled
                && providerApproved
                && shrtFlyService.isConfigured()
                && !getBotUsername().isBlank();
    }

    /**
     * Activation itself does NOT require calling ShrtFly.
     *
     * Example:
     *
     * 1. User already completed ShrtFly.
     * 2. ShrtFly redirects to Telegram.
     * 3. API key temporarily becomes unavailable.
     *
     * We should still honor the previously-generated,
     * valid one-time token.
     */
    private boolean isActivationEnabled() {

        return enabled
                && providerApproved
                && !getBotUsername().isBlank();
    }

    // =========================================================
    // CREATE / REUSE REWARD LINK
    // =========================================================

    @Transactional
    public synchronized RewardLinkResult createOrReuseRewardLink(
            TelegramUser user
    ) {

        validateLinkCreationFeature();

        if (user == null) {
            throw new IllegalArgumentException(
                    "Telegram user is required"
            );
        }

        if (user.getRole() != UserRole.USER) {
            throw new IllegalStateException(
                    "Reward trial is available only for normal users"
            );
        }

        LocalDateTime now =
                LocalDateTime.now();

        // -----------------------------------------------------
        // Do not create another reward if access already active
        // -----------------------------------------------------

        Optional<RewardTrial> active =
                getActiveRewardTrial(user);

        if (active.isPresent()) {

            throw new IllegalStateException(
                    "Reward access already active until "
                            + active.get().getExpiresAt()
            );
        }

        // -----------------------------------------------------
        // Reuse existing valid PENDING link
        // -----------------------------------------------------

        Optional<RewardTrial> pendingOptional =
                rewardTrialRepository
                        .findTopByTelegramUserAndStatusOrderByCreatedAtDesc(
                                user,
                                RewardTrialStatus.PENDING
                        );

        if (pendingOptional.isPresent()) {

            RewardTrial pending =
                    pendingOptional.get();

            String provider =
                    pending.getProvider() == null
                            ? ""
                            : pending.getProvider().trim();

            boolean currentDirectFlow =
                    PROVIDER.equalsIgnoreCase(provider);

            boolean linkStillValid =
                    pending.getLinkExpiresAt() != null
                            && now.isBefore(
                            pending.getLinkExpiresAt()
                    );

            boolean shortUrlAvailable =
                    pending.getShortUrl() != null
                            && !pending.getShortUrl()
                            .isBlank();

            if (currentDirectFlow
                    && linkStillValid
                    && shortUrlAvailable) {

                log.info(
                        "Reusing pending reward link "
                                + "telegramId={} "
                                + "rewardTrialId={} "
                                + "linkExpiresAt={}",
                        user.getTelegramId(),
                        pending.getId(),
                        pending.getLinkExpiresAt()
                );

                return new RewardLinkResult(
                        pending.getShortUrl(),
                        pending.getLinkExpiresAt()
                );
            }

            // Old web/ngrok flow or stale row.
            pending.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            pending.setUpdatedAt(now);

            rewardTrialRepository.save(pending);
        }

        // -----------------------------------------------------
        // Generate new token
        // -----------------------------------------------------

        String token =
                newToken();

        LocalDateTime linkExpiresAt =
                now.plusMinutes(
                        REWARD_LINK_MINUTES
                );

        RewardTrial rewardTrial =
                RewardTrial.builder()
                        .telegramUser(user)
                        .token(token)
                        .status(
                                RewardTrialStatus.PENDING
                        )
                        .provider(PROVIDER)
                        .createdAt(now)
                        .updatedAt(now)
                        .linkExpiresAt(
                                linkExpiresAt
                        )
                        .build();

        rewardTrial =
                rewardTrialRepository.save(
                        rewardTrial
                );

        // -----------------------------------------------------
        // Create Telegram destination and shorten it
        // -----------------------------------------------------

        try {

            String telegramDestination =
                    buildTelegramClaimUrl(
                            token
                    );

            log.info(
                    "Creating ShrtFly reward link "
                            + "telegramId={} "
                            + "rewardTrialId={} "
                            + "destinationBot={}",
                    user.getTelegramId(),
                    rewardTrial.getId(),
                    getBotUsername()
            );

            String shortUrl =
                    shrtFlyService.shorten(
                            telegramDestination
                    );

            if (shortUrl == null
                    || shortUrl.isBlank()) {

                throw new IllegalStateException(
                        "ShrtFly returned an empty short URL"
                );
            }

            rewardTrial.setShortUrl(
                    shortUrl
            );

            rewardTrial.setUpdatedAt(
                    LocalDateTime.now()
            );

            rewardTrialRepository.save(
                    rewardTrial
            );

            log.info(
                    "Direct Telegram reward link created "
                            + "telegramId={} "
                            + "rewardTrialId={} "
                            + "linkExpiresAt={}",
                    user.getTelegramId(),
                    rewardTrial.getId(),
                    linkExpiresAt
            );

            return new RewardLinkResult(
                    shortUrl,
                    linkExpiresAt
            );

        } catch (RuntimeException ex) {

            rewardTrial.setStatus(
                    RewardTrialStatus.FAILED
            );

            rewardTrial.setUpdatedAt(
                    LocalDateTime.now()
            );

            rewardTrialRepository.save(
                    rewardTrial
            );

            log.error(
                    "Unable to create ShrtFly reward link "
                            + "telegramId={} "
                            + "rewardTrialId={}",
                    user.getTelegramId(),
                    rewardTrial.getId(),
                    ex
            );

            throw ex;
        }
    }

    // =========================================================
    // ACTIVATE USING TELEGRAM /start PAYLOAD
    // =========================================================

    /**
     * Called when Telegram receives:
     *
     * /start rw_<TOKEN>
     *
     * The claimant MUST be the same Telegram account
     * that originally requested this reward link.
     */
    @Transactional
    public synchronized ActivationResult activateByStartPayload(
            String startPayload,
            TelegramUser claimant
    ) {

        // Existing valid token should still work even if
        // ShrtFly API itself is temporarily unavailable.
        if (!isActivationEnabled()) {

            return new ActivationResult(
                    false,
                    false,
                    "Reward access is currently disabled.",
                    null
            );
        }

        if (claimant == null
                || claimant.getTelegramId() == null) {

            return new ActivationResult(
                    false,
                    false,
                    "Telegram user could not be verified.",
                    null
            );
        }

        if (claimant.getRole() != UserRole.USER) {

            return new ActivationResult(
                    false,
                    false,
                    "OWNER / ADMIN accounts already have unrestricted access.",
                    null
            );
        }

        // -----------------------------------------------------
        // Extract token from rw_<TOKEN>
        // -----------------------------------------------------

        String token =
                tokenFromStartPayload(
                        startPayload
                );

        if (token.isBlank()) {

            return new ActivationResult(
                    false,
                    false,
                    "Invalid reward link.",
                    null
            );
        }

        // -----------------------------------------------------
        // Lock DB row
        // -----------------------------------------------------

        Optional<RewardTrial> optional =
                rewardTrialRepository
                        .findByTokenForUpdate(
                                token
                        );

        if (optional.isEmpty()) {

            return new ActivationResult(
                    false,
                    false,
                    "Reward link was not found. Request a new link in the bot.",
                    null
            );
        }

        RewardTrial rewardTrial =
                optional.get();

        LocalDateTime now =
                LocalDateTime.now();

        TelegramUser owner =
                rewardTrial.getTelegramUser();

        // -----------------------------------------------------
        // Verify reward owner
        // -----------------------------------------------------

        if (owner == null
                || owner.getTelegramId() == null) {

            rewardTrial.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(
                    rewardTrial
            );

            return new ActivationResult(
                    false,
                    false,
                    "Reward owner could not be verified.",
                    null
            );
        }

        // -----------------------------------------------------
        // CRITICAL:
        // Token must belong to same Telegram user
        // -----------------------------------------------------

        if (!Objects.equals(
                owner.getTelegramId(),
                claimant.getTelegramId()
        )) {

            log.warn(
                    "Reward token owner mismatch "
                            + "rewardTrialId={} "
                            + "expectedTelegramId={} "
                            + "claimantTelegramId={}",
                    rewardTrial.getId(),
                    owner.getTelegramId(),
                    claimant.getTelegramId()
            );

            /*
             * Do NOT expire the reward here.
             *
             * Someone else may have copied/opened the link.
             * The legitimate owner must still be allowed
             * to finish the flow.
             */

            return new ActivationResult(
                    false,
                    false,
                    "This reward link belongs to a different Telegram account.",
                    null
            );
        }

        // -----------------------------------------------------
        // Prevent legacy web callback reward
        // -----------------------------------------------------

        String provider =
                rewardTrial.getProvider() == null
                        ? ""
                        : rewardTrial
                        .getProvider()
                        .trim();

        if (!PROVIDER.equalsIgnoreCase(provider)) {

            return new ActivationResult(
                    false,
                    false,
                    "This is an old reward link. Please request a new one in Telegram.",
                    null
            );
        }

        // -----------------------------------------------------
        // Already ACTIVE
        // -----------------------------------------------------

        if (rewardTrial.getStatus()
                == RewardTrialStatus.ACTIVE) {

            if (rewardTrial.getExpiresAt() != null
                    && now.isBefore(
                    rewardTrial.getExpiresAt()
            )) {

                return new ActivationResult(
                        true,
                        true,
                        "Your 1-hour access is already active.",
                        rewardTrial.getExpiresAt()
                );
            }

            rewardTrial.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(
                    rewardTrial
            );

            return new ActivationResult(
                    false,
                    false,
                    "This reward has expired. Request a new one in Telegram.",
                    null
            );
        }

        // -----------------------------------------------------
        // Only PENDING can activate
        // -----------------------------------------------------

        if (rewardTrial.getStatus()
                != RewardTrialStatus.PENDING) {

            return new ActivationResult(
                    false,
                    false,
                    "This reward link has already been used or is no longer valid.",
                    null
            );
        }

        // -----------------------------------------------------
        // 20-minute claim expiry
        // -----------------------------------------------------

        if (rewardTrial.getLinkExpiresAt() == null
                || !now.isBefore(
                rewardTrial.getLinkExpiresAt()
        )) {

            rewardTrial.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            rewardTrial.setUpdatedAt(now);

            rewardTrialRepository.save(
                    rewardTrial
            );

            return new ActivationResult(
                    false,
                    false,
                    "This reward link expired. Request a new one in Telegram.",
                    null
            );
        }

        // -----------------------------------------------------
        // Never stack/extend another ACTIVE reward
        // -----------------------------------------------------

        Optional<RewardTrial> existingActive =
                getActiveRewardTrial(
                        claimant
                );

        if (existingActive.isPresent()) {

            RewardTrial active =
                    existingActive.get();

            if (!Objects.equals(
                    active.getId(),
                    rewardTrial.getId()
            )) {

                rewardTrial.setStatus(
                        RewardTrialStatus.EXPIRED
                );

                rewardTrial.setUpdatedAt(now);

                rewardTrialRepository.save(
                        rewardTrial
                );
            }

            return new ActivationResult(
                    true,
                    true,
                    "Your 1-hour access is already active.",
                    active.getExpiresAt()
            );
        }

        // -----------------------------------------------------
        // ACTIVATE EXACTLY 60 MINUTES
        // -----------------------------------------------------

        LocalDateTime accessExpiresAt =
                now.plusMinutes(
                        REWARD_ACCESS_MINUTES
                );

        rewardTrial.setStatus(
                RewardTrialStatus.ACTIVE
        );

        rewardTrial.setActivatedAt(now);

        rewardTrial.setExpiresAt(
                accessExpiresAt
        );

        rewardTrial.setUpdatedAt(now);

        rewardTrialRepository.save(
                rewardTrial
        );

        log.info(
                "Direct Telegram reward activated "
                        + "telegramId={} "
                        + "rewardTrialId={} "
                        + "expiresAt={}",
                claimant.getTelegramId(),
                rewardTrial.getId(),
                accessExpiresAt
        );

        return new ActivationResult(
                true,
                false,
                "1-hour free access activated successfully.",
                accessExpiresAt
        );
    }

    // =========================================================
    // ACTIVE REWARD CHECK
    // =========================================================

    @Transactional
    public Optional<RewardTrial> getActiveRewardTrial(
            TelegramUser user
    ) {

        if (user == null) {
            return Optional.empty();
        }

        LocalDateTime now =
                LocalDateTime.now();

        List<RewardTrial> activeRows =
                rewardTrialRepository
                        .findAllByTelegramUserAndStatusOrderByCreatedAtDesc(
                                user,
                                RewardTrialStatus.ACTIVE
                        );

        RewardTrial selected =
                null;

        for (RewardTrial row : activeRows) {

            // ---------------------------------------------
            // Invalid/expired row
            // ---------------------------------------------

            if (row.getExpiresAt() == null
                    || !now.isBefore(
                    row.getExpiresAt()
            )) {

                row.setStatus(
                        RewardTrialStatus.EXPIRED
                );

                row.setUpdatedAt(now);

                rewardTrialRepository.save(
                        row
                );

                continue;
            }

            // ---------------------------------------------
            // Keep newest valid active row
            // ---------------------------------------------

            if (selected == null) {

                selected = row;

            } else {

                /*
                 * Defensive duplicate ACTIVE cleanup.
                 */

                row.setStatus(
                        RewardTrialStatus.EXPIRED
                );

                row.setUpdatedAt(now);

                rewardTrialRepository.save(
                        row
                );
            }
        }

        return Optional.ofNullable(
                selected
        );
    }

    public boolean hasActiveRewardTrial(
            TelegramUser user
    ) {

        return getActiveRewardTrial(user)
                .isPresent();
    }

    public Optional<LocalDateTime> getRewardExpiry(
            TelegramUser user
    ) {

        return getActiveRewardTrial(user)
                .map(
                        RewardTrial::getExpiresAt
                );
    }

    // =========================================================
    // AUTOMATIC EXPIRY
    // =========================================================

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireRewardRows() {

        LocalDateTime now =
                LocalDateTime.now();

        // -----------------------------------------------------
        // Expire ACTIVE reward
        // -----------------------------------------------------

        List<RewardTrial> activeExpired =
                rewardTrialRepository
                        .findByStatusAndExpiresAtLessThanEqual(
                                RewardTrialStatus.ACTIVE,
                                now
                        );

        for (RewardTrial row : activeExpired) {

            row.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            row.setUpdatedAt(now);
        }

        if (!activeExpired.isEmpty()) {

            rewardTrialRepository.saveAll(
                    activeExpired
            );
        }

        // -----------------------------------------------------
        // Expire unused PENDING link
        // -----------------------------------------------------

        List<RewardTrial> pendingExpired =
                rewardTrialRepository
                        .findByStatusAndLinkExpiresAtLessThanEqual(
                                RewardTrialStatus.PENDING,
                                now
                        );

        for (RewardTrial row : pendingExpired) {

            row.setStatus(
                    RewardTrialStatus.EXPIRED
            );

            row.setUpdatedAt(now);
        }

        if (!pendingExpired.isEmpty()) {

            rewardTrialRepository.saveAll(
                    pendingExpired
            );
        }
    }

    // =========================================================
    // TELEGRAM PAYLOAD
    // =========================================================

    public boolean isRewardStartPayload(
            String startPayload
    ) {

        if (startPayload == null) {
            return false;
        }

        return startPayload
                .startsWith(
                        START_PAYLOAD_PREFIX
                )
                && !tokenFromStartPayload(
                startPayload
        ).isBlank();
    }

    // =========================================================
    // VALIDATION
    // =========================================================

    private void validateLinkCreationFeature() {

        if (!enabled) {

            throw new IllegalStateException(
                    "Reward trial feature is disabled"
            );
        }

        if (!providerApproved) {

            throw new IllegalStateException(
                    "Reward provider approval is required before enabling incentivized traffic"
            );
        }

        if (!shrtFlyService.isConfigured()) {

            throw new IllegalStateException(
                    "SHRTFLY_API_KEY is not configured"
            );
        }

        if (getBotUsername().isBlank()) {

            throw new IllegalStateException(
                    "telegram.bot.username is not configured"
            );
        }
    }

    // =========================================================
    // TELEGRAM DESTINATION
    // =========================================================

    private String buildTelegramClaimUrl(
            String token
    ) {

        String payload =
                START_PAYLOAD_PREFIX
                        + token;

        /*
         * Telegram deep-link /start payload
         * must be <= 64 characters.
         */

        if (payload.length() > 64) {

            throw new IllegalStateException(
                    "Reward start payload exceeds Telegram limit"
            );
        }

        return "https://t.me/"
                + getBotUsername()
                + "?start="
                + payload;
    }

    // =========================================================
    // TOKEN PARSING
    // =========================================================

    private String tokenFromStartPayload(
            String startPayload
    ) {

        if (startPayload == null) {
            return "";
        }

        String payload =
                startPayload.trim();

        if (!payload.startsWith(
                START_PAYLOAD_PREFIX
        )) {

            return "";
        }

        String token =
                payload.substring(
                                START_PAYLOAD_PREFIX.length()
                        )
                        .trim();

        /*
         * UUID without hyphens:
         *
         * 32 lowercase hexadecimal chars.
         */

        if (!token.matches(
                "[a-f0-9]{32}"
        )) {

            return "";
        }

        return token;
    }

    // =========================================================
    // TOKEN GENERATOR
    // =========================================================

    private String newToken() {

        return UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .toLowerCase();
    }

    // =========================================================
    // BOT USERNAME
    // =========================================================

    private String getBotUsername() {

        String value =
                telegramConfig.getBotUsername();

        if (value == null) {
            return "";
        }

        return value
                .replace("@", "")
                .trim();
    }
}