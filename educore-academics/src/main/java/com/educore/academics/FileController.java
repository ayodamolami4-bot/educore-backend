package com.educore.academics;

import com.educore.platform.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class FileController {
  private final FileService s;

  public FileController(FileService s) {
    this.s = s;
  }

  @PostMapping("/api/v1/schools/{school}/files")
  Object create(
      @PathVariable UUID school,
      @RequestBody FileService.Metadata b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return s.create(Actor.current(), school, b, key);
  }

  @GetMapping("/api/v1/schools/{school}/files")
  Object list(@PathVariable UUID school) {
    return s.own(Actor.current(), school);
  }

  @GetMapping("/api/v1/schools/{school}/files/{id}")
  Object metadata(@PathVariable UUID school, @PathVariable UUID id) {
    return s.metadata(Actor.current(), school, id);
  }

  @PutMapping(
      value = "/api/v1/schools/{school}/files/{id}/content",
      consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  Object upload(
      @PathVariable UUID school,
      @PathVariable UUID id,
      @RequestBody byte[] content,
      @RequestHeader(value = "If-Match", required = false) String version) {
    return s.upload(Actor.current(), school, id, content, Api.version(version));
  }

  @GetMapping("/api/v1/schools/{school}/files/{id}/content")
  ResponseEntity<byte[]> download(@PathVariable UUID school, @PathVariable UUID id) {
    var file = s.download(Actor.current(), school, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.mediaType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(file.content());
  }

  @PostMapping("/internal/file-context")
  Object internal(@RequestBody FileService.InternalFile b) {
    return s.internal(b);
  }
}
