package com.akbank.analytics.applications.bo.model;

/** Sbox'ta tek bir kimlik bilgisi kaydını tarif eder. */
public final class SboxTarget {
    private final String appName;
    private final String subAppName;
    private final String ip;
    private final String sensitiveDataName;

    public SboxTarget(String appName, String subAppName, String ip, String sensitiveDataName) {
        this.appName = appName;
        this.subAppName = subAppName;
        this.ip = ip;
        this.sensitiveDataName = sensitiveDataName;
    }

    public String getAppName()           { return appName; }
    public String getSubAppName()        { return subAppName; }
    public String getIp()                { return ip; }
    public String getSensitiveDataName() { return sensitiveDataName; }

    @Override
    public String toString() {
        return appName + "/" + subAppName + "/" + sensitiveDataName;
    }
}
