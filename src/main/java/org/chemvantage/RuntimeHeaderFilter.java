package org.chemvantage;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletResponse;

@WebFilter("/*")
public class RuntimeHeaderFilter extends HttpFilter {

	private static final long serialVersionUID = 1L;

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (response instanceof HttpServletResponse httpResponse) {
			httpResponse.setHeader("X-CV-Runtime", detectRuntime());
			String runtimeService = detectRuntimeService();
			if (!runtimeService.isBlank()) {
				httpResponse.setHeader("X-CV-Runtime-Service", runtimeService);
			}
		}
		chain.doFilter(request, response);
	}

	private static String detectRuntime() {
		if (hasText(System.getenv("K_SERVICE"))) return "cloud-run";
		if (hasText(System.getenv("GAE_SERVICE"))) return "app-engine";
		return "local";
	}

	private static String detectRuntimeService() {
		String kService = System.getenv("K_SERVICE");
		if (hasText(kService)) return kService;
		String gaeService = System.getenv("GAE_SERVICE");
		if (hasText(gaeService)) return gaeService;
		return "";
	}

	private static boolean hasText(String s) {
		return s != null && !s.isBlank();
	}
}