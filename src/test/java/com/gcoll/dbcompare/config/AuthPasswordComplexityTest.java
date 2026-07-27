package com.gcoll.dbcompare.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthPasswordComplexityTest {

    @Test
    void defaultPassword_meetsComplexity() {
        assertTrue(AuthPasswordRules.isComplex("DbC0mpare#2026!Xk9$mQ7"));
    }

    @Test
    void weakPasswords_rejected() {
        assertFalse(AuthPasswordRules.isComplex("admin"));
        assertFalse(AuthPasswordRules.isComplex("password123"));
        assertFalse(AuthPasswordRules.isComplex("Short1!"));
        assertFalse(AuthPasswordRules.isComplex("alllowercase1!aaaa"));
        assertFalse(AuthPasswordRules.isComplex("ALLUPPERCASE1!AAAA"));
        assertFalse(AuthPasswordRules.isComplex("NoDigitsHere!!!!"));
        assertFalse(AuthPasswordRules.isComplex("NoSpecialChar12Ab"));
    }
}
