/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2012 ChemVantage LLC
*   
*	This servlet file is adapted from an open-source Java servlet 
*	LTIProviderServlet written by Charles Severance at 1edtech.org
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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.regex.Pattern;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
import com.google.recaptchaenterprise.v1.RiskAnalysis.ClassificationReason;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@WebServlet(urlPatterns = {"/lti/registration","/lti/registration/"})
public class LTIRegistration extends HttpServlet {

	/*This servlet class is used to apply for and grant access to LTI connections between client
	 * LMS platforms and the ChemVantage tool. The user will complete a short form with name, role,
	 * email, organization, home page, and LMS type. and use case (testing or production).
	 * Requests for LTIv1.1 credentials were discontinued in 2021.
	 * The workflow path for LTIv1.3 requests is:
	 *   1) All users complete a basic form giving information about their org and the LTI request. If the
	 *      launch uses Dynamic Registration, this information is used to eliminate some of the fields. If
	 *      present, the OpenID Configuration URL and Registration Token are included in the POST to ChemVantage.
	 *   2) ChemVantage validates the registration parameters, and if necessary, displays the form again
	 *      to correct any errors.
	 *   3) The registration email contains an activation token and, if necessary, the ChemVantage endpoints 
	 *      and configuration JSON to complete the registration in the LMS. 
	 *   4) The user then clicks the tokenized link, which contains the platformDeploymentId. If necessary, a form
	 *      is presented to supply the client_id and deployment_id values and LMS endpoints. Otherwise, the 
	 *      registration is complete.
	 *      
	 * For LTI Dynamic Registration, the ChemVantage endpoint is the same, and the form still applies, but
	 * some information is automatically received (e.g., LMS product name, LTIAdvantage) so does not appear 
	 * as an option on the form. When submitted, the registration success email is sent immediately.
	 * 
	 * */
	
	@Serial
	private static final long serialVersionUID = 137L;
	static String price = "2";
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		PrintWriter out = response.getWriter();
		
		try {
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";

			String iss = "https://" + request.getServerName();
			String reg_code = request.getParameter("reg_code");
			boolean dynamicRegistration = request.getParameter("openid_configuration")!=null;
			
			if ("config".contentEquals(userRequest)) {
				response.setContentType("application/json");
				out.println(getConfigurationJson(iss,request.getParameter("lms")));
			} else if (reg_code!=null) {
				response.setContentType("text/html");
				RegistrationCode rc = ofy().load().type(RegistrationCode.class).id(reg_code).now();
				if (rc==null || rc.expires.before(new Date())) {
					throw new Exception("The registration code is invalid or has expired. "
					+ (Subject.getProjectId().equals("dev-vantage-hrd")?"Please contact admin@chemvantage.org for a new code.":"Please request a new code."));
				}
				if (dynamicRegistration) doPost(request, response);
				else out.println(Subject.header("LTI Registration") + clientIdForm(rc) + Subject.footer);
			} else {
				out.println(Subject.header() + registrationForm(request,null) + Subject.footer);
			}
		} catch (Exception e) {
			out.println(Subject.header() + "<h1>Registration Failed</h1>" + e.getMessage() + Subject.footer);
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
	throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		StringBuffer debug = new StringBuffer("Debug:0");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";

		boolean dynamicRegistration = request.getParameter("openid_configuration")!=null;
		String reg_code = request.getParameter("reg_code");
		RegistrationCode rc = ofy().load().type(RegistrationCode.class).id(reg_code).now();
				
		try {
			if ("finalize".contentEquals(userRequest)) {
				if (rc==null) throw new Exception("The registration code provided with this link could not be validated.");
				out.println(Subject.header("ChemVantage LTI Registration") + Subject.banner + "<h1>Registration Success</h1>" + createDeployment(request, rc) + Subject.footer);			
			} else {
				if (rc==null) rc = validateApplicationFormContents(request);
				if (dynamicRegistration) {
					JsonObject openIdConfiguration = getOpenIdConfiguration(request);  // LTIDRSv1p0 section 3.4
					String registration_token = request.getParameter("registration_token");
					validateOpenIdConfigurationURL(request.getParameter("openid_configuration"),openIdConfiguration);  // LTIDRSv1p0 section 3.5.1
					JsonObject registrationResponse = postRegistrationRequest(openIdConfiguration, registration_token);  // LTIDRSv1p0 section 3.5.2 & 3.6
			        Deployment d = createNewDeployment(openIdConfiguration,registrationResponse,rc); // LTIDRSv1p0 section 3.7
					sendApprovalEmail(d);
					response.setContentType("text/html");
					out.println(successfulRegistrationRequestPage(d));
				} else { // manual registration
					if (Subject.getProjectId().equals("dev-vantage-hrd") && !rc.email.equals("admin@chemvantage.org")) {
						out.println(Subject.header("ChemVantage LTI Registration") + Subject.banner 
							+ "<h1>Development Registration</h1>Thank you for your interest in ChemVantage. "
							+ "In this development environment, registration codes are not automatically generated or emailed.>br/><br/>"
							+ "Please contact Chuck Wight at admin@chemvantage.org for more information.");
					} else {
						ofy().save().entity(rc).now();			
						sendRegistrationEmail(rc);
						out.println(Subject.header("ChemVantage LTI Registration") + Subject.banner 
							+ "<h1>Success</h1>Thank you. A registration code has been sent to your email address.<p>"
							+ "If you don't receive the email within a few minutes, please check your spam folder or contact us at admin@chemvantage.org<br/><br/>"
							+ "<form method=get action=/lti/registration>"
							+ "<label>Enter the registration code here: <input type=text name=reg_code /></label><input type=submit value=Submit />"
							+ "</form><br/>"
							+ Subject.footer);
					}			
				}
			}
		} catch (Exception e) {
			String message = (e.getMessage()==null?e.toString():e.getMessage());
			if (dynamicRegistration) {
				String emailmessage = message + "<br/>"
						+ "Name: " + request.getParameter("name") + "<br/>"
						+ "Email: " + request.getParameter("email") + "<br/>"
						+ "Org: " + request.getParameter("org") + "<br/>"
						+ "URL: " + request.getParameter("url") + "<br/>"
						+ "LMS: " + request.getParameter("lms") + "<br/>"
						+ debug.toString();
				Utilities.sendEmail("ChemVantage Administrator","admin@chemvantage.org","Dynamic Registration Error",emailmessage);
			}
			out.println(Subject.header() + registrationForm(request,message) + Subject.footer);
		}
	}
		
