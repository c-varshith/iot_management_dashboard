package com.iot.dashboard.report;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.iot.dashboard.database.DatabaseManager;
import com.iot.dashboard.model.SensorData;
import com.iot.dashboard.model.SensorType;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

/**
 * ReportGenerator — produces structured PDF analytical reports using iText 7.
 *
 * Generation sequence:
 * 1. Execute parameterised JDBC query via DatabaseManager for the time range.
 * 2. Instantiate PdfDocument and Document layout container.
 * 3. Build header section with title, metadata, and time range.
 * 4. Build summary statistics section (AVG, MIN, MAX per sensor type).
 * 5. Build per-sensor detail tables by iterating the ResultSet data.
 * 6. Close the document — auto-flushes all PDF byte streams.
 *
 * This programmatic approach (vs JasperReports templates) provides pixel-perfect
 * control over layout — useful for dynamically sized result sets.
 */
public class ReportGenerator {

    private static final Logger LOGGER = Logger.getLogger(ReportGenerator.class.getName());

    // Brand colours for the PDF
    private static final DeviceRgb HEADER_BG    = new DeviceRgb(0x1A, 0x1A, 0x2E); // Dark navy
    private static final DeviceRgb ACCENT_BLUE  = new DeviceRgb(0x00, 0xB4, 0xD8); // Cyan accent
    private static final DeviceRgb TABLE_HEADER  = new DeviceRgb(0x02, 0x37, 0x5E); // Deep blue
    private static final DeviceRgb ROW_EVEN      = new DeviceRgb(0xF0, 0xF7, 0xFF); // Light blue tint
    private static final DeviceRgb TEXT_DARK     = new DeviceRgb(0x1A, 0x1A, 0x2E);

    private static final DateTimeFormatter DT_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HDR_FMT  = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm");

    /**
     * Generates a complete PDF energy analytics report and writes it to the given path.
     *
     * @param outputPath  absolute file system path for the output PDF
     * @param from        start of the time range to query
     * @param to          end of the time range to query
     * @throws IOException  if the PDF file cannot be written
     */
    public static void generatePdfReport(String outputPath,
                                         LocalDateTime from,
                                         LocalDateTime to) throws IOException {

        LOGGER.info("Generating PDF report: " + outputPath);

        // 1. Retrieve data from MySQL via JDBC
        List<SensorData> readings = DatabaseManager.getInstance().getReadingsByTimeRange(from, to);
        LOGGER.info("Fetched " + readings.size() + " readings for report.");

        // 2. Initialise iText 7 PDF document
        PdfWriter   writer   = new PdfWriter(outputPath);
        PdfDocument pdf      = new PdfDocument(writer);
        Document    document = new Document(pdf, PageSize.A4);
        document.setMargins(36, 36, 36, 36);

        // Load fonts
        PdfFont boldFont    = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        PdfFont regularFont = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont monoFont    = PdfFontFactory.createFont(StandardFonts.COURIER);

        // 3. Build report sections
        addReportHeader (document, boldFont, regularFont, from, to);
        addSummaryStats (document, boldFont, regularFont, readings);
        addDetailTables (document, boldFont, regularFont, monoFont, readings);
        addFooter       (document, regularFont);

        // 4. Close streams — finalises all PDF byte encoding
        document.close();
        LOGGER.info("PDF report generation complete: " + outputPath);
    }

