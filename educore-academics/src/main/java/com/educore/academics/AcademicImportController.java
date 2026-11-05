package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/imports")
public class AcademicImportController {
  private final AcademicImportService s;

  public AcademicImportController(AcademicImportService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/templates/{kind}")
  Object template(@PathVariable UUID school, @PathVariable String kind) {
    return s.template(Actor.current(), school, kind);
  }

  @GetMapping
  Object list(@PathVariable UUID school) {
    return s.list(Actor.current(), school);
  }

  @PostMapping
  Object preview(
      @PathVariable UUID school,
      @RequestBody AcademicImportService.Preview b,
      @RequestHeader HttpHeaders h) {
    return s.preview(Actor.current(), school, b, key(h));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.detail(Actor.current(), school, id);
  }

  @PutMapping("/{id}/rows/{row}")
  Object correct(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID row,
      @RequestBody AcademicImportService.Correction b,
      @RequestHeader HttpHeaders h) {
    return s.correct(Actor.current(), school, id, row, b, version(h), key(h));
  }

  @PostMapping("/{id}/confirm")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object confirm(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AcademicImportService.Confirmation b,
      @RequestHeader HttpHeaders h) {
    return s.confirm(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/{id}/errors.csv")
  ResponseEntity<byte[]> errors(@PathVariable UUID school, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors.csv\"")
        .body(s.errors(Actor.current(), school, id));
  }
}
