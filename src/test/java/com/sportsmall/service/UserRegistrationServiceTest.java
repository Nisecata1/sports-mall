package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.RegisteredUser;
import com.sportsmall.util.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UserRegistrationServiceTest {
    @Test
    void passesOnlyPasswordHashToStorageAndReturnsGeneratedIdentity() {
        char[] password = " example-口令 ".toCharArray();
        AtomicInteger writes = new AtomicInteger();
        UserRegistrationService service = new UserRegistrationService((username, hash, email, phone) -> {
            writes.incrementAndGet();
            assertEquals("mall_test", username);
            assertEquals("mall@example.com", email);
            assertNull(phone);
            assertTrue(PasswordHasher.verify(password, hash));
            return new RegisteredUser(42L, username, email);
        });
        RegisteredUser result = service.register(" mall_test ", " mall@example.com ", " ", password, password.clone());
        assertEquals(42L, result.userId());
        assertEquals(1, writes.get());
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidInputNeverReachesStorage(String username, String email, String phone, String code) {
        UserRegistrationService service = new UserRegistrationService((u, h, e, p) -> {
            fail("无效输入不能写入数据库");
            return null;
        });
        char[] password = "synthetic-test-password".toCharArray();
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.register(username, email, phone, password, password.clone()));
        assertEquals(code, failure.code());
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of(" ", "mall@example.com", null, "INVALID_USERNAME"),
                Arguments.of("u".repeat(51), "mall@example.com", null, "INVALID_USERNAME"),
                Arguments.of("mall_test", "", null, "INVALID_EMAIL"),
                Arguments.of("mall_test", "not-an-email", null, "INVALID_EMAIL"),
                Arguments.of("mall_test", "u".repeat(90) + "@example.com", null, "INVALID_EMAIL"),
                Arguments.of("mall_test", "mall@example.com", "1".repeat(21), "INVALID_PHONE")
        );
    }

    @Test
    void mismatchedConfirmationNeverReachesStorage() {
        UserRegistrationService service = new UserRegistrationService((u, h, e, p) -> {
            fail("密码未确认不能写入数据库");
            return null;
        });
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.register("mall_test", "mall@example.com", null,
                        "synthetic-one".toCharArray(), "synthetic-two".toCharArray()));
        assertEquals("PASSWORD_MISMATCH", failure.code());
    }

    @Test
    void emptyPasswordNeverReachesStorage() {
        UserRegistrationService service = new UserRegistrationService((u, h, e, p) -> {
            fail("空密码不能写入数据库");
            return null;
        });
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.register("mall_test", "mall@example.com", null, new char[0], new char[0]));
        assertEquals("PASSWORD_REQUIRED", failure.code());
    }

    @Test
    void databaseUniquenessConflictRemainsAnUnderstandableBusinessError() {
        UserRegistrationService service = new UserRegistrationService((u, h, e, p) -> {
            throw new MallBusinessException("ACCOUNT_ALREADY_EXISTS", "该用户名或邮箱已被使用，请换一个。");
        });
        char[] password = "synthetic-test-password".toCharArray();
        MallBusinessException failure = assertThrows(MallBusinessException.class,
                () -> service.register("mall_test", "mall@example.com", null, password, password.clone()));
        assertEquals("ACCOUNT_ALREADY_EXISTS", failure.code());
    }

    @Test
    void databaseFailureIsNotReportedAsRegistrationSuccess() {
        UserRegistrationService service = new UserRegistrationService((u, h, e, p) -> {
            throw new MallDataAccessException("USER_SAVE_FAILED", "用户注册未完成");
        });
        char[] password = "synthetic-test-password".toCharArray();
        MallDataAccessException failure = assertThrows(MallDataAccessException.class,
                () -> service.register("mall_test", "mall@example.com", null, password, password.clone()));
        assertEquals("USER_SAVE_FAILED", failure.code());
    }
}
