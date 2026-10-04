package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.math.BigInteger;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.UrlJwkProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Unindex;

/*
 * Datastore cache of a platform's JSON Web Key Set, keyed by the JWKS URL.
 * fetchJwk fetches a JSON Web Key by its key ID (kid) from the JWKS URL, using the cache if available.
 * If the key is missing, the method tries to retrieve a fresh keyset from the JWKS URL.
 * Otherwise, it returns null.
 */

@Entity
public class JwksCache {
	@Id String url;
	@Unindex String json;
	Date fetched;

	JwksCache() {}

	JwksCache(String url, String json) {
		this.url = url;
		this.json = json;
		this.fetched = new Date();
	}

	static RSAPublicKey fetchPublicKey(String url,String kid) {
		RSAPublicKey publicKey = null;
		JwksCache cached = null;
		JsonObject jwk = null;
		
		if (url == null || kid == null) return null;

		try {
			cached = ofy().load().type(JwksCache.class).id(url).safe();
			jwk = extractJwk(cached, kid);
			if (jwk != null) return getRsaPublicKey(jwk);
			else {  // If the key is missing, attempt to fetch a fresh keyset from the JWKS URL
				Map<String,String> headers = new HashMap<String,String>();
				//headers.put("User-Agent", Utilities.LTI_USER_AGENT);
				headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.8037.93 Safari/537.3");
				UrlJwkProvider provider = new UrlJwkProvider(new URI(url).toURL(),null,null,null,headers);
				Jwk jwkObject = provider.get(kid);
				if (jwkObject == null) throw new Exception("Failed to fetch JWK for kid: " + kid);
				
				publicKey = (RSAPublicKey)jwkObject.getPublicKey();
				
				// Success -  cache the entire JWKS
				List<Jwk> jwks = provider.getAll();
				JsonArray keys = new JsonArray();
				for (Jwk jwkObj : jwks) {
					JsonObject key = new JsonObject();
					key.addProperty("kid", jwkObj.getId());
					key.addProperty("kty", jwkObj.getType());
					key.addProperty("alg", jwkObj.getAlgorithm());
					key.addProperty("use", jwkObj.getUsage());
					key.addProperty("n", (String) jwkObj.getAdditionalAttributes().get("n"));
					key.addProperty("e", (String) jwkObj.getAdditionalAttributes().get("e"));
					keys.add(key);
				}
				JsonObject jwksJson = new JsonObject();
				jwksJson.add("keys", keys);
				cached = new JwksCache(url, jwksJson.toString());
				ofy().save().entity(cached).now();
				return publicKey;
			}
		} catch (Exception e) {}
		return null;
	}

	private static JsonObject extractJwk(JwksCache cached, String kid) {
		if (cached == null) return null;
		JsonArray keys = JsonParser.parseString(cached.json).getAsJsonObject().getAsJsonArray("keys");
		for (JsonElement keyElem : keys) {
			JsonObject keyObj = keyElem.getAsJsonObject();
			if (keyObj.has("kid") && kid.equals(keyObj.get("kid").getAsString())) {
				return keyObj;
			}
		}
		return null;
	}

	private static RSAPublicKey getRsaPublicKey(JsonObject jwk) throws GeneralSecurityException {
		if (!jwk.has("kty") || !"RSA".equals(jwk.get("kty").getAsString())) {
			throw new IllegalArgumentException("Expected an RSA JWK");
		}
		if (!jwk.has("n") || !jwk.has("e")) {
			throw new IllegalArgumentException("Missing RSA modulus or exponent");
    	}

		Base64.Decoder decoder = Base64.getUrlDecoder();

		BigInteger modulus = new BigInteger(
				1, decoder.decode(jwk.get("n").getAsString()));

		BigInteger exponent = new BigInteger(
				1, decoder.decode(jwk.get("e").getAsString()));

		RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);

		return (RSAPublicKey) KeyFactory.getInstance("RSA")
				.generatePublic(spec);
	}
}

