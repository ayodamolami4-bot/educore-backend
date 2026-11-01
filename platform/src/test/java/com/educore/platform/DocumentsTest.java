package com.educore.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class DocumentsTest {
  @Test
  void csvSupportsQuotedCommasAndLineBreaksWithoutExecutingFormulas() {
    byte[] bytes =
        Documents.csv(
            List.of("Name", "Note"),
            List.of(
                List.of("Ada, Demo", "line one\nline two"),
                List.of("=HYPERLINK(\"bad\")", "safe")));
    var rows = Documents.parseCsv(new String(bytes, StandardCharsets.UTF_8));
    assertEquals("Ada, Demo", rows.get(0).get("Name"));
    assertEquals("line one\nline two", rows.get(0).get("Note"));
    assertTrue(rows.get(1).get("Name").startsWith("'="));
  }

  @Test
  void malformedAndDuplicatedHeadersAreRejected() {
    assertThrows(Api.Failure.class, () -> Documents.parseCsv("name,name\nA,B"));
    assertThrows(Api.Failure.class, () -> Documents.parseCsv("a,b\nx,y,z"));
  }

  @Test
  void reportPdfPreservesUnicodeStudentNamesAndPaginates() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("Ayọ Adésínà — report revision 2");
    for (int i = 0; i < 150; i++)
      lines.add("English: CA1 19.5; CA2 20; Exam 55; Total 94.5; Grade A");
    byte[] bytes = Documents.pdf("School report", lines);
    try (var doc = Loader.loadPDF(bytes)) {
      assertTrue(doc.getNumberOfPages() > 1);
      String text = new PDFTextStripper().getText(doc);
      assertTrue(text.contains("Ayọ Adésínà"));
      assertTrue(text.contains("revision 2"));
    }
  }
}
