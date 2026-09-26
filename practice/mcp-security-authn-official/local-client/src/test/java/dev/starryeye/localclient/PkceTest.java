package dev.starryeye.localclient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

	@Test
	void RFC_7636_부록_B의_값과_같은_challenge를_만든다() {
		Pkce pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

		assertThat(pkce.challenge()).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
	}

	@Test
	void verifier는_43자의_URL_안전_문자이고_매번_다르다() {
		Pkce first = Pkce.generate();
		Pkce second = Pkce.generate();

		assertThat(first.verifier()).matches("[A-Za-z0-9._~-]{43}");
		assertThat(first.verifier()).isNotEqualTo(second.verifier());
	}
}
