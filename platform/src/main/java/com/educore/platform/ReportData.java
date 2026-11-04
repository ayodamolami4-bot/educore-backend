package com.educore.platform;

import java.time.Instant;
import java.util.*;
import org.springframework.http.*;

/** Bounded document formatting; callers authorize and select all source records. */
public record ReportData(
    String kind,
    String title,
    List<String> columns,
    List<Map<String, Object>> rows,
    List<String> notes) {
  public ReportData {
    if (rows.size() > 1000)
      throw Api.fail(422, "REPORT_TOO_LARGE", "Narrow the report filters to at most 1,000 rows.");
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
    notes = List.copyOf(notes);
  }

  public ResponseEntity<?> response(String format) {
    if ("JSON".equalsIgnoreCase(format))
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(
              Api.map(
                  "kind",
                  kind,
                  "title",
                  title,
                  "state",
                  "READY",
                  "generatedAt",
                  Instant.now(),
                  "complete",
                  true,
                  "rowCount",
                  rows.size(),
                  "columns",
                  columns,
                  "rows",
                  rows,
                  "notes",
                  notes));
    byte[] bytes;
    MediaType type;
    String suffix;
    if ("CSV".equalsIgnoreCase(format)) {
      List<List<?>> data = new ArrayList<>();
      for (var row : rows) data.add(columns.stream().map(row::get).toList());
      bytes = Documents.csv(columns, data);
      type = MediaType.parseMediaType("text/csv;charset=UTF-8");
      suffix = "csv";
    } else if ("PDF".equalsIgnoreCase(format)) {
      List<String> lines = new ArrayList<>(notes);
      lines.add("Generated: " + Instant.now() + " | Rows: " + rows.size());
      for (var row : rows) {
        lines.add("");
        for (String column : columns)
          lines.add(column + ": " + Objects.toString(row.get(column), ""));
      }
      bytes = Documents.pdf(title, lines);
      type = MediaType.APPLICATION_PDF;
      suffix = "pdf";
    } else throw Api.fail(422, "INVALID_REPORT_FORMAT", "Choose JSON, CSV or PDF.");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(type)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + kind.toLowerCase(Locale.ROOT) + "." + suffix + "\"")
        .body(bytes);
  }
}
