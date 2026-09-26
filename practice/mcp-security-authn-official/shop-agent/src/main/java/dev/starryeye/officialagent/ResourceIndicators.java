package dev.starryeye.officialagent;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.endpoint.AbstractOAuth2AuthorizationGrantRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * RFC 8707 {@code resource}를 authorization request와 token request에 넣는다.
 *
 * <p>{@code resource}는 "이 token은 이 MCP Server에 쓸 것"이라고
 * Authorization Server에 알리는 값이다.
 * 이 값이 있어야 Authorization Server가 {@code aud}를 그 서버로 좁혀 발급한다.
 * 그러면 token을 다른 resource에서 다시 쓸 수 없다.
 *
 * <p>값은 discovery 결과에서 오므로 {@link Supplier}로 받는다. 설정을 만드는 시점에는 아직 값을 모른다.
 */
public final class ResourceIndicators {

    public static final String PARAMETER = "resource";

    private ResourceIndicators() {
    }

    /** authorization request(browser redirect)에 resource를 넣는다. */
    public static Consumer<OAuth2AuthorizationRequest.Builder> authorizationRequest(Supplier<String> resource) {
        return builder -> builder.additionalParameters(parameters -> parameters.put(PARAMETER, resource.get()));
    }

    /** token request와 refresh 요청(back-channel)에 resource를 넣는다. */
    public static <T extends AbstractOAuth2AuthorizationGrantRequest> Converter<T, MultiValueMap<String, String>>
            tokenRequest(Supplier<String> resource) {
        return grantRequest -> {
            MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
            parameters.add(PARAMETER, resource.get());
            return parameters;
        };
    }
}
