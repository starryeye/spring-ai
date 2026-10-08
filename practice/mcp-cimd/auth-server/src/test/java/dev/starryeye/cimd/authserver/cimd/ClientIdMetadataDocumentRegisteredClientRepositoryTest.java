package dev.starryeye.cimd.authserver.cimd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ClientIdMetadataDocumentRegisteredClientRepositoryTest {

	static final String CHATGPT = "https://localhost:8172/oauth/client.json";

	static final String CLAUDE = "https://localhost:8172/oauth/public-client.json";

	static final String THIRD = "https://localhost:8172/oauth/third.json";

	static final String REDIRECT_URI = "http://localhost:8170/login/oauth2/code/authserver";

	static final String CHATGPT_DOCUMENT = """
			{"client_id":"https://localhost:8172/oauth/client.json","client_name":"Shop Agent (ChatGPT형)",\
			"redirect_uris":["http://localhost:8170/login/oauth2/code/authserver"],\
			"grant_types":["authorization_code","refresh_token"],"response_types":["code"],\
			"token_endpoint_auth_method":"private_key_jwt","token_endpoint_auth_signing_alg":"RS256",\
			"jwks_uri":"https://localhost:8172/oauth/jwks.json"}""";

	static final String CLAUDE_DOCUMENT = """
			{"client_id":"https://localhost:8172/oauth/public-client.json","client_name":"Shop Agent (Claude형)",\
			"redirect_uris":["http://localhost:8170/login/oauth2/code/authserver"],\
			"grant_types":["authorization_code","refresh_token"],"response_types":["code"],\
			"token_endpoint_auth_method":"none"}""";

	MutableClock clock = new MutableClock(Instant.parse("2026-10-07T00:00:00Z"));

	Map<String, FetchedDocument> documents = new HashMap<>();

	AtomicInteger fetches = new AtomicInteger();

	ClientMetadataHttp http = uri -> {
		this.fetches.incrementAndGet();
		FetchedDocument document = this.documents.get(uri.toString());
		if (document == null) {
			throw new InvalidClientMetadataException("문서가 없다: " + uri);
		}
		return document;
	};

	ClientIdMetadataDocumentRegisteredClientRepository repository = new ClientIdMetadataDocumentRegisteredClientRepository(
			new ClientIdUrlValidator("https://localhost:8172",
					host -> new InetAddress[] { InetAddress.getByName("93.184.216.34") }),
			this.http,
			new ClientIdMetadataDocumentProperties("https://localhost:8172", null, null, null, null, null, null, null),
			this.clock);

	void 문서(String url, String body, String cacheControl) {
		this.documents.put(url, FetchedDocument.of(body.getBytes(StandardCharsets.UTF_8), cacheControl));
	}

	ClientIdMetadataDocumentRegisteredClientRepository 상한이_있는_저장소(int maxCacheEntries) {
		return new ClientIdMetadataDocumentRegisteredClientRepository(
				new ClientIdUrlValidator("https://localhost:8172",
						host -> new InetAddress[] { InetAddress.getByName("93.184.216.34") }),
				this.http,
				new ClientIdMetadataDocumentProperties("https://localhost:8172", null, null, null, null, null, null, null),
				this.clock, maxCacheEntries);
	}

	@Test
	void 처음_보는_client_id면_문서를_가져와_RegisteredClient로_바꾼다() {
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=300");

		RegisteredClient client = this.repository.findByClientId(CHATGPT);

		assertThat(client.getId()).isEqualTo(CHATGPT);
		assertThat(client.getClientId()).isEqualTo(CHATGPT);
		assertThat(client.getClientName()).isEqualTo("Shop Agent (ChatGPT형)");
		assertThat(client.getRedirectUris()).containsExactly(REDIRECT_URI);
		assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
		assertThat(client.getAuthorizationGrantTypes())
				.containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
		assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "products:read", "products:write", "orders:write");
		assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
		assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
		assertThat(client.getClientSettings().getJwkSetUrl()).isEqualTo("https://localhost:8172/oauth/jwks.json");
		assertThat(client.getClientSettings().getTokenEndpointAuthenticationSigningAlgorithm())
				.isEqualTo(SignatureAlgorithm.RS256);
		assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
	}

	@Test
	void Claude형_문서는_none_client가_된다() {
		문서(CLAUDE, CLAUDE_DOCUMENT, null);

		RegisteredClient client = this.repository.findByClientId(CLAUDE);

		assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
		assertThat(client.getClientSettings().getJwkSetUrl()).isNull();
	}

	@Test
	void cache_기간_안에는_다시_가져오지_않는다() {
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=300");

		this.repository.findByClientId(CHATGPT);
		this.clock.advance(Duration.ofSeconds(299));
		this.repository.findByClientId(CHATGPT);
		assertThat(this.fetches).hasValue(1);

		this.clock.advance(Duration.ofSeconds(1));
		this.repository.findByClientId(CHATGPT);
		assertThat(this.fetches).hasValue(2);
	}

	@Test
	void max_age가_상한을_넘으면_상한까지만_cache한다() {
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=86400");

		this.repository.findByClientId(CHATGPT);
		this.clock.advance(Duration.ofHours(1));
		this.repository.findByClientId(CHATGPT);

		assertThat(this.fetches).hasValue(2);
	}

	@Test
	void Cache_Control이_없으면_기본_기간만큼_cache한다() {
		문서(CLAUDE, CLAUDE_DOCUMENT, null);

		this.repository.findByClientId(CLAUDE);
		this.clock.advance(Duration.ofMinutes(4));
		this.repository.findByClientId(CLAUDE);
		assertThat(this.fetches).hasValue(1);

		this.clock.advance(Duration.ofMinutes(1));
		this.repository.findByClientId(CLAUDE);
		assertThat(this.fetches).hasValue(2);
	}

	@Test
	void no_store면_cache하지_않는다() {
		문서(CLAUDE, CLAUDE_DOCUMENT, "no-store");

		this.repository.findByClientId(CLAUDE);
		this.repository.findByClientId(CLAUDE);

		assertThat(this.fetches).hasValue(2);
	}

	@Test
	void 실패는_cache하지_않고_다음_요청에_다시_가져온다() {
		문서(CLAUDE, CLAUDE_DOCUMENT.replace("public-client.json\",\"client_name", "other.json\",\"client_name"), "max-age=300");
		assertThat(this.repository.findByClientId(CLAUDE)).isNull();

		문서(CLAUDE, CLAUDE_DOCUMENT, "max-age=300");
		assertThat(this.repository.findByClientId(CLAUDE)).isNotNull();
		assertThat(this.fetches).hasValue(2);
	}

	@Test
	void URL이_아닌_client_id는_문서를_가져오지_않는다() {
		assertThat(this.repository.findByClientId("cimd-shop-agent")).isNull();
		assertThat(this.repository.findByClientId(null)).isNull();
		assertThat(this.fetches).hasValue(0);
	}

	@Test
	void 주소_규칙을_어기는_client_id는_가져오지_않는다() {
		assertThat(this.repository.findByClientId("http://localhost:8172/oauth/client.json")).isNull();
		assertThat(this.fetches).hasValue(0);
	}

	@Test
	void JSON_object가_아니면_쓰지_않는다() {
		문서(CLAUDE, "[]", null);
		assertThat(this.repository.findByClientId(CLAUDE)).isNull();

		문서(CLAUDE, "not json", null);
		assertThat(this.repository.findByClientId(CLAUDE)).isNull();
	}

	@Test
	void findById는_findByClientId와_같은_client를_돌려준다() {
		문서(CLAUDE, CLAUDE_DOCUMENT, "max-age=300");

		assertThat(this.repository.findById(CLAUDE)).isEqualTo(this.repository.findByClientId(CLAUDE));
	}

	@Test
	void 새_client를_cache할_때_만료된_항목은_치운다() {
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=60");
		문서(CLAUDE, CLAUDE_DOCUMENT, "max-age=300");

		this.repository.findByClientId(CHATGPT);
		assertThat(this.repository.cacheSize()).isEqualTo(1);

		this.clock.advance(Duration.ofSeconds(61));
		this.repository.findByClientId(CLAUDE);

		assertThat(this.repository.cacheSize()).isEqualTo(1);
		this.repository.findByClientId(CLAUDE);
		assertThat(this.fetches).hasValue(2);
		this.repository.findByClientId(CHATGPT);
		assertThat(this.fetches).hasValue(3);
	}

	@Test
	void cache가_가득_차면_새_client는_돌려주되_cache하지_않는다() {
		ClientIdMetadataDocumentRegisteredClientRepository limited = 상한이_있는_저장소(2);
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=300");
		문서(CLAUDE, CLAUDE_DOCUMENT, "max-age=300");
		문서(THIRD, CLAUDE_DOCUMENT.replace("public-client.json", "third.json"), "max-age=300");

		limited.findByClientId(CHATGPT);
		limited.findByClientId(CLAUDE);
		assertThat(limited.cacheSize()).isEqualTo(2);

		assertThat(limited.findByClientId(THIRD)).isNotNull();
		assertThat(limited.cacheSize()).isEqualTo(2);
		assertThat(this.fetches).hasValue(3);

		limited.findByClientId(THIRD);
		assertThat(this.fetches).hasValue(4);

		limited.findByClientId(CHATGPT);
		limited.findByClientId(CLAUDE);
		assertThat(this.fetches).hasValue(4);
	}

	@Test
	void 자리가_나면_다시_cache한다() {
		ClientIdMetadataDocumentRegisteredClientRepository limited = 상한이_있는_저장소(1);
		문서(CHATGPT, CHATGPT_DOCUMENT, "max-age=60");
		문서(CLAUDE, CLAUDE_DOCUMENT, "max-age=300");

		limited.findByClientId(CHATGPT);
		limited.findByClientId(CLAUDE);
		assertThat(this.fetches).hasValue(2);

		this.clock.advance(Duration.ofSeconds(60));
		limited.findByClientId(CLAUDE);
		limited.findByClientId(CLAUDE);

		assertThat(this.fetches).hasValue(3);
	}

	@Test
	void 로그에_남기는_값에서_제어_문자를_없애고_길이를_줄인다() {
		assertThat(ClientIdMetadataDocumentRegisteredClientRepository.forLog("https://a/b\n2026-10-07 ERROR forged\r\t\u2028"))
				.isEqualTo("https://a/b?2026-10-07 ERROR forged???");
		assertThat(ClientIdMetadataDocumentRegisteredClientRepository.forLog(null)).isEqualTo("null");
		assertThat(ClientIdMetadataDocumentRegisteredClientRepository.forLog("https://localhost:8172/ok.json"))
				.isEqualTo("https://localhost:8172/ok.json");

		String truncated = ClientIdMetadataDocumentRegisteredClientRepository.forLog("a".repeat(5000));
		assertThat(truncated).startsWith("a".repeat(200) + "...").hasSizeLessThan(260).doesNotContain("a".repeat(201));
	}

	@Test
	void 줄바꿈이_든_client_id는_WARN_로그에_한_줄로_남는다() {
		Logger logger = (Logger) LoggerFactory.getLogger(ClientIdMetadataDocumentRegisteredClientRepository.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThat(this.repository.findByClientId("https://a/b\n2026-10-07 ERROR forged")).isNull();

			List<ILoggingEvent> warnings = appender.list.stream().filter((event) -> event.getLevel() == Level.WARN).toList();
			assertThat(warnings).hasSize(1);
			assertThat(warnings.get(0).getFormattedMessage()).doesNotContain("\n").doesNotContain("\r").contains("forged");
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void save는_지원하지_않는다() {
		문서(CLAUDE, CLAUDE_DOCUMENT, null);
		RegisteredClient client = this.repository.findByClientId(CLAUDE);

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> this.repository.save(client));
	}
}
