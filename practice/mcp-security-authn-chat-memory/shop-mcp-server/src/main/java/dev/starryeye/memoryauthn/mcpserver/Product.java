package dev.starryeye.memoryauthn.mcpserver;

public record Product(
        String id,
        String name,
        String category,
        int price,
        int stock
) {
}
