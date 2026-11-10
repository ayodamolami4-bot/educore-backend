package com.educore.finance;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class FeeAdministrationController {
  private final FeeAdministrationService f;

  public FeeAdministrationController(FeeAdministrationService f) {
    this.f = f;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @PostMapping("/fee-plans")
  Object plan(
      @PathVariable UUID school,
      @RequestBody FeeAdministrationService.Plan b,
      @RequestHeader HttpHeaders h) {
    return f.savePlan(Actor.current(), school, null, b, null, key(h));
  }

  @PutMapping("/fee-plans/{id}")
  Object plan(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FeeAdministrationService.Plan b,
      @RequestHeader HttpHeaders h) {
    return f.savePlan(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/fee-plans")
  Object plans(@PathVariable UUID school, @RequestParam(required = false) UUID termId) {
    return f.plans(Actor.current(), school, termId);
  }

  @GetMapping("/fee-plans/{id}")
  Object plan(@PathVariable UUID school, @PathVariable UUID id) {
    return f.plan(Actor.current(), school, id);
  }

  @PostMapping("/fee-plans/{id}/publish")
  Object publish(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return f.publish(Actor.current(), school, id, version(h), key(h));
  }

  @PostMapping("/fee-plans/{id}/batches")
  Object preview(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FeeAdministrationService.Preview b,
      @RequestHeader HttpHeaders h) {
    return f.preview(Actor.current(), school, id, b, key(h));
  }

  @GetMapping("/fee-batches/{id}")
  Object batch(@PathVariable UUID school, @PathVariable UUID id) {
    return f.batch(Actor.current(), school, id);
  }

  @PostMapping("/fee-batches/{id}/confirm")
  Object issue(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return f.issue(Actor.current(), school, id, version(h), key(h));
  }

  @PostMapping("/invoices/{id}/adjustments")
  Object adjustment(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FeeAdministrationService.Adjustment b,
      @RequestHeader HttpHeaders h) {
    return f.requestAdjustment(Actor.current(), school, id, b, key(h));
  }

  @GetMapping("/fee-adjustments")
  Object adjustments(@PathVariable UUID school, @RequestParam(required = false) UUID invoiceId) {
    return f.adjustments(Actor.current(), school, invoiceId);
  }

  @GetMapping("/fee-adjustments/{id}")
  Object adjustment(@PathVariable UUID school, @PathVariable UUID id) {
    return f.adjustment(Actor.current(), school, id);
  }

  @PostMapping("/fee-adjustments/{id}/decision")
  Object decide(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FeeAdministrationService.Decision b,
      @RequestHeader HttpHeaders h) {
    return f.decide(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/fee-adjustments/{id}/confirm-refund")
  Object refund(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody FeeAdministrationService.RefundConfirmation b,
      @RequestHeader HttpHeaders h) {
    return f.confirmRefund(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/payments")
  Object payments(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return f.payments(Actor.current(), school, studentId, cursor, Api.pageSize(limit));
  }

  private ResponseEntity<byte[]> pdf(byte[] value, String name) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(value);
  }

  @GetMapping("/invoices/{id}/pdf")
  ResponseEntity<byte[]> invoice(@PathVariable UUID school, @PathVariable UUID id) {
    return pdf(f.invoicePdf(Actor.current(), school, id), "invoice-" + id + ".pdf");
  }

  @GetMapping("/payments/{id}/receipt")
  ResponseEntity<byte[]> receipt(@PathVariable UUID school, @PathVariable UUID id) {
    return pdf(f.receipt(Actor.current(), school, id), "receipt-" + id + ".pdf");
  }

  @GetMapping("/invoices/export.csv")
  ResponseEntity<byte[]> export(@PathVariable UUID school) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoices.csv\"")
        .body(f.export(Actor.current(), school));
  }
}
