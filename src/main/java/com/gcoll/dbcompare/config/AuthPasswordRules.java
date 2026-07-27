package com.gcoll.dbcompare.config;

/**
 * 登录密码复杂度：≥12 位，含大写、小写、数字、特殊字符。
 */
public final class AuthPasswordRules {

    private AuthPasswordRules() {
    }

    public static boolean isComplex(String password) {
        if (password == null || password.length() < 12) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else if (!Character.isLetterOrDigit(c)) {
                special = true;
            }
        }
        return upper && lower && digit && special;
    }
}
