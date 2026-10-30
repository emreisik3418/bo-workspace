package com.akbank.analytics.applications.bo.webidocuments;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Web Intelligence RESTful (raylight) istemcisi. Kimlik: CMS oturumundan alınan logon token (X-SAP-LogonToken).
 *   GET /raylight/v1/documents/{SI_ID}/dataproviders  -> sorgular
 *   PUT /raylight/v1/documents/{SI_ID}  {"document":{"state":"Unused"}}  -> dokümanı sunucuda kapat
 * Not: org.json (chargebee) sürümünde JSONException checked olduğu için ayrıştırma try/catch içinde.
 */
final class WebiRestClient {

    static final class DataProvider {
        final String id;
        final String name;
        final String dataSourceId;
        final String dataSourceType;

        DataProvider(String id, String name, String dataSourceId, String dataSourceType) {
            this.id = id;
            this.name = name;
            this.dataSourceId = dataSourceId;
            this.dataSourceType = dataSourceType;
        }
    }

    /** Token yenileme: oturum süresi dolarsa (401) App yeni token verir. */
    interface TokenSource {
        String newToken() throws Exception;
    }

    private static final int CONNECT_TIMEOUT_MS = 30 * 1000;
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;

    private final String baseUrl;      // ör. http://HOST:6405/biprws
    private final TokenSource tokens;
    private String token;

    WebiRestClient(String baseUrl, TokenSource tokens) throws Exception {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tokens = tokens;
        this.token = tokens.newToken();
    }

    /** CMS adresinden (HOST:6400) varsayılan REST adresi: http://HOST:6405/biprws */
    static String defaultBaseUrl(String cms) {
        String host = cms.contains(":") ? cms.substring(0, cms.lastIndexOf(':')) : cms;
        return "http://" + host + ":6405/biprws";
    }

    List<DataProvider> dataProviders(int documentId) throws Exception {
        String body = call("GET", "/raylight/v1/documents/" + documentId + "/dataproviders", null);
        return parseDataProviders(body);
    }

    /** Dokümanı sunucu tarafında kapatır (en iyi çaba; hata fırlatır, çağıran yutar). */
    void closeDocument(int documentId) throws Exception {
        call("PUT", "/raylight/v1/documents/" + documentId, "{\"document\":{\"state\":\"Unused\"}}");
    }

    /** Bağlantı testi: sunucu ayakta ve token geçerli mi. */
    void ping() throws Exception {
        call("GET", "/raylight/v1/about", null);
    }

    private String call(String method, String path, String body) throws Exception {
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-SAP-LogonToken", "\"" + token + "\"");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                if (attempt >= 2) throw e;          // ağ hatasında bir kez daha dene
                continue;
            }
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code == 401 && attempt < 2) {       // token süresi dolmuş olabilir
                token = tokens.newToken();
                continue;
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + method + " " + path + " : " + shorten(text));
            }
            return text;
        }
    }

    /**
     * {"dataproviders":{"dataprovider":[{"id":"DP0","name":"Sorgu 1","dataSourceId":"5678","dataSourceType":"unx"}]}}
     * Tek sorguda dizi yerine nesne gelebilir; sorgu yoksa "dataprovider" hiç olmayabilir.
     */
    static List<DataProvider> parseDataProviders(String json) throws IOException {
        List<DataProvider> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject dps = root.optJSONObject("dataproviders");
            if (dps == null) return result;
            Object raw = dps.opt("dataprovider");
            List<JSONObject> items = new ArrayList<>();
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                for (int i = 0; i < a.length(); i++) items.add(a.getJSONObject(i));
            } else if (raw instanceof JSONObject) {
                items.add((JSONObject) raw);
            }
            for (JSONObject o : items) {
                result.add(new DataProvider(
                        str(o, "id"), str(o, "name"), str(o, "dataSourceId"), str(o, "dataSourceType")));
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("dataproviders yanıtı ayrıştırılamadı: " + e.getMessage() + " : " + shorten(json), e);
        }
    }

    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || JSONObject.NULL.equals(v)) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = s.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\r', ' ').replace('\n', ' ');
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
