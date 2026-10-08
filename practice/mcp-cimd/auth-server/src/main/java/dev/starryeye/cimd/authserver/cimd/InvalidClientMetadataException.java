package dev.starryeye.cimd.authserver.cimd;

/**
 * 문서 주소나 문서를 믿을 수 없다는 뜻이다. 메시지는 로그에 남길 이유다.
 */
public class InvalidClientMetadataException extends RuntimeException {

	public InvalidClientMetadataException(String message) {
		super(message);
	}

	public InvalidClientMetadataException(String message, Throwable cause) {
		super(message, cause);
	}
}
