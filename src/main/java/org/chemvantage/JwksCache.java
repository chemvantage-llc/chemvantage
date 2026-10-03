package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.SigningKeyNotFoundException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Unindex;

/*
 * Datastore-backed cache of a platform's JSON Web Key Set, keyed by the JWKS URL.
 * Some platform edges (MoodleCloud, Schoology) intermittently answer 403 to requests from
 * Cloud Run egress addresses, and an in-memory cache is refetched on every cold start, so the
 * last good key set is shared across instances and used as a fallback when a refetch fails.
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
			Exception failure = null;
			try {
				JwksCache fresh = fetch();
				Jwk jwk = fresh.find(kid);
				if (jwk != null) return jwk;
			} catch (Exception e) {
				failure = e;
			}
			if (cached != null) {  // the platform is unreachable or rotated: the last good key set may still verify
				Jwk jwk = cached.find(kid);
				if (jwk != null) return jwk;
			}
			if (failure != null) throw new SigningKeyNotFoundException("Cannot obtain jwks from url " + jwks_url, failure);
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

		private JwksCache fetch() throws Exception {
			Exception first = null;
			for (int attempt = 0; attempt < 2; attempt++) {
				try {
					JwksCache result = new JwksCache(jwks_url.toString(), read());
					result.keys();  // reject a response that is not a parseable key set before caching it
					memo = result;
					try {
						ofy().save().entity(result).now();
					} catch (Exception ignored) {}
					return result;
				} catch (Exception e) {
					if (first == null) first = e;
				}
			}
			throw first == null ? new Exception("Could not read jwks from " + jwks_url) : first;
		}

		private String read() throws Exception {
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
				int code = uc.getResponseCode();
				InputStream in = code < 400 ? uc.getInputStream() : uc.getErrorStream();
				String body = in == null ? "" : readAll(in);
				if (code != HttpURLConnection.HTTP_OK) {
					throw new Exception("HTTP " + code + " from " + jwks_url
							+ (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(200, body.length()))));
				}
				return body;
			} finally {
				uc.disconnect();
			}
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
