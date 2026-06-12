package org.chemvantage;

import org.springframework.stereotype.Component;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

@Component // Registers this filter globally in Spring Boot
public class DomainRedirectFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
    
        String serverName = req.getServerName();
    
        // Add security headers to all responses (detects LTI context automatically)
        addSecurityHeaders(res, req);
        
        // Handle CORS preflight OPTIONS requests for LTI iframe embedding
        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) && isLtiRequest(req)) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.flushBuffer();
            return;
        }
        
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
     * Add security headers to HTTP responses, conditionally allowing LTI iframe embedding.
     * 
     * LTI context is detected by:
     * 1. Direct LTI endpoints: /lti/launch, /lti/deeplinks
     * 2. Valid LTI user token (sig parameter) with User.platformId set
     * 
     * For LTI requests, frame-ancestors and X-Frame-Options are relaxed to allow LMS embedding.
     * For other requests, strict SAMEORIGIN policy is enforced to prevent clickjacking.
     */
    private void addSecurityHeaders(HttpServletResponse response, HttpServletRequest request) {
        boolean isLtiContext = isLtiRequest(request);
        
        // HSTS (HTTP Strict-Transport-Security)
        // Forces browser to use HTTPS for all future requests for 1 year (31536000 seconds)
        // includeSubDomains ensures all subdomains are covered
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        
        // Content Security Policy (CSP) - prevents XSS and injection attacks
        // frame-ancestors is adjusted based on LTI context
        String frameAncestors = isLtiContext ? "'self' *" : "'self'";
        response.setHeader("Content-Security-Policy", 
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' cdn.jsdelivr.net https://www.youtube.com https://www.google.com https://www.gstatic.com https://www.googletagmanager.com https://www.paypal.com; " +
            "style-src 'self' 'unsafe-inline' fonts.googleapis.com cdn.jsdelivr.net; " +
            "img-src 'self' data: images.chemvantage.org fonts.gstatic.com https://www.google-analytics.com https://www.paypalobjects.com; " +
            "font-src 'self' fonts.gstatic.com fonts.googleapis.com cdn.jsdelivr.net; " +
            "frame-src https://www.youtube.com https://www.google.com https://www.gstatic.com https://www.paypal.com https://www.sandbox.paypal.com; " +
            "connect-src 'self' cdn.jsdelivr.net https://www.google.com https://www.gstatic.com https://www.google-analytics.com https://region1.google-analytics.com https://analytics.google.com https://stats.g.doubleclick.net https://www.paypal.com https://www.sandbox.paypal.com; " +
            "frame-ancestors " + frameAncestors + "; " +
            "upgrade-insecure-requests; block-all-mixed-content");
        
        // X-Content-Type-Options - prevents MIME-sniffing attacks
        response.setHeader("X-Content-Type-Options", "nosniff");
        
        // X-Frame-Options - prevents clickjacking attacks (adjusted for LTI)
        String frameOptions = isLtiContext ? "ALLOWALL" : "SAMEORIGIN";
        response.setHeader("X-Frame-Options", frameOptions);
        
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
        
        // For LTI iframe embedding: Allow credentials in cross-origin requests
        if (isLtiContext) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
            String origin = request.getHeader("Origin");
            if (origin != null) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
                response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
                response.setHeader("Access-Control-Max-Age", "86400");
            }
        }
    }
    
    /**
     * Detect if the current request is from an LTI context.
     * 
     * Returns true if:
     * - Request path contains /lti/launch or /lti/deeplinks
     * - POST request with id_token parameter (LTI 1.3 launch)
     * - Request is being loaded as an iframe (Sec-Fetch-Dest: iframe header) - indicates navigation within LMS iframe
     * - Valid LTI user token (sig parameter with User.platformId set)
     * - Cross-origin request (Origin header present) - indicates iframe embedding from LMS
     */
    private boolean isLtiRequest(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        
        // Direct LTI endpoints are always LTI context
        if (requestPath != null) {
            String normalizedPath = requestPath.toLowerCase();
            if (normalizedPath.contains("/lti/launch") || normalizedPath.contains("/lti/deeplinks")) {
                return true;
            }
        }
        
        // Check for LTI 1.3 POST request with id_token (primary indicator of LTI launch)
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String idToken = request.getParameter("id_token");
            if (idToken != null && !idToken.isEmpty()) {
                return true;  // LTI 1.3 launch request
            }
        }
        
        // Check if request is being loaded as an iframe (Sec-Fetch-Dest header)
        // Browser automatically sends this when loading any page/resource inside an <iframe>
        // This catches assignment navigation within an LMS iframe after initial LTI launch
        String secFetchDest = request.getHeader("Sec-Fetch-Dest");
        if (secFetchDest != null && "iframe".equalsIgnoreCase(secFetchDest)) {
            return true;  // Request is for iframe content, likely from LMS
        }
        
        // Check if user token indicates an LTI user
        String token = request.getParameter("sig");
        if (token != null && !token.isEmpty()) {
            try {
                // Validate the token and check if it references an LTI user
                Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
                String sig = JWT.require(algorithm).build().verify(token).getSubject();
                User user = com.googlecode.objectify.ObjectifyService.ofy()
                    .load().type(User.class).id(Long.parseLong(sig)).now();
                
                // If user exists and has a platformId, it's an LTI user
                if (user != null && user.platformId != null && !user.platformId.isEmpty()) {
                    return true;
                }
            } catch (Exception e) {
                // Token validation failed, continue to next check
            }
        }
        
        // Check for cross-origin request (Origin header indicates iframe/CORS request)
        // This is a strong indicator of LMS iframe embedding
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            // Verify the origin is HTTPS (security requirement for LTI)
            if (origin.startsWith("https://")) {
                return true;  // Cross-origin HTTPS request, likely LMS iframe
            }
        }
        
        return false;
    }
}
