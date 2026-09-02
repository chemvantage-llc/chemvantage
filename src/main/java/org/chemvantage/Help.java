/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2020 ChemVantage LLC
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
import java.io.PrintWriter;
import java.io.Serial;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@WebServlet(urlPatterns = {"/Help","/help"})
public class Help extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
    /**
     * This servlet allows students to seek help on a Homework assignment by obtaining a Java Web Token (JWT)
     * that can be emailed to an instructor as part of a URL pointing to this servlet. Possession of a valid
     * JWT allows that person (any person) to view the question item (parameterized for the particular student),
     * as well as all of the student's answer submission and the current value of the showWork field.
     * 
     * If the instructor is currently logged into the LMS and connected to ChemVantage, they can enter the URL
     * into a field. The servlet checks the validity of the instructor role and domain, and then allows the
     * instructor to see the student's proposed solution side-by-side with the ChemVantage solution.
     */
	
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// This is the entry point for a URL from any unverified source to view the student submissions
    	PrintWriter out = response.getWriter();
    	response.setContentType("text/html");
    	response.setCharacterEncoding("UTF-8");
    	String jwt = request.getParameter("JWT");
    	
    	if (jwt==null) out.println(Subject.header("ChemVantage Help Page") + "<h1>Help Page</h1>" + "The ChemVantage Help Page is only "
    			+ "accessible to users through our LTI interface. To use this service, please login through your school "
    			+ "learning management system." + Subject.footer);
		try {
    		JsonObject payload = validateJWT(jwt); // throws exception if expired or otherwise invalid
    		out.println(showStudentSubmission(payload));
    	} catch (Exception e) {
    		response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
    	}
		
    }

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// This is the entry point for authenticated students to request a tokenized URL to get help with a problem.
		// Required elements are:
		// 1) valid id_token (Token)
		// 2) assignmentType (AssignmentType)
		// 3) transactionId  (TransactionId)
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception();
			
			String jwt = createJWT(request);
			
			PrintWriter out = response.getWriter();
			response.setContentType("text/html");
			String iss = "https://" + request.getServerName();
			
			out.println(Subject.header("ChemVantage Help Page") + "<h1>Help Page</h1>" + displayHelpUrl(jwt,iss) + Subject.footer);
			
		} catch (Exception e) {
			response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}
	
	protected JsonObject validateJWT(String jwt) throws Exception {
		// This method verifies that the token is properly signed and has not expired
		DecodedJWT token = JWT.decode(jwt);            // throws JWTDecodeException if not a valid JWT
		Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
		JWT.require(algorithm).build().verify(token);  // throws JWTVerificationException if not valid		
		JsonObject payload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(token.getPayload()))).getAsJsonObject();
		return payload;
	}

	protected String showStudentSubmission(JsonObject payload) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("ChemVantage Help Page") + "<h1>Help Page</h1>");
		Date exp = new Date(payload.get("exp").getAsLong()*1000L);

		buf.append("<h2>A student is seeking your help with a ChemVantage assignment.</h2>");
		buf.append("The information below may help to illuminate the problem. This information may contain updates since "
				+ "the student made this request (i.e., they may have solved the problem on their own).<p>"
				+ "The token contained in the URL you just entered is only valid for 3 days, and will expire at "
				+ exp + ". Please communicate your response directly to the student, not through ChemVantage.<br/><br/>");
		/*
		 * The expected contents of the payload are:
		 *  1) aid (Assignment ID: long)
		 *  2) tid (Transaction ID: long)
		 *  3) exp (Expires: 3 days after issuance)
		 *  4) hsh (int hashcode for question parameters)
		 */

		HWTransaction hwt = ofy().load().type(HWTransaction.class).id(payload.get("tid").getAsLong()).safe();
		List<HWTransaction> hwTransactions = ofy().load().type(HWTransaction.class).filter("userId",hwt.userId).filter("assignmentId",hwt.assignmentId).order("graded").list();
		hwt = hwTransactions.get(hwTransactions.size()-1); // loads the most recent transaction, regardless of the one in the token

		String title = "<h3>Homework Assignment</h3>";
		try {
			Assignment a = ofy().load().type(Assignment.class).id(hwt.assignmentId).safe();
			title = "<h3>Assignment: Homework - " + org.springframework.web.util.HtmlUtils.htmlEscape(a.title) + "</h3>";
		} catch (Exception e) {}		
		buf.append(title);

		Question q = ofy().load().type(Question.class).id(hwt.questionId).safe();
		int hashCode = -1;
		try {
			hashCode = payload.get("hsh").getAsInt();
		} catch (Exception e) {}
		q.setParameters(hashCode);  // creates different parameters for different assignments
		buf.append(q.print(hwt.showWork,""));

		buf.append("<script>document.getElementById('showWork" + q.id + "').style.display='';</script>");

		Date solved = null;
		StringBuffer tablebuf = new StringBuffer();
		if (hwTransactions.size() > 0) {
			tablebuf.append("<table><tr><td>Date/Time (UTC)</td><td>Student Response</td><td>Score</td></tr>");
			for (HWTransaction hwtr : hwTransactions) {
				if (hwtr.assignmentId==0) continue;
				if (hwtr.score>0 && (solved==null || solved.after(hwtr.graded))) solved = hwtr.graded;
				tablebuf.append("<tr><td>" + hwtr.graded.toString() + "</td><td align=center>" + hwtr.studentAnswer 
						+ "</td><td align=center>" + hwtr.score + "</td></tr>");	
			}
			tablebuf.append("</table><br>");
		}
		if (solved != null) buf.append("<font color=red><b>Note: This question was answered correctly on " + solved + "</b></font><p>");
		buf.append(tablebuf);

		buf.append(Subject.footer);

		return buf.toString();
	}
	
	protected String createJWT(HttpServletRequest request) throws Exception {
		long tid = Long.parseLong(request.getParameter("TransactionId"));
		int hsh = Integer.parseInt(request.getParameter("HashCode"));
		
		Date now = new Date();
		Date in3Days = new Date(now.getTime() + 259200000L);
		Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
		   
		String token = JWT.create()
				.withClaim("tid", tid)
				.withClaim("hsh", hsh)
				.withExpiresAt(in3Days)
				.sign(algorithm);
		
		return token;
	}
	
	protected String displayHelpUrl(String jwt, String server) {
		StringBuffer buf = new StringBuffer();
		Date exp = new Date(JWT.decode(jwt).getExpiresAt().getTime());
		
		buf.append("<h3>Need Some Help?</h3>");
		buf.append("Here's what to do: Copy the message below, including the entire URL, and send it with your question via email "
				+ " to your instructor or teaching assistant, or even a friend. They will be able to view your responses to the question, including "
				+ "anything that you may have written in the box the was labeled: 'Show your work here'. You can use the link "
				+ "yourself to verify exactly what information your instructor will see.<p>"
				+ "Your email should try to explain why you're stuck, if possible. When solving homework problems, it is important "
				+ "to show your work in the box provided, because that's helpful to the instructor for diagnosing the issue.<p>");
		
		buf.append("<hr><p>");
		
		buf.append("From ChemVantage to the instructor:<br>"
				+ "The student sending this message is having difficulties solving a problem in ChemVantage. Please click the link "
				+ "below to view the question item and the student's responses. The link is only valid for 3 days and expires at " + exp + ".<p>");
		
		String url = server + "/help?JWT=" + jwt;
		
		buf.append("<a href=" + url + ">" + url + "</a>");
		
		buf.append("<p>Thank you.");
		return buf.toString();
	}
}
