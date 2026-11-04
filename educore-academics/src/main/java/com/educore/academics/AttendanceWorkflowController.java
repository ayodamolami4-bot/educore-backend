package com.educore.academics;

import com.educore.platform.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/attendance")
public class AttendanceWorkflowController {
  private final AttendanceWorkflowService s;
  private final AttendanceFollowupService followups;

  public AttendanceWorkflowController(
      AttendanceWorkflowService s, AttendanceFollowupService followups) {
    this.s = s;
    this.followups = followups;
  }

  @GetMapping("/registers/{register}/correction-history")
  Object correctionHistory(
      @PathVariable UUID school,
      @PathVariable UUID register,
      @RequestParam(required = false) UUID studentId) {
    return s.correctionHistory(Actor.current(), school, register, studentId);
  }

  @PostMapping("/registers/{register}/correction-alerts")
  Object correctionAlerts(
      @PathVariable UUID school,
      @PathVariable UUID register,
      @RequestBody AttendanceWorkflowService.Notices b,
      @RequestHeader HttpHeaders h) {
    return s.correctionNotices(
        Actor.current(),
        school,
        register,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/registers")
  Object registers(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.registers(
        Actor.current(), school, classId, from, to, state, cursor, Api.pageSize(limit));
  }

  @PostMapping("/registers/{register}/correction-requests")
  Object request(
      @PathVariable UUID school,
      @PathVariable UUID register,
      @RequestBody AttendanceWorkflowService.Correction b,
      @RequestHeader HttpHeaders h) {
    return s.request(
        Actor.current(),
        school,
        register,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/correction-requests")
  Object requests(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.requests(Actor.current(), school, classId, state, cursor, Api.pageSize(limit));
  }

  @GetMapping("/correction-requests/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PutMapping("/correction-requests/{id}")
  Object update(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AttendanceWorkflowService.Correction b,
      @RequestHeader HttpHeaders h) {
    return s.updateRequest(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @PostMapping("/correction-requests/{id}/decision")
  Object decision(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AttendanceWorkflowService.Decision b,
      @RequestHeader HttpHeaders h) {
    return s.decide(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @PostMapping("/correction-requests/{id}/cancel")
  Object cancel(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AttendanceWorkflowService.Reason b,
      @RequestHeader HttpHeaders h) {
    return s.cancel(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/correction-requests/{id}/history")
  Object history(@PathVariable UUID school, @PathVariable UUID id) {
    return s.requestHistory(Actor.current(), school, id);
  }

  @PostMapping("/follow-up-cases")
  Object open(
      @PathVariable UUID school,
      @RequestBody AttendanceFollowupService.Open b,
      @RequestHeader HttpHeaders h) {
    return followups.open(Actor.current(), school, b, h.getFirst("Idempotency-Key"));
  }

  @GetMapping("/follow-up-cases")
  Object cases(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return followups.list(Actor.current(), school, studentId, state, cursor, Api.pageSize(limit));
  }

  @GetMapping("/follow-up-cases/{id}")
  Object oneCase(@PathVariable UUID school, @PathVariable UUID id) {
    return followups.detail(Actor.current(), school, id);
  }

  @PatchMapping("/follow-up-cases/{id}")
  Object change(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AttendanceFollowupService.Change b,
      @RequestHeader HttpHeaders h) {
    return followups.change(
        Actor.current(),
        school,
        id,
        b,
        Api.version(h.getFirst("If-Match")),
        h.getFirst("Idempotency-Key"));
  }
}
