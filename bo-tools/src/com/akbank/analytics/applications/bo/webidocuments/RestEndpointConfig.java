package com.akbank.analytics.applications.bo.webidocuments;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * credentials.json'daki isteğe bağlı RESTful ayarları (ortak AppConfig'e dokunmamak için burada okunur):
 *   "system": { "cms": "HOST:6400", "rest": "https://REST-SUNUCU:6405/biprws", "restToken": "rest" }
 * - rest      : RESTful adresi (Postman'de çalışan adres; sonundaki /logon/long vb. atılır)
 * - restToken : logon / serialized / rest — verilirse sadece bu yöntem kullanılır
 * Not: org.json (chargebee) sürümünde JSONException checked olduğu için try/catch var.
 */
final class RestEndpointConfig {

    final String restUrl;     // null: tanımlı değil
    final String restToken;   // null: tanımlı değil

    private RestEndpointConfig(String restUrl, String restToken) {
        this.restUrl = restUrl;
        this.restToken = restToken;
    }

    static RestEndpointConfig read(Path credentialsFile, String cms) throws IOException {
        String text = new String(Files.readAllBytes(credentialsFile), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return parse(text, cms);
    }

    static RestEndpointConfig parse(String json, String cms) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray envs = root.getJSONArray("environments");
            for (int i = 0; i < envs.length(); i++) {
                JSONObject system = envs.getJSONObject(i).optJSONObject("system");
                if (system == null) continue;
                if (!cms.equalsIgnoreCase(system.optString("cms", "").trim())) continue;
                return new RestEndpointConfig(blankToNull(system.optString("rest", "")),
                        blankToNull(system.optString("restToken", "")));
            }
            return new RestEndpointConfig(null, null);
        } catch (JSONException e) {
            throw new IOException("credentials.json okunamadı: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String s) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? null : s;
    }
}
