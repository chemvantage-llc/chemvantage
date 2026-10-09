/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2019 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static com.googlecode.objectify.ObjectifyService.key;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.web.util.HtmlUtils;

public class LTIMessage {  // utility for sending LTI-compliant "POX" or "REST+JSON" messages to a Tool Consumer (LMS)
	
	private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(LTIMessage.class.getName());
	
	private static void setRequestHeaders(HttpURLConnection connection) {
		connection.setRequestProperty("User-Agent", "ChemVantage/1.0 (https://www.chemvantage.org; admin@chemvantage.org)");
		connection.setRequestProperty("Host", connection.getURL().getAuthority());
	}
	
	// SECURITY FIX: Removed static HashMap authTokens - replaced with SecureCredentialManager
	// The old implementation stored unencrypted OAuth tokens in memory
	// SecureCredentialManager provides:
	// - Automatic expiration checking
	// - No logging of sensitive tokens
	// - Thread-safe operations
	// - Proper cleanup on invalidation
/* 	
	static String getAccessToken(String platformDeploymentId,String scope) throws IOException {
		return getAccessToken(platformDeploymentId, scope, false);
	}
	
	static String getAccessToken(String platformDeploymentId,String scope, boolean retry) throws IOException {

		// SECURITY FIX: Uses SecureCredentialManager for token caching
		// - Automatically checks expiration
		// - Doesn't log sensitive token values
		// - Thread-safe operations
		Deployment d = null;
		Date now = new Date();
		Date in15Min = new Date(now.getTime() + 900000L);  // 15 minutes from now
		StringBuffer debug = new StringBuffer("Failed LTIMessage.getAccessToken()<br/>");

		try {
			d = Deployment.getInstance(platformDeploymentId);
			if (d==null) debug.append("ChemVantage Deployment unknown<br/>");
			else debug.append("Deployment: " + d.platform_deployment_id + " (" + d.org_url + ")<br/>");

			if (d==null || d.scope==null || !d.scope.contains(scope)) return null;  // must be authorized

			// Repair legacy/malformed deployments that are missing a signing key id.
			if (d.rsa_key_id == null) {
				d.rsa_key_id = KeyStore.getAKeyId(d.lms_type);
				ofy().save().entity(d).now();
			}
			RSAPrivateKey signingKey = KeyStore.getRSAPrivateKey(d.rsa_key_id);
			if (signingKey == null) {
				d.rsa_key_id = KeyStore.getAKeyId(d.lms_type);
				signingKey = KeyStore.getRSAPrivateKey(d.rsa_key_id);
				if (signingKey == null) throw new Exception("No RSA private key available for deployment: " + d.platform_deployment_id);
				ofy().save().entity(d).now();
			}

			// SECURITY FIX: Retrieve cached token from SecureCredentialManager
			// Uses 5-minute buffer to ensure token doesn't expire mid-request
			String cachedToken = SecureCredentialManager.getCachedToken(platformDeploymentId, 300000L);
			if (cachedToken != null) {
				logger.fine("Using cached access token for deployment: " + platformDeploymentId);
				return cachedToken;
			}

			// At this point no valid cached authToken was found, so we request a new authToken from the LMS platform:
			// First, construct a request token to send to the platform
			String iss = Subject.getProjectId().equals("dev-vantage-hrd")?"https://dev.chemvantage.org":"https://www.chemvantage.org";
			debug.append("Denied by: " + d.oauth_access_token_url + "<br/>");

			String aud = d.oauth_access_token_url;
			String sub = d.client_id;

			if ("brightspace".equals(d.lms_type) || "desire2learn".equals(d.lms_type)) {
				iss = sub;
				aud = "https://api.brightspace.com/auth/token";
			}

			String token = JWT.create()
					.withIssuer(iss)
					.withSubject(sub)
					.withAudience(aud)
					.withKeyId(d.rsa_key_id)
					.withExpiresAt(in15Min)
					.withIssuedAt(now)
					.withJWTId(Nonce.generateNonce())
					.sign(Algorithm.RSA256(null,signingKey));

			String body = "grant_type=" + URLEncoder.encode("client_credentials", StandardCharsets.UTF_8)
					+ "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8)
					+ "&client_assertion=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
					+ "&scope=" + URLEncoder.encode(d.scope, StandardCharsets.UTF_8);
			debug.append("Body: " + body.replace("&client_assertion=" + URLEncoder.encode(token, StandardCharsets.UTF_8),
					"&client_assertion=(redacted)") + "<br/>");

			HttpRequest tokenRequest = HttpRequest.newBuilder(new URI(d.oauth_access_token_url))
					.timeout(Duration.ofMillis(15000))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header("Accept", "application/json, text/plain")
					.header("Accept-Encoding", "identity")
					.header("Accept-Language", "en-US,en;q=0.9")
					.header("charset", "utf-8")
					.header("User-Agent", "ChemVantage/1.0 (https://www.chemvantage.org; admin@chemvantage.org)")
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

			if (responseCode/100 == 2) { // response is OK
				JsonObject json = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
				String access_token = json.get("access_token").getAsString();
				long expires_in = json.get("expires_in").getAsLong();  // number of seconds from now, typically 3600

				// SECURITY FIX: Cache token using SecureCredentialManager
				// - Token is cached with proper expiration
				// - No logging of the actual token value
				// - Thread-safe operations
				SecureCredentialManager.cacheToken(d.platform_deployment_id, access_token, expires_in);

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
		} catch (Exception e) {
			try {  // This overcomes a timeout problem with some servers getting our jwks
				if (retry) throw new Exception();
				Thread.sleep(5000);
				return getAccessToken(platformDeploymentId,scope,true);
			} catch (Exception e1) {}
			debug.append("Elapsed time: " + (new Date().getTime() - now.getTime()) + " ms<br/>");
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Failed AuthToken Request",debug.toString() + "<br/>" + (e.getMessage()==null?e.toString():e.getMessage()));
			return "Failed AuthToken Request <br/>" + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString();
		}    
	}
*/
    static JsonObject getLineItem(Deployment d,String resourceLinkId,String lti_ags_lineitems_url) throws Exception {   	
    	String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem";    	
    	try {
    		String lineitems = getLineItemContainer(d,lti_ags_lineitems_url,resourceLinkId,scope);
    		JsonArray lineitems_json_array = JsonParser.parseString(lineitems).getAsJsonArray();

    		// We submitted the request including the resourceLinkId query, so there are only two possible valid responses:
    		// 0 - if the array is empty, we need to return null
    		// 1 - if there is exactly one lineitem, we need to return it

    		return lineitems_json_array.get(0).getAsJsonObject();
    	} catch (Exception e) {
    		return null;
    	}
    }

