package com.akbank.analytics.applications.bo.sdk;

import com.akbank.analytics.applications.bo.model.Credential;
import com.crystaldecisions.sdk.exception.SDKException;
import com.crystaldecisions.sdk.framework.CrystalEnterprise;
import com.crystaldecisions.sdk.framework.IEnterpriseSession;
import com.crystaldecisions.sdk.occa.infostore.IInfoStore;

/**
 * Tek bir CMS oturumu. try-with-resources ile kullanılır;
 * bloktan nasıl çıkılırsa çıkılsın logoff çalışır.
 */
public final class BoSession implements AutoCloseable {

    private final String cms;
    private final IEnterpriseSession session;

    private BoSession(String cms, IEnterpriseSession session) {
        this.cms = cms;
        this.session = session;
    }

    public static BoSession logon(String cms, Credential credential, String authType) throws SDKException {
        IEnterpriseSession s = CrystalEnterprise.getSessionMgr()
                .logon(credential.getUsername(), credential.getPassword(), cms, authType);
        return new BoSession(cms, s);
    }

    public String getCms() { return cms; }

    public IEnterpriseSession getEnterpriseSession() { return session; }

    public IInfoStore getInfoStore() throws SDKException {
        return (IInfoStore) session.getService("InfoStore");
    }

    @Override
    public void close() {
        try {
            session.logoff();
        } catch (Exception e) {
            System.err.println("Logoff sırasında hata (" + cms + "): " + e);
        }
    }
}
