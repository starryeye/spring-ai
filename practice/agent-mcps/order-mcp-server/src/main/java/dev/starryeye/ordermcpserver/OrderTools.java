package dev.starryeye.ordermcpserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final OrderRepository orderRepository;

    public OrderTools(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @McpTool(
            name = "searchOrders",
            description = "주문을 검색한다. 각 주문의 ID, 고객명, 상품명, 수량, 상태를 반환한다. "
                    + "고객명을 생략하면 전체 주문을 반환한다."
    )
    public Mono<String> searchOrders(
            @McpToolParam(description = "고객명의 일부. 특정 고객을 지목하지 않으면 생략한다.",
                    required = false)
            String customer) {

        log.info("searchOrders 호출 (customer={})", customer);
        return Mono.fromSupplier(() -> orderRepository.findByCustomer(customer).stream()
                        .map(order -> "- [%s] %s / %s %d개 / %s"
                                .formatted(order.id(), order.customer(),
                                        order.productName(), order.quantity(), order.status()))
                        .collect(Collectors.joining("\n")))
                .map(joined -> joined.isBlank()
                        ? "'%s' 의 주문을 찾을 수 없습니다.".formatted(customer)
                        : joined);
    }

    @McpTool(
            name = "getOrder",
            description = "주문 ID로 주문 상세를 조회한다. 주문한 상품의 ID를 함께 반환하므로, "
                    + "주문한 상품의 재고를 알아보려면 이 툴로 상품 ID를 먼저 얻어야 한다."
    )
    public Mono<String> getOrder(
            @McpToolParam(description = "주문 ID. 예: o1", required = true)
            String orderId) {

        log.info("getOrder 호출 (orderId={})", orderId);
        return Mono.fromSupplier(() -> orderRepository.findById(orderId)
                        .map(order -> "주문 %s / 고객 %s / 상품 %s (ID: %s) %d개 / 상태 %s"
                                .formatted(order.id(), order.customer(), order.productName(),
                                        order.productId(), order.quantity(), order.status()))
                        .orElse("주문 %s 를 찾을 수 없습니다.".formatted(orderId)));
    }

    @McpTool(
            name = "cancelOrder",
            description = "주문을 취소한다. 주문 상태를 '취소됨' 으로 바꾸며 되돌릴 수 없다. "
                    + "사용자가 명시적으로 취소를 요청한 경우에만 사용한다."
    )
    public Mono<String> cancelOrder(
            @McpToolParam(description = "취소할 주문 ID. 예: o1", required = true)
            String orderId) {

        log.warn("cancelOrder 호출 (orderId={}) — 상태를 변경한다", orderId);
        return Mono.fromSupplier(() -> orderRepository.findById(orderId)
                        .map(existing -> {
                            if (OrderRepository.STATUS_CANCELLED.equals(existing.status())) {
                                return "주문 %s 는 이미 취소된 주문입니다.".formatted(orderId);
                            }
                            orderRepository.cancel(orderId);
                            return "주문 %s 를 취소했습니다.".formatted(orderId);
                        })
                        .orElse("주문 %s 를 찾을 수 없습니다.".formatted(orderId)));
    }
}
