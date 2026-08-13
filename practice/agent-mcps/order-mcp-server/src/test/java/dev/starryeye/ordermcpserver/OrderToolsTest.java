package dev.starryeye.ordermcpserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolsTest {

    private OrderRepository repository;
    private OrderTools tools;

    @BeforeEach
    void setUp() {
        repository = new OrderRepository();
        tools = new OrderTools(repository);
    }

    @Test
    void 고객명으로_주문을_검색한다() {
        StepVerifier.create(tools.searchOrders("홍길동"))
                .assertNext(text -> assertThat(text)
                        .contains("o1")
                        .contains("o2")
                        .doesNotContain("o3"))
                .verifyComplete();
    }

    @Test
    void 고객명을_생략하면_전체를_반환한다() {
        StepVerifier.create(tools.searchOrders(null))
                .assertNext(text -> assertThat(text)
                        .contains("o1").contains("o2").contains("o3"))
                .verifyComplete();
    }

    @Test
    void 주문_상세는_상품_ID_를_포함한다() {
        StepVerifier.create(tools.getOrder("o1"))
                .assertNext(text -> assertThat(text)
                        .contains("o1")
                        .contains("p1")
                        .contains("결제완료"))
                .verifyComplete();
    }

    @Test
    void 없는_주문이면_찾을_수_없다고_말한다() {
        StepVerifier.create(tools.getOrder("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }

    @Test
    void 주문을_취소하면_상태가_실제로_바뀐다() {
        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).contains("취소"))
                .verifyComplete();

        assertThat(repository.findById("o1")).isPresent();
        assertThat(repository.findById("o1").orElseThrow().status()).isEqualTo("취소됨");
    }

    @Test
    void 이미_취소된_주문은_다시_취소되지_않는다() {
        tools.cancelOrder("o1").block();

        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).contains("이미 취소"))
                .verifyComplete();
    }

    @Test
    void 없는_주문은_취소할_수_없다() {
        StepVerifier.create(tools.cancelOrder("nope"))
                .assertNext(text -> assertThat(text).contains("찾을 수 없습니다"))
                .verifyComplete();
    }
}
