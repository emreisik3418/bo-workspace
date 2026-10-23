package com.akbank.analytics.applications.bo.common;

import com.akbank.analytics.applications.bo.app.RunLog;
import com.akbank.analytics.applications.bo.config.AppConfig;
import com.akbank.analytics.applications.bo.model.BoEnvironment;
import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.sdk.BoSession;

import java.util.ArrayList;
import java.util.List;

/** Tüm uygulamaların ortak kullandığı ortam seçimi ve yeniden denemeli CMS logon'u. */
public final class BoLogon {

    public static final String AUTH_TYPE = System.getProperty("bo.authType", "secEnterprise");
    public static final int LOGON_ATTEMPTS = 4;
    public static final long LOGON_RETRY_WAIT_MS = 2 * 60 * 1000L;

    private BoLogon() { }

    /** --env verilmediyse ve tek ortam varsa onu, verildiyse eşleşeni döner; bulunamazsa null. */
    public static BoEnvironment pickEnvironment(AppConfig config, String envArg) {
        if (envArg == null) {
            return config.getEnvironments().size() == 1 ? config.getEnvironments().get(0) : null;
        }
        for (BoEnvironment e : config.getEnvironments()) {
            if (e.getCms().equalsIgnoreCase(envArg.trim())) return e;
        }
        return null;
    }

    /** credentials.json'daki ortamların CMS listesi (hata mesajları için). */
    public static String cmsList(AppConfig config) {
        List<String> l = new ArrayList<>();
        for (BoEnvironment e : config.getEnvironments()) l.add(e.getCms());
        return String.join(", ", l);
    }

    /** Geçici hatalarda (lisans dolu, ağ vb.) LOGON_ATTEMPTS kez, LOGON_RETRY_WAIT_MS arayla dener. */
    public static BoSession logonWithRetry(String cms, Credential credential, RunLog log) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return BoSession.logon(cms, credential, AUTH_TYPE);
            } catch (Exception e) {
                if (attempt >= LOGON_ATTEMPTS) throw e;
                log.warn(String.format("[%s] CMS logon başarısız (deneme %d/%d), %d dk sonra tekrar denenecek: %s",
                        cms, attempt, LOGON_ATTEMPTS, LOGON_RETRY_WAIT_MS / 60000, RunLog.describe(e)));
                Thread.sleep(LOGON_RETRY_WAIT_MS);
            }
        }
    }
}