	String registrationForm(HttpServletRequest request, String message) {
		String name = request.getParameter("name");
		String email = request.getParameter("email");
		String org = request.getParameter("org");
		String url = request.getParameter("url");
		String lms = request.getParameter("lms");
		String lms_other = request.getParameter("lms_other");
		String AcceptChemVantageTOS = request.getParameter("AcceptChemVantageTOS");
		String openid_configuration = request.getParameter("openid_configuration");
		String registration_token = request.getParameter("registration_token");
		boolean dynamic = openid_configuration != null;
		boolean dev = Subject.getProjectId().equals("dev-vantage-hrd");

		StringBuffer buf = new StringBuffer(Subject.banner);
		
		if (message != null) {
			buf.append("<span style='color: #EE0000; border: 2px solid red'>&nbsp;" + message + " &nbsp;</span>");
		}
		
		buf.append("<h1>LTI Advantage " + (dynamic?"Dynamic ":"Manual ") + "Registration</h1>");
		
		buf.append("<div id=reg_code style='display:" + (dev || (message==null && !dynamic)?"block":"none") + "'>"
			+ "<form method=" + (dynamic?"post":"get") + " action=/lti/registration><br/>");
		if (dynamic) {
			buf.append("<input type=hidden name=openid_configuration value='" + (openid_configuration==null?"":openid_configuration) + "' />"
				+ "<input type=hidden name=registration_token value='" + (registration_token==null?"":registration_token) + "' />");
		}
		buf.append("If you already have a ChemVantage registration code,<br/>please enter it here: <input type=text name=reg_code /><input type=submit value=Submit />"
				+ "</form><br/><br/>");
		if (Subject.getProjectId().equals("dev-vantage-hrd")) {
			buf.append("In this development environment, registration codes are not automatically generated or emailed.<br/>"
				+ "Please contact Chuck Wight at <a href='mailto:admin@chemvantage.org'>admin@chemvantage.org</a> for additional information.<br/><br/>");
		} else {
			buf.append("Otherwise, you may <a href=# onClick=document.getElementById('reg_code').style.display='none';document.getElementById('reg_form').style.display='block';>request a new code here</a>.<br/>");
		}
		buf.append("</div>");

		buf.append("<div id=reg_form style='display:" + (!dev && (message!=null || dynamic)?"block":"none") + "'>"
				+ "<form id=regform method=post action=/lti/registration>"
				+ "Please complete the form below to create a trusted LTI Advantage connection between your LMS and ChemVantage "
				+ "that is convenient, secure and <a href=https://site.imsglobal.org/certifications/chemvantage/chemvantage>certified by 1EdTech</a>. "
				+ "When you submit the form, ChemVantage will send "
				+ (dynamic?"a back-end registration request to your LMS. If successful, you must activate the deployment in your LMS.":"a registration code to complete the registration process.")
				+ "<br/><br/>\n");
		
		buf.append("Contact information for the LMS administrator or office responsible for LMS administration:<br/>"
				+ "<label>Name: <input type=text required name=name size=40 value='" + (name==null?"":name) + "' /> </label><br/>"
				+ "<label>Email: <input type=text required name=email size=40 value='" + (email==null?"":email) + "' /> </label><br/><br/>\n");
		
		buf.append("Your school, business or organization:<br/>"
				+ "<label>Org Name: <input type=text required name=org size=30 value='" + (org==null?"":org) + "' /> </label><br/>\n"
				+ "<label>Home Page: <input type=text required name=url size=30 placeholder='https://myschool.edu' value='" + (url==null?"":url) + "' /></label><br/><br/>\n");

		if (dynamic) {
			if (registration_token!=null) buf.append("<input type=hidden name=registration_token value='" + registration_token + "' />");
			buf.append("<input type=hidden name=openid_configuration value='" + openid_configuration + "' />");
			if (Subject.getProjectId().equals("dev-vantage-hrd")) {
				String reg_code = request.getParameter("reg_code");
				buf.append("Registration Code: <input type=text name=reg_code value='" + (reg_code==null?"":reg_code) + "' /> (contact Chuck Wight at <a href='mailto:admin@chemvantage.org'>admin@chemvantage.org</a>)<br/><br/>");
			} 
		} else {
			buf.append("<fieldset style='width:400px'><legend>Type of LMS:<br/></legend>"
				+ "<div id=rec_dyn style='color:#EE0000;display:none;'>Dynamic Registration is highly recommended for this LMS. Stop now and <a href=/install.html>see the instructions.</a></div><br/>\n"
				+ "<label><input type=radio name=lms required value=blackboard " + ((lms!=null && lms.equals("blackboard"))?"checked":"") + "  />&nbsp;Blackboard</label><br/>\n"
				+ "<label><input type=radio name=lms required value=brightspace " + ((lms!=null && lms.equals("brightspace"))?"checked":"") + "  />&nbsp;Brightspace</label><br/>\n"
				+ "<label><input type=radio name=lms required value=canvas " + ((lms!=null && lms.equals("canvas"))?"checked":"") + "  />&nbsp;Canvas</label><br/>\n"
				+ "<label><input type=radio name=lms required value=moodle " + ((lms!=null && lms.equals("moodle"))?"checked":"") + "  />&nbsp;Moodle</label><br/>\n"
				+ "<label><input type=radio name=lms required value=open_edx " + ((lms!=null && lms.equals("open_edx"))?"checked":"") + "  />&nbsp;Open edX</label><br/>\n"
				+ "<label><input type=radio name=lms required value=sakai " + ((lms!=null && lms.equals("sakai"))?"checked":"") + "  />&nbsp;Sakai</label><br/>\n"
				+ "<label><input type=radio name=lms required value=schoology " + ((lms!=null && lms.equals("schoology"))?"checked":"") + "  />&nbsp;Schoology</label><br/>\n"
				+ "<label><input type=radio name=lms required id=other value=other " + ((lms!=null && lms.equals("other"))?"checked":"") + "  />&nbsp;Other:</label>\n"
				+ "<label><input type=text name=lms_other value='" + (lms_other==null?"":lms_other) + "' placeholder='(specify)' onFocus=document.getElementById('other').checked=true; /></label>\n"
				+ "</fieldset>\n"
				+ "<br/>");
		
			buf.append("<script>"
				+ "function onLmsChange() {"
				+ "  var lmsRadio = document.querySelector('input[name=\"lms\"]:checked');"
				+ "  var lms_selected = lmsRadio ? lmsRadio.value : '';"
				+ "  var recDyn = document.getElementById('rec_dyn');"
				+ "  if (!recDyn) return;"
				+ "  switch (lms_selected) {"
				+ "    case 'brightspace':"
				+ "    case 'canvas':"
				+ "    case 'moodle':"
				+ "    case 'sakai':"
				+ "      recDyn.style.display = 'block';"
				+ "      break;"
				+ "    default:"
				+ "      recDyn.style.display = 'none';"
				+ "  }"
				+ "}"
				+ "document.addEventListener(\"DOMContentLoaded\", function () {"
				+ "  document.querySelectorAll('input[name=\"lms\"]').forEach(function (radio) {"
				+ "    radio.addEventListener(\"change\", onLmsChange);"
				+ "  });"
				+ "  onLmsChange();"
				+ "});"
				+ "</script>");
		}

		buf.append("Pricing:"
				+ "<ul>"
				+ "	<li>LTI registration and instructor accounts are free.</li>"
				+ "	<li>Each student account costs only $2 USD per month or $8 USD per semester (5 months).</li>"
				+ "	<li>Institutions can purchase 1-year student licenses for $5 USD/each (10 license minimum). Contact admin@chemvantage.org for an invoice.</li>"
				+ " <li>All ChemVantage subscription fees are non-refundable.</li>"
				+ "</ul>\n");

		buf.append("<label><input type=checkbox required name=AcceptChemVantageTOS value=true " + ((AcceptChemVantageTOS!=null && AcceptChemVantageTOS.equals("true"))?"checked":"") + " />&nbsp;Accept the <a href=/terms_and_conditions.html target=_blank aria-label='opens new tab'>ChemVantage Terms of Service</a></label><br/><br/>\n");

		if (!dynamic && !request.getServerName().contains("localhost")) { // use reCAPTCHA v2 for manual registration, but skip localhost testing
			buf.append("Note: This page is protected by reCAPTCHA to prevent abuse. The reCAPTCHA service is provided by Google and is subject to Google's <a href=https://policies.google.com/privacy>Privacy Policy</a> and <a href=https://policies.google.com/terms>Terms of Service</a>.<br/><br/>"
				+ "<script src='https://www.google.com/recaptcha/enterprise.js?render=" + Subject.getReCaptchaKey() + "'></script>\n"
				+ "<script>"
				+ "  function onSubmit(token) { "
				+ "    document.getElementById('g-recaptcha-response').value = token; "
				+ "    document.getElementById('regform').submit(); "
				+ "  }"
				+ "</script>"
				+ "<input type='hidden' id='g-recaptcha-response' name='g-recaptcha-response' />"
				+ "<button class='btn btn-primary g-recaptcha' data-sitekey='" + Subject.getReCaptchaKey() + "' data-callback='onSubmit' data-action='submitRegistration'>"
				+ "Request Registration Code"
				+ "</button>");
		} else {
			buf.append("<input type=submit class='btn btn-primary' "
				+ "onclick=this.disabled=true;this.value='Submitting...';this.form.submit(); "
				+ "value='" + (dynamic?"Register":"Request Registration Code") + "' />");
		}
		buf.append("</FORM></div><br/>"); // end of reg_form
		return buf.toString();
	}
	
