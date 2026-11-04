package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/provisioning/{type}/{subject}")
public class ProvisioningController {
  private final ProvisioningService s;

  public ProvisioningController(ProvisioningService s) {
    this.s = s;
  }

  @GetMapping
  Object status(@PathVariable UUID school, @PathVariable String type, @PathVariable UUID subject) {
    return s.status(Actor.current(), school, type, subject);
  }

  @PostMapping("/retry")
  Object retry(
      @PathVariable UUID school,
      @PathVariable String type,
      @PathVariable UUID subject,
      @RequestBody ProvisioningService.Retry b,
      @RequestHeader HttpHeaders h) {
    return s.retry(
        Actor.current(),
        school,
        type,
        subject,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }
}
