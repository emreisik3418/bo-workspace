package com.akbank.analytics.applications.bo.credential;

import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.model.SboxTarget;
import org.apache.axis.client.Stub;
import org.tempuri.HsmServiceLocator;
import org.tempuri.IHsmService;
// !!! DİKKAT: OpRequestDVO, OpSysSensDataNameRequest, OpSysSensDataListDVO, OpSysSensDataDVO
// sınıfları WSDL'den üretilmiş org.datacontract... paketlerinden gelir ve paket adları repoda yok.
// Bu dosyayı kopyaladıktan sonra her birinin üzerine gelip Alt+Enter ile import edin
// (veya mevcut çalışan dosyanızdaki import satırlarını koruyun).

import java.net.URL;

/** Kimlik bilgisini Sbox (HsmService, Axis 1.4) servisinden alır. */
public final class SboxCredentialProvider implements CredentialProvider {

    private static final int TIMEOUT_MS = 30_000;   // Sbox yanıt vermezse sonsuza kadar beklemesin

    private final IHsmService service;
    private final String sboxUsername;
    private final String sboxPassword;

    public SboxCredentialProvider(String endpoint, String sboxUsername, String sboxPassword)
            throws CredentialException {
        this.sboxUsername = sboxUsername;
        this.sboxPassword = sboxPassword;
        try {
            IHsmService s = new HsmServiceLocator().getBasicHttpBinding_IHsmService(new URL(endpoint));
            ((Stub) s).setTimeout(TIMEOUT_MS);
            this.service = s;
        } catch (Exception e) {
            throw new CredentialException("Sbox servisi başlatılamadı: " + endpoint, e);
        }
    }

    @Override
    public Credential get(SboxTarget t) throws CredentialException {
        OpRequestDVO op = new OpRequestDVO();
        op.setClientIP(t.getIp());
        op.setServerIP(t.getIp());
        op.setKullName(sboxUsername);
        op.setKullPass(sboxPassword);

        OpSysSensDataNameRequest request = new OpSysSensDataNameRequest();
        request.setAppName(t.getAppName());
        request.setSubAppName(t.getSubAppName());
        request.setSensitiveDataName(t.getSensitiveDataName());
        request.setOperationRequest(op);

        OpSysSensDataListDVO response;
        try {
            response = service.getParametersWithName(request);
        } catch (Exception e) {
            throw new CredentialException("Sbox çağrısı başarısız: " + t, e);
        }

        if (response == null) {
            throw new CredentialException("Sbox boş yanıt döndü: " + t);
        }
        if (response.getOperationResult() != null && response.getOperationResult().getUserMsg() != null) {
            throw new CredentialException("Sbox hata döndü (" + t + "): "
                    + response.getOperationResult().getUserMsg());
        }

        OpSysSensDataDVO[] list = response.getSensDataList();
        if (list != null) {
            for (OpSysSensDataDVO sd : list) {
                if (sd != null && t.getSensitiveDataName().equals(sd.getMBL_SENSDATA_NAME())) {
                    return parse(sd.getMBL_SENSDATA_ENCRYPTED_VALUE(), t);
                }
            }
        }
        throw new CredentialException("Sbox'ta kayıt bulunamadı: " + t);
    }

    /** Beklenen format: "kullanici;sifre". Şifrede ';' varsa korunur. */
    private static Credential parse(String value, SboxTarget t) throws CredentialException {
        if (value == null) {
            throw new CredentialException("Sbox kaydının değeri boş: " + t);
        }
        int idx = value.indexOf(';');
        if (idx <= 0 || idx == value.length() - 1) {
            throw new CredentialException("Sbox değeri 'kullanici;sifre' formatında değil: " + t);
        }
        return new Credential(value.substring(0, idx), value.substring(idx + 1));
    }
}
