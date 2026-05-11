package org.chemvantage;

import org.springframework.stereotype.Component;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component // Registers this filter globally in Spring Boot
public class DomainRedirectFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
    
        String serverName = req.getServerName();
    
        // Add security headers to all responses
        addSecurityHeaders(res);
        
        // Check for naked domain
        if ("chemvantage.org".equalsIgnoreCase(serverName)) {
            String path = req.getRequestURI();
            String query = req.getQueryString() != null ? "?" + req.getQueryString() : "";
            String destination = "https://www.chemvantage.org" + path + query;
        
            // Use 308 (Permanent Redirect) or 307 (Temporary) to preserve POST methods for LTI
            res.setStatus(308); 
            res.setHeader("Location", destination);
            res.flushBuffer(); // Force the response out
            return; 
        }
    
        chain.doFilter(request, response);
    }
    
    /**
     * Add security headers to all HTTP responses.
     * Implements defense-in-depth security controls.
     */
    private void addSecurityHeaders(HttpServletResponse response) {
        // HSTS (HTTP Strict-Transport-Security)
        // Forces browser to use HTTPS for all future requests for 1 year (31536000 seconds)
        // includeSubDomains ensures all subdomains are covered
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        
        // Content Security Policy (CSP) - prevents XSS and injection attacks
        // Uses 'self' for scripts/styles, blocks external inline scripts
        response.setHeader("Content-Security-Policy", 
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' cdn.jsdelivr.net; " +
            "style-src 'self' 'unsafe-inline' fonts.googleapis.com cdn.jsdelivr.net; " +
            "img-src 'self' images.chemvantage.org fonts.gstatic.com; " +
            "font-src 'self' fonts.gstatic.com fonts.googleapis.com; " +
            "connect-src 'self'; " +
            "frame-ancestors 'self'; " +
            "upgrade-insecure-requests; block-all-mixed-content");
        
        // X-Content-Type-Options - prevents MIME-sniffing attacks
        response.setHeader("X-Content-Type-Options", "nosniff");
        
        // X-Frame-Options - prevents clickjacking attacks
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        
        // X-XSS-Protection - legacy XSS protection for older browsers
        response.setHeader("X-XSS-Protection", "1; mode=block");
        
        // Referrer-Policy - controls what referrer info is sent
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        
        // Permissions-Policy - disables access to sensitive browser features
        response.setHeader("Permissions-Policy", 
            "geolocation=(), " +
            "microphone=(), " +
            "camera=(), " +
            "payment=(), " +
            "usb=(), " +
            "magnetometer=(), " +
            "gyroscope=(), " +
            "accelerometer=()");
    }
}
