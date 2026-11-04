package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class SchoolSetupController {
  private final SchoolSetupService s;

  public SchoolSetupController(SchoolSetupService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @PatchMapping("/academic-sessions/{id}")
  Object session(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolSetupService.Dates b,
      @RequestHeader HttpHeaders h) {
    return s.session(Actor.current(), school, id, b, version(h), key(h));
  }

  @PatchMapping("/terms/{id}")
  Object term(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolSetupService.Dates b,
      @RequestHeader HttpHeaders h) {
    return s.term(Actor.current(), school, id, b, version(h), key(h));
  }

  @PutMapping("/active-academic-session")
  Object active(
      @PathVariable UUID school,
      @RequestBody SchoolSetupService.ActiveSession b,
      @RequestHeader HttpHeaders h) {
    return s.activeSession(Actor.current(), school, b, version(h), key(h));
  }

  @PatchMapping("/subjects/{id}")
  Object subject(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolSetupService.Subject b,
      @RequestHeader HttpHeaders h) {
    return s.subject(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/rooms")
  Object rooms(
      @PathVariable UUID school,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.rooms(Actor.current(), school, active, cursor, Api.pageSize(limit));
  }

  @PostMapping("/rooms")
  Object room(
      @PathVariable UUID school,
      @RequestBody SchoolSetupService.Room b,
      @RequestHeader HttpHeaders h) {
    return s.room(Actor.current(), school, null, b, 0, key(h));
  }

  @PatchMapping("/rooms/{id}")
  Object room(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody SchoolSetupService.Room b,
      @RequestHeader HttpHeaders h) {
    return s.room(Actor.current(), school, id, b, version(h), key(h));
  }
}
