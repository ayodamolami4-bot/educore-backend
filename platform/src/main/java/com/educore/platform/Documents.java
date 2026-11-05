package com.educore.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** File formatting only. Callers must authorise the source records before exporting. */
public final class Documents {
  private Documents() {}

  public static byte[] csv(List<String> headers, List<? extends List<?>> rows) {
    try {
      StringWriter text = new StringWriter();
      try (CSVPrinter printer = new CSVPrinter(text, CSVFormat.RFC4180)) {
        printer.printRecord(headers);
        for (var row : rows) printer.printRecord(row.stream().map(Documents::safeCell).toList());
      }
      return text.toString().getBytes(StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("CSV generation failed.", ex);
    }
  }

  static String safeCell(Object value) {
    String s = Objects.toString(value, "");
    if (!(value instanceof Number) && !s.isEmpty() && "=+-@\t\r\n".indexOf(s.charAt(0)) >= 0)
      s = "'" + s;
    return s;
  }

  public static List<Map<String, String>> parseCsv(String text) {
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
      throw Api.fail(422, "CSV_TOO_LARGE", "Use a CSV file no larger than 1 MB.");
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    try (CSVParser parser =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setAllowDuplicateHeaderNames(false)
            .get()
            .parse(new StringReader(text))) {
      if (parser.getHeaderNames().isEmpty() || parser.getHeaderNames().size() > 40)
        throw Api.fail(422, "INVALID_CSV_HEADERS", "Provide a bounded, distinct CSV header row.");
      List<Map<String, String>> rows = new ArrayList<>();
      for (CSVRecord r : parser) {
        if (rows.size() >= 1000)
          throw Api.fail(
              422, "TOO_MANY_IMPORT_ROWS", "Use at most 1,000 rows per reviewed import.");
        if (!r.isConsistent())
          throw Api.fail(422, "INVALID_CSV_ROW", "Every row must match the header columns.");
        rows.add(r.toMap());
      }
      return rows;
    } catch (IOException | IllegalArgumentException | UncheckedIOException ex) {
      throw Api.fail(
          422, "INVALID_CSV", "The CSV is malformed. Check its quotes, headers and row widths.");
    }
  }

  public static byte[] pdf(String title, List<String> lines) {
    Path fontPath =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "EDUCORE_PDF_FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
    if (!Files.isRegularFile(fontPath) && System.getenv("WINDIR") != null)
      fontPath = Path.of(System.getenv("WINDIR"), "Fonts", "arial.ttf");
    if (!Files.isRegularFile(fontPath))
      throw Api.fail(503, "PDF_FONT_NOT_CONFIGURED", "Configure the server's Unicode PDF font.");
    try (PDDocument doc = new PDDocument();
        InputStream input = Files.newInputStream(fontPath)) {
      PDType0Font font = PDType0Font.load(doc, input);
      List<String> content = new ArrayList<>();
      content.add(title);
      content.add("");
      content.addAll(lines);
      PDPageContentStream stream = null;
      float y = 0;
      int page = 0;
      try {
        for (String raw : content) {
          String line =
              Objects.toString(raw, "").replaceAll("[\\p{Cc}&&[^\\t]]", " ").replace('\t', ' ');
          List<String> wrapped = wrap(line, font, 10, 499);
          for (String part : wrapped) {
            if (stream == null || y < 55) {
              if (stream != null) stream.close();
              PDPage p = new PDPage(PDRectangle.A4);
              doc.addPage(p);
              stream = new PDPageContentStream(doc, p);
              y = 790;
              page++;
              stream.beginText();
              stream.setFont(font, 8);
              stream.newLineAtOffset(48, 28);
              stream.showText("EduCore | Page " + page);
              stream.endText();
            }
            stream.beginText();
            stream.setFont(font, 10);
            stream.newLineAtOffset(48, y);
            stream.showText(part);
            stream.endText();
            y -= 15;
          }
        }
      } finally {
        if (stream != null) stream.close();
      }
      doc.getDocumentInformation().setTitle(title);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      doc.save(bytes);
      return bytes.toByteArray();
    } catch (IOException | IllegalArgumentException ex) {
      throw Api.fail(
          503,
          "PDF_GENERATION_FAILED",
          "The document could not be generated. Contact the school office with the request reference.");
    }
  }

  private static List<String> wrap(String line, PDType0Font font, float size, float width)
      throws IOException {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int offset = 0; offset < line.length(); ) {
      int code = line.codePointAt(offset);
      String part = new String(Character.toChars(code));
      offset += Character.charCount(code);
      if (font.getStringWidth(current.toString() + part) / 1000 * size > width
          && current.length() > 0) {
        result.add(current.toString());
        current.setLength(0);
      }
      current.append(part);
    }
    result.add(current.toString());
    return result;
  }
}
