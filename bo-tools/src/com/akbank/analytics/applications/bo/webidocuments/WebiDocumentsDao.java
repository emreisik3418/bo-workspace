package com.akbank.analytics.applications.bo.webidocuments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Oracle yazımı, rapor bazında (günlük artımlı çalışma için):
 *   - detectColumns  : kolon tipleri (eski tabloda tarih / ROW_ID kolonları metin olabilir; ona göre okunur / yazılır)
 *   - loadState      : SYSTEM'in raporları (CUID -> UPDATED_TIME, satır sayısı, sorgusu boş satır sayısı)
 *   - replaceDocument: bir raporun satırlarını sil + yenilerini ekle, hemen COMMIT (iş yarıda kesilse de yazılanlar kalır)
 *   - deleteDocuments: CMS'te artık olmayan raporların satırlarını sil
 * Not: SQL metninde Türkçe karakter OLMAMALI (DB karakter seti WE8ISO8859P9, orai18n.jar yok).
 * Değerler bind ile yazılır; okunan kolonlar CUID, UPDATED_TIME, QUERY_ID, ROW_ID (ASCII) — sırayla okunur.
 */
final class WebiDocumentsDao {

    static final String DEFAULT_TABLE = "POPR.SDK_METADATA_WEBIDOCUMENTS_JOB";
    private static final int DELETE_BATCH = 500;

    // Kolon uzunlukları (DDL ile aynı tutun; webidocuments.md). Uzun değerler kırpılır, ORA-12899 alınmaz.
    static final int LEN_CUID = 64, LEN_OWNER = 255, LEN_NAME = 500, LEN_PATH = 2000, LEN_DESCRIPTION = 4000,
            LEN_DS_CUID = 64, LEN_DS_NAME = 500, LEN_SYSTEM = 100, LEN_KEYWORD = 2000, LEN_QUERY_ID = 50,
            LEN_QUERY_NAME = 500;

    private final String table;

    WebiDocumentsDao(String table) {
        if (!table.matches("[A-Za-z0-9_$#.]+")) throw new IllegalArgumentException("Gecersiz tablo adi: " + table);
        this.table = table;
    }

    /** Mevcut satır sayısı. */
    int count(Connection c, String system) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE SYSTEM = ?")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Metin tipindeki kolonlar (büyük harf). detectColumns ile dolar. */
    private final Set<String> charColumns = new HashSet<>();
    /** Metin tarih kolonlarına yazarken kullanılan biçim (mevcut veriden tespit edilir). */
    private String datePattern = DateText.DEFAULT_PATTERN;

    /** loadState sonucu hakkında bilgi (log için). */
    static final class LoadInfo {
        long maxRowId;
        int rows;
        int unparsedDates;
        String sampleDate;      // UPDATED_TIME'dan örnek ham değer
        String unparsedSample;  // okunamayan bir örnek
    }

