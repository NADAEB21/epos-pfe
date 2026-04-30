package tn.epos.auth_service.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    private final String accessToken;

    /** Raw refresh token — only ever returned here. Caller must treat as a secret. */
    private final String refreshToken;

    @Builder.Default
    private final String tokenType = "Bearer";
}
