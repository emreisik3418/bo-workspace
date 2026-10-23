package com.akbank.analytics.applications.bo.keyword;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rapor CUID listesi (txt). Her satırda bir CUID.
 * - Boş satırlar ve '#' ile başlayan satırlar atlanır.
 * - Satırda CUID'den sonra TAB, ';', ',' veya boşlukla ayrılmış başka bilgi olabilir (ilk parça alınır).
 * - Aynı CUID birden fazla kez yazılmışsa bir kez işlenir.
 * - CUID olamayacak satırlar (örn. "CUID" başlığı) geçersiz olarak raporlanır, işlenmez.
 */
public final class CuidList {

    /** BO CUID'leri harf, rakam ve _ . - karakterlerinden oluşur. */
    private static final Pattern CUID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]{9,63}");
    private static final Pattern SEPARATOR = Pattern.compile("[\\t;,\\s]+");

    private final List<String> cuids;
    private final List<String> invalidLines;
    private final List<String> duplicates;

    private CuidList(List<String> cuids, List<String> invalidLines, List<String> duplicates) {
        this.cuids = Collections.unmodifiableList(cuids);
        this.invalidLines = Collections.unmodifiableList(invalidLines);
        this.duplicates = Collections.unmodifiableList(duplicates);
    }

    public static CuidList read(Path file) throws IOException {
        // CUID'ler ASCII; ISO-8859-1 hiçbir dosyada okuma hatası vermez (UTF-8/ANSI fark etmez).
        return parse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
    }

    public static CuidList parse(String text) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        List<String> dup = new ArrayList<>();
        String[] lines = text.split("\\r?\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripBom(lines[i]).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String first = SEPARATOR.split(line, 2)[0];
            if (!CUID.matcher(first).matches()) {
                invalid.add((i + 1) + ": " + line);
            } else if (!seen.add(first)) {
                dup.add(first);
            }
        }
        return new CuidList(new ArrayList<>(seen), invalid, dup);
    }

    private static String stripBom(String s) {
        if (s.startsWith("ï»¿")) return s.substring(3);   // UTF-8 BOM, ISO-8859-1 ile okunmuş
        if (s.startsWith("﻿")) return s.substring(1);
        return s;
    }

    public List<String> getCuids()        { return cuids; }
    public List<String> getInvalidLines() { return invalidLines; }
    public List<String> getDuplicates()   { return duplicates; }
}
