package com.educore.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PlatformTest {
  @Test
  void encryptedCommandResultsCannotBeReadWithAnotherServiceKey() {
    Crypto first = new Crypto(Base64.getEncoder().encodeToString(new byte[32]));
    byte[] other = new byte[32];
    other[0] = 1;
    Crypto second = new Crypto(Base64.getEncoder().encodeToString(other));
    String original = "A private one-time recovery code";
    String sealed = first.seal(original);
    assertFalse(sealed.contains(original));
    assertEquals(original, first.open(sealed));
    assertThrows(IllegalStateException.class, () -> second.open(sealed));
    assertNotEquals(sealed, first.seal(original));
  }

  @Test
  void invalidEncryptionKeysFailClosed() {
    assertThrows(IllegalStateException.class, () -> new Crypto("not-a-key"));
  }

  @Test
  void credentialsUseIndependentHighEntropyRandomTokens() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      String token = Crypto.token();
      assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
      assertTrue(tokens.add(token));
    }
    assertFalse(Crypto.equal("secret", null));
    assertFalse(Crypto.equal("secret", "other"));
    assertTrue(Crypto.equal("secret", "secret"));
  }

  @Test
  void staleVersionsAndMissingVersionsAreDifferentErrors() {
    assertEquals(428, assertThrows(Api.Failure.class, () -> Api.version(null)).status);
    assertEquals(422, assertThrows(Api.Failure.class, () -> Api.version("1")).status);
    assertEquals(4, Api.version("\"v4\""));
    assertEquals(412, assertThrows(Api.Failure.class, () -> Api.expectVersion(1, 2)).status);
  }

  @Test
  void schoolContextDoesNotGrantAnotherSchoolOrAnotherChild() {
    UUID school = UUID.randomUUID(), child = UUID.randomUUID();
    Actor actor =
        new Actor(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new Actor.Context(
                school,
                "School",
                "PARENT",
                child,
                "Child",
                UUID.randomUUID(),
                List.of("child.learning.read")));
    assertEquals(
        404, assertThrows(Api.Failure.class, () -> actor.school(UUID.randomUUID())).status);
    assertEquals(
        403, assertThrows(Api.Failure.class, () -> actor.school(school, "PRINCIPAL")).status);
    assertThrows(Api.Failure.class, () -> actor.child(UUID.randomUUID(), false));
    assertThrows(Api.Failure.class, () -> actor.child(child, true));
    assertDoesNotThrow(() -> actor.child(child, false));
  }

  @Test
  void phoneNamesAndEmailRejectInvalidInput() {
    assertEquals("+2348012345678", Api.phone("0801 234 5678"));
    assertThrows(Api.Failure.class, () -> Api.phone("123"));
    assertThrows(Api.Failure.class, () -> Api.personName("12345"));
    assertEquals("Oluwaseun Ade-Ola", Api.personName(" Oluwaseun Ade-Ola "));
    assertThrows(Api.Failure.class, () -> Api.email("ayo@"));
    assertEquals("ayo@example.invalid", Api.email(" AYO@example.invalid "));
  }
}
