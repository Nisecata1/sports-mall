package com.sportsmall.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingFilterTest {
    @Test
    void successfulRequestLogsOperationStatusAndDuration() throws Exception {
        try (Fixture fixture = new Fixture(200, 1)) {
            assertEquals(200, fixture.send("GET", "/api/me/profile", "").statusCode());
            fixture.awaitCompletion();

            assertEquals(2, fixture.events.size());
            assertTrue(fixture.messages().contains("收到请求 id=1 操作=维护本人档案"));
            assertTrue(fixture.messages().contains("接口=/api/me/profile"));
            assertTrue(fixture.messages().contains("请求完成 id=1"));
            assertTrue(fixture.messages().matches("(?s).*HTTP=200 耗时=[0-9]+毫秒.*"));
            assertEquals(Level.INFO, fixture.events.get(1).getLevel());
        }
    }

    @Test
    void rejectedRequestHasWarningAndActualHttpStatus() throws Exception {
        try (Fixture fixture = new Fixture(401, 1)) {
            assertEquals(401, fixture.send("GET", "/api/me", "").statusCode());
            fixture.awaitCompletion();

            assertEquals(2, fixture.events.size());
            assertEquals(Level.WARN, fixture.events.get(1).getLevel());
            assertTrue(fixture.messages().contains("请求未成功 id=1"));
            assertTrue(fixture.messages().contains("HTTP=401"));
        }
    }

    @Test
    void requestBodiesCredentialsQueryAndDynamicIdentifiersStayOutOfLogs() throws Exception {
        try (Fixture fixture = new Fixture(200, 5)) {
            String body = "{\"password\":\"body-secret\"}";
            fixture.send("POST", "/api/auth/login?password=query-secret", body);
            assertEquals(body, fixture.lastBody.get(), "Logging must preserve the request body for the handler");
            fixture.send("GET", "/api/items/987654/comments", "");
            fixture.send("POST", "/api/orders/876543/cancel", "{}");
            fixture.send("POST", "/api/browses/" + "b".repeat(43) + "/finish", "{}");
            fixture.send("GET", "/unknown/path-secret%0AINJECTED", "");
            fixture.awaitCompletion();

            String messages = fixture.messages();
            for (String secret : List.of("body-secret", "query-secret", "header-secret",
                    "987654", "876543", "b".repeat(43), "path-secret", "INJECTED")) {
                assertFalse(messages.contains(secret), "Sensitive content appeared in request logs");
            }
            assertTrue(messages.contains("接口=/api/items/{itemId}/comments"));
            assertTrue(messages.contains("接口=/api/orders/{orderId}/cancel"));
            assertTrue(messages.contains("接口=/api/browses/{browseId}/finish"));
            assertTrue(messages.contains("接口=[未识别接口]"));
            assertEquals(10, fixture.events.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        private final Level previousLevel = logger.getLevel();
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final CountDownLatch completedRequests;
        private final AppenderBase<ILoggingEvent> appender;
        private final HttpServer server;
        private final HttpClient client = HttpClient.newHttpClient();

        Fixture(int responseStatus, int requestCount) throws IOException {
            completedRequests = new CountDownLatch(requestCount);
            appender = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    events.add(event);
                    if (event.getMessage().startsWith("请求")) {
                        completedRequests.countDown();
                    }
                }
            };
            appender.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(responseStatus, -1);
                } finally {
                    exchange.close();
                }
            }).getFilters().add(new RequestLoggingFilter());
            server.start();
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                    .header("Authorization", "Bearer header-secret")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        void awaitCompletion() throws InterruptedException {
            assertTrue(completedRequests.await(3, TimeUnit.SECONDS), "Request completion log was missing");
        }

        String messages() {
            return String.join("\n", events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        }

        @Override
        public void close() {
            server.stop(0);
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }
}
