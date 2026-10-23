package com.akbank.analytics.applications.bo.config;

import com.akbank.analytics.applications.bo.model.BoEnvironment;
import com.akbank.analytics.applications.bo.model.SboxTarget;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * credentials.json okuyucu (C# uygulamasıyla AYNI dosya).
 * Not: Projedeki org.json (chargebee) sürümünde JSONException checked olduğu için try/catch var.
 */
public final class AppConfig {

    private final String sboxEndpoint;
    private final String sboxUsername;
    private final String sboxPassword;
    private final List<BoEnvironment> environments;
    private final SboxTarget dbTarget;
    private final String dbJdbcUrl;

    private AppConfig(String sboxEndpoint, String sboxUsername, String sboxPassword,
                      List<BoEnvironment> environments, SboxTarget dbTarget, String dbJdbcUrl) {
        this.sboxEndpoint = sboxEndpoint;
        this.sboxUsername = sboxUsername;
        this.sboxPassword = sboxPassword;
        this.environments = Collections.unmodifiableList(environments);
        this.dbTarget = dbTarget;
        this.dbJdbcUrl = dbJdbcUrl;
    }

    public static AppConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Yapılandırma dosyası bulunamadı: " + file.toAbsolutePath());
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {          // Notepad'in eklediği BOM karakteri
            text = text.substring(1);
        }

        try {
            JSONObject root = new JSONObject(text);

            List<BoEnvironment> envs = new ArrayList<>();
            JSONArray envArray = root.getJSONArray("environments");
            for (int i = 0; i < envArray.length(); i++) {
                JSONObject e = envArray.getJSONObject(i);
                String cms = required(e.getJSONObject("system"), "cms");
                envs.add(new BoEnvironment(cms, target(e)));
            }
            if (envs.isEmpty()) {
                throw new IllegalArgumentException("credentials.json: 'environments' boş");
            }

            JSONArray dbArray = root.getJSONArray("db");
            if (dbArray.length() == 0) {
                throw new IllegalArgumentException("credentials.json: 'db' boş");
            }

            return new AppConfig(
                    required(root, "endpoint"),
                    required(root, "username"),
                    required(root, "password"),
                    envs,
                    target(dbArray.getJSONObject(0)),
                    required(dbArray.getJSONObject(0), "jdbcUrl"));

        } catch (JSONException ex) {
            throw new IOException("credentials.json okunamadı (" + file.toAbsolutePath() + "): "
                    + ex.getMessage(), ex);
        }
    }

    private static SboxTarget target(JSONObject o) {
        return new SboxTarget(
                required(o, "appName"),
                required(o, "subAppName"),
                required(o, "ip"),
                required(o, "sensitiveDataName"));
    }

    private static String required(JSONObject o, String key) {
        String v = o.optString(key, "").trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("credentials.json: '" + key + "' alanı eksik veya boş");
        }
        return v;
    }

    public String getSboxEndpoint()              { return sboxEndpoint; }
    public String getSboxUsername()              { return sboxUsername; }
    public String getSboxPassword()              { return sboxPassword; }
    public List<BoEnvironment> getEnvironments() { return environments; }
    public SboxTarget getDbTarget()              { return dbTarget; }
    public String getDbJdbcUrl()                 { return dbJdbcUrl; }
}