    // =====================================================================
    // Header Section
    // =====================================================================
    private static void addReportHeader(Document doc, PdfFont bold, PdfFont regular,
                                        LocalDateTime from, LocalDateTime to) {
        // Title banner
        Table headerTable = new Table(UnitValue.createPercentArray(new float[]{1}))
                .useAllAvailableWidth()
                .setBackgroundColor(HEADER_BG)
                .setBorder(Border.NO_BORDER);

        Cell titleCell = new Cell()
                .add(new Paragraph("IoT Smart Energy Management")
                        .setFont(bold).setFontSize(22).setFontColor(ColorConstants.WHITE))
                .add(new Paragraph("Analytical Telemetry Report")
                        .setFont(regular).setFontSize(13).setFontColor(ACCENT_BLUE))
                .setBorder(Border.NO_BORDER)
                .setPadding(18);
        headerTable.addCell(titleCell);
        doc.add(headerTable);
        doc.add(new Paragraph("\n"));

        // Metadata grid
        Table metaTable = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                .useAllAvailableWidth();

        addMetaRow(metaTable, bold, regular, "Report Generated:",
                LocalDateTime.now().format(HDR_FMT));
        addMetaRow(metaTable, bold, regular, "Time Range (From):",
                from.format(HDR_FMT));
        addMetaRow(metaTable, bold, regular, "Time Range (To):",
                to.format(HDR_FMT));
        addMetaRow(metaTable, bold, regular, "System:",
                "Java IoT Energy Dashboard v1.0");

        doc.add(metaTable);
        doc.add(new Paragraph("\n"));
    }

    private static void addMetaRow(Table table, PdfFont bold, PdfFont regular,
                                   String label, String value) {
        table.addCell(new Cell()
                .add(new Paragraph(label).setFont(bold).setFontSize(9).setFontColor(TEXT_DARK))
                .setBorder(Border.NO_BORDER).setPaddingBottom(4));
        table.addCell(new Cell()
                .add(new Paragraph(value).setFont(regular).setFontSize(9).setFontColor(TEXT_DARK))
                .setBorder(Border.NO_BORDER).setPaddingBottom(4));
    }

