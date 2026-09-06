package com.siva.storybot;

import com.siva.storybot.enums.RewardLinkProviderType;
import com.siva.storybot.service.RewardLinkProvider;
import com.siva.storybot.service.RewardLinkProviderResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RewardLinkProviderResolverTest {

    @Test
    void rotatesProviderFirstThenKeys() {
        List<String> calls = new ArrayList<>();

        FakeProvider lite = new FakeProvider(
                RewardLinkProviderType.LITESHORT, 2, calls, Set.of());
        FakeProvider shrt = new FakeProvider(
                RewardLinkProviderType.SHRTFLY, 2, calls, Set.of());

        RewardLinkProviderResolver resolver =
                new RewardLinkProviderResolver(List.of(lite, shrt));

        for (int i = 0; i < 5; i++) {
            resolver.shortenWithFailover("https://t.me/test_bot?start=rw_token");
        }

        assertEquals(
                List.of(
                        "LITESHORT-key1",
                        "LITESHORT-key2",
                        "SHRTFLY-key1",
                        "SHRTFLY-key2",
                        "LITESHORT-key1"
                ),
                calls
        );
    }

    @Test
    void failureMovesToNextSlotAndNextRequestContinuesAfterSuccess() {
        List<String> calls = new ArrayList<>();

        FakeProvider lite = new FakeProvider(
                RewardLinkProviderType.LITESHORT, 2, calls, Set.of(0));
        FakeProvider shrt = new FakeProvider(
                RewardLinkProviderType.SHRTFLY, 2, calls, Set.of());

        RewardLinkProviderResolver resolver =
                new RewardLinkProviderResolver(List.of(lite, shrt));

        // LITE key1 fails, LITE key2 succeeds.
        resolver.shortenWithFailover("https://t.me/test_bot?start=rw_one");

        // Must continue from SHRT key1, not restart from LITE key1.
        resolver.shortenWithFailover("https://t.me/test_bot?start=rw_two");

        assertEquals(
                List.of(
                        "LITESHORT-key1",
                        "LITESHORT-key2",
                        "SHRTFLY-key1"
                ),
                calls
        );
    }

    @Test
    void supportsDifferentKeyCountsPerProvider() {
        List<String> calls = new ArrayList<>();

        FakeProvider lite = new FakeProvider(
                RewardLinkProviderType.LITESHORT, 3, calls, Set.of());
        FakeProvider shrt = new FakeProvider(
                RewardLinkProviderType.SHRTFLY, 2, calls, Set.of());

        RewardLinkProviderResolver resolver =
                new RewardLinkProviderResolver(List.of(lite, shrt));

        for (int i = 0; i < 6; i++) {
            resolver.shortenWithFailover("https://t.me/test_bot?start=rw_token_" + i);
        }

        assertEquals(
                List.of(
                        "LITESHORT-key1",
                        "LITESHORT-key2",
                        "LITESHORT-key3",
                        "SHRTFLY-key1",
                        "SHRTFLY-key2",
                        "LITESHORT-key1"
                ),
                calls
        );
    }

    private static final class FakeProvider implements RewardLinkProvider {

        private final RewardLinkProviderType type;
        private final int keyCount;
        private final List<String> calls;
        private final Set<Integer> failingIndexes;

        private FakeProvider(
                RewardLinkProviderType type,
                int keyCount,
                List<String> calls,
                Set<Integer> failingIndexes
        ) {
            this.type = type;
            this.keyCount = keyCount;
            this.calls = calls;
            this.failingIndexes = new HashSet<>(failingIndexes);
        }

        @Override
        public RewardLinkProviderType getProviderType() {
            return type;
        }

        @Override
        public boolean isConfigured() {
            return keyCount > 0;
        }

        @Override
        public int getConfiguredApiKeyCount() {
            return keyCount;
        }

        @Override
        public String shorten(String destinationUrl, int apiKeyIndex) {
            calls.add(type.name() + "-key" + (apiKeyIndex + 1));

            if (failingIndexes.contains(apiKeyIndex)) {
                throw new IllegalStateException("forced test failure");
            }

            return "https://short.test/" + type.name().toLowerCase() + "/" + apiKeyIndex;
        }
    }
}