    static JsonObject getLineItem(Deployment d, String lti_ags_lineitem_url) {
    	// This method returns a single lineitem from the platform
    	int responseCode = 0;
    	try {
    		String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem";
    		String bearerAuth = "Bearer " + AccessTokenCache.fetchToken(d,scope);

    		URL u = new URI(lti_ags_lineitem_url).toURL();
    		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
    		setRequestHeaders(uc);
    		uc.setDoInput(true);
    		uc.setRequestMethod("GET");
    		uc.setRequestProperty("Authorization", bearerAuth);
    		uc.setRequestProperty("Accept", "application/vnd.ims.lis.v2.lineitem+json");
    		uc.connect();

    		responseCode = uc.getResponseCode();
    		if (responseCode == 200) {
    			BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
    			JsonObject lineitem_json = JsonParser.parseReader(reader).getAsJsonObject();
    			reader.close();
    			return lineitem_json;
    		} 
    	} catch (Exception e) {
    		JsonObject response = new JsonObject();
    		response.addProperty("responseCode", responseCode);
    		response.addProperty("errorMessage", e.getMessage());
    		return response;
    	}
    	return null;
    }
    
    static JsonArray getLineItemContainer(Deployment d,String lti_ags_lineitems_url) {
    	// This method asks the platform to return all lineitems for the context as a JsonArray container
    	// Note: this may require multiple calls to the platform to get batches of lineitems. If the container 
    	// is incomplete, the platform MUST provide one or more Link objects in the response header. These have the form of a comma-sepated list
    	// Link: <https://lms.example.com/sections/2923/lineitems/69?p=3>; rel="next",<https://lms.example.com/sections/2923/lineitems/69?p=1>; rel="prev"
    	
    	JsonArray container = new JsonArray();
    	try {
    		String accessToken = AccessTokenCache.fetchToken(d,d.scope);
    		if (accessToken.indexOf("Failed AuthToken Request")>=0) throw new Exception("Failed AuthToken request.");
    		String bearerAuth = "Bearer " + accessToken;
    		String next_url = lti_ags_lineitems_url;
    		while (next_url != null) {
    			URL u = new URI(next_url).toURL();
    			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
    			setRequestHeaders(uc);
    			uc.setDoInput(true);
    			uc.setRequestMethod("GET");
    			uc.setRequestProperty("Authorization", bearerAuth);
    			uc.setRequestProperty("Accept", "application/vnd.ims.lis.v2.lineitemcontainer+json");
    			uc.connect();
    			BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
    			container.addAll(JsonParser.parseReader(reader).getAsJsonArray());
    			reader.close();
    			
    			next_url = null;
    			String[] links = uc.getHeaderField("Link").split(",");
    			for (String l : links) if (l.contains("next")) {
    				next_url = l.trim().substring(1); // removes any leading/trailing white space and the first <
    				next_url = next_url.substring(0,next_url.indexOf(">"));
    			}
    		}
    	} catch (Exception e) {
    		JsonArray error =  new JsonArray();
    		error.add(e.getMessage()==null?e.toString():e.getMessage());
    	}
    	return container;
    }

