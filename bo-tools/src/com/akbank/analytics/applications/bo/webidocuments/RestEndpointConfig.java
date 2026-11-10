package com.akbank.analytics.applications.bo.webidocuments;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * credentials.json'daki isteğe bağlı RESTful ayarları (ortak AppConfig'e dokunmamak için burada okunur).
 * Adres anahtarı "biprwsUrl" (mevcut dosyalarda olan) ya da "rest"; şu sırayla aranır:
 *   1) eşleşen ortamın "system" nesnesi   2) eşleşen ortamın kendisi   3) dosyanın kökü
 * - restToken : logon / serialized / rest — verilirse sadece bu yöntem kullanılır (aynı yerlerde aranır)
 * Not: org.json (chargebee) sürümünde JSONException checked olduğu için try/catch var.
 */
final class RestEndpointConfig {

    private static final String[] URL_KEYS = {"biprwsUrl", "rest"};

    final String restUrl;     // null: tanımlı değil
    final String restToken;   // null: tanımlı değil
    final String source;      // logda gösterilir, ör. "system.biprwsUrl"

    private RestEndpointConfig(String restUrl, String restToken, String source) {
        this.restUrl = restUrl;
        this.restToken = restToken;
        this.source = source;
    }

    static RestEndpointConfig read(Path credentialsFile, String cms) throws IOException {
        String text = new String(Files.readAllBytes(credentialsFile), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return parse(text, cms);
    }

    static RestEndpointConfig parse(String json, String cms) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject env = null;
            JSONArray envs = root.getJSONArray("environments");
            for (int i = 0; i < envs.length(); i++) {
                JSONObject e = envs.getJSONObject(i);
                JSONObject system = e.optJSONObject("system");
                if (system != null && cms.equalsIgnoreCase(system.optString("cms", "").trim())) {
                    env = e;
                    break;
                }
            }
            JSONObject system = env == null ? null : env.optJSONObject("system");
            JSONObject[] places = {system, env, root};
            String[] names = {"system.", "environments[].", ""};
            String url = null, token = null, source = null;
            for (int p = 0; p < places.length; p++) {
                if (places[p] == null) continue;
                for (String key : URL_KEYS) {
                    String v = blankToNull(places[p].optString(key, ""));
                    if (url == null && v != null) {
                        url = v;
                        source = names[p] + key;
                    }
                }
                if (token == null) token = blankToNull(places[p].optString("restToken", ""));
            }
            return new RestEndpointConfig(url, token, source);
        } catch (JSONException e) {
            throw new IOException("credentials.json okunamadı: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String s) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? null : s;
    }
}
