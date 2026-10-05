package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Unindex;
import org.springframework.web.util.HtmlUtils;

@Entity
public class AccessTokenCache {
    @Id String platformDeploymentId;
	@Unindex String accessToken;
	@Unindex Date expiresAt;
    private static final Logger logger = Logger.getLogger(AccessTokenCache.class.getName());
    private static final Duration TOKEN_HTTP_CONNECT_TIMEOUT = Duration.ofMillis(15000);
	private static final HttpClient.Redirect TOKEN_HTTP_REDIRECT_POLICY = HttpClient.Redirect.NORMAL;
	private static final HttpClient TOKEN_HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(TOKEN_HTTP_CONNECT_TIMEOUT)
        .followRedirects(TOKEN_HTTP_REDIRECT_POLICY)
        .build();

	AccessTokenCache() {}

    AccessTokenCache(String platformDeploymentId, String accessToken, Date expiresAt) {
        this.platformDeploymentId = platformDeploymentId;
		this.accessToken = accessToken;
		this.expiresAt = expiresAt;
	}

    static String fetchToken(Deployment d,String scope) {
		AccessTokenCache cached = null;
		StringBuffer debug = new StringBuffer("Failed AccessTokenCache.fetchToken()\nTime: ")
				.append(java.time.Instant.now()).append('\n');

		if (d == null || scope == null) return null;
        debug.append("Deployment: ").append(d.platform_deployment_id)
                .append(" ( ").append(d.org_url).append(" )\nToken endpoint: ")
                .append(d.oauth_access_token_url).append('\n');

		try {
            Date now = new Date();
            Date in5Min = new Date(now.getTime() + 300000L);
            cached = ofy().load().type(AccessTokenCache.class).id(d.platform_deployment_id).now();
			if (cached != null && cached.expiresAt != null && cached.expiresAt.after(in5Min)) return cached.accessToken;
			else {  // If the key is missing or expired, attempt to fetch a fresh one from the authorization server
				RSAPrivateKey signingKey = KeyStore.getRSAPrivateKey(d.rsa_key_id);
                if (signingKey == null) {
                    d.rsa_key_id = KeyStore.getAKeyId(d.lms_type);
                    signingKey = KeyStore.getRSAPrivateKey(d.rsa_key_id);
                    if (signingKey == null) throw new Exception("No RSA private key available for deployment: " + d.platform_deployment_id);
                    ofy().save().entity(d).now();
                }
                String iss = Subject.getProjectId().equals("dev-vantage-hrd")?"https://dev.chemvantage.org":"https://www.chemvantage.org";
                debug.append("Requested by: " + iss + "<br/>");
                debug.append("Denied by: " + d.oauth_access_token_url + "<br/>");

                String aud = d.oauth_access_token_url;
                String sub = d.client_id;
                if ("brightspace".equals(d.lms_type) || "desire2learn".equals(d.lms_type)) {
                    iss = sub;
                    aud = "https://api.brightspace.com/auth/token";
                }

                String jwtToken = JWT.create()
                        .withIssuer(iss)
                        .withSubject(sub)
                        .withAudience(aud)
                        .withKeyId(d.rsa_key_id)
                        .withExpiresAt(in5Min)
                        .withIssuedAt(now)
                        .withJWTId(Nonce.generateNonce())
                        .sign(Algorithm.RSA256(null,signingKey));

                String body = "grant_type=" + URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
                        + "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8)
                        + "&client_assertion=" + URLEncoder.encode(jwtToken, StandardCharsets.UTF_8)
                        + "&scope=" + URLEncoder.encode(d.scope, StandardCharsets.UTF_8);
                debug.append("Body: " + body.replace("&client_assertion=" + URLEncoder.encode(jwtToken, StandardCharsets.UTF_8),
                        "&client_assertion=(redacted)") + "<br/>");

                HttpRequest tokenRequest = HttpRequest.newBuilder(new URI(d.oauth_access_token_url))
                        .timeout(Duration.ofMillis(15000))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Encoding", "identity")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("charset", "utf-8")
                        .header("User-Agent", Utilities.LTI_USER_AGENT)
                        .header("Cache-Control", "no-cache")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                debug.append("Request: POST ").append(tokenRequest.uri()).append("<br/>");
                debug.append("Headers:<br/>");
                for (Map.Entry<String,List<String>> header : tokenRequest.headers().map().entrySet()) {
                    debug.append(header.getKey()).append(": ").append(String.join(", ", header.getValue())).append("<br/>");
                }
                debug.append("POST URL: ").append(tokenRequest.uri()).append("<br/>");
                debug.append("Date/Time: ").append(new Date().toString()).append("<br/>");
                debug.append("HttpClient: connectTimeout=").append(TOKEN_HTTP_CONNECT_TIMEOUT)
                        .append(", redirectPolicy=").append(TOKEN_HTTP_REDIRECT_POLICY)
                        .append(", preferredVersion=").append(TOKEN_HTTP_CLIENT.version()).append("<br/>");

                HttpResponse<String> tokenResponse;
                try {
                    tokenResponse = TOKEN_HTTP_CLIENT.send(tokenRequest,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                int responseCode = tokenResponse.statusCode();
                debug.append("ResponseCode: " + responseCode + "<br/>");
                debug.append("HTTP version: ").append(tokenResponse.version()).append("<br/>");
                tokenResponse.headers().firstValue("Content-Type").ifPresent(value ->
                        debug.append("Response Content-Type: ").append(value).append("<br/>"));

                if (responseCode/100 == 2) { // response is OK
                    JsonObject json = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
                    String access_token = json.get("access_token").getAsString();
                    long expires_in = json.get("expires_in").getAsLong();  // number of seconds from now, typically 3600
                    if (access_token == null || access_token.isBlank() || expires_in <= 0) {
                        debug.append("OAuth response JSON fields: ").append(json.keySet())
                                .append("; token value omitted\n");
                        throw new Exception("OAuth success response did not contain a usable access token and positive expires_in value.");
                    }
                    Date expiresAt = new Date(System.currentTimeMillis() + expires_in * 1000);
                    cached = new AccessTokenCache(d.platform_deployment_id, access_token, expiresAt);
                    ofy().save().entity(cached).now();
                    return access_token;
                } else {
                    String errorBody = tokenResponse.body().isEmpty() ? "(no error body)" : tokenResponse.body();
                    debug.append("Error Stream: " + errorBody + "<br/>");
                    // These identify whether the platform's edge rejected the request before it reached the OAuth service
                    for (String h : new String[] {"x-amzn-errortype","x-amzn-requestid","x-amz-apigw-id","x-amzn-waf-action","cf-ray","server","www-authenticate"}) {
                        List<String> values = tokenResponse.headers().allValues(h);
                        if (!values.isEmpty()) debug.append(h).append(": ").append(String.join(", ", values)).append("<br/>");
                    }
                    throw new Exception("Failed AuthToken Request");
                }
			}
        } catch (Exception e) {
			debug.append("\nFailure: ").append(LTIv1p3Launch.describeFailure(e));
			try {
				Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "Failed AuthToken Request",
						"<pre>" + HtmlUtils.htmlEscape(debug.toString()) + "</pre>");
			} catch (Exception emailFailure) {
				logger.log(Level.WARNING, "Unable to send OAuth token failure email for deployment: "
                        + (d == null ? "unknown" : d.platform_deployment_id), emailFailure);
			}
        }
		return null;
	}
}
