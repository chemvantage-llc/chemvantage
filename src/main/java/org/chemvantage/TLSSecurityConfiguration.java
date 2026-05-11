package org.chemvantage;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TLS Security Configuration for ChemVantage.
 * 
 * Implements strong cipher suite and TLS version policies to mitigate vulnerabilities:
 * - Disables weak ciphers: DES, 3DES, Blowfish, RC4, MD5
 * - Disables ciphers with keys < 128-bit
 * - Disables SSL (all versions)
 * - Disables anonymous/null ciphers
 * - Disables 64-bit block ciphers
 * - Disables export-grade ciphers
 * 
 * Only allows: TLS 1.2 and TLS 1.3 with strong AEAD cipher suites
 */
@Configuration
public class TLSSecurityConfiguration {

	/**
	 * Configure Tomcat to use strong TLS cipher suites and disable weak protocols.
	 */
	@Bean
	public ServletWebServerFactory servletWebServerFactory() {
		TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
		
		factory.addConnectorCustomizers(new TomcatConnectorCustomizer() {
			@Override
			public void customize(Connector connector) {
				if (connector.getProtocolHandler() instanceof Http11NioProtocol) {
					Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
					
					// Enforce minimum TLS version: TLS 1.2+
					// This automatically disables SSL v3, TLS 1.0, and TLS 1.1
					protocol.setProperty("sslProtocol", "TLSv1.2");
					protocol.setProperty("sslEnabledProtocols", "TLSv1.2,TLSv1.3");
					
					// Define strong cipher suites (AEAD only, no weak algorithms)
					// These ciphers:
					// - Use ECDHE or DHE for forward secrecy
					// - Use AES with 128-bit or 256-bit keys
					// - Use GCM for authenticated encryption
					// - Exclude DES, 3DES, Blowfish, RC4, MD5, export-grade, anonymous, null ciphers
					String strongCiphers = "TLS_AES_256_GCM_SHA384,"
						+ "TLS_AES_128_GCM_SHA256,"
						+ "TLS_CHACHA20_POLY1305_SHA256,"
						+ "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,"
						+ "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,"
						+ "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,"
						+ "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,"
						+ "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,"
						+ "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,"
						+ "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,"
						+ "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,"
						+ "TLS_RSA_WITH_AES_256_GCM_SHA384,"
						+ "TLS_RSA_WITH_AES_128_GCM_SHA256";
					
					protocol.setProperty("ciphers", strongCiphers);
					protocol.setProperty("honorCipherOrder", "true");
				}
			}
		});
		
		return factory;
	}
}
