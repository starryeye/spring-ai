package dev.starryeye.cimd.authserver.cimd;

import java.net.URI;

/**
 * client 문서와 JWKS를 가져온다. 실패하면 {@link InvalidClientMetadataException}을 던진다.
 *
 * <p>문서와 {@code jwks_uri}를 같은 규칙으로 가져오도록 한곳에 둔다.
 * 테스트는 메모리의 문서를 돌려주는 구현으로 바꿔 끼운다.
 */
@FunctionalInterface
public interface ClientMetadataHttp {

	FetchedDocument get(URI uri);
}
