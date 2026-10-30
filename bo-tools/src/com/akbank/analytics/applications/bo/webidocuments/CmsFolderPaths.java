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
