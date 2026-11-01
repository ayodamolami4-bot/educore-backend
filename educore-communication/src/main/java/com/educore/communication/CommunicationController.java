package com.educore.communication;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommunicationController {
  private final CommunicationService s;

  public CommunicationController(CommunicationService s) {
    this.s = s;
  }

  public record Event(UUID eventId, String kind, JsonNode payload) {}

  @PostMapping("/internal/events")
  Object event(@RequestBody Event b) {
    return s.event(b.eventId(), b.kind(), b.payload());
  }

  @GetMapping("/api/v1/me/notifications")
  Object notifications(
      @RequestParam UUID schoolId,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "false") boolean unread,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "25") int limit) {
    return s.notifications(
        Actor.current(), schoolId, category, unread, cursor, Api.pageSize(limit));
  }

  @PutMapping("/api/v1/me/notifications/{id}/read")
  Object read(@PathVariable UUID id) {
    return s.read(Actor.current(), id);
  }

  @PostMapping("/api/v1/schools/{school}/notices")
  Object notice(
      @PathVariable UUID school,
      @RequestBody CommunicationService.NoticeInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.notice(Actor.current(), school, b, key);
  }

  @GetMapping("/api/v1/schools/{school}/notices")
  Object notices(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.notices(Actor.current(), school, cursor, Api.pageSize(limit));
  }

  @PostMapping("/api/v1/schools/{school}/complaints")
  Object complaint(
      @PathVariable UUID school,
      @RequestBody CommunicationService.Complaint b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.complaint(Actor.current(), school, b, key);
  }

  @GetMapping("/api/v1/schools/{school}/complaints")
  Object cases(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.cases(Actor.current(), school, cursor, Api.pageSize(limit));
  }

  @GetMapping("/api/v1/schools/{school}/complaints/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.caseDetail(Actor.current(), school, id);
  }

  @PostMapping("/api/v1/schools/{school}/complaints/{id}/replies")
  Object reply(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody CommunicationService.Reply b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.reply(Actor.current(), school, id, b, Api.version(version), key);
  }

  public record Close(String reason) {}

  @PostMapping("/api/v1/schools/{school}/complaints/{id}/close")
  Object close(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody Close b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.close(Actor.current(), school, id, b.reason(), Api.version(version), key);
  }
}
