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
                .assertNext(text -> assertThat(text).isEqualTo("주문 ID nope에 해당하는 주문이 없습니다."))
                .verifyComplete();
    }

    @Test
    void 고객명에_맞는_주문이_없으면_없다고_말한다() {
        StepVerifier.create(tools.searchOrders("없는고객"))
                .assertNext(text -> assertThat(text).isEqualTo("고객명 '없는고객'에 해당하는 주문이 없습니다."))
                .verifyComplete();
    }

    @Test
    void 주문을_취소하면_상태가_실제로_바뀐다() {
        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).isEqualTo("주문 o1의 상태를 '취소됨'으로 바꿨습니다."))
                .verifyComplete();

        assertThat(repository.findById("o1")).isPresent();
        assertThat(repository.findById("o1").orElseThrow().status()).isEqualTo("취소됨");
    }

    @Test
    void 이미_취소된_주문은_다시_취소되지_않는다() {
        tools.cancelOrder("o1").block();

        StepVerifier.create(tools.cancelOrder("o1"))
                .assertNext(text -> assertThat(text).isEqualTo("주문 o1의 상태는 이미 '취소됨'입니다."))
                .verifyComplete();
    }

    @Test
    void 없는_주문은_취소할_수_없다() {
        StepVerifier.create(tools.cancelOrder("nope"))
                .assertNext(text -> assertThat(text).isEqualTo("주문 ID nope에 해당하는 주문이 없습니다."))
                .verifyComplete();
    }
}
