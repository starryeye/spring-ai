package dev.starryeye.officialmcpserver;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
