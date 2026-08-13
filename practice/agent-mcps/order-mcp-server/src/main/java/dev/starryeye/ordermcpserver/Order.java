package dev.starryeye.ordermcpserver;

public record Order(
        String id,
        String customer,
        String productId,
        String productName,
        int quantity,
        String status
) {
    public Order withStatus(String newStatus) {
        return new Order(id, customer, productId, productName, quantity, newStatus);
    }
}
