package com.educore.academics;

import com.educore.platform.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class SchoolController {
  private final SchoolService service;
  private final ServiceHttp http;
  private final String identity;

  public SchoolController(
      SchoolService service, ServiceHttp http, @Value("${educore.identity-url}") String identity) {
    this.service = service;
    this.http = http;
    this.identity = identity;
  }

  public record BootstrapSlip(UUID schoolId, UUID principalSubjectId, String idempotencyKey) {}

  @PostMapping("/internal/principal-activation")
  Object firstPrincipal(@RequestBody BootstrapSlip b) {
    Actor.internalOnly("operator");
    service.one(
        "select id from staff where school_id=? and id=?", b.schoolId(), b.principalSubjectId());
    return http.post(identity, "/internal/bootstrap-slip", b, Object.class);
  }

  @PostMapping("/internal/schools")
  Object bootstrap(
      @RequestBody SchoolService.Bootstrap b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.bootstrap(b, key);
  }

  @GetMapping("/api/v1/schools/{s}/metadata")
  Object metadata(@PathVariable UUID s) {
    return service.metadata(Actor.current(), s);
  }

  @PostMapping("/api/v1/schools/{s}/academic-sessions")
  Object session(
      @PathVariable UUID s,
      @RequestBody SchoolService.SessionInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.session(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/academic-sessions")
  Object sessions(@PathVariable UUID s) {
    return service.sessions(Actor.current(), s);
  }

  @PostMapping("/api/v1/schools/{s}/terms")
  Object term(
      @PathVariable UUID s,
      @RequestBody SchoolService.TermInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.term(Actor.current(), s, b, key);
  }

  @PostMapping("/api/v1/schools/{s}/classes")
  Object addClass(
      @PathVariable UUID s,
      @RequestBody SchoolService.ClassInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.addClass(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/classes")
  Object classes(@PathVariable UUID s, @RequestParam(required = false) UUID sessionId) {
    return service.classes(Actor.current(), s, sessionId);
  }

  public record AttendanceTeacher(UUID teacherId) {}

  @PutMapping("/api/v1/schools/{s}/classes/{c}/attendance-teacher")
  Object teacher(
      @PathVariable UUID s,
      @PathVariable UUID c,
      @RequestBody AttendanceTeacher b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.attendanceTeacher(
        Actor.current(), s, c, b.teacherId(), Api.version(version), key);
  }

  @PostMapping("/api/v1/schools/{s}/subjects")
  Object subject(
      @PathVariable UUID s,
      @RequestBody SchoolService.SubjectInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.subject(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/subjects")
  Object subjects(@PathVariable UUID s) {
    return service.subjects(Actor.current(), s);
  }

  @PostMapping("/api/v1/schools/{s}/teachers")
  Object teacher(
      @PathVariable UUID s,
      @RequestBody SchoolService.TeacherInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.teacher(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/teachers")
  Object teachers(
      @PathVariable UUID s,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return service.teachers(Actor.current(), s, cursor, Api.pageSize(limit));
  }

  @PostMapping("/api/v1/schools/{s}/students")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  Object enrol(
      @PathVariable UUID s,
      @RequestBody SchoolService.EnrolInput b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.enrol(Actor.current(), s, b, key);
  }

  @GetMapping("/api/v1/schools/{s}/students")
  Object students(
      @PathVariable UUID s,
      @RequestParam(required = false) UUID classId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "30") int limit) {
    return service.students(Actor.current(), s, classId, cursor, Api.pageSize(limit), search);
  }

  @GetMapping("/api/v1/schools/{s}/students/{id}")
  Object student(@PathVariable UUID s, @PathVariable UUID id) {
    return service.student(Actor.current(), s, id);
  }

  public record Reason(String reason) {}

  @PostMapping("/api/v1/schools/{s}/students/{student}/guardians/{guardian}/revoke")
  Object revoke(
      @PathVariable UUID s,
      @PathVariable UUID student,
      @PathVariable UUID guardian,
      @RequestBody Reason b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.revokeGuardian(Actor.current(), s, student, guardian, b.reason(), key);
  }

  @GetMapping("/api/v1/schools/{s}/audit")
  Object audit(
      @PathVariable UUID s,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return service.audit(Actor.current(), s, cursor, Api.pageSize(limit));
  }

  public record FieldDefinition(String key, boolean required, int maxLength) {}

  public record MetadataInput(List<FieldDefinition> studentFields, Boolean wednesdayPtEnabled) {}

  @PutMapping("/api/v1/schools/{s}/metadata")
  Object metadata(
      @PathVariable UUID s,
      @RequestBody MetadataInput b,
      @RequestHeader(value = "If-Match", required = false) String version,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    var a = Actor.current();
    a.school(s, "PRINCIPAL");
    long expected = Api.version(version);
    return service.commands.execute(
        a.accountId().toString(),
        s.toString(),
        "metadata",
        key,
        Api.map("definition", b, "version", expected),
        () -> {
          service.one("select id from schools where id=? for update", s);
          var previous =
              service.one(
                  "select version from metadata_versions where school_id=? order by version desc limit 1",
                  s);
          Api.expectVersion(expected, ((Number) previous.get("version")).longValue());
          if (b.studentFields() == null || b.studentFields().size() > 2)
            throw Api.fail(
                422,
                "INVALID_METADATA",
                "Configure the supported homeAddress and previousSchool fields.");
          Set<String> seen = new HashSet<>();
          var definitions = new ArrayList<Object>();
          for (var field : b.studentFields()) {
            if (field.key() == null
                || !Set.of("homeAddress", "previousSchool").contains(field.key())
                || !seen.add(field.key())
                || field.maxLength() < 10
                || field.maxLength() > ("homeAddress".equals(field.key()) ? 240 : 160))
              throw Api.fail(
                  422,
                  "INVALID_METADATA",
                  "Use distinct supported fields and their permitted lengths.");
            definitions.add(
                Api.map(
                    "key",
                    field.key(),
                    "type",
                    "TEXT",
                    "required",
                    field.required(),
                    "maxLength",
                    field.maxLength()));
          }
          service.db.update(
              "insert into metadata_versions(school_id,version,definition) values (?,?,?::jsonb)",
              s,
              expected + 1,
              service.json.write(Api.map("studentFields", definitions)));
          if (b.wednesdayPtEnabled() != null)
            service.db.update(
                "update schools set wednesday_pt=?,version=version+1 where id=?",
                b.wednesdayPtEnabled(),
                s);
          service.commands.audit(
              s,
              a.accountId(),
              "METADATA_PUBLISHED",
              "SCHOOL",
              s,
              null,
              Api.map("version", expected + 1));
          return service.metadata(a, s);
        });
  }

  @GetMapping("/api/v1/schools/{s}/guardians")
  Object guardians(
      @PathVariable UUID s,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    Actor.current().school(s, "PRINCIPAL", "SCHOOL_OFFICE");
    int size = Api.pageSize(limit);
    var rows =
        service.db.queryForList(
            "select id,full_name as \"fullName\",phone,email,version from guardians where school_id=? and (?::uuid is null or id>?::uuid) order by id limit ?",
            s,
            cursor,
            cursor,
            size + 1);
    return Api.page(rows, size, row -> row.get("id").toString());
  }
}
