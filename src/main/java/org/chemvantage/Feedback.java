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

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.*;
import java.util.Date;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
import com.google.recaptchaenterprise.v1.RiskAnalysis.ClassificationReason;
import com.googlecode.objectify.cmd.Query;


@WebServlet(urlPatterns = {"/Feedback", "/feedback"})
public class Feedback extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet is used by users to submit comments, questions or requests.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		User user = null;
		try {
			user = User.getUser(request.getParameter("sig"));
		} catch (Exception e) {
			user = new User();  // creates new anonymous User
		}
		
		try {
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			switch (userRequest) {
			case "ReportAProblem":
				String userId = user==null?"":user.getId();
				long questionId = Long.parseLong(request.getParameter("QuestionId"));
				int[] params = {0,0,0,0};
				try {
					String[] sparams = request.getParameter("Params").replace("[","").replace("]","").replaceAll("\\s", "").split(",");
					for (int i=0;i<sparams.length;i++) params[i]=Integer.parseInt(sparams[i]);
				} catch (Exception e) {}
				String notes = request.getParameter("Notes");
				String email = request.getParameter("Email");
				String studentAnswer = request.getParameter("StudentAnswer");
				UserReport r = new UserReport(userId,questionId,params,studentAnswer,notes);
				ofy().save().entity(r);
				sendEmailToAdmin(r,user,email);
				break;
			case "AjaxRating":
				out.println(recordAjaxRating(request));
				break;
			default:
				out.println((user.isChemVantageAdmin()?Subject.getHeader(user):Subject.header("ChemVantage Feedback Form")) + feedbackForm(user) + Subject.footer);
			}
		} catch (Exception e) {
			response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) user = new User(); // anonymous user
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";

			out.println(user.isChemVantageAdmin()?Subject.getHeader(user):Subject.header("ChemVantage Feedback"));
			switch (userRequest) {
			case "SubmitFeedback":
				out.println(submitFeedback(user,request));
				break;
			case "Delete Report":
				ofy().delete().key(key(UserReport.class,Long.parseLong(request.getParameter("ReportId")))).now();
			default:
				out.println(feedbackForm(user));
			}
			out.println(Subject.footer);
		} catch (Exception e) {
			response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	public String recordAjaxRating(HttpServletRequest request) {
		int stars = 0;
		try {
			stars = Integer.parseInt(request.getParameter("NStars"));
			Subject.addStarReport(stars);
		}
		catch (Exception e) {
			return e.toString();
		}
		return "Thank you. Your rating was " + stars + " stars.<br/>The average user rating is " + Subject.getAvgStars() + " stars (" + Subject.getNStarReports() + " ratings).";
	}

	String feedbackForm(User user) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:800px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>ChemVantage Feedback</h1>"
				+ "				 Your comments and opinions are important to us.  We use this "
				+ "              information to improve the functionality of the site for our users."
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		
		buf.append(fiveStars(user.getTokenSignature()));
		
		String[] member = getMember(user);  // role, name, email
		String email = member!=null && member[2]!=null?member[2]:null;
		
		buf.append("<FORM NAME=FeedbackForm id=FeedbackForm ACTION=Feedback METHOD=POST>\n"
				+ "<label for='comment'>Comments, suggestions or requests: <FONT SIZE=-1>(160 characters max.)</FONT></label><br>"
				+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=SubmitFeedback />"
				+ "<INPUT TYPE=HIDDEN NAME=Stars />"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "' />"
				+ "<TEXTAREA id=comment NAME=Comments ROWS=5 COLS=60 WRAP=SOFT "				
				+ "onKeyUp=javascript:{document.FeedbackForm.Comments.value=document.FeedbackForm.Comments.value.substring(0,160);document.getElementById('cbox').style.visibility='visible';}>"
				+ "</TEXTAREA><br>");

		if (email==null || email.isEmpty()) buf.append("<label id=cbox style='visibility:hidden'>Email: <input type=text size=50 placeholder=' optional, if you want a response to your comment' name=Email></label><p>");
		else buf.append("<span id=cbox style='visibility:hidden'>Our response will be sent to you at " + email + "<br/></span>"
				+ "<input type=hidden name=Email value=" + email + " /><br/>");
		
		
 		
		// If the user is anonymous, insert the Google reCaptcha tool on the page
		if (user.isAnonymous()) {
			buf.append("Note: This page is protected by reCAPTCHA to prevent abuse. The reCAPTCHA service is provided by Google and is subject to Google's <a href=https://policies.google.com/privacy>Privacy Policy</a> and <a href=https://policies.google.com/terms>Terms of Service</a>.<br/><br/>"
				+ "<script src='https://www.google.com/recaptcha/enterprise.js?render=" + Subject.getReCaptchaKey() + "'></script>\n"
				+ "<script>"
				+ "  function onSubmit(token) { "
				+ "    document.getElementById('g-recaptcha-response').value = token; "
				+ "    document.getElementById('FeedbackForm').submit(); "
				+ "  }"
    			+ "</script>"
				+ "<input type='hidden' id='g-recaptcha-response' name='g-recaptcha-response' />"
				+ "<button class='g-recaptcha btn btn-primary' data-sitekey='" + Subject.getReCaptchaKey() + "' data-callback='onSubmit' data-action='submitFeedback'>"
				+ "Submit Comment"
				+ "</button></FORM>");				
		} else buf.append("<INPUT CLASS='btn btn-primary' TYPE=SUBMIT VALUE='Submit Comment'></FORM>");

		if (user.isChemVantageAdmin()) {
			String reports = viewUserFeedback(user);
			if (!reports.isEmpty()) buf.append("<hr><h3>User Feedback</h3>" + reports);
		}
		return buf.toString(); 
	}

	static String fiveStars(String sig) {
		StringBuffer buf = new StringBuffer();
		buf.append("""
				<style>\
				.radioStar {
				  position: absolute;
				  opacity: 0;
				  cursor: pointer;
				  height: 0;
				  width: 0;
				}\
				.radioStar:focus + .star-label {
				  outline: 2px solid blue;
				  outline-offset: 2px;
				}\
				.radio-star-container {
				  display: inline-block;
				  cursor: pointer;
				}\
				</style>""");
		
		buf.append("<br/><div id=star-rating display='flex'><fieldset>Please rate ChemVantage: ");
		for (int istar=1;istar<6;istar++) {
			buf.append("<div class='radio-star-container'>"
					+ "<input type='radio' id='radio" + istar + "' name='StarSelection' class='radioStar' value='" + istar + "' "
					+ " onfocus=showStars(" + istar + "); "
					+ " onblur=showStars(0); "
					+ " onkeydown=submitStars(event,'" + sig + "'); />"
					+ "<label for='radio" + istar + "' class='star-label'>"
					+ " <img id=" + istar + " src='https://images.chemvantage.org/star1.gif' alt='star" + istar + "' "
					+ " onmouseover=showStars(this.id); "
					+ " onmouseout=showStars(0); "
					+ " onclick=document.getElementById('radio'+this.id).checked=true;submitStars(event,'" + sig + "'); />\n"
					+ "</label>"
					+ "</div>");
		}
		buf.append("<span id='vote' style='color:#B20000;display:none'></span></fieldset></div><br/>");
		buf.append("<script src='/js/five_star_radios.js?v=3'></script>");
		
		return buf.toString();
	}
	
	String[] getMember(User user) {
		String[] member = null;
		try {
			if (user.isAnonymous()) throw new Exception();
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
			Map<String,String[]> membership = LTIMessage.getMembership(a);
			String userId = user.getId();  // includes platform_id
			member = membership.get(userId.substring(userId.lastIndexOf("/")+1));  // LMS userId
		} catch (Exception e) {}
		return member;
	}
	
	String submitFeedback(User user,HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:800px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>ChemVantage Feedback</h1>"
				+ "				 Your comments and opinions are important to us.  We use this "
				+ "              information to improve the functionality of the site for our users."
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		
		int stars = 0;
		try {
			stars = Integer.parseInt(request.getParameter("Stars"));
			if (stars>0) Subject.addStarReport(stars);
		} catch (Exception e) {}
		
		String comments = request.getParameter("Comments");
		if (stars==0 && (comments == null || comments.isEmpty())) {
			return feedbackForm(user);
		}
		
		String email = request.getParameter("Email");
		try {
			String regex = "^[\\w-_\\.+]*[\\w-_\\.]\\@([\\w]+\\.)+[\\w]+[\\w]$";
			if (!email.matches(regex)) email = null;
		} catch (Exception e) {
			email = null;
		}
		
		float riskScore = -1.0f;
		try { 
			if (!request.getServerName().equals("localhost") && user.isAnonymous()) {
				String token = request.getParameter("g-recaptcha-response");
				if (token == null || token.isEmpty()) {
					throw new Exception("reCAPTCHA token missing");
				}
				riskScore = createAssessment(token, "submitFeedback");
				if (riskScore < 0.3) {
					throw new Exception("Sorry, the reCAPTCHA risk score was too low: " + riskScore);
				}
			}
		} catch (Exception e) {
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Error during reCAPTCHA validation: ", e.toString());
			return "<h1>Submission Failed</h1>"
					+ "The ReCAPTCHA validation failed: " + e.getMessage() + "<br/>"
					+ "Please click the BACK button on your browser to try again.";
		}
		
		if (comments.length() > 0) {
			UserReport r = new UserReport(user.getId(),stars,comments);
			if (riskScore >= 0) r.riskScore = riskScore;  // Store reCAPTCHA score if available
			ofy().save().entity(r);
			sendEmailToAdmin(r,user,email);
		}

		buf.append(new Date().toString() + "<p>");
		buf.append("Thank you for your feedback" + (stars>0?" (" + stars + " stars)":"") + ". ");
		
		if (comments.length() > 0) {
			buf.append("Your comment was: <p><font color=red>" + comments + "</font><p>");
		
			if (email==null) buf.append("We will review your comment, but we're unable to provide a response because you did not provide a valid email address.<p>");
			else buf.append("We will review your comment. Any response will be sent to " + email + ".<p>");
			buf.append("Feel free to email any additional comments to us at <a href=mailto:admin@chemvantage.org>admin@chemvantage.org</a><p>");
		}
		
		if (user != null && user.isAnonymous()) buf.append("<p><a href=Home>Return to the Home page</a><br>");
		return buf.toString();
	}

	public static float createAssessment(String token, String recaptchaAction) throws IOException {
		String projectId = Subject.getProjectId();
		String recaptchaKey = Subject.getReCaptchaKey();
    	
		// Create the reCAPTCHA client.
    	try (RecaptchaEnterpriseServiceClient client = RecaptchaEnterpriseServiceClient.create()) {

      		// Set the properties of the event to be tracked.
      		Event event = Event.newBuilder().setSiteKey(recaptchaKey).setToken(token).build();

      		// Build the assessment request.
      		CreateAssessmentRequest createAssessmentRequest =
         	 CreateAssessmentRequest.newBuilder()
         	    .setParent(ProjectName.of(projectId).toString())
         	    .setAssessment(Assessment.newBuilder().setEvent(event).build())
         		.build();

      		Assessment response = client.createAssessment(createAssessmentRequest);

      		// Check if the token is valid.
      		if (!response.getTokenProperties().getValid()) {
        		System.out.println("The CreateAssessment call failed because the token was: "
        	        + response.getTokenProperties().getInvalidReason().name());
       			return 0.0f;
      		}

      		// Check if the expected action was executed.
      		if (!response.getTokenProperties().getAction().equals(recaptchaAction)) {
        		System.out.println("The action attribute in reCAPTCHA tag is: "
            	    + response.getTokenProperties().getAction());
        		System.out.println("The action attribute in the reCAPTCHA tag "
            	    + "does not match the action ("
            	    + recaptchaAction
            	    + ") you are expecting to score");
        		return 0.0f;
      		}

      		// Get the risk score and the reason(s).
      		// For more information on interpreting the assessment, see:
      		// https://cloud.google.com/recaptcha/docs/interpret-assessment
      		for (ClassificationReason reason : response.getRiskAnalysis().getReasonsList()) {
        		System.out.println(reason);
      		}

      		float recaptchaScore = response.getRiskAnalysis().getScore();
      		System.out.println("The reCAPTCHA score is: " + recaptchaScore);

      		// Get the assessment name (id). Use this to annotate the assessment.
      		String assessmentName = response.getName();
      		System.out.println("Assessment name: " + assessmentName.substring(assessmentName.lastIndexOf("/") + 1));
      		return recaptchaScore;
    	} 
	}
