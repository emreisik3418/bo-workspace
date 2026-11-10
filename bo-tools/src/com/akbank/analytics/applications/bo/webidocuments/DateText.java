package com.akbank.analytics.applications.bo.webidocuments;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tablodaki tarih kolonları metin (VARCHAR2) olabilir (eski uygulama tarihleri metin yazmış).
 * Okurken bilinen biçimler denenir; yazarken mevcut veride bulunan biçim kullanılır (eski veriyle tutarlı kalsın).
 * Okunamayan değer null döner -> rapor "değişmiş" sayılır, yeniden yazılır (kendiliğinden düzelir).
 */
final class DateText {

    /** Tablo boşsa / örnek okunamazsa kullanılan biçim (mevcut veriyle aynı: gün/ay/yıl, saniye kesri yok). */
    static final String DEFAULT_PATTERN = "dd/MM/yyyy HH:mm:ss";

    /** Sıra önemli: Türkiye'de gün önce yazıldığı için dd.MM / dd/MM, MM/dd'den önce denenir. */
    static final String[] PATTERNS = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy hh:mm:ss a",
            "MMM d, yyyy h:mm:ss a",
            "MMM d, yyyy h:mm a",
            "EEE MMM dd HH:mm:ss zzz yyyy",
            "dd-MMM-yy hh.mm.ss a",
            "dd-MMM-yy",
            "yyyy-MM-dd",
            "dd.MM.yyyy",
    };

    private DateText() { }

    /** Metni tarihe çevirir; olmazsa null. Saniye kesirleri (".0", ",000000") atılır. */
    static Date parse(String text) {
        String p = patternOf(text);
        return p == null ? null : parseWith(clean(text), p);
    }

    /** Metnin hangi biçimde olduğu; hiçbiri değilse null. */
    static String patternOf(String text) {
        if (text == null) return null;
        String s = clean(text);
        if (s.isEmpty()) return null;
        for (String p : PATTERNS) {
            if (parseWith(s, p) != null) return p;
        }
        return null;
    }

    static String format(Date d, String pattern) {
        return d == null ? null : fmt(pattern).format(d);
    }

    /** Sondaki saniye kesrini atar: "2026-11-06 11:36:00.0" -> "2026-11-06 11:36:00", "...00,000000 AM" -> "...00 AM". */
    static String clean(String text) {
        return text.trim().replaceAll("(\\d{2}:\\d{2}:\\d{2}|\\d{2}\\.\\d{2}\\.\\d{2})[.,]\\d+", "$1");
    }

    private static Date parseWith(String s, String pattern) {
        SimpleDateFormat f = fmt(pattern);
        ParsePosition pos = new ParsePosition(0);
        Date d = f.parse(s, pos);
        return d != null && pos.getIndex() == s.length() && pos.getErrorIndex() < 0 ? d : null;
    }

    private static SimpleDateFormat fmt(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setLenient(false);
        return f;
    }
}
