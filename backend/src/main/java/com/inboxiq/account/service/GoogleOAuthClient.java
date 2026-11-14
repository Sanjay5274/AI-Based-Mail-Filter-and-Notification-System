package com.inboxiq.account.service;

import com.inboxiq.account.model.GoogleTokenResponse;
import com.inboxiq.account.model.GoogleUserInfo;

public interface GoogleOAuthClient {

    GoogleTokenResponse exchangeCode(String code, String codeVerifier, String redirectUri);

    GoogleUserInfo getUserInfo(String accessToken);

    boolean revokeToken(String plainTextToken);
}