/* 
	private static final long MAX_AGE_MILLIS = 86400000L;  // refetch the key set at most once a day
	private static final Gson gson = new Gson();

	static JwkProvider provider(URL jwks_url) {
		return new Provider(jwks_url);
	}

	static class Provider implements JwkProvider {
		private final URL jwks_url;
		private volatile JwksCache memo;

		Provider(URL jwks_url) {
			this.jwks_url = jwks_url;
		}

		@Override
		public Jwk get(String kid) throws JwkException {
			JwksCache cached = load();
			if (cached != null && cached.isFresh()) {
				Jwk jwk = cached.find(kid);
				if (jwk != null) return jwk;  // a stale kid forces a refetch below (key rotation)
			}
			StringBuilder debug = new StringBuilder("Failed JWKS retrieval\nTime: ").append(new Date())
					.append("\nJWKS URL: ").append(jwks_url).append("\nRequested kid: ").append(kid).append('\n');
			Exception failure = null;
			try {
				JwksCache fresh = fetch(debug);
				Jwk jwk = fresh.find(kid);
				if (jwk != null) return jwk;
			} catch (Exception e) {
				failure = e;
			}
			if (cached != null) {  // the platform is unreachable or rotated: the last good key set may still verify
				Jwk jwk = cached.find(kid);
				if (jwk != null) return jwk;
			}
			if (failure != null) {
				debug.append("\nCached fallback: ").append(cached == null ? "No cached JWKS available." : "Requested kid not found in cached JWKS.");
				try {
					Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "LTI JWKS Retrieval Failure",
							"<pre>" + HtmlUtils.htmlEscape(debug.toString()) + "</pre>");
				} catch (Exception emailFailure) {
					java.util.logging.Logger.getLogger(JwksCache.class.getName()).log(java.util.logging.Level.WARNING,
							"Unable to send JWKS failure email.", emailFailure);
				}
				throw new SigningKeyNotFoundException("Cannot obtain jwks from url " + jwks_url, failure);
			}
			throw new SigningKeyNotFoundException("No key with kid " + kid + " was found at " + jwks_url, null);
		}

		private JwksCache load() {
			JwksCache m = memo;
			if (m != null && m.isFresh()) return m;
			try {
				JwksCache stored = ofy().load().type(JwksCache.class).id(jwks_url.toString()).now();
				if (stored != null) memo = stored;
				return stored == null ? m : stored;
			} catch (Exception e) {
				return m;
			}
		}

		private JwksCache fetch(StringBuilder debug) throws Exception {
			Exception first = null;
			for (int attempt = 0; attempt < 2; attempt++) {
				debug.append("\nAttempt: ").append(attempt + 1).append('\n');
				try {
					JwksCache result = new JwksCache(jwks_url.toString(), read(debug));
					result.keys();  // reject a response that is not a parseable key set before caching it
					memo = result;
					try {
						ofy().save().entity(result).now();
					} catch (Exception ignored) {}
					return result;
				} catch (Exception e) {
					debug.append("Failure: ").append(LTIv1p3Launch.describeFailure(e)).append('\n');
					if (first == null) first = e;
				}
			}
			throw first == null ? new Exception("Could not read jwks from " + jwks_url) : first;
		}

		private String read(StringBuilder debug) throws Exception {
			debug.append("Request: GET ").append(jwks_url).append("\nRequest body: (none)\n");
			HttpURLConnection uc = (HttpURLConnection) jwks_url.openConnection();
			try {
				uc.setRequestMethod("GET");
				uc.setInstanceFollowRedirects(true);
				uc.setConnectTimeout(Utilities.LTI_TIMEOUT_MILLIS);
				uc.setReadTimeout(Utilities.LTI_TIMEOUT_MILLIS);
				uc.setRequestProperty("Host", jwks_url.getHost());
				uc.setRequestProperty("Accept", "application/json");
				uc.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
				uc.setRequestProperty("User-Agent", Utilities.LTI_USER_AGENT);
				debug.append("Request headers:\n");
				for (Map.Entry<String,List<String>> header : uc.getRequestProperties().entrySet()) {
					debug.append(header.getKey()).append(": ").append(diagnosticHeader(header.getKey(), header.getValue())).append('\n');
				}
				debug.append("Response: (awaiting server; absent if connection fails)\n");
				int code = uc.getResponseCode();
				debug.append("ResponseCode: ").append(code).append("\nResponse URL: ").append(uc.getURL()).append("\nResponse headers:\n");
				for (Map.Entry<String,List<String>> header : uc.getHeaderFields().entrySet()) {
					debug.append(header.getKey() == null ? "Status" : header.getKey()).append(": ")
							.append(diagnosticHeader(header.getKey(), header.getValue())).append('\n');
				}
				InputStream in = code < 400 ? uc.getInputStream() : uc.getErrorStream();
				String body = in == null ? "" : readAll(in);
				debug.append("Response body:\n").append(body).append('\n');
				if (code != HttpURLConnection.HTTP_OK) {
					throw new Exception("HTTP " + code + " from " + jwks_url
							+ (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
				}
				return body;
			} finally {
				uc.disconnect();
			}
		}

		private static String diagnosticHeader(String name, List<String> values) {
			if ("Authorization".equalsIgnoreCase(name) || "Proxy-Authorization".equalsIgnoreCase(name)
					|| "Cookie".equalsIgnoreCase(name) || "Set-Cookie".equalsIgnoreCase(name)) return "(redacted)";
			return String.join(", ", values);
		}
	}

	private static String readAll(InputStream in) throws Exception {
		try (InputStream is = in) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			for (int n = is.read(chunk); n > 0; n = is.read(chunk)) buf.write(chunk, 0, n);
			return buf.toString(StandardCharsets.UTF_8);
		}
	}

	private boolean isFresh() {
		return fetched != null && System.currentTimeMillis() - fetched.getTime() < MAX_AGE_MILLIS;
	}

	private Jwk find(String kid) {
		try {
			for (Jwk jwk : keys()) if (kid.equals(jwk.getId())) return jwk;
		} catch (Exception e) {}
		return null;
	}

	private List<Jwk> keys() throws Exception {
		JsonObject jwks = JsonParser.parseString(json).getAsJsonObject();
		JsonArray keys = jwks.getAsJsonArray("keys");
		if (keys == null) throw new Exception("The response contained no JWKS keys array.");
		List<Jwk> result = new ArrayList<Jwk>();
		for (JsonElement key : keys) {
			Map<String,Object> values = gson.fromJson(key, new TypeToken<Map<String,Object>>(){}.getType());
			result.add(Jwk.fromValues(values));
		}
		return result;
	}
}
*/