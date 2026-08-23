package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.googlecode.objectify.Key;
import com.google.gson.JsonObject;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

public class Utilities {
	private static final String ADMIN_EMAIL = "admin@chemvantage.org";
	
	public static void createTask(String relativeUri, String query) throws IOException {
		createTask(relativeUri, query, 0);
	}
	
	public static void createTask(String relativeUri, String query, int seconds)
			throws IOException {
		// This method accepts a relativeUri (e.g., /ReportScore) to POST a request to a ChemVantage servlet
		String projectId = Subject.getProjectId();
		String location = "us-central1";
		String queueName = "default";
		String baseUrl = getTaskBaseUrl();
		String taskUrl = buildTaskUrl(baseUrl, relativeUri);
		String oidcServiceAccount = getTaskOidcServiceAccount();
		// Instantiates a client.
		try (CloudTasksClient client = CloudTasksClient.create()) {
			// Construct the fully qualified queue name.
			String queuePath = QueueName.of(projectId, location, queueName).toString();

			// Build the Task for Cloud Run / HTTPS load balancer target.
			HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
					.setUrl(taskUrl)
					.setBody(ByteString.copyFrom(query, Charset.defaultCharset()))
					.setHttpMethod(HttpMethod.POST)
					.putHeaders("Content-Type", "application/x-www-form-urlencoded");

			if (oidcServiceAccount != null && !oidcServiceAccount.isBlank()) {
				requestBuilder.setOidcToken(
						OidcToken.newBuilder()
								.setServiceAccountEmail(oidcServiceAccount)
								.setAudience(baseUrl)
								.build());
			}

			Task.Builder taskBuilder = Task.newBuilder().setHttpRequest(requestBuilder.build());

			// Add the scheduled time to the request.
			taskBuilder.setScheduleTime(
					Timestamp.newBuilder()
					.setSeconds(Instant.now(Clock.systemUTC()).plusSeconds(seconds).getEpochSecond()));

			// Send create task request.
			client.createTask(queuePath, taskBuilder.build());  // returns Task entity
			//System.out.println("Task created: " + task.getName());
		}
	}

	public static boolean synchronizeScores(org.chemvantage.User user, Assignment assignment) throws Exception {
		if (user == null || !user.isInstructor() || assignment == null) throw new Exception("Instructor access required to synchronize scores.");
		Deployment deployment = ofy().load().type(Deployment.class).id(assignment.domain).safe();
		if (assignment.lti_ags_lineitem_url == null
				&& assignment.lti_ags_lineitems_url != null
				&& assignment.resourceLinkId != null) {
			JsonObject lineItem = LTIMessage.getLineItem(deployment, assignment.resourceLinkId,
					assignment.lti_ags_lineitems_url);
			if (lineItem != null && lineItem.has("id")) {
				assignment.lti_ags_lineitem_url = lineItem.get("id").getAsString();
				ofy().save().entity(assignment).now();
			}
		}
		if (assignment.lti_ags_lineitem_url == null) throw new Exception("Assignment does not have a valid LTI Advantage line item URL.");

		Map<String, String> lmsScores = LTIMessage.readMembershipScores(assignment);
		if (lmsScores == null || lmsScores.containsKey("Error")) throw new Exception("Failed to read scores from LMS: " + (lmsScores != null ? lmsScores.get("Error") : "Unknown error"));
		Map<String, String[]> membership = assignment.lti_nrps_context_memberships_url == null
				? new HashMap<>() : LTIMessage.getMembership(assignment);
		if (membership == null) membership = new HashMap<>();
		if (membership.isEmpty()) {
			for (String userId : lmsScores.keySet()) {
				if (!"Error".equals(userId)) membership.put(userId, new String[] {"Learner"});
			}
		}
		if (membership.isEmpty()) return true;

		String platformId = deployment.getPlatformId() + "/";
		for (Map.Entry<String, String[]> entry : membership.entrySet()) {
			if (entry.getValue() == null || entry.getValue().length == 0
					|| !"Learner".equals(entry.getValue()[0])) continue;
			try {
				String userId = platformId + entry.getKey();
				Key<Score> scoreKey = key(key(org.chemvantage.User.class,
						Subject.hashId(userId)), Score.class, assignment.id);
				Score cvScore = ofy().load().key(scoreKey).now();
				if (cvScore == null) {
					cvScore = Score.getInstance(userId, assignment);
					if (!"-".equals(cvScore.getScore())) ofy().save().entity(cvScore).now();
				}
				if (String.valueOf(cvScore.getPctScore()).equals(lmsScores.get(entry.getKey()))) continue;
				String payload = "AssignmentId=" + assignment.id + "&UserId="
						+ URLEncoder.encode(userId, "UTF-8");
				createTask("/ReportScore", payload);
			} catch (Exception e) {
			}
		}
		return true;
	}

