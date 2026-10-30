package com.akbank.analytics.applications.bo.webidocuments;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.crystaldecisions.sdk.exception.SDKException;
import com.crystaldecisions.sdk.occa.infostore.IInfoObject;
import com.crystaldecisions.sdk.occa.infostore.IInfoObjects;
import com.crystaldecisions.sdk.occa.infostore.IInfoStore;
import com.crystaldecisions.sdk.properties.IProperties;
import com.crystaldecisions.sdk.properties.IProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CMS sorguları: klasörler, Webi raporları, veri kaynağı (universe) adları. */
final class CmsWebiReader {

    private static final int PAGE = 1000;
    private static final int IN_BATCH = 200;

    private final IInfoStore store;
    private final RunLog log;

    CmsWebiReader(IInfoStore store, RunLog log) {
        this.store = store;
        this.log = log;
    }

    /** Tüm 'Folder' klasörleri (sayfalı, SI_ID sırasıyla). */
    CmsFolderPaths loadFolders() throws SDKException {
        CmsFolderPaths paths = new CmsFolderPaths();
        int lastId = 0;
        while (true) {
            IInfoObjects page = store.query("SELECT TOP " + PAGE + " SI_ID, SI_NAME, SI_PARENTID FROM CI_INFOOBJECTS"
                    + " WHERE SI_KIND='Folder' AND SI_ID > " + lastId + " ORDER BY SI_ID");
            if (page.size() == 0) break;
            for (Object o : page) {
                IInfoObject f = (IInfoObject) o;
                paths.add(f.getID(), f.getTitle(), f.getParentID());
                lastId = Math.max(lastId, f.getID());
            }
            if (page.size() < PAGE) break;
        }
        // Teşhis: kökün (23) doğrudan altındaki klasörler okunabiliyor mu
        IInfoObjects top = store.query("SELECT TOP 20 SI_ID, SI_NAME, SI_PARENTID, SI_KIND FROM CI_INFOOBJECTS"
                + " WHERE SI_PARENTID=" + CmsFolderPaths.PUBLIC_ROOT_ID);
        StringBuilder sb = new StringBuilder();
        for (Object o : top) {
            IInfoObject f = (IInfoObject) o;
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getID()).append('(').append(f.getTitle()).append(' ').append(f.getKind())
                    .append(paths.contains(f.getID()) ? "" : " LISTEDE YOK").append(')');
        }
        log.info("Kök (23) altındaki ilk nesneler: " + (sb.length() == 0 ? "YOK" : sb));
        return paths;
    }

    /** Public Folders altındaki Webi raporları (instance'lar hariç). folderPrefix verilirse sadece o yolun altı. */
    List<WebiDocument> listDocuments(CmsFolderPaths folders, String folderPrefix) throws SDKException {
        List<WebiDocument> result = new ArrayList<>();
        Map<Integer, Integer> outside = new HashMap<>();   // teşhis: Public dışı kalan raporların üst klasörleri
        int lastId = 0;
        int seen = 0;
        while (true) {
            IInfoObjects page = store.query("SELECT TOP " + PAGE + " SI_ID, SI_CUID, SI_NAME, SI_OWNER, SI_DESCRIPTION,"
                    + " SI_KEYWORD, SI_PARENTID, SI_CREATION_TIME, SI_UPDATE_TS, SI_UNIVERSE, SI_DSL_UNIVERSE"
                    + " FROM CI_INFOOBJECTS WHERE SI_KIND='Webi' AND SI_INSTANCE=0 AND SI_ID > " + lastId
                    + " ORDER BY SI_ID");
            if (page.size() == 0) break;
            for (Object o : page) {
                IInfoObject io = (IInfoObject) o;
                lastId = Math.max(lastId, io.getID());
                seen++;
                String path = folders.pathOf(io.getParentID());
                if (path == null) {                                              // Public Folders dışında
                    Integer n = outside.get(io.getParentID());
                    outside.put(io.getParentID(), n == null ? 1 : n + 1);
                    continue;
                }
                if (folderPrefix != null && !underFolder(path, folderPrefix)) continue;

                WebiDocument d = new WebiDocument();
                d.id = io.getID();
                d.cuid = io.getCUID();
                d.name = io.getTitle();
                d.description = io.getDescription();
                d.keyword = io.getKeyword();
                d.parentId = io.getParentID();
                d.path = path;
                IProperties props = io.properties();
                d.owner = asString(value(props, "SI_OWNER"));
                d.creationTime = asDate(value(props, "SI_CREATION_TIME"));
                d.updatedTime = asDate(value(props, "SI_UPDATE_TS"));
                d.universeIds.addAll(bagIds(props, "SI_UNIVERSE"));
                for (Integer id : bagIds(props, "SI_DSL_UNIVERSE")) {
                    if (!d.universeIds.contains(id)) d.universeIds.add(id);
                }
                result.add(d);
            }
            log.info("CMS'ten okunan Webi: " + seen + ", Public Folders altında: " + result.size());
            if (page.size() < PAGE) break;
        }
        logOutside(folders, outside);
        return result;
    }

    /** Teşhis: Public Folders dışında sayılan raporların en çok görülen 10 üst klasörü ve zincirleri. */
    private void logOutside(CmsFolderPaths folders, Map<Integer, Integer> outside) {
        if (outside.isEmpty()) return;
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(outside.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        log.info("Public Folders dışında sayılan rapor: " + sum(outside) + ", farklı üst klasör: " + outside.size()
                + ". En çok görülenler:");
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Map.Entry<Integer, Integer> e = list.get(i);
            log.info("  " + e.getValue() + " rapor, SI_PARENTID=" + e.getKey() + " : " + folders.trace(e.getKey()));
        }
    }

    private static int sum(Map<Integer, Integer> m) {
        int s = 0;
        for (Integer v : m.values()) s += v;
        return s;
    }

    /** "/Public Folders/A" önekinin altı mı (büyük/küçük harf duyarsız, "/A" ile "/AB" karışmaz). */
    static boolean underFolder(String path, String prefix) {
        String p = path.toLowerCase();
        String f = prefix.toLowerCase();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        return p.equals(f) || p.startsWith(f + "/");
    }

    /** Veri kaynağı SI_ID'leri -> {CUID, ad, tür}. Önce CI_APPOBJECTS (universe'ler), kalanlar CI_INFOOBJECTS. */
    Map<Integer, String[]> resolveDataSources(Collection<Integer> ids) throws SDKException {
        Map<Integer, String[]> result = new HashMap<>();
        Set<Integer> pending = new LinkedHashSet<>(ids);
        for (String table : new String[]{"CI_APPOBJECTS", "CI_INFOOBJECTS"}) {
            List<Integer> list = new ArrayList<>(pending);
            for (int from = 0; from < list.size(); from += IN_BATCH) {
                List<Integer> part = list.subList(from, Math.min(from + IN_BATCH, list.size()));
                StringBuilder in = new StringBuilder();
                for (Integer id : part) {
                    if (in.length() > 0) in.append(',');
                    in.append(id);
                }
                IInfoObjects objs = store.query("SELECT TOP " + (IN_BATCH * 2) + " SI_ID, SI_CUID, SI_NAME, SI_KIND FROM "
                        + table + " WHERE SI_ID IN (" + in + ")");
                for (Object o : objs) {
                    IInfoObject io = (IInfoObject) o;
                    result.put(io.getID(), new String[]{io.getCUID(), io.getTitle(), io.getKind()});
                    pending.remove(io.getID());
                }
            }
            if (pending.isEmpty()) break;
        }
        if (!pending.isEmpty()) {
            log.warn("CMS'te bulunamayan veri kaynağı SI_ID'leri: " + pending);
        }
        return result;
    }

    // ---- Özellik okuma yardımcıları

    private static Object value(IProperties props, String name) {
        if (props == null) return null;
        IProperty p = props.getProperty(name);
        return p == null ? null : p.getValue();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Date asDate(Object v) {
        return v instanceof Date ? (Date) v : null;
    }

    /** SI_UNIVERSE gibi özellik torbaları: SI_TOTAL + "1", "2", ... anahtarlarında SI_ID'ler. */
    static List<Integer> bagIds(IProperties props, String name) {
        List<Integer> ids = new ArrayList<>();
        Object bag = value(props, name);
        if (!(bag instanceof IProperties)) return ids;
        IProperties b = (IProperties) bag;
        Object total = value(b, "SI_TOTAL");
        int n = total instanceof Number ? ((Number) total).intValue() : 0;
        for (int i = 1; i <= n; i++) {
            Object v = value(b, String.valueOf(i));
            if (v instanceof Number) ids.add(((Number) v).intValue());
        }
        return ids;
    }
}