	RegistrationCode validateApplicationFormContents(HttpServletRequest request) throws Exception {
		String name = request.getParameter("name");
		String email = request.getParameter("email");
		String org = request.getParameter("org");
		String url = request.getParameter("url");
		String lms = request.getParameter("lms");
		String lms_other = request.getParameter("lms_other");
		String reg_code = request.getParameter("reg_code");
		String openid_configuration = request.getParameter("openid_configuration");
		boolean dynamic = openid_configuration != null;
		
		if (Subject.getProjectId().equals("dev-vantage-hrd")) {
			if (reg_code==null || reg_code.isEmpty()) throw new Exception("Registration code is required in the development environment. Please contact admin@chemvantage.org for assistance.");
			RegistrationCode rc = ofy().load().type(RegistrationCode.class).id(reg_code).now();
			if (rc==null || rc.expires.before(new Date())) {
				throw new Exception("The registration code is invalid or has expired. Please contact admin@chemvantage.org for assistance.");
			}
			return rc;
		}
	
		if (name==null) name = "";
		else name = name.trim();
		if (name.isEmpty()) throw new Exception("Name is required.");
		
		if (email==null) email = "";
		email = email.trim();
		if (email.isEmpty()) throw new Exception("Email is required.");
		
		String regex = "^[A-Za-z0-9+_.-]+@(.+)$";		 
		Pattern pattern = Pattern.compile(regex);
		if (!pattern.matcher(email).matches()) throw new Exception("Your email address was not formatted correctly. ");
		
		if (org==null) org = "";
		org = org.trim();
		if (org.isEmpty()) throw new Exception("Please enter your organization name.");
		
		if (url==null) url = "";
		url = url.trim();
		if (url.isEmpty()) throw new Exception("Please enter the URL for your organization's home page.");
		
		if (!url.isEmpty() && !url.startsWith("http")) url = "https://" + url;
		try {
			new URI(url).toURL();   // throws Exception if URL is not formatted correctly
		} catch (Exception e) {
			throw new Exception("Invalid domain name (" + url + "). " + e.toString());
		}

		if (!dynamic) {
			if (lms==null) throw new Exception("Please select the type of LMS that you are connecting to ChemVantage. ");
			if ("other".equals(lms) && (lms_other==null || lms_other.isEmpty())) throw new Exception("Please describe the type of LMS that you are connecting to ChemVantage. ");
			if ("other".equals(lms)) lms = lms_other;
			float riskScore = -1.0f;
			String token = request.getParameter("g-recaptcha-response");
			if (!request.getServerName().contains("localhost")) {
				if ((token == null || token.isEmpty())) {
					throw new Exception("reCAPTCHA token missing");
				}
				riskScore = createAssessment(token, "submitRegistration");
				if (riskScore < 0.3) {
					throw new Exception("Sorry, the reCAPTCHA risk score was too low: " + riskScore);
				}
			}
		}

		if (!"true".equals(request.getParameter("AcceptChemVantageTOS"))) throw new Exception("Please read and accept the ChemVantage Terms of Service. ");

		// Save a new registration code
		RegistrationCode rc = new RegistrationCode(name,email,org,url,lms);
		return rc;
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
	
	void sendRegistrationEmail(RegistrationCode rc) throws Exception {
		StringBuffer buf = new StringBuffer();
		
		buf.append("<h2>ChemVantage Registration</h2>");
		
		buf.append("Name: " + rc.name + " (" + rc.email + ")<br/>");
		buf.append("Organization: " + rc.org + (rc.url.isEmpty()?"":" (" + rc.url + ")") + "<br/>");
		buf.append("LMS: " + rc.lms + "<br/><br/>");
		
		switch (rc.lms) {
			case "blackboard":
			case "canvas":
			case "moodle":
			case "sakai":
			case "schoology":
			case "brightspace":
			case "open_edx":
			case "LTI Certification":
			case "IMS Certification":
			case "1EdTech Certification":
				buf.append("If everything above looks OK, you may proceed with registration. Your registration code is "
					+ "<span style='font-weight:bold;font-size:1.2em;'>" + rc.code + "</span><p>"
					+ "Enter this on the registration page to continue the process and receive your LTI credentials. Or, you can use the button below to enter the code directly.<p>"
					+ "The code is valid for 3 days.<br/><br/>"
					+ "<a href='" + Subject.getServerUrl() + "/lti/registration?reg_code=" + rc.code + "' class='btn btn-primary' >Continue Registration</a><br/><br/>");
				break;
			default: 
				buf.append("ChemVantage is committed to working with all LMS platforms that support LTI Advantage. We will review your registration information and contact you within 1-2 business days to discuss next steps.<br/><br/>");
		}

		buf.append("If you have questions or require assistance, please contact us at admin@chemvantage.org.<p>"
			+ "Thank you,<br/>Chuck Wight<br/>ChemVantage LLC"
		);

		Utilities.sendEmail(rc.name,rc.email,"ChemVantage LTI Registration",buf.toString());
	}

	String clientIdForm(RegistrationCode rc) {
		StringBuffer buf = new StringBuffer(Subject.banner + "<h1>Configuration</h1>");
		
		try {
			String client_id = "";
			String deployment_id = "";
			String platform_id = "";
			String oidc_auth_url = "";
			String oauth_access_token_url = "";
			String well_known_jwks_url = "";
			
			buf.append("<h2>To the LMS Administrator:</h2>"
					+ "By now you should have configured your LMS to connect with ChemVantage, and you should have "
					+ "received a Client ID and Deployment ID from your LMS. Please enter these below, along with the "
					+ "secure URLs (https://) that identify the service endpoints for your LMS. In some cases, these are "
					+ "provided below, but you may need to edit them for your specific situation.<p>"
					+ "<form method=post action=/lti/registration>"
					+ "<input type=hidden name=UserRequest value='finalize'>"
					+ "<input type=hidden name=reg_code value='" + rc.code + "' />");
			
			switch (rc.lms) {
			case "blackboard":
				client_id = (Subject.getProjectId().equals("dev-vantage-hrd")?"ec076e8c-b90f-4ecf-9b5d-a9eff03976be":"be1004de-6f8e-45b9-aae4-2c1370c24e1e");
				platform_id = "https://blackboard.com";
				oidc_auth_url = "https://developer.anthology.com/api/v1/gateway/oidcauth";
				well_known_jwks_url = "https://developer.anthology.com/.well-known/jwks.json";
				oauth_access_token_url = "https://developer.anthology.com/api/v1/gateway/oauth2/jwttoken";
				break;
			case "schoology":
				client_id = (Subject.getProjectId().equals("chem-vantage-hrd")?"6558245496":"");
				platform_id = "https://schoology.schoology.com";
				oidc_auth_url = "https://lti-service.svc.schoology.com/lti-service/authorize-redirect";
				well_known_jwks_url = "https://lti-service.svc.schoology.com/lti-service/.well-known/jwks";
				oauth_access_token_url = "https://lti-service.svc.schoology.com/lti-service/access-token";
				
				buf.append("The Schoology admin can get the Deployment ID value for ChemVantage by clicking the "
						+ "Apps icon > App Center > " + (Subject.getProjectId().equals("chem-vantage-hrd")?"Organization Apps. ":"My Developer Apps. ") 
						+ "Find ChemVantage and select Configure. The Deployment ID should be two large (~10-digit) numbers separated by a hyphen. "
						+ (Subject.getProjectId().equals("dev-vantage-hrd")?"The Client ID value is the first 10-digit number in the Deployment ID.":"") 
						+ "<p>");
				break;
			case "canvas":
				platform_id = "https://canvas.instructure.com";
				oidc_auth_url = "https://sso.canvaslms.com/api/lti/authorize_redirect";
				well_known_jwks_url = "https://sso.canvaslms.com/api/lti/security/jwks";
				oauth_access_token_url = "https://sso.canvaslms.com/login/oauth2/token";
				
				buf.append("Canvas uses the developer key as the Client ID, so enter that value from the list of "
						+ "developer keys. It is a numeric value that looks something like 32570000000000041.<br/>"
						+ "The Deployment ID can be found in Settings | Apps | App Configurations by opening the "
						+ "settings menu for ChemVantage. It is a compound value that consists of a number and a hex string "
						+ "separated by a colon and looks something like 10408:7db438070728c02373713c12c73869b3af470b68.<p>");
				break;
			case "open_edx":
				platform_id = "https://master.openedx.io";
				oidc_auth_url = "https://master.openedx.io/api/lti_consumer/v1/launch/";
				well_known_jwks_url = "https://master.openedx.io/api/lti_consumer/v1/public_keysets/02587f0b-811f-4fa4-91d7-a96eaf2b3906";
				oauth_access_token_url = "https://master.openedx.io/api/lti_consumer/v1/token/02587f0b-811f-4fa4-91d7-a96eaf2b3906";
				break;
			case "IMS Certification":
			case "LTI Certification":
			case "1EdTech Certification":
				platform_id = "https://ltiadvantagevalidator.imsglobal.org";
				oidc_auth_url = "https://ltiadvantagevalidator.imsglobal.org/ltitool/oidcauthurl.html";
				well_known_jwks_url = "https://oauth2server.imsglobal.org/jwks";
				oauth_access_token_url = "https://ltiadvantagevalidator.imsglobal.org/ltitool/authcodejwt.html";
				deployment_id = "testdeploy";
				break;
			default:
			}
			
			buf.append("Client ID: <input type=text size=40 required name=ClientId value='" + client_id + "' /><br>"
					+ "Deployment ID: <input type=text size=40 required name=DeploymentId value='" + deployment_id + "' /><br>"
					+ "Platform ID: <input type=text size=40 required name=PlatformId value='" + platform_id + "' /> (base URL for your LMS)<br>"
					+ "Platform OIDC Auth URL: <input type=text size=40 required name=OIDCAuthUrl value='" + oidc_auth_url + "' /><br>"
					+ "Platform OAuth Access Token URL: <input type=text size=40 required name=OauthAccessTokenUrl value='" + oauth_access_token_url + "' /><br>"
					+ "Platform JSON Web Key Set URL: <input type=text size=40 required name=JWKSUrl value='" + well_known_jwks_url + "' /><br>");

			buf.append("<input type=submit value='Complete the LTI Registration'></form>");	
		} catch (Exception e) {
			buf.append("<h3>Registration Failed</h3>"
					+ e.getMessage() + "<p>"
					+ "Name: " + rc.name + "<br/>"
					+ "Email: " + rc.email + "<br/>"
					+ "Organization: " + rc.org + "<br>"
					+ "Home Page: " + rc.url + "<br/>"
					+ "LMS: " + rc.lms + "<br/><br/>"
					+ "The registration code provided with this link could not be validated. It may have expired (after 3 days) "
					+ "or it may not have contained enough information to complete the registration request. You "
					+ "may <a href=/lti/registration>get a new token</a> by restarting the registration, or contact "
					+ "Chuck Wight (admin@chemvantage.org) for assistance.");
		}		
		return buf.toString();
	}
	
	String createDeployment(HttpServletRequest request, RegistrationCode rc) throws Exception {
		/* Manual Registration */
		String client_id = request.getParameter("ClientId");
		String deployment_id = request.getParameter("DeploymentId");
		String platform_id = request.getParameter("PlatformId");
		String oidc_auth_url = request.getParameter("OIDCAuthUrl");
		String oauth_access_token_url = request.getParameter("OauthAccessTokenUrl");
		String well_known_jwks_url = request.getParameter("JWKSUrl");
		
		if (client_id==null) throw new Exception("Client ID value is required.");
		if (platform_id==null || platform_id.isEmpty()) throw new Exception("Platform ID value is required.");
		if (oidc_auth_url==null || oidc_auth_url.isEmpty()) throw new Exception("OIDC Auth URL is required.");
		if (oauth_access_token_url==null || oauth_access_token_url.isEmpty()) throw new Exception("OAuth Access Token URL is required.");
		if (well_known_jwks_url==null || well_known_jwks_url.isEmpty()) throw new Exception("JSON Web Key Set URL is required.");

		Deployment d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,rc.name,rc.email,rc.org,rc.url,rc.lms);
		d.status = "pending";
		d.price = Subject.getProjectId().equals("dev-vantage-hrd")?0:Integer.parseInt(price);

		Deployment prior = Deployment.getInstance(d.platform_deployment_id);

		String msg = "Your LMS platform deployment has been registered with ChemVantage.<p>";
				
		if (prior!=null) {  // this is a repeat registration
			d.status = prior.status==null?"pending":prior.status;
			if (prior.client_id.equals(d.client_id)) msg += "Note: this platform deployment was registered previously. The registration data have now been updated.<p>";
			else msg += "Note: This platform deployment was registered previously. The client_id and registration data have now been updated. If this is not correct, you should contact admin@chemvantage.org immediately.<p>";
		} else {
			if (Subject.getProjectId().equals("dev-vantage-hrd") || rc.email.equals("admin@chemvantage.org")) {
				d.status = "pending";
				msg += "Your deployment is now active.<br/><br/>";
			} else {
				d.status = "blocked";
				msg += "The deployment is currently BLOCKED while we review the registration details. We will contact you within 1-2 business days when the deployment is set to ACTIVE.<br/><br/>";
			}
		}

		msg += "Contact Chuck Wight at admin@chemvantage.org for support with any questions or issues.<br/><br/>Thank you.";
		
		ofy().save().entity(d).now();  // registration is now complete
		return msg;
	}

