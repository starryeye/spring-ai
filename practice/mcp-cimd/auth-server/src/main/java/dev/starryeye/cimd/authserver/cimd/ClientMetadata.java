package dev.starryeye.cimd.authserver.cimd;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.List;
import java.util.Set;

/**
 * 검증을 통과한 CIMD 문서의 값이다.
 *
 * @param jwksUri {@code private_key_jwt}일 때만 있다. 그 밖에는 {@code null}
 */
public record ClientMetadata(String clientId, String clientName, List<String> redirectUris,
		Set<AuthorizationGrantType> grantTypes, ClientAuthenticationMethod authenticationMethod, String jwksUri) {
}
