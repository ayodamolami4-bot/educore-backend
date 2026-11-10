package com.educore.identity;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/onboarding/{type}/{subject}")
public class InvitationController {
  private final InvitationService s;

  public InvitationController(InvitationService s) {
    this.s = s;
  }

  @GetMapping("/invitations")
  Object list(
      @PathVariable UUID school,
      @PathVariable String type,
      @PathVariable UUID subject,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return s.list(Actor.current(), school, type, subject, cursor, Api.pageSize(limit));
  }

  @PostMapping("/invitations/{id}/revoke")
  Object revoke(
      @PathVariable UUID school,
      @PathVariable String type,
      @PathVariable UUID subject,
      @PathVariable UUID id,
      @RequestBody InvitationService.Revoke b,
      @RequestHeader HttpHeaders h) {
    return s.revoke(
        Actor.current(),
        school,
        type,
        subject,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }
}