    static String getLineItemContainer(Deployment d,String lti_ags_lineitems_url,String resourceLinkId,String scope) {
    	// This method asks the platform to return one lineitems for the context having the specified resourceLinkId
    	try {
    		String accessToken = AccessTokenCache.fetchToken(d,scope);
    		if (accessToken == null) return "Access token not granted: " + accessToken;
    		String bearerAuth = "Bearer " + accessToken;

    		URL u = new URI(lti_ags_lineitems_url + "?resource_link_id=" + resourceLinkId).toURL();
    		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
    		setRequestHeaders(uc);
    		uc.setDoInput(true);
    		uc.setRequestMethod("GET");
    		uc.setRequestProperty("Authorization", bearerAuth);
    		uc.setRequestProperty("Accept", "application/vnd.ims.lis.v2.lineitemcontainer+json");
    		uc.connect();

    		int responseCode = uc.getResponseCode();
    		if (responseCode > 199 && responseCode < 300) { // OK
    			JsonArray lineitems_json = null;
        		BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
    			lineitems_json = JsonParser.parseReader(reader).getAsJsonArray();
    			reader.close();
    			return lineitems_json.toString();
    		} else {
    			return "Response code " + responseCode;
    		}
    	} catch (Exception e) {
    		return null;
    		//return "getLineItemContainer: " + e.toString() + " " + e.getMessage();
    	}
	}
	
