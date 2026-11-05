package com.educore.communication;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class DeliveryOperationsController {
  private final DeliveryOperationsService s;

  public DeliveryOperationsController(DeliveryOperationsService s) {
    this.s = s;
  }

  @GetMapping("/delivery/notifications")
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) UUID resourceId,
      @RequestParam(required = false) UUID childId,
      @RequestParam(defaultValue = "false") boolean failedOnly,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.list(
        Actor.current(),
        school,
        category,
        resourceId,
        childId,
        failedOnly,
        cursor,
        Api.pageSize(limit));
  }

  @GetMapping("/delivery/notifications/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PostMapping("/delivery/notifications/{id}/retry")
  Object retry(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody DeliveryOperationsService.Retry b,
      @RequestHeader HttpHeaders h) {
    return s.retry(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/notices/{id}/delivery-summary")
  Object notice(@PathVariable UUID school, @PathVariable UUID id) {
    return s.noticeSummary(Actor.current(), school, id);
  }
}
