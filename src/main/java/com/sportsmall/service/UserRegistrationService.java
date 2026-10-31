package com.sportsmall.service;

import com.sportsmall.dao.mysql.UserDao;
import com.sportsmall.exception.MallBusinessException;
import com.sportsmall.model.RegisteredUser;
import com.sportsmall.util.PasswordHasher;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** 注册的业务规则：输入校验、密码哈希，再交给 DAO 保存普通用户。 */
public final class UserRegistrationService {
    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", Pattern.UNICODE_CHARACTER_CLASS);
    private final UserWriter writer;

    public UserRegistrationService(UserDao dao) {
        this(Objects.requireNonNull(dao)::create);
    }

    UserRegistrationService(UserWriter writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    public RegisteredUser register(String username, String email, String phone,
                                   char[] password, char[] confirmation) {
        String checkedUsername = requiredText(username, 50, "INVALID_USERNAME", "用户名");
        String checkedEmail = requiredText(email, 100, "INVALID_EMAIL", "邮箱");
        if (!EMAIL.matcher(checkedEmail).matches()) {
            throw new MallBusinessException("INVALID_EMAIL", "邮箱格式不正确，请填写类似 name@example.com 的地址。");
        }
        String checkedPhone = phone == null || phone.isBlank() ? null : phone.strip();
        if (checkedPhone != null && checkedPhone.codePointCount(0, checkedPhone.length()) > 20) {
            throw new MallBusinessException("INVALID_PHONE", "电话不能超过20个字符。");
        }
        if (password == null || password.length == 0) {
            throw new MallBusinessException("PASSWORD_REQUIRED", "商城密码不能为空。");
        }
        if (!Arrays.equals(password, confirmation)) {
            throw new MallBusinessException("PASSWORD_MISMATCH", "两次输入的商城密码不一致，请重新注册。");
        }
        // 密码不做 trim；DAO 接收的只有哈希，不接收明文密码。
        String passwordHash = PasswordHasher.hash(password);
        return writer.create(checkedUsername, passwordHash, checkedEmail, checkedPhone);
    }

    private static String requiredText(String value, int maximum, String code, String label) {
        String checked = value == null ? "" : value.strip();
        if (checked.isEmpty() || checked.codePointCount(0, checked.length()) > maximum) {
            throw new MallBusinessException(code, label + "不能为空，且不能超过" + maximum + "个字符。");
        }
        return checked;
    }

    @FunctionalInterface
    interface UserWriter {
        RegisteredUser create(String username, String passwordHash, String email, String phone);
    }
}
