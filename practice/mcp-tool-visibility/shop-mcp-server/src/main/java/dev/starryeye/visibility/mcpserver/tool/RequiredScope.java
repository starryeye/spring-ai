package dev.starryeye.visibility.mcpserver.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 tool을 부르려면 access token에 있어야 하는 scope다(안내서 10장).
 *
 * <p>MCP 명세에는 tool 정의에 필요한 scope를 적는 표준 field가 없다.
 * 그래서 서버 안에서만 쓰는 annotation으로 tool 옆에 적고, {@link ToolScopeRegistry}가 모은다.
 * client에게는 tool 정의로 알리지 않고, 권한이 모자랄 때 {@code 403}의 {@code WWW-Authenticate}로 알린다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiredScope {

	String value();
}
