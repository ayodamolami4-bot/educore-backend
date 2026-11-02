package com.educore.platform;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class Crypto {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  public Crypto(@Value("${educore.result-encryption-key}") String encodedKey) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Configure a base64 32-byte result encryption key.");
    }
    if (bytes.length != 32)
      throw new IllegalStateException("Result encryption key must decode to 32 bytes.");
    this.key = new SecretKeySpec(bytes, "AES");
  }

  public static String token() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public static String hashBytes(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public byte[] sealBytes(byte[] plain) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(plain);
      byte[] result = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, result, 0, nonce.length);
      System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
      return result;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Could not protect file content.", ex);
    }
  }

  public byte[] openBytes(byte[] bytes) {
    try {
      if (bytes == null || bytes.length < 28)
        throw new IllegalArgumentException("Invalid encrypted file.");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
      return cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("Could not read protected file content.", ex);
    }
  }

  public String seal(String plain) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, result, 0, nonce.length);
      System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(result);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Could not protect a command result.", ex);
    }
  }

  public String open(String sealed) {
    try {
      byte[] bytes = Base64.getDecoder().decode(sealed);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("Could not read a protected command result.", ex);
    }
  }
}
