package com.akbank.analytics.applications.bo.webidocuments;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** CMS'ten okunan bir Webi raporu (rapor düzeyi bilgiler). */
final class WebiDocument {
    int id;
    String cuid;
    String name;
    String owner;
    String description;
    String keyword;
    int parentId;
    String path;
    Date creationTime;
    Date updatedTime;
    /** CMS'teki SI_UNIVERSE (UNV) + SI_DSL_UNIVERSE (UNX) SI_ID'leri. REST okunamazsa yedek olarak kullanılır. */
    final List<Integer> universeIds = new ArrayList<>();
}
