package dev.starryeye.ordermcpserver;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderMcpServerApplicationTests {

	/**
	 * ASYNC 서버는 {@code @McpTool} 메서드가 {@code Mono}/{@code Flux} 를 반환할 때만
	 * 툴로 등록한다 — 리턴 타입을 {@code String} 으로 바꾸면 오류 없이 조용히 등록에서
	 * 빠진다({@code application.yml} 의 {@code type: ASYNC} 주석 참고). {@code contextLoads()}
	 * 만으로는 이 사고를 잡지 못하므로, 실제로 등록된 툴 목록을 직접 확인한다.
	 * <p>
	 * 자동설정은 {@code List<AsyncToolSpecification>} 빈을 둘 만든다
	 * (어노테이션 스캔용 {@code toolSpecs}, {@code ToolCallback} 변환용 {@code asyncTools}) —
	 * 둘 다 모으려면 단일 {@code List} 대신 {@link ObjectProvider} 로 받아야 한다.
	 */
	@Autowired
	ObjectProvider<List<McpServerFeatures.AsyncToolSpecification>> toolSpecificationLists;

	@Test
	void contextLoads() {
	}

	@Test
	void MCP_툴이_실제로_등록된다() {
		List<String> toolNames = toolSpecificationLists.orderedStream()
				.flatMap(List::stream)
				.map(spec -> spec.tool().name())
				.toList();

		assertThat(toolNames).containsExactlyInAnyOrder("searchOrders", "getOrder", "cancelOrder");
	}

}
