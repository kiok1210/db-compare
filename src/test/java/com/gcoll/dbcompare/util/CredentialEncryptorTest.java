package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialEncryptorTest {

    private CredentialEncryptor encryptor;

    @BeforeEach
    void setUp() {
        encryptor = new CredentialEncryptor("unit-test-secret-key");
    }

    @Test
    void encryptDecrypt_roundTrip() {
        String plain = "P@ssw0rd!123";
        String encrypted = encryptor.encrypt(plain);

        assertTrue(encrypted.startsWith("ENC:"));
        assertNotEquals(plain, encrypted);
        assertEquals(plain, encryptor.decrypt(encrypted));
    }

    @Test
    void encrypt_alreadyEncrypted_returnsAsIs() {
        String stored = "ENC:abc123";
        assertEquals(stored, encryptor.encrypt(stored));
    }

    @Test
    void decrypt_plainText_returnsAsIs() {
        assertEquals("plain", encryptor.decrypt("plain"));
    }

    @Test
    void encrypt_nullOrEmpty_returnsAsIs() {
        assertEquals(null, encryptor.encrypt(null));
        assertEquals("", encryptor.encrypt(""));
    }
}
