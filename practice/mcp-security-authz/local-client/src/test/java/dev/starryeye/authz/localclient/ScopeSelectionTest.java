package dev.starryeye.authz.localclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeSelectionTest {

	@Test
	void challenge의_scope가_먼저다() {
		ScopeSelection selection = ScopeSelection.select("products:read", List.of("products:read", "products:write"));

		assertThat(selection.scopes()).containsExactly("products:read");
		assertThat(selection.describe()).isEqualTo("products:read (401의 scope)");
	}

	@Test
	void challenge에_없으면_scopes_supported_전부다() {
		ScopeSelection selection = ScopeSelection.select(null, List.of("products:read", "products:write"));

		assertThat(selection.scopes()).containsExactly("products:read", "products:write");
		assertThat(selection.describe()).isEqualTo("products:read products:write (PRM의 scopes_supported)");
	}

	@Test
	void 둘_다_없으면_scope를_보내지_않는다() {
		ScopeSelection selection = ScopeSelection.select(" ", null);

		assertThat(selection.scopes()).isEmpty();
		assertThat(selection.describe()).isEqualTo("없음 (scope parameter를 보내지 않는다)");
	}
}
