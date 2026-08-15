package org.chemvantage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

public class Utilities {
	
	public static void createTask(String relativeUri, String query) throws IOException {
		createTask(relativeUri, query, 0);
	}
	
	public static void createTask(String relativeUri, String query, int seconds)
			throws IOException {
		// This method accepts a relativeUri (e.g., /ReportScore) to POST a request to a ChemVantage servlet
		String projectId = Subject.getProjectId();
		String location = "us-central1";
		String queueName = "default";
		String baseUrl = Subject.getServerUrl();
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

	private static String getTaskOidcServiceAccount() {
		String configured = System.getenv("CLOUD_TASKS_OIDC_SERVICE_ACCOUNT");
		if (configured == null || configured.isBlank()) {
			configured = System.getenv("CV_TASKS_OIDC_SERVICE_ACCOUNT");
		}
		return configured;
	}

	private static String buildTaskUrl(String baseUrl, String relativeUri) {
		String trimmedBase = baseUrl == null ? "" : baseUrl.trim();
		String trimmedPath = relativeUri == null ? "" : relativeUri.trim();
		if (trimmedPath.isEmpty()) return trimmedBase;
		if (!trimmedPath.startsWith("/")) trimmedPath = "/" + trimmedPath;
		if (trimmedBase.endsWith("/")) trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
		return trimmedBase + trimmedPath;
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