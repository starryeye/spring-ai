package dev.starryeye.productmcpserver;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
