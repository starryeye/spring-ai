package dev.starryeye.visibility.mcpserver;

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
 * MCP authorization 명세가 resource server에 요구하는 것들을 검증한다.
 * RFC 9728 protected resource metadata, 401 challenge, audience 검증, Origin/Host 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpAuthorizationStandardTest {

	static final String ISSUER = "http://localhost:9050";
	static final String RESOURCE = "http://localhost:8161/mcp";
	static final String HOST = "localhost:8161";
	static final String RESOURCE_METADATA = RESOURCE.replace("/mcp", "/.well-known/oauth-protected-resource/mcp");
	static final String ORIGIN = RESOURCE.replace("/mcp", "");
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
	 * JWT 디코더가 Authorization Server에서 metadata·JWKS를 받아오는 자리에 가짜 응답을 물린다.
	 * 운영 설정(issuer-uri + audiences)은 그대로 두고 네트워크만 끊는 것이라,
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
		return 토큰(issuer, audience, subject, expiresAt, key, List.of("products:read"));
	}

	static String 토큰(String issuer, String audience, String subject, Instant expiresAt, RSAKey key,
			List<String> scopes) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.audience(List.of(audience))
				.issuedAt(expiresAt.minusSeconds(600))
				.expiresAt(expiresAt)
				.claim("scope", scopes);
		// subject가 null이면 sub claim을 아예 넣지 않는다.
		// 사용자를 모르는 token을 만들 때 쓴다.
		if (subject != null) {
			claims.subject(subject);
		}
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims.build())).getTokenValue();
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
	 * {@code protocolVersion}이 {@code null}이면 header 자체를 보내지 않는다.
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
				// RFC 9728 §3.3: resource는 metadata URL을 만든 리소스 식별자와 같아야 한다.
				.andExpect(jsonPath("$.resource").value(RESOURCE))
				.andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
				.andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
				// 이 서버는 mTLS로 묶인 token을 쓰지 않는다.
				// Spring 기본값이 true라 꺼야 한다.
				.andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
				.andExpect(jsonPath("$.scopes_supported").value(org.hamcrest.Matchers.contains("products:read")));
	}

	@Test
	void 토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다() throws Exception {
		this.mockMvc.perform(mcp(null, null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer resource_metadata=\"http://localhost:8161/.well-known/oauth-protected-resource/mcp\", scope=\"products:read\""));
	}

	/*
	 * PRM의 resource와 401의 resource_metadata는 요청의 Host가 아니라 설정한 주소(audiences)로 만든다.
	 * Host로 만들면 Host를 바꾼 요청에 다른 서버 주소를 광고하게 된다(reverse proxy 뒤에서는 scheme도 틀린다).
	 */

	@Test
	void Host를_바꿔_불러도_PRM의_resource는_설정한_주소다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(RESOURCE));
	}

	@Test
	void Host를_바꿔_불러도_401의_resource_metadata는_설정한_주소다() throws Exception {
		// MCP endpoint 밖의 경로는 Host 검사(McpTransportSecurityFilter)를 거치지 않고 바로 401이 된다.
		this.mockMvc.perform(get("/").header("Host", "evil.example"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("resource_metadata=\"" + RESOURCE_METADATA + "\"")));
	}

	@Test
	void 허용한_다른_Host로_불러도_401의_resource_metadata는_설정한_주소다() throws Exception {
		this.mockMvc.perform(mcp(null, null, HOST.replace("localhost", "127.0.0.1")))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("resource_metadata=\"" + RESOURCE_METADATA + "\"")));
	}

	/*
	 * RFC 9728 §3.3: PRM의 resource는 그 PRM 주소를 만든 식별자와 같아야 한다.
	 * 루트형 주소(/.well-known/oauth-protected-resource)를 만든 식별자는 서버 origin이다.
	 * 그래서 경로 모양은 요청한 PRM 주소를 따르고, scheme·host·port만 설정에서 가져온다.
	 */

	@Test
	void 루트형_PRM의_resource는_설정한_주소의_origin이다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource").header("Host", HOST))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(ORIGIN));
	}

	@Test
	void Host를_바꿔_불러도_루트형_PRM의_resource는_설정한_origin이다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(ORIGIN));
	}

	@Test
	void 그_밖의_경로형_PRM은_설정한_origin에_요청한_경로를_붙인다() throws Exception {
		this.mockMvc.perform(get("/.well-known/oauth-protected-resource/other").header("Host", "evil.example"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resource").value(ORIGIN + "/other"));
	}

	@Test
	void aud_가_맞는_토큰이면_initialize_가_성공한다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
				.andExpect(header().doesNotExist("Mcp-Session-Id"));
	}

	@Test
	void aud_가_다른_토큰은_거부한다() throws Exception {
		// 같은 Authorization Server가 발급했지만 다른 청중(client 자신)을 향한 token이다.
		this.mockMvc.perform(mcp(토큰(ISSUER, "visibility-shop-agent"), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void iss_가_다른_토큰은_거부한다() throws Exception {
		this.mockMvc.perform(mcp(토큰("http://localhost:9999", RESOURCE), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void 만료된_토큰은_거부한다() throws Exception {
		// Spring JwtTimestampValidator 의 기본 clock skew 는 60초다.
		// 그보다 오래 지난 token 을 쓴다.
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
		// Origin 검증이 Spring Security 뒤에 있으면 token 없는 요청은 401 을 받고 Origin 검사에 닿지 않는다.
		this.mockMvc.perform(mcp(null, "http://evil.example", HOST))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void token_이_없어도_허용되지_않은_Host_는_인증보다_먼저_421이다() throws Exception {
		this.mockMvc.perform(mcp(null, null, "evil.example:8161"))
				.andExpect(status().is(421))
				.andExpect(header().doesNotExist("WWW-Authenticate"));
	}

	@Test
	void 허용되지_않은_Origin_은_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), "http://evil.example", HOST))
				.andExpect(status().isForbidden());
	}

	@Test
	void 허용되지_않은_Host_는_421이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, "evil.example:8161"))
				.andExpect(status().is(421));
	}

	@Test
	void Origin_없는_서버간_요청은_통과한다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	/*
	 * MCP 2025-11-25 전송 명세 "Protocol Version Header" 절.
	 * transport(WebMvcStatelessServerTransport)와 SDK는 이 header를 검증하지 않으므로
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
				// 오류 본문을 바이트 단위로 고정한다.
				// docs/superpowers/captures/2026-09-12-official.txt의 "지원하지 않는 MCP-Protocol-Version" 단계에 기록된 본문과 같고, 안내서는 그 본문을 인용한다.
				// filter가 본문을 Jackson tree로 직렬화하므로, field 순서나 공백이 바뀌면 이 단정이 실패한다.
				.andExpect(content().string(
						"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
								+ "\"message\":\"Unsupported MCP-Protocol-Version: 1999-01-01\"}}"));
	}

	@Test
	void MCP_Protocol_Version_헤더가_없으면_명세대로_통과한다() throws Exception {
		// 명세: header가 없고 버전을 알 다른 방법이 없다면 서버는 2025-03-26을 가정해야
		// 한다("SHOULD assume protocol version 2025-03-26") — 거부 사유가 아니다.
		this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), null))
				.andExpect(status().isOk());
	}

	@Test
	void 따옴표가_든_MCP_Protocol_Version_헤더_값도_구조가_깨지지_않는_JSON으로_응답한다() throws Exception {
		// 문자열 연결로 오류 메시지를 만들면 header 값에 섞인 따옴표가 JSON 구조를 깨고 임의의 최상위 멤버를 주입할 수 있었다.
		// jsonPath 평가 자체가 본문이 유효한 JSON임을 증명하고, injected field 부재가 주입이 통하지 않음을 증명한다.
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
