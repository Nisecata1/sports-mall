package com.sportsmall.server;

import com.sportsmall.model.AuthenticatedUser;
import java.util.Objects;
import java.util.Optional;

/** 解析请求携带的会话，只返回身份信息；是否需要登录及业务权限由调用方决定。 */
final class RequestAuthentication {
    private final SessionStore sessions;

    RequestAuthentication(SessionStore sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    Result resolve(String authorization) {
        String token = authorization != null && authorization.matches("Bearer [A-Za-z0-9_-]{43}")
                ? authorization.substring(7) : null;
        return new Result(token, sessions.find(token));
    }

    record Result(String token, Optional<SessionStore.Session> session) {
        Result { Objects.requireNonNull(session); }

        boolean authenticated() { return session.isPresent(); }

        Optional<AuthenticatedUser> user() { return session.map(value -> value.user); }

        @Override public String toString() {
            return "AuthenticationResult[authenticated=" + authenticated() + "]";
        }
    }
}
