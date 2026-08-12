package dev.starryeye.productmcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpToolParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ProductToolsTest {

    private ProductTools toolsWith(ProductClient client) {
        return new ProductTools(client);
    }

    @Test
    void 검색_결과를_사람이_읽을_수_있는_문장으로_반환한다() {
        ProductTools tools = toolsWith(new StubProductClient(
                Flux.just(new Product("p1", "게이밍 노트북 15인치", "노트북", 1_890_000, 7)),
                Mono.just(7)));

        StepVerifier.create(tools.searchProducts("노트북"))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("p1"))
                .verifyComplete();
    }

    @Test
    void 검색_결과가_없으면_없다고_말한다() {
        ProductTools tools = toolsWith(new StubProductClient(Flux.empty(), Mono.empty()));

        StepVerifier.create(tools.searchProducts("없는상품"))
                .assertNext(text -> assertThat(text).contains("없습니다"))
                .verifyComplete();
    }

    @Test
    void 키워드_없이_호출하면_전체_상품을_반환한다() throws NoSuchMethodException {
        RecordingProductClient client = new RecordingProductClient(Flux.just(
                new Product("p1", "게이밍 노트북 15인치", "노트북", 1_890_000, 7),
                new Product("p4", "인체공학 마우스", "주변기기", 59_000, 145)));
        ProductTools tools = toolsWith(client);

        StepVerifier.create(tools.searchProducts(null))
                .assertNext(text -> assertThat(text)
                        .contains("게이밍 노트북 15인치")
                        .contains("인체공학 마우스")
                        .doesNotContain("없습니다"))
                .verifyComplete();

        assertThat(client.lastKeyword).isNull();

        McpToolParam param = ProductTools.class
                .getDeclaredMethod("searchProducts", String.class)
                .getParameters()[0]
                .getAnnotation(McpToolParam.class);
        assertThat(param.required())
                .as("keyword 가 필수면 모델이 전체 목록 질문에도 키워드를 지어내야 한다")
                .isFalse();
    }

    @Test
    void 재고가_0이면_품절이라고_말한다() {
        ProductTools tools = toolsWith(new StubProductClient(Flux.empty(), Mono.just(0)));

        StepVerifier.create(tools.getStock("p3"))
                .assertNext(text -> assertThat(text)
                        .contains("품절")
                        .contains("p3"))
                .verifyComplete();
    }

    @Test
    void 재고_수량을_문장으로_반환한다() {
        ProductTools tools = toolsWith(new StubProductClient(Flux.empty(), Mono.just(7)));

        StepVerifier.create(tools.getStock("p1"))
                .assertNext(text -> assertThat(text).contains("7"))
                .verifyComplete();
    }

    @Test
    void 상품이_없으면_찾을_수_없다고_말한다() {
        ProductTools tools = toolsWith(new StubProductClient(Flux.empty(), Mono.empty()));

        StepVerifier.create(tools.getStock("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }

    @Test
    void 서비스_장애시_예외_대신_설명_문장을_반환한다() {
        ProductTools tools = toolsWith(new StubProductClient(
                Flux.error(new RuntimeException("connection refused")),
                Mono.error(new RuntimeException("connection refused"))));

        StepVerifier.create(tools.searchProducts("노트북"))
                .assertNext(text -> assertThat(text).contains("조회할 수 없습니다"))
                .verifyComplete();

        StepVerifier.create(tools.getStock("p1"))
                .assertNext(text -> assertThat(text).contains("조회할 수 없습니다"))
                .verifyComplete();
    }

    private record StubProductClient(Flux<Product> products, Mono<Integer> stock)
            implements ProductClient {

        @Override
        public Flux<Product> search(String keyword) {
            return products;
        }

        @Override
        public Mono<Integer> stockOf(String productId) {
            return stock;
        }
    }

    private static final class RecordingProductClient implements ProductClient {

        private final Flux<Product> products;
        private String lastKeyword = "not-called";

        private RecordingProductClient(Flux<Product> products) {
            this.products = products;
        }

        @Override
        public Flux<Product> search(String keyword) {
            this.lastKeyword = keyword;
            return products;
        }

        @Override
        public Mono<Integer> stockOf(String productId) {
            return Mono.empty();
        }
    }
}
