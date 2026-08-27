package com.siva.storybot.enums;

/**
 * Central source of truth for episode delivery limits.
 * <p>
 * REWARD_TRIAL  -> ShrtFly 1-hour reward users
 * STANDARD_USER -> Paid / manual trial / global trial users
 * ADMIN_OWNER     -> ADMIN / OWNER
 */
public enum EpisodeLimitPolicy {

    // perSearch, perHour, perDay
    REWARD_TRIAL(5, 10, 15),

    STANDARD_USER(50, 500, 1000),

    ADMIN_OWNER(250, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final int perSearch;
    private final int perHour;
    private final int perDay;

    EpisodeLimitPolicy(int perSearch, int perHour, int perDay) {
        this.perSearch = perSearch;
        this.perHour = perHour;
        this.perDay = perDay;
    }

    public int getPerSearch() {
        return perSearch;
    }

    public int getPerHour() {
        return perHour;
    }

    public int getPerDay() {
        return perDay;
    }

    public boolean hasUnlimitedHourlyAndDailyUsage() {
        return this == ADMIN_OWNER;
    }
}