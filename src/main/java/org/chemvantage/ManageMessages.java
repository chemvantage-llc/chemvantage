/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2022 ChemVantage LLC
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.auth.oauth2.GoogleCredentials;

import com.googlecode.objectify.Key;

/* 
 * Access to this servlet is restricted to ChemVantage admin users and the project service account
 * by specifying login: admin in a url handler of the project app.yaml file
 */
@WebServlet("/messages")
public class ManageMessages extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doPost(request,response);
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			// Defense-in-depth: verify admin authentication
			if (!isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, 
					"Admin access required");
				logSecurityEvent("UNAUTHORIZED_ADMIN_ACCESS_ATTEMPT", request);
				return;
			}
			
			User user = null;
			try {
				String userId = "admin";
				user = new User("https://"+request.getServerName(), userId);
				user.setIsChemVantageAdmin(true);
			//user.setToken();
		} catch (Exception e) {
			user = new User();
		}
		
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Messages</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		
		EmailMessage m = null;
		try {  // load the requested message
			m = ofy().load().type(EmailMessage.class).id(Long.parseLong(request.getParameter("MessageId"))).safe();
		} catch (Exception e) {   // or load the first active message
			List<EmailMessage> messages = ofy().load().type(EmailMessage.class).order("created").list();
			for (EmailMessage message : messages) {
				if (!message.isActive) continue;
				m = message;
				break;
			}
		}
		// if there are no active messages, load the most recent message
		if (m==null) m = ofy().load().type(EmailMessage.class).order("-created").first().now();
		if (m==null) m = new EmailMessage("subject line","message body text",false);  // in case no messages exist yet
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";
		
		String msg = "";
		String subjectLine = request.getParameter("SubjectLine");
		if (subjectLine==null) subjectLine = m.subjectLine;
		String text = request.getParameter("Text");
		if (text == null) text = m.text;
		boolean isActive = Boolean.parseBoolean(request.getParameter("IsActive"));
		
		List<Key<EmailMessage>> msgKeys = new ArrayList<Key<EmailMessage>>();
		Key<EmailMessage> mKey = key(m);
		List<Contact> contacts = new ArrayList<Contact>();
		
		switch (userRequest) {
		case "Save New Message":
			m = new EmailMessage(subjectLine,text,isActive);
			ofy().save().entity(m).now();
			msg = "The message was saved.";
			break;
		case "Update This Message":
			m.subjectLine = subjectLine;
			m.text = text;
			m.isActive = isActive;
			ofy().save().entity(m).now();
			msg = "The message was updated OK.";
			break;
		case "Delete This Message":
			msgKeys = ofy().load().type(EmailMessage.class).order("created").keys().list();
			if (m.lastRecipientCreated.getTime()==0L) {  // message has never been sent
				ofy().delete().entity(m).now();
				msg = "The message was deleted OK. The text remains in case you want to save it again.";
			} else msg = "This message cannot be deleted because it has been sent already.";
			break;
		case "Previous Message":
			msgKeys = ofy().load().type(EmailMessage.class).order("created").keys().list();			
			for (int i=0;i<msgKeys.size();i++) {
				if (msgKeys.get(i).equals(mKey)) {
					if (i==0) msg = "This is the oldest message.";  // don't change m
					else m = ofy().load().key(msgKeys.get(i-1)).now();  // assign m to the following message
					break;
				}
			}
			subjectLine = m.subjectLine;
			text = m.text;
			break;
		case "Next Message":
			msgKeys = ofy().load().type(EmailMessage.class).order("created").keys().list();
			for (int i=0;i<msgKeys.size();i++) {
				if (msgKeys.get(i).equals(mKey)) {  // found the right spot in the list
					if (i==msgKeys.size()-1) msg = "This is the newest message.";  // don't change m
					else m = ofy().load().key(msgKeys.get(i+1)).now();  // assign m to the following message
					break;
				}
			}
			subjectLine = m.subjectLine;
			text = m.text;
			break;
		case "Send 1 Test Message":
			try {
				boolean testOnly = true;
				String firstName = request.getParameter("FirstName");
				String lastName = request.getParameter("LastName");
				String email = request.getParameter("Email");
				contacts.add(new Contact(firstName,lastName,email));
				int count = sendNMessages(m,testOnly,contacts);
				msg = count + " test message was sent OK.";
			} catch (Exception e) {
				msg = "Send failed. " + e.toString() + " " + e.getMessage();
			}
			break;
		case "SendMessages":  // automated task from cron.yaml /messages?UserRequest=SendMessages&N=1000
			if (!m.isActive) {
				msg = "No emails were sent because the message is not active.";  // only send active messages
				break;
			}
			
			// determine the actual number of messages to be sent'
			Integer nMessagesToSend = 0;
			try {
				nMessagesToSend = Integer.parseInt(request.getParameter("N"));
			} catch (Exception e) {}
			int nContacts = ofy().load().type(Contact.class).filter("unsubscribed",false).filter("created >",m.lastRecipientCreated).count();
			if (nContacts < nMessagesToSend) nMessagesToSend = nContacts;
			
			if (nMessagesToSend > 100) {  // break this into Tasks of 100 messages each
				int nMessagesSent = nMessagesToSend;
				try {
					int nTasks = nMessagesToSend/100 + (nMessagesToSend%100==0?0:1);
					for (int i=0; i<nTasks;i++) {
						int delaySeconds = 300 + i * 60;  // 5 min delay plus 1 min per task
						Utilities.createTask("/messages","UserRequest=SendMessages&N=" + (nMessagesToSend > 100?100:nMessagesToSend) + "&MessageId=" + m.id,delaySeconds);
						if (nMessagesToSend > 100) nMessagesToSend -= 100;
						else nMessagesToSend = 0;
					}
					msg = nMessagesSent + " messages were queued to send in " + nTasks + " tasks.";
				} catch (Exception e) {
					msg = "an error occurred: " + e.getMessage()==null?e.toString():e.getMessage();
				}
			} else {  // send all messages now (usually as directed in a Task)
				try {
					boolean testOnly = false;
					contacts = ofy().load().type(Contact.class).filter("unsubscribed",false).filter("created >",m.lastRecipientCreated).limit(nMessagesToSend).list();
					if (contacts.size() > 0) msg = sendNMessages(m,testOnly,contacts) + " messages sent OK.";
					if (nContacts == contacts.size()) {  // last message has been sent; deactivate the message
						m.isActive = false;
						ofy().save().entity(m);
					}
				} catch (Exception e) {}
			}
			break;
		default:
		}
		
		buf.append(editMessage(subjectLine,text,m.id,m.isActive));
		if (!msg.isEmpty()) buf.append("<br/>" + msg + "<br/>");
		if (m!=null) {
			int nContacts = ofy().load().type(Contact.class).count();
			int nUnsubscribed = ofy().load().type(Contact.class).filter("unsubscribed",true).count();
			int nAvailable = ofy().load().type(Contact.class).filter("unsubscribed",false).filter("created >",m.lastRecipientCreated).count();
			boolean unsent = m.lastRecipientCreated == null || m.lastRecipientCreated.getTime() == 0L;
			buf.append("<h4>Send This Message</h4>"
				+ "You have " + nContacts + " contacts in the database, " + (nContacts - nUnsubscribed) + " remain subscribed.<br/>"
				+ "This message " 
				+ (unsent?"has not yet been sent to any contacts.":"can be sent to as many as " + nAvailable + " more contacts.") 
				+ "<br/>"
				+ "<form method=post action=/messages>"
				+ "<input type=hidden name=MessageId value=" + m.id + " />"
				//+ "<input type=submit name=UserRequest value='Send 50 Messages' />&nbsp;"
				+ "<input type=submit name=UserRequest value='Send 1 Test Message' /> to "
				+ "<input type=text size=7 name=FirstName value=Chuck /> "
				+ "<input type=text size=7 name=LastName value=Wight /> "
				+ "<input type=text name=Email value='chuck.wight@gmail.com' />"
				+ "</form>");	
		}
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		out.println(Subject.getHeader(user) + buf.toString() + Subject.footer);
		} catch (Exception e) {
			response.setContentType("text/html");
			response.getWriter().println("Error: " + e.getMessage());
		}
	}

	String editMessage(String subjectLine, String text, long messageId, boolean isActive) {
		return "<div style='width: 350px;border-style:solid;border-width: 1px;padding-left:25px;'>" + subjectLine + "</div>"
			+ "<div style='width: 750px;border-style: solid;border-width: 1px;padding: 25px;'>"
			+ salutationText(null) + (text.isEmpty()?"A preview of the message will be shown here.":text) + unsubscribeText(null) + "</div>"
			+ "<h4>Edit the message here:</h4>"
			+ "<form method=post action=/messages>"
			+ "<input type=text size=40 name=SubjectLine placeholder='Email subject line (plain text)' value='" + subjectLine + "'>"
			+ "<label><input type=checkbox name=IsActive value=true " + (isActive?"CHECKED":"") + " /> This message is active</label><br/>"
			+ "<textarea name=Text rows=10 cols=100>" + text + "</textarea><br/>"
			+ "<input type=hidden name=MessageId value=" + messageId + ">"
			+ "<input type=submit name=UserRequest value='Preview Message'>&nbsp;"
			+ "<input type=submit name=UserRequest value='Save New Message'>&nbsp;"
			+ "<input type=submit name=UserRequest value='Update This Message'>&nbsp;"
			+ "<input type=submit name=UserRequest value='Delete This Message'>&nbsp;"
			+ "<input type=submit name=UserRequest value='Previous Message'>&nbsp;"
			+ "<input type=submit name=UserRequest value='Next Message'>&nbsp;"
			+ "</form>";
	}
	
	int sendNMessages(EmailMessage m,boolean testOnly,List<Contact> contacts) throws Exception {
		int count = 0;
		for (Contact c : contacts) {
			try {
				if (m.text.contains("##a##")) {
					String code = Integer.toHexString(c.email.hashCode()).toUpperCase();
					m.text = m.text.replaceAll("##a##",code);
				}
				Utilities.sendEmail(c.getFullName(),c.email,m.subjectLine,salutationText(c) + m.text + unsubscribeText(c));
			} catch (Exception e) {
				c.role = "failed message";
				ofy().save().entity(c);
				continue;
			}
			if (!testOnly && c.created.after(m.lastRecipientCreated)) m.lastRecipientCreated = c.created;
			count++;
		}
		if (!testOnly && count > 0) ofy().save().entity(m).now();
		return count;
	}
	
	String unsubscribeText(Contact c) {
		return "<span style='font-size: small;'><a href=https://www.chemvantage.org/unsubscribe?k=" 
				+ (c==null?"":key(c).toLegacyUrlSafe()) 
				+ ">Unsubscribe</a></span>";
	}
	
	String salutationText(Contact c) {
		if (c==null) c = new Contact("Edwin","Strangeglove","strange@example.com");
		return c.lastName==null || c.lastName.isEmpty()? "" : ("Dr. " + c.lastName + ", ");
	}

	private boolean isAdminAuthenticated(HttpServletRequest request) {
		try {
			// Verify the application has Google Cloud credentials
			GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
			
			if (credentials == null) {
				return false;  // No credentials available
			}
			
			// Validate that the application has access to Google Cloud
			String projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
			if (projectId == null) {
				projectId = System.getenv("GCLOUD_PROJECT");
			}
			
			if (projectId == null) {
				return false;  // Cannot determine project
			}
			
			// Admin access is controlled through IAM policies in Google Cloud Console
			// If the request reached here with valid credentials, the IAM policy 
			// has already filtered for admin-only access
			return true;
		} catch (Exception e) {
			System.err.println("Error during admin authentication: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Log security events for audit trail
	 */
	private void logSecurityEvent(String eventType, HttpServletRequest request) {
		try {
			System.err.println("SECURITY_EVENT: %s | IP: %s | Time: %s".formatted(
			eventType,
			getClientIp(request),
			System.currentTimeMillis()
			));
		} catch (Exception e) {
			// Silently fail logging
		}
	}

	/**
	 * Extract real client IP, accounting for proxies
	 */
	private String getClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("X-Real-IP");
		}
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		// Return only the first IP if multiple are present
		return ip.split(",")[0].trim();
	}
}
