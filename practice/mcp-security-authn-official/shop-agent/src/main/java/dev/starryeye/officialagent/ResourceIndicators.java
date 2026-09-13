package dev.starryeye.officialagent;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.endpoint.AbstractOAuth2AuthorizationGrantRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * RFC 8707 {@code resource} 를 인가·토큰 요청에 싣는다.
 *
 * <p>"이 토큰은 이 MCP 서버에 쓸 것"이라고 인가 서버에 알리는 값이다. 이것이 있어야
 * 인가 서버가 {@code aud} 를 그 서버로 좁혀 발급하고, 토큰이 다른 리소스에서 재사용되지 않는다.
 *
 * <p>값은 발견 결과에서 오므로 {@link Supplier} 로 받는다 — 설정 시점에는 아직 모른다.
 */
public final class ResourceIndicators {

    public static final String PARAMETER = "resource";

    private ResourceIndicators() {
    }

    /** 인가 요청(브라우저 리다이렉트)에 resource 를 싣는다. */
    public static Consumer<OAuth2AuthorizationRequest.Builder> authorizationRequest(Supplier<String> resource) {
        return builder -> builder.additionalParameters(parameters -> parameters.put(PARAMETER, resource.get()));
    }

    /** 토큰·갱신 요청(백채널)에 resource 를 싣는다. */
    public static <T extends AbstractOAuth2AuthorizationGrantRequest> Converter<T, MultiValueMap<String, String>>
            tokenRequest(Supplier<String> resource) {
        return grantRequest -> {
            MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
            parameters.add(PARAMETER, resource.get());
            return parameters;
        };
    }
}