	static String createLineItem(Deployment d,Assignment a,String lti_ags_lineitems_url) throws Exception {
		if (d==null || a==null || !a.isValid()) return null;
	
		StringBuffer debug = new StringBuffer("Failed to create lineitem: ");
		try {
			String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem";
			String lineItemUrl = null;
			
			String accessToken = AccessTokenCache.fetchToken(d,scope);
    		if (accessToken == null) return "Access token not granted.";
    		String bearerAuth = "Bearer " + accessToken;
    		debug.append("Authorization: " + bearerAuth + "<br>");
			
			JsonObject j = new JsonObject();
			
			String label = "ChemVantage Assignment";
			try {
				Topic t = ofy().load().type(Topic.class).id(a.topicId).safe();
				label = a.assignmentType + " - " + t.title;
			} catch (Exception e) {}
			
			int maxPossibleScore = 100;
			try {
				maxPossibleScore = a.assignmentType.equals("PracticeExam")?100:(a.assignmentType.equals("VideoQuiz")?5:10);
			} catch (Exception e) {}
			
			j.addProperty("scoreMaximum", maxPossibleScore);
			j.addProperty("label", label);
			j.addProperty("resourceLinkId", a.resourceLinkId);
			j.addProperty("resourceId", String.valueOf(a.id));

			String json = j.toString();
			debug.append("Lineitem JSON: " + json);
			
			URL u = new URI(lti_ags_lineitems_url).toURL();

			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			setRequestHeaders(uc);
			uc.setDoOutput(true);
			uc.setDoInput(true);
			uc.setRequestMethod("POST");
			uc.setRequestProperty("Authorization", bearerAuth);
			uc.setRequestProperty("Content-Type", "application/vnd.ims.lis.v2.lineitem+json");
			uc.setRequestProperty("Accept-Type", "application/vnd.ims.lis.v2.lineitem+json");
			//uc.setRequestProperty("Content-Length", String.valueOf(json.length()));
			uc.connect();

			// send the message
			OutputStreamWriter toTC = new OutputStreamWriter(uc.getOutputStream());
			toTC.write(json);
			toTC.flush();
			int responseCode = uc.getResponseCode(); // success if 200-202
			debug.append("Response code: " + responseCode + "<br>");

			boolean success = responseCode>199 && responseCode<203;
			BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			JsonObject lineitem = JsonParser.parseReader(reader).getAsJsonObject();
			lineItemUrl = lineitem.get("id").getAsString();
			reader.close();
			toTC.close();
			
			if (success) {
				new URI(lineItemUrl);  // throws Exception if not a valid URL
			} else {
				lineitem.addProperty("status", responseCode);
				return lineitem.toString();
			}
			return lineItemUrl;			
		} catch (Exception e) {
			return null;  //e.toString() + " " + e.getMessage() + "<br>" + debug.toString();
		}
	}

	static Map<String,String> readMembershipScores(Assignment a) {
		// This method uses the LTIv1p3 message protocol to retrieve a JSON containing all of
		// the existing  scores for one assignment from the LMS. 
		// The lineitem URL corresponds to the LMS grade book column fpr the Assignment entity.
		
		// Construct the deployment_id because we may need to strip this from the userId values
		
		Map<String,String> scores = new HashMap<String,String>();		
		
		try {		
			String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly";
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			String accessToken = AccessTokenCache.fetchToken(d,scope);
			if (accessToken == null || accessToken.startsWith("Failed AuthToken Request"))
				throw new Exception(accessToken == null ? "Access token was not granted" : accessToken);
			String bearerAuth = "Bearer " + accessToken;

			if (a.lti_ags_lineitem_url==null) throw new Exception("the lineitem URL for this assignment is unknown");
			
			// calculate the index of the position between the path and query parts of the URL
			int beginQuery = a.lti_ags_lineitem_url.indexOf("?");
			if (beginQuery == -1) beginQuery = a.lti_ags_lineitem_url.length();  // end of the path (no query)
			
			// append "/results" to the path and reassemble the URL
			String next_url = a.lti_ags_lineitem_url.substring(0,beginQuery) + "/results" + a.lti_ags_lineitem_url.substring(beginQuery);
			
			URL u = null;
			while (next_url != null) {
				u = new URI(next_url).toURL();

				HttpURLConnection uc = (HttpURLConnection) u.openConnection();
				setRequestHeaders(uc);
				uc.setDoInput(true);
				uc.setRequestMethod("GET");
				uc.setRequestProperty("Authorization", bearerAuth);
				uc.setRequestProperty("Accept", "application/vnd.ims.lis.v2.resultcontainer+json");
				uc.connect();

				int responseCode = uc.getResponseCode();
				if (responseCode > 199 && responseCode < 203) {  // OK
					BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					JsonArray json = JsonParser.parseReader(reader).getAsJsonArray();
					reader.close();

					Iterator<JsonElement> iterator = json.iterator();
					while(iterator.hasNext()) {
						JsonObject result = iterator.next().getAsJsonObject();
						String userId = result.get("userId").getAsString();
						scores.put(userId,String.valueOf(Math.round(1000.*result.get("resultScore").getAsDouble()/result.get("resultMaximum").getAsDouble())/10.));
					}
				} else {
					scores.put("Error", "LMS returned HTTP " + responseCode + " for the assignment results request");
				}
				next_url = null;
				try {  // per LTI AGS specs, this section looks for a HttpLink to the next page of results
					String[] links = uc.getHeaderField("Link").split(",");  // splits comma-separated list of Links
					for (String l : links) if (l.contains("next")) next_url = l.substring(l.indexOf("<")+1,l.indexOf(">")); // url is enclosed in <>
				} catch (Exception e2) {}
				uc.disconnect();
			}
		} catch (Exception e) {	
			scores.put("Error", e.getMessage()==null?e.toString():e.getMessage());
		}
		return scores;
	}

