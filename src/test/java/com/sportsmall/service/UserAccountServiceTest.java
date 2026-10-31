package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountServiceTest {
    private static final AuthenticatedUser CURRENT = new AuthenticatedUser(42L, "mall_test", "USER");

    @Test
    void viewAndUpdateUseLoggedInIdentityAndReadBackPersistedAccount() {
        AtomicReference<UserAccount> stored = new AtomicReference<>(account(null, 1));
        UserAccountService service = new UserAccountService(id -> {
            assertEquals(42L, id);
            return Optional.of(stored.get());
        }, (id, phone) -> {
            assertEquals(42L, id);
            assertEquals("010-12345678", phone);
            stored.set(account(phone, 1));
            return true;
        });
        assertNull(service.viewMyAccount(CURRENT).phone());
        var result = service.changeMyPhone(CURRENT, " 010-12345678 ");
        assertTrue(result.changed());
        assertEquals("010-12345678", result.account().phone());
        assertEquals(42L, result.account().userId());
        assertEquals("USER", result.account().role());
        assertEquals("mall_test@example.com", result.account().email());
    }

    @Test
    void blankPhoneClearsExistingValueToNull() {
        AtomicReference<UserAccount> stored = new AtomicReference<>(account("010-12345678", 1));
        UserAccountService service = new UserAccountService(id -> Optional.of(stored.get()), (id, phone) -> {
            assertNull(phone);
            stored.set(account(null, 1));
            return true;
        });
        assertNull(service.changeMyPhone(CURRENT, " ").account().phone());
    }

    @Test
    void unchangedPhoneDoesNotWriteOrCreateAnotherUser() {
        UserAccountService service = new UserAccountService(id -> Optional.of(account("010-12345678", 1)),
                (id, phone) -> {
                    fail("相同号码不需要再次写入");
                    return false;
                });
        assertFalse(service.changeMyPhone(CURRENT, "010-12345678").changed());
    }

    @Test
    void overlongPhoneIsRejectedBeforeAnyDatabaseAccess() {
        UserAccountService service = new UserAccountService(id -> {
            fail("超长号码不应访问数据库");
            return Optional.empty();
        }, (id, phone) -> {
            fail("超长号码不应保存");
            return false;
        });
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.changeMyPhone(CURRENT, "1".repeat(21)));
        assertEquals("INVALID_PHONE", error.code());
    }

    @Test
    void accountDisabledAfterLoginCannotBeUpdated() {
        UserAccountService service = new UserAccountService(id -> Optional.of(account(null, 0)), (id, phone) -> {
            fail("登录后已被停用的用户不能修改资料");
            return false;
        });
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.changeMyPhone(CURRENT, "010-12345678"));
        assertEquals("ACCOUNT_DISABLED", error.code());
    }

    @Test
    void missingAccountCannotBeUpdated() {
        UserAccountService service = new UserAccountService(id -> Optional.empty(), (id, phone) -> {
            fail("不存在的账号不能修改资料");
            return false;
        });
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.changeMyPhone(CURRENT, "010-12345678"));
        assertEquals("ACCOUNT_NOT_FOUND", error.code());
    }

    @Test
    void writeFailureNeverReturnsSuccessfulChange() {
        UserAccountService service = new UserAccountService(id -> Optional.of(account(null, 1)), (id, phone) -> {
            throw new MallDataAccessException("USER_UPDATE_FAILED", "修改未确认");
        });
        MallDataAccessException error = assertThrows(MallDataAccessException.class,
                () -> service.changeMyPhone(CURRENT, "010-12345678"));
        assertEquals("USER_UPDATE_FAILED", error.code());
    }

    @Test
    void zeroUpdatedRowsCannotBeReportedAsSaved() {
        UserAccountService service = new UserAccountService(id -> Optional.of(account(null, 1)),
                (id, phone) -> false);
        MallBusinessException error = assertThrows(MallBusinessException.class,
                () -> service.changeMyPhone(CURRENT, "010-12345678"));
        assertEquals("ACCOUNT_UPDATE_CONFLICT", error.code());
    }

    @Test
    void unauthenticatedCallerCannotReadOrWriteAccounts() {
        UserAccountService service = new UserAccountService(id -> {
            fail("没有当前身份不能读取用户资料");
            return Optional.empty();
        }, (id, phone) -> {
            fail("没有当前身份不能修改用户资料");
            return false;
        });
        MallBusinessException error = assertThrows(MallBusinessException.class, () -> service.viewMyAccount(null));
        assertEquals("LOGIN_REQUIRED", error.code());
    }

    @Test
    void anotherUsersRecordCannotBeDisplayedAsCurrentUser() {
        UserAccount another = new UserAccount(99L, "other_test", "other@example.com", null, "USER", 1);
        UserAccountService service = new UserAccountService(id -> Optional.of(another), (id, phone) -> {
            fail("不能覆盖其他人的资料");
            return false;
        });
        MallDataAccessException error = assertThrows(MallDataAccessException.class,
                () -> service.viewMyAccount(CURRENT));
        assertEquals("USER_DATA_INVALID", error.code());
    }

    private static UserAccount account(String phone, int status) {
        return new UserAccount(42L, "mall_test", "mall_test@example.com", phone, "USER", status);
    }
}
