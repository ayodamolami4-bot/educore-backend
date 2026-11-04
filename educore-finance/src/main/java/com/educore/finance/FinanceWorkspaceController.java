package com.educore.finance;

import com.educore.platform.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class FinanceWorkspaceController {
  private final FinanceWorkspaceService workspace;
  private final PaymentSettingsService settings;

  public FinanceWorkspaceController(
      FinanceWorkspaceService workspace, PaymentSettingsService settings) {
    this.workspace = workspace;
    this.settings = settings;
  }

  @GetMapping("/invoices/fee-status")
  Object feeStatus(
      @PathVariable UUID school,
      @RequestParam UUID studentId,
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return workspace.feeStatus(
        Actor.current(), school, studentId, termId, cursor, Api.pageSize(limit));
  }

  @GetMapping("/payment-intents")
  Object attempts(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) UUID invoiceId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String reference,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return workspace.attempts(
        Actor.current(),
        school,
        studentId,
        invoiceId,
        state,
        reference,
        from,
        to,
        cursor,
        Api.pageSize(limit));
  }

  @GetMapping("/invoices/payment-settings")
  Object settings(@PathVariable UUID school) {
    return settings.view(Actor.current(), school);
  }

  @PutMapping("/invoices/payment-settings")
  Object updateSettings(
      @PathVariable UUID school,
      @RequestBody PaymentSettingsService.Update b,
      @RequestHeader HttpHeaders h) {
    return settings.update(
        Actor.current(),
        school,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/invoices/payment-settings/history")
  Object history(
      @PathVariable UUID school,
      @RequestParam(required = false) Long beforeVersion,
      @RequestParam(defaultValue = "30") int limit) {
    return settings.history(Actor.current(), school, beforeVersion, Api.pageSize(limit));
  }
}