	Deployment createNewDeployment(JsonObject openIdConfiguration, JsonObject registrationResponse, RegistrationCode rc) throws Exception {
		/* Dynamic Registration */
		try {
			String platformId = openIdConfiguration.get("issuer").getAsString();
			String clientId = registrationResponse.get("client_id").getAsString();
			String oidc_auth_url = openIdConfiguration.get("authorization_endpoint").getAsString();
			String oauth_access_token_url = openIdConfiguration.get("token_endpoint").getAsString();
			String well_known_jwks_url = openIdConfiguration.get("jwks_uri").getAsString();
			
			String lms = "unknown";
			try {
				lms = openIdConfiguration.get("https://purl.imsglobal.org/spec/lti-platform-configuration").getAsJsonObject().get("product_family_code").getAsString();
			} catch (Exception e) {	
				Utilities.sendEmail("ChemVantage Administrator","admin@chemvantage.org","Dynamic Registration Error: LMS Type Unknown",openIdConfiguration.toString());
			}
	
			String contact_name = rc.name;
			String contact_email = rc.email;
			String organization = rc.org;
			String org_url = rc.url;
			
			String deploymentId = "";  // Most LMS platforms send the deployment_id in the registration response, but it's not required. Thanks, Brightspace and Canvas.
			try {
				deploymentId = registrationResponse.get("https://purl.imsglobal.org/spec/lti-tool-configuration").getAsJsonObject().get("deployment_id").getAsString();
			} catch (Exception e) {}
			
			Deployment d = new Deployment(platformId,deploymentId,clientId,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,contact_email,organization,org_url,lms);
			
			if (d.email.equals("admin@chemvantage.org")) d.status = "approved";
			else if (Subject.getProjectId().equals("dev-vantage-hrd")) d.status = "pending";
			else if (d.lms_type.equals("canvas") || d.lms_type.equals("brightspace") || d.lms_type.equals("desire2learn")) d.status = "pending";
			else d.status = "blocked";

			d.price = Integer.parseInt(price);
			if (deploymentId.isEmpty()) {  // create a provisional deployment to use when an authToken is requested
				ProvisionalDeployment pd = new ProvisionalDeployment(platformId,clientId,contact_name,contact_email,organization,org_url);
				ofy().save().entity(pd);
			} else 	ofy().save().entity(d);  // only save the deployment if it is complete
			return d;
		} catch (Exception e) {
			throw new Exception("Failed to create new deployment in ChemVantage: " + e.toString() + "<br/>OpenId Configuration: " + openIdConfiguration.toString() + "<br/>Registration Response: " + registrationResponse.toString());
		}
	}