	static String readUserScore(Assignment a, String userId) {
		// This method uses the LTIv1p3 message protocol to retrieve a user's score from the LMS.
		// The lineitem URL corresponds to the LMS grade book column fpr the Assignment entity,
		// and the specific cell is identified by the user_id value defined by the LMS platform
		// This method accepts either the full ChemVantage userId or the raw LMS user_id
		StringBuffer debug = new StringBuffer("Debug: ");
		HttpURLConnection uc = null;
		try {
			String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly";
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			String accessToken = AccessTokenCache.fetchToken(d,scope);
			String bearerAuth = "Bearer " + accessToken;
			
			// If necessary, convert the full ChemVantage userId (with domain and slash) to raw LMNS user_ud
			int lastSlash = userId.lastIndexOf("/");  // returns -1 if no slashes (raw LMS user_id)
			String user_id = lastSlash<0?userId:userId.substring(lastSlash+1); // stripped of the platform_id and "/"
			
			if (a.lti_ags_lineitem_url==null) throw new Exception("the lineitem URL for this assignment is unknown");
			
			
			// There is some uncertainty about the query parameter; The LTI spec is user_id but the container has userId
			URL u = null;
			switch (d.lms_type) {
			case "moodle":
				String base_url = a.lti_ags_lineitem_url.substring(0,a.lti_ags_lineitem_url.indexOf("?")) + "/results";
				String query = a.lti_ags_lineitem_url.substring(a.lti_ags_lineitem_url.indexOf("?"));
				u = new URI(base_url + query + "&user_id=" + user_id + "&userId=" + user_id).toURL();
				break;
			case "schoology":
				u = new URI(a.lti_ags_lineitem_url + "/results?user_id=" + user_id.substring(0,user_id.indexOf(":"))).toURL();
				break;
			default:
				u = new URI(a.lti_ags_lineitem_url + "/results?userId=" + user_id + "&user_id=" + user_id).toURL();				
			}
			
			debug.append("0");
			uc = (HttpURLConnection) u.openConnection();
			setRequestHeaders(uc);
			uc.setDoInput(true);
			uc.setRequestMethod("GET");
			uc.setRequestProperty("Authorization", bearerAuth);
			uc.setRequestProperty("Accept", "application/vnd.ims.lis.v2.resultcontainer+json");
			uc.connect();
			debug.append("1");
			
			int responseCode = uc.getResponseCode();
			debug.append("2");
			if (responseCode == HttpURLConnection.HTTP_OK) { // 200
				BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
				JsonArray json = JsonParser.parseReader(reader).getAsJsonArray();
				reader.close();
				debug.append("3");
				
				Iterator<JsonElement> iterator = json.iterator();
				while(iterator.hasNext()){
					JsonObject result = iterator.next().getAsJsonObject();
					if (result.get("userId").getAsString().equals(user_id)) {
						return String.valueOf(Math.round(1000.*result.get("resultScore").getAsDouble()/result.get("resultMaximum").getAsDouble())/10.);
					}
				}
				return "no score found: " + json.toString();
			} else {
				debug.append("4");
				BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
				debug.append("5");
				
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				reader.close();
				debug.append("6");
				return "response code=" + responseCode + " for " + u.toString() + "<br/>" + json.toString(); 
			}
		} catch (Exception e) {
			
			return (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString();
		}
	}
	
	static String postUserScore(Score s, String userId) throws Exception {
		// This method uses the LTIv1p3 message protocol to post a user's score to the LMS grade book.
		// The lineitem URL corresponds to the LMS grade book column for the Assignment entity,
		// and the specific cell is identified by the user_id value defined by the LMS platform

		StringBuffer buf = new StringBuffer("<h2>PostUserScoreDebug</h2>");
		String accessToken = null;
		try {
			Assignment a = ofy().load().type(Assignment.class).id(s.assignmentId).safe();
			if (a == null) throw new Exception("Assignment not found: " + s.assignmentId);
			String scope = "https://purl.imsglobal.org/spec/lti-ags/scope/score";
			buf.append("AssignmentId=" + (a==null?"unknown":a.id) + "<br/>");
			String hashedId = Subject.hashId(userId);
			buf.append("User hashedId=" + hashedId + "<br/>");
			if (a != null) buf.append("ScoreKey: " + key(key(User.class,hashedId),Score.class,a.id).toString() + "<br/>");
			
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			buf.append("Deployment: " + d.platform_deployment_id + "(" + d.lms_type + ")<br/>");
			accessToken = AccessTokenCache.fetchToken(d,scope);
			buf.append("AuthToken: [REDACTED]<br/>");

			if (accessToken != null && accessToken.startsWith("Failed")) throw new Exception("Failed: could not get access token. " + accessToken);
			String bearerAuth = "Bearer " + accessToken;
			
			String raw_id = userId.substring(userId.lastIndexOf("/")+1);
			
			SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
			Timestamp timestamp = new Timestamp(new Date().getTime());
			//String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format());   //.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

			JsonObject j = new JsonObject();
			j.addProperty("timestamp", sdf2.format(timestamp));
			double scoreGiven = "Homework".equals(a.assignmentType) ? s.homeworkScore : s.score;
			j.addProperty("scoreGiven", scoreGiven);
			j.addProperty("scoreMaximum", Double.valueOf(s.maxPossibleScore));
			if (s.numberOfAttempts>0) j.addProperty("comment", "Attempt "+s.numberOfAttempts+": "+scoreGiven+"/"+s.maxPossibleScore);
			j.addProperty("activityProgress", "Completed");
			j.addProperty("gradingProgress", "FullyGraded");
			j.addProperty("userId", raw_id);
			String json = j.toString();

			// append "/scores" to the lineitem URL, taking into account that the URL may have a query part (thank you, Moodle)
			URL u = null;
			if (a != null && a.lti_ags_lineitem_url != null) {
				int i = a.lti_ags_lineitem_url.indexOf("?")==-1?a.lti_ags_lineitem_url.length():a.lti_ags_lineitem_url.indexOf("?");
				u = new URI(a.lti_ags_lineitem_url.substring(0,i) + "/scores" + a.lti_ags_lineitem_url.substring(i)).toURL();

				HttpURLConnection uc = (HttpURLConnection) u.openConnection();
				setRequestHeaders(uc);
				uc.setRequestMethod("POST");
				uc.setRequestProperty("Authorization", bearerAuth);
				uc.setRequestProperty("Content-Type", "application/vnd.ims.lis.v1.score+json");
				uc.setDoOutput(true);

				// send the message
				//OutputStreamWriter toTC = new OutputStreamWriter(uc.getOutputStream());
				OutputStream os = uc.getOutputStream();
				byte[] json_bytes = json.getBytes("utf-8");
				os.write(json_bytes, 0, json_bytes.length);           
				os.close();

				int responseCode = uc.getResponseCode(); // success if 200-202
				buf.append("Response Code = " + responseCode + "<br/>");

				boolean success = responseCode>199 && responseCode<203;
				if (success) {  
					s.lisReportComplete = true;
					ofy().save().entity(s);
					buf.append("Success " + responseCode + "<br/>JSON: " + json);
					//sendEmailToAdmin("Score submission success",buf.toString());
				} else if (responseCode==422) {
					buf.append("Response code 422: This LMS does not allow LTI score submissions for instructors or test students.<br/>");
					//Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Score submission failed",buf.toString());
				} else {			
					buf.append(uc.getRequestMethod() + " " + u.toString() + "<br>"
							+ "Content-Type: application/vnd.ims.lis.v1.score+json<br>"
							+ "Authorization: [REDACTED]<p>"
							+ json + "<p>");
					Map<String,List<String>> headers = uc.getHeaderFields();
					for (Entry<String,List<String>> e : headers.entrySet()) {
						buf.append(e.getKey() + ": ");
						for (String es : e.getValue()) buf.append(es + " ");
						buf.append("<br>");
					}

					BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
					String line;
					while ((line = reader.readLine()) != null) {
						buf.append(line);
					}
					reader.close();
				}
			}
		} catch (Exception e) {
			String errorDetails = e.toString();
			if (accessToken != null && !accessToken.isBlank()) {
				errorDetails = errorDetails.replace(accessToken, "[REDACTED]");
			}
			buf.append("Exception: " + errorDetails + "<br/>");
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Score submission failed",
					redactAccessToken(buf.toString(), accessToken));
		}
		return redactAccessToken(buf.toString(), accessToken);
	}

