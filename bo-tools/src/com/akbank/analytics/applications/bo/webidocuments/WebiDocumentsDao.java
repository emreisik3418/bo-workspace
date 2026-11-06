package com.akbank.analytics.applications.bo.webidocuments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle yazımı, rapor bazında (günlük artımlı çalışma için):
 *   - loadState      : SYSTEM'in raporları (CUID -> UPDATED_TIME, satır sayısı, sorgusu boş satır sayısı)
 *   - replaceDocument: bir raporun satırlarını sil + yenilerini ekle, hemen COMMIT (iş yarıda kesilse de yazılanlar kalır)
 *   - deleteDocuments: CMS'te artık olmayan raporların satırlarını sil
 * Not: SQL metninde Türkçe karakter OLMAMALI (DB karakter seti WE8ISO8859P9, orai18n.jar yok).
 * Değerler bind ile yazılır; okunan kolonlar CUID (ASCII), tarih ve sayı — sırayla (1, 2, 3) okunur.
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

    /** CUID -> {UPDATED_TIME, satır sayısı, QUERY_ID boş satır sayısı}. */
    Map<String, SyncPlan.DbState> loadState(Connection c, String system) throws SQLException {
        Map<String, SyncPlan.DbState> m = new HashMap<>();
        String sql = "SELECT CUID, MAX(UPDATED_TIME), COUNT(*), SUM(CASE WHEN QUERY_ID IS NULL THEN 1 ELSE 0 END)"
                + " FROM " + table + " WHERE SYSTEM = ? GROUP BY CUID";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, system);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cuid = rs.getString(1);
                    Timestamp t = rs.getTimestamp(2);
                    if (cuid != null) {
                        m.put(cuid, new SyncPlan.DbState(t == null ? null : new Date(t.getTime()), rs.getInt(3), rs.getInt(4)));
                    }
                }
            }
        }
        return m;
    }

    /** SYSTEM'deki en büyük ROW_ID (yeni satırlar bunun üstünden numaralanır). */
    long maxRowId(Connection c, String system) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT NVL(MAX(ROW_ID), 0) FROM " + table + " WHERE SYSTEM = ?")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
            Timestamp export = new Timestamp(exportDate.getTime());
            try (PreparedStatement ins = c.prepareStatement(sql)) {
                for (WebiDocumentRow r : rows) {
                    WebiDocument d = r.doc;
                    int i = 1;
                    ins.setLong(i++, r.rowId);
                    ins.setString(i++, cut(system, LEN_SYSTEM));
                    ins.setTimestamp(i++, export);
                    ins.setString(i++, cut(d.cuid, LEN_CUID));
                    ins.setString(i++, cut(d.owner, LEN_OWNER));
                    ins.setString(i++, cut(d.name, LEN_NAME));
                    ins.setString(i++, cut(d.path, LEN_PATH));
                    ins.setString(i++, cut(d.description, LEN_DESCRIPTION));
                    ins.setString(i++, cut(d.keyword, LEN_KEYWORD));
                    setDate(ins, i++, d.creationTime);
                    setDate(ins, i++, d.updatedTime);
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

    private static void setDate(PreparedStatement ps, int i, Date d) throws SQLException {
        if (d == null) ps.setNull(i, Types.TIMESTAMP);
        else ps.setTimestamp(i, new Timestamp(d.getTime()));
    }

    /** null/boş -> null; uzunsa kırp. */
    static String cut(String s, int max) {
        if (s == null) return null;
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
