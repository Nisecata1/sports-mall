package com.sportsmall.server;

import com.sportsmall.model.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RequestAuthenticationTest {
    @Test void missingMalformedAndUnknownCredentialsReturnInformationWithoutRejectingRequests() {
        RequestAuthentication authentication = new RequestAuthentication(new SessionStore());
        for (String header : Arrays.asList(null, "", "Basic ignored", "Bearer too-short",
                "Bearer " + "a".repeat(43), "Bearer " + "a".repeat(43) + " ADMIN")) {
            var result = assertDoesNotThrow(() -> authentication.resolve(header));
            assertFalse(result.authenticated());
            assertTrue(result.user().isEmpty());
        }
    }

    @Test void identityComesFromTheStoredSessionAndCredentialsStayOutOfResultText() {
        SessionStore store = new SessionStore();
        var user = new AuthenticatedUser(7, "ordinary-user", "USER");
        String token = store.create(user);
        var result = new RequestAuthentication(store).resolve("Bearer " + token);
        assertTrue(result.authenticated());
        assertEquals(user, result.user().orElseThrow());
        assertSame(store.find(token).orElseThrow(), result.session().orElseThrow());
        assertFalse(result.toString().contains(token));
    }

    @Test void logoutAndExpiryInvalidateSubsequentResolutionWithoutMakingPolicyDecisions() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        SessionStore store = new SessionStore(clock, Duration.ofMinutes(1));
        RequestAuthentication authentication = new RequestAuthentication(store);
        var user = new AuthenticatedUser(7, "ordinary-user", "USER");
        String loggedOut = store.create(user), expiring = store.create(user);
        store.remove(loggedOut);
        assertFalse(authentication.resolve("Bearer " + loggedOut).authenticated());
        assertTrue(authentication.resolve("Bearer " + expiring).authenticated());
        now.set(now.get().plusSeconds(60));
        assertFalse(authentication.resolve("Bearer " + expiring).authenticated());
    }

    @Test void simultaneousRequestsKeepTheirOwnIdentity() throws Exception {
        SessionStore store = new SessionStore();
        RequestAuthentication authentication = new RequestAuthentication(store);
        var ordinary = new AuthenticatedUser(7, "ordinary-user", "USER");
        var admin = new AuthenticatedUser(8, "administrator", "ADMIN");
        String ordinaryHeader = "Bearer " + store.create(ordinary), adminHeader = "Bearer " + store.create(admin);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> requests = java.util.stream.IntStream.range(0, 40).mapToObj(index -> (Callable<Void>) () -> {
                boolean isAdmin = index % 2 == 0;
                assertEquals(isAdmin ? admin : ordinary,
                        authentication.resolve(isAdmin ? adminHeader : ordinaryHeader).user().orElseThrow());
                return null;
            }).toList();
            for (Future<Void> request : executor.invokeAll(requests)) request.get(3, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test void onlyExactPublicPathsBypassTheEntryLoginRequirement() {
        for (String path : List.of("/api/health", "/api/auth/login", "/api/auth/register")) {
            assertFalse(MallApiServer.requiresLogin(path), path);
        }
        for (String path : List.of("/api/me", "/api/admin/items", "/api/orders", "/api/new-feature",
                "/api/auth/logout", "/api/auth/login/extra", "/api/health/extra")) {
            assertTrue(MallApiServer.requiresLogin(path), path);
        }
        assertFalse(MallApiServer.requiresLogin("/unknown"));
    }
}