	private static String getTaskOidcServiceAccount() {
		String configured = System.getenv("CLOUD_TASKS_OIDC_SERVICE_ACCOUNT");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("CV_TASKS_OIDC_SERVICE_ACCOUNT");
		}
		return configured;
	}

	private static String getTaskBaseUrl() {
		String configured = System.getenv("CLOUD_TASKS_TARGET_BASE_URL");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("CV_TASKS_TARGET_BASE_URL");
		}
		return configured == null || configured.isBlank() ? Subject.getServerUrl() : configured;
	}

	private static String buildTaskUrl(String baseUrl, String relativeUri) {
		String trimmedBase = baseUrl == null ? "" : baseUrl.trim();
		String trimmedPath = relativeUri == null ? "" : relativeUri.trim();
		if (trimmedPath.isEmpty()) return trimmedBase;
		if (!trimmedPath.startsWith("/")) trimmedPath = "/" + trimmedPath;
		if (trimmedBase.endsWith("/")) trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
		return trimmedBase + trimmedPath;
	}

	public static boolean isAdminAuthenticated(jakarta.servlet.http.HttpServletRequest request) {
		if (request == null) return false;
		String serverName = request.getServerName();
		if ("localhost".equals(serverName) || "127.0.0.1".equals(serverName)) return true;
		String authenticatedEmail = getAuthenticatedEmail(request);
		if (authenticatedEmail != null && ADMIN_EMAIL.equalsIgnoreCase(authenticatedEmail)) return true;
		try {
			UserService userService = UserServiceFactory.getUserService();
			if (!userService.isUserLoggedIn()) return false;
			User currentUser = userService.getCurrentUser();
			return currentUser != null && ADMIN_EMAIL.equalsIgnoreCase(currentUser.getEmail());
		} catch (Exception e) {
			return false;
		}
	}

	public static boolean isAuthenticatedEmail(jakarta.servlet.http.HttpServletRequest request, String email) {
		if (request == null || email == null || email.isBlank()) return false;
		String authenticatedEmail = getAuthenticatedEmail(request);
		if (authenticatedEmail != null && email.equalsIgnoreCase(authenticatedEmail)) return true;
		return isBearerTokenEmail(request, email);
	}

	private static boolean isBearerTokenEmail(jakarta.servlet.http.HttpServletRequest request, String email) {
		try {
			String authorization = request.getHeader("Authorization");
			if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
			String token = authorization.substring(7).trim();
			if (token.isEmpty()) return false;
			String audience = "https://" + request.getServerName();
			JsonWebSignature verifiedToken = TokenVerifier.newBuilder()
					.setAudience(audience)
					.build()
					.verify(token);
			Object tokenEmail = verifiedToken.getPayload().get("email");
			return tokenEmail != null && email.equalsIgnoreCase(tokenEmail.toString());
		} catch (Exception e) {
			return false;
		}
	}

	private static String getAuthenticatedEmail(jakarta.servlet.http.HttpServletRequest request) {
		String[] headerNames = {
			"X-Goog-Authenticated-User-Email",
			"X-Forwarded-Email",
			"X-Authenticated-User-Email"
		};
		for (String headerName : headerNames) {
			String headerValue = request.getHeader(headerName);
			if (headerValue == null || headerValue.isBlank()) continue;
			headerValue = headerValue.trim();
			int colonIndex = headerValue.indexOf(':');
			if (colonIndex >= 0 && colonIndex < headerValue.length() - 1) {
				headerValue = headerValue.substring(colonIndex + 1).trim();
			}
			if (!headerValue.isEmpty()) return headerValue;
		}
		String remoteUser = request.getRemoteUser();
		if (remoteUser != null && !remoteUser.isBlank()) return remoteUser.trim();
		return null;
	}
	
	public static void sendEmail(String recipientName, String recipientEmail, String subject, String message) 
			throws IOException {
		Email from = new Email("admin@chemvantage.org","ChemVantage LLC");
		if (recipientName==null) recipientName="";
		Email to = new Email(recipientEmail,recipientName);
		Content content = new Content("text/html", message);
		Mail mail = new Mail(from, subject, to, content);
			
		SendGrid sg = new SendGrid(Subject.getSendGridKey());
		Request request = new Request();
		request.setMethod(Method.POST);
		request.setEndpoint("mail/send");
		request.setBody(mail.build());
		Response response = sg.api(request);
		System.out.println(response.getStatusCode());
		System.out.println(response.getBody());
		System.out.println(response.getHeaders());
		
		// For all outgoing email except marketing, send a copy to admin@chemvantage.org
		if (!"admin@chemvantage.org".equals(recipientEmail) && !message.contains("unsubscribe")) {
			sendEmail("ChemVantage LLC","admin@chemvantage.org",subject,message);
		}
	}
}