package com.akbank.analytics.applications.bo.universeconnections;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.akbank.analytics.applications.bo.common.BoLogon;
import com.akbank.analytics.applications.bo.config.AppConfig;
import com.akbank.analytics.applications.bo.credential.SboxCredentialProvider;
import com.akbank.analytics.applications.bo.model.BoEnvironment;
import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.sdk.BoSession;
import com.crystaldecisions.sdk.occa.infostore.IInfoObject;
import com.crystaldecisions.sdk.occa.infostore.IInfoObjects;
import com.crystaldecisions.sdk.occa.infostore.IInfoStore;
import com.crystaldecisions.sdk.properties.IProperties;
import com.crystaldecisions.sdk.properties.IProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Universe'lerin (UNX + UNV) connection bilgilerini tabloya yazacak iş. ŞU AN SADECE TEŞHİS MODU VAR:
 *
 *   UniverseConnectionsApp <credentials.json> --diag [--env HOST:6400] [--count N] [--cuid CUID]
 *
 *   --diag  : her türden (DSL.Universe, Universe, CCIS.DataConnection, MetaData.DataConnection) ilk N nesnenin
 *             TÜM CMS özelliklerini loga döker; universe'lerin connection'a işaret eden özelliklerini bulup o
 *             connection'ları da döker. DB'ye ve CMS'e hiçbir şey yazmaz.
 *   --count : tür başına kaç nesne (varsayılan 2)
 *   --cuid  : sadece bu CUID'li nesneyi (ve bağlı connection'larını) dök
 *
 * Amaç: DBMS / DATABASE / DATABASE_USER / NETWORK_LAYER ile universe -> connection bağlantısının hangi özellikte
 * olduğunu sunucuda görüp asıl yazımı ona göre kurmak.
 * Çıkış kodu: 0 = tamam, 2 = hata.
 */
public final class UniverseConnectionsApp {

    private static final String USAGE = "Kullanım: UniverseConnectionsApp <credentials.json> --diag [--env HOST:6400]"
            + " [--count N] [--cuid CUID]";

    static final String[] UNIVERSE_KINDS = {"DSL.Universe", "Universe"};
    static final String[] CONNECTION_KINDS = {"CCIS.DataConnection", "MetaData.DataConnection"};

    public static void main(String[] args) {
        int exitCode;
        RunLog log = null;
        try {
            Path base = Paths.get(System.getProperty("user.dir"));
            log = RunLog.open(base.resolve("logs"), "universeconnections");
            exitCode = run(args, log);
        } catch (Throwable t) {
            if (log != null) log.error("Beklenmeyen hata, uygulama durduruldu", t);
            else t.printStackTrace();
            exitCode = 2;
        } finally {
            if (log != null) log.close();
        }
        System.exit(exitCode);   // BO SDK'nın arka plan thread'leri JVM'i açık tutmasın
    }

    private static int run(String[] args, RunLog log) throws Exception {
        List<String> positional = new ArrayList<>();
        String envArg = null, cuid = null;
        int count = 2;
        boolean diag = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--diag".equalsIgnoreCase(a)) diag = true;
            else if ("--env".equalsIgnoreCase(a) && i + 1 < args.length) envArg = args[++i];
            else if ("--cuid".equalsIgnoreCase(a) && i + 1 < args.length) cuid = args[++i].trim();
            else if ("--count".equalsIgnoreCase(a) && i + 1 < args.length) count = Integer.parseInt(args[++i].trim());
            else positional.add(a);
        }
        if (positional.size() != 1) {
            log.error(USAGE);
            return 2;
        }
        if (!diag) {
            log.error("Şu an sadece teşhis modu var: --diag ile çalıştırın. Asıl yazım teşhis sonucuna göre eklenecek.");
            return 2;
        }
        AppConfig config = AppConfig.load(Paths.get(positional.get(0)));
        BoEnvironment env = BoLogon.pickEnvironment(config, envArg);
        if (env == null) {
            log.error("Ortam seçilemedi. credentials.json'daki ortamlar: " + BoLogon.cmsList(config) + ". --env ile belirtin.");
            return 2;
        }
        SboxCredentialProvider sbox = new SboxCredentialProvider(
                config.getSboxEndpoint(), config.getSboxUsername(), config.getSboxPassword());
        Credential cred = sbox.get(env.getSbox());
        log.info("TEŞHİS modu (DB'ye ve CMS'e yazılmaz). Ortam: " + env.getCms() + ", log: " + log.getPath());

        try (BoSession session = BoLogon.logonWithRetry(env.getCms(), cred, log)) {
            IInfoStore store = session.getInfoStore();
            if (cuid != null) {
                IInfoObject o = findByCuid(store, cuid);
                if (o == null) {
                    log.error("CUID bulunamadı: " + cuid);
                    return 2;
                }
                dumpWithLinks(store, o, log);
                return 0;
            }
            for (String kind : UNIVERSE_KINDS) {
                log.info("===== " + kind + ": toplam " + countKind(store, kind) + " =====");
                for (Object x : store.query("SELECT TOP " + count + " * FROM CI_APPOBJECTS WHERE SI_KIND='" + kind
                        + "' ORDER BY SI_ID DESC")) {
                    dumpWithLinks(store, (IInfoObject) x, log);
                }
            }
            for (String kind : CONNECTION_KINDS) {
                log.info("===== " + kind + ": toplam " + countKind(store, kind) + " =====");
                for (Object x : store.query("SELECT TOP " + count + " * FROM CI_APPOBJECTS WHERE SI_KIND='" + kind
                        + "' ORDER BY SI_ID DESC")) {
                    PropertyDump.dump((IInfoObject) x, log);
                }
            }
        }
        log.info("Teşhis bitti. Log dosyasını (ya da ekran görüntülerini) paylaşın.");
        return 0;
    }

    /** Nesneyi döker; adında CONN geçen özelliklerdeki SI_ID'leri bulup o nesneleri de döker. */
    private static void dumpWithLinks(IInfoStore store, IInfoObject o, RunLog log) throws Exception {
        PropertyDump.dump(o, log);
        Set<Integer> ids = new LinkedHashSet<>();
        collectConnectionIds(o.properties(), false, ids, 0);
        ids.remove(o.getID());
        if (ids.isEmpty()) {
            log.info("  (adında CONN geçen özellikte SI_ID bulunamadı)");
            return;
        }
        log.info("  Bağlı olabilecek nesneler (adında CONN geçen özelliklerden): " + ids);
        int n = 0;
        for (Integer id : ids) {
            if (n++ >= 3) break;
            IInfoObjects r = store.query("SELECT * FROM CI_APPOBJECTS WHERE SI_ID=" + id);
            if (r.size() == 0) r = store.query("SELECT * FROM CI_INFOOBJECTS WHERE SI_ID=" + id);
            for (Object x : r) PropertyDump.dump((IInfoObject) x, log);
        }
    }

    @SuppressWarnings("rawtypes")
    static void collectConnectionIds(IProperties props, boolean underConn, Set<Integer> out, int depth) {
        if (props == null || depth > 5) return;
        for (Object e : props.entrySet()) {
            Map.Entry entry = (Map.Entry) e;
            String name = PropertyDump.nameOf(entry.getKey()).toUpperCase(Locale.ROOT);
            Object val = entry.getValue();
            Object value = val instanceof IProperty ? ((IProperty) val).getValue() : val;
            boolean conn = underConn || name.contains("CONN");
            if (value instanceof IProperties) {
                collectConnectionIds((IProperties) value, conn, out, depth + 1);
            } else if (conn && value instanceof Number && !name.equals("SI_TOTAL")) {
                int id = ((Number) value).intValue();
                if (id > 0) out.add(id);
            }
        }
    }

    private static IInfoObject findByCuid(IInfoStore store, String cuid) throws Exception {
        String safe = cuid.replace("'", "");
        for (String t : new String[]{"CI_APPOBJECTS", "CI_INFOOBJECTS"}) {
            IInfoObjects r = store.query("SELECT * FROM " + t + " WHERE SI_CUID='" + safe + "'");
            if (r.size() > 0) return (IInfoObject) r.get(0);
        }
        return null;
    }

    /** Türün toplam nesne sayısı (sayfalı, sadece SI_ID). */
    private static int countKind(IInfoStore store, String kind) throws Exception {
        int total = 0, last = 0;
        while (true) {
            IInfoObjects page = store.query("SELECT TOP 1000 SI_ID FROM CI_APPOBJECTS WHERE SI_KIND='" + kind
                    + "' AND SI_ID > " + last + " ORDER BY SI_ID");
            for (Object x : page) last = Math.max(last, ((IInfoObject) x).getID());
            total += page.size();
            if (page.size() < 1000) return total;
        }
    }
}
