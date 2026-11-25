package com.akbank.analytics.applications.bo.webidocuments;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Rapor rapor yazılan CSV (her raporda flush: iş yarıda kesilse de o ana kadarki satırlar dosyada kalır).
 * Excel'de doğrudan açılsın diye UTF-8 BOM + ';' ayraç.
 * ISLEM: NEW / CHANGED / RETRY / DELETE (dry-run) / DELETED / FAILED / SKIPPED
 */
final class CsvOut implements AutoCloseable {

    static final String HEADER = "ISLEM;DURUM;ROW_ID;CUID;NAME;OWNER;PATH;DESCRIPTION;KEYWORD;CREATION_TIME;UPDATED_TIME;"
            + "QUERY_ID;QUERY_NAME;DATASOURCE_TYPE;DATASOURCE_ID;DATASOURCE_CUID;DATASOURCE_NAME;SYSTEM;EXPORT_DATE;NOT";

    private final Writer w;
    private final Path path;
    private final String system;
    private final String export;
    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    CsvOut(Path file, String system, Date exportDate) throws IOException {
        Files.createDirectories(file.getParent());
        this.path = file;
        this.system = system;
        this.export = f.format(exportDate);
        this.w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        w.write('﻿');
        w.write(HEADER + "\r\n");
        w.flush();
    }

    Path getPath() { return path; }

    void rows(String action, List<WebiDocumentRow> rows) throws IOException {
        for (WebiDocumentRow r : rows) {
            WebiDocument d = r.doc;
            line(action, r.status, r.rowId == 0 ? null : String.valueOf(r.rowId), d.cuid, d.name, d.owner, d.path,
                    d.description, d.keyword, fmt(d.creationTime), fmt(d.updatedTime), r.queryId, r.queryName,
                    r.dataSourceType, r.dataSourceId, r.dataSourceCuid, r.dataSourceName, system, export, r.note);
        }
        w.flush();
    }

    /** Sadece rapor düzeyi satır (FAILED / SKIPPED). */
    void doc(String action, WebiDocument d, String note) throws IOException {
        line(action, null, null, d.cuid, d.name, d.owner, d.path, d.description, d.keyword, fmt(d.creationTime),
                fmt(d.updatedTime), null, null, null, null, null, null, system, export, note);
        w.flush();
    }

    /** Silinen (CMS'te artık olmayan) rapor: sadece CUID bilinir. */
    void deleted(String action, String cuid, String note) throws IOException {
        line(action, null, null, cuid, null, null, null, null, null, null, null, null, null, null, null, null, null,
                system, export, note);
        w.flush();
    }

    private void line(String... cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(cell(cells[i]));
        }
        w.write(sb.append("\r\n").toString());
    }

    private String fmt(Date d) {
        return d == null ? null : f.format(d);
    }

    static String cell(String s) {
        if (s == null) return "";
        if (s.indexOf(';') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() throws IOException {
        w.close();
    }
}
