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
			cached = ofy().load().type(JwksCache.class).id(url).now();
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