package dev.starryeye.localclient;

import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;

/** 기본 browser로 주소를 연다. 열 수 없는 환경이면 주소를 붙여 넣으라고 알린다. */
final class Browser {

	private Browser() {
	}

	static void open(URI uri, PrintStream out) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
				return;
			}
		}
		catch (IOException | UnsupportedOperationException ex) {
			// 아래 안내로 넘어간다.
		}
		out.println("    browser를 열지 못했습니다. 위 주소를 browser에 붙여 넣으세요.");
	}
}
