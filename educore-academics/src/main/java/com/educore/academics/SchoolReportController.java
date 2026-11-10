package com.educore.academics;

import com.educore.platform.*;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schools/{school}/school-reports")
public class SchoolReportController {
  private final SchoolReportService s;

  public SchoolReportController(SchoolReportService s) {
    this.s = s;
  }

  @GetMapping("/catalogue")
  Object catalogue(@PathVariable UUID school) {
    return s.catalogue(Actor.current(), school);
  }

  @GetMapping("/{kind}")
  ResponseEntity<?> report(
      @PathVariable UUID school,
      @PathVariable String kind,
      @RequestParam(required = false) UUID sessionId,
      @RequestParam(required = false) UUID termId,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) UUID studentId,
      @RequestParam(required = false) String department,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) UUID cycleId,
      @RequestParam(required = false) UUID assignmentId,
      @RequestParam(defaultValue = "JSON") String format) {
    return s.build(
            Actor.current(),
            school,
            kind,
            new SchoolReportService.Filters(
                sessionId, termId, classId, studentId, department, from, to, cycleId, assignmentId))
        .response(format);
  }
}
