package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class PlanningController {
  private final PlanningService p;

  public PlanningController(PlanningService p) {
    this.p = p;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/classes/{id}/workspace")
  Object workspace(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestParam(required = false) LocalDate on) {
    return p.workspace(Actor.current(), school, id, on);
  }

  @PatchMapping("/classes/{id}")
  Object editClass(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody PlanningService.ClassEdit b,
      @RequestHeader HttpHeaders h) {
    return p.editClass(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/curricula")
  Object curriculum(
      @PathVariable UUID school,
      @RequestBody PlanningService.Curriculum b,
      @RequestHeader HttpHeaders h) {
    return p.addCurriculum(Actor.current(), school, b, key(h));
  }

  @GetMapping("/curricula")
  Object curricula(@PathVariable UUID school, @RequestParam(required = false) UUID sessionId) {
    return p.curricula(Actor.current(), school, sessionId);
  }

  @GetMapping("/curricula/{id}")
  Object curriculum(@PathVariable UUID school, @PathVariable UUID id) {
    return p.curriculum(Actor.current(), school, id);
  }

  @PostMapping("/curricula/{id}/publish")
  Object publishCurriculum(
      @PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return p.publishCurriculum(Actor.current(), school, id, version(h), key(h));
  }

  @PutMapping("/classes/{id}/curriculum")
  Object apply(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody PlanningService.CurriculumApply b,
      @RequestHeader HttpHeaders h) {
    return p.applyCurriculum(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/students/{id}/subject-choices")
  Object choices(@PathVariable UUID school, @PathVariable UUID id, @RequestParam UUID sessionId) {
    return p.choices(Actor.current(), school, id, sessionId);
  }

  @PutMapping("/students/{id}/subject-choices")
  Object choose(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody PlanningService.Choices b,
      @RequestHeader HttpHeaders h) {
    return p.choose(Actor.current(), school, id, b, version(h), key(h));
  }

  @PutMapping("/staff/{id}/teaching-scope")
  Object scope(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody PlanningService.TeacherScope b,
      @RequestHeader HttpHeaders h) {
    return p.teacherScope(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/calendar")
  Object calendar(@PathVariable UUID school, @RequestParam UUID sessionId) {
    return p.calendar(Actor.current(), school, sessionId);
  }

  @GetMapping("/calendar-plans")
  Object calendarPlans(@PathVariable UUID school, @RequestParam UUID sessionId) {
    return p.calendarPlans(Actor.current(), school, sessionId);
  }

  @PostMapping("/calendar-plans")
  Object draftCalendar(
      @PathVariable UUID school,
      @RequestBody PlanningService.CalendarInput b,
      @RequestHeader HttpHeaders h) {
    return p.draftCalendar(Actor.current(), school, b, key(h));
  }

  @PostMapping("/calendar-plans/{id}/publish")
  Object publishCalendar(
      @PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return p.publishCalendar(Actor.current(), school, id, version(h), key(h));
  }
}
