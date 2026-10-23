package com.akbank.analytics.applications.bo.keyword;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.akbank.analytics.applications.bo.config.AppConfig;
import com.akbank.analytics.applications.bo.credential.SboxCredentialProvider;
import com.akbank.analytics.applications.bo.model.BoEnvironment;
import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.sdk.BoSession;
import com.crystaldecisions.sdk.exception.SDKException;
import com.crystaldecisions.sdk.occa.infostore.IInfoObject;
import com.crystaldecisions.sdk.occa.infostore.IInfoObjects;
import com.crystaldecisions.sdk.occa.infostore.IInfoStore;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bir txt dosyasındaki CUID'lere ait raporların Properties > Keywords alanına etiket ekler (varsayılan "Arşiv").
 *
 * Kullanım:
 *   ReportKeywordApp <credentials.json> <cuid_listesi.txt> [--env HOST:6400] [--tag Etiket] [--apply]
 *
 *   cuid_listesi.txt : her satırda bir CUID (boş satırlar ve # ile başlayan satırlar atlanır)
 *   --env            : credentials.json'da birden fazla ortam varsa hangisi
 *   --tag            : eklenecek etiket (verilmezse "Arşiv")
 *   --apply          : verilmezse DRY-RUN — CMS'te HİÇBİR ŞEY değişmez, sadece rapor yazılır
 *
 * Kurallar:
 *   - Mevcut keyword'ler korunur; etiket sona ", " ile eklenir. Boşsa sadece etiket yazılır.
 *   - Etiket zaten varsa (büyük/küçük harf duyarsız) rapora dokunulmaz.
 *   - Her çalıştırmada reports\keyword_<zaman>.csv yazılır: eski ve yeni keyword değerleri burada durur (yedek).
 *   - --apply sonrası raporlar CMS'ten tekrar okunup etiketin yazıldığı doğrulanır.
 *   - Bir raporda hata olursa o rapor atlanır, diğerlerine devam edilir.
 *
 * Çıkış kodu: 0 = başarılı, 1 = kısmi (bulunamayan / hata alan / doğrulanamayan rapor var), 2 = hata.
 */
public final class ReportKeywordApp {

    private static final String AUTH_TYPE = System.getProperty("bo.authType", "secEnterprise");
    private static final int LOGON_ATTEMPTS = 4;
    private static final long LOGON_RETRY_WAIT_MS = 2 * 60 * 1000L;
    /** CMS sorgusunda IN listesine tek seferde konan CUID sayısı. */
    private static final int BATCH_SIZE = 100;

    private static final String USAGE =
            "Kullanım: ReportKeywordApp <credentials.json> <cuid_listesi.txt> [--env HOST:6400] [--tag Etiket] [--apply]";

    // Durumlar
    static final String S_UPDATE = "UPDATE";                 // dry-run: etiket eklenecek
    static final String S_ALREADY = "ALREADY_TAGGED";        // etiket zaten var, dokunulmadı
    static final String S_NOT_FOUND = "NOT_FOUND";           // CUID CMS'te yok
    static final String S_INVALID = "INVALID_LINE";          // txt'de CUID olmayan satır
    static final String S_UPDATED = "UPDATED";               // apply: kaydedildi ve doğrulandı
    static final String S_FAILED = "FAILED";                 // apply: kaydedilemedi
    static final String S_VERIFY_FAILED = "VERIFY_FAILED";   // apply: kaydedildi ama tekrar okununca etiket yok

    public static void main(String[] args) {
        int exitCode;
        RunLog log = null;
        try {
            Path base = Paths.get(System.getProperty("user.dir"));
            log = RunLog.open(base.resolve("logs"), "keyword");
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
        String envArg = null;
        String tag = KeywordTag.DEFAULT_TAG;
        boolean apply = false;
        for (int i = 0; i < args.length; i++) {
            if ("--apply".equalsIgnoreCase(args[i])) apply = true;
            else if ("--env".equalsIgnoreCase(args[i]) && i + 1 < args.length) envArg = args[++i];
            else if ("--tag".equalsIgnoreCase(args[i]) && i + 1 < args.length) tag = args[++i].trim();
            else positional.add(args[i]);
        }
        if (positional.size() != 2) {
            log.error(USAGE);
            log.error("2 parametre bekleniyordu, " + positional.size() + " geldi. Boşluk içeren değerleri \"tırnak\" içine alın:");
            for (int i = 0; i < positional.size(); i++) {
                log.error("  [" + (i + 1) + "] " + positional.get(i));
            }
            return 2;
        }
        if (tag.isEmpty() || tag.matches(".*[,;\\s].*")) {
            log.error("Geçersiz etiket: \"" + tag + "\" (boş olamaz, boşluk / virgül / noktalı virgül içeremez)");
            return 2;
        }
        Path listFile = Paths.get(positional.get(1));
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        log.info("Başladı. Mod: " + (apply ? "APPLY (keywords güncellenecek)" : "DRY-RUN (hiçbir şey değiştirilmeyecek)"));
        log.info("Etiket: " + tag);
        log.info("Log: " + log.getPath());

        // ---- 1) CUID listesi (önce bu: liste boş/hatalıysa BO'ya hiç bağlanmayalım)
        CuidList list = CuidList.read(listFile);
        log.info("Liste: " + listFile.toAbsolutePath() + "  ->  " + list.getCuids().size() + " CUID"
                + (list.getDuplicates().isEmpty() ? "" : ", " + list.getDuplicates().size() + " tekrar (bir kez işlenecek)")
                + (list.getInvalidLines().isEmpty() ? "" : ", " + list.getInvalidLines().size() + " geçersiz satır"));
        for (String bad : list.getInvalidLines()) {
            log.warn("CUID olmayan satır atlandı -> " + bad);
        }
        if (list.getCuids().isEmpty()) {
            log.error("Listede hiç CUID yok. CMS'e bağlanılmadı.");
            return 2;
        }

        AppConfig config = AppConfig.load(Paths.get(positional.get(0)));
        BoEnvironment env = pickEnvironment(config, envArg);
        if (env == null) {
            log.error("Ortam seçilemedi. credentials.json'daki ortamlar: " + cmsList(config) + ". --env ile belirtin.");
            return 2;
        }
        SboxCredentialProvider sbox = new SboxCredentialProvider(
                config.getSboxEndpoint(), config.getSboxUsername(), config.getSboxPassword());
        String cms = env.getCms();
        Credential boCredential = sbox.get(env.getSbox());
        Path csv = base.resolve("reports").resolve("keyword_" + stamp + ".csv");

        try (BoSession session = logonWithRetry(cms, boCredential, log)) {
            IInfoStore store = session.getInfoStore();
            log.info("[" + cms + "] Bağlanıldı.");

            // ---- 2) Raporları bul, planı çıkar
            Map<String, IInfoObject> found = fetch(store, list.getCuids(), "*", log);
            List<Row> rows = new ArrayList<>();
            Map<String, Integer> kinds = new TreeMap<>();
            for (String cuid : list.getCuids()) {
                IInfoObject o = found.get(cuid);
                if (o == null) {
                    rows.add(new Row(S_NOT_FOUND, cuid, null));
                    continue;
                }
                Row r = new Row(null, cuid, o);
                r.id = String.valueOf(o.getID());
                r.name = o.getTitle();
                r.kind = o.getKind();
                r.oldKeywords = o.getKeyword() == null ? "" : o.getKeyword();
                if (KeywordTag.hasTag(r.oldKeywords, tag)) {
                    r.status = S_ALREADY;
                    r.newKeywords = r.oldKeywords;
                } else {
                    r.status = S_UPDATE;
                    r.newKeywords = KeywordTag.addTag(r.oldKeywords, tag);
                }
                rows.add(r);
                Integer n = kinds.get(r.kind);
                kinds.put(r.kind, n == null ? 1 : n + 1);
            }
            for (String bad : list.getInvalidLines()) {
                Row r = new Row(S_INVALID, "", null);
                r.note = "satır " + bad;
                rows.add(r);
            }
            log.info("Bulunan nesne türleri: " + kinds);
            logCounts(rows, log);
            writeCsv(csv, rows);
            log.info("Plan raporu: " + csv);

            if (!apply) {
                log.info("DRY-RUN bitti. CSV'yi kontrol edin; uygunsa aynı komutu --apply ile çalıştırın.");
                return 0;
            }

            // ---- 3) Uygula: sadece UPDATE olanlar, rapor rapor (biri hata verirse diğerleri devam eder)
            List<String> saved = new ArrayList<>();
            int toUpdate = count(rows, S_UPDATE);
            int done = 0;
            for (Row r : rows) {
                if (!S_UPDATE.equals(r.status)) continue;
                done++;
                try {
                    r.object.setKeyword(r.newKeywords);
                    r.object.save();
                    saved.add(r.cuid);
                } catch (Exception e) {
                    r.status = S_FAILED;
                    r.note = RunLog.describe(e);
                    log.error("[" + r.cuid + "] " + r.name + " kaydedilemedi", e);
                }
                if (done % 50 == 0 || done == toUpdate) {
                    log.info("Kaydedilen: " + done + "/" + toUpdate);
                }
            }

            // ---- 4) Doğrula: CMS'ten tekrar oku
            Map<String, IInfoObject> fresh = fetch(store, saved, "SI_ID, SI_CUID, SI_KEYWORD", log);
            for (Row r : rows) {
                if (!S_UPDATE.equals(r.status)) continue;
                IInfoObject o = fresh.get(r.cuid);
                String actual = o == null ? null : o.getKeyword();
                if (o != null && KeywordTag.hasTag(actual, tag)) {
                    r.status = S_UPDATED;
                    r.newKeywords = actual;
                } else {
                    r.status = S_VERIFY_FAILED;
                    r.note = o == null ? "tekrar okunamadı" : "CMS'teki değer: " + actual;
                    log.warn("[" + r.cuid + "] " + r.name + " doğrulanamadı: " + r.note);
                }
            }
            logCounts(rows, log);
            writeCsv(csv, rows);
            log.info("Sonuç raporu (eski değerler yedek olarak burada): " + csv);

            int failed = count(rows, S_FAILED) + count(rows, S_VERIFY_FAILED);
            if (toUpdate > 0 && count(rows, S_UPDATED) == 0) return 2;
            return failed > 0 || count(rows, S_NOT_FOUND) > 0 ? 1 : 0;
        }
    }

    /** CUID'leri BATCH_SIZE'lık gruplar halinde CI_INFOOBJECTS'ten okur. Anahtar: CUID. */
    private static Map<String, IInfoObject> fetch(IInfoStore store, List<String> cuids, String fields, RunLog log)
            throws SDKException {
        Map<String, IInfoObject> result = new HashMap<>();
        for (int from = 0; from < cuids.size(); from += BATCH_SIZE) {
            List<String> part = cuids.subList(from, Math.min(from + BATCH_SIZE, cuids.size()));
            StringBuilder in = new StringBuilder();
            for (String c : part) {
                if (in.length() > 0) in.append(',');
                in.append('\'').append(c).append('\'');   // CUID'ler CuidList'te doğrulandı, tırnak içeremez
            }
            String q = "SELECT TOP " + (BATCH_SIZE * 2) + " " + fields
                    + " FROM CI_INFOOBJECTS WHERE SI_CUID IN (" + in + ")";
            IInfoObjects objs = store.query(q);
            for (Object o : objs) {
                IInfoObject io = (IInfoObject) o;
                result.put(io.getCUID(), io);
            }
            if (cuids.size() > BATCH_SIZE) {
                log.info("CMS'ten okunan: " + Math.min(from + BATCH_SIZE, cuids.size()) + "/" + cuids.size());
            }
        }
        return result;
    }

    private static void logCounts(List<Row> rows, RunLog log) {
        Map<String, Integer> c = new TreeMap<>();
        for (Row r : rows) {
            Integer n = c.get(r.status);
            c.put(r.status, n == null ? 1 : n + 1);
        }
        log.info("Durumlar: " + c);
    }

    private static int count(List<Row> rows, String status) {
        int n = 0;
        for (Row r : rows) if (status.equals(r.status)) n++;
        return n;
    }

    /** Excel'de doğrudan açılsın diye UTF-8 BOM + ';' ayraç. */
    static void writeCsv(Path file, List<Row> rows) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("DURUM;CUID;SI_ID;AD;TUR;ESKI_KEYWORD;YENI_KEYWORD;NOT\r\n");
            for (Row r : rows) {
                w.write(cell(r.status) + ";" + cell(r.cuid) + ";" + cell(r.id) + ";" + cell(r.name) + ";"
                        + cell(r.kind) + ";" + cell(r.oldKeywords) + ";" + cell(r.newKeywords) + ";" + cell(r.note) + "\r\n");
            }
        }
    }

    static String cell(String s) {
        if (s == null) return "";
        if (s.indexOf(';') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static final class Row {
        String status;
        final String cuid;
        final IInfoObject object;
        String id, name, kind, oldKeywords, newKeywords, note;

        Row(String status, String cuid, IInfoObject object) {
            this.status = status;
            this.cuid = cuid;
            this.object = object;
        }
    }

    private static BoEnvironment pickEnvironment(AppConfig config, String envArg) {
        if (envArg == null) {
            return config.getEnvironments().size() == 1 ? config.getEnvironments().get(0) : null;
        }
        for (BoEnvironment e : config.getEnvironments()) {
            if (e.getCms().equalsIgnoreCase(envArg.trim())) return e;
        }
        return null;
    }

    private static String cmsList(AppConfig config) {
        List<String> l = new ArrayList<>();
        for (BoEnvironment e : config.getEnvironments()) l.add(e.getCms());
        return String.join(", ", l);
    }

    /** Geçici hatalarda (lisans dolu, ağ vb.) LOGON_ATTEMPTS kez, LOGON_RETRY_WAIT_MS arayla dener. */
    private static BoSession logonWithRetry(String cms, Credential credential, RunLog log) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return BoSession.logon(cms, credential, AUTH_TYPE);
            } catch (Exception e) {
                if (attempt >= LOGON_ATTEMPTS) throw e;
                log.warn(String.format("[%s] CMS logon başarısız (deneme %d/%d), %d dk sonra tekrar denenecek: %s",
                        cms, attempt, LOGON_ATTEMPTS, LOGON_RETRY_WAIT_MS / 60000, RunLog.describe(e)));
                Thread.sleep(LOGON_RETRY_WAIT_MS);
            }
        }
    }
}
