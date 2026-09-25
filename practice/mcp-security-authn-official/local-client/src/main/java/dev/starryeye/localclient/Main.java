package dev.starryeye.localclient;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 사용자 기기에서 도는 MCP client(Claude Desktop·Cursor 같은 public client)의 흐름을 한 번 밟는다(안내서 7장).
 *
 * <p>discovery → browser login과 consent → loopback callback → client_secret 없는 token request → MCP 호출.
 */
public final class Main {

	static final String CLIENT_ID = "local-mcp-client";

	/** public client는 openid 말고 consent할 scope가 하나는 있어야 한다. */
	static final String SCOPE = "openid profile";

	static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

	private static final SecureRandom RANDOM = new SecureRandom();

	private Main() {
	}

	record Options(String resourceUrl, String issuer, boolean openBrowser) {

		static Options parse(String[] args) {
			String resource = "http://localhost:8111/mcp";
			String issuer = "http://localhost:9010";
			boolean browser = true;
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--resource" -> resource = value(args, ++i, "--resource");
					case "--issuer" -> issuer = value(args, ++i, "--issuer");
					case "--no-browser" -> browser = false;
					default -> throw new LocalClientException("모르는 인자다: " + args[i]);
				}
			}
			return new Options(resource, issuer, browser);
		}

		private static String value(String[] args, int index, String name) {
			if (index >= args.length) {
				throw new LocalClientException(name + " 뒤에 값이 없다");
			}
			return args[index];
		}
	}

	public static void main(String[] args) {
		try {
			run(Options.parse(args), System.out);
		}
		catch (LocalClientException ex) {
			System.err.println("실패: " + ex.getMessage());
			System.exit(1);
		}
	}

	static void run(Options options, PrintStream out) {
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

		out.println("[1] discovery: " + options.resourceUrl());
		AuthorizationServer server = new Discovery(http).discover(options.resourceUrl(), options.issuer());
		out.println("    Authorization Server: " + server.issuer());

		Pkce pkce = Pkce.generate();
		String state = randomState();
		try (LoopbackCallbackServer callback = LoopbackCallbackServer.start()) {
			URI authorization = AuthorizationRequest.uri(server, CLIENT_ID, callback.redirectUri(), SCOPE, state, pkce);
			out.println("[2] browser에서 login과 consent를 한다");
			out.println("    " + authorization);
			if (options.openBrowser()) {
				Browser.open(authorization, out);
			}
			String code = AuthorizationResponse.code(callback.await(LOGIN_TIMEOUT), state, server);
			out.println("[3] callback으로 authorization code를 받았다: " + callback.redirectUri());

			TokenResponse token = new TokenClient(http).exchange(server, CLIENT_ID, code, callback.redirectUri(), pkce);
			out.println("[4] client_secret 없이 access token을 받았다(" + token.expiresIn() + "초 뒤 만료)");

			out.println("[5] MCP 호출");
			McpCalls.run(options.resourceUrl(), token.accessToken(), out);
		}
		catch (IOException ex) {
			throw new LocalClientException("callback server를 열지 못했다", ex);
		}
	}

	private static String randomState() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
