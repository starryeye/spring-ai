package dev.starryeye.authz.agent.mcp;

import dev.starryeye.authz.agent.security.StepUpRequiredException;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class StepUpTransportTest {

    HttpServer server;

    @BeforeEach
    void start() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer error=\"insufficient_scope\", scope=\"products:write\", resource_metadata=\"http://x\"");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    @Test
    void SDK는_handler가_던진_step_up_예외를_호출한_쪽까지_올린다() {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + this.server.getAddress().getPort())
                .endpoint("/mcp")
                .authorizationErrorHandler(new StepUpAuthorizationErrorHandler())
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();

        Throwable error = catchThrowable(client::initialize);

        assertThat(StepUpRequiredException.find(error))
                .hasValueSatisfying(stepUp -> assertThat(stepUp.scopes()).containsExactly("products:write"));
        client.close();
    }
}
