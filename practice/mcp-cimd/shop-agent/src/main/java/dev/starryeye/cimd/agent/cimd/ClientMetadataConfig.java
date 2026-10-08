package dev.starryeye.cimd.agent.cimd;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** client 문서 서버와 서명 key를 bean으로 만든다. */
@Configuration
@EnableConfigurationProperties(ClientMetadataProperties.class)
public class ClientMetadataConfig {

	@Bean
	public ClientSigningKey clientSigningKey(ClientMetadataProperties properties, ResourceLoader resourceLoader)
			throws Exception {
		return ClientSigningKey.load(resourceLoader.getResource(properties.signingKeyStore()),
				properties.signingKeyStorePassword(), properties.signingKeyAlias());
	}

	@Bean
	public ClientMetadataDocuments clientMetadataDocuments(ClientMetadataProperties properties,
			ClientSigningKey signingKey) {
		return new ClientMetadataDocuments(properties, signingKey);
	}

	@Bean
	public ClientMetadataServer clientMetadataServer(ClientMetadataProperties properties, SslBundles sslBundles,
			ClientMetadataDocuments documents) {
		return new ClientMetadataServer(properties.port(),
				sslBundles.getBundle(properties.tlsBundle()).createSslContext(), documents);
	}
}