	String getConfigurationJson(String iss,String lms) {
		String domain = null;
		try {
			domain = new URI(iss).toURL().getHost();
		} catch (Exception e) { 
			return "Domain was not valid."; 
		}
		
		JsonObject config = new JsonObject();
		config.addProperty("title","ChemVantage" + (iss.contains("dev")?" Development":""));
		config.addProperty("description", "ChemVantage is an Open Education Resource for teaching and learning college-level General Chemistry");;
		config.addProperty("privacy_level", "public");
		config.addProperty("target_link_uri", iss + "/lti/launch");
		config.addProperty("oidc_initiation_url", iss + "/auth/token");
		//config.addProperty("public_jwk_url", iss + "/jwks");
		config.add("public_jwk", KeyStore.getJwk(KeyStore.getAKeyId(lms)));
		  JsonArray scopes = new JsonArray();
		  scopes.add("https://purl.imsglobal.org/spec/lti-ags/scope/lineitem");
		  scopes.add("https://purl.imsglobal.org/spec/lti-ags/scope/lineitem.readonly");
		  scopes.add("https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly");
		  scopes.add("https://purl.imsglobal.org/spec/lti-ags/scope/score");
		  scopes.add("https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly");
		config.add("scopes", scopes);
		  JsonArray extensions = new JsonArray();
		    JsonObject ext = new JsonObject();
		    ext.addProperty("domain", domain);
		    ext.addProperty("platform", "canvas.instructure.com");
		      JsonObject settings = new JsonObject();
		      settings.addProperty("text", "ChemVantage" + (iss.contains("dev")?" Development":""));
		      settings.addProperty("icon_url", iss + "/images/CVLogo_thumb.png");
		        JsonArray placements = new JsonArray();
		         JsonObject plcmnt1 = new JsonObject();
		          plcmnt1.addProperty("text", "ChemVantage" + (iss.contains("dev")?" Development":""));
		          plcmnt1.addProperty("enabled", true);
		          plcmnt1.addProperty("icon_url", iss + "/images/CVLogo_thumb.png");
		          plcmnt1.addProperty("placement", "assignment_selection");
		          plcmnt1.addProperty("message_type", "LtiDeepLinkingRequest");
		          plcmnt1.addProperty("target_link_uri", iss + "/lti/deeplinks");
		        placements.add(plcmnt1);
		         JsonObject plcmnt2 = new JsonObject();
		          plcmnt2.addProperty("text", "ChemVantage" + (iss.contains("dev")?" Development":""));
		          plcmnt2.addProperty("enabled", true);
		          plcmnt2.addProperty("icon_url", iss + "/images/CVLogo_thumb.png");
		          plcmnt2.addProperty("placement", "editor_button");
		          plcmnt2.addProperty("message_type", "LtiDeepLinkingRequest");
		          plcmnt2.addProperty("target_link_uri", iss + "/lti/deeplinks");
		        placements.add(plcmnt2);
		         JsonObject plcmnt3 = new JsonObject();
		          plcmnt3.addProperty("text", "ChemVantage" + (iss.contains("dev")?" Development":""));
		          plcmnt3.addProperty("enabled", true);
		          plcmnt3.addProperty("icon_url", iss + "/images/CVLogo_thumb.png");
		          plcmnt3.addProperty("placement", "link_selection");
		          plcmnt3.addProperty("message_type", "LtiDeepLinkingRequest");
		          plcmnt3.addProperty("target_link_uri", iss + "/lti/deeplinks");
		        placements.add(plcmnt3);
		      settings.add("placements", placements);
		    ext.add("settings", settings);
		  extensions.add(ext);
		config.add("extensions", extensions);
		
		return config.toString();
	}
	
