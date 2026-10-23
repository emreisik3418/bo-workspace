package com.akbank.analytics.applications.bo.keyword;

import java.util.Locale;
import java.util.regex.Pattern;

/** Keywords alanına etiket ekleme kuralları (SDK'dan bağımsız, test edilebilir). */
public final class KeywordTag {

    /** Varsayılan etiket: "Arşiv" (kaynak dosya kodlamasından etkilenmesin diye \\u ile yazıldı). */
    public static final String DEFAULT_TAG = "Arşiv";

    /** Mevcut keyword'ler doluysa yeni etiket bu ayraçla eklenir. */
    public static final String SEPARATOR = ", ";

    private static final Locale TR = new Locale("tr", "TR");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[,;\\s]+");

    private KeywordTag() { }

    /** Etiket, keywords içinde ayrı bir kelime olarak (büyük/küçük harf duyarsız) zaten var mı? */
    public static boolean hasTag(String keywords, String tag) {
        if (keywords == null) return false;
        String want = tag.trim().toLowerCase(TR);
        for (String t : TOKEN_SPLIT.split(keywords.trim())) {
            if (t.toLowerCase(TR).equals(want)) return true;
        }
        return false;
    }

    /** Etiket eklenmiş yeni değer. Etiket zaten varsa değer aynen döner. Mevcut keyword'ler korunur. */
    public static String addTag(String keywords, String tag) {
        String current = keywords == null ? "" : keywords.trim();
        if (hasTag(current, tag)) return current;
        current = current.replaceAll("[,;\\s]+$", "");   // sondaki fazla ayraçlar: "a, b," -> "a, b"
        if (current.isEmpty()) return tag.trim();
        return current + SEPARATOR + tag.trim();
    }
}
