package dev.starryeye.productservice;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