    // =====================================================================
    // Summary Statistics Section
    // =====================================================================
    private static void addSummaryStats(Document doc, PdfFont bold, PdfFont regular,
                                        List<SensorData> readings) {
        doc.add(new Paragraph("Summary Statistics")
                .setFont(bold).setFontSize(14).setFontColor(TEXT_DARK)
                .setBorderBottom(new SolidBorder(ACCENT_BLUE, 2))
                .setPaddingBottom(4));
        doc.add(new Paragraph("\n"));

        if (readings.isEmpty()) {
            doc.add(new Paragraph("No data available for the selected time range.")
                    .setFont(regular).setFontSize(10).setFontColor(ColorConstants.GRAY));
            doc.add(new Paragraph("\n"));
            return;
        }

        // Group readings by sensor type
        Map<Integer, List<SensorData>> grouped = readings.stream()
                .collect(Collectors.groupingBy(SensorData::getTypeId));

        // Build summary table: Sensor | Unit | Count | Min | Max | Avg
        float[] widths = {3f, 1.5f, 1.5f, 2f, 2f, 2f};
        Table summaryTable = new Table(UnitValue.createPercentArray(widths))
                .useAllAvailableWidth();

        // Table header row
        String[] headers = {"Sensor Type", "Unit", "Count", "Min", "Max", "Average"};
        for (String h : headers) {
            summaryTable.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(9).setFontColor(ColorConstants.WHITE))
                    .setBackgroundColor(TABLE_HEADER)
                    .setBorder(Border.NO_BORDER)
                    .setPadding(6));
        }

        // Data rows
        boolean even = false;
        for (SensorType type : SensorType.values()) {
            List<SensorData> typeReadings = grouped.getOrDefault(type.getTypeId(), List.of());
            if (typeReadings.isEmpty()) continue;

            DoubleSummaryStatistics stats = typeReadings.stream()
                    .mapToDouble(d -> d.getValue())
                    .summaryStatistics();

            Color bg = even ? ROW_EVEN : ColorConstants.WHITE;
            even = !even;

            summaryTable.addCell(styledCell(type.getMeasurement(), regular, 9, bg));
            summaryTable.addCell(styledCell(type.getUnit(),        regular, 9, bg));
            summaryTable.addCell(styledCell(String.valueOf(stats.getCount()), regular, 9, bg));
            summaryTable.addCell(styledCell(String.format("%.2f", stats.getMin()), regular, 9, bg));
            summaryTable.addCell(styledCell(String.format("%.2f", stats.getMax()), regular, 9, bg));
            summaryTable.addCell(styledCell(String.format("%.2f", stats.getAverage()), bold, 9, bg));
        }

        doc.add(summaryTable);
        doc.add(new Paragraph("\n"));
    }

    // =====================================================================
    // Detail Tables Section (one per sensor type)
    // =====================================================================
    private static void addDetailTables(Document doc, PdfFont bold, PdfFont regular,
                                        PdfFont mono, List<SensorData> readings) {

        Map<Integer, List<SensorData>> grouped = readings.stream()
                .collect(Collectors.groupingBy(SensorData::getTypeId));

        for (SensorType type : SensorType.values()) {
            List<SensorData> typeReadings = grouped.getOrDefault(type.getTypeId(), List.of());
            if (typeReadings.isEmpty()) continue;

            // Limit to last 100 rows per sensor for report readability
            List<SensorData> displayRows = typeReadings.size() > 100
                    ? typeReadings.subList(0, 100)
                    : typeReadings;

            doc.add(new Paragraph(type.getMeasurement() + " Readings (" + type.getUnit() + ")")
                    .setFont(bold).setFontSize(12).setFontColor(TEXT_DARK)
                    .setMarginTop(12)
                    .setBorderBottom(new SolidBorder(ACCENT_BLUE, 1))
                    .setPaddingBottom(4));

            if (typeReadings.size() > 100) {
                doc.add(new Paragraph("Showing latest 100 of " + typeReadings.size() + " readings.")
                        .setFont(regular).setFontSize(8).setFontColor(ColorConstants.GRAY));
            }

            doc.add(new Paragraph("\n").setFontSize(4));

            // Build detail table: Timestamp | Value | Device
            Table detailTable = new Table(UnitValue.createPercentArray(new float[]{4, 2, 3}))
                    .useAllAvailableWidth();

            detailTable.addHeaderCell(headerCell("Timestamp", bold));
            detailTable.addHeaderCell(headerCell("Value (" + type.getUnit() + ")", bold));
            detailTable.addHeaderCell(headerCell("Device ID", bold));

            boolean rowEven = false;
            for (SensorData sd : displayRows) {
                Color bg = rowEven ? ROW_EVEN : ColorConstants.WHITE;
                rowEven = !rowEven;
                detailTable.addCell(styledCell(sd.getTimestamp().format(DT_FMT), mono, 8, bg));
                detailTable.addCell(styledCell(String.format("%.4f", sd.getValue()), regular, 8, bg));
                detailTable.addCell(styledCell("Device " + sd.getDeviceId(), regular, 8, bg));
            }

            doc.add(detailTable);
            doc.add(new Paragraph("\n"));
        }
    }

    // =====================================================================
    // Footer
    // =====================================================================
    private static void addFooter(Document doc, PdfFont regular) {
        doc.add(new Paragraph(
                "Generated by IoT Smart Energy Management Dashboard  |  " +
                LocalDateTime.now().format(DT_FMT) + "  |  Confidential")
                .setFont(regular).setFontSize(7)
                .setFontColor(ColorConstants.GRAY)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(20)
                .setBorderTop(new SolidBorder(ColorConstants.LIGHT_GRAY, 0.5f))
                .setPaddingTop(6));
    }

    // =====================================================================
    // Cell factory helpers
    // =====================================================================
    private static Cell headerCell(String text, PdfFont bold) {
        return new Cell()
                .add(new Paragraph(text).setFont(bold).setFontSize(9).setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(TABLE_HEADER)
                .setBorder(Border.NO_BORDER)
                .setPadding(5);
    }

    private static Cell styledCell(String text, PdfFont font, float size, Color bg) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(size).setFontColor(TEXT_DARK))
                .setBackgroundColor(bg)
                .setBorder(Border.NO_BORDER)
                .setPadding(4);
    }
}