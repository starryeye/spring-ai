package dev.starryeye.cimd.authserver.cimd;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FetchedDocumentTest {

	@Test
	void max_age와_no_store를_읽는다() {
		assertThat(FetchedDocument.of(new byte[0], "public, max-age=300").maxAge()).isEqualTo(Duration.ofSeconds(300));
		assertThat(FetchedDocument.of(new byte[0], "no-store").noStore()).isTrue();
		assertThat(FetchedDocument.of(new byte[0], "Max-Age=60").maxAge()).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void no_cache는_no_store처럼_cache하지_않는다() {
		// no-cache는 다시 쓰기 전에 문서 host에 확인하라는 뜻이다. 이 서버는 확인 요청을 보내지 않는다.
		assertThat(FetchedDocument.of(new byte[0], "no-cache").noStore()).isTrue();
		assertThat(FetchedDocument.of(new byte[0], "max-age=300, No-Cache").noStore()).isTrue();
	}

	@Test
	void header가_없거나_값이_틀리면_max_age가_없다() {
		assertThat(FetchedDocument.of(new byte[0], null).maxAge()).isNull();
		assertThat(FetchedDocument.of(new byte[0], "max-age=abc").maxAge()).isNull();
		assertThat(FetchedDocument.of(new byte[0], null).noStore()).isFalse();
	}

	@Test
	void long_범위를_넘는_max_age는_없는_값으로_본다() {
		FetchedDocument document = FetchedDocument.of(new byte[0], "max-age=99999999999999999999, no-store");

		assertThat(document.maxAge()).isNull();
		assertThat(document.noStore()).isTrue();
	}
}
