package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class SchoolWorkspaceController {
  private final SchoolWorkspaceService s;

  public SchoolWorkspaceController(SchoolWorkspaceService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/settings")
  Object settings(@PathVariable UUID school) {
    return s.settings(Actor.current(), school);
  }

  @PutMapping("/settings")
  Object settings(
      @PathVariable UUID school,
      @RequestBody SchoolWorkspaceService.Settings b,
      @RequestHeader HttpHeaders h) {
    return s.settings(Actor.current(), school, b, version(h), key(h));
  }

  @PostMapping("/metadata-versions")
  Object metadata(
      @PathVariable UUID school,
      @RequestBody SchoolWorkspaceService.Metadata b,
      @RequestHeader HttpHeaders h) {
    return s.metadata(Actor.current(), school, b, version(h), key(h));
  }

  @GetMapping("/guardians/{id}")
  Object guardian(@PathVariable UUID school, @PathVariable UUID id) {
    return s.guardian(Actor.current(), school, id);
  }

  @PatchMapping("/guardians/{id}")
  Object guardian(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolWorkspaceService.GuardianEdit b,
      @RequestHeader HttpHeaders h) {
    return s.guardian(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/teaching-assignments")
  Object teaching(
      @PathVariable UUID school,
      @RequestBody SchoolWorkspaceService.Teaching b,
      @RequestHeader HttpHeaders h) {
    return s.teaching(Actor.current(), school, b, key(h));
  }

  @GetMapping("/terms/{id}/activities")
  Object activities(@PathVariable UUID school, @PathVariable UUID id) {
    return s.activities(Actor.current(), school, id);
  }

  @PutMapping("/terms/{id}/activities")
  Object activities(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolWorkspaceService.Activity b,
      @RequestHeader HttpHeaders h) {
    return s.activity(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/my-classes")
  Object classes(@PathVariable UUID school, @RequestParam(required = false) UUID sessionId) {
    return s.myClasses(Actor.current(), school, sessionId);
  }

  @GetMapping("/dashboard")
  Object dashboard(@PathVariable UUID school) {
    return s.dashboard(Actor.current(), school);
  }

  @GetMapping("/attendance/follow-ups")
  Object followups(@PathVariable UUID school, @RequestParam(required = false) LocalDate on) {
    return s.followUps(Actor.current(), school, on);
  }

  private ResponseEntity<byte[]> csv(byte[] bytes, String name) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .body(bytes);
  }

  @GetMapping("/students/export.csv")
  ResponseEntity<byte[]> students(
      @PathVariable UUID school, @RequestParam(required = false) UUID classId) {
    return csv(s.studentsCsv(Actor.current(), school, classId), "students.csv");
  }

  @GetMapping("/teachers/export.csv")
  ResponseEntity<byte[]> teachers(@PathVariable UUID school) {
    return csv(s.staffCsv(Actor.current(), school), "teachers.csv");
  }
}