	private static String redactAccessToken(String diagnostic, String accessToken) {
		if (diagnostic == null || accessToken == null || accessToken.isBlank()) return diagnostic;
		return diagnostic.replace(accessToken, "[REDACTED]");
	}

	static JsonObject getMembershipContainer(Assignment a) {
		JsonObject membershipContainer = new JsonObject();
		
		try {
			String scope = "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
			String bearerAuth;
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			String accessToken = AccessTokenCache.fetchToken(d,scope);
			if ((bearerAuth=accessToken).startsWith("response")) throw new Exception("the LMS failed to issue an auth token: " + bearerAuth);
			else bearerAuth = "Bearer " + bearerAuth;

			if (a.lti_nrps_context_memberships_url==null) throw new Exception("the service endpoint URL for this group is unknown");

			String next_url = a.lti_nrps_context_memberships_url;

			while (next_url != null) {
				URL u = new URI(next_url).toURL();
				HttpURLConnection uc = (HttpURLConnection) u.openConnection();
				setRequestHeaders(uc);
				//uc.setDoOutput(true);
				uc.setDoInput(true);
				uc.setRequestMethod("GET");
				uc.setRequestProperty("Authorization", bearerAuth);
				uc.setRequestProperty("Accept", "application/vnd.ims.lti-nrps.v2.membershipcontainer+json");
				uc.connect();

				int responseCode = uc.getResponseCode();
				if (responseCode > 199 && responseCode < 203) { // OK
					BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					reader.close();

					// Copy the relevant parts of the json to the membershipContainer if they don;t already exist:
					if (membershipContainer.get("id") == null) membershipContainer.add("id", json.get("id"));
					if (membershipContainer.get("context") == null) membershipContainer.add("context", json.get("context"));
					if (membershipContainer.get("members") == null) membershipContainer.add("members", json.get("members"));
					else membershipContainer.get("members").getAsJsonArray().addAll(json.get("members").getAsJsonArray());
						
				} else return null;
				next_url = null;
				String[] links = null;
				if ( uc.getHeaderField("Link") != null) links = uc.getHeaderField("Link").split(",");
				if (links != null) {
					for (String l : links) {
						if (l.contains("next")) {
							next_url = l.trim().substring(1); // removes any leading/trailing white space and the first <
							next_url = next_url.substring(0,next_url.indexOf(">"));
						}
					}
				}
				uc.disconnect();
			}
		} catch (Exception e) {}
		
		return membershipContainer;
	}
	
