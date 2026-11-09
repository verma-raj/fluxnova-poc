package com.example.fluxnova.web;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing on the landing page's sign-in.
 * <p>
 * The tenant engines have their own brute-force delay, but the platform administrator's sign-in (the only one that works
 * while no tenant exists) does not go through an engine, so it needs one of its own. After {@value #FREE_ATTEMPTS} failed
 * attempts in a row for a user name, further attempts are refused for 30 seconds, then 60, 120 and so on up to 15 minutes.
 * A successful sign-in clears the count, and so does half an hour without failures. State is in memory, per task.
 * <p>
 * Keyed by user name (not by address): behind a load balancer every client has the same address.
 */
@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    private static final long BASE_LOCK_SECONDS = 30;
    private static final long MAX_LOCK_SECONDS = 900;
    private static final Duration FORGET_AFTER = Duration.ofMinutes(30);
    private static final int MAX_ENTRIES = 5000;

    private record State(int failures, Instant lastFailure) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** How many more seconds the user has to wait before another attempt is accepted; 0 if an attempt is allowed now. */
    public long secondsToWait(String user) {
        String key = key(user);
        State state = states.get(key);
        if (state == null) {
            return 0;
        }
        Instant now = clock.instant();
        if (Duration.between(state.lastFailure(), now).compareTo(FORGET_AFTER) > 0) {
            states.remove(key);
            return 0;
        }
        if (state.failures() < FREE_ATTEMPTS) {
            return 0;
        }
        long lockSeconds = Math.min(MAX_LOCK_SECONDS, BASE_LOCK_SECONDS << Math.min(state.failures() - FREE_ATTEMPTS, 10));
        long remainingMillis = lockSeconds * 1000 - Duration.between(state.lastFailure(), now).toMillis();
        return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
    }

    public void failure(String user) {
        if (states.size() >= MAX_ENTRIES) {
            Instant cutoff = clock.instant().minus(FORGET_AFTER);
            states.values().removeIf(s -> s.lastFailure().isBefore(cutoff));
            if (states.size() >= MAX_ENTRIES) {
                states.clear();             // an attack with endless made-up user names must not exhaust memory
            }
        }
        Instant now = clock.instant();
        states.merge(key(user), new State(1, now), (old, ignored) -> new State(old.failures() + 1, now));
    }

    public void success(String user) {
        states.remove(key(user));
    }

    private static String key(String user) {
        String normalized = user == null ? "" : user.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