	JsonObject getOpenIdConfiguration(HttpServletRequest request) throws Exception {
	 	// This method retrieves the OpenID Configuration from the platform for Dynamic Registration
    	try {
    		URL u = new URI(request.getParameter("openid_configuration")).toURL();
    		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
    		uc.setDoInput(true);
    		uc.setRequestMethod("GET");
    		uc.connect();
    		int responseCode = uc.getResponseCode();
    		if (responseCode == 200) {
    			BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
    			JsonObject openIdConfiguration = JsonParser.parseReader(reader).getAsJsonObject();
    			reader.close();
    			return openIdConfiguration;
    		} else throw new Exception("Platform returned response code " + responseCode);
    	} catch (Exception e) {
    		throw new Exception("Failed to retrieve OpenID Configuration from platform: " + e.getMessage());
    	}
    }
	
	void validateOpenIdConfigurationURL(String openIdConfigurationURL, JsonObject openIdConfiguration) throws Exception {
		try {
			URL issuer = new URI(openIdConfiguration.get("issuer").getAsString()).toURL();
			URL config = new URI(openIdConfigurationURL).toURL();
			if (!issuer.getProtocol().contains("https")) throw new Exception("Issuer protocol must be https:// ");
			if (!config.getProtocol().contains("https")) throw new Exception("OpenID configuration URL protocol must be https:// ");
			if (!issuer.getHost().equals(config.getHost())) throw new Exception("Host names of issuer and openid_configuration URL must match. ");
			if (config.getRef() != null) throw new Exception("OpenID configuration URL must not contain any fragmant parameter. ");
		} catch (Exception e) {
			throw new Exception("Invalid openid_configuration from " + openIdConfigurationURL + ": " + e.getMessage());
		}		
	}
	
