package com.camel_hub.advertisement.identity.service;

import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.AccessTokenService;

public record AuthenticationResult(
		AccessTokenService.IssuedAccessToken accessToken,
		AuthenticatedUser user,
		String refreshToken
) {
}
