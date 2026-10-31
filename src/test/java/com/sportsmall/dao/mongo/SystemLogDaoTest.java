package com.sportsmall.dao.mongo;

import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SystemLogDaoTest {
    @Test
    void successLogUsesBsonLongDateAndNestedOperationWithoutCredentials() {
        Instant now = Instant.parse("2026-10-31T08:00:00Z");
        Document log = SystemLogDao.loginDocument(42L, true, "127.0.0.1", now);
        BsonDocument bson = log.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());

        assertEquals(Set.of("user_id", "log_type", "log_level", "message", "action_detail", "timestamp"), log.keySet());
        assertTrue(bson.get("user_id").isInt64());
        assertEquals(42L, bson.getInt64("user_id").getValue());
        assertTrue(bson.get("timestamp").isDateTime());
        assertEquals(now.toEpochMilli(), bson.getDateTime("timestamp").getValue());
        assertEquals("LOGIN", log.getString("log_type"));
        assertEquals("INFO", log.getString("log_level"));
        BsonDocument detail = bson.getDocument("action_detail");
        assertEquals("127.0.0.1", detail.getString("ip").getValue());
        assertEquals("LOGIN_SUCCESS", detail.getString("operation").getValue());
    }

    @Test
    void unknownUserFailureKeepsNullReferenceAndWarnLevel() {
        Document log = SystemLogDao.loginDocument(null, false, "127.0.0.1", Instant.EPOCH);
        BsonDocument bson = log.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(log.containsKey("user_id"));
        assertTrue(bson.get("user_id").isNull());
        assertEquals("WARN", log.getString("log_level"));
        assertEquals("LOGIN_FAILED", bson.getDocument("action_detail").getString("operation").getValue());
    }
}
