package com.educore.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.educore.platform.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaystackTest {
  @Test
  void signatureCoversOriginalBytesNotReserializedJson() throws Exception {
    String key = "sk_test_synthetic_unit_test_key";
    Paystack p = new Paystack(key, "https://api.paystack.co", new Json());
    byte[] body = "{ \"event\": \"charge.success\" }".getBytes(StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA512");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
    String signature = HexFormat.of().formatHex(mac.doFinal(body));
    assertTrue(p.signature(body, signature));
    assertFalse(
        p.signature("{\"event\":\"charge.success\"}".getBytes(StandardCharsets.UTF_8), signature));
    assertFalse(p.signature(body, null));
  }

  @Test
  void missingConfigurationOrLiveKeyNeverInitiatesADevelopmentCharge() {
    Paystack missing = new Paystack("", "https://api.paystack.co", new Json());
    assertEquals(
        "PAYSTACK_NOT_CONFIGURED",
        assertThrows(
                Api.Failure.class,
                () -> missing.initialize("ref", 1, "test@example.invalid", "ACCT_TEST"))
            .code);
    assertThrows(
        IllegalStateException.class,
        () -> new Paystack("sk_live_not_real", "https://api.paystack.co", new Json()));
    assertThrows(
        IllegalStateException.class,
        () -> new Paystack("sk_test_not_real", "https://untrusted.example.invalid", new Json()));
  }

  @Test
  void financialAmountsUseBoundedPositiveIntegerMinorUnits() {
    assertThrows(Api.Failure.class, () -> FinanceService.amount(0));
    assertThrows(Api.Failure.class, () -> FinanceService.amount(-1));
    assertThrows(Api.Failure.class, () -> FinanceService.amount(Long.MAX_VALUE));
    assertDoesNotThrow(() -> FinanceService.amount(125050));
  }
}
