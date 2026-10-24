package dev.starryeye.stateless.mcpserver.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 요청 본문을 한 번 읽어 두고, 뒤에서 몇 번이든 처음부터 다시 읽게 한다.
 *
 * <p>scope filter는 JSON-RPC 본문에서 tool 이름을 읽어야 한다.
 * 그런데 servlet 요청의 본문은 한 번만 읽을 수 있어서, 그대로 읽으면 transport가 빈 본문을 받는다.
 * Spring의 {@code ContentCachingRequestWrapper}는 읽힌 뒤에야 내용을 모으므로 이 용도에 맞지 않는다.
 *
 * <p>본문은 상한까지만 읽는다.
 * 상한 없이 메모리에 올리면 token이 있는 사용자 하나가 큰 본문으로 heap을 채울 수 있다.
 * Tomcat의 {@code maxPostSize}는 form 본문에만 적용되어 JSON 본문을 막지 못한다.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	/**
	 * 본문을 {@code maxBytes}까지 읽어 둔다.
	 * 넘으면 {@link BodyTooLargeException}이다.
	 * {@code Content-Length}가 상한을 넘으면 읽지 않고 거절한다.
	 * chunked처럼 길이를 미리 알 수 없는 본문은 상한보다 1바이트 더 읽어 넘었는지 본다.
	 */
	public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
		super(request);
		if (request.getContentLengthLong() > maxBytes) {
			throw new BodyTooLargeException(maxBytes);
		}
		byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
		if (read.length > maxBytes) {
			throw new BodyTooLargeException(maxBytes);
		}
		this.body = read;
	}

	public byte[] body() {
		return this.body;
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream in = new ByteArrayInputStream(this.body);
		return new ServletInputStream() {

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("비동기 읽기는 쓰지 않는다");
			}

			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				return in.read(buffer, offset, length);
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		String encoding = getCharacterEncoding();
		Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

	@Override
	public int getContentLength() {
		return this.body.length;
	}

	@Override
	public long getContentLengthLong() {
		return this.body.length;
	}

	/**
	 * 본문이 상한을 넘었다.
	 * 응답은 {@code 413}이다.
	 */
	public static final class BodyTooLargeException extends IOException {

		private final int maxBytes;

		BodyTooLargeException(int maxBytes) {
			super("요청 본문이 상한 " + maxBytes + "바이트를 넘는다");
			this.maxBytes = maxBytes;
		}

		public int maxBytes() {
			return this.maxBytes;
		}
	}
}
