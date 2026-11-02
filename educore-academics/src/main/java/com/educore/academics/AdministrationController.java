package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class AdministrationController {
  private final AdministrationService s;

  public AdministrationController(AdministrationService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/staff")
  Object staff(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return s.staffList(Actor.current(), school, cursor, Api.pageSize(limit));
  }

  @PostMapping("/staff")
  Object staff(
      @PathVariable UUID school,
      @RequestBody AdministrationService.StaffInput b,
      @RequestHeader HttpHeaders h) {
    return s.addStaff(Actor.current(), school, b, key(h));
  }

  @GetMapping("/staff/{id}")
  Object staff(@PathVariable UUID school, @PathVariable UUID id) {
    return s.staffDetail(Actor.current(), school, id);
  }

  @PatchMapping("/staff/{id}")
  Object edit(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.StaffEdit b,
      @RequestHeader HttpHeaders h) {
    return s.editStaff(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/staff/{id}/access")
  Object access(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.AccessInput b,
      @RequestHeader HttpHeaders h) {
    return s.access(Actor.current(), school, id, b, version(h), key(h));
  }

  @PutMapping("/teaching-assignments/{id}")
  Object assignment(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.AssignmentEdit b,
      @RequestHeader HttpHeaders h) {
    return s.assignment(Actor.current(), school, id, b, version(h), key(h));
  }

  @PatchMapping("/students/{id}")
  Object student(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.StudentEdit b,
      @RequestHeader HttpHeaders h) {
    return s.editStudent(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/students/{id}/transfers")
  Object transfer(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.Transfer b,
      @RequestHeader HttpHeaders h) {
    return s.transfer(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/students/{id}/withdrawal")
  Object withdrawal(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.Withdrawal b,
      @RequestHeader HttpHeaders h) {
    return s.withdraw(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/students/{id}/guardians")
  Object guardian(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AdministrationService.GuardianLink b,
      @RequestHeader HttpHeaders h) {
    return s.guardian(
        Actor.current(), school, id, b, h.getFirst("If-Match") == null ? null : version(h), key(h));
  }
}
