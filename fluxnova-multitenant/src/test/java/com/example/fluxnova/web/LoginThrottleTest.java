package com.example.fluxnova.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final LoginThrottle throttle = new LoginThrottle(clock);

    private void fail(String user, int times) {
        for (int i = 0; i < times; i++) {
            throttle.failure(user);
        }
    }

    @Test
    void theFirstFailedAttemptsAreNotSlowedDown() {
        fail("admin", LoginThrottle.FREE_ATTEMPTS - 1);

        assertThat(throttle.secondsToWait("admin")).isZero();
    }

    @Test
    void afterTheFreeAttemptsTheUserHasToWaitAndThenMayTryAgain() {
        fail("admin", LoginThrottle.FREE_ATTEMPTS);

        assertThat(throttle.secondsToWait("admin")).isEqualTo(30);
        clock.advance(Duration.ofSeconds(20));
        assertThat(throttle.secondsToWait("admin")).isEqualTo(10);
        clock.advance(Duration.ofSeconds(10));
        assertThat(throttle.secondsToWait("admin")).isZero();
    }

    @Test
    void everyFurtherFailureDoublesTheWaitUpToALimit() {
        fail("admin", LoginThrottle.FREE_ATTEMPTS + 1);
        assertThat(throttle.secondsToWait("admin")).isEqualTo(60);

        throttle.failure("admin");
        assertThat(throttle.secondsToWait("admin")).isEqualTo(120);

        fail("admin", 20);
        assertThat(throttle.secondsToWait("admin")).isEqualTo(900);        // capped at 15 minutes
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        fail("admin", LoginThrottle.FREE_ATTEMPTS);
        clock.advance(Duration.ofSeconds(31));

        throttle.success("admin");
        fail("admin", LoginThrottle.FREE_ATTEMPTS - 1);

        assertThat(throttle.secondsToWait("admin")).isZero();
    }

    @Test
    void theWaitAppliesToTheUserNameOnlyAndIgnoresCaseAndSpaces() {
        fail("  Admin ", LoginThrottle.FREE_ATTEMPTS);

        assertThat(throttle.secondsToWait("admin")).isEqualTo(30);
        assertThat(throttle.secondsToWait("ADMIN")).isEqualTo(30);
        assertThat(throttle.secondsToWait("somebody-else")).isZero();
    }

    @Test
    void oldFailuresAreForgotten() {
        fail("admin", LoginThrottle.FREE_ATTEMPTS + 3);
        clock.advance(Duration.ofMinutes(31));

        assertThat(throttle.secondsToWait("admin")).isZero();
        fail("admin", LoginThrottle.FREE_ATTEMPTS - 1);
        assertThat(throttle.secondsToWait("admin")).isZero();         // counting started over
    }

    @Test
    void madeUpUserNamesCannotExhaustMemory() {
        for (int i = 0; i < 6000; i++) {
            throttle.failure("user-" + i);
        }

        assertThat(throttle.secondsToWait("user-5999")).isZero();      // still works, nothing blew up
    }
}
