package com.akbank.analytics.applications.bo.webidocuments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Date;
import java.util.List;

/**
 * Oracle yazımı: SYSTEM başına DELETE + batch INSERT, tek transaction (hata olursa rollback, eski veri kalır).
 * Not: SQL metninde Türkçe karakter OLMAMALI (DB karakter seti WE8ISO8859P9, orai18n.jar yok).
 * Değerler bind ile yazılır; sonuç okuması sadece COUNT(*) (sayı, sırayla getInt(1)).
 */
final class WebiDocumentsDao {

    static final String DEFAULT_TABLE = "POPR.SDK_METADATA_WEBIDOCUMENTS_JOB";
    private static final int BATCH = 500;

    // Kolon uzunlukları (DDL ile aynı tutun; webidocuments.md). Uzun değerler kırpılır, ORA-12899 alınmaz.
    static final int LEN_CUID = 64, LEN_OWNER = 255, LEN_NAME = 500, LEN_PATH = 2000, LEN_DESCRIPTION = 4000,
            LEN_DS_CUID = 64, LEN_DS_NAME = 500, LEN_SYSTEM = 100, LEN_KEYWORD = 2000, LEN_QUERY_ID = 50,
            LEN_QUERY_NAME = 500;

    private final String table;

    WebiDocumentsDao(String table) {
        if (!table.matches("[A-Za-z0-9_$#.]+")) throw new IllegalArgumentException("Gecersiz tablo adi: " + table);
        this.table = table;
    }

    /** Mevcut satır sayısı (karşılaştırma için). */
    int count(Connection c, String system) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE SYSTEM = ?")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** SYSTEM'in satırlarını silip yenilerini yazar. Dönen: {silinen, eklenen}. */
    int[] replace(Connection c, String system, Date exportDate, List<WebiDocumentRow> rows) throws SQLException {
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int deleted;
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE SYSTEM = ?")) {
                del.setString(1, system);
                deleted = del.executeUpdate();
            }
            String sql = "INSERT INTO " + table + " (ROW_ID, SYSTEM, EXPORT_DATE, CUID, OWNER, NAME, PATH, DESCRIPTION,"
                    + " KEYWORD, CREATION_TIME, UPDATED_TIME, QUERY_ID, QUERY_NAME, DATASOURCE_CUID, DATASOURCE_NAME)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            int inserted = 0;
            Timestamp export = new Timestamp(exportDate.getTime());
            try (PreparedStatement ins = c.prepareStatement(sql)) {
                int pending = 0;
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
                    if (++pending == BATCH) {
                        ins.executeBatch();
                        inserted += pending;
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ins.executeBatch();
                    inserted += pending;
                }
            }
            c.commit();
            return new int[]{deleted, inserted};
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignore) { /* asıl hata önemli */ }
            throw e;
        } finally {
            c.setAutoCommit(oldAuto);
        }
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
