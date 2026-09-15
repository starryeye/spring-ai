package dev.starryeye.memoryauthn.mcpserver;

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
 * MCP 인가 명세가 리소스 서버에 요구하는 것들을 검증한다.
 * RFC 9728 보호 리소스 메타데이터, 401 챌린지, audience 검증, Origin/Host 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpAuthorizationStandardTest {

	static final String ISSUER = "http://localhost:9020";
	static final String RESOURCE = "http://localhost:8131/mcp";
	static final String HOST = "localhost:8131";
	static final String KEY_ID = "test-key";

	static final RSAKey KEY;

	static {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			var keyPair = generator.generateKeyPair();
			KEY = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyID(KEY_ID)
					.build();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * JWT 디코더가 인가 서버에서 메타데이터·JWKS 를 받아오는 자리에 가짜 응답을 물린다.
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
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject("user")
				.audience(List.of(audience))
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300))
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
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
	 * {@code MCP-Protocol-Version} 헤더만 다르게 실어 보내는 요청을 만든다.
	 * {@code protocolVersion} 이 {@code null} 이면 헤더 자체를 보내지 않는다.
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
				// RFC 9728 §3.3: resource 는 메타데이터 URL 을 만든 리소스 식별자와 같아야 한다.
				.andExpect(jsonPath("$.resource").value(RESOURCE))
				.andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
				.andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
				// 이 서버는 mTLS 로 묶인 토큰을 쓰지 않는다. Spring 기본값이 true 라 꺼야 한다.
				.andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
	}

	@Test
	void 토큰_없는_요청의_챌린지가_경로형_메타데이터를_가리킨다() throws Exception {
		this.mockMvc.perform(mcp(null, null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						"Bearer resource_metadata=\"http://localhost:8131/.well-known/oauth-protected-resource/mcp\""));
	}

	@Test
	void aud_가_맞는_토큰이면_initialize_가_성공한다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, HOST))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
				.andExpect(header().exists("Mcp-Session-Id"));
	}

	@Test
	void aud_가_다른_토큰은_거부한다() throws Exception {
		// 같은 인가 서버가 발급했지만 다른 청중(클라이언트 자신)을 향한 토큰이다.
		this.mockMvc.perform(mcp(토큰(ISSUER, "memory-agent"), null, HOST))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate",
						org.hamcrest.Matchers.containsString("error=\"invalid_token\"")));
	}

	@Test
	void iss_가_다른_토큰은_거부한다() throws Exception {
		this.mockMvc.perform(mcp(토큰("http://localhost:9999", RESOURCE), null, HOST))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 허용되지_않은_Origin_은_403이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), "http://evil.example", HOST))
				.andExpect(status().isForbidden());
	}

	@Test
	void 허용되지_않은_Host_는_421이다() throws Exception {
		this.mockMvc.perform(mcp(토큰(ISSUER, RESOURCE), null, "evil.example:8131"))
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
	 * SDK(WebMvcStreamableServerTransportProvider)는 이 헤더를 검증하지 않으므로
	 * McpProtocolVersionFilter 가 대신 검증한다.
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
				.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("1999-01-01")));
	}

	@Test
	void MCP_Protocol_Version_헤더가_없으면_명세대로_통과한다() throws Exception {
		// 명세: 헤더가 없고 버전을 알 다른 방법이 없다면 서버는 2025-03-26 을 가정해야
		// 한다("SHOULD assume protocol version 2025-03-26") — 거부 사유가 아니다.
		this.mockMvc.perform(mcpWithProtocolVersion(토큰(ISSUER, RESOURCE), null))
				.andExpect(status().isOk());
	}
}
