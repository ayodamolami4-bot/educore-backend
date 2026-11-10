package com.educore.finance;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/invoices/opening-balances")
public class OpeningBalanceController {
  private final OpeningBalanceService s;

  public OpeningBalanceController(OpeningBalanceService s) {
    this.s = s;
  }

  @PostMapping
  Object preview(
      @PathVariable UUID school,
      @RequestBody OpeningBalanceService.Preview b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.preview(Actor.current(), school, b, key);
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PostMapping("/{id}/confirm")
  Object confirm(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody OpeningBalanceService.Confirm b,
      @RequestHeader HttpHeaders h) {
    return s.confirm(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }
}
