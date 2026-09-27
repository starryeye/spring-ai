package dev.starryeye.memory.mcpserver.domain;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
