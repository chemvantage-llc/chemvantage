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

import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.cloud.ServiceOptions;
import com.googlecode.objectify.NotFoundException;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;

@Entity
public class Subject {
	private static final Logger logger = Logger.getLogger(Subject.class.getName());
	private static final int REFRESH_RETRY_LIMIT = 5;
	private static final long REFRESH_RETRY_DELAY_MS = 200L;
	private static final String PRIVACY_BANNER_EXPIRY_DATE = "2026-05-16"; // 30 days from policy update

	@Id Long id;
	private static Subject s;
	
	private String title;
	private String HMAC256Secret;
	private String reCaptchaKey;
	private String openai_key;
	private String salt;
	private String announcement;
	private String sendGridAPIKey;
	private int nStarReports;
	private double avgStars;
	private String projectId;
	private String serverUrl;
	private String gptModel;  // e.g. "gpt-4-0613"
	private String gemModel;  // e.g. "gemini-2.5-pro"
	private String payPalClientId;
	private String payPalClientSecret;
	
	private Subject() {}

	private static Subject createDefaultSubject() {
		Subject fallback = new Subject();
		fallback.id = 1L;
		fallback.title = "General Chemistry";
		fallback.HMAC256Secret = "ChangeMeInTheDatastoreManuallyForYourProtection";
		fallback.salt = "ChangeMeInTheDatastoreManuallyForYourProtection";
		fallback.reCaptchaKey = "changeMe";
		fallback.openai_key = "changeMe";
		fallback.gptModel = "changeMe";
		fallback.gemModel = "changeMe";
		fallback.sendGridAPIKey = "changeMe";
		fallback.payPalClientId = "changeMe";
		fallback.payPalClientSecret = "changeMe";
		fallback.projectId = ServiceOptions.getDefaultProjectId();
		if (fallback.projectId == null || fallback.projectId.isBlank()) fallback.projectId = "localhost";
		fallback.serverUrl = switch (fallback.projectId) {
			case "dev-vantage-hrd" -> "https://dev-vantage-hrd.appspot.com";
			case "localhost" -> "http://localhost:8080";
			default -> "https://www.chemvantage.org";
		};
		return fallback;
	}
	
	private static synchronized void refresh() {
		if (s != null) return;

		for (int attempt = 1; attempt <= REFRESH_RETRY_LIMIT; attempt++) {
			try {
				s = ofy().load().type(Subject.class).id(1L).safe();
				return;
			} catch (NotFoundException e) {  // runs only once at inception of datastore
				s = createDefaultSubject();
				try {
					ofy().save().entity(s).now();
				} catch (Exception saveException) {
					logger.log(Level.WARNING, "Unable to persist default Subject during initialization.", saveException);
				}
				return;
			} catch (Exception e) {  // ofy() may not be ready during classloading
				if (attempt == REFRESH_RETRY_LIMIT) {
					s = createDefaultSubject();
					logger.log(Level.WARNING, "Subject datastore initialization unavailable; using fallback defaults.", e);
					return;
				}
				try {
					Thread.sleep(REFRESH_RETRY_DELAY_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					s = createDefaultSubject();
					logger.log(Level.WARNING, "Interrupted while waiting for Subject initialization; using fallback defaults.", ie);
					return;
				}
			}
		}
	}
	
	static String getTitle() {
		if (s==null) refresh();
		return s.title; 
	}
	
	static String getHMAC256Secret() { 
		if (s==null) refresh();
		return s.HMAC256Secret; 
	}

	static String getReCaptchaKey() {
		if (s==null) refresh();
		return s.reCaptchaKey;
	}
	
	static String getSalt() { 
		if (s==null) refresh();
		return s.salt; 
	}
	
	static String getAnnouncement() { 
		if (s==null) refresh();
		return s.announcement; 
	}
	
	static String getSendGridKey() {
		if (s==null) refresh();
		return s.sendGridAPIKey;
	}
	
	static String getPayPalClientId() {
		if (s==null) refresh();
		return s.payPalClientId;
	}
	
	static String getPayPalClientSecret() {
		if (s==null) refresh();
		return s.payPalClientSecret;
	}
	
	static int getNStarReports() { 
		if (s==null) refresh();
		return s.nStarReports; 
	}
	
	static void setAnnouncement(String msg) {
		if (s==null) refresh();
		s.announcement = msg;
		ofy().save().entity(s);
	}
	
	static double getAvgStars() {
		DecimalFormat df2 = new DecimalFormat("#.#");
		if (s==null) refresh();
		return Double.valueOf(df2.format(s.avgStars));
	}
	
	static void addStarReport(int stars) {
		if (s==null) refresh();
		s.avgStars = (s.avgStars*s.nStarReports + stars)/(s.nStarReports+1);
		s.nStarReports++;
		ofy().save().entity(s);
	}
		
	static String hashId(String userId) {
		try {
			if (s==null) refresh();
			MessageDigest md = MessageDigest.getInstance("SHA-256");
        	byte[] bytes = md.digest((userId + s.salt).getBytes("UTF-8"));
        	StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append("%02x".formatted(b));
            }
            return sb.toString();
		} catch (Exception e) {
        	return null;
        }
	}
	
