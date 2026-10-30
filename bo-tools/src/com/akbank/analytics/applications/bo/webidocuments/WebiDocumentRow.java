package com.akbank.analytics.applications.bo.webidocuments;

/** Tablodaki bir satır: bir raporun bir sorgusu (data provider). */
final class WebiDocumentRow {

    // CSV'deki DURUM değerleri (DB'ye yazılmaz)
    static final String S_QUERY = "QUERY";             // REST'ten sorgu okundu
    static final String S_NO_QUERY = "NO_QUERY";       // REST okundu ama raporda sorgu yok
    static final String S_CMS_ONLY = "CMS_ONLY";       // --no-query: sorgu bilgisi istenmedi, universe CMS'ten
    static final String S_REST_FAILED = "REST_FAILED"; // REST hata verdi, universe CMS'ten

    final WebiDocument doc;
    String status;
    long rowId;
    String queryId;
    String queryName;
    String dataSourceId;      // SI_ID (CSV için)
    String dataSourceType;    // unx / unv / ... (CSV için)
    String dataSourceCuid;
    String dataSourceName;
    String note;

    WebiDocumentRow(WebiDocument doc, String status) {
        this.doc = doc;
        this.status = status;
    }
}
