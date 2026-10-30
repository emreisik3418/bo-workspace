package com.akbank.analytics.applications.bo.webidocuments;

import java.util.HashMap;
import java.util.Map;

/**
 * Klasör ID -> yol. Sadece Public Folders (kök SI_ID 23) altındaki klasörler için yol döner;
 * kişisel klasörler / Inbox gibi zinciri 23'e ulaşmayanlar için null.
 * Yol biçimi: "/Public Folders/AKBANK/Risk" (rapor adı dahil değil).
 */
final class CmsFolderPaths {

    static final int PUBLIC_ROOT_ID = 23;
    static final String PUBLIC_ROOT_NAME = "Public Folders";
    private static final int MAX_DEPTH = 100;

    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, Integer> parents = new HashMap<>();
    private final Map<Integer, String> cache = new HashMap<>();

    void add(int id, String name, int parentId) {
        names.put(id, name);
        parents.put(id, parentId);
    }

    int size() { return names.size(); }

    /** Klasörün yolu; Public Folders altında değilse null. */
    String pathOf(int folderId) {
        if (cache.containsKey(folderId)) return cache.get(folderId);
        String result = build(folderId);
        cache.put(folderId, result);
        return result;
    }

    boolean contains(int folderId) { return names.containsKey(folderId); }

    /** Teşhis: klasör zinciri, ör. "5012(Risk) -> 4100(AKBANK) -> 23(Public)" ya da "... -> 18(listede yok)". */
    String trace(int folderId) {
        StringBuilder sb = new StringBuilder();
        Integer current = folderId;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            if (sb.length() > 0) sb.append(" -> ");
            if (current == PUBLIC_ROOT_ID) return sb.append(current).append("(Public)").toString();
            String name = names.get(current);
            if (name == null) return sb.append(current).append("(listede yok)").toString();
            sb.append(current).append('(').append(name).append(')');
            current = parents.get(current);
        }
        return sb.append(" ...").toString();
    }

    private String build(int folderId) {
        StringBuilder sb = new StringBuilder();
        int current = folderId;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (current == PUBLIC_ROOT_ID) {
                return "/" + PUBLIC_ROOT_NAME + sb;
            }
            String name = names.get(current);
            if (name == null) return null;   // zincir Public Folders'a ulaşmadı
            sb.insert(0, "/" + name);
            current = parents.get(current);
        }
        return null;                          // döngü / çok derin
    }
}
