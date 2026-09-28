package dev.starryeye.authz.mcpserver.domain;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
