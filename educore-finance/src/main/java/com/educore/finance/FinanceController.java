package com.educore.finance;

import com.educore.platform.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class FinanceController {
  private final FinanceService s;

  public FinanceController(FinanceService s) {
    this.s = s;
  }

  @PostMapping("/api/v1/schools/{school}/invoices")
  Object issue(
      @PathVariable UUID school,
      @RequestBody FinanceService.InvoiceInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.issue(Actor.current(), school, b, key);
  }

  @GetMapping("/api/v1/schools/{school}/invoices")
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.list(Actor.current(), school, studentId, cursor, Api.pageSize(limit));
  }

  @GetMapping("/api/v1/schools/{school}/invoices/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PostMapping("/api/v1/schools/{school}/invoices/{id}/manual-payments")
  Object manual(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FinanceService.Manual b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.manual(Actor.current(), school, id, b, Api.version(version), key);
  }

  public record Settlement(String subaccount) {}

  @PostMapping("/api/v1/schools/{school}/invoices/payment-settings")
  Object settings(
      @PathVariable UUID school,
      @RequestBody Settlement b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.setting(Actor.current(), school, b.subaccount(), key);
  }

  @PostMapping("/api/v1/schools/{school}/invoices/{id}/checkout")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  Object checkout(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FinanceService.Checkout b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.checkout(Actor.current(), school, id, b, key);
  }

  @GetMapping("/api/v1/schools/{school}/payment-intents/{id}")
  Object intent(@PathVariable UUID school, @PathVariable UUID id) {
    return s.intent(Actor.current(), school, id);
  }

  @PostMapping("/api/v1/schools/{school}/payment-intents/{id}/verify")
  Object verify(@PathVariable UUID school, @PathVariable UUID id) {
    return s.verify(Actor.current(), school, id);
  }

  @GetMapping("/api/v1/schools/{school}/reconciliation/exceptions")
  Object exceptions(@PathVariable UUID school) {
    return s.exceptions(Actor.current(), school);
  }

  @PostMapping("/api/v1/webhooks/paystack")
  Object webhook(
      @RequestBody byte[] raw,
      @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
    return s.webhook(raw, signature);
  }
}
