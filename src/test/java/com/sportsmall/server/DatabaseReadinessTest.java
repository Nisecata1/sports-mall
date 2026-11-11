package com.sportsmall.server;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseReadinessTest {
    @ParameterizedTest
    @CsvSource({"true,true,200", "false,true,503", "true,false,503", "false,false,503"})
    void eachDependencyIsReportedAndBothMustPass(boolean sqlOk, boolean mongoOk, int http) {
        AtomicInteger closed = new AtomicInteger(), pings = new AtomicInteger();
        var readiness = new DatabaseReadiness(source(sqlOk, false, closed), database(() -> {
            pings.incrementAndGet();
            return new Document("ok", mongoOk ? 1.0 : 0.0);
        }));
        var report = readiness.check();
        assertEquals(http, report.httpStatus());
        assertEquals(sqlOk ? "UP" : "DOWN", report.dependencies().get("mysql"));
        assertEquals(mongoOk ? "UP" : "DOWN", report.dependencies().get("mongodb"));
        assertEquals(1, closed.get(), "Every borrowed SQL connection is returned");
        assertEquals(1, pings.get(), "Mongo is still checked after SQL is unavailable");
    }

    @Test void sqlAcquisitionFailureDoesNotExposeDriverDetailsOrSkipMongo() {
        AtomicInteger pings = new AtomicInteger();
        var report = new DatabaseReadiness(source(false, true, new AtomicInteger()), database(() -> {
            pings.incrementAndGet(); return new Document("ok", 1);
        })).check();
        assertEquals(503, report.httpStatus());
        assertEquals("UP", report.dependencies().get("mongodb"));
        assertEquals(1, pings.get());
        assertFalse(report.toString().contains("private-driver-detail"));
    }

    @Test void mongoFailureIsSanitizedAndNextCheckCanRecover() {
        AtomicBoolean broken = new AtomicBoolean(true);
        var readiness = new DatabaseReadiness(source(true, false, new AtomicInteger()), database(() -> {
            if (broken.get()) throw new IllegalStateException("private-driver-detail");
            return new Document("ok", 1);
        }));
        var failed = readiness.check();
        assertEquals(503, failed.httpStatus());
        assertEquals("UP", failed.dependencies().get("mysql"));
        assertFalse(failed.toString().contains("private-driver-detail"));
        broken.set(false);
        assertEquals(200, readiness.check().httpStatus());
    }

    @Test void missingMongoAcknowledgementIsNotReportedAsSuccess() {
        assertEquals(503, new DatabaseReadiness(source(true, false, new AtomicInteger()),
                database(Document::new)).check().httpStatus());
    }

    private static DataSource source(boolean valid, boolean throwsOnAcquire, AtomicInteger closed) {
        return proxy(DataSource.class, (object, method, args) -> {
            if (!method.getName().equals("getConnection")) throw new AssertionError(method);
            if (throwsOnAcquire) throw new SQLException("private-driver-detail");
            return proxy(Connection.class, (connection, call, parameters) -> {
                return switch (call.getName()) {
                    case "isValid" -> { assertEquals(2, parameters[0]); yield valid; }
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> throw new AssertionError(call);
                };
            });
        });
    }

    private static MongoDatabase database(java.util.function.Supplier<Document> ping) {
        return proxy(MongoDatabase.class, (object, method, args) -> {
            if (!method.getName().equals("runCommand")) throw new AssertionError(method);
            assertEquals(new Document("ping", 1), args[0]);
            return ping.get();
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