/*
	boolean reCaptchaOK(HttpServletRequest request) throws Exception {
		OutputStreamWriter writer = null;
		BufferedReader reader = null;
		JsonObject captchaResponse = null;
		try {
			String queryString = "secret=" + Subject.getReCaptchaSecret() + "&response=" 
					+ request.getParameter("g-recaptcha-response") + "&remoteip=" + request.getRemoteAddr();
			URL u = new URI("https://www.google.com/recaptcha/api/siteverify").toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setDoOutput(true);
			uc.setRequestMethod("POST");
			uc.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
			uc.setRequestProperty("Content-Length", String.valueOf(queryString.length()));

			writer = new OutputStreamWriter(uc.getOutputStream());
			writer.write(queryString);
			writer.flush();
			writer.close();

			// read & interpret the JSON response from Google
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			captchaResponse = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
		} catch (Exception e) {
		} finally {
			if (writer != null) writer.close();
			if (reader != null) reader.close();
		}
		return captchaResponse != null && captchaResponse.get("success").getAsBoolean();
	}
*/
	String viewUserFeedback(User user) {
		StringBuffer buf = new StringBuffer();
		Query<UserReport> reports = ofy().load().type(UserReport.class).order("-submitted");		
		for (UserReport r : reports) {
			String report = r.view(user);  // returns report only for ChemVantage admins, domainAdmins and report author
			if (report != null) buf.append(report + "<hr>");
		}
		return buf.toString();
	}
	
	private void sendEmailToAdmin(UserReport r,User user,String email) {
		String msgBody = r.view();
		String name = "";
		
		try {
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
			Map<String,String[]> members = LTIMessage.getMembership(a);
			String userId = user.getId();
			String raw_id = userId.substring(userId.lastIndexOf("/")+1);  // userId according to the platform
			String[] member = members.get(raw_id);
			if (member[1]!=null && !member[1].isEmpty()) name=member[1];
			if (member[2]!=null && !member[2].isEmpty()) email = member[2];
		} catch (Exception e) {}

		if (email==null || email.isEmpty()) return;  // nowhere to send
		else msgBody += "<p>Respond to" + (user.isInstructor()?" instructor: ":": ") + name + " &lt;" + email + "&gt;";
			
		if (msgBody.length()==0) return;  // no reports exist
		
		try {
		Utilities.sendEmail("ChemVantage","admin@chemvantage.org","ChemVantage Feedback Report",msgBody);
		} catch (Exception e) {}
	}
}

