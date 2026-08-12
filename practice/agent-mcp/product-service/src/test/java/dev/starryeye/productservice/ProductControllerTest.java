package dev.starryeye.productservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ProductControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void 키워드로_상품을_검색한다() {
        webTestClient.get().uri("/api/products?keyword=노트북")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Product.class)
                .value(products -> assertThat(products)
                        .isNotEmpty()
                        .allSatisfy(product ->
                                assertThat(product.name()).contains("노트북")));
    }

    @Test
    void 키워드가_없으면_전체_목록을_반환한다() {
        webTestClient.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Product.class)
                .value(products -> assertThat(products).hasSizeGreaterThanOrEqualTo(5));
    }

    @Test
    void 상품_ID_로_재고를_조회한다() {
        webTestClient.get().uri("/api/products/p1/stock")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.productId").isEqualTo("p1")
                .jsonPath("$.stock").isNumber();
    }

    @Test
    void 존재하지_않는_상품은_404_를_반환한다() {
        webTestClient.get().uri("/api/products/nope")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void 존재하지_않는_상품의_재고도_404_를_반환한다() {
        webTestClient.get().uri("/api/products/nope/stock")
                .exchange()
                .expectStatus().isNotFound();
    }
}
