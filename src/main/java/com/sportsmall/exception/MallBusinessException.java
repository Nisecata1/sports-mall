package com.sportsmall.exception;

/** 可向用户说明的输入或业务冲突，不携带密码。 */
public final class MallBusinessException extends RuntimeException {
    private final String code;

    public MallBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