    /** Tablonun kolon tiplerini okur (tarih / ROW_ID kolonları metin olabilir). Dönen: log için özet. */
    String detectColumns(Connection c) throws SQLException {
        charColumns.clear();
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " WHERE 1 = 0");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnName(i).toUpperCase(Locale.ROOT);
                int t = md.getColumnType(i);
                boolean isChar = t == Types.CHAR || t == Types.VARCHAR || t == Types.NCHAR || t == Types.NVARCHAR
                        || t == Types.LONGVARCHAR || t == Types.LONGNVARCHAR || t == Types.CLOB || t == Types.NCLOB;
                if (isChar) charColumns.add(name);
                if (name.equals("ROW_ID") || name.endsWith("_TIME") || name.endsWith("_DATE")) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name).append('=').append(md.getColumnTypeName(i));
                }
            }
        }
        return sb.toString();
    }

    boolean isChar(String column) { return charColumns.contains(column); }

    String getDatePattern() { return datePattern; }

    void setDatePattern(String p) { datePattern = p; }

    /**
     * CUID -> {en büyük UPDATED_TIME, satır sayısı, QUERY_ID boş satır sayısı}. Satır satır okunup Java'da toplanır
     * (UPDATED_TIME metin olabilir: MAX metinde yanlış sonuç verir). Metin tarih okunamazsa rapor yeniden yazılır.
     * Metin tarih kolonları için yazma biçimi mevcut veride en sık görülen biçim olarak ayarlanır.
     */
    Map<String, SyncPlan.DbState> loadState(Connection c, String system, LoadInfo info) throws SQLException {
        boolean updatedIsChar = isChar("UPDATED_TIME");
        Map<String, Date> maxDate = new HashMap<>();
        Map<String, int[]> counts = new HashMap<>();   // {satır, sorgusu boş, okunamayan tarih}
        Map<String, Integer> patterns = new HashMap<>();
        String sql = "SELECT CUID, UPDATED_TIME, QUERY_ID, ROW_ID FROM " + table + " WHERE SYSTEM = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, system);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cuid = rs.getString(1);
                    Date updated;
                    boolean unparsed = false;
                    if (updatedIsChar) {
                        String raw = rs.getString(2);
                        if (info.sampleDate == null && raw != null && !raw.trim().isEmpty()) info.sampleDate = raw;
                        String p = DateText.patternOf(raw);
                        if (p != null) {
                            Integer n = patterns.get(p);
                            patterns.put(p, n == null ? 1 : n + 1);
                        }
                        updated = DateText.parse(raw);
                        if (updated == null && raw != null && !raw.trim().isEmpty()) {
                            unparsed = true;
                            info.unparsedDates++;
                            if (info.unparsedSample == null) info.unparsedSample = raw;
                        }
                    } else {
                        Timestamp t = rs.getTimestamp(2);
                        updated = t == null ? null : new Date(t.getTime());
                    }
                    String queryId = rs.getString(3);
                    String rowId = rs.getString(4);
                    info.rows++;
                    if (rowId != null) {
                        try {
                            info.maxRowId = Math.max(info.maxRowId, new java.math.BigDecimal(rowId.trim()).longValue());
                        } catch (NumberFormatException ignore) {
                            // sayısal olmayan ROW_ID: yok say
                        }
                    }
                    if (cuid == null) continue;
                    int[] k = counts.get(cuid);
                    if (k == null) counts.put(cuid, k = new int[3]);
                    k[0]++;
                    if (queryId == null) k[1]++;
                    if (unparsed) k[2]++;
                    Date m = maxDate.get(cuid);
                    if (updated != null && (m == null || updated.after(m))) maxDate.put(cuid, updated);
                }
            }
        }
        // Yazma biçimi: mevcut veride en sık görülen
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : patterns.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        if (best != null) datePattern = best;

        Map<String, SyncPlan.DbState> m = new HashMap<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] k = e.getValue();
            m.put(e.getKey(), new SyncPlan.DbState(maxDate.get(e.getKey()), k[0], k[1], k[2] > 0));
        }
        return m;
    }

    /** Bir raporun satırlarını değiştirir ve COMMIT eder. Dönen: {silinen, eklenen}. Hata olursa rollback. */
    int[] replaceDocument(Connection c, String system, String cuid, Date exportDate, List<WebiDocumentRow> rows)
            throws SQLException {
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int deleted;
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE SYSTEM = ? AND CUID = ?")) {
                del.setString(1, system);
                del.setString(2, cuid);
                deleted = del.executeUpdate();
            }
            String sql = "INSERT INTO " + table + " (ROW_ID, SYSTEM, EXPORT_DATE, CUID, OWNER, NAME, PATH, DESCRIPTION,"
                    + " KEYWORD, CREATION_TIME, UPDATED_TIME, QUERY_ID, QUERY_NAME, DATASOURCE_CUID, DATASOURCE_NAME)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ins = c.prepareStatement(sql)) {
                for (WebiDocumentRow r : rows) {
                    WebiDocument d = r.doc;
                    int i = 1;
                    if (isChar("ROW_ID")) ins.setString(i++, String.valueOf(r.rowId));
                    else ins.setLong(i++, r.rowId);
                    ins.setString(i++, cut(system, LEN_SYSTEM));
                    setDate(ins, i++, exportDate, "EXPORT_DATE");
                    ins.setString(i++, cut(d.cuid, LEN_CUID));
                    ins.setString(i++, cut(d.owner, LEN_OWNER));
                    ins.setString(i++, cut(d.name, LEN_NAME));
                    ins.setString(i++, cut(d.path, LEN_PATH));
                    ins.setString(i++, cut(d.description, LEN_DESCRIPTION));
                    ins.setString(i++, cut(d.keyword, LEN_KEYWORD));
                    setDate(ins, i++, d.creationTime, "CREATION_TIME");
                    setDate(ins, i++, d.updatedTime, "UPDATED_TIME");
                    ins.setString(i++, cut(r.queryId, LEN_QUERY_ID));
                    ins.setString(i++, cut(r.queryName, LEN_QUERY_NAME));
                    ins.setString(i++, cut(r.dataSourceCuid, LEN_DS_CUID));
                    ins.setString(i, cut(r.dataSourceName, LEN_DS_NAME));
                    ins.addBatch();
                }
                if (!rows.isEmpty()) ins.executeBatch();
            }
            c.commit();
            return new int[]{deleted, rows.size()};
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignore) { /* asıl hata önemli */ }
            throw e;
        } finally {
            try { c.setAutoCommit(oldAuto); } catch (SQLException ignore) { /* bağlantı kopmuş olabilir */ }
        }
    }

    /** Verilen CUID'lerin satırlarını siler (500'lük gruplar, her grup COMMIT). Dönen: silinen satır. */
    int deleteDocuments(Connection c, String system, List<String> cuids) throws SQLException {
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        int total = 0;
        try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE SYSTEM = ? AND CUID = ?")) {
            int pending = 0;
            for (String cuid : cuids) {
                del.setString(1, system);
                del.setString(2, cuid);
                del.addBatch();
                if (++pending == DELETE_BATCH) {
                    total += sum(del.executeBatch());
                    c.commit();
                    pending = 0;
                }
            }
            if (pending > 0) {
                total += sum(del.executeBatch());
                c.commit();
            }
            return total;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignore) { /* asıl hata önemli */ }
            throw e;
        } finally {
            try { c.setAutoCommit(oldAuto); } catch (SQLException ignore) { /* bağlantı kopmuş olabilir */ }
        }
    }

    /** Oracle batch sonucu SUCCESS_NO_INFO (-2) dönebilir; o durumda satır sayısı bilinmez, 1 sayılır. */
    private static int sum(int[] r) {
        int s = 0;
        for (int v : r) s += v >= 0 ? v : 1;
        return s;
    }

    /** Kolon metinse mevcut biçimde metin, değilse TIMESTAMP yazar. */
    private void setDate(PreparedStatement ps, int i, Date d, String column) throws SQLException {
        if (isChar(column)) {
            if (d == null) ps.setNull(i, Types.VARCHAR);
            else ps.setString(i, DateText.format(d, datePattern));
        } else {
            if (d == null) ps.setNull(i, Types.TIMESTAMP);
            else ps.setTimestamp(i, new Timestamp(d.getTime()));
        }
    }

    /** null/boş -> null; uzunsa kırp. */
    static String cut(String s, int max) {
        if (s == null) return null;
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
