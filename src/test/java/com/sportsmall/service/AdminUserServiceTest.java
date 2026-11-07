package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AdminUserSummary;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdminUserServiceTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(42, "admin", "ADMIN");

    @Test
    void activeAdministratorCanReadBoundedList() {
        List<AdminUserSummary> expected = List.of(new AdminUserSummary(1, "ordinary", "USER", 1));
        AdminUserService service = new AdminUserService(current -> {
            assertSame(ADMIN, current);
            return account("ADMIN", 1);
        }, limit -> {
            assertEquals(100, limit);
            return expected;
        });
        assertEquals(expected, service.listUsers(ADMIN));
    }

    @Test
    void ordinaryAccountCannotUseClaimedSessionRoleToReadList() {
        AdminUserService service = new AdminUserService(current -> account("USER", 1),
                limit -> fail("Denied callers must not query the user list"));
        assertEquals("ADMIN_REQUIRED",
                assertThrows(MallBusinessException.class, () -> service.listUsers(ADMIN)).code());
    }

    @Test
    void roleRevokedAfterLoginIsRecheckedOnNextRequest() {
        AtomicReference<UserAccount> stored = new AtomicReference<>(account("ADMIN", 1));
        UserAccountService accounts = new UserAccountService(id -> Optional.of(stored.get()),
                (id, phone) -> fail("Read-only operation"));
        AdminUserService service = new AdminUserService(accounts::viewMyAccount, limit -> List.of());
        assertTrue(service.listUsers(ADMIN).isEmpty());
        stored.set(account("USER", 1));
        assertEquals("ADMIN_REQUIRED",
                assertThrows(MallBusinessException.class, () -> service.listUsers(ADMIN)).code());
    }

    @Test
    void disabledAndDeletedAdministratorsAreDeniedBeforeListRead() {
        AtomicReference<UserAccount> stored = new AtomicReference<>(account("ADMIN", 0));
        UserAccountService accounts = new UserAccountService(id -> Optional.ofNullable(stored.get()),
                (id, phone) -> fail("Read-only operation"));
        AdminUserService service = new AdminUserService(accounts::viewMyAccount,
                limit -> fail("Inactive administrators must not query the list"));
        assertEquals("ACCOUNT_DISABLED",
                assertThrows(MallBusinessException.class, () -> service.listUsers(ADMIN)).code());
        stored.set(null);
        assertEquals("ACCOUNT_NOT_FOUND",
                assertThrows(MallBusinessException.class, () -> service.listUsers(ADMIN)).code());
    }

    @Test
    void missingLoginDoesNotReachDatabase() {
        UserAccountService accounts = new UserAccountService(id -> fail("No identity"),
                (id, phone) -> fail("No identity"));
        AdminUserService service = new AdminUserService(accounts::viewMyAccount, limit -> fail("No identity"));
        assertEquals("LOGIN_REQUIRED",
                assertThrows(MallBusinessException.class, () -> service.listUsers(null)).code());
    }

    @Test
    void databaseFailureIsNotReturnedAsAnEmptySuccessfulList() {
        AdminUserService service = new AdminUserService(current -> account("ADMIN", 1), limit -> {
            throw new MallDataAccessException("USER_LIST_FAILED", "Fixture failure");
        });
        assertEquals("USER_LIST_FAILED",
                assertThrows(MallDataAccessException.class, () -> service.listUsers(ADMIN)).code());
    }

    private static UserAccount account(String role, int status) {
        return new UserAccount(42, "admin", "admin@example.test", null, role, status);
    }
}
