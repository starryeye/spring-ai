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
	void header가_없거나_값이_틀리면_max_age가_없다() {
		assertThat(FetchedDocument.of(new byte[0], null).maxAge()).isNull();
		assertThat(FetchedDocument.of(new byte[0], "max-age=abc").maxAge()).isNull();
		assertThat(FetchedDocument.of(new byte[0], null).noStore()).isFalse();
	}
}
