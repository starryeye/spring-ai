package dev.starryeye.productmcpserver;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductClient {

    Flux<Product> search(String keyword);

    /**
     * 상품이 없으면 빈 Mono 를 반환한다.
     */
    Mono<Integer> stockOf(String productId);
}
