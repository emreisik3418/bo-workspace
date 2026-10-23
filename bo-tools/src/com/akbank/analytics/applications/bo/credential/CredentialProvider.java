package com.akbank.analytics.applications.bo.credential;

import com.akbank.analytics.applications.bo.model.Credential;
import com.akbank.analytics.applications.bo.model.SboxTarget;

public interface CredentialProvider {
    Credential get(SboxTarget target) throws CredentialException;
}
