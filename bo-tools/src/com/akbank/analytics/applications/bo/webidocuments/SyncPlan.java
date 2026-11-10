package com.akbank.analytics.applications.bo.webidocuments;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Günlük artımlı çalışma planı: CMS'teki raporlar ile DB'deki mevcut durum karşılaştırılır.
 *   NEW       : DB'de yok                              -> REST + yaz
 *   CHANGED   : UPDATED_TIME farklı (rapor değişmiş)    -> REST + eski satırları sil, yenilerini yaz
 *   RETRY     : DB'de sorgusu boş satır var (önceki REST hatası / --no-query / sorgusuz rapor) -> tekrar dene
 *   UNCHANGED : aynı                                    -> dokunulmaz, REST çağrısı yapılmaz
 *   silinecek : DB'de var, CMS listesinde yok (rapor silinmiş / Public dışına taşınmış)
 */
final class SyncPlan {

    enum Action { NEW, CHANGED, RETRY, UNCHANGED }

    /** DB'de bir raporun özeti. */
    static final class DbState {
        final Date updatedTime;
        final int rows;
        final int emptyQueryRows;
        /** DB'deki tarih okunamadı (metin kolon, bilinmeyen biçim): rapor yeniden yazılsın. */
        final boolean forceRefresh;

        DbState(Date updatedTime, int rows, int emptyQueryRows) {
            this(updatedTime, rows, emptyQueryRows, false);
        }

        DbState(Date updatedTime, int rows, int emptyQueryRows, boolean forceRefresh) {
            this.updatedTime = updatedTime;
            this.rows = rows;
            this.emptyQueryRows = emptyQueryRows;
            this.forceRefresh = forceRefresh;
        }
    }

    final Map<WebiDocument, Action> actions = new LinkedHashMap<>();
    final List<String> toDelete = new ArrayList<>();
    final int[] counts = new int[Action.values().length];

    /**
     * @param full       true: değişmemişler de yeniden işlenir (--full)
     * @param listIsFull false ise (--limit / --folder) silme hesaplanmaz: liste eksik, eksik olan silinmiş sayılamaz
     */
    static SyncPlan build(List<WebiDocument> docs, Map<String, DbState> existing, boolean full, boolean listIsFull) {
        SyncPlan p = new SyncPlan();
        Set<String> seen = new HashSet<>();
        for (WebiDocument d : docs) {
            seen.add(d.cuid);
            DbState s = existing.get(d.cuid);
            Action a;
            if (s == null) a = Action.NEW;
            else if (s.forceRefresh || !sameSecond(s.updatedTime, d.updatedTime)) a = Action.CHANGED;
            else if (full || s.emptyQueryRows > 0) a = Action.RETRY;
            else a = Action.UNCHANGED;
            p.actions.put(d, a);
            p.counts[a.ordinal()]++;
        }
        if (listIsFull) {
            for (String cuid : existing.keySet()) {
                if (!seen.contains(cuid)) p.toDelete.add(cuid);
            }
        }
        return p;
    }

    int count(Action a) { return counts[a.ordinal()]; }

    int toProcess() { return count(Action.NEW) + count(Action.CHANGED) + count(Action.RETRY); }

    /** Oracle DATE saniye tutar; milisaniye farkı "değişmiş" sayılmasın. */
    static boolean sameSecond(Date a, Date b) {
        if (a == null || b == null) return a == b;
        return Math.floorDiv(a.getTime(), 1000L) == Math.floorDiv(b.getTime(), 1000L);
    }

    @Override
    public String toString() {
        return "yeni=" + count(Action.NEW) + ", degismis=" + count(Action.CHANGED) + ", tekrar=" + count(Action.RETRY)
                + ", degismemis=" + count(Action.UNCHANGED) + ", silinecek=" + toDelete.size();
    }
}