	static String getGPTModel() {  // GPT-4.0 is "gpt-4-0613", GPT-3.5 is "gpt-3.5-turbo-0613"
		if (s==null) refresh(); 
		return s.gptModel;
	}
	
	static String getGemModel() { // Gemini is Google's family of LLMs, e.g. "gemini-2.5-pro"
		if (s==null) refresh(); 
		return s.gemModel;
	}
	
	static String getOpenAIKey() {
		if (s==null) refresh();
		return s.openai_key;
	}
	
	static String getProjectId() {
		if (s==null) refresh();
		return s.projectId;
	}
	
	static String getServerUrl() {
		if (s==null) refresh();
		return s.serverUrl;
	}
	
	public static String header() {
		return header("","");
	}

	static String header(String title) {
		return header(title, "");
	}
	
	static String header(String title, String customJSFile) {
		StringBuffer buf = new StringBuffer();
		String announcement = Subject.getAnnouncement();
		buf.append("<!DOCTYPE html>\n"
		+ "<html lang='en'>\n"
		+ "<head>\n"
		+ "  <meta charset='UTF-8'>\n"
		+ "  <title>ChemVantage" + (title==null || title.isEmpty()?"":" | " + title) + "</title>\n"
		+ "  <meta name='viewport' content='width=device-width, initial-scale=1'>\n"
		+ "  <meta name='description' content='ChemVantage provides standards-aligned homework, quizzes, and exams for General Chemistry with automatic grading and seamless LMS integration using LTI 1.3 Advantage.'>\n"
		+ "  <meta http-equiv='Cache-Control' content='no-cache, no-store, must-revalidate' />\n"
		+ "  <meta http-equiv='Pragma' content='no-cache' />\n"
		+ "  <meta http-equiv='Expires' content='0' />\n"
		+ "  <link rel='icon' href='images/logo_sq.png'>\n"
		+ "  <link rel='preconnect' href='https://fonts.googleapis.com' crossorigin>\n"
		+ "  <link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>\n"
		+ "  <link rel='stylesheet' href='https://fonts.googleapis.com/css2?family=Poppins:wght@100;200;300;400;500;600;700;900&family=Shantell+Sans:wght@300;400;500;600;700;800&display=swap'>\n"
		+ "  <link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css' integrity='sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH' crossorigin='anonymous'>\n"
		+ "  <script src='/js/script-backend.js?v=1'></script>\n"
		+ "  <style>\n"
		+ "    body {\n"
		+ "      font-family: 'Poppins', Arial, sans-serif;\n"
		+ "    }\n"
		+ "    .has-padding {\n"
		+ "      padding: 20px;\n"
		+ "    }\n"
		+ "    .bg-gradient-primary {\n"
		+ "      background: linear-gradient(180deg, #0b5ed7 0%, #0d6efd 100%);\n"
		+ "    }\n"
		+ "    .section-heading + p.lead {\n"
		+ "      max-width: 56rem;\n"
		+ "    }\n"
		+ "    /* Ensure visible focus for keyboard users on minimal devices */\n"
		+ "    :focus-visible {\n"
		+ "      outline: 2px solid blue;\n"
		+ "      outline-offset: 2px;\n"
		+ "    }\n"
		+ "  </style>\n");
		
		switch (customJSFile) {
		case "checkout":
			buf.append("<script src='https://www.paypal.com/sdk/js?client-id=" + getPayPalClientId() + "&enable-funding=venmo&disable-funding=paylater'></script>");
			buf.append("<script src='/js/checkout.js?r=" + new Random().nextInt() + "'></script>");
			break;
		}
		
		buf.append("</head>\n"
		+ "<body class='bg-white text-body' style='padding: 10px'><main id='main-content'>\n"
		+ (announcement==null || announcement.isEmpty()?"":"<FONT style='color: #B20000'>" + announcement + "</FONT><br/>\n")
		);
		
		return buf.toString();
	}
	
