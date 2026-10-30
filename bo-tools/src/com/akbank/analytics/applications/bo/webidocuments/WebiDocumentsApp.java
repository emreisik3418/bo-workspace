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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Public Folders altındaki Webi raporlarının sorgu (data provider) ve universe bilgilerini
 * POPR.SDK_METADATA_WEBIDOCUMENTS_JOB tablosuna yazar. Satır = raporun bir sorgusu.
 *
 * Kullanım:
 *   WebiDocumentsApp <credentials.json> [--env HOST:6400] [--rest http://HOST:6405/biprws]
 *                    [--folder "/Public Folders/AKBANK"] [--limit N] [--no-query] [--table SEMA.TABLO]
 *                    [--apply] [--force]
 *
 *   --rest     : RESTful adresi; verilmezse http://<CMS sunucusu>:6405/biprws
 *   --folder   : sadece bu klasörün altı (test için; --apply ile kullanılamaz)
 *   --limit    : ilk N rapor (test için; --apply ile kullanılamaz)
 *   --no-query : REST'e gitmeden sadece CMS: rapor x universe satırları, QUERY alanları boş
 *   --apply    : verilmezse DRY-RUN — DB'ye yazılmaz, sadece reports\webidocuments_<zaman>.csv
 *   --force    : REST hatası %10'u geçse de DB'ye yaz
 *
 * DB: aynı SYSTEM'in satırları silinir, yenileri eklenir (tek transaction). Hiç rapor okunamadıysa DB'ye dokunulmaz.
 * Çıkış kodu: 0 = başarılı, 1 = kısmi (REST hatası olan rapor var / DB'ye yazılmadı), 2 = hata.
 */
public final class WebiDocumentsApp {

    private static final String USAGE = "Kullanım: WebiDocumentsApp <credentials.json> [--env HOST:6400] [--rest URL]"
            + " [--folder \"/Public Folders/...\"] [--limit N] [--no-query] [--table SEMA.TABLO] [--apply] [--force]";

    /** REST hatası bu oranı geçerse --force olmadan DB'ye yazılmaz. */
    private static final double MAX_REST_FAIL_RATIO = 0.10;
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
        boolean apply = false, force = false, noQuery = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--apply".equalsIgnoreCase(a)) apply = true;
            else if ("--force".equalsIgnoreCase(a)) force = true;
            else if ("--no-query".equalsIgnoreCase(a)) noQuery = true;
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
        if (apply && (limit > 0 || folder != null)) {
            log.error("--apply, --limit / --folder ile birlikte kullanılamaz: SYSTEM'in tüm satırları silinip"
                    + " sadece bir kısmı yazılırdı.");
            return 2;
        }
        WebiDocumentsDao dao = new WebiDocumentsDao(table);
        Date exportDate = new Date();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(exportDate);

        log.info("Başladı. Mod: " + (apply ? "APPLY (" + table + " yazılacak)" : "DRY-RUN (DB'ye yazılmayacak)")
                + (noQuery ? ", --no-query (sadece CMS)" : ""));
        if (folder != null) log.info("Klasör filtresi: " + folder);
        if (limit > 0) log.info("Limit: ilk " + limit + " rapor");
        log.info("Log: " + log.getPath());

        AppConfig config = AppConfig.load(Paths.get(positional.get(0)));
        BoEnvironment env = BoLogon.pickEnvironment(config, envArg);
        if (env == null) {
            log.error("Ortam seçilemedi. credentials.json'daki ortamlar: " + BoLogon.cmsList(config) + ". --env ile belirtin.");
            return 2;
        }
        String cms = env.getCms();
        SboxCredentialProvider sbox = new SboxCredentialProvider(
                config.getSboxEndpoint(), config.getSboxUsername(), config.getSboxPassword());
        Credential boCredential = sbox.get(env.getSbox());

        // --apply: DB'ye uzun işten ÖNCE bir kez bağlanıp tabloyu kontrol et (saatler sonra hata almayalım)
        Credential dbCredential = null;
        int before = -1;
        if (apply) {
            dbCredential = sbox.get(config.getDbTarget());
            try (Connection c = DriverManager.getConnection(config.getDbJdbcUrl(),
                    dbCredential.getUsername(), dbCredential.getPassword())) {
                before = dao.count(c, cms);
            }
            log.info("DB bağlantısı tamam. " + table + " içinde SYSTEM=" + cms + " için mevcut satır: " + before);
        }

        List<WebiDocumentRow> rows = new ArrayList<>();
        int restFailed = 0;
        int docCount;
        try (BoSession session = BoLogon.logonWithRetry(cms, boCredential, log)) {
            log.info("[" + cms + "] Bağlanıldı.");
            CmsWebiReader reader = new CmsWebiReader(session.getInfoStore(), log);

            // ---- 1) Klasörler + raporlar
            CmsFolderPaths folders = reader.loadFolders();
            log.info("Klasör sayısı: " + folders.size());
            List<WebiDocument> docs = reader.listDocuments(folders, folder);
            if (limit > 0 && docs.size() > limit) docs = new ArrayList<>(docs.subList(0, limit));
            docCount = docs.size();
            log.info("İşlenecek Webi raporu: " + docCount);
            if (docs.isEmpty()) {
                log.error("Hiç rapor bulunamadı. DB'ye dokunulmadı.");
                return 2;
            }

            // ---- 2) Sorgular (REST)
            WebiRestClient rest = null;
            String restMode = null;
            if (!noQuery) {
                String restUrl = restArg != null ? restArg : WebiRestClient.defaultBaseUrl(cms);
                String[] mode = new String[1];
                rest = connectRest(restUrl, session.getEnterpriseSession(), boCredential, docs.get(0).id, mode, log);
                if (rest == null) {
                    log.error("RESTful'a hiçbir token yöntemiyle bağlanılamadı (" + restUrl + "). Ayrıntı yukarıda."
                            + " Sadece CMS bilgisi için --no-query ile çalıştırabilirsiniz.");
                    return 2;
                }
                restMode = mode[0];
                log.info("RESTful bağlantısı tamam: " + restUrl + " (token yöntemi: " + restMode + ")");
            }
            long started = System.currentTimeMillis();
            int done = 0, closeFailed = 0;
            for (WebiDocument d : docs) {
                done++;
                if (rest == null) {
                    rows.addAll(buildRows(d, null, null));
                } else {
                    try {
                        rows.addAll(buildRows(d, rest.dataProviders(d.id), null));
                    } catch (Exception e) {
                        restFailed++;
                        rows.addAll(buildRows(d, null, RunLog.describe(e)));
                        log.error("[" + d.cuid + "] " + d.name + " sorguları okunamadı (CMS universe bilgisi yazılacak)", e);
                    }
                    try {
                        rest.closeDocument(d.id);
                    } catch (Exception e) {
                        if (closeFailed++ == 0) log.warn("Doküman kapatılamadı (sonrakiler loglanmayacak): " + RunLog.describe(e));
                    }
                }
                if (done % 100 == 0 || done == docCount) {
                    long sec = (System.currentTimeMillis() - started) / 1000;
                    long eta = done == 0 ? 0 : sec * (docCount - done) / done;
                    log.info(String.format("Rapor: %d/%d, REST hatası: %d, geçen: %d dk, kalan tahmini: %d dk",
                            done, docCount, restFailed, sec / 60, eta / 60));
                }
            }
            if (closeFailed > 0) log.warn("Kapatılamayan doküman sayısı: " + closeFailed);
            if (rest != null && MODE_REST.equals(restMode)) {
                try {
                    rest.logoff();
                } catch (Exception e) {
                    log.warn("REST logoff başarısız: " + RunLog.describe(e));
                }
            }

            // ---- 3) Veri kaynağı SI_ID -> CUID / ad
            Set<Integer> ids = new LinkedHashSet<>();
            for (WebiDocumentRow r : rows) {
                Integer id = parseId(r.dataSourceId);
                if (id != null) ids.add(id);
            }
            Map<Integer, String[]> ds = ids.isEmpty() ? new TreeMap<>() : reader.resolveDataSources(ids);
            for (WebiDocumentRow r : rows) {
                Integer id = parseId(r.dataSourceId);
                String[] info = id == null ? null : ds.get(id);
                if (info != null) {
                    r.dataSourceCuid = info[0];
                    r.dataSourceName = info[1];
                    if (r.dataSourceType == null) r.dataSourceType = info[2];
                } else if (r.dataSourceId != null) {
                    r.note = join(r.note, "veri kaynagi CMS'te bulunamadi: " + r.dataSourceId);
                }
            }
        }

        // ---- 4) ROW_ID + CSV
        long n = 0;
        for (WebiDocumentRow r : rows) r.rowId = ++n;
        Path csv = base.resolve("reports").resolve("webidocuments_" + stamp + ".csv");
        writeCsv(csv, rows, cms, exportDate);
        logCounts(rows, log);
        log.info("Rapor: " + docCount + ", satır: " + rows.size() + ", REST hatası: " + restFailed);
        log.info("CSV: " + csv);

        if (!apply) {
            log.info("DRY-RUN bitti. CSV'yi kontrol edin; uygunsa --apply ile çalıştırın (mesai sonrası).");
            return restFailed > 0 ? 1 : 0;
        }

        // ---- 5) DB
        if (!noQuery && restFailed > docCount * MAX_REST_FAIL_RATIO && !force) {
            log.error(String.format("REST hatası çok fazla (%d/%d). DB'ye yazılmadı. Logu inceleyin; yine de yazmak için --force.",
                    restFailed, docCount));
            return 1;
        }
        try (Connection c = DriverManager.getConnection(config.getDbJdbcUrl(),
                dbCredential.getUsername(), dbCredential.getPassword())) {
            int[] res = dao.replace(c, cms, exportDate, rows);
            int after = dao.count(c, cms);
            log.info("DB: silinen " + res[0] + " (önceki sayım " + before + "), eklenen " + res[1] + ", şimdi " + after);
            if (after != rows.size()) {
                log.error("Doğrulama başarısız: beklenen " + rows.size() + " satır, tabloda " + after);
                return 2;
            }
        }
        log.info("Bitti.");
        return restFailed > 0 ? 1 : 0;
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
     * Token yöntemlerini sırayla dener; ilk rapor için dataproviders çağrısı başarılı olan yöntemi döner.
     * -Dbo.restToken=logon|serialized|rest verilirse sadece o denenir. Hiçbiri olmazsa null.
     */
    private static WebiRestClient connectRest(String restUrl, IEnterpriseSession es, Credential cred, int probeDocId,
                                              String[] modeOut, RunLog log) {
        String forced = System.getProperty("bo.restToken");
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
                client.dataProviders(probeDocId);
                try {
                    client.closeDocument(probeDocId);
                } catch (Exception ignore) {
                    // kapatma hatası bağlantı testini bozmasın
                }
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

    private static void logCounts(List<WebiDocumentRow> rows, RunLog log) {
        Map<String, Integer> c = new TreeMap<>();
        for (WebiDocumentRow r : rows) {
            Integer k = c.get(r.status);
            c.put(r.status, k == null ? 1 : k + 1);
        }
        log.info("Durumlar: " + c);
    }

    /** Excel'de doğrudan açılsın diye UTF-8 BOM + ';' ayraç. */
    static void writeCsv(Path file, List<WebiDocumentRow> rows, String system, Date exportDate) throws IOException {
        Files.createDirectories(file.getParent());
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String export = f.format(exportDate);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("DURUM;ROW_ID;CUID;NAME;OWNER;PATH;DESCRIPTION;KEYWORD;CREATION_TIME;UPDATED_TIME;QUERY_ID;QUERY_NAME;"
                    + "DATASOURCE_TYPE;DATASOURCE_ID;DATASOURCE_CUID;DATASOURCE_NAME;SYSTEM;EXPORT_DATE;NOT\r\n");
            for (WebiDocumentRow r : rows) {
                WebiDocument d = r.doc;
                String[] cells = {r.status, String.valueOf(r.rowId), d.cuid, d.name, d.owner, d.path, d.description,
                        d.keyword, d.creationTime == null ? null : f.format(d.creationTime),
                        d.updatedTime == null ? null : f.format(d.updatedTime), r.queryId, r.queryName,
                        r.dataSourceType, r.dataSourceId, r.dataSourceCuid, r.dataSourceName, system, export, r.note};
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) line.append(';');
                    line.append(cell(cells[i]));
                }
                w.write(line.append("\r\n").toString());
            }
        }
    }

    static String cell(String s) {
        if (s == null) return "";
        if (s.indexOf(';') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