	static Map<String,String[]> getMembership(Assignment a) {
		// This method uses the LTIv1p3 message protocol to retrieve the group membership from the LMS.
		// If this service is offered by providing the endpoint, the Json array MUST contain the user_id and roles
		// values, but may also include other fields such as name, given_name, middle_name, family_name, email, ...
		Map<String,String[]> membership = new HashMap<String,String[]>();
		String accessToken = null;
		boolean rejectedResponse = false;
		StringBuilder debug = new StringBuilder("Failed LTIMessage.getMembership()\nDeployment: ")
				.append(a == null ? "unknown" : a.domain).append('\n');
		
		try {
			if (a == null) throw new IllegalArgumentException("Assignment is required for a membership request.");
			String scope = "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			accessToken = AccessTokenCache.fetchToken(d,scope);
			if (accessToken == null || accessToken.startsWith("Failed") || accessToken.startsWith("response"))
				throw new Exception("The LMS failed to issue an access token.");
			String bearerAuth = "Bearer " + accessToken;
			
			if (a.lti_nrps_context_memberships_url==null) throw new Exception("the service endpoint URL for this group is unknown");
			
			String next_url = a.lti_nrps_context_memberships_url;
    		
			while (next_url != null) {
				URL u = new URI(next_url).toURL();
				debug.append("\nRequest: GET ").append(u).append("\nRequest body: (none)\n");
				HttpURLConnection uc = (HttpURLConnection) u.openConnection();
				setRequestHeaders(uc);
				uc.setDoInput(true);
				uc.setRequestMethod("GET");
				uc.setRequestProperty("Authorization", bearerAuth);
				uc.setRequestProperty("Accept", "application/vnd.ims.lti-nrps.v2.membershipcontainer+json");
				for (Entry<String,List<String>> header : uc.getRequestProperties().entrySet()) {
					String value = "Authorization".equalsIgnoreCase(header.getKey()) || "Cookie".equalsIgnoreCase(header.getKey())
							? "(redacted)" : String.join(", ", header.getValue());
					debug.append(header.getKey()).append(": ").append(value).append('\n');
				}
				try {

				int responseCode = uc.getResponseCode();
				rejectedResponse = responseCode < 200 || responseCode > 202;
				debug.append("\nResponseCode: ").append(responseCode).append('\n');
				for (Entry<String,List<String>> header : uc.getHeaderFields().entrySet()) {
					String value = "Set-Cookie".equalsIgnoreCase(header.getKey())
							? "(redacted)" : String.join(", ", header.getValue());
					debug.append(header.getKey() == null ? "Status" : header.getKey()).append(": ").append(value).append('\n');
				}
				String responseBody;
				try (InputStream responseStream = responseCode >= 400 ? uc.getErrorStream() : uc.getInputStream()) {
					responseBody = responseStream == null ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
				}
				debug.append("Response body:\n").append(responseBody).append('\n');
				if (responseCode > 199 && responseCode < 203) { // OK
					JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

					JsonArray members = json.get("members").getAsJsonArray();
					Iterator<JsonElement> iterator = members.iterator();
					while(iterator.hasNext()){
						JsonObject member = iterator.next().getAsJsonObject();
						String user_id = member.get("user_id").getAsString();
						//String roles = member.get("roles").getAsString().toLowerCase();
						String roles = member.get("roles").getAsJsonArray().toString().toLowerCase();
						String role = roles.contains("administrator")?"Administrator":roles.contains("instructor")?"Instructor":"Learner";
						String name = "";
						try {
							name = member.get("name").getAsString();
						} catch (Exception e) {
							try {
								name = member.get("family_name").getAsString() + ", " + member.get("given_name").getAsString();
							} catch (Exception e2) {
							}
						}
						String email = "";
						try {
							email = member.get("email").getAsString();
						} catch (Exception e) {
						}
						String[] properties = {role, name, email};
						membership.put(user_id,properties);
					}
				} else throw new IOException("Membership request returned HTTP " + responseCode);
				next_url = null;
				try {  // per LTI NPRS specs, this section looks for a HttpLink to the next page of results
					String[] links = uc.getHeaderField("Link").split(",");  // splits comma-separated list of Links
					for (String l : links) if (l.contains("next")) next_url = l.substring(l.indexOf("<")+1,l.indexOf(">")); // url is enclosed in <>
				} catch (Exception e2) {}
				} finally {
					uc.disconnect();
				}
			}
		} catch (Exception e) {
			debug.append("\nFailure: ").append(LTIv1p3Launch.describeFailure(e));
			String diagnostic = debug.toString();
			if (accessToken != null && !accessToken.isEmpty()) diagnostic = diagnostic.replace(accessToken, "(redacted)");
			try {
				Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "LTI Membership Request Failure",
						"<pre>" + HtmlUtils.htmlEscape(diagnostic) + "</pre>");
			} catch (Exception emailFailure) {
				logger.log(java.util.logging.Level.WARNING, "Unable to send membership failure email.", emailFailure);
			}
		}
		return rejectedResponse ? null : membership;
	}

}