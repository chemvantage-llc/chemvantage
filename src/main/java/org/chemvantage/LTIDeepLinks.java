package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.googlecode.objectify.Key;

@WebServlet(urlPatterns={"/lti/deeplinks","/lti/deeplinks/"})
public class LTIDeepLinks extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			if ("LTI-1p0".equals(request.getParameter("lti_version"))) throw new Exception("Sorry, deep linking is only available for connections using LTI Advantage.");
			JsonObject claims = validateDeepLinkRequest(request);
			Deployment d = validateIdToken(request);  // returns the validated Deployment			
			d.claims = claims.toString();
			ofy().save().entity(d);
			User user = null;
			if (request.getParameter("state") != null) {
				validateStateToken(request); // ensures proper OIDC authorization flow completed							
				user = getUserClaims(claims);
				user.setToken();
			} else if (request.getParameter("sig") != null) {  // return of authenticated user
				user = User.getUser(request.getParameter("sig"));
				if (user==null) throw new Exception("Invalid or expired User entity.");
			} else { // wrong URL or bad request
				throw new Exception("Wrong URL or Bad Request. This URL only receives LTI Advantage (v1.3) Deep Linking requests for ChemVantage. "
						+ "Please check to ensure that your LMS is registered properly. Contact admin@chemvantage.org for assistance.");
			}
			debug.append("request validated.");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			switch (userRequest) {
			case "Select assignment":
				if (Boolean.parseBoolean(request.getParameter("Refresh"))) out.println(contentPickerForm(user,request,claims));
				else out.println(deepLinkResponseMsg(request));
				break;
			default:
				if (request.getParameter("id_token") == null) throw new Exception("ID token not found");
				out.println(contentPickerForm(user,request,claims));
			}
		} catch (Exception e) {	 
			Enumeration<String> parameterNames = request.getParameterNames();
			String message = "<h1>LTI DeepLinking Failed. Status 401</h1>" + e.getMessage()==null?e.toString():e.getMessage();
			message += "<br/>To: " + request.getServerName();
			message += "<br/>From: " + request.getRemoteHost();
			while (parameterNames.hasMoreElements()) {
				String name = parameterNames.nextElement();
				message += "<br />" + name + ": " + request.getParameter(name);
			}
			message += "<br/>" + debug.toString();
			if (Subject.getProjectId().equals("dev-vantage-hrd")) Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Deep Linking Error",message);
			//if (!message.contains("Unauthorized")) Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Deep Linking Error",message);
			//Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Deep Linking Error",message);
			
			out.println(message);
			//response.sendError(401,e.getMessage()==null?e.toString():e.getMessage());
		}
	}

	JsonObject validateDeepLinkRequest(HttpServletRequest request) throws Exception {
			if (request.getParameter("id_token")==null && request.getParameter("login_hint")!=null) 
				throw new Exception("The required id_token was missing. Please ensure that the OIDC Initiation URL is set to https://www.chemvantage.org/auth/token");
			
			// Decode the JWT id_token payload as a JsonObject:
			JsonObject claims = null;
			try {
				DecodedJWT id_token = JWT.decode(request.getParameter("id_token"));
				String json = new String(Base64.getUrlDecoder().decode(id_token.getPayload()));
				claims = JsonParser.parseString(json).getAsJsonObject();
			} catch (Exception e) {
				throw new Exception("The id_token was not a valid JWT.");
			}
			try {
				verifyLtiMessageClaims(claims);	
			} catch (Exception e) {
				throw new Exception("LTI message claims were invalid. " + e.getMessage());
			}
			try {
				verifyIsInstructor(claims);
			} catch (Exception e) {
				throw new Exception("Unauthorized: " + e.getMessage());
			}
			
			return claims;
	}
	
	protected void validateStateToken(HttpServletRequest request) throws Exception {
		/* This method ensures that the state token required by LTI v1.3 standards is a
		 * valid token issued by the tool provider (ChemVantage) as part of the LTI
		 * launch request sequence. Otherwise throws a JWTVerificationException.
		 */

		String iss = "https://" + request.getServerName();
		Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
		JWTVerifier verifier = JWT.require(algorithm).withIssuer(iss).build();
		String state = request.getParameter("state");
		verifier.verify(state);
	    String nonce = JWT.decode(state).getClaim("nonce").asString();
	    if (!Nonce.isUnique(nonce)) throw new Exception("Nonce was used previously.");
	    
	    // return the state token payload as a JSON
	    JsonObject state_json = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(JWT.decode(state).getPayload()))).getAsJsonObject();
	    if (!state_json.get("redirect_uri").getAsString().contains("https://" + request.getServerName() + "/lti/deeplinks")) throw new Exception("Invalid redirect_uri.");
	}

	protected Deployment validateIdToken(HttpServletRequest request) throws Exception {
		DecodedJWT id_token = JWT.decode(request.getParameter("id_token"));
		Deployment d = null;
		
		try {
			// get the platform_id and deployment_id to load the correct Deployment d
			String platform_id = id_token.getIssuer();
			if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);
			
			String deployment_id = id_token.getClaim("https://purl.imsglobal.org/spec/lti/claim/deployment_id").asString();
			if (deployment_id == null) throw new Exception("The deployment_id claim was not found in the id_token payload.");
			String platformDeploymentId = platform_id + "/" + deployment_id;

			d = Deployment.getInstance(platformDeploymentId);
			
			if (d==null) throw new Exception("The deployment was not found in the ChemVantage database.");
			
			if (d.expires != null && d.expires.before(new Date())) d.status = "blocked";
			if ("blocked".equals(d.status)) throw new Exception("Sorry, we were unable to launch ChemVantage from this "
					+ "account. Please contact admin@chemvantage.org for assistance to reactivate the account. Thank you.");
			
			// validate the id_token audience:
			List<String> aud = id_token.getAudience();
			if (aud.size()==1 && aud.get(0).contentEquals(d.client_id)); // OK, continue
			else if (aud.size()>1 && aud.contains(d.client_id) && id_token.getClaim("azp").asString().contentEquals(d.client_id)); // OK, continue
			else throw new Exception("The id_token client_id claim is not authorized in ChemVantage.");

			// validate the id_token signature:
			// retrieve the public Java Web Key from the platform to verify the signature
			if (d.well_known_jwks_url==null) throw new Exception("The deployment does not have a valid JWKS URL.");
			URL jwks_url = new URI(d.well_known_jwks_url).toURL();
			JwkProvider provider = new UrlJwkProvider(jwks_url);
			if (id_token.getKeyId() == null || id_token.getKeyId().isEmpty()) throw new Exception("No JWK id found.");
			Jwk jwk = provider.get(id_token.getKeyId()); //throws Exception when not found or can't get one
			RSAPublicKey public_key = (RSAPublicKey)jwk.getPublicKey();
			// verify the JWT signature
			Algorithm algorithm = Algorithm.RSA256(public_key,null);
			if (!"RS256".contentEquals(id_token.getAlgorithm())) throw new Exception("JWT algorithm must be RS256");
			JWT.require(algorithm).build().verify(id_token);  // throws JWTVerificationException if not valid
		} catch (Exception e) {
			throw new Exception("The id_token could not be validated. " + e.getMessage());
		}
		return d;
	}

	void verifyLtiMessageClaims(JsonObject claims) throws Exception {
		// verify LTI version 1.3.0
		JsonElement lti_version = claims.get("https://purl.imsglobal.org/spec/lti/claim/version");
		if (lti_version == null) throw new Exception("LTI version claim was missing.");    
		if (!"1.3.0".equals(lti_version.getAsString())) throw new Exception("Incorrect LTI version claim");

		// Validate the LTI message_type:
		JsonElement message_type = claims.get("https://purl.imsglobal.org/spec/lti/claim/message_type");
		if (message_type == null) throw new Exception("Missing LTI message_type.");
		if (!"LtiDeepLinkingRequest".equals(message_type.getAsString())) throw new Exception("LTI message_type claim must be LtiDeepLinkingRequest");
	
		JsonElement deep_linking_settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings");
		if (deep_linking_settings == null) throw new Exception("Required settings claim was not found.");
		
		JsonElement userId_claim = claims.get("sub");
		if (userId_claim == null) throw new Exception("The id_token was missing required subject claim");
	}
	
	void verifyIsInstructor(JsonObject claims) throws Exception {
		// use the roles claim to verify that the requester is an instructor or administrator
		boolean authorized = false;
		JsonElement roles_claim = claims.get("https://purl.imsglobal.org/spec/lti/claim/roles");
		if (roles_claim == null || !roles_claim.isJsonArray()) throw new Exception("Required roles claim is missing from the id_token");
		JsonArray roles = roles_claim.getAsJsonArray();
		Iterator<JsonElement> roles_iterator = roles.iterator();
		while(!authorized && roles_iterator.hasNext()){
			String role = roles_iterator.next().getAsString().toLowerCase();
			if (role.contains("instructor")) authorized = true;
			if (role.contains("administrator")) authorized = true;
			if (role.contains("contentdeveloper")) authorized = true;
			}
		if (!authorized) throw new Exception("Sorry, this link works only for the course instructor.");
	}
	
	static String contentPickerForm(User user, HttpServletRequest request,JsonObject claims) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("ChemVantage Assignment Setup"));
		try {
		JsonObject settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings").getAsJsonObject();
		boolean acceptsLtiResourceLink = settings.get("accept_types").getAsJsonArray().contains(new JsonPrimitive("ltiResourceLink"));
		if (!acceptsLtiResourceLink) throw new Exception("Deep Link request failed because platform does not accept new LtiResourceLinks.");
		
		buf.append("<h1>Assignment Setup Page</h1>");

		buf.append("<form name=AssignmentForm action=/lti/deeplinks method=POST>");
		buf.append("<input type=hidden name=id_token value='" + request.getParameter("id_token") + "' />");
		buf.append("<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
		buf.append("<input type=hidden name=UserRequest value='Select assignment' />");
		buf.append("<input type=hidden name=Refresh id=refresh value=true />");
		buf.append("<input type=hidden name=Subject value='" + claims.get("sub") + "' />");
		
		String assignmentType = request.getParameter("AssignmentType");
		if (assignmentType==null) assignmentType="";
		
		buf.append("Select the type of assignment to create:");
		buf.append("<div style='display:table;width:100%'><div style='display:table-row'><div style='display:table-cell'>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='PlacementExam'" + (assignmentType.equals("PlacementExam")?" CHECKED />":" />") + "&nbsp;Placement&nbsp;Exam</label><br/>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='SmartText'" + (assignmentType.equals("SmartText")?" CHECKED />":" />") + "&nbsp;SmartText Chapter</label><br />"
				+ "</div><div style='display:table-cell'>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='Quiz'" + (assignmentType.equals("Quiz")?" CHECKED />":" />") + "&nbsp;Quiz</label><br />"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='Homework'" + (assignmentType.equals("Homework")?" CHECKED />":" />") + "&nbsp;Homework</label><br />"
				+ "</div><div style='display:table-cell'>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='VideoQuiz'" + (assignmentType.equals("VideoQuiz")?" CHECKED />":" />") + "&nbsp;Video</label><br />"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='Poll'" + (assignmentType.equals("Poll")?" CHECKED />":" />") + "&nbsp;In-class&nbsp;Poll</label><br />"
				+ "</div><div style='display:table-cell'>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='PracticeExam'" + (assignmentType.equals("PracticeExam")?" CHECKED />":" />") + "&nbsp;Practice&nbsp;Exam</label><br/>"
				+ "<label><input type=radio name=AssignmentType onClick=document.getElementById('plswait').style='display:block';this.form.submit(); value='Sage'" + (assignmentType.equals("Sage")?" CHECKED />":" />") + "&nbsp;Sage&nbsp;Tutor</label><br/>"
				+ "</div></div></div><br/>");
		buf.append("<div id=plswait style='color:red;display:none'>Please wait...</div>");
		
		// find out whether the LMS accepts multiple assignments in the Deep Linking process
		boolean acceptsMultiple = settings.get("accept_multiple").getAsBoolean();
		
		// display a selector, depending on the type of assignment selected:
		long textId = 0L;
		Text text = null;
		List<Text> texts = null;
		int oneThird = 0;
		int oneHalf = 0;
		int i = 0;
		switch (assignmentType) {
		case "PlacementExam":
			buf.append("<input type=submit onClick=\"document.getElementById('refresh').value=false\"; value='Create a placement exam for General Chemistry' /><br/><br/>");
			break;
		case "Poll":
			buf.append("Poll questions will be selected or created when the assignment is launched by the instructor.<br/><br/>"
					+ "<input type=submit onClick=\"document.getElementById('refresh').value=false\"; value='Create an in-class poll' />");
			break;
		case "SmartText":
			try {
				texts = ofy().load().type(Text.class).filter("smartText",true).list();
				textId = Long.parseLong(request.getParameter("TextId"));
				buf.append("<div>Please select one of the available ChemVantage smart textbooks below:</div>");
				for (Text txt : texts) {
					if (txt.id==textId) text = txt;
					buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
				}
				buf.append("<br/>");
			if (text != null) {
				buf.append("<div style='color:red'>Select " + (acceptsMultiple?"at least ":"") + "one of the chapters below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text.chapters.size()/2;
					for (Chapter ch : text.chapters) {
					if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
					i++;
					buf.append("<div><label><input type=" + (acceptsMultiple?"checkbox":"radio") + " name=ChapterNumber onClick=countChecks('SmartText'); "
						+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
				}
				buf.append("</div></div></div>");
				buf.append("<input id=stsub type=submit disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select" + (acceptsMultiple?" at least":"") + " one chapter' />");
			}
			} catch (Exception e) {
				buf.append("<div style='color:red'>Please select one of the available ChemVantage smart textbooks below:</div>");
				if (texts != null) {
					for (Text txt : texts) buf.append("<div><label><input type=radio name=TextId value=" + txt.id + " onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
				}
			}		
			buf.append("<br/>");
			break;
		case "Quiz":
		case "Homework":
		case "Sage":
			try {
				texts = ofy().load().type(Text.class).list();
				textId = Long.parseLong(request.getParameter("TextId"));
				buf.append("<div>Please select one of the topic groups below:</div>");
				Text allTopics = null;
				for (Text txt : texts) {
					if (txt.chapters.isEmpty()) continue;
					if (txt.id==textId) text = txt;
					if (txt.title.equals("View All Topics")) {
						allTopics = txt;
						continue;
					}
					buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
				}
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + allTopics.title + "</label></div><br/>");
				
			if (text != null) {
				buf.append("<div style='color:red'>Select " + (acceptsMultiple?"at least ":"") + "one of the topics below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text.chapters.size()/2;
					for (Chapter ch : text.chapters) {
					if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
					i++;
					buf.append("<div><label><input type=" + (acceptsMultiple?"checkbox":"radio") + " name=ChapterNumber onClick=countChecks('" + assignmentType + "'); "
						+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
				}
				buf.append("</div></div></div>");
				buf.append("<input type=submit id=stsub disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select" + (acceptsMultiple?" at least":"") + " one topic' />");
			}

		} catch (Exception e) {
			buf.append("<div style='color:red'>Please select one of the topic groups below:</div>");
			texts = ofy().load().type(Text.class).list();
			Text allTopics = null;
			if (texts != null) {
				for (Text txt : texts) {
					if (txt.chapters.isEmpty()) continue;
					if (txt.id==textId) text = txt;
					if (txt.title.equals("View All Topics")) {
						allTopics = txt;
						continue;
					}
					buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
				}
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + allTopics.title + "</label></div><br/>");
			}
		}
		break;
		case "PracticeExam":
			try {
				texts = ofy().load().type(Text.class).list();
				textId = Long.parseLong(request.getParameter("TextId"));
				buf.append("<div>Please select one of the topic groups below:</div>");
				Text allTopics = null;
				if (texts != null) {
					for (Text txt : texts) {
						if (txt.chapters.isEmpty()) continue;
						if (txt.id==textId) text = txt;
						if (txt.title.equals("View All Topics")) {
							allTopics = txt;
							continue;
						}
						buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
					}
					if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + allTopics.title + "</label></div><br/>");
				}
				
			if (text != null) {
				buf.append("<div style='color:red'>Select at least three of the topics below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text.chapters.size()/2;
					for (Chapter ch : text.chapters) {
					if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
					i++;
					buf.append("<div><label><input type=checkbox name=ChapterNumber onClick=countChecks('" + assignmentType + "'); "
						+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
				}
				buf.append("</div></div></div>");
			buf.append("<input type=submit id=pesub disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select at least three topics' />");
			}
		} catch (Exception e) {
				buf.append("<div style='color:red'>Please select one of the topic groups below:</div>");
				Text allTopics = null;
				if (texts != null) {
					for (Text txt : texts) {
						if (txt.chapters.isEmpty()) continue;
						if (txt.id==textId) text = txt;
						if (txt.title.equals("View All Topics")) {
							allTopics = txt;
							continue;
						}
						buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + txt.title + "</label></div>");
					}
					if (allTopics != null) {
						buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />&nbsp;" + allTopics.title + "</label></div><br/>");
					}
				}
			}	
			break;
		case "VideoQuiz":
			buf.append("Videos marked with an asterisk (*) have embedded quizzes; others will give full credit for watching to the end.<br/>");
			List<Video> videos = ofy().load().type(Video.class).order("orderBy").list();
			oneThird = videos.size()/3;
			buf.append("<div style='color:red'>Please select " + (acceptsMultiple?"at least":"") + " one topic:</div>");
			buf.append("<div style=display:table><div style=display:table-row><div style=display:table-cell>");
			for (Video v : videos) {
				if (v.orderBy.equals("Hide")) continue;
				if (i==oneThird || i==2*oneThird) buf.append("</div><div style=display:table-cell>");
				i++;
				buf.append("<div><label><input type=" + (acceptsMultiple?"checkbox":"radio") + " name=VideoId value=" + v.id + " onClick=countChecks('VideoQuiz'); />&nbsp;" + v.title + (v.breaks==null?"":"*") + "</label></div>");
			}
			buf.append("</div></div></div>");
			buf.append("<input type=submit id=vidsub disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select" + (acceptsMultiple?" at least":"") + " one topic' />");
			break;
		default:  // no assignmentType selected
		}
		
		buf.append("</form>");
		
		buf.append("<script>"
				+ "function countChecks(type) {"
				+ "  var videoArray=document.getElementsByName('VideoId');"
				+ "  var stArray=document.getElementsByName('ChapterNumber');"
				+ "  var peSubmit = document.getElementById('pesub');"
				+ "  var vidSubmit = document.getElementById('vidsub');"
				+ "  var stSubmit = document.getElementById('stsub');"
				+ "  var count=0;"
				+ "  switch (type) {"
				+ "    case 'PracticeExam':"
				+ "      for (var i=0;i<stArray.length;i++) if (stArray[i].checked) count++;"
				+ "        peSubmit.disabled=(count<3);"
				+ "      if (count<3) peSubmit.value='Select at least 3 topics';"
				+ "      else peSubmit.value='Create this exam';"
				+ "      break;"
				+ "    case 'VideoQuiz':"
				+ "      for (var i=0;i<videoArray.length;i++) if (videoArray[i].checked) count++;"
				+ "      vidSubmit.disabled = (count<1);"
				+ "      if (count<1) vidSubmit.value='Select" + (acceptsMultiple?" at least":"") + " one topic';"
				+ "      else vidSubmit.value='Create ' + (count==1?'this assignment':'these assignments');"
				+ "      break;"
				+ "    case 'Quiz':"
				+ "    case 'Sage':"
				+ "	   case 'Homework':"
				+ "	   case 'SmartText':"
				+ "      for (var i=0;i<stArray.length;i++) if (stArray[i].checked) count++;"
				+ "      stSubmit.disabled = (count<1);"
				+ "      if (count<1) stSubmit.value='Select" + (acceptsMultiple?" at least":"") + " one chapter';"
				+ "      else stSubmit.value='Create ' + (count==1?'this assignment':'these assignments');"
				+ "      break;"
				+ "  }"
				+ "}"
				+ "</script>");

		buf.append(Subject.footer);
		} catch (Exception e) {
			buf.append(e.toString() + " " + e.getMessage());
		}
		return buf.toString();
	}
	
	User getUserClaims(JsonObject claims) throws Exception {
		// Process User information:

		User user = null;
		JsonElement sub = claims.get("sub");
		if (sub==null || sub.getAsString().isEmpty()) user = new User();  // special provision to allow anonymous user via LTI launch
		else user = new User(claims.get("iss").getAsString(), sub.getAsString());

		JsonElement roles_claim = claims.get("https://purl.imsglobal.org/spec/lti/claim/roles");
		if (roles_claim == null || !roles_claim.isJsonArray()) throw new Exception("Required roles claim is missing from the id_token");
		JsonArray roles = roles_claim.getAsJsonArray();
		Iterator<JsonElement> roles_iterator = roles.iterator();
		while(roles_iterator.hasNext()){
			String role = roles_iterator.next().getAsString();
			user.setIsTeachingAssistant(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership/Instructor#TeachingAssistant"));
			user.setIsInstructor(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor"));
			user.setIsAdministrator(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Administrator"));
			user.setIsAdministrator(role.equals("http://purl.imsglobal.org/vocab/lis/v2/institution/person#Administrator"));
		}
		return user;
	}

	String deepLinkResponseMsg(HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer();
		String deep_link_return_url = null;
		try {
			JsonObject claims = null;
			DecodedJWT id_token = JWT.decode(request.getParameter("id_token"));
			String json = new String(Base64.getUrlDecoder().decode(id_token.getPayload()));
			claims = JsonParser.parseString(json).getAsJsonObject();
			
			String platform_id = claims.get("iss").getAsString();
			String deployment_id = claims.get("https://purl.imsglobal.org/spec/lti/claim/deployment_id").getAsString();
			JsonObject settings = claims.get("https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings").getAsJsonObject();		
			deep_link_return_url = settings.get("deep_link_return_url").getAsString();
			String data = settings.get("data")==null?"":settings.get("data").getAsString();
			
			Date now = new Date();
			Date exp = new Date(now.getTime() + 5400000L); // 90 minutes from now
			Deployment d = Deployment.getInstance(platform_id + "/" + deployment_id);
			
			// Determine the assignmentType for this selection
			String assignmentType = request.getParameter("AssignmentType");
			if (assignmentType == null || assignmentType.isEmpty()) throw new Exception("Assignment type is missing.");
			
			// Make a List of topicIds for this selection (may be VideoIds instead)
			// PracticeExam assignments always transmit as TopicIds in checkboxes
			// VideoQuiz assignments transmit as VideoId (checkboxes if acceptsMultiple; otherwise radio)
			// Quiz or Homework assignments transmit as TopicId (checkboxes if acceptsMultiple; otherwise radio)
			
			Long textId = null;
			String[] videoIdArray;
			String[] chapterNoArray;
			List<Long> videoIds = new ArrayList<Long>();
			List<Integer> chapterNumbers = new ArrayList<Integer>();
			switch (assignmentType) {
			case "VideoQuiz":
				videoIdArray = request.getParameterValues("VideoId");
				for (int i=0;i<videoIdArray.length;i++) videoIds.add(Long.parseLong(videoIdArray[i]));			
				break;
			case "Sage":
			case "Quiz":
			case "Homework":
			case "SmartText":
			case "PracticeExam":
				textId = Long.parseLong(request.getParameter("TextId"));
				chapterNoArray = request.getParameterValues("ChapterNumber");
				for (int i=0;i<chapterNoArray.length;i++) chapterNumbers.add(Integer.parseInt(chapterNoArray[i]));
				break;
			case "Poll":
			case "PlacementExam":  // this assignment has fixed hidden conceptIds
			}
			// At this point all of the VideoIds or ChapterNumbers are recorded in the corresponding List
			// If assignmentType is Quiz, Homework, SmartText or Video, allow multiple assignments
			// If assignmentTyupe is PracticeExam, PlacementExam or Poll, only one assignment will be created
			// If assignmentType is Poll, there are no topics at this point, but set pollClosed=true;
			debug.append("data complete.");
			Assignment a = null;
			List<Assignment> assignments = new ArrayList<Assignment>();
			Text text = null;
			
			switch (assignmentType) {
			case "PracticeExam":
				a = new Assignment();
				a.assignmentType = "PracticeExam";
				a.created = now;
				a.domain = d.platform_deployment_id;
			if (textId != null) a.textId = textId;
			a.title = "";
			if (textId != null) text = ofy().load().type(Text.class).id(textId).safe();
				a.questionKeys = new ArrayList<Key<Question>>();
				if (text != null) for (Chapter ch : text.chapters) {
					if (chapterNumbers.contains(ch.chapterNumber)) {
						a.title += ch.title + ", ";
						for (Long conceptId : ch.conceptIds) {
							if (!a.conceptIds.contains(conceptId)) {
								a.conceptIds.add(conceptId);
								a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",conceptId).keys().list());
							}
						}
					}
				}
				if (a.title.contains(", ")) a.title = a.title.substring(0, a.title.lastIndexOf(", "));
				a.valid = now;
				assignments.add(a);
				break;
			case "Poll":
				a = new Assignment(assignmentType,d.platform_deployment_id);
				a.pollIsClosed = true;
				a.valid = now;
				assignments.add(a);
				break;
			case "PlacementExam":
				a = new Assignment();
				a.assignmentType = "PlacementExam";
				a.created = now;
				a.domain = d.platform_deployment_id;
				a.questionKeys = new ArrayList<Key<Question>>();
				a.conceptIds = new ArrayList<Long>();
				List<Concept> concepts = ofy().load().type(Concept.class).list();
				for (Concept c : concepts) {
					switch (c.title) {
					case "Essential Chemistry":
					case "Essential Math":
					case "Word Problems":
						a.conceptIds.add(c.id);
					break;
					}
				}
				for (int i=0;i<a.conceptIds.size();i++) {
					a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",a.conceptIds.get(i)).keys().list());
				}
				a.valid = now;
				assignments.add(a);
				break;
			case "SmartText":
			case "Quiz":
			case "Sage":
			case "Homework":
				if (textId != null) text = ofy().load().type(Text.class).id(textId).safe();
				if (text != null) for (Integer chN : chapterNumbers) {
					a = new Assignment(assignmentType,d.platform_deployment_id);
					a.textId = textId;
					a.chapterNumber = chN;
					for (Chapter ch : text.chapters) {
						if (ch.chapterNumber == a.chapterNumber) {
							a.title = ch.title;
							for (Long conceptId : ch.conceptIds) {
								a.conceptIds.add(conceptId);
								if ("Sage".equals(a.assignmentType)) continue;  // don't add questionKeys 
								else if ("Homework".equals(a.assignmentType)) { // for homework, select up to 15 questions dfistributed among the concepts
									int numConceptsRemaining = ch.conceptIds.size() - a.conceptIds.size() +1;
									a.questionKeys.addAll(selectHomeworkQuestions(conceptId,numConceptsRemaining,a.questionKeys.size()));
								}
								else { // for Quiz and SmartText, add all questions for this conceptId and assignmentType
									List<Key<Question>> conceptQuestionKeys = ofy().load().type(Question.class).filter("assignmentType",(assignmentType.equals("SmartText")?"Quiz":assignmentType)).filter("conceptId",conceptId).keys().list();
									if (!conceptQuestionKeys.isEmpty()) a.questionKeys.addAll(conceptQuestionKeys);
								}
							}
							break;
						}
					}
					debug.append("Chapter " + a.chapterNumber + ".");
					a.valid = now;
					assignments.add(a);
				}
				break;
			case "VideoQuiz":
				for (Long vId : videoIds) {
					a = new Assignment();
					a.assignmentType = "VideoQuiz";
					a.created = now;
					a.domain = d.platform_deployment_id;
					a.videoId = vId;
					a.valid = now;
					assignments.add(a);
				}
				break;
			}
			
			ofy().save().entities(assignments).now();
			for (Assignment a1 : assignments) {  // start asynchronous processes to validate the question items for each new Homework assignment
				if (a1 != null && a1.assignmentType.equals("Homework")) a1.validateQuestionItemsWithAI();
			}
				
			String serverUrl = "https://" + request.getServerName();
			String launchUrl = serverUrl + "/lti/launch";
			String iconUrl = serverUrl + "/images/CVLogo_thumb.png";
			String client_id = d.client_id;
			String subject = request.getParameter("Subject");
			String nonce = Nonce.generateNonce();
			
			Encoder enc = Base64.getUrlEncoder().withoutPadding();
			
			// Create a JSON header for the JWT to send as DeepLinkingResponse
			JsonObject header = new JsonObject();
			header.addProperty("typ", "JWT");
			header.addProperty("alg", "RS256");
			header.addProperty("kid", d.rsa_key_id);
			byte[] hdr = enc.encode(header.toString().getBytes("UTF-8"));
			
			// Create a JSON payload for the JWT to send as DeepLinkingResponse:
			JsonObject payload = new JsonObject();
			payload.addProperty("iss",client_id);
			payload.addProperty("sub",subject);
			payload.addProperty("aud",platform_id);
			payload.addProperty("nonce", nonce);
			payload.addProperty("exp", exp.getTime()/1000);
			payload.addProperty("iat", now.getTime()/1000);
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/message_type", "LtiDeepLinkingResponse");
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/version", "1.3.0");
			payload.addProperty("https://purl.imsglobal.org/spec/lti/claim/deployment_id", deployment_id);
			if (!data.isEmpty()) payload.addProperty("https://purl.imsglobal.org/spec/lti-dl/claim/data", data);
		
			//Add the user-selected assignments to the payload as an array of content_items:
			JsonArray content_items = new JsonArray();
			
			for (Assignment a1 : assignments) {
				JsonObject item = new JsonObject();
				item.addProperty("type", "ltiResourceLink");
					
				String title = null;
				int maxScore = 10;
				switch (assignmentType) {
					case "PracticeExam":
						title = "Exam - " + a1.title;
						maxScore = 100;
						break;
					case "VideoQuiz":
						title = "Video - " + ofy().load().type(Video.class).id(a1.videoId).now().title;
						maxScore = 5;
						break;
					case "Poll":
						title = "Class Poll";
						maxScore = 5;
						break;
					case "PlacementExam":
						title = "General Chemistry Placement Exam";
						maxScore = 100;
						break;
					case "SmartText":
						title = "SmartText Chapter " + a1.chapterNumber;
						maxScore = 10;
						break;
					case "Quiz":
					case "Sage":
					case "Homework":
						title = a1.assignmentType + " - " + a1.title;
						maxScore = 10;
						break;
				}
				item.addProperty("title", title);

				JsonObject lineitem = new JsonObject();
				lineitem.addProperty("scoreMaximum", maxScore);
				lineitem.addProperty("label", title);
				lineitem.addProperty("resourceId",String.valueOf(a1.id));
				
				JsonObject submissionReview = new JsonObject();
				submissionReview.add("reviewableStatus", new JsonArray());
				lineitem.add("submissionReview", submissionReview);
				
				item.add("lineItem", lineitem);
				
				// adding the assignmentId as a custom parameter should work for every LMS but is required for canvas
				JsonObject custom = new JsonObject();
				custom.addProperty("resourceId",String.valueOf(a1.id));
				custom.addProperty("resource_link_id_history","$ResourceLink.id.history");
				item.add("custom", custom);
				
				if (d.lms_type.equals("canvas")) launchUrl += "?resourceId=" + a1.id;
				item.addProperty("url", launchUrl);
				
				JsonObject icon = new JsonObject();
				icon.addProperty("url", iconUrl);
				icon.addProperty("width", 75);
				icon.addProperty("height", 70);
				item.add("icon", icon);
				
				content_items.add(item);
			}
			payload.add("https://purl.imsglobal.org/spec/lti-dl/claim/content_items", content_items);
			
			byte[] pld = enc.encode(payload.toString().getBytes("UTF-8"));
			
			// Join the header and payload together with a period separator:
			String jwt = "%s.%s".formatted(new String(hdr),new String(pld));
			
			// Add a signature item to complete the JWT:
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(KeyStore.getRSAPrivateKey(d.rsa_key_id),new SecureRandom());
			signature.update(jwt.getBytes("UTF-8"));
			String sig = new String(enc.encode(signature.sign()));
			jwt = "%s.%s".formatted(jwt,sig);
			
			// Create a form to be auto-submitted to the platform by the user_agent browser
			buf.append("Submitting your selection back to your LMS...");
			buf.append("<div style='visibility: hidden'>"
					+ "<form id=selections method=POST action='" + deep_link_return_url + "'>"
					+ "<input type=hidden name=JWT value='" + jwt + "' />"
					+ "Assignment selection OK. <input type=submit />"
					+ "</form>Please click submit if this form is not submitted automatically.</div>");
			buf.append("<script>document.getElementById('selections').submit();</script>");
			//buf.append("The new content items are: " + content_items.toString());
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage() + "Debug: " + debug.toString());
		}
		return buf.toString();
	}

	List<Key<Question>> selectHomeworkQuestions(Long conceptId, int numConceptsRemaining, int numQuestionsAlreadySelected) {
		List<Key<Question>> questionKeys = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",conceptId).keys().list();
		Collections.shuffle(questionKeys);
		int numToSelect = (int) Math.round((double)(15-numQuestionsAlreadySelected)/numConceptsRemaining);
		if (questionKeys.size() > numToSelect) return questionKeys.subList(0, numToSelect);
		else return questionKeys;
	}
}