	JsonObject postRegistrationRequest(JsonObject openIdConfiguration,String registration_token) throws Exception {
		
		StringBuffer registrationResponseBuffer = new StringBuffer();
		JsonObject registrationResponse = new JsonObject();;
		JsonObject regJson = new JsonObject();
		StringBuffer debug = new StringBuffer("a");
		try {
		regJson.addProperty("application_type","web");
		JsonArray grantTypes = new JsonArray();
		grantTypes.add("implicit");
		grantTypes.add("client_credentials");
		regJson.add("grant_types", grantTypes);
		JsonArray responseTypes = new JsonArray();
		responseTypes.add("id_token");
		regJson.add("response_types", responseTypes);
		String projectId = Subject.getProjectId();
		String iss = null;
		String domain = null;
		switch (projectId) {
		case "dev-vantage-hrd":
			iss = "https://dev-vantage-hrd.appspot.com";
			domain = "dev-vantage-hrd.appspot.com";
			break;
		case "chem-vantage-hrd":
			iss = "https://www.chemvantage.org";
			domain = "chemvantage.org";
		}
		JsonArray redirectUris = new JsonArray();
		if (iss != null) {
			redirectUris.add(iss + "/lti/launch");
			redirectUris.add(iss + "/lti/deeplinks");
			regJson.add("redirect_uris", redirectUris);
			regJson.addProperty("initiate_login_uri", iss + "/auth/token");
			regJson.addProperty("client_name", "ChemVantage" + (iss.contains("dev-vantage")?" Development":""));
			regJson.addProperty("jwks_uri", iss + "/jwks");
			regJson.addProperty("logo_uri", iss + "/images/CVLogo_thumb.png");
			regJson.addProperty("token_endpoint_auth_method", "private_key_jwt");
			JsonArray contactEmails = new JsonArray();
			contactEmails.add("admin@chemvantage.org");
			regJson.add("contacts", contactEmails);		
			regJson.addProperty("client_uri", iss);
			regJson.addProperty("tos_uri", iss + "/terms_and_conditions.html");
			regJson.addProperty("policy_uri", iss + "/privacy_policy.html");
			regJson.addProperty("scope", "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem https://purl.imsglobal.org/spec/lti-ags/scope/lineitem.readonly https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly https://purl.imsglobal.org/spec/lti-ags/scope/score https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly");
			JsonObject ltiToolConfig = new JsonObject();
			ltiToolConfig.addProperty("domain", domain);
			ltiToolConfig.addProperty("description",  "ChemVantage is an Open Education Resource for General Chemistry.");
			ltiToolConfig.addProperty("target_link_uri", iss + "/lti/launch");
			JsonArray idTokenClaims = new JsonArray();
			idTokenClaims.add("iss");
			idTokenClaims.add("sub");
			idTokenClaims.add("email");
			idTokenClaims.add("name");
			idTokenClaims.add("given_name");
			idTokenClaims.add("family_name");
			ltiToolConfig.add("claims", idTokenClaims);
			debug.append("b");
			JsonArray ltiMessages = new JsonArray();
			JsonObject deepLinking = new JsonObject();
			deepLinking.addProperty("type",  "LtiDeepLinkingRequest");
			deepLinking.addProperty("target_link_uri", iss + "/lti/deeplinks");
			deepLinking.addProperty("label", "ChemVantage" + (iss.contains("dev-vantage")?" Development":""));
		debug.append("c");
		try {
			JsonArray messagesSupported = openIdConfiguration.get("https://purl.imsglobal.org/spec/lti-platform-configuration").getAsJsonObject().get("messages_supported").getAsJsonArray();
			Iterator<JsonElement> iterator = messagesSupported.iterator();
			JsonObject message;
			while (iterator.hasNext()) {
				message = iterator.next().getAsJsonObject();
				if ("LtiDeepLinkingRequest".equals(message.get("type").getAsString()) && message.get("placements")!=null) {
					deepLinking.add("placements", message.get("placements").getAsJsonArray());
					break;
				};
			}
		} catch (Exception e) {
		}	
		ltiMessages.add(deepLinking);
		debug.append("d");
		JsonObject resourceLaunch = new JsonObject();
		resourceLaunch.addProperty("type",  "LtiResourceLinkRequest");
		resourceLaunch.addProperty("target_link_uri", iss + "/lti/launch");
			resourceLaunch.addProperty("label", "ChemVantage" + (iss.contains("dev-vantage")?" Development":""));
			debug.append("e");
			try {
				JsonArray messagesSupported = openIdConfiguration.get("https://purl.imsglobal.org/spec/lti-platform-configuration").getAsJsonObject().get("messages_supported").getAsJsonArray();
				Iterator<JsonElement> iterator = messagesSupported.iterator();
				JsonObject message;
				while (iterator.hasNext()) {
					message = iterator.next().getAsJsonObject();
					if ("LtiResourceLinkRequest".equals(message.get("type").getAsString()) && message.get("placements")!=null) {
						resourceLaunch.add("placements", message.get("placements").getAsJsonArray());
						break;
					};
				}
			} catch (Exception e) {
			}
			ltiMessages.add(resourceLaunch);
			debug.append("f");
			ltiToolConfig.add("messages", ltiMessages);
			regJson.add("https://purl.imsglobal.org/spec/lti-tool-configuration", ltiToolConfig);
		}
		byte[] json_bytes = regJson.toString().getBytes("utf-8");

		String reg_endpoint = openIdConfiguration.get("registration_endpoint").getAsString();
		debug.append("b");

		URL u = new URI(reg_endpoint).toURL();
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setRequestMethod("POST");
		if (registration_token != null) uc.setRequestProperty("Authorization", "Bearer " + registration_token);
		uc.setRequestProperty("Content-Type", "application/json");
		uc.setRequestProperty("Content-Length", String.valueOf(json_bytes.length));
		uc.setRequestProperty("Accept", "application/json");

		try {
			switch (openIdConfiguration.get("https://purl.imsglobal.org/spec/lti-platform-configuration").getAsJsonObject().get("product_family_code").getAsString()) {
			case "moodle": 
				if (iss != null && iss.equals("https://www.chemvantage.org")) uc.setRequestProperty("Host", "www.chemvantage.org"); // prevents code 400 failure in Moodle due to getRemoteHost()->chem-vantage-hrd.appspot.com
				break;
			default:
			}
		} catch (Exception e) {}

		uc.setDoOutput(true);
		uc.setDoInput(true);
		debug.append("c");

		// send the message
		OutputStream os = uc.getOutputStream();
		os.write(json_bytes, 0, json_bytes.length);           
		os.close();
		debug.append("d");

		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			debug.append("e");
			registrationResponse = JsonParser.parseReader(reader).getAsJsonObject();
			debug.append("f");
		} catch (Exception e) {
			debug.append("g ");
			reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
			String line = "";
			while ((line = reader.readLine()) != null) registrationResponseBuffer.append(line);
			debug.append(line);
		}
		if (reader != null) reader.close();
		debug.append("i");
		if (uc.getResponseCode() >= 400) throw new Exception("Platform refused registration request with code " + uc.getResponseCode() + ":<br/>" + registrationResponseBuffer.toString());
		} catch (Exception e) {
			throw new Exception(e.getMessage() + "<br/>Debug: " + debug.toString());
		}
		return registrationResponse;
	}
	
	String successfulRegistrationRequestPage(Deployment d) {
		StringBuffer buf = new StringBuffer(Subject.header() + Subject.banner + "<h1>Dynamic Registration</h1>");
		
		buf.append("<h2>Your ChemVantage Registration Was Successful</h2>"
				+ "The LTI Advantage deployment was created in ChemVantage and in your LMS.<br/>"
				+ "Please be sure to activate the deployment in your LMS.<br/>");

		if (d.status.equals("blocked")) {
			buf.append("<h3>Your Deployment is Currently Under Review</h3>"
					+ "You should receive an email within 1-2 business days when the registration is approved and your deployment is active.<br/><br/>");
		} else {
			buf.append("<h3>Your Deployment is Active</h3>"
					+ "You can start using ChemVantage immediately.<br/>"
					+ "We recommend creating and testing a ChemVantage assignment in a sandbox course.<br/><br/>");
		}
		buf.append("If you have any questions or need assistance, please contact us at admin@chemvantage.org.<br/><br/>");

		buf.append("<button class='btn btn-primary' onclick=\"(window.opener || window.parent).postMessage({subject:'org.imsglobal.lti.close'},'*');\">Click here to close this window.</button><br/><br/>");
		buf.append(Subject.footer);
		return buf.toString();
	}
