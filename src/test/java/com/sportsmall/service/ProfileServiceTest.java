package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProfileServiceTest {
    private static final AuthenticatedUser USER = new AuthenticatedUser(42, "profile-user", "USER");
    private static final UserAccount ACCOUNT = new UserAccount(42, "profile-user", "profile@example.test", null, "USER", 1);

    @Test
    void accountWithoutProfileHasAnExplicitEmptyProfile() {
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                ProfileServiceTest::unexpectedSave, owner -> fail("Viewing must not delete"));
        UserProfile profile = service.viewMine(USER);
        assertEquals(42, profile.userId());
        assertNull(profile.profileId());
        assertNull(profile.realName());
    }

    @Test
    void saveUsesVerifiedIdentityAndNormalizesOptionalText() {
        ProfileService service = new ProfileService(current -> {
            assertSame(USER, current);
            return ACCOUNT;
        }, owner -> Optional.empty(), (owner, name, card, address, notes) -> {
            assertEquals(42, owner);
            assertEquals("演示用户", name);
            assertNull(card);
            assertEquals("演示地址", address);
            assertEquals("第一行\n第二行", notes);
            return new UserProfile(owner, 17L, name, card, address, notes);
        }, owner -> false);
        assertEquals(17L, service.saveMine(USER, " 演示用户 ", " ", " 演示地址 ", "第一行\n第二行").profileId());
    }

    @Test
    void blankFieldsCanBeClearedWhileTheProfileStillExists() {
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                (owner, name, card, address, notes) -> new UserProfile(owner, 17L, name, card, address, notes), owner -> false);
        UserProfile saved = service.saveMine(USER, "", null, " ", "\n");
        assertEquals(17L, saved.profileId());
        assertNull(saved.realName());
        assertNull(saved.idCard());
        assertNull(saved.address());
        assertNull(saved.notes());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void overlongFieldsAreRejectedBeforeDatabaseAccess(int field) {
        String[] values = {"姓名", "", "地址", ""};
        int[] limits = {50, 20, 500, 2000};
        values[field] = "字".repeat(limits[field] + 1);
        ProfileService service = new ProfileService(current -> fail("Invalid input reached account query"),
                owner -> Optional.empty(), ProfileServiceTest::unexpectedSave, owner -> false);
        assertEquals("INVALID_PROFILE_INPUT", assertThrows(MallBusinessException.class,
                () -> service.saveMine(USER, values[0], values[1], values[2], values[3])).code());
    }

    @Test
    void characterLimitsCountUnicodeCharactersRatherThanUtf16Units() {
        String name = "𠮷".repeat(50);
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                (owner, realName, card, address, notes) -> new UserProfile(owner, 17L, realName, card, address, notes),
                owner -> false);
        assertEquals(name, service.saveMine(USER, name, "", "", "").realName());
    }

    @Test
    void readingAnotherUsersProfileIsRejected() {
        ProfileService service = new ProfileService(current -> ACCOUNT,
                owner -> Optional.of(new UserProfile(99, 17L, "wrong", null, null, null)),
                ProfileServiceTest::unexpectedSave, owner -> false);
        assertEquals("PROFILE_DATA_INVALID", assertThrows(MallDataAccessException.class,
                () -> service.viewMine(USER)).code());
    }

    @Test
    void mismatchedSaveDoesNotBecomeSuccess() {
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                (owner, name, card, address, notes) -> new UserProfile(owner, 17L, "wrong", card, address, notes),
                owner -> false);
        assertEquals("PROFILE_SAVE_UNCONFIRMED", assertThrows(MallDataAccessException.class,
                () -> service.saveMine(USER, "演示用户", "", "", "")).code());
    }

    @Test
    void saveResultMustHaveAProfileIdAndTheCorrectOwner() {
        for (UserProfile invalid : new UserProfile[]{
                new UserProfile(42, null, "演示用户", null, null, null),
                new UserProfile(99, 17L, "演示用户", null, null, null),
                new UserProfile(42, 0L, "演示用户", null, null, null)}) {
            ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                    (owner, name, card, address, notes) -> invalid, owner -> false);
            assertThrows(MallDataAccessException.class, () -> service.saveMine(USER, "演示用户", "", "", ""));
        }
    }

    @Test
    void deleteOnlyReceivesTheAuthenticatedOwnerAndKeepsMissingResult() {
        AtomicReference<Long> deletedOwner = new AtomicReference<>();
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> Optional.empty(),
                ProfileServiceTest::unexpectedSave, owner -> {
            deletedOwner.set(owner);
            return false;
        });
        assertFalse(service.deleteMine(USER));
        assertEquals(42L, deletedOwner.get());
    }

    @Test
    void accountFailureStopsReadingSavingAndDeleting() {
        ProfileService service = new ProfileService(current -> {
            throw new MallBusinessException("ACCOUNT_DISABLED", "Disabled fixture");
        }, owner -> fail("Disabled account reached profile read"), ProfileServiceTest::unexpectedSave,
                owner -> fail("Disabled account reached profile delete"));
        assertThrows(MallBusinessException.class, () -> service.viewMine(USER));
        assertThrows(MallBusinessException.class, () -> service.saveMine(USER, "", "", "", ""));
        assertThrows(MallBusinessException.class, () -> service.deleteMine(USER));
    }

    @Test
    void readAndWriteFailuresArePreserved() {
        ProfileService service = new ProfileService(current -> ACCOUNT, owner -> {
            throw new MallDataAccessException("PROFILE_QUERY_FAILED", "Fixture read failure");
        }, (owner, name, card, address, notes) -> {
            throw new MallDataAccessException("PROFILE_SAVE_UNCONFIRMED", "Fixture write failure");
        }, owner -> false);
        assertEquals("PROFILE_QUERY_FAILED", assertThrows(MallDataAccessException.class,
                () -> service.viewMine(USER)).code());
        assertEquals("PROFILE_SAVE_UNCONFIRMED", assertThrows(MallDataAccessException.class,
                () -> service.saveMine(USER, "", "", "", "")).code());
    }

    private static UserProfile unexpectedSave(long owner, String name, String card, String address, String notes) {
        return fail("Unexpected profile save");
    }
}
