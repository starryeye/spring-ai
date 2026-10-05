package dev.starryeye.visibility.mcpserver.filter;

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
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
		super(request);
		this.body = request.getInputStream().readAllBytes();
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
}
