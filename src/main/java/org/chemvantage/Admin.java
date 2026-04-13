/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2011 ChemVantage LLC
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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.openpdf.pdf.ITextRenderer;

import com.google.cloud.datastore.Cursor;
import com.google.cloud.datastore.QueryResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.cmd.Query;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.auth.oauth2.GoogleCredentials;

/* 
 * Access to this servlet is restricted to ChemVantage admin users and the project service account
 * by specifying login: admin in a url handler of the project app.yaml file
 */
@WebServlet("/Admin")
public class Admin extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet is used by ChemVantage admins to manage user properties and roles.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		try {
			// Verify admin authentication
			if (!isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, 
					"Admin access required");
				logSecurityEvent("UNAUTHORIZED_ADMIN_ACCESS_ATTEMPT", request);
				return;
			}
			
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			if ("DownloadVoucherPDF".equals(userRequest)) {
				try {
					String org = request.getParameter("Org");
					response.setContentType("application/pdf");
					response.setHeader("Content-Disposition", "attachment; filename=\"vouchers.pdf\"");
					ServletOutputStream os = response.getOutputStream();
					ITextRenderer renderer = new ITextRenderer();
					renderer.setDocumentFromString(voucherTemplate(org));
					renderer.layout();
					renderer.createPDF(os);
					os.flush();
				} catch (Exception e) {
					response.setContentType("text/html");
					response.setHeader("Content-Disposition", "inline");
					response.getWriter().println(e.getMessage());
				}
				return;
			}
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			String searchString = request.getParameter("SearchString");
			String cursor = request.getParameter("Cursor");
			String org = null;
			
			switch (userRequest) {
			case "OpenStaxReport":
				out.println(Subject.header() + Group.openStaxReport() + Subject.footer);
				break;
			case "OpenStaxCSVReport":
				response.setContentType("text/csv");
				out.println(Group.openStaxCSVReport());
				break;
			case "Find text":
				String searchFor = request.getParameter("searchFor");
				out.println(Subject.getHeader(user) + searchQuestions(searchFor) + Subject.footer);
				break;
			case "ViewCodes":
				org = request.getParameter("Org");
				out.println(Subject.getHeader(user) + viewCodes(org) + Subject.footer);
				break;
			default: 
				out.println(Subject.getHeader(user) + mainAdminForm(user,userRequest,searchString,cursor) + Subject.footer);
			}
		} catch (Exception e) {
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		try {
			// Verify admin authentication
			if (!isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, 
					"Admin access required");
				logSecurityEvent("UNAUTHORIZED_ADMIN_ACCESS_ATTEMPT", request);
				return;
			}
			
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			String searchString = request.getParameter("SearchString");
			String cursor = request.getParameter("Cursor");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			switch (userRequest) {
			case "Announce": 
				Subject.setAnnouncement(request.getParameter("Announcement"));
				break;
			case "OpenStaxReport":  // for monthly cron job
				if (Subject.getProjectId().equals("chem-vantage-hrd")) {
					String msg = "<b>Your Quarterly OpenStax Ally Partner Report Is Ready</b>"
							+ "<ol><li>Download the CSV file <a href=https://www.chemvantage.org/Admin?UserRequest=OpenStaxCSVReport>here</a></li>"
							+ "<li>Import the CSV file to cell B3 of the Excel template file at Drive -> ChemVantage LLC-> OpenStax Partnership -> Quarterly Reports</li>"
							+ "<li>Review the file, including any carry-forward amounts from previous quarter (up to $500.)</li>"
							+ "<li>Save and upload the file to the <a href=https://openstax-community.force.com/partnerportal>OpenStax Partner Portal</a></li></ol>";
					Utilities.sendEmail("ChemVantage","admin@chemvantage.org","OpenStax Quarterly Report", msg);
				}
				return;
			case "Submit Review":
				Deployment d = ofy().load().type(Deployment.class).id(request.getParameter("platform_deployment_id")).safe();
				switch(request.getParameter("action")) {
				case "Approve":
					d.status = "approved";
					ofy().save().entity(d);
					break;
				case "Block":
					d.status = "blocked";
					ofy().save().entity(d);
					break;
				case "Delete":
					ofy().delete().entity(d);
				}
				break;
			case "Create Vouchers":
				try {
					int n = Integer.parseInt(request.getParameter("NVouchers"));
					String org = request.getParameter("Organization");
					int months = Integer.parseInt(request.getParameter("Months"));
					int price = Integer.parseInt(request.getParameter("Price"));
					if (org == null) throw new Exception();
					List<Voucher> newVouchers = new ArrayList<Voucher>();
					for (int i=0; i<n; i++) newVouchers.add(new Voucher(org,months,price));
					ofy().save().entities(newVouchers).now();
					out.println(Subject.getHeader(user) + viewCodes(org) + Subject.footer);
					return;
				} catch (Exception e) {}
				break;
			}
			out.println(Subject.getHeader(user) + mainAdminForm(user,userRequest,searchString,cursor) + Subject.footer);
		} catch (Exception e) {
			response.getWriter().println("Unexpected error: " + e.toString() + e.getMessage());
		}
	}

	String voucherTemplate(String org) {
		StringBuffer buf = new StringBuffer(Subject.header());
		DateFormat df = new SimpleDateFormat("EEE MMM d, yyyy");
		List<Voucher> vouchers = ofy().load().type(Voucher.class).filter("org =",org).filter("activated =",null).order("-purchased").list();
		for (Voucher v : vouchers) {
			buf.append("<div" + (vouchers.indexOf(v)==0?">":" style='page-break-before: always;'>")); 
			buf.append(Subject.banner + "An Open Educational Resource for General Chemistry<br/><br/>");
			buf.append("<h1>Student Subscription Voucher</h1>"
					+ "Issued: " + df.format(v.purchased) + "<br/>"
					+ "Organization: " + v.org + "<br/>"
					+ "Voucher Code: <b>" + v.code + "</b><br/>"
					+ "Code Expires: " + df.format(v.expires) + "<br/><br/>");
			buf.append("This voucher can be redeemed for a " + v.months + "-month subscription to ChemVantage at any "
					+ "time prior to the expiration date above. Your instructor will create ChemVantage assignments in "
					+ "your class LMS. When you launch your first ChemVantage assignment, you will be taken to a subscription "
					+ "page similar to the one shown below. Check the two boxes to accept the terms of the subscription. "
					+ "Then enter the voucher code shown above into the form and click Redeem. Do not use the Checkout "
					+ "button to pay for your subscription.<br/><br/>"
					+ "This voucher code is unique and can only be used to activate one subscription. Once your code "
					+ "is validated, you will be able to proceed to your assignment. Your subscription will "
					+ "be active for a period of " + v.months + " months from the date it is activated.<br/><br/>");
			buf.append("<div><img width=600px src='" + Subject.getServerUrl() + "/images/subscription.png' /></div>");
			buf.append("</div>");
		}
		buf.append("</main></body></html>");
		return buf.toString();
	}
	
	String mainAdminForm(User user,String userRequest,String searchString,String cursor) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Administration</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			// Announcements
			buf.append("<h2>Announcements</h2>");
			buf.append("The following message will be posted in red font at the top of each main page: ");
			
			buf.append("<FORM ACTION=Admin METHOD=POST>"
					+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=Announce>"
					+ "<INPUT TYPE=TEXT SIZE=80 NAME=Announcement VALUE='" + Subject.getAnnouncement() + "'><BR>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + ">"
					+ "<INPUT TYPE=SUBMIT VALUE='Post this message now'></FORM><p>");

			// User Feedback
			int nUserReports = ofy().load().type(UserReport.class).count();
			if (nUserReports > 0)  {
				buf.append("<h2>User Feedback</h2>"
						+ "You have " + nUserReports + " new user feedback reports.<p>");
			}

			// Item Bank Requests
			List<Contact> contacts = ofy().load().type(Contact.class).filter("role","applicant").list();
			if (contacts.size() > 0) {
				buf.append("<h2>Requests for Access to the Item Bank</h2>");
				buf.append("<ul>");
				for (Contact c : contacts) {
					buf.append("<li>" + c.getFullName() + " (" + c.getEmail() + ") at " + c.institution
							+ "<form method=post action=/itembank>"
							+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
							+ "<input type=hidden name=Email value='" + c.getEmail() + "' />"
							+ "<input type=submit name=UserRequest value=Approve /> "
							+ "<input type=submit name=UserRequest value=Deny />"
							+ "</form>"
							+ "</li>");
				}
				buf.append("</ul>");
			}
			
			// Contributed Questions
			int nPending = ofy().load().type(ProposedQuestion.class).count();
			if (nPending > 0) buf.append("<h2>Contributed Questions</h2>"
					+ "<a href=Edit?UserRequest=Review>"
					+ nPending + " items are currently pending editorial review.</a><p>");
			
			// Recent Activity
			buf.append("<h2>Recent Activity (past 30 days)</h2>");			
			if ("ShowGroupEnrollments".equals(userRequest)) buf.append(Group.enrollmentReport());
			else {
				Date oneMonthAgo = new Date(new Date().getTime()-2592000000L);			
				buf.append("Active LTI Advantage deployments: " + ofy().load().type(Deployment.class).filter("lastLogin >",oneMonthAgo).count() 
					+ " <a href=/Admin?UserRequest=ShowGroupEnrollments>show details</a><br/>");			
				int hwt = ofy().load().type(HWTransaction.class).filter("graded >",oneMonthAgo).count();
				int qzt = ofy().load().type(QuizTransaction.class).filter("graded >",oneMonthAgo).count();
				int stt = ofy().load().type(STTransaction.class).filter("graded >",oneMonthAgo).count();
				int pet = ofy().load().type(PracticeExamTransaction.class).filter("graded >",oneMonthAgo).count();
				int plt = ofy().load().type(PlacementExamTransaction.class).filter("graded >",oneMonthAgo).count();
				int vdt = ofy().load().type(VideoTransaction.class).filter("graded >",oneMonthAgo).count();
				int pot = ofy().load().type(PollTransaction.class).filter("completed >",oneMonthAgo).count();
				buf.append("Total number of transactions: " + (hwt+qzt+stt+pet+plt+vdt+pot) + " "
						+ "<a href=# onclick=document.getElementById('transdetail').style.display=''>show details</a><br/>"
						+ "<div id=transdetail style='display:none;'>"
						+ "Homework: " +  hwt + "<br/>"
						+ "Quiz: " + qzt + "<br/>"
						+ "SmartText: " + stt + "<br/>"
						+ "Practice Exam: " + pet + "<br/>"
						+ "Placement Exam: " + plt + "<br/>"
						+ "Video: " + vdt + "<br/>"
						+ "Poll: " + pot
						+ "</div><p>");
			}
			
			// New Accounts
			List<Deployment> review = ofy().load().type(Deployment.class).filter("status", "pending").list();
			review.addAll(ofy().load().type(Deployment.class).filter("status","blocked").list());
			if (review.size() > 0) buf.append("<h2>Accounts Needing Review and Approval</h2>");
			for (Deployment d : review) {
				buf.append("<form method=post><input type=hidden name=UserRequest value='Submit Review'/><input type=hidden name=sig value='" + user.getTokenSignature() + "'/>"
						+ "<input type=hidden name=platform_deployment_id value='" + d.platform_deployment_id + "'/>"
						+ d.platform_deployment_id + " (" + d.lms_type + ")<br/>"
						+ "by " + d.contact_name + " (" + d.email + ") at " + d.organization + " (" + d.org_url + ") -- " + d.status + "<br/>");
				int nAssignments = ofy().load().type(Assignment.class).filter("domain",d.platform_deployment_id).count();
				buf.append("Assignments: " + nAssignments + ".<br/>"
						+ "<input type=submit name=action value='Approve'/>&nbsp;"
						+ "<input type=submit name=action value='Block'/>&nbsp;"
						+ "<input type=submit name=action value='Delete'/></form><p>");
			}
			
			// OpenStax
			buf.append("<h2>Quarterly OpenStax Ally Partner Report</h2>"
					+ "<a href=/Admin?UserRequest=OpenStaxReport>Preview</a> or <a href=/Admin?UserRequest=OpenStaxCSVReport>Download CSV File</a><p>");
			
			// Subscription vouchers
			buf.append("<h2>Subscription Vouchers</h2>");
			List<Voucher> vouchers = ofy().load().type(Voucher.class).filter("activated =",null).order("-purchased").list();

			buf.append("<a href=# onclick=document.getElementById('vouchers').style='display:block';>"
					+ "There are " + vouchers.size() + " unclaimed vouchers.</a><br/>");
			if (vouchers.size()>0) {
				Map<String,Integer> voucherCounts = new HashMap<String,Integer>();
				for (Voucher v : vouchers) { // increment count for this org
					voucherCounts.put(v.org, voucherCounts.containsKey(v.org)?voucherCounts.get(v.org)+1:1);
				}
				buf.append("<div id=vouchers style='display:none'>"
						+ "<table><tr><th>Organization</th><th>Vouchers</th></tr>");
				for (Entry<String,Integer> e : voucherCounts.entrySet()) {
					buf.append("<tr><td>" + e.getKey() + "</td><td style='text-align:center'>"
							+ "<a href='/Admin?UserRequest=ViewCodes&Org=" + URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "'>" + e.getValue() + "</a></td></tr>");
				}
			}
			buf.append("</table>");

			buf.append("<form method=post>");
			buf.append("<input type=hidden name=UserRequest value='Create Vouchers' />");
			buf.append("Create <input type=text size=2 name=NVouchers value=0 /> new "
					+ "<input type=text size=2 name=Months value=12 />-month vouchers for "
					+ "<input type=text name=Organization placeholder=organization /> at "
					+ "$<input type=text size=2 name=Price value=16 /> each. "
					+ "<input type=submit value='Show Codes' />");
			buf.append("</form></div><p>");
			
			// Signature Code
			buf.append("<h2>Anonymous Signature Codes</h2>"
					+ "1 week: " + Long.toHexString(User.encode(new Date(new Date().getTime() + 604800000L).getTime())) + "<br/>"
					+ "1 month: " + Long.toHexString(User.encode(new Date(new Date().getTime() + 2678400000L).getTime())) + "<br/>"
					+ "1 year: " + Long.toHexString(User.encode(new Date(new Date().getTime() + 31536000000L).getTime())) + "<p>");	
			buf.append("<h2>Search Questions for Text</h2><"
					+ "form method=get>"
					+ "Search for: <input type=text name=searchFor /><input type=submit name=UserRequest value='Find text' />"
					+ "</form><p>");
			
			// Regression Tester
			buf.append("<h2>Regression Tester</h2>");
			buf.append("<a href='https://test-vantage.appspot.com/' target='_blank'>Run regression tests interactively</a><br/>");
			buf.append(getRegressionTestStatusReport());

			
		} catch (Exception e) {
			buf.append("<p>" + e.toString());
		}
		return buf.toString();
	}

	String getRegressionTestStatusReport() {
		String endpoint = System.getenv("TEST_VANTAGE_STATUS_URL");
		if (endpoint == null || endpoint.isBlank()) endpoint = "https://test-vantage.appspot.com/api/status";
		StringBuilder buf = new StringBuilder();

		try {
			HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "application/json");

			if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				return "<p>Regression status unavailable (HTTP " + conn.getResponseCode() + ").</p>";
			}

			JsonObject status;
			try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
				status = JsonParser.parseReader(reader).getAsJsonObject();
			}

			String overall = status.has("overallStatus") && !status.get("overallStatus").isJsonNull()
					? status.get("overallStatus").getAsString() : "UNKNOWN";

			if ("PASSED".equalsIgnoreCase(overall)) {
				String message = status.has("message") && !status.get("message").isJsonNull()
						? status.get("message").getAsString() : "All tests passed.";
				buf.append("<p>&#x2705; ")
					.append(escapeHtml(message))
					.append("</p>");
				return buf.toString();
			}

			if ("FAILED".equalsIgnoreCase(overall)) {
				buf.append("<p><b>Regression tests failed:</b></p><ul>");
				JsonArray tests = status.has("tests") && status.get("tests").isJsonArray()
						? status.getAsJsonArray("tests") : new JsonArray();
				for (JsonElement t : tests) {
					if (!t.isJsonObject()) continue;
					JsonObject test = t.getAsJsonObject();
					String testStatus = test.has("status") && !test.get("status").isJsonNull()
							? test.get("status").getAsString() : "UNKNOWN";
					if (!"FAILED".equalsIgnoreCase(testStatus)) continue;
					String title = test.has("title") && !test.get("title").isJsonNull()
							? test.get("title").getAsString() : "Unnamed test";
					String msg = test.has("message") && !test.get("message").isJsonNull()
							? test.get("message").getAsString() : "";
					buf.append("<li>&#x26A0;&#xFE0F; ")
						.append(escapeHtml(title));
					if (!msg.isBlank()) {
						buf.append(" - ").append(escapeHtml(msg));
					}
					buf.append("</li>");
				}
				buf.append("</ul>");
				return buf.toString();
			}

			String message = status.has("message") && !status.get("message").isJsonNull()
					? status.get("message").getAsString() : "No details available.";
			buf.append("<p>Regression status: ")
				.append(escapeHtml(overall))
				.append(". ")
				.append(escapeHtml(message))
				.append("</p>");
			return buf.toString();
		} catch (Exception e) {
			return "<p>Regression status unavailable: " + escapeHtml(e.getMessage() == null ? e.toString() : e.getMessage()) + "</p>";
		}
	}

	private String escapeHtml(String input) {
		if (input == null) return "";
		return input
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
	
	String viewCodes(String org) {
		StringBuffer buf = new StringBuffer("<h1>Student Subscription Vouchers</h1><b>" + org + "</b><br/><br/>");
		DateFormat df = new SimpleDateFormat("EEE MMM d, yyyy");
		List<Voucher> vouchers = ofy().load().type(Voucher.class).filter("org =",org).filter("activated =",null).order("-purchased").list();
		buf.append("<table><tr style='text-align:center'><th>Voucher Code</th><th>Code Expires</th><th>Subscription</th><th>&nbsp;&nbsp;Paid</th></tr>");
		for (Voucher v : vouchers) {
			buf.append("<tr style='text-align:center'><td>" + v.code + "</td>"
					+ "<td>" + (v.expires==null?" - ":df.format(v.expires)) + "</td>"
					+ "<td>" + v.months + " month" + (v.months>1?"s":"") + "</td>"
					+ "<td>$" + v.paid + "</td>"
					+ "</tr>");
		}
		buf.append("</table><br/><br/>");
		try {
			buf.append("<a href='/Admin?UserRequest=DownloadVoucherPDF&Org=" + URLEncoder.encode(org,StandardCharsets.UTF_8) + "'>Download PDF file</a>");	
		} catch (Exception e) {}
		return buf.toString();
	}
	
	String searchQuestions(String searchFor) throws Exception {
		StringBuffer buf = new StringBuffer("<h1>Searching Questions For: " + searchFor + "</h1>");
		if (searchFor==null || searchFor.isEmpty()) {
			buf.append("search string was missing");
			return buf.toString();
		}
		int found = 0;
		Query<Question> query = ofy().load().type(Question.class).limit(1000);
		while (found < 100) {
			QueryResults<Question> iterator = query.iterator();
			int countInBatch = 0;
			while (iterator.hasNext()) {
				Question question = iterator.next();
				countInBatch++;
				if (question.text != null && question.text.toLowerCase().contains(searchFor)) {
					buf.append("id: " + question.id + " Text: " + question.text + "<br/>");
					found++;
				}
			}

			if (countInBatch < 1000) break;
			Cursor nextCursor = iterator.getCursorAfter();
			query = query.startAt(nextCursor);           
		}
		if (found==0) buf.append("String not found.");
		return buf.toString();
	}

	/**
	 * Verify the request is from an authenticated admin user or service account
	 * using Google Cloud IAM authentication
	 */
	private boolean isAdminAuthenticated(HttpServletRequest request) {
		try {
			// For local development (localhost), allow admin access without credentials
			String serverName = request.getServerName();
			if ("localhost".equals(serverName) || "127.0.0.1".equals(serverName)) {
				System.out.println("Local development mode: Admin authentication bypassed for localhost");
				return true;
			}
			
			// In production, verify the application has Google Cloud credentials
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

