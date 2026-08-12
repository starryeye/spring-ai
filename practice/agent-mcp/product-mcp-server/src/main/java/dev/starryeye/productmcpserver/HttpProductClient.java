package dev.starryeye.productmcpserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class HttpProductClient implements ProductClient {

    private final WebClient webClient;

    public HttpProductClient(WebClient.Builder builder,
                             @Value("${product-service.base-url}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    private record StockResponse(String productId, int stock) {
    }

    @Override
    public Flux<Product> search(String keyword) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/products")
                        .queryParam("keyword", keyword == null ? "" : keyword)
                        .build())
                .retrieve()
                .bodyToFlux(Product.class);
    }

    @Override
    public Mono<Integer> stockOf(String productId) {
        return webClient.get()
                .uri("/api/products/{id}/stock", productId)
                .retrieve()
                .bodyToMono(StockResponse.class)
                .map(StockResponse::stock)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }
}
