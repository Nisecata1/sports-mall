package com.sportsmall.server;

import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.service.BrowsingService;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端保存登录身份。令牌不含身份声明，8小时到期，重启后失效。 */
final class SessionStore {
    private final Clock clock;
    private final Duration lifetime;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    SessionStore() { this(Clock.systemUTC(), Duration.ofHours(8)); }
    SessionStore(Clock clock, Duration lifetime) { this.clock = clock; this.lifetime = lifetime; }
    synchronized String create(AuthenticatedUser user) {
        sessions.values().removeIf(s -> !s.expires.isAfter(clock.instant()));
        if (sessions.size() >= 1000) throw new ApiProblem(503, "SESSION_CAPACITY", "登录人数已达到本机课程演示上限，请稍后重试。");
        String token = randomId();
        sessions.put(token, new Session(user, clock.instant().plus(lifetime)));
        return token;
    }
    Optional<Session> find(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session == null || !session.expires.isAfter(clock.instant())) {
            if (session != null) sessions.remove(token, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }
    void remove(String token) { if (token != null) sessions.remove(token); }
    private String randomId() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    final class Session {
        final AuthenticatedUser user;
        final Instant expires;
        private final Map<String, PendingBrowse> browses = new HashMap<>();
        Session(AuthenticatedUser user, Instant expires) { this.user = user; this.expires = expires; }
        synchronized String open(BrowsingService.OpenedItem item) {
            if (browses.size() >= 20) throw new ApiProblem(409, "BROWSE_LIMIT", "尚未结束的浏览过多，请重新登录。");
            String id = randomId();
            browses.put(id, new PendingBrowse(item, System.nanoTime()));
            return id;
        }
        synchronized PendingBrowse take(String id) {
            PendingBrowse result = browses.remove(id);
            if (result == null) throw new ApiProblem(404, "BROWSE_NOT_FOUND", "本次浏览不存在、已结束或不属于当前登录。");
            return result;
        }
    }
    record PendingBrowse(BrowsingService.OpenedItem opened, long startedNanos) {
        long seconds() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000L); }
    }
}
