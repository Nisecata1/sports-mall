package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.LoginAccount;
import com.sportsmall.util.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UserLoginServiceTest {
    private static final String PASSWORD = " synthetic-商城口令 ";
    private static String hash;

    @BeforeAll
    static void prepareSyntheticAccount() {
        hash = PasswordHasher.hash(PASSWORD.toCharArray());
    }

    @Test
    void correctPasswordReturnsStoredIdentityAndWritesSuccessfulLogin() {
        List<LoginAttempt> logs = new ArrayList<>();
        UserLoginService service = new UserLoginService(username -> {
            assertEquals("mall_test", username);
            return Optional.of(account("USER", 1));
        }, (id, success, ip) -> logs.add(new LoginAttempt(id, success, ip)));

        AuthenticatedUser user = service.login(" mall_test ", PASSWORD.toCharArray(), "127.0.0.1");
        assertEquals(new AuthenticatedUser(42L, "mall_test", "USER"), user);
        assertEquals(List.of(new LoginAttempt(42L, true, "127.0.0.1")), logs);
        assertFalse(user.toString().contains(hash));
    }

    @Test
    void wrongPasswordWritesFailureForIdentifiedAccountAndNeverReturnsIdentity() {
        List<LoginAttempt> logs = new ArrayList<>();
        UserLoginService service = new UserLoginService(username -> Optional.of(account("USER", 1)),
                (id, success, ip) -> logs.add(new LoginAttempt(id, success, ip)));
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.login("mall_test", "synthetic-wrong-password".toCharArray(), "127.0.0.1"));
        assertEquals("INVALID_CREDENTIALS", error.code());
        assertEquals(List.of(new LoginAttempt(42L, false, "127.0.0.1")), logs);
    }

    @Test
    void missingAccountWritesFailureWithoutInventingUserId() {
        List<LoginAttempt> logs = new ArrayList<>();
        UserLoginService service = new UserLoginService(username -> Optional.empty(),
                (id, success, ip) -> logs.add(new LoginAttempt(id, success, ip)));
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.login("missing_test_account", PASSWORD.toCharArray(), "127.0.0.1"));
        assertEquals("INVALID_CREDENTIALS", error.code());
        assertEquals(List.of(new LoginAttempt(null, false, "127.0.0.1")), logs);
    }

    @Test
    void disabledAccountCannotLogInEvenWithCorrectPassword() {
        List<LoginAttempt> logs = new ArrayList<>();
        UserLoginService service = new UserLoginService(username -> Optional.of(account("USER", 0)),
                (id, success, ip) -> logs.add(new LoginAttempt(id, success, ip)));
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.login("mall_test", PASSWORD.toCharArray(), "127.0.0.1"));
        assertEquals("ACCOUNT_DISABLED", error.code());
        assertEquals(List.of(new LoginAttempt(42L, false, "127.0.0.1")), logs);
    }

    @Test
    void mysqlFailureDoesNotBecomeAnIncorrectPasswordLog() {
        UserLoginService service = new UserLoginService(username -> {
            throw new MallDataAccessException("USER_QUERY_FAILED", "读取失败");
        }, (id, success, ip) -> fail("读库故障不是一次已核验的账号登录结果"));
        MallDataAccessException error = assertThrows(MallDataAccessException.class,
                () -> service.login("mall_test", PASSWORD.toCharArray(), "127.0.0.1"));
        assertEquals("USER_QUERY_FAILED", error.code());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logWriteFailureIsReportedSeparatelyFromCredentialResult(boolean correctPassword) {
        UserLoginService service = new UserLoginService(username -> Optional.of(account("USER", 1)),
                (id, success, ip) -> {
                    assertEquals(correctPassword, success);
                    throw new MallDataAccessException("LOGIN_LOG_SAVE_FAILED", "日志未确认保存");
                });
        char[] password = (correctPassword ? PASSWORD : "synthetic-wrong-password").toCharArray();
        MallDataAccessException error = assertThrows(MallDataAccessException.class,
                () -> service.login("mall_test", password, "127.0.0.1"));
        assertEquals("LOGIN_LOG_SAVE_FAILED", error.code());
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void incompleteInputDoesNotReachEitherDatabase(String username, char[] password, String code) {
        UserLoginService service = new UserLoginService(name -> {
            fail("基础输入检查失败，不应读取账号");
            return Optional.empty();
        }, (id, success, ip) -> fail("不应生成已核验的登录日志"));
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.login(username, password, "127.0.0.1"));
        assertEquals(code, error.code());
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of(" ", PASSWORD.toCharArray(), "INVALID_USERNAME"),
                Arguments.of("u".repeat(51), PASSWORD.toCharArray(), "INVALID_USERNAME"),
                Arguments.of("mall_test", null, "PASSWORD_REQUIRED"),
                Arguments.of("mall_test", new char[0], "PASSWORD_REQUIRED")
        );
    }

    @Test
    void invalidStoredRoleCannotProduceSuccessfulLogin() {
        UserLoginService service = new UserLoginService(username -> Optional.of(account("UNKNOWN", 1)),
                (id, success, ip) -> fail("非法身份不能记为成功登录"));
        MallDataAccessException error = assertThrows(MallDataAccessException.class,
                () -> service.login("mall_test", PASSWORD.toCharArray(), "127.0.0.1"));
        assertEquals("USER_DATA_INVALID", error.code());
    }

    private static LoginAccount account(String role, int status) {
        return new LoginAccount(42L, "mall_test", hash, role, status);
    }

    private record LoginAttempt(Long userId, boolean succeeded, String sourceIp) {
    }
}
