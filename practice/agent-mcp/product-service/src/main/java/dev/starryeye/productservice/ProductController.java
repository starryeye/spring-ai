package dev.starryeye.productservice;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public record StockResponse(String productId, int stock) {
    }

    @GetMapping
    public Flux<Product> search(@RequestParam(required = false) String keyword) {
        return Flux.fromIterable(productRepository.findByKeyword(keyword));
    }

    @GetMapping("/{id}")
    public Mono<Product> findById(@PathVariable String id) {
        return Mono.justOrEmpty(productRepository.findById(id))
                .switchIfEmpty(Mono.error(notFound(id)));
    }

    @GetMapping("/{id}/stock")
    public Mono<StockResponse> stock(@PathVariable String id) {
        return Mono.justOrEmpty(productRepository.findById(id))
                .map(product -> new StockResponse(product.id(), product.stock()))
                .switchIfEmpty(Mono.error(notFound(id)));
    }

    private ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다: " + id);
    }
}
