package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}")
public class AssessmentController {
  private final AssessmentService s;

  public AssessmentController(AssessmentService s) {
    this.s = s;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/grading-policy")
  Object policy(@PathVariable UUID school) {
    return s.gradingPolicy(Actor.current(), school);
  }

  @PostMapping("/assessment-cycles/{id}/approve-policy")
  Object policy(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.PolicyApproval b,
      @RequestHeader HttpHeaders h) {
    return s.approvePolicy(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/assessment-cycles")
  Object create(
      @PathVariable UUID school,
      @RequestBody AssessmentService.CycleInput b,
      @RequestHeader HttpHeaders h) {
    return s.create(Actor.current(), school, b, key(h));
  }

  @GetMapping("/assessment-cycles")
  Object list(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID classId) {
    return s.list(Actor.current(), school, termId, classId);
  }

  @GetMapping("/assessment-cycles/{id}")
  Object detail(@PathVariable UUID school, @PathVariable UUID id) {
    return s.review(Actor.current(), school, id);
  }

  @PostMapping("/assessment-cycles/{id}/open")
  Object open(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.Window b,
      @RequestHeader HttpHeaders h) {
    return s.window(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/roster/sync")
  Object syncRoster(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.RosterSync b,
      @RequestHeader HttpHeaders h) {
    return s.syncRoster(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/assessment-cycles/{id}/sheets/{assignment}")
  Object sheet(@PathVariable UUID school, @PathVariable UUID id, @PathVariable UUID assignment) {
    return s.sheet(Actor.current(), school, id, assignment);
  }

  @PutMapping("/assessment-cycles/{id}/sheets/{assignment}/marks")
  Object marks(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID assignment,
      @RequestBody AssessmentService.Marks b,
      @RequestHeader HttpHeaders h) {
    return s.marks(Actor.current(), school, id, assignment, b, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/sheets/{assignment}/submit")
  Object submit(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID assignment,
      @RequestHeader HttpHeaders h) {
    return s.submit(Actor.current(), school, id, assignment, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/sheets/{assignment}/return")
  Object returned(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID assignment,
      @RequestBody AssessmentService.Reason b,
      @RequestHeader HttpHeaders h) {
    return s.returnSheet(Actor.current(), school, id, assignment, b, version(h), key(h));
  }

  @PutMapping("/assessment-cycles/{id}/remarks/{student}")
  Object remark(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @PathVariable UUID student,
      @RequestBody AssessmentService.Remark b,
      @RequestHeader HttpHeaders h) {
    return s.remark(Actor.current(), school, id, student, b, version(h), key(h));
  }

  @GetMapping("/assessment-cycles/{id}/review")
  Object review(@PathVariable UUID school, @PathVariable UUID id) {
    return s.review(Actor.current(), school, id);
  }

  @PostMapping("/assessment-cycles/{id}/publish")
  Object publish(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.Publication b,
      @RequestHeader HttpHeaders h) {
    return s.publish(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/submit-review")
  Object submitReview(
      @PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return s.submitReview(Actor.current(), school, id, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/return-review")
  Object returnReview(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.Reason b,
      @RequestHeader HttpHeaders h) {
    return s.returnReview(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/assessment-cycles/{id}/reopen")
  Object reopen(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody AssessmentService.Reopen b,
      @RequestHeader HttpHeaders h) {
    return s.reopen(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/students/{student}/reports")
  Object reports(@PathVariable UUID school, @PathVariable UUID student) {
    return s.reports(Actor.current(), school, student);
  }

  @GetMapping("/reports/{id}")
  Object report(@PathVariable UUID school, @PathVariable UUID id) {
    return s.report(Actor.current(), school, id);
  }

  @GetMapping("/reports/{id}/pdf")
  ResponseEntity<byte[]> pdf(@PathVariable UUID school, @PathVariable UUID id) {
    return download(
        s.pdf(Actor.current(), school, id), MediaType.APPLICATION_PDF, "report-" + id + ".pdf");
  }

  @GetMapping("/assessment-cycles/{id}/broadsheet.csv")
  ResponseEntity<byte[]> csv(@PathVariable UUID school, @PathVariable UUID id) {
    return download(
        s.broadsheet(Actor.current(), school, id),
        MediaType.parseMediaType("text/csv;charset=UTF-8"),
        "broadsheet-" + id + ".csv");
  }

  static ResponseEntity<byte[]> download(byte[] data, MediaType type, String filename) {
    return ResponseEntity.ok()
        .contentType(type)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(data);
  }
}