/* 
		buf.append("<h3>Getting Started</h3>");
		
		switch (d.lms_type) {
		case "moodle":
			buf.append("<h2>For the Instructor</h2>"
					+ "To add ChemVantage assignments to your course, turn editing ON and:<ol>"
					+ "<li>Click 'Add an activity or resource'</li>"
					+ "<li>Click 'External Tool'</li>"
					+ "<li>Select ChemVantage from preconfigured tools and click 'Select content'</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Submit' and then 'Continue'</li>"
					+ "</ol>");
			break;
		case "canvas":
			buf.append("<h2>For the Instructor</h2>"
					+ "To add ChemVantage assignments to your course, go to the Assignments page:<ol>"
					+ "<li>Click the red '+ Assignment' button</li>"
					+ "<li>For Submission Type select 'External Tool'</li>"
					+ "<li>Click Find and select ChemVantage from preconfigured tools</li>"
					+ "<li>Choose a ChemVantage assignment and topic, then click 'Create this assignment' and then 'Select'</li>"
					+ "<li>Click 'Save' or 'Save and Publish'</li>"
					+ "</ol>");
			break;
		case "blackboard":
			buf.append("For the Course Instructor:");
			buf.append("<ol><li>Go to the course | Content | Build Content | ChemVantage</li>"
					+ "<li>Name: as appropriate (e.g., Quiz - Heat & Enthalpy)</li>"
					+ "<li>Grading:"
					+ "<ul><li>Enable Evaluation - Yes</li>"
					+ " <li>Points - 10 for quiz or homework; 5 for video; 100 for practice exam</li>"
					+ " <li>Visible to Students - Yes</li>"
					+ "</ul></li>"
					+ "<li>Submit</li>"
					+ "<li>Click the new assignment link to launch ChemVantage</li>"
					+ "<li>Choose the relevant assignment (e.g., Quiz on Heat & Enthalpy)</li>"
					+ "<li>Customize the assignment, if desired, using the highlighted link</li>"
					+ "</ol>");
			break;
		case "brightspace":
			buf.append("For the Course Instructor:");
			buf.append("<ol>"
					+ "<li>Go to the course | Module</li>"
					+ "<li>Select Existing Activities, then ChemVantage</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Create This Assignment'</li>"
					+ "</ol>");
			break;
		case "schoology":
			buf.append("For the Course Instructor:");
			buf.append("<ol>"
					+ "<li>Go to the course | Materials</li>"
					+ "<li>Select Add Materials, then ChemVantage</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Create This Assignment'</li>"
					+ "</ol>");
			break;
		default:
			buf.append("<h2>For the Course Instructor:</h2>"
					+ "Although we do not have specific instructions for how to add a ChemVantage assignment to your course in " + d.lms_type + ", "
					+ "in general you should navigate to your course page and<ol>"
					+ "<li>Add a new assignment, content or resource</li>"
					+ "<li>Select ChemVantage from a list of preconfigured tools</li>"
					+ "<li>Select one or more ChemVantage assignments to add</li>"
					+ "<li>Enable grading. Recommended points is 10 for quizzes or homework, 100 for practice exams.</li>"
					+ "</ol>");
				break;
		}

		buf.append(	"If you need assistance, contact us at admin@chemvantage.org");
*/
	
	static void sendApprovalEmail(Deployment d) {
		StringBuffer buf = new StringBuffer();
		
		buf.append("<h2>ChemVantage Registration Success</h2>"
				+ "Congratulations! Your LTI registration has been completed:<br/>"
				+ "Tool URL: " + Subject.getServerUrl() + "<br/>"
				+ "LMS Platform: " + d.getPlatformId() + "<br/>"
				+ "Deployment ID: " + d.getDeploymentId() + "<br/>"
				+ "Client ID: " + d.client_id + "<br/><br/>");
		
		if (d.status.equals("blocked")) {
			buf.append("<h3>Your Deployment is Currently Under Review</h3>"
					+ "You should receive an email within 1-2 business days when the registration is approved and your deployment is active.<br/><br/>"
					+ "If you have any questions or need assistance, please contact us at admin@chemvantage.org.");
		} else {
			buf.append("<h3>Your Deployment is Active</h3>"
					+ "Your deployment is now active and you can start using ChemVantage.");
		}

		buf.append("<h3>Getting Started</h3>"
				+ "Instructors may create new placement exams and assignments, or just expolore the site without limitations. "
				+ "Students must purchase a ChemVantage subscription for $" + price + ".00 USD per month to access the assignments. "
				+ "As a reminder, access to ChemVantage by instructors and LMS account administrators is always free.");
		
		buf.append("<h3>Helpful Hints</h3>"
				+ "ChemVantage supports two types of LTI launches from your LMS:<ol>"
				+ "<li>Deep Linking - used by the instructor, course designer or administrator to select ChemVantage assignments.</li>"
				+ "<li>Resource Link - used by students to launch an existing assignment.</li>"
				+ "</ol>"
				+ "You should configure the ChemVantage placements in your LMS with the appropriate locations and permissions.<br/><br/>");
		
		switch (d.lms_type) {
		case "canvas":
			buf.append("To the Course Instructor:<ol>"
					+ "<li>Create a new Canvas assignment with the following recommended parameters:" 
					+ "<ul><li>Name: (as appropriate, e.g. Quiz - Heat and Enthalpy)</li>"
					+ " <li>Points: 10 for quiz or homework; 5 for video; 100 for practice exam</li>"
					+ " <li>Submission Type: External Tool</li>"
					+ " <li>External Tool URL: Find ChemVantage or enter " + Subject.getServerUrl() + "/lti/launch</li>"
					+ " <li>Save or Save and Publish</li>"
					+ "</ul></li>"
					+ "<li>When you launch the assignment, you may use the highlighted link to customize it for your class.</li>"
					+ "</ol>");
			break;
		case "blackboard":
			buf.append("To the Course Instructor:");
			buf.append("<ol><li>Go to the course | Content | Build Content | ChemVantage</li>"
					+ "<li>Name: as appropriate (e.g., Quiz - Heat & Enthalpy)</li>"
					+ "<li>Grading:"
					+ "<ul><li>Enable Evaluation - Yes</li>"
					+ " <li>Points - 10 for quiz or homework; 5 for video; 100 for practice exam</li>"
					+ " <li>Visible to Students - Yes</li>"
					+ "</ul></li>"
					+ "<li>Submit</li>"
					+ "<li>Click the new assignment link to launch ChemVantage</li>"
					+ "<li>Choose the relevant assignment (e.g., Quiz on Heat & Enthalpy)</li>"
					+ "<li>Customize the assignment, if desired, using the highlighted link</li>"
					+ "</ol>");
			break;
		case "moodle":
			buf.append("To the Course Instructor:<ol>"
					+ "<li>On your course page, turn editing on and click 'Add an activity or resource'</li>"
					+ "<li>Click 'External Tool'</li>"
					+ "<li>Select ChemVantage from preconfigured tools and click 'Select content'</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Submit' and then 'Continue'</li>"
					+ "</ol>");
			break;
		case "brightspace":
			buf.append("For the Course Instructor:");
			buf.append("<ol>"
					+ "<li>Go to the course | Module</li>"
					+ "<li>Select Existing Activities, then ChemVantage</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Create This Assignment'</li>"
					+ "</ol>");
			break;
		case "schoology":
			buf.append("For the Course Instructor:");
			buf.append("<ol>"
					+ "<li>Go to the course | Materials</li>"
					+ "<li>Select Add Materials, then ChemVantage</li>"
					+ "<li>Choose one or more ChemVantage assignments, click 'Create This Assignment'</li>"
					+ "</ol>");
			break;
		default:
			buf.append("To the Course Instructor:<br>"
					+ "Although we do not have specific instructions for how to add a ChemVantage assignment to your course in " + d.lms_type + ", "
					+ "in general you should navigate to your course page and<ol>"
					+ "<li>Add a new assignment, content or resource</li>"
					+ "<li>Select ChemVantage from a list of preconfigured tools</li>"
					+ "<li>Select one or more ChemVantage assignments to add</li>"
					+ "<li>Enable grading. Recommended points is 10 for quizzes or homework, 100 for practice exams.</li>"
					+ "</ol>");	
		}
		buf.append("If you need additional assistance, please contact us at admin@chemvantage.org<br/>Thank you.");
		
		try {
			Utilities.sendEmail(d.contact_name,d.email,"ChemVantage Registration",buf.toString());
		} catch (Exception e) {
		}
	}

	@Entity
	static class RegistrationCode {
		@Id String code;
			String name;
			String email;
			String org;
			String url;
			String lms;
		@Index Date expires;
		
		RegistrationCode() {}
		RegistrationCode(String name, String email, String org, String url, String lms) {
			this.code = Long.toHexString(User.encode(new Date().getTime()));
			this.name = name;
			this.email = email;
			this.org = org;
			this.url = url;
			this.lms = lms;
			Calendar c = Calendar.getInstance();
			c.add(Calendar.DATE, 3);  // registration code is valid for 3 days
			this.expires = c.getTime();
		}
	}
}
