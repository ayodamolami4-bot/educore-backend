package com.educore.finance;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/reconciliation")
public class ReconciliationController {
  private final ReconciliationService r;

  public ReconciliationController(ReconciliationService r) {
    this.r = r;
  }

  @PostMapping("/statements")
  Object statement(
      @PathVariable UUID school,
      @RequestBody ReconciliationService.Statement b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return r.statement(Actor.current(), school, b, key);
  }

  @PostMapping("/paystack-sync")
  Object sync(
      @PathVariable UUID school,
      @RequestBody ReconciliationService.Period b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return r.sync(Actor.current(), school, b, key);
  }

  @GetMapping("/settlements")
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return r.list(Actor.current(), school, cursor, Api.pageSize(limit));
  }

  @GetMapping("/settlements/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return r.detail(Actor.current(), school, id);
  }

  @PostMapping("/settlements/{id}/rows/{row}/resolution")
  Object resolve(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID row,
      @RequestBody ReconciliationService.Resolution b,
      @RequestHeader HttpHeaders h) {
    return r.resolve(
        Actor.current(),
        school,
        id,
        row,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/overview")
  Object overview(@PathVariable UUID school) {
    return r.overview(Actor.current(), school);
  }
}
