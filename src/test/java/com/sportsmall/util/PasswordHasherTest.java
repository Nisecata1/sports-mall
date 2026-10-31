package com.sportsmall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void verifiesOriginalPasswordWithoutRemovingSpacesOrUnicode() {
        char[] password = " test-口令 " .toCharArray();
        String hash = PasswordHasher.hash(password);
        assertTrue(hash.startsWith("pbkdf2_sha256$600000$"));
        assertTrue(hash.length() <= 255);
        assertFalse(hash.contains("test-口令"));
        assertTrue(PasswordHasher.verify(password, hash));
        assertFalse(PasswordHasher.verify("test-口令".toCharArray(), hash));
    }

    @Test
    void randomSaltMakesSamePasswordProduceDifferentStoredHashes() {
        char[] password = "synthetic-test-password".toCharArray();
        assertNotEquals(PasswordHasher.hash(password), PasswordHasher.hash(password));
    }

    @Test
    void malformedStoredHashesAreNotAccepted() {
        char[] password = "synthetic-test-password".toCharArray();
        assertFalse(PasswordHasher.verify(password, "plaintext"));
        assertFalse(PasswordHasher.verify(password, "pbkdf2_sha256$bad$bad$bad"));
        assertFalse(PasswordHasher.verify(password, "pbkdf2_sha256$999999999$bad$bad"));
        assertFalse(PasswordHasher.verify(password, null));
    }
}
