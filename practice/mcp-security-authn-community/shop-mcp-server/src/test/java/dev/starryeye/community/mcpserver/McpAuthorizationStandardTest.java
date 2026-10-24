package dev.starryeye.community.mcpserver;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP authorization 명세가 resource server에 요구하는 것을 검증한다.
 * RFC 9728 Protected Resource Metadata, 401 challenge, audience 검증, Origin/Host 검증을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpAuthorizationStandardTest {

    static final String ISSUER = "http://localhost:9000";
    static final String RESOURCE = "http://localhost:8101/mcp";
    static final String HOST = "localhost:8101";
    static final String KEY_ID = "test-key";

    static final RSAKey KEY = rsaKey();

    static RSAKey rsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * JWT decoder가 Authorization Server에서 metadata와 JWKS를 받아 오는 자리에 가짜 응답을 넣는다.
     * 운영 설정(issuer-uri + audiences)은 그대로 두고 network만 끊는 것이라,
     * 실제로 켜지는 검증기(issuer·audience)를 그대로 시험한다.
     */
    @TestConfiguration
    static class StubAuthorizationServer {

        @Bean
        JwkSetUriJwtDecoderBuilderCustomizer stubbedAuthorizationServer() {
            RestTemplate restTemplate = new RestTemplate((uri, method) -> {
                String body = uri.getPath().endsWith("/jwks")
                        ? new JWKSet(KEY.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/oauth2/jwks\"}";
                MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(), HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                request.setResponse(response);
                return request;
            });
            return builder -> builder.restOperations(restTemplate);
        }
    }

    @Autowired
    MockMvc mockMvc;

    static String 토큰(String issuer, String audience) {
        return 토큰(issuer, audience, "user");
    }

    static String 토큰(String issuer, String audience, String subject) {
        return 토큰(issuer, audience, subject, Instant.now().plusSeconds(300), KEY);
    }

    static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key) {
        return 토큰(issuer, audience, subject, expiresAt, key, null);
    }

    /**
     * header의 {@code typ}을 정해 token을 만든다.
     * {@code type}이 {@code null}이면 {@code typ}을 넣지 않는다.
     */
    static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key, String type) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .build();
        JwsHeader.Builder header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID);
        if (type != null) {
            header.type(type);
        }
        return encoder.encode(JwtEncoderParameters.from(header.build(), claims)).getTokenValue();
    }

    static String typ이_있는_토큰(String type) {
        return 토큰(ISSUER, RESOURCE, "user", Instant.now().plusSeconds(300), KEY, type);
    }

    static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
            "capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""";

    static MockHttpServletRequestBuilder mcp(String token, String origin, String host) {
        MockHttpServletRequestBuilder request = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .header("Host", host)
                .content(INITIALIZE);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return request;
    }

    /**
     * {@code MCP-Protocol-Version} header만 다르게 넣어 보내는 요청을 만든다.
     * {@code protocolVersion}이 {@code null}이면 header를 아예 보내지 않는다.
     */
    static MockHttpServletRequestBuilder mcpWithProtocolVersion(String token, String protocolVersion) {
        MockHttpServletRequestBuilder request = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .header("Host", HOST)
                .header("Authorization", "Bearer " + token)
                .content(INITIALIZE);
        if (protocolVersion != null) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }
        return request;
    }

    @Test
    void 보호_리소스_메타데이터를_경로형으로_공개한다() throws Exception {
        this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", HOST))
                .andExpect(status().isOk())
                // RFC 9728 §3.3: resource는 PRM URL을 만든 resource 식별자와 같아야 한다.
                .andExpect(jsonPath("$.resource").value(RESOURCE))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                // 이 서버는 mTLS에 묶인 token을 쓰지 않는다. Spring 기본값이 true라 꺼야 한다.
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
                .andExpect(jsonPath("$.resource_name").value("shop-mcp-server"));
    }

    @Test
    void 토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다() throws Exception {
        // SecurityConfig는 module의 BearerResourceMetadataTokenAuthenticationEntryPoint를
        // official과 같은 Spring Security의 BearerTokenAuthenticationEntryPoint로 바꾼다.
        // RFC 9110 §11.2 auth-param 문법에 따르면 ":"와 "/"가 든 URL 값은 quoted-string이어야 하므로
        // resource_metadata 값은 따옴표로 감싸야 한다.
        this.mockMvc.perform(mcp(null, null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer resource_metadata=\"http://localhost:8101/.well-known/oauth-protected-resource/mcp\""));
    }

    @Test
    void aud_가_맞는_토큰이면_initialize_가_성공한다() throws Exception {
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
                .andExpect(header().exists("Mcp-Session-Id"));
    }

    /*
     * RFC 9068 Authorization Server는 access token header의 typ에 at+jwt를 넣는다.
     * 이 서버의 Authorization Server는 typ을 넣지 않으므로, typ이 없는 token도 받아야 한다.
     * 그 밖의 값은 access token이 아닌 JWT일 수 있어 거절한다.
     */

    @Test
    void typ이_없는_토큰도_받는다() throws Exception {
        // 이 서버의 Authorization Server는 typ을 넣지 않는다.
        String token = 토큰(ISSUER, RESOURCE);
        org.junit.jupiter.api.Assertions.assertNull(com.nimbusds.jwt.SignedJWT.parse(token).getHeader().getType());
        this.mockMvc.perform(mcp(token, null, HOST))
                .andExpect(status().isOk());
    }

    @Test
    void typ이_at_jwt인_토큰도_받는다() throws Exception {
        this.mockMvc.perform(mcp(typ이_있는_토큰("at+jwt"), null, HOST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }

    @Test
    void typ이_JWT인_토큰도_받는다() throws Exception {
        this.mockMvc.perform(mcp(typ이_있는_토큰("JWT"), null, HOST))
                .andExpect(status().isOk());
    }

    @Test
    void typ이_application_at_jwt인_토큰도_받는다() throws Exception {
        this.mockMvc.perform(mcp(typ이_있는_토큰("application/at+jwt"), null, HOST))
                .andExpect(status().isOk());
    }

    @Test
    void typ의_대소문자는_가리지_않는다() throws Exception {
        this.mockMvc.perform(mcp(typ이_있는_토큰("AT+JWT"), null, HOST))
                .andExpect(status().isOk());
    }

    @Test
    void typ이_그_밖의_값인_토큰은_거부한다() throws Exception {
        this.mockMvc.perform(mcp(typ이_있는_토큰("foo"), null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"invalid_token\"")))
                // iss·aud·exp가 아니라 typ 검사에서 거절됐다.
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("typ")));
    }

    @Test
    void aud_가_다른_토큰은_거부한다() throws Exception {
        // 같은 Authorization Server가 발급했지만 다른 audience(client 자신)를 향한 token이다.
        this.mockMvc.perform(mcp(토큰(ISSUER, "shop-agent"), null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"invalid_token\"")))
                // token이 있어도 유효하지 않으면 같은 entry point가 응답한다. resource_metadata 값도
                // 따옴표로 감싸져 있어야 한다(RFC 9110 §11.2 auth-param 문법).
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString(
                        "resource_metadata=\"http://localhost:8101/.well-known/oauth-protected-resource/mcp\"")));
    }

    @Test
    void iss_가_다른_토큰은_거부한다() throws Exception {
        this.mockMvc.perform(mcp(토큰("http://localhost:9999", RESOURCE), null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
    }

    @Test
    void 만료된_토큰은_거부한다() throws Exception {
        // Spring JwtTimestampValidator의 기본 clock skew는 60초다. 그보다 오래 지난 token을 쓴다.
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE, "user", Instant.now().minusSeconds(120), KEY), null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
    }

    @Test
    void 다른_키로_서명한_토큰은_거부한다() throws Exception {
        // kid는 같지만 JWKS의 public key로 signature가 맞지 않는다.
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE, "user", Instant.now().plusSeconds(300), rsaKey()), null, HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
    }

    @Test
    void token_이_없어도_허용되지_않은_Origin_은_인증보다_먼저_403이다() throws Exception {
        // module의 OriginValidationFilter는 Spring Security 앞에서 돈다. token 없는 요청도 403을 받는다.
        this.mockMvc.perform(mcp(null, "http://evil.example", HOST))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void token_이_없어도_허용되지_않은_Host_는_인증보다_먼저_421이다() throws Exception {
        this.mockMvc.perform(mcp(null, null, "evil.example:8101"))
                .andExpect(status().is(421))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void Host_를_바꾸고_그_Host_용_token_을_실어도_audience_계산_전에_421이다() throws Exception {
        // module의 AudienceValidationJwtDecoder는 기대하는 aud를 요청 URL로 계산한다. Host 검증이 없으면
        // evil Host로 계산한 aud와 token의 aud가 맞아 통과한다. OriginValidationFilter가 먼저 막는다.
        this.mockMvc.perform(mcp(토큰(ISSUER, "http://evil.example:8101/mcp"), null, "evil.example:8101"))
                .andExpect(status().is(421));
    }

    /*
     * module은 PRM의 resource와 기대하는 aud를 요청 URL로 계산한다(ResourceIdentifier).
     * official처럼 설정값으로 바꾸지 않는 대신, module의 OriginValidationFilter가 security filter chain의
     * 모든 경로에서 PRM·401보다 먼저 Host를 검사한다. 허용 목록 밖의 Host로는 다른 서버 주소를 광고받을 수 없다.
     */

    @Test
    void Host_를_바꾸면_PRM_을_만들기_전에_421이다() throws Exception {
        this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", "evil.example"))
                .andExpect(status().is(421));
    }

    @Test
    void Host_를_바꾸면_MCP_endpoint_밖의_경로도_401_을_만들기_전에_421이다() throws Exception {
        this.mockMvc.perform(get("/").header("Host", "evil.example"))
                .andExpect(status().is(421))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void 허용되지_않은_Origin_은_403이다() throws Exception {
        // module의 OriginValidationFilter는 거부 응답을 JSON-RPC 본문으로 돌려준다.
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), "http://evil.example", HOST))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid Origin header")));
    }

    @Test
    void 허용되지_않은_Host_는_421이다() throws Exception {
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, "evil.example:8101"))
                .andExpect(status().is(421));
    }

    @Test
    void Origin_없는_서버간_요청은_통과한다() throws Exception {
        this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /*
     * MCP 2025-11-25 Transports 명세의 "Protocol Version Header" 절이다.
     * SDK(WebMvcStreamableServerTransportProvider)는 이 header를 검증하지 않으므로
     * McpProtocolVersionFilter가 대신 검증한다.
     */

    @Test
    void 지원하는_MCP_Protocol_Version_헤더는_통과한다() throws Exception {
        this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), "2025-11-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }

    @Test
    void 지원하지_않는_MCP_Protocol_Version_헤더는_400이다() throws Exception {
        this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), "1999-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("1999-01-01")))
                // 오류 본문을 바이트 단위로 고정한다. docs/superpowers/captures/2026-09-12-community.txt의
                // "지원하지 않는 MCP-Protocol-Version" 단계에 기록된 본문과 같고, 안내서는 그 본문을 인용한다.
                // filter가 본문을 Jackson tree로 직렬화하므로, field 순서나 공백이 바뀌면 이 단정이 실패한다.
                .andExpect(content().string(
                        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                                + "\"message\":\"Unsupported MCP-Protocol-Version: 1999-01-01\"}}"));
    }

    @Test
    void MCP_Protocol_Version_헤더가_없으면_명세대로_통과한다() throws Exception {
        // 명세: header가 없고 버전을 알 다른 방법도 없다면 서버는 2025-03-26으로 가정한다
        // ("SHOULD assume protocol version 2025-03-26"). 거부할 이유가 아니다.
        this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), null))
                .andExpect(status().isOk());
    }

    @Test
    void 따옴표가_든_MCP_Protocol_Version_헤더_값도_구조가_깨지지_않는_JSON으로_응답한다() throws Exception {
        // 오류 메시지를 문자열 연결로 만들면 header 값에 섞인 따옴표가 JSON 구조를 깨고
        // 최상위 member를 멋대로 더할 수 있다. jsonPath 평가가 성공하면 본문이 유효한 JSON이고,
        // injected field가 없으면 injection이 통하지 않은 것이다.
        String 악성_헤더_값 = "x\"},\"injected\":{\"a\":\"b";
        this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), 악성_헤더_값))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.error.code").value(-32600))
                .andExpect(jsonPath("$.error.message")
                        .value("Unsupported MCP-Protocol-Version: " + 악성_헤더_값))
                .andExpect(jsonPath("$.injected").doesNotExist());
    }
}
