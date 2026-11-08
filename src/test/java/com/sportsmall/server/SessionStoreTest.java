package com.sportsmall.server;

import com.sportsmall.model.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    private final AuthenticatedUser user = new AuthenticatedUser(7L, "session-user", "USER");
    @Test void opaqueTokensAreUniqueAndResolveServerIdentity() {
        SessionStore store = new SessionStore();
        String first = store.create(user), second = store.create(user);
        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second); assertEquals(user, store.find(first).orElseThrow().user);
        assertTrue(store.find(null).isEmpty());
        assertTrue(store.find("invented").isEmpty());
    }
    @Test void logoutInvalidatesOnlyItsSession() {
        SessionStore store = new SessionStore();
        String first=store.create(user), second=store.create(user);
        store.remove(first);
        assertTrue(store.find(first).isEmpty());
        assertEquals(user, store.find(second).orElseThrow().user);
    }
    @Test void expiryUsesServerTimeAndIsNotExtendedByReading() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-06T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        SessionStore store = new SessionStore(clock, Duration.ofMinutes(1));
        String token=store.create(user);
        now.set(now.get().plusSeconds(59)); assertEquals(user, store.find(token).orElseThrow().user);
        now.set(now.get().plusSeconds(1));
        assertTrue(store.find(token).isEmpty());
    }
}
