package com.educore.communication;

import com.educore.platform.*;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommunicationWorkController {
  private final CommunicationWorkService w;

  public CommunicationWorkController(CommunicationWorkService w) {
    this.w = w;
  }

  private String key(HttpHeaders h) {
    return h.getFirst("Idempotency-Key");
  }

  private long version(HttpHeaders h) {
    return Api.version(h.getFirst("If-Match"));
  }

  @GetMapping("/api/v1/schools/{school}/notices/{id}")
  Object notice(@PathVariable UUID school, @PathVariable UUID id) {
    return w.notice(Actor.current(), school, id);
  }

  @PutMapping("/api/v1/schools/{school}/notices/{id}")
  Object notice(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody CommunicationWorkService.Notice b,
      @RequestHeader HttpHeaders h) {
    return w.saveNotice(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/api/v1/schools/{school}/notices/{id}/publish")
  Object publish(@PathVariable UUID school, @PathVariable UUID id, @RequestHeader HttpHeaders h) {
    return w.publishNotice(Actor.current(), school, id, version(h), key(h));
  }

  public record Reason(String reason) {}

  @PostMapping("/api/v1/schools/{school}/notices/{id}/archive")
  Object archive(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody Reason b,
      @RequestHeader HttpHeaders h) {
    return w.archiveNotice(Actor.current(), school, id, b.reason(), version(h), key(h));
  }

  @GetMapping("/api/v1/schools/{school}/threads/contacts")
  Object contacts(@PathVariable UUID school, @RequestParam UUID studentId) {
    return w.contacts(Actor.current(), school, studentId);
  }

  @PostMapping("/api/v1/schools/{school}/threads")
  Object create(
      @PathVariable UUID school,
      @RequestBody CommunicationWorkService.ThreadInput b,
      @RequestHeader HttpHeaders h) {
    return w.createThread(Actor.current(), school, b, key(h));
  }

  @GetMapping("/api/v1/schools/{school}/threads")
  Object threads(
      @PathVariable UUID school,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "30") int limit) {
    return w.threads(Actor.current(), school, cursor, Api.pageSize(limit));
  }

  @GetMapping("/api/v1/schools/{school}/threads/{id}")
  Object thread(@PathVariable UUID school, @PathVariable UUID id) {
    return w.thread(Actor.current(), school, id);
  }

  @PostMapping("/api/v1/schools/{school}/threads/{id}/messages")
  Object message(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody CommunicationWorkService.Message b,
      @RequestHeader HttpHeaders h) {
    return w.message(Actor.current(), school, id, b, version(h), key(h));
  }

  @PostMapping("/api/v1/schools/{school}/threads/{id}/close")
  Object closeThread(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody Reason b,
      @RequestHeader HttpHeaders h) {
    return w.closeThread(Actor.current(), school, id, b.reason(), version(h), key(h));
  }

  @PutMapping("/api/v1/schools/{school}/threads/{id}/read")
  Object read(@PathVariable UUID school, @PathVariable UUID id) {
    return w.readThread(Actor.current(), school, id);
  }

  @PostMapping("/api/v1/schools/{school}/complaints/{id}/status")
  Object status(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody CommunicationWorkService.StateChange b,
      @RequestHeader HttpHeaders h) {
    return w.state(Actor.current(), school, id, b, version(h), key(h));
  }

  @PutMapping("/api/v1/schools/{school}/complaints/{id}/owner")
  Object owner(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody CommunicationWorkService.Owner b,
      @RequestHeader HttpHeaders h) {
    return w.assign(Actor.current(), school, id, b, version(h), key(h));
  }

  @GetMapping("/api/v1/schools/{school}/complaints/{id}/history")
  Object history(@PathVariable UUID school, @PathVariable UUID id) {
    return w.caseHistory(Actor.current(), school, id);
  }

  @PostMapping("/internal/file-access")
  Object file(@RequestBody CommunicationWorkService.FileAccess b) {
    return w.fileAccess(b);
  }
}
