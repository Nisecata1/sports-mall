package com.sportsmall.service;

import com.sportsmall.dao.mysql.ProfileDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.exception.MallDataAccessException;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.model.UserAccount;
import com.sportsmall.model.UserProfile;

import java.util.Objects;
import java.util.Optional;

/** 账号校验复用UserAccountService，所有档案操作使用后端确认的用户编号。 */
public final class ProfileService {
    private final AccountReader accounts;
    private final ProfileReader reader;
    private final ProfileWriter writer;
    private final ProfileDeleter deleter;

    public ProfileService(UserAccountService accounts, ProfileDao profiles) {
        this(Objects.requireNonNull(accounts)::viewMyAccount, Objects.requireNonNull(profiles)::findByUserId,
                profiles::save, profiles::deleteByUserId);
    }

    ProfileService(AccountReader accounts, ProfileReader reader, ProfileWriter writer, ProfileDeleter deleter) {
        this.accounts = Objects.requireNonNull(accounts);
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.deleter = Objects.requireNonNull(deleter);
    }

    public UserProfile viewMine(AuthenticatedUser currentUser) {
        UserAccount account = accounts.read(currentUser);
        UserProfile profile = reader.read(account.userId()).orElseGet(() ->
                new UserProfile(account.userId(), null, null, null, null, null));
        return checkedProfile(profile, account.userId());
    }

    public UserProfile saveMine(AuthenticatedUser currentUser, String realName, String idCard, String address, String notes) {
        String name = optionalText(realName, 50, "姓名");
        String card = optionalText(idCard, 20, "证件号码");
        String location = optionalText(address, 500, "地址");
        String note = optionalText(notes, 2000, "备注");
        UserAccount account = accounts.read(currentUser);
        UserProfile saved = checkedProfile(writer.save(account.userId(), name, card, location, note), account.userId());
        if (saved.profileId() == null || !Objects.equals(saved.realName(), name) || !Objects.equals(saved.idCard(), card)
                || !Objects.equals(saved.address(), location) || !Objects.equals(saved.notes(), note)) {
            throw new MallDataAccessException("PROFILE_SAVE_UNCONFIRMED", "档案保存结果与本次提交不一致，请重新查询。");
        }
        return saved;
    }

    public boolean deleteMine(AuthenticatedUser currentUser) {
        return deleter.delete(accounts.read(currentUser).userId());
    }

    private static String optionalText(String input, int maximum, String label) {
        String value = input == null || input.isBlank() ? null : input.strip();
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new MallBusinessException("INVALID_PROFILE_INPUT", label + "最多允许" + maximum + "个字符。");
        }
        return value;
    }

    private static UserProfile checkedProfile(UserProfile profile, long userId) {
        if (profile == null || profile.userId() != userId || (profile.profileId() != null && profile.profileId() < 1)) {
            throw new MallDataAccessException("PROFILE_DATA_INVALID", "返回的档案与当前用户不一致，请重新查询。");
        }
        return profile;
    }

    @FunctionalInterface
    interface AccountReader { UserAccount read(AuthenticatedUser currentUser); }
    @FunctionalInterface
    interface ProfileReader { Optional<UserProfile> read(long userId); }
    @FunctionalInterface
    interface ProfileWriter { UserProfile save(long userId, String name, String card, String address, String notes); }
    @FunctionalInterface
    interface ProfileDeleter { boolean delete(long userId); }
}
