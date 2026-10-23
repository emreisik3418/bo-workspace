package com.akbank.analytics.applications.bo.model;

/** Bir BO ortamı: CMS adresi (ör. SUNUCU:6400, aynı zamanda SYSTEM kolonu) + kimlik bilgisinin Sbox'taki yeri. */
public final class BoEnvironment {
    private final String cms;
    private final SboxTarget sbox;

    public BoEnvironment(String cms, SboxTarget sbox) {
        this.cms = cms;
        this.sbox = sbox;
    }

    public String getCms()      { return cms; }
    public SboxTarget getSbox() { return sbox; }

    @Override
    public String toString() { return cms; }
}
