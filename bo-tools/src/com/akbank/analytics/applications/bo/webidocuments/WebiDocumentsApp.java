package com.akbank.analytics.applications.bo.webidocuments;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.akbank.analytics.applications.bo.common.BoLogon;
import com.akbank.analytics.applications.bo.config.AppConfig;
import com.akbank.analytics.applications.bo.credential.SboxCredentialProvider;
import com.akbank.analytics.applications.bo.model.BoEnvironment;
import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.sdk.BoSession;
import com.crystaldecisions.sdk.framework.IEnterpriseSession;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public Folders altındaki Webi raporlarının sorgu (data provider) ve universe bilgilerini
 * POPR.SDK_METADATA_WEBIDOCUMENTS_JOB tablosuna yazar. Satır = raporun bir sorgusu. Günlük çalışacak şekilde ARTIMLI:
 *   - DB'de olmayan rapor        -> eklenir
 *   - UPDATED_TIME değişmiş rapor -> satırları yenilenir
 *   - sorgusu boş satırı olan    -> tekrar denenir (önceki REST hatası vb.)
 *   - değişmemiş rapor           -> dokunulmaz, REST'e gidilmez
 *   - CMS listesinde olmayan     -> DB'den silinir (sadece liste tam okunduysa)
 * Her rapor işlenir işlenmez DB'ye yazılır (rapor başına COMMIT) ve sunucuda kapatılır; iş yarıda kesilirse
 * o ana kadar yazılanlar kalır, ertesi çalıştırma kaldığı yerden devam eder (yazılanlar "değişmemiş" sayılır).
 *
 * Kullanım:
 *   WebiDocumentsApp <credentials.json> [--env HOST:6400 | --env all] [--rest http://HOST:6405/biprws]
 *                    [--folder "/Public Folders/AKBANK"] [--limit N] [--no-query] [--full] [--table SEMA.TABLO]
 *                    [--apply] [--force]
 *
 *   --env      : ortam (credentials.json system.cms); "all" = tüm ortamlar sırayla, biri hata verirse diğerleri devam eder
 *   --rest     : RESTful adresi; verilmezse credentials.json biprwsUrl, o da yoksa http://<CMS sunucusu>:6405/biprws
 *   --folder   : sadece bu klasörün altı (test için; silme adımı yapılmaz)
 *   --limit    : ilk N rapor (test için; silme adımı yapılmaz)
 *   --no-query : REST'e gitmeden sadece CMS: rapor x universe satırları, QUERY alanları boş
 *   --full     : değişmemiş raporlar da yeniden işlenir
 *   --apply    : verilmezse DRY-RUN — DB'ye yazılmaz, sadece reports\webidocuments_<HOST>_<zaman>.csv
 *   --force    : silinecek rapor mevcutların %10'unu geçse de sil
 *
 * Çıkış kodu: 0 = tüm ortamlar başarılı, 1 = kısmi (REST/DB hatası olan rapor, atlanan silme ya da hata veren ortam var),
 *             2 = hata (hiçbir ortam işlenemedi).
 */
public final class WebiDocumentsApp {

    private static final String USAGE = "Kullanım: WebiDocumentsApp <credentials.json> [--env HOST:6400 | all] [--rest URL]"
            + " [--folder \"/Public Folders/...\"] [--limit N] [--no-query] [--full] [--table SEMA.TABLO] [--apply] [--force]";

    /** Art arda bu kadar raporda REST hatası olursa (sunucu düşmüş olabilir) ortam durdurulur. */
    private static final int MAX_CONSECUTIVE_REST_FAIL = 20;
    /** Art arda bu kadar raporda DB hatası olursa ortam durdurulur. */
    private static final int MAX_CONSECUTIVE_DB_FAIL = 5;
    /** REST bağlantı testinde denenecek rapor sayısı. */
    private static final int PROBE_DOCS = 5;
    /** Silinecek rapor mevcutların bu oranını geçerse --force olmadan silinmez (liste eksik okunmuş olabilir). */
    private static final double MAX_DELETE_RATIO = 0.10;
    private static final int TOKEN_MINUTES = 12 * 60;
    private static final int TOKEN_LOGONS = 1000000;

    public static void main(String[] args) {
        int exitCode;
        RunLog log = null;
        try {
            Path base = Paths.get(System.getProperty("user.dir"));
            log = RunLog.open(base.resolve("logs"), "webidocuments");
            exitCode = run(args, base, log);
        } catch (Throwable t) {
            if (log != null) log.error("Beklenmeyen hata, uygulama durduruldu", t);
            else t.printStackTrace();
            exitCode = 2;
        } finally {
            if (log != null) log.close();
        }
        System.exit(exitCode);   // BO SDK'nın arka plan thread'leri JVM'i açık tutmasın
    }

    private static int run(String[] args, Path base, RunLog log) throws Exception {
        // ---- Parametreler
        List<String> positional = new ArrayList<>();
        String envArg = null, restArg = null, folder = null, table = WebiDocumentsDao.DEFAULT_TABLE;
        int limit = 0;
        boolean apply = false, force = false, noQuery = false, full = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--apply".equalsIgnoreCase(a)) apply = true;
            else if ("--force".equalsIgnoreCase(a)) force = true;
            else if ("--no-query".equalsIgnoreCase(a)) noQuery = true;
            else if ("--full".equalsIgnoreCase(a)) full = true;
            else if ("--env".equalsIgnoreCase(a) && i + 1 < args.length) envArg = args[++i];
            else if ("--rest".equalsIgnoreCase(a) && i + 1 < args.length) restArg = args[++i];
            else if ("--folder".equalsIgnoreCase(a) && i + 1 < args.length) folder = args[++i].trim();
            else if ("--table".equalsIgnoreCase(a) && i + 1 < args.length) table = args[++i].trim();
            else if ("--limit".equalsIgnoreCase(a) && i + 1 < args.length) limit = Integer.parseInt(args[++i].trim());
            else positional.add(a);
        }
        if (positional.size() != 1) {
            log.error(USAGE);
            log.error("1 parametre bekleniyordu, " + positional.size() + " geldi. Boşluk içeren değerleri \"tırnak\" içine alın:");
            for (int i = 0; i < positional.size(); i++) log.error("  [" + (i + 1) + "] " + positional.get(i));
            return 2;
        }
        WebiDocumentsDao dao = new WebiDocumentsDao(table);
        Date exportDate = new Date();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(exportDate);

        log.info("Başladı. Mod: " + (apply ? "APPLY (" + table + " yazılacak)" : "DRY-RUN (DB'ye yazılmayacak)")
                + (noQuery ? ", --no-query (sadece CMS)" : "") + (full ? ", --full (tüm raporlar yeniden)" : ""));
        if (folder != null) log.info("Klasör filtresi: " + folder);
        if (limit > 0) log.info("Limit: ilk " + limit + " rapor");
        if (limit > 0 || folder != null) log.info("--limit / --folder: liste eksik olacağı için silme adımı yapılmayacak.");
        log.info("Log: " + log.getPath());

        Opts o = new Opts();
        o.base = base;
        o.credentials = Paths.get(positional.get(0));
        o.restArg = restArg;
        o.folder = folder;
        o.limit = limit;
        o.apply = apply;
        o.force = force;
        o.noQuery = noQuery;
        o.full = full;
        o.table = table;
        o.dao = dao;
        o.exportDate = exportDate;
        o.stamp = stamp;
        o.config = AppConfig.load(o.credentials);

        // ---- Ortamlar: --env all -> hepsi sırayla; --env HOST:6400 -> o; verilmezse tek ortam varsa o
        List<BoEnvironment> envs = new ArrayList<>();
        if ("all".equalsIgnoreCase(envArg == null ? "" : envArg.trim())) {
            envs.addAll(o.config.getEnvironments());
        } else {
            BoEnvironment env = BoLogon.pickEnvironment(o.config, envArg);
            if (env == null) {
                log.error("Ortam seçilemedi. credentials.json'daki ortamlar: " + BoLogon.cmsList(o.config)
                        + ". --env HOST:6400 ya da hepsi için --env all verin.");
                return 2;
            }
            envs.add(env);
        }
        log.info("Ortamlar (" + envs.size() + "): " + envs);

        o.sbox = new SboxCredentialProvider(
                o.config.getSboxEndpoint(), o.config.getSboxUsername(), o.config.getSboxPassword());
        // DB: --apply'da yazmak için, dry-run'da karşılaştırma (yeni / değişmiş / silinecek) için okunur
        try {
            o.dbCredential = o.sbox.get(o.config.getDbTarget());
        } catch (Exception e) {
            if (apply) throw e;
            log.warn("DB kimlik bilgisi alınamadı, dry-run'da tüm raporlar yeni sayılacak: " + RunLog.describe(e));
        }

        // ---- Ortam ortam: biri hata verirse diğerleri devam eder
        Map<String, Integer> results = new java.util.LinkedHashMap<>();
        for (int i = 0; i < envs.size(); i++) {
            BoEnvironment env = envs.get(i);
            log.info("========== Ortam " + (i + 1) + "/" + envs.size() + ": " + env.getCms() + " ==========");
            int code;
            try {
                code = runEnv(o, env, log);
            } catch (Throwable t) {
                log.error("[" + env.getCms() + "] ortam işlenemedi, sonraki ortama geçiliyor", t);
                code = 2;
            }
            results.put(env.getCms(), code);
            log.info("[" + env.getCms() + "] bitti, sonuç: " + describeCode(code));
        }

        log.info("========== Özet ==========");
        int ok = 0, failed = 0;
        for (Map.Entry<String, Integer> e : results.entrySet()) {
            log.info("  " + e.getKey() + " : " + describeCode(e.getValue()));
            if (e.getValue() == 0) ok++;
            if (e.getValue() == 2) failed++;
        }
        if (ok == results.size()) return 0;
        if (failed == results.size()) return 2;
        return 1;
    }

    static String describeCode(int code) {
        return code == 0 ? "BASARILI (0)" : code == 1 ? "KISMI (1)" : "HATA (2)";
    }

    /** Ortamlar arasında ortak ayarlar. */
    private static final class Opts {
        Path base, credentials;
        String restArg, folder, table, stamp;
        int limit;
        boolean apply, force, noQuery, full;
        WebiDocumentsDao dao;
        Date exportDate;
        AppConfig config;
        SboxCredentialProvider sbox;
        Credential dbCredential;
    }

    /** Tek bir ortam: DB durumu -> CMS listesi -> plan -> rapor rapor REST + DB + CSV -> silme. */
    private static int runEnv(Opts o, BoEnvironment env, RunLog log) throws Exception {
        String cms = env.getCms();
        Credential boCredential = o.sbox.get(env.getSbox());

        try (Db db = o.dbCredential == null ? null : new Db(o.config.getDbJdbcUrl(), o.dbCredential)) {
            // ---- 1) DB'deki mevcut durum (uzun işten ÖNCE: tablo / yetki hatası erken çıksın)
            Map<String, SyncPlan.DbState> existing = new HashMap<>();
            long rowId = 0;
            if (db != null) {
                try {
                    existing = o.dao.loadState(db.get(), cms);
                    rowId = o.dao.maxRowId(db.get(), cms);
                    log.info("DB: " + o.table + " içinde SYSTEM=" + cms + " için mevcut rapor: " + existing.size());
                } catch (SQLException e) {
                    if (o.apply) throw e;
                    log.warn("DB okunamadı, dry-run'da tüm raporlar yeni sayılacak: " + RunLog.describe(e));
                    existing = new HashMap<>();
                }
            }

            try (BoSession session = BoLogon.logonWithRetry(cms, boCredential, log)) {
                log.info("[" + cms + "] Bağlanıldı.");
                CmsWebiReader reader = new CmsWebiReader(session.getInfoStore(), log);

                // ---- 2) CMS listesi
                CmsFolderPaths folders = reader.loadFolders();
                log.info("Klasör sayısı: " + folders.size());
                List<WebiDocument> docs = reader.listDocuments(folders, o.folder);
                if (o.limit > 0 && docs.size() > o.limit) docs = new ArrayList<>(docs.subList(0, o.limit));
                log.info("CMS'teki Webi raporu (Public Folders): " + docs.size());
                if (docs.isEmpty()) {
                    log.error("Hiç rapor bulunamadı. DB'ye dokunulmadı.");
                    return 2;
                }

                // ---- 3) Plan
                boolean listIsFull = o.folder == null && o.limit <= 0;
                SyncPlan plan = SyncPlan.build(docs, existing, o.full, listIsFull);
                log.info("Plan: " + plan);
                List<WebiDocument> work = new ArrayList<>();
                for (Map.Entry<WebiDocument, SyncPlan.Action> e : plan.actions.entrySet()) {
                    if (e.getValue() != SyncPlan.Action.UNCHANGED) work.add(e.getKey());
                }

                Path csvPath = o.base.resolve("reports").resolve("webidocuments_" + fileSafe(cms) + "_" + o.stamp + ".csv");
                try (CsvOut csv = new CsvOut(csvPath, cms, o.exportDate)) {
                    log.info("CSV (rapor rapor yazılıyor): " + csvPath);

                    // ---- 4) REST bağlantısı (işlenecek rapor varsa)
                    WebiRestClient rest = null;
                    String restMode = null;
                    if (!o.noQuery && !work.isEmpty()) {
                        String[] mode = new String[1];
                        List<Integer> probe = new ArrayList<>();
                        for (int i = 0; i < Math.min(PROBE_DOCS, work.size()); i++) probe.add(work.get(i).id);
                        rest = openRest(o, cms, session.getEnterpriseSession(), boCredential, probe, mode, log);
                        if (rest == null) return 2;
                        restMode = mode[0];
                    }

                    // ---- 5) Rapor rapor: REST -> kapat -> veri kaynağı -> DB (COMMIT) -> CSV
                    Map<Integer, String[]> dsCache = new HashMap<>();
                    int done = 0, written = 0, writtenRows = 0, kept = 0, dbFailed = 0, restFailed = 0;
                    int restStreak = 0, dbStreak = 0;
                    boolean aborted = false;
                    long started = System.currentTimeMillis();
                    try {
                        for (WebiDocument d : work) {
                            done++;
                            SyncPlan.Action action = plan.actions.get(d);
                            List<WebiDocumentRow> rows;
                            if (rest == null) {
                                rows = buildRows(d, null, null);
                            } else {
                                String restError = null;
                                List<WebiRestClient.DataProvider> dps = null;
                                try {
                                    dps = rest.dataProviders(d.id);
                                    restStreak = 0;
                                } catch (Exception e) {
                                    restFailed++;
                                    restStreak++;
                                    restError = RunLog.describe(e);
                                    log.error("[" + d.cuid + "] " + d.name + " sorguları okunamadı", e);
                                } finally {
                                    closeQuietly(rest, d.id, log);   // hata olsa da olmasa da rapor hemen kapatılır
                                }
                                if (restError == null) {
                                    rows = buildRows(d, dps, null);
                                } else if (action == SyncPlan.Action.NEW) {
                                    rows = buildRows(d, null, restError);   // yeni rapor: CMS universe bilgisiyle yaz, yarın tekrar denenir
                                } else {
                                    rows = null;                            // DB'de var: eski satırlar korunur, yarın tekrar denenir
                                    kept++;
                                    csv.doc("SKIPPED", d, "REST hatasi, DB'deki satirlar korundu: " + restError);
                                }
                                if (restStreak >= MAX_CONSECUTIVE_REST_FAIL) {
                                    log.error("Art arda " + restStreak + " raporda REST hatası: ortam durduruldu (RESTful sunucusu"
                                            + " düşmüş olabilir). Yazılanlar DB'de kaldı, silme adımı yapılmadı.");
                                    aborted = true;
                                }
                            }
                            if (rows != null) {
                                resolveDataSources(reader, rows, dsCache);
                                for (WebiDocumentRow r : rows) r.rowId = ++rowId;
                                String csvAction = action.name();
                                if (o.apply) {
                                    try {
                                        writeDocument(o, db, cms, d, rows);
                                        written++;
                                        writtenRows += rows.size();
                                        dbStreak = 0;
                                    } catch (SQLException e) {
                                        dbFailed++;
                                        dbStreak++;
                                        csvAction = "FAILED";
                                        for (WebiDocumentRow r : rows) r.note = join(r.note, "DB: " + RunLog.describe(e));
                                        log.error("[" + d.cuid + "] " + d.name + " DB'ye yazılamadı", e);
                                        if (dbStreak >= MAX_CONSECUTIVE_DB_FAIL) {
                                            log.error("Art arda " + dbStreak + " raporda DB hatası: ortam durduruldu.");
                                            aborted = true;
                                        }
                                    }
                                }
                                csv.rows(csvAction, rows);
                            }
                            if (done % 100 == 0 || done == work.size() || aborted) {
                                long sec = (System.currentTimeMillis() - started) / 1000;
                                long eta = sec * (work.size() - done) / done;
                                log.info(String.format("Rapor: %d/%d, yazılan: %d, REST hatası: %d, DB hatası: %d,"
                                                + " geçen: %d dk, kalan tahmini: %d dk",
                                        done, work.size(), written, restFailed, dbFailed, sec / 60, eta / 60));
                            }
                            if (aborted) break;
                        }
                    } finally {
                        if (rest != null && MODE_REST.equals(restMode)) {
                            try {
                                rest.logoff();
                            } catch (Exception e) {
                                log.warn("REST logoff başarısız: " + RunLog.describe(e));
                            }
                        }
                    }

                    // ---- 6) CMS'te artık olmayanları sil
                    int deleted = 0;
                    boolean deleteSkipped = false;
                    if (aborted) {
                        log.warn("Ortam yarıda kesildi: silme adımı yapılmadı.");
                    } else if (!listIsFull) {
                        log.info("--limit / --folder: silme adımı yapılmadı.");
                    } else if (!plan.toDelete.isEmpty()) {
                        int n = plan.toDelete.size();
                        if (existing.size() >= 100 && n > existing.size() * MAX_DELETE_RATIO && !o.force) {
                            deleteSkipped = true;
                            log.error(String.format("Silinecek rapor çok fazla (%d / %d mevcut). Silinmedi; CSV'de DELETE_SKIPPED"
                                    + " satırlarını kontrol edin, doğruysa --force ile çalıştırın.", n, existing.size()));
                            for (String cuid : plan.toDelete) csv.deleted("DELETE_SKIPPED", cuid, "CMS listesinde yok");
                        } else if (o.apply) {
                            deleted = deleteDocuments(o, db, cms, plan.toDelete);
                            for (String cuid : plan.toDelete) csv.deleted("DELETED", cuid, "CMS listesinde yok");
                            log.info("CMS'te artık olmayan " + n + " rapor DB'den silindi (" + deleted + " satır).");
                        } else {
                            for (String cuid : plan.toDelete) csv.deleted("DELETE", cuid, "CMS listesinde yok");
                            log.info("DRY-RUN: CMS'te artık olmayan " + n + " rapor silinecek.");
                        }
                    }

                    log.info(String.format("[%s] Sonuç: işlenen %d / %d, yazılan %d rapor (%d satır), değişmeyen %d,"
                                    + " REST hatası %d (DB'deki korunan %d), DB hatası %d, silinen rapor %d%s",
                            cms, done, work.size(), written, writtenRows, plan.count(SyncPlan.Action.UNCHANGED),
                            restFailed, kept, dbFailed, deleteSkipped ? 0 : (o.apply ? plan.toDelete.size() : 0),
                            o.apply ? "" : " (DRY-RUN: DB'ye yazılmadı)"));
                    if (aborted) return 2;
                    return restFailed > 0 || dbFailed > 0 || deleteSkipped ? 1 : 0;
                }
            }
        }
    }

    /** RESTful adresi + token yöntemi seçimi ve bağlantı testi. Bağlanılamazsa null. */
    private static WebiRestClient openRest(Opts o, String cms, IEnterpriseSession es, Credential boCredential,
                                           List<Integer> probeDocIds, String[] modeOut, RunLog log) throws IOException {
        RestEndpointConfig rc = RestEndpointConfig.read(o.credentials, cms);
        String restUrl;
        String source;
        if (o.restArg != null) {
            restUrl = WebiRestClient.normalizeBaseUrl(o.restArg);
            source = "--rest";
        } else if (rc.restUrl != null) {
            restUrl = WebiRestClient.normalizeBaseUrl(rc.restUrl);
            source = "credentials.json " + rc.source;
        } else {
            restUrl = WebiRestClient.defaultBaseUrl(cms);
            source = "varsayılan, CMS sunucusu";
        }
        String forced = System.getProperty("bo.restToken") != null ? System.getProperty("bo.restToken") : rc.restToken;
        log.info("RESTful adresi: " + restUrl + " (" + source + ")"
                + (forced != null ? ", token yöntemi: " + forced : ", token yöntemleri sırayla denenecek"));
        WebiRestClient rest = connectRest(restUrl, forced, es, boCredential, probeDocIds, modeOut, log);
        if (rest == null) {
            log.error("RESTful'a hiçbir token yöntemiyle bağlanılamadı (" + restUrl + "). Ayrıntı yukarıda."
                    + " DB'ye dokunulmadı. Sadece CMS bilgisi için --no-query ile çalıştırabilirsiniz.");
            return null;
        }
        log.info("RESTful bağlantısı tamam: " + restUrl + " (token yöntemi: " + modeOut[0] + ")");
        return rest;
    }

    private static void closeQuietly(WebiRestClient rest, int docId, RunLog log) {
        try {
            rest.closeDocument(docId);
        } catch (Exception e) {
            log.warn("[" + docId + "] doküman kapatılamadı: " + RunLog.describe(e));
        }
    }

    /** Raporun satırlarındaki veri kaynağı SI_ID'lerini CUID / ada çevirir (önbellekli; bilinmeyenler için tek CMS sorgusu). */
    private static void resolveDataSources(CmsWebiReader reader, List<WebiDocumentRow> rows, Map<Integer, String[]> cache)
            throws Exception {
        Set<Integer> unknown = new LinkedHashSet<>();
        for (WebiDocumentRow r : rows) {
            Integer id = parseId(r.dataSourceId);
            if (id != null && !cache.containsKey(id)) unknown.add(id);
        }
        if (!unknown.isEmpty()) {
            Map<Integer, String[]> found = reader.resolveDataSources(unknown);
            for (Integer id : unknown) cache.put(id, found.get(id));   // bulunamayan da (null) önbelleğe: tekrar sorulmasın
        }
        for (WebiDocumentRow r : rows) {
            Integer id = parseId(r.dataSourceId);
            String[] info = id == null ? null : cache.get(id);
            if (info != null) {
                r.dataSourceCuid = info[0];
                r.dataSourceName = info[1];
                if (r.dataSourceType == null) r.dataSourceType = info[2];
            } else if (r.dataSourceId != null) {
                r.note = join(r.note, "veri kaynagi CMS'te bulunamadi: " + r.dataSourceId);
            }
        }
    }

    /** Bağlantı koptuysa bir kez yeniden bağlanıp tekrar dener. */
    private static void writeDocument(Opts o, Db db, String cms, WebiDocument d, List<WebiDocumentRow> rows)
            throws SQLException {
        try {
            o.dao.replaceDocument(db.get(), cms, d.cuid, o.exportDate, rows);
        } catch (SQLException e) {
            db.reset();
            o.dao.replaceDocument(db.get(), cms, d.cuid, o.exportDate, rows);
        }
    }

    private static int deleteDocuments(Opts o, Db db, String cms, List<String> cuids) throws SQLException {
        try {
            return o.dao.deleteDocuments(db.get(), cms, cuids);
        } catch (SQLException e) {
            db.reset();
            return o.dao.deleteDocuments(db.get(), cms, cuids);
        }
    }

    /** Uzun işte kopabilecek DB bağlantısı: get() gerekirse yeniden bağlanır. */
    private static final class Db implements AutoCloseable {
        private final String url;
        private final Credential cred;
        private Connection c;

        Db(String url, Credential cred) {
            this.url = url;
            this.cred = cred;
        }

        Connection get() throws SQLException {
            if (c == null) c = DriverManager.getConnection(url, cred.getUsername(), cred.getPassword());
            return c;
        }

        void reset() {
            close();
        }

        @Override
        public void close() {
            if (c != null) {
                try { c.close(); } catch (SQLException ignore) { /* kapanırken hata önemsiz */ }
                c = null;
            }
        }
    }

    /** "HOST:6400" -> "HOST_6400" (dosya adı için). */
    static String fileSafe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * REST'ten gelen sorgulardan satırlar. providers null ise (REST hatası / --no-query) CMS'teki universe'lerden:
     * universe başına bir satır, QUERY alanları boş. Sorgusu / universe'ü olmayan rapor yine tek satır olarak yazılır.
     */
    static List<WebiDocumentRow> buildRows(WebiDocument d, List<WebiRestClient.DataProvider> providers, String restError) {
        List<WebiDocumentRow> out = new ArrayList<>();
        if (providers != null) {
            for (WebiRestClient.DataProvider p : providers) {
                WebiDocumentRow r = new WebiDocumentRow(d, WebiDocumentRow.S_QUERY);
                r.queryId = p.id;
                r.queryName = p.name;
                r.dataSourceId = p.dataSourceId;
                r.dataSourceType = p.dataSourceType;
                out.add(r);
            }
            if (out.isEmpty()) out.add(new WebiDocumentRow(d, WebiDocumentRow.S_NO_QUERY));
            return out;
        }
        String status = restError == null ? WebiDocumentRow.S_CMS_ONLY : WebiDocumentRow.S_REST_FAILED;
        for (Integer id : d.universeIds) {
            WebiDocumentRow r = new WebiDocumentRow(d, status);
            r.dataSourceId = String.valueOf(id);
            r.note = restError;
            out.add(r);
        }
        if (out.isEmpty()) {
            WebiDocumentRow r = new WebiDocumentRow(d, status);
            r.note = restError;
            out.add(r);
        }
        return out;
    }

    static final String MODE_LOGON = "logon";           // CMS oturumundan createLogonToken
    static final String MODE_SERIALIZED = "serialized"; // CMS oturumunun serialize hali
    static final String MODE_REST = "rest";             // REST'in kendi logon'u (/logon/long), +1 oturum

    /**
     * Token yöntemlerini sırayla dener; ilk PROBE_DOCS rapordan en az biri için dataproviders çağrısı başarılı olan
     * yöntemi döner.
     * forced (-Dbo.restToken ya da credentials.json system.restToken) verilirse sadece o denenir. Hiçbiri olmazsa null.
     */
    private static WebiRestClient connectRest(String restUrl, String forced, IEnterpriseSession es, Credential cred,
                                              List<Integer> probeDocIds, String[] modeOut, RunLog log) {
        String[] modes = forced != null ? new String[]{forced.trim().toLowerCase()}
                : new String[]{MODE_LOGON, MODE_SERIALIZED, MODE_REST};
        for (String mode : modes) {
            WebiRestClient client = null;
            try {
                WebiRestClient.TokenSource src;
                if (MODE_LOGON.equals(mode)) {
                    src = () -> es.getLogonTokenMgr().createLogonToken("", TOKEN_MINUTES, TOKEN_LOGONS);
                } else if (MODE_SERIALIZED.equals(mode)) {
                    src = es::getSerializedSession;
                } else if (MODE_REST.equals(mode)) {
                    src = () -> WebiRestClient.restLogon(restUrl, cred.getUsername(), cred.getPassword(), BoLogon.AUTH_TYPE);
                } else {
                    log.error("Bilinmeyen bo.restToken değeri: " + mode + " (logon / serialized / rest)");
                    return null;
                }
                client = new WebiRestClient(restUrl, src);
                // Tek rapor kendi içinde hatalı olabilir: ilk birkaç rapordan biri okunabiliyorsa yöntem çalışıyor sayılır
                Exception last = null;
                for (Integer id : probeDocIds) {
                    try {
                        client.dataProviders(id);
                        last = null;
                        break;
                    } catch (Exception e) {
                        last = e;
                    } finally {
                        try {
                            client.closeDocument(id);
                        } catch (Exception ignore) {
                            // kapatma hatası bağlantı testini bozmasın
                        }
                    }
                }
                if (last != null) throw last;
                modeOut[0] = mode;
                return client;
            } catch (Exception e) {
                log.warn("REST token yöntemi '" + mode + "' çalışmadı: " + RunLog.describe(e));
                if (client != null && MODE_REST.equals(mode)) {
                    try { client.logoff(); } catch (Exception ignore) { /* zaten hata var */ }
                }
            }
        }
        return null;
    }

    static Integer parseId(String s) {
        if (s == null) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String join(String a, String b) {
        return a == null || a.isEmpty() ? b : a + " | " + b;
    }
}
