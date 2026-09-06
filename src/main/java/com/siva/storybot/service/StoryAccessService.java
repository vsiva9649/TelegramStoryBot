package com.siva.storybot.service;

import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.entity.UserStoryAccess;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.StoryRepository;
import com.siva.storybot.repository.UserStoryAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryAccessService {

    private final UserStoryAccessRepository userStoryAccessRepository;
    private final StoryRepository storyRepository;
    private final SubscriptionService subscriptionService;
    private final GlobalTrialService globalTrialService;
    private final RewardTrialService rewardTrialService;

    // =========================================
    // ACCESS CHECK
    // =========================================

    public boolean hasStoryAccess(TelegramUser user, Story story) {

        if (user == null || story == null || user.getRole() == null) {
            return false;
        }

        // OWNER always has access to every story.
        if (user.getRole() == UserRole.OWNER) {
            return true;
        }

        // ADMIN access must come from an OWNER-granted mapping.
        // This closes the promotion loophole where a USER permission granted
        // by another ADMIN could otherwise become ADMIN access after promotion.
        if (user.getRole() == UserRole.ADMIN) {
            return userStoryAccessRepository
                    .existsByTelegramUserAndStoryAndActiveTrueAndGrantedByRole(
                            user,
                            story,
                            UserRole.OWNER);
        }

        // Normal USER can use any active direct mapping granted by OWNER/ADMIN.
        return userStoryAccessRepository
                .existsByTelegramUserAndStoryAndActiveTrue(user, story);
    }

    public boolean hasAssignedStoryAccess(TelegramUser targetUser, Story story) {
        return hasStoryAccess(targetUser, story);
    }

    /**
     * Final listening authorization. Story catalog visibility is public, but
     * episode delivery is protected here.
     *
     * Priority:
     * 1) OWNER -> every story
     * 2) GLOBAL FREE TRIAL -> every active story for ADMIN and USER
     * 3) ADMIN outside global trial -> only OWNER-assigned stories
     * 4) USER with paid/manual subscription -> permanent UserStoryAccess mapping
     * 5) USER with reward access -> only the one story selected on that RewardTrial
     *
     * IMPORTANT:
     * Global free trial grants CONTENT access only. It does not increase an
     * ADMIN's ability to assign/manage stories for other users.
     */
    public boolean hasEpisodeAccess(TelegramUser user, Story story) {

        if (user == null || story == null || user.getRole() == null) {
            return false;
        }

        // OWNER always has story content access.
        if (user.getRole() == UserRole.OWNER) {
            return true;
        }

        // During GLOBAL FREE TRIAL every ADMIN/USER can listen to every
        // active story. Story.active is still enforced by TelegramService.
        if (globalTrialService.hasGlobalTrialAccess(user)) {
            return true;
        }

        // Outside global trial, ADMIN remains restricted to OWNER mappings.
        if (user.getRole() == UserRole.ADMIN) {
            return hasStoryAccess(user, story);
        }

        if (user.getRole() != UserRole.USER) {
            return false;
        }

        // Paid plans and individual/manual FREE subscriptions use persistent
        // OWNER/ADMIN story mappings.
        if (subscriptionService.hasActiveSubscription(user)) {
            return hasStoryAccess(user, story);
        }

        // Short-link reward is limited to ONE selected story for that reward row.
        if (rewardTrialService.hasActiveRewardTrial(user)) {
            return rewardTrialService.hasSelectedStoryAccess(user, story);
        }

        return false;
    }

    public long getAssignedStoryCount(TelegramUser user) {

        if (user == null) {
            return 0;
        }

        if (user.getRole() == UserRole.OWNER) {
            return storyRepository.count();
        }

        if (user.getRole() == UserRole.ADMIN) {
            return userStoryAccessRepository
                    .findAllByTelegramUserAndActiveTrue(user)
                    .stream()
                    .filter(access -> access.getGrantedByRole() == UserRole.OWNER)
                    .count();
        }

        return userStoryAccessRepository.countByTelegramUserAndActiveTrue(user);
    }

    // =========================================
    // STORY LISTS FOR CURRENT USER
    // =========================================

    public Page<Story> getCompletedStories(
            TelegramUser user,
            int page,
            int size) {

        // Public catalog: every user can browse every active completed story.
        // Access is checked only when episodes are requested.
        return storyRepository.getCompletedStories(pageRequest(page, size));
    }

    public Page<Story> getOnGoingStories(
            TelegramUser user,
            int page,
            int size) {

        // Public catalog: every user can browse every active ongoing story.
        // Access is checked only when episodes are requested.
        return storyRepository.getOnGoingStories(pageRequest(page, size));
    }

    /**
     * Stories the actor is allowed to assign to another user.
     * OWNER -> every active story.
     * ADMIN -> only OWNER-mapped stories assigned to that ADMIN.
     */
    public Page<Story> getAssignableStories(
            TelegramUser actor,
            int page,
            int size) {

        Pageable pageable = pageRequest(page, size);

        if (isOwner(actor)) {
            return storyRepository.findByActiveTrue(pageable);
        }

        if (actor != null && actor.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdmin(
                    actor,
                    UserRole.OWNER,
                    pageable);
        }

        return Page.empty(pageable);
    }

    /**
     * Stories that may be managed by the actor in story-specific admin tools.
     * OWNER can manage all stories including inactive stories.
     * ADMIN can manage only active OWNER-assigned stories.
     */
    public Page<Story> getManageableStories(
            TelegramUser actor,
            int page,
            int size) {

        Pageable pageable = pageRequest(page, size);

        if (isOwner(actor)) {
            return storyRepository.findAll(pageable);
        }

        if (actor != null && actor.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdmin(
                    actor,
                    UserRole.OWNER,
                    pageable);
        }

        return Page.empty(pageable);
    }

    public List<Story> getAccessibleStories(TelegramUser user) {

        if (isOwner(user)) {
            return storyRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        }

        if (user != null && user.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdminList(user, UserRole.OWNER);
        }

        if (user == null) {
            return List.of();
        }

        return storyRepository.getActiveStoriesForUserList(user);
    }

    // =========================================
    // GRANT / REVOKE
    // =========================================

    @Transactional
    public UserStoryAccess grantStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateGrant(actor, targetUser, story);

        UserStoryAccess existing = userStoryAccessRepository
                .findByTelegramUserAndStory(targetUser, story)
                .orElse(null);

        // Keep a valid active grant unchanged. However, if the target is now
        // ADMIN and the old row was granted by ADMIN while the target was a
        // USER, hasAssignedStoryAccess() is false. In that case OWNER must be
        // able to upgrade the existing row into a valid OWNER grant.
        if (existing != null
                && Boolean.TRUE.equals(existing.getActive())
                && hasAssignedStoryAccess(targetUser, story)) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();

        UserStoryAccess access = existing == null
                ? UserStoryAccess.builder()
                        .telegramUser(targetUser)
                        .story(story)
                        .build()
                : existing;

        access.setGrantedBy(actor);
        access.setGrantedByRole(actor.getRole());
        access.setActive(true);
        access.setGrantedAt(now);
        access.setRevokedAt(null);
        access.setUpdatedAt(now);

        UserStoryAccess saved = userStoryAccessRepository.save(access);

        log.info(
                "Story access granted actor={} actorRole={} target={} targetRole={} storyId={}",
                actor.getTelegramId(),
                actor.getRole(),
                targetUser.getTelegramId(),
                targetUser.getRole(),
                story.getId());

        return saved;
    }

    @Transactional
    public void revokeStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateRevoke(actor, targetUser, story);

        UserStoryAccess access = userStoryAccessRepository
                .findByTelegramUserAndStory(targetUser, story)
                .orElse(null);

        if (access == null || !Boolean.TRUE.equals(access.getActive())) {
            return;
        }

        access.setActive(false);
        access.setRevokedAt(LocalDateTime.now());
        access.setUpdatedAt(LocalDateTime.now());

        userStoryAccessRepository.save(access);

        log.info(
                "Story access revoked actor={} actorRole={} target={} targetRole={} storyId={}",
                actor.getTelegramId(),
                actor.getRole(),
                targetUser.getTelegramId(),
                targetUser.getRole(),
                story.getId());
    }

    @Transactional
    public boolean toggleStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        boolean currentlyAssigned = hasAssignedStoryAccess(targetUser, story);

        if (currentlyAssigned) {
            revokeStoryAccess(actor, targetUser, story);
            return false;
        }

        grantStoryAccess(actor, targetUser, story);
        return true;
    }

    // =========================================
    // PERMISSION VALIDATION
    // =========================================

    private void validateGrant(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateCommon(actor, targetUser, story);

        if (actor.getRole() == UserRole.OWNER) {

            if (targetUser.getRole() == UserRole.OWNER) {
                throw new IllegalArgumentException("OWNER does not require story mapping");
            }

            return;
        }

        if (actor.getRole() == UserRole.ADMIN) {

            if (targetUser.getRole() != UserRole.USER) {
                throw new SecurityException("ADMIN can assign stories only to USER accounts");
            }

            if (!hasStoryAccess(actor, story)) {
                throw new SecurityException("ADMIN cannot assign a story that is not assigned to the ADMIN");
            }

            return;
        }

        throw new SecurityException("USER cannot assign story access");
    }

    private void validateRevoke(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        // Same authority model is used for revoke as grant.
        validateGrant(actor, targetUser, story);
    }

    private void validateCommon(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        if (actor == null) {
            throw new IllegalArgumentException("Actor is required");
        }

        if (targetUser == null) {
            throw new IllegalArgumentException("Target user is required");
        }

        if (story == null) {
            throw new IllegalArgumentException("Story is required");
        }

        if (actor.getRole() == null) {
            throw new SecurityException("Actor role is required");
        }
    }

    private boolean isOwner(TelegramUser user) {
        return user != null && user.getRole() == UserRole.OWNER;
    }

    private Pageable pageRequest(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        return PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "id"));
    }
}