	public static String getHeader(User user) {
		String announcement = Subject.getAnnouncement();
		String sig = user.getTokenSignature();
		return "<!DOCTYPE html>\n"
		+ "<html lang='en'>\n"
		+ "<head>\n"
		+ "  <meta charset='UTF-8'>\n"
		+ "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n"
		+ "  <meta http-equiv='Cache-Control' content='no-cache, no-store, must-revalidate' />\n"
		+ "  <meta http-equiv='Pragma' content='no-cache' />\n"
		+ "  <meta http-equiv='Expires' content='0' />\n"
		+ "  <link rel='icon' href='images/logo_sq.png'>\n"
		+ "  <title>ChemVantage</title>\n"
		+ "  <link rel='preconnect' href='https://fonts.googleapis.com' crossorigin>\n"
		+ "  <link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>\n"
		+ "  <link rel='stylesheet' href='https://fonts.googleapis.com/css2?family=Poppins:wght@100;200;300;400;500;600;700;900&family=Shantell+Sans:wght@300;400;500;600;700;800&display=swap'>\n"
		+ "  <link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css' integrity='sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH' crossorigin='anonymous'>\n"
		+ "  <script src='/js/script-backend.js?v=1'></script>\n"
		+ "  <style>\n"
		+ "    body { font-family: 'Poppins', Arial, sans-serif; }\n"
		+ "    .has-padding {"
		+ "      padding: 50px;"
		+ "    }"
		+ "    .bg-gradient-primary {\n"
		+ "      background: linear-gradient(180deg, #0b5ed7 0%, #0d6efd 100%);\n"
		+ "    }\n"
		+ "    .section-heading + p.lead {\n"
		+ "      max-width: 56rem;\n"
		+ "    }\n"
		+ "    /* Ensure visible focus for keyboard users on minimal devices */\n"
		+ "    :focus-visible {\n"
		+ "      outline: 3px solid #fd7e14;\n"
		+ "      outline-offset: 2px;\n"
		+ "    }\n"
		+ "  </style>\n"
		+ "</head>"
		+ "<body class='bg-white text-body' style='padding: 10px'>\n"
		+ "  <a href='#main-content' class='visually-hidden-focusable position-absolute start-0 top-0 m-2 p-2  rounded text-dark'>Skip to main content</a>\n"
		+ "  <div style='padding: 20px;'>"
		+ "    <a href=/ style='padding-right:25px'>Home</a> "
		+ "    <a href='/Feedback?sig=" + sig + "' style='padding-right:25px'>Feedback</a> "
		+ "    <a href='/Contribute?sig=" + sig + "' style='padding-right:25px'>Authors</a> "
		+ "    <a href='/Edit?sig=" + sig + "' style='padding-right:25px'>Editors</a> "
		+ "    <a href='/Admin?sig=" + sig + "' style='padding-right:25px'>Admin</a> "
		+ "    <a href='/contacts' style='padding-right:25px'>Contacts</a> "
		+ "    <a href='/messages' style='padding-right:25px'>Messages</a> "
		+ "  </div><br/>"
		+ ((announcement==null || announcement.isEmpty())?"":"<FONT style='color: #B20000'>" + announcement + "</FONT><br/>\n"
		+ "<main id='main-content'>");
	}
	
	static String banner = "<div style='font-size:2em;font-weight:bold;color:#000080;'><img src='https://images.chemvantage.org/CVLogo_thumb.png' alt='ChemVantage Logo' style='vertical-align:middle;width:60px;'> ChemVantage</div>";
	
	static String privacyPolicyBanner() {
		try {
			java.time.LocalDate today = java.time.LocalDate.now();
			java.time.LocalDate expiryDate = java.time.LocalDate.parse(PRIVACY_BANNER_EXPIRY_DATE);
			if (today.isAfter(expiryDate) || today.isEqual(expiryDate)) {
				return ""; // Campaign expired, don't send banner HTML
			}
		} catch (Exception e) {
			return ""; // If date parsing fails, don't show banner
		}
		
		return """
			<div id='privacyBanner' style='display:none;background-color:#fff3cd;border:1px solid #ffc107;border-radius:5px;padding:15px;margin:10px 0;position:relative;'>
				<button onclick='dismissPrivacyBanner()' style='position:absolute;top:10px;right:10px;background:none;border:none;font-size:24px;cursor:pointer;color:#856404;' aria-label='Close'>&times;</button>
				<div style='margin-right:30px;'>
					<strong>Privacy Policy Update:</strong> We've updated our <a href='/privacy.html' target='_blank' style='color:#856404;text-decoration:underline;'>Privacy Policy</a> to provide greater transparency about how we handle educator contact information. Student privacy protections remain unchanged. We never store student PII.
				</div>
			</div>
			<script>
			(function() {
				var dismissed = localStorage.getItem('privacyBannerDismissed');
				if (!dismissed) {
					document.getElementById('privacyBanner').style.display = 'block';
				}
			})();
			function dismissPrivacyBanner() {
				document.getElementById('privacyBanner').style.display = 'none';
				localStorage.setItem('privacyBannerDismissed', new Date().toISOString());
			}
			</script>
			""";
	}
			
	public static String footer = """
			
			</main>
			<footer id=footer style='max-width: 600px;'><hr/>\
			<a style='text-decoration:none;color:#000080;font-weight:bold' href=/index.html><img src=https://images.chemvantage.org/logo_sq.png alt='ChemVantage logo' style='vertical-align:middle;width:30px;' /> ChemVantage</a> | \
			<a href=/terms_and_conditions.html>Terms and Conditions</a> | \
			<a href=/privacy.html>Privacy</a> | \
			<a href=/copyright.html>Copyright</a></footer>\
			<script>if (window===window.top)document.body.classList.add('has-padding');</script>
			</body>
			</html>""";

}
