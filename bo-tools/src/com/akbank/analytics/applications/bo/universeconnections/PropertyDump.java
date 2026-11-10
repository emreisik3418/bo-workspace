package com.akbank.analytics.applications.bo.universeconnections;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.crystaldecisions.sdk.occa.infostore.IInfoObject;
import com.crystaldecisions.sdk.properties.IProperties;
import com.crystaldecisions.sdk.properties.IProperty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Teşhis: bir CMS nesnesinin TÜM özelliklerini (iç içe torbalar dahil) loga döker.
 * Hangi alanın hangi özellikte olduğunu tahmin etmek yerine sunucuda görmek için.
 * IProperties bir Map; anahtarlar özellik ID'si (Integer) olabilir: ad için CePropertyID.idToName (reflection ile)
 * denenir, olmazsa anahtarın kendisi yazılır.
 */
final class PropertyDump {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_VALUE = 300;
    private static Method idToName;
    private static boolean idToNameTried;

    private PropertyDump() { }

    static void dump(IInfoObject o, RunLog log) {
        log.info("----- " + o.getKind() + " | SI_ID=" + o.getID() + " | " + o.getTitle() + " | CUID=" + o.getCUID() + " -----");
        List<String> lines = new ArrayList<>();
        walk(o.properties(), "  ", 0, lines);
        for (String l : lines) log.info(l);
    }

    @SuppressWarnings("rawtypes")
    static void walk(IProperties props, String indent, int depth, List<String> out) {
        if (props == null) return;
        for (Object e : props.entrySet()) {
            Map.Entry entry = (Map.Entry) e;
            Object key = entry.getKey();
            Object val = entry.getValue();
            Object value = val instanceof IProperty ? ((IProperty) val).getValue() : val;
            String name = nameOf(key);
            if (value instanceof IProperties) {
                out.add(indent + name + " = {torba}");
                if (depth < MAX_DEPTH) walk((IProperties) value, indent + "    ", depth + 1, out);
            } else {
                out.add(indent + name + " = " + shorten(value) + (value == null ? "" : "  (" + value.getClass().getSimpleName() + ")"));
            }
        }
    }

    static String nameOf(Object key) {
        if (key instanceof Integer) {
            if (!idToNameTried) {
                idToNameTried = true;
                try {
                    Class<?> c = Class.forName("com.crystaldecisions.sdk.occa.infostore.CePropertyID");
                    idToName = c.getMethod("idToName", Integer.class);
                } catch (Exception ignore) {
                    idToName = null;
                }
            }
            if (idToName != null) {
                try {
                    Object n = idToName.invoke(null, key);
                    if (n != null) return n.toString();
                } catch (Exception ignore) {
                    // ad bulunamadı: ID yazılır
                }
            }
        }
        return String.valueOf(key);
    }

    static String shorten(Object v) {
        if (v == null) return "null";
        String s = v.toString().replace('\r', ' ').replace('\n', ' ');
        return s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "...(" + s.length() + " karakter)" : s;
    }
}
