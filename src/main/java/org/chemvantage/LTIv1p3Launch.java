/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2019 ChemVantage LLC
*   
*   This program is free software: you can redistribute it and/or modify
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

/* This servlet executes a valid LTI ResourceLink launch request using LTI v1.3 specifications
 * The basic requesting entity is a Deployment. Although a single LMS platform may host several
 * Deployments (e.g., as separate accounts), each Deployment will designate a client_id that identifies
 * the tool provider (ChemVantage) and thereby defines the security contract for the connection.
 *  
 * The following values are determined by the Deployment, and therefore cannot be considered
 * to be universally unique for the tool provider:
 * platform_id - this must be the fully qualified secure base URL for the platform (LMS)
 * deployment_id - non-uniqueness is mitigated by prepending the platform_id to form a platformDeploymentId
 * client_id - this value should be confirmed only after the platformDeploymentId is known
 * user_id - this should be prepended with the platformId to form a unique platformUserId value
 * resource_link_id - the assignmentId should be identified by the platformDeploymentId and the resource_link_id
 * 
 * The User.token is a JWT containing useful information about the transaction, including
 * platformDeploymentId
 * platformUserId
 * user.roles - an integer defining Learner (0), Instructor (8) and Administrator (16) roles
 * assignmentId - the datastore id value for the Assignment entity for this launch
 * 
 * ChemVantage requires that each assignment must be accessed through a separate LTIv1p3Launch request
 */

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URI;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import com.googlecode.objectify.Key;

@WebServlet(urlPatterns = {"/lti/launch","/lti/launch/"})
public class LTIv1p3Launch extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		//response.sendRedirect(Subject.getServerUrl() + "/lti/registration");
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		StringBuffer buf = new StringBuffer(Subject.header("ChemVantage"));
		buf.append("<h1>Welcome to ChemVantage</h1>"
				+ "If you are a student trying to access an assignment, you must login to your class "
				+ "learning management system (LMS) and click the assignment link there.<p>"
				+ "This URL only accepts POST requests from LMS servers with a valid id_token.");
		buf.append(Subject.footer);
		out.println(buf.toString());
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		try {
			if (request.getParameter("id_token") != null) ltiv1p3LaunchRequest(request,response);  // handle LTIv1p3 launch request			
			else {
				User user = User.getUser(request.getParameter("sig"));
				if (user==null) throw new Exception("User token was missing or invalid.");				
				if (!user.isInstructor()) throw new Exception("Unauthorized. User must be an instructor or administrator for this request.");

				switch (userRequest) {
				case "UpdateAssignment":
					Assignment myAssignment = updateAssignment(request,user);
					boolean refresh = Boolean.parseBoolean(request.getParameter("Refresh"));
					if (!refresh && myAssignment.isValid()) {
						myAssignment.valid = new Date();
						ofy().save().entity(myAssignment).now();  // we will need this in a few milliseconds					
						launchResourceRequest(user,myAssignment,request,response);
					} else {  // send the user back to the resourcePickerForm
						response.getWriter().println(Subject.header("Select A ChemVantage Assignment") + pickResourceForm(user,myAssignment,request) + Subject.footer);
					}
					break;
				case "Register":
					response.getWriter().println(registrationForm(user,request,request.getParameter("platform_deployment_id")));
					break;
				default:
					throw new Exception("Wrong URL or Bad Request. This URL only receives LTI Advantage (v1.3) Resource Link and Submission Review launch requests for ChemVantage. "
							+ "Please check to ensure that your LMS is registered properly. Contact admin@chemvantage.org for assistance.");
				}
			}
		} catch (Exception e) {	
			//if (Subject.getProjectId().equals("dev-vantage-hrd")) {
				String message = "<h1>LTI Launch Failure</h1>" + (e.getMessage()==null?e.toString():e.getMessage());
				//Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "LTI Launch Failure", message + "<br/>id_token: " + request.getParameter("id_token") + "<br/>state: " + request.getParameter("state"));
				response.setContentType("text/html");
				response.getWriter().println(message);
			//} else response.sendError(401);
		}
	}

	void ltiv1p3LaunchRequest(HttpServletRequest request,HttpServletResponse response) throws Exception {
		JsonObject state = validateStateToken(request); // ensures proper OIDC authorization flow completed			

		Deployment d = validateIdToken(request);  // returns the validated Deployment
		
		// Decode the JWT id_token payload as a JsonObject:
		JsonObject claims = null;
		try {
			DecodedJWT id_token = JWT.decode(request.getParameter("id_token"));
			String json = new String(Base64.getUrlDecoder().decode(id_token.getPayload()));
			claims = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			throw new Exception("id_token was not a valid JWT.");
		}
		
		// verify that the redirect_uri are consistent with the state token:
		if (!state.get("redirect_uri").getAsString().contains("https://" + request.getServerName() + "/lti/launch")) throw new Exception("Invalid redirect_uri.");
		
		verifyLtiMessageClaims(claims); // required
		User user = getUserClaims(claims);
		if (user == null) throw new Exception("User could not be created from claims.");
		
		// Check to see if registration is complete and gather required info if necessary:
		if (user.isInstructor() && "auto".equals(d.status)) {
			response.getWriter().println(registrationForm(user,request,d.platform_deployment_id));
			return;
		}
		
		// At this point we have all of the REQUIRED info for a valid LTI launch
		// Process all remaining optional claims in try/catch structures to avoid
		// throwing unnecessary Exceptions

		switch (claims.get("https://purl.imsglobal.org/spec/lti/claim/message_type").getAsString()) {
		case "LtiResourceLinkRequest":
			launchResourceLink(request,response,d,user,claims);
			break;
		case "LtiSubmissionReviewRequest":
			launchSubmissionReview(response, claims, d, user);
			break;
		}
	}
	
	void launchResourceLink(HttpServletRequest request, HttpServletResponse response, Deployment d, User user, JsonObject claims) throws Exception {
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			// Process deployment claims:
			Deployment original_d = d.clone();  // make a copy to compare for updating later
			Date now = new Date();
			Date yesterday = new Date(now.getTime()-86400000L); // 24 hrs ago
			try {
				d.lastLogin = now;
				if (user.isInstructor()) d.claims = claims.toString();

				JsonElement platformElem = claims.get("https://purl.imsglobal.org/spec/lti/claim/tool_platform");
				if (platformElem != null) {
					JsonObject platform = platformElem.getAsJsonObject();
					JsonElement je = platform.get("email_contact");
					if (je != null) d.email = je.getAsString();
				}
			} catch (Exception e) {
			}	

			// Process information for LTI Assignment and Grade Services (AGS)
			String scope = "";
			String lti_ags_lineitem_url = null;
			String lti_ags_lineitems_url = null;
			try {  
				JsonElement agsElem = claims.get("https://purl.imsglobal.org/spec/lti-ags/claim/endpoint");
				if (agsElem != null) {
					JsonObject lti_ags_claims = agsElem.getAsJsonObject();

					// get the list of AGS capabilities allowed by the platform
					JsonArray scope_claims = lti_ags_claims.get("scope")==null?new JsonArray():lti_ags_claims.get("scope").getAsJsonArray();
					Iterator<JsonElement> scopes_iterator = scope_claims.iterator();
					while (scopes_iterator.hasNext()) scope += scopes_iterator.next().getAsString() + (scopes_iterator.hasNext()?" ":"");
					lti_ags_lineitems_url = (lti_ags_claims.get("lineitems")==null?null:lti_ags_claims.get("lineitems").getAsString());
					lti_ags_lineitem_url = (lti_ags_claims.get("lineitem")==null?null:lti_ags_claims.get("lineitem").getAsString());
				}
			} catch (Exception e) {				
			}
		
			String lti_nrps_context_memberships_url = null;
			try { 
				JsonElement nrpsElem = claims.get("https://purl.imsglobal.org/spec/lti-nrps/claim/namesroleservice");
				if (nrpsElem != null) {
					JsonObject lti_nrps_claims = nrpsElem.getAsJsonObject();
					if (lti_nrps_claims != null) {
						scope += (scope.length()>0?" ":"") + "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
						JsonElement membershipUrl = lti_nrps_claims.get("context_memberships_url");
						if (membershipUrl != null) lti_nrps_context_memberships_url = membershipUrl.getAsString();
					}
				}
			} catch (Exception e) {
			}
		
			d.scope = scope;
			debug.append(scope.isEmpty()?"Scope is empty. ":"Scope is " + scope + ". ");
		
			// Launch only premium users
			boolean isPremiumUser = user.isPremium();
			if (!isPremiumUser) {
				debug.append("t1");
				if (d.getNLicensesRemaining()>0) {
					d.nLicensesRemaining--;
					new PremiumUser(user.getHashedId(),12,0,d.organization,"allocated");
					isPremiumUser = true;
				} else if (d.price == 0) {
					new PremiumUser(user.getHashedId(),12,0,d.organization,"comped");
					isPremiumUser = true;
				}
			}
		
			// Save the updated Deployment entity, if necessary
			if (!d.equivalentTo(original_d) || original_d.lastLogin == null || original_d.lastLogin.before(yesterday)) {
				ofy().save().entity(d).now();
				debug.append("Deployment saved. ");
			}
			/* Find assignment using multiple strategies (in priority order):
			 *   1. By lti_ags_lineitem_url (most common for established graded assignments)
			 *   2. By resourceId (set during Deep Linking)
			 *   3. By domain + resourceLinkId (for ungraded assignments)
			 *   4. By resourceLinkId history (for copied assignments)
			 *   5. Create new assignment (will be completed via resourcePicker)
			 */

			Assignment myAssignment = null;
			String resourceLinkId = null;
			try {
				resourceLinkId = claims.get("https://purl.imsglobal.org/spec/lti/claim/resource_link").getAsJsonObject().get("id").getAsString();
			} catch (Exception e) {
				throw new Exception("Resource link ID value missing from id_token claims. ");
			}
	
			// Strategy 1: Find by lineitem URL
			if (myAssignment == null && lti_ags_lineitem_url != null) {
				debug.append("Searching by lineitem URL. ");
				myAssignment = ofy().load().type(Assignment.class).filter("lti_ags_lineitem_url",lti_ags_lineitem_url).first().now();
			}
		
			// Strategy 2: Find by resourceId (from Deep Linking)
			if (myAssignment == null) {
				String resourceId = null;
			
				// Check custom parameters first
				try {
					JsonObject custom = claims.get("https://purl.imsglobal.org/spec/lti/claim/custom").getAsJsonObject();
					JsonElement resIdElem = custom.get("resourceId");
					if (resIdElem == null) resIdElem = custom.get("resourceid"); // Schoology lowercases this
					resourceId = resIdElem.getAsString();
				} catch (Exception e) {}
			
				// Check lineitem for resourceId (other LMS platforms)
				if (resourceId == null && lti_ags_lineitem_url != null) {
					try {
						resourceId = LTIMessage.getLineItem(d, lti_ags_lineitem_url).get("resourceId").getAsString();
					} catch (Exception e) {}
				}
				
				// Load assignment by resourceId if found
				if (resourceId != null) {
					debug.append("Searching by resourceId=" + resourceId + ". ");
					try {
						myAssignment = ofy().load().type(Assignment.class).id(Long.parseLong(resourceId)).now();
						if (myAssignment != null && !myAssignment.resourceLinkId.equals(resourceLinkId)) {
							// This is a descendant copy; create new assignment entity
							myAssignment.id = ofy().factory().allocateId(Assignment.class).getId();
							myAssignment.created = new Date();
							debug.append("Found ancestor, creating descendant. ");
						}
					} catch (Exception e) {
						debug.append("ResourceId lookup failed. ");
					}	
				}
			}
		
			// Strategy 3: Find by domain + resourceLinkId (for non-DeepLinked assignments)
			if (myAssignment == null) {
				debug.append("Searching by resourceLinkId. ");
				myAssignment = ofy().load().type(Assignment.class).filter("domain",d.platform_deployment_id).filter("resourceLinkId",resourceLinkId).first().now();
			}
		
			// Strategy 4: Find by resourceLinkId history (for copied assignments)
			if (myAssignment == null) {
				String resourceLinkIdHistory = null;
				try {  // try custom parameter set during deeplinking
					resourceLinkIdHistory = claims.get("https://purl.imsglobal.org/spec/lti/claim/custom").getAsJsonObject().get("resource_link_id_history").getAsString();
				} catch (Exception e) {}
				if (resourceLinkIdHistory == null) {
					try {  // this should work for Canvas
						resourceLinkIdHistory = claims.get("https://purl.imsglobal.org/spec/lti/claim/resource_link").getAsJsonObject().get("history").getAsString();
					} catch (Exception e) {}
				}
				if (resourceLinkIdHistory != null) {
					debug.append("Searching by resourceLinkId history. ");
					String[] historyParts = resourceLinkIdHistory.split(",");
					int i = 0;
					while (myAssignment == null && i < historyParts.length) {
						myAssignment = ofy().load().type(Assignment.class).filter("domain",d.platform_deployment_id).filter("resourceLinkId", historyParts[i]).first().now();
						i++;
					}
					if (myAssignment != null) {
						// Create a new copy of the parent assignment
						myAssignment.id = ofy().factory().allocateId(Assignment.class).getId();
						myAssignment.created = new Date();
						debug.append("Found parent, creating copy. ");
					}
				}
			}
		
			// Strategy 5: Check for resourceId in request parameters
			if (myAssignment == null) {
				String resourceId = request.getParameter("resourceId");
				if (resourceId != null) {
					debug.append("Searching by resourceId parameter. ");
					try {
						myAssignment = ofy().load().type(Assignment.class).id(Long.parseLong(resourceId)).now();
						if (myAssignment != null && !myAssignment.resourceLinkId.equals(resourceLinkId)) {
							// This is a descendant copy; create new assignment entity
							myAssignment.id = ofy().factory().allocateId(Assignment.class).getId();
							myAssignment.created = new Date();
							debug.append("Found ancestor, creating descendant. ");
						}
					} catch (Exception e) {
						debug.append("ResourceId parameter lookup failed. ");
					}	
				}
			}

			// Strategy 6: Create a new assignment
			if (myAssignment == null) {
				debug.append("Creating new assignment. ");
				myAssignment = new Assignment();
				myAssignment.domain = d.platform_deployment_id;
				myAssignment.resourceLinkId = resourceLinkId;
				myAssignment.lti_nrps_context_memberships_url = lti_nrps_context_memberships_url;
				myAssignment.created = new Date();
			}
			
			// At this point we should have a valid (but possibly incomplete) Assignment entity
			
			// Update the Assignment parameters:
			try {
				Assignment original_a = myAssignment.clone(); // make a copy to compare with for updating later
				myAssignment.resourceLinkId = resourceLinkId;		
				if (lti_ags_lineitems_url != null) myAssignment.lti_ags_lineitems_url = lti_ags_lineitems_url;
				if (lti_ags_lineitem_url != null) myAssignment.lti_ags_lineitem_url = lti_ags_lineitem_url;
				else if (scope.contains("https://purl.imsglobal.org/spec/lti-ags/scope/lineitem")) myAssignment.lti_ags_lineitem_url = LTIMessage.createLineItem(d, myAssignment, lti_ags_lineitems_url);

				if (lti_nrps_context_memberships_url != null) myAssignment.lti_nrps_context_memberships_url = lti_nrps_context_memberships_url;
				
				myAssignment.valid=now;
				
				// If required, save the updated Assignment entity now so its id will be accessible
				if (myAssignment.id==null || !myAssignment.equivalentTo(original_a)) {
					ofy().save().entity(myAssignment).now();
					Group.update(d,myAssignment);
					Utilities.createTask("/DataStoreCleaner","Task=CleanAssignments&AssignmentId=" + myAssignment.id);
				}
			} catch (Exception e) {
				throw new Exception("Assignment could not be updated during LTI launch sequence. " + e.getMessage());
			}

			// Create a cross-site request forgery (CSRF) token containing the Assignment.id
			user.setAssignment(myAssignment.id);  // this sets the assignment and token and saves the user to the database
			debug.append("User credentials set OK. ");
			
			// If this is the first time this Assignment has been used, it may be missing the assignmentType and topicId(s)
			if (!myAssignment.isValid()) {  //Show the the pickResource form:
				response.getWriter().println(Subject.header("Select A ChemVantage Assignment") + pickResourceForm(user,myAssignment,request) + Subject.footer);
				return;
			} else if (!isPremiumUser) {
				String url = Subject.getServerUrl() + "/checkout?sig=" + user.getTokenSignature() + "&d=" + d.platform_deployment_id;
				if ("PlacementExam".equals(myAssignment.assignmentType)) url += "&n=1";
				else url += "&n=5";
				response.sendRedirect(url);
			} else {
				launchResourceRequest(user,myAssignment,request,response);
			}
		} catch (Exception e) {
			Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "LTI Resource Link Launch Failure", e.getMessage() + "\n" + debug.toString());
			throw new Exception("Resource Link Request Launch Failed: " + e.getMessage() + " " + debug.toString());
		}
	}
	
	void launchResourceRequest (User user,Assignment myAssignment,HttpServletRequest request,HttpServletResponse response) throws Exception {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		if (user.isInstructor()) {
			switch(myAssignment.assignmentType) {
			case "Quiz":
				out.println(Subject.header("Instructor Page") + Quiz.instructorPage(user, myAssignment) + Subject.footer);
				break;
			case "Homework":
				out.println(Subject.header("Instructor Page") + Homework.instructorPage(user, myAssignment) + Subject.footer);
				break;
			case "PracticeExam":
				out.println(Subject.header("Instructor Page") + PracticeExam.instructorPage(user, myAssignment) + Subject.footer);
				break;
			case "PlacementExam":
				out.println(Subject.header("Instructor Page") + PlacementExam.instructorPage(user, myAssignment) + Subject.footer);
				break;
			case "SmartText":
				out.println(Subject.header("Instructor Page") + SmartText.instructorPage(user, myAssignment) + Subject.footer);
				break;
			case "Poll":
				out.println(Subject.header("Instructor Page") + Poll.instructorPage(user,myAssignment) + Subject.footer);
				break;
			case "VideoQuiz":
				out.println(Subject.header("Instructor Page") + VideoQuiz.instructorPage(user,myAssignment) + Subject.footer);
				break;
			case "Sage":
				out.println(Subject.header("Instructor Page") + Sage.instructorPage(user,myAssignment) + Subject.footer);
				break;
			default: return;
			}
		} else {
			response.sendRedirect(Subject.getServerUrl() + "/" + myAssignment.assignmentType + "?sig=" + user.getTokenSignature());
		}
	}
	
	void launchSubmissionReview(HttpServletResponse response, JsonObject claims, Deployment d, User u) throws Exception {
		StringBuffer debug = new StringBuffer("Debug: ");
		//if (!u.isInstructor()) throw new Exception("Instructor role required.");
		
		try {
			JsonElement for_user = claims.get("https://purl.imsglobal.org/spec/lti/claim/for_user");
			String for_user_id = for_user==null?null:claims.get("iss").getAsString() + "/" + for_user.getAsJsonObject().get("user_id").getAsString();
			//if (!u.isInstructor()) for_user_id = u.getId();  // bulletproofing: only instructors can view others'scores
			String for_user_hashedId = for_user_id==null?null:Subject.hashId(for_user_id);
			String resourceLinkId = claims.get("https://purl.imsglobal.org/spec/lti/claim/resource_link").getAsJsonObject().get("id").getAsString();
			Assignment a = ofy().load().type(Assignment.class).filter("domain",d.platform_deployment_id).filter("resourceLinkId",resourceLinkId).first().safe();
			u.setAssignment(a.id);
				
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			switch (a.assignmentType) {
			case "Quiz":
				if (u.isInstructor()) out.println(Subject.header() + (for_user_id==null?Quiz.showSummary(u,a):Quiz.showScores(u,a,for_user_hashedId,null)) + Subject.footer);
				else out.println(Subject.header() + Quiz.showScores(u,a,null,null) + Subject.footer);
				break;
			case "Homework":
				if (u.isInstructor()) out.println(Subject.header() + (for_user_id==null?Homework.showSummary(u,a):Homework.reviewSubmissions(u,a,for_user_id,null)) + Subject.footer);
				else out.println(Subject.header() + Homework.showScores(u,a,null) + Subject.footer);
				break;
			case "PracticeExam":
				out.println(Subject.header() + (for_user_id==null?PracticeExam.reviewExamScores(u,a):PracticeExam.showExamScores(u,a,for_user_id)) + Subject.footer);
				break;
			case "PlacementExam":
				out.println(Subject.header() + (for_user_id==null?PlacementExam.reviewExamScores(u,a):PlacementExam.submissionReview(u,a,for_user_id)) + Subject.footer);
				break;
			case "Poll":
				out.println(Subject.header() + Poll.resultsPage(u,for_user_hashedId,a) + Subject.footer);
				break;
			case "SmartText":
				out.println(Subject.header() + SmartText.reviewScore(u,a,for_user_id) + Subject.footer);
				break;
			case "Video":
			default:
				out.println(Subject.header() + "<h1>Video Transactions</h1>" + "<h4>Sorry, submission review is not currently available for this type of ChemVantage assignment.</h4>" + Subject.footer);
			}
		} catch (Exception e) {
			throw new Exception("Submission Review Launch Failed: " + (e.getMessage()==null?e.toString():e.getMessage()) + "\n" + debug.toString() + "\n" + claims.toString());
		}
	}
	
	List<Key<Question>> selectHomeworkQuestions(Long conceptId, int numConceptsRemaining, int numQuestionsAlreadySelected) {
		List<Key<Question>> questionKeys = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",conceptId).keys().list();
		Collections.shuffle(questionKeys);
		int numToSelect = (int) Math.round((double)(15-numQuestionsAlreadySelected)/numConceptsRemaining);
		if (questionKeys.size() > numToSelect) return questionKeys.subList(0, numToSelect);
		else return questionKeys;
	}

	protected JsonObject validateStateToken(HttpServletRequest request) throws Exception {
		/* This method ensures that the state token required by LTI v1.3 standards is a
		 * valid token issued by the tool provider (ChemVantage) as part of the LTI
		 * launch request sequence. Otherwise throws a JWTVerificationException.
		 */
		try {
			String iss = "https://" + request.getServerName();
			Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
			JWTVerifier verifier = JWT.require(algorithm).withIssuer(iss).build();
			String state = request.getParameter("state");
			verifier.verify(state);
			String nonce = JWT.decode(state).getClaim("nonce").asString();
			if (!Nonce.isUnique(nonce)) throw new Exception("Nonce was used previously.");

			// return the state token payload as a JSON
			return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(JWT.decode(state).getPayload()))).getAsJsonObject();
		} catch (Exception e) {
			throw new Exception("State parameter was invalid: " + e.getMessage());
		}
	}

	protected Deployment validateIdToken(HttpServletRequest request) throws Exception {
		try {
			DecodedJWT id_token = JWT.decode(request.getParameter("id_token"));

			// get the platform_id and deployment_id to load the correct Deployment d
			String platform_id = id_token.getIssuer().trim();
			if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);

			String deployment_id = id_token.getClaim("https://purl.imsglobal.org/spec/lti/claim/deployment_id").asString();
			if (deployment_id == null) throw new Exception("The deployment_id claim was not found in the id_token payload.");
			String platformDeploymentId = platform_id + "/" + deployment_id;

			Deployment d = Deployment.getInstance(platformDeploymentId);

			if (d==null) {
				throw new Exception("The deployment was not found in the ChemVantage database. You "
						+ "can register your LMS with ChemVantage at https://www.chemvantage.org/lti/registration");
			}
			if (d.expires != null && d.expires.before(new Date())) d.status = "blocked";
			if ("blocked".equals(d.status)) throw new Exception("Sorry, we are unable to launch ChemVantage while this deployment is under review. "
				+ "Please contact admin@chemvantage.org for assistance. Thank you.");
			if (d.status == null) d.status = "pending";

			// validate the id_token audience:
			List<String> aud = id_token.getAudience();
			if (aud.size()==1 && aud.get(0).contentEquals(d.client_id)); // OK, continue
			else if (aud.size()>1 && aud.contains(d.client_id) && id_token.getClaim("azp").asString().contentEquals(d.client_id)); // OK, continue
			else throw new Exception("The id_token client_id claim is not authorized in ChemVantage.");

			// validate the id_token signature:
			// retrieve the public Java Web Key from the platform to verify the signature
			if (d.well_known_jwks_url==null) throw new Exception("The deployment does not have a valid JWKS URL.");
			URL jwks_url = new URI(d.well_known_jwks_url).toURL();
			String kid = id_token.getKeyId();
			
			// Header may be necessary to get the JWKS from Moodle
			Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36");
            JwkProvider provider = new UrlJwkProvider(jwks_url, null, null, null, headers);
			
            if (kid == null || kid.isEmpty()) throw new Exception("No JWK id found.");
			Jwk jwk = provider.get(kid); //throws Exception when not found or can't get one
			RSAPublicKey public_key = (RSAPublicKey)jwk.getPublicKey();
			
			// verify the JWT signature
			Algorithm algorithm = Algorithm.RSA256(public_key,null);
			if (!"RS256".contentEquals(id_token.getAlgorithm())) throw new Exception("JWT algorithm must be RS256");
			JWT.require(algorithm).build().verify(id_token);  // throws JWTVerificationException if not valid
			return d;
		} catch (Exception e) {
			throw new Exception("ID token could not be validated: " + e.getMessage());
		}
	}
	
	void verifyLtiMessageClaims(JsonObject claims) throws Exception {
		try {
			// verify LTI version 1.3.0
			JsonElement lti_version = claims.get("https://purl.imsglobal.org/spec/lti/claim/version");
			if (lti_version == null) throw new Exception("LTI version claim was missing.");    
			if (!"1.3.0".equals(lti_version.getAsString())) throw new Exception("Incorrect LTI version claim");

			// Validate the LTI message_type:
			JsonElement message_type = claims.get("https://purl.imsglobal.org/spec/lti/claim/message_type");
			if (message_type == null) throw new Exception("Missing LTI message_type.");
			switch (message_type.getAsString()) {
			case "LtiResourceLinkRequest": break;
			case "LtiSubmissionReviewRequest": break;
			case "LtiDeepLinkingRequest": throw new Exception("Invalid launch. DeepLinking requests must use the target_link_uri /lti/deeplinks");
			default: throw new Exception("The LTI message_type claim " + message_type.getAsString() + " is not suppported.");
			}

			// Process the ResourceLink claim information:
			JsonElement resource_link_claims = claims.get("https://purl.imsglobal.org/spec/lti/claim/resource_link");
			if (resource_link_claims == null) throw new Exception("Resource link claims were missing from the id_token.");
			if (resource_link_claims.getAsJsonObject().get("id").getAsString()==null) throw new Exception("Resource link ID value missing from id_token claims. ");

			return;
		} catch (Exception e) {
			throw new Exception("LTI Message Claims could not be validated: " + e.getMessage());
		}
	}
	
	User getUserClaims(JsonObject claims) throws Exception {
		// Process User information:
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			String userId = claims.get("sub")==null?"":claims.get("sub").getAsString();  // allows for anonymous user ""
			String platform_id = claims.get("iss").getAsString();
			User user = new User(platform_id, userId);

			JsonElement roles_claim = claims.get("https://purl.imsglobal.org/spec/lti/claim/roles");
			if (roles_claim == null || !roles_claim.isJsonArray()) throw new Exception("Required roles claim is missing from the id_token");
			JsonArray roles = roles_claim.getAsJsonArray();
			Iterator<JsonElement> roles_iterator = roles.iterator();
			boolean isLearner = false;
			boolean isMentor = false;
			while(roles_iterator.hasNext()){
				String role = roles_iterator.next().getAsString();
				user.setIsTeachingAssistant(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership/Instructor#TeachingAssistant"));
				user.setIsInstructor(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor"));
				user.setIsInstructor(role.contains("Instructor")); // bulletproofing for non-standard role values that contain "Instructor"
				user.setIsAdministrator(role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Administrator"));
				user.setIsAdministrator(role.equals("http://purl.imsglobal.org/vocab/lis/v2/institution/person#Administrator"));
				user.setIsAdministrator(role.equals("http://purl.imsglobal.org/vocab/lis/v2/system/person#Administrator"));
				if (role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner")) isLearner = true;
				if (role.contains("Learner")) isLearner = true;  // bulletproofing for non-standard role values that contain "Learner"
				if (role.equals("http://purl.imsglobal.org/vocab/lis/v2/membership#Mentor")) isMentor = true;
			}
			if (!user.isInstructor() && (!isLearner || isMentor)) {  // unusual login; send a message to ChemVantage administrator
				Utilities.sendEmail("ChemVantage Admin","admin@chemvantage.org","Unusual Login User Roles",claims.getAsString());
			}
			if (user.isInstructor() && ofy().load().type(Instructor.class).filter("hashedId",user.hashedId).count() == 0) {
				// inform ChemVantage administrator of instructor contact information
				StringBuffer message = new StringBuffer("<h2>New Instructor:</h2>");
				try {
					new Instructor(platform_id,userId,user.roles);
					String givenName = claims.get("given_name")!= null?claims.get("given_name").getAsString():"";
					String familyName = claims.get("family_name")!= null?claims.get("family_name").getAsString():"";
					String email = claims.get("email")!= null?claims.get("email").getAsString():"";
					if (!familyName.isEmpty() && !email.isEmpty() && ofy().load().type(Contact.class).id(email).now()==null) { // add a Contact
						Contact contact = new Contact(givenName,familyName,email);
						contact.role = "faculty";
						contact.vetted = true;
						ofy().save().entity(contact);
					}
					message.append("Name: "  + givenName + " " + familyName + "<br/>");
					message.append("Email: " + email + "<br/>");
					JsonObject context = claims.get("https://purl.imsglobal.org/spec/lti/claim/context").getAsJsonObject();
					message.append("Context: " + context.get("label").getAsString() + " - " + context.get("title").getAsString() + "<br/>");
					JsonObject platform = claims.get("https://purl.imsglobal.org/spec/lti/claim/tool_platform").getAsJsonObject();
					message.append("Institution: " + platform.get("name").getAsString() + "<br/><br/>");
				} catch (Exception e) {
				}
				message.append(claims.toString());
				Utilities.sendEmail("ChemVantage Admin","admin@chemvantage.org","New Instructor Login",message.toString());
			}

			return user;
		} catch (Exception e) {
			throw new Exception("User claims could not be validated: " + e.getMessage() + "<br/>" + debug.toString());
		}
	}
	
	Assignment updateAssignment(HttpServletRequest request, User user) throws Exception {	
		Assignment a = null;
		try {
			long assignmentId = user.getAssignmentId();
			if (assignmentId == 0L) throw new Exception("Assignment ID was 0L.");

			a = ofy().load().type(Assignment.class).id(assignmentId).safe();
			a.assignmentType = request.getParameter("AssignmentType");
			Text text = null;
			switch (a.assignmentType) {
			case "SmartText":
			case "Quiz":
			case "Sage":
			case "Homework":
				a.textId = Long.parseLong(request.getParameter("TextId"));
				a.chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
				text = ofy().load().type(Text.class).id(a.textId).safe();
				Chapter ch = null;
				for (Chapter c : text.chapters) if (c.chapterNumber == a.chapterNumber) ch = c;
				if (ch != null) {
					a.title = ch.title;
					if ("Sage".equals(a.assignmentType)) break;  // don't store questionKeys for this assignment
					a.questionKeys.clear();
					for (Long conceptId : ch.conceptIds) {
						a.conceptIds.add(conceptId);
						if (a.assignmentType.equals("Homework")) {
							int numConceptsRemaining = ch.conceptIds.size() - a.conceptIds.size() +1;
							a.questionKeys.addAll(selectHomeworkQuestions(conceptId,numConceptsRemaining,a.questionKeys.size()));
						} else {  // for Quiz or SmartText assignments:
							a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType",a.assignmentType).filter("conceptId",conceptId).keys().list());
						}
					}
					if (a.assignmentType.equals("Homework")) a.validateQuestionItemsWithAI();
				}
				break;
			case "PracticeExam":
				a.textId = Long.parseLong(request.getParameter("TextId"));
				a.chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
				text = ofy().load().type(Text.class).id(a.textId).safe();
				String[] chapterNoArray = request.getParameterValues("ChapterNumber");
				List<Integer> chapterNumbers = new ArrayList<Integer>();
				for (int i=0;i<chapterNoArray.length;i++) chapterNumbers.add(Integer.parseInt(chapterNoArray[i]));
				a.title = "General Chemistry Exam";
				a.questionKeys.clear();
				for (Chapter chp : text.chapters) {
					if (chapterNumbers.contains(chp.chapterNumber)) {
						for (Long conceptId : chp.conceptIds) {
							if (!a.conceptIds.contains(conceptId)) {
								a.conceptIds.add(conceptId);
								a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",conceptId).keys().list());
							}
						}
					}
				}
				break;
			case "PlacementExam":
				List<Concept> concepts = ofy().load().type(Concept.class).list();
				a.questionKeys = new ArrayList<Key<Question>>();
				a.title = "General Chemistry Placement Exam";
				for (Concept c : concepts) {
					switch (c.title) {
					case "Essential Chemistry":
					case "Essential Math":
					case "Word Problems":
						a.conceptIds.add(c.id);
						a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",c.id).keys().list());
						break;
					default: continue;
					}
				}
				break;
			case "VideoQuiz":
				a.assignmentType = "VideoQuiz";
				String videoId = request.getParameter("VideoId");
				if (videoId != null) a.videoId = Long.parseLong(videoId);
				break;
			case "Poll":
				a.pollIsClosed = true;
				break;
			}	
		} catch (Exception e) {
		}
		return a;
	}
	
	String pickResourceForm(User user,Assignment myAssignment,HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.banner);

		buf.append("<h1>Assignment Setup Page</h1>");

		if (!user.isInstructor()) {
			buf.append("The link that you just activated in your learning management system (LMS) is not yet associated with a ChemVantage assignment. "
					+ "<b>Please ask your instructor to click the same link in your LMS to complete the setup.</b> "
					+ "You will not be able to complete this assignment until after this has been done.");
			return buf.toString();
		}

		buf.append("<form name=AssignmentForm action=/lti/launch method=POST>");
		buf.append("<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
		buf.append("<input type=hidden name=UserRequest value='UpdateAssignment' />");
		buf.append("<input type=hidden name=Refresh id=refresh value=true />");

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

		// this method only accepts single assignments (different from DeepLinking)
		boolean acceptsMultiple = false;
		
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
				buf.append("<div style='color:red'>Select " + (acceptsMultiple?"at least ":"") + "one of the chapters below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text!=null?text.chapters.size()/2:0;
				if (text != null) {
					for (Chapter ch : text.chapters) {
					if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
					i++;
					buf.append("<div><label><input type=" + (acceptsMultiple?"checkbox":"radio") + " name=ChapterNumber onClick=countChecks('SmartText'); "
						+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
				}
				buf.append("</div></div></div>");
			}
			buf.append("<input id=stsub type=submit disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select" + (acceptsMultiple?" at least":"") + " one chapter' />");
			} catch (Exception e) {
				buf.append("<div style='color:red'>Please select one of the available ChemVantage smart textbooks below:</div>");
				if (texts != null) {
					for (Text txt : texts) buf.append("<div><label><input type=radio name=TextId value=" + txt.id + " onclick=this.form.submit(); />" + txt.title + "</label></div>");
				}
			}		
			break;
		case "Quiz":
		case "Sage":
		case "Homework":
			try {
				textId = Long.parseLong(request.getParameter("TextId"));
				texts = ofy().load().type(Text.class).list();
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
				}
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />" + allTopics.title + "</label></div><br/>");
				
				buf.append("<div style='color:red'>Select " + (acceptsMultiple?"at least ":"") + "one of the topics below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text!=null?text.chapters.size()/2:0;
				if (text != null) {
					for (Chapter ch : text.chapters) {
						if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
						i++;
						buf.append("<div><label><input type=" + (acceptsMultiple?"checkbox":"radio") + " name=ChapterNumber onClick=countChecks('" + assignmentType + "'); "
							+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
					}
				}
				buf.append("</div></div></div>");
				buf.append("<input type=submit id=stsub disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select" + (acceptsMultiple?" at least":"") + " one topic' />");
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
					buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />" + txt.title + "</label></div>");
				}
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />" + allTopics.title + "</label></div><br/>");
				}
			}	
			break;
		case "PracticeExam":
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
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />" + allTopics.title + "</label></div><br/>");
				
				buf.append("<div style='color:red'>Select at least three of the topics below for this assignment.</div>");
				buf.append("<div style=display:table;width:100%><div style=display:table-row><div style=display:table-cell>");
				oneHalf = text!=null?text.chapters.size()/2:0;
				if (text != null) {
					for (Chapter ch : text.chapters) {
						if (i==oneHalf) buf.append("</div><div style=display:table-cell>");
						i++;
						buf.append("<div><label><input type=checkbox name=ChapterNumber onClick=countChecks('" + assignmentType + "'); "
							+ "value=" + ch.chapterNumber + " />&nbsp;" + ch.chapterNumber + ". " + ch.title + "</label></div>");
					}
				}
				buf.append("</div></div></div>");
				buf.append("<input type=submit id=pesub disabled=true onClick=\"document.getElementById('refresh').value=false\" value='Select at least three topics' />");
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
						buf.append("<div><label><input type=radio name=TextId value=" + txt.id + (textId==txt.id?" checked ":" ") + "onclick=this.form.submit(); />" + txt.title + "</label></div>");
					}
				}
				if (allTopics != null) buf.append("<div><label><input type=radio name=TextId value=" + allTopics.id + (textId==allTopics.id?" checked ":" ") + "onclick=this.form.submit(); />" + allTopics.title + "</label></div><br/>");
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

		return buf.toString();
	}

	String registrationForm(User user, HttpServletRequest request,String platform_deployment_id) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("ChemVantage Registration") + "<h1>ChemVantage Registration</h1>");
		try {
			Deployment d = ofy().load().type(Deployment.class).id(platform_deployment_id).now();
			
			if (registrationCompleted(request,d)) {
				buf.append("<h2>Thank you</h2>"
						+ "Registration is now complete. Please return to your LMS and relaunch the assignment to continue.<br/><br/>");
			} else {
				buf.append("<h2>ChemVantage Registration</h2>"
						+ "Please provide the information below and accept the Terms of Service to complete the registration.<p></p>"
						+ "<form method=post><input type=hidden name=UserRequest value=Register />"
						+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
						+ "<input type=hidden name=platform_deployment_id value='" + d.platform_deployment_id + "' />");
				buf.append("Please tell us how to contact you if there is ever a problem with your account:<br/>"
						+ "<label>Your Name: " + (d.contact_name==null?"<input type=text name=contact_name size=40 required />":d.contact_name) + "</label><br/>"
						+ "<label>Your Email: " + (d.email==null?"<input type=text name=contact_email size=40 required />":d.email) + "</label><br/><br/>"
						+ "Please tell us about your school, business or organization:<br/>"
						+ "<label>Org Name: " + (d.organization==null?"<input type=text name=org_name size=40 required />":d.organization) + "</label><br/>"
						+ "<label>Home Page: " + (d.org_url==null?"<input type=text name=org_url placeholder='https://myschool.edu' size=40 required />":d.org_url) + "</label><br/><br/>");
				buf.append("Pricing:"
						+ "<ul>"
						+ "<li>LTI registration and instructor accounts are free.</li>"
						+ "<li>Each student license costs $2.00 USD per month or $8.00 USD per semester.</li>"
						+ "<li>Institutions may purchase student licenses in bulk for as little as $2.00 USD per year.</li>"
						+ "</ul>"
						+ "If you have questions or need assistance, please email admin@chemvantage.org<br/><br/>"
						+ "<label><input type=checkbox name=AcceptChemVantageTOS value=true required />Accept the <a href=/terms_and_conditions.html target=_blank aria-label='opens new tab'>ChemVantage Terms of Service</a></label><br/><br/>"
						+ "<input type=submit value='Complete Registration'/><br/><br/>");
				buf.append("</form>");
			}
			buf.append(Subject.footer);
		} catch (Exception e) {
			
		}
		return buf.toString();
	}

	
	boolean registrationCompleted(HttpServletRequest request, Deployment d) {
		try {
			String contact_name = request.getParameter("contact_name");
			String email = request.getParameter("contact_email");
			String organization = request.getParameter("org_name");
			String org_url = request.getParameter("org_url");
			if (d.contact_name==null && !contact_name.trim().isEmpty()) d.contact_name = contact_name;
			if (d.email==null && !email.trim().isEmpty()) d.email = email;
			if (d.organization==null && !organization.trim().isEmpty()) d.organization = organization;
			if (d.org_url==null && !org_url.trim().isEmpty()) d.org_url = org_url;
			boolean terms = Boolean.parseBoolean(request.getParameter("AcceptChemVantageTOS"));
			boolean incomplete = d.contact_name==null || d.email==null || d.organization==null || d.org_url==null || !terms;
			d.status = incomplete?"auto":"pending";
			ofy().save().entity(d).now();
			return !incomplete;
		} catch (Exception e) {
			return false;
		}
	}
	
/*	
	private void sendEmailToAdmin(String message) {
		Properties props = new Properties();
		Session session = Session.getDefaultInstance(props, null);

		try {
			Message msg = new MimeMessage(session);
			msg.setFrom(new InternetAddress("admin@chemvantage.org", "ChemVantage"));
			msg.addRecipient(Message.RecipientType.TO,
					new InternetAddress("admin@chemvantage.org", "ChemVantage"));
			msg.setSubject("ResourceLinkRequest Error");
			msg.setContent(message,"text/html");
			Transport.send(msg);
		} catch (Exception e) {
		}
	}
*/
}	
