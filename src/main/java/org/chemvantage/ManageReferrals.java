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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/rewards/*")
public class ManageReferrals extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet handles referral rewards requests for ChemVantage adoption.";
	}

	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			// Extract the 8-character hex code from the URL path
			String pathInfo = request.getPathInfo();
			String referralCode = null;
			
			if (pathInfo != null && pathInfo.length() > 1) {
				referralCode = pathInfo.substring(1); // Remove leading slash
				// Validate that it's an 8-character hex string
				if (!referralCode.matches("[0-9a-fA-F]{8}")) {
					out.println(Subject.header("Invalid Referral Code") 
							+ "<p>The referral code is invalid.</p>" 
							+ Subject.footer);
					return;
				}
			} else {
				out.println(Subject.header("Missing Referral Code") 
						+ "<p>A valid referral code is required.</p>" 
						+ Subject.footer);
				return;
			}
			
			try {  // Check if this is a verification request (has referral id parameter)
				String referralIdParam = request.getParameter("id");
				Long referralId = Long.parseLong(referralIdParam);
				Referral referral = ofy().load().type(Referral.class).id(referralId).safe();
				if (!referral.getReferralCode().equals(referralCode)) throw new Exception("Referral code does not match.");
				referral.setIsVerified(true);
				ofy().save().entity(referral).now();
			
				out.println(Subject.header("Email Verified") 
				+ Subject.banner
				+ thankYouSection(referral.getName())
				+ Subject.footer);
				return;
			} catch (Exception e) {  //Show the referral form
				out.println(Subject.header("ChemVantage Adoption")
				+ Subject.banner
				+ rewardForm(referralCode) 
				+ Subject.footer);
				return;
			}
		} catch (Exception e) {
			response.getWriter().println(Subject.header("Error") 
					+ "<p>An error occurred: " + e.getMessage() + "</p>" 
					+ Subject.footer);
		}
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String referralCode = request.getParameter("ReferralCode").toLowerCase();
			String name = request.getParameter("Name");
			String email = request.getParameter("Email");
			String orgName = request.getParameter("OrgName");
			String orgHomePage = request.getParameter("OrgHomePage");
			
			// Validate input fields
			if (referralCode == null || referralCode.isEmpty() ||
				name == null || name.isEmpty() ||
				email == null || email.isEmpty() ||
				orgName == null || orgName.isEmpty() ||
				orgHomePage == null || orgHomePage.isEmpty()) {
				
				out.println(Subject.header("Missing Required Fields") 
						+ "<p>Please fill in all required fields.</p>" 
						+ Subject.footer);
				return;
			}
			
			// Validate referral code format
			if (!referralCode.matches("[0-9a-f]{8}")) {
				out.println(Subject.header("Invalid Referral Code") 
						+ "<p>The referral code is invalid.</p>" 
						+ Subject.footer);
				return;
			}
			
			// Create and store the Referral entity
			Referral referral = new Referral(referralCode, name, email, orgName, orgHomePage);
			ofy().save().entity(referral).now();
			
			// Send verification email
			sendVerificationEmail(name, email, referralCode, referral.id);
			
			out.println(Subject.header("Thank You") 
				+ Subject.banner
				+ "<section class='bg-gradient-primary text-white' style='max-width:600px'>"
				+ "  <div class='container py-5'>"
				+ "    <div class='col-lg-7'>"
				+ "      <h1 class='display-5 fw-semibold mb-3'>Email Verification</h1>"
				+ "    </div>"
				+ "  </div>"
				+ "</section>"
				+ "<p>We sent a verification email to " + email + "<br/>"
				+ "Please check your email and click the link to verify your email address.<br/>"
				+ "If it doesn't come within a few minutes, please check your spam or junk folder, or try resubmitting the form.<br/>"
				+ "If THAT doesn't resolve the issue, just contact us at admin@chemvantage.org and we'll get it sorted out.</p>" 
				+ Subject.footer);
		} catch (Exception e) {
			response.getWriter().println(Subject.header("Error") 
					+ "<p>An error occurred while processing your request: " + e.getMessage() + "</p>" 
					+ Subject.footer);
		}
	}

	String rewardForm(String referralCode) {
		StringBuffer buf = new StringBuffer();
		buf.append("<section class='bg-gradient-primary text-white' style='max-width:600px'>"
				+ "   <div class='container py-5'>"
				+ "     <div class='col-lg-7'>"
				+ "       <h1 class='display-5 fw-semibold mb-3'>Welcome</h1>"
				+ "       <p>Thank you for your interest in adopting ChemVantage for your General Chemistry class. "
				+ "         Please provide your contact information below to verify your email and institutional affiliation."
				+ "         This will also help us to recognize and reward the person who referred you to us.</p>"
				+ "     </div>"
				+ "   </div>"
				+ " </section><p>");
		
		buf.append("<form method='post' action='/rewards' style='max-width:500px'>"
				+ "  <div class='mb-3'>"
				+ "    <label for='Name' class='form-label'>Your Name:</label>"
				+ "    <input type='text' class='form-control' id='Name' name='Name' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='Email' class='form-label'>Your Email:</label>"
				+ "    <input type='email' class='form-control' id='Email' name='Email' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='OrgName' class='form-label'>Your school, business or organization:</label>"
				+ "    <input type='text' class='form-control' id='OrgName' name='OrgName' placeholder='Org Name' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='OrgHomePage' class='form-label'>Home Page:</label>"
				+ "    <input type='url' class='form-control' id='OrgHomePage' name='OrgHomePage' placeholder='https://myschool.edu' required>"
				+ "  </div>"
				+ "  <input type='hidden' name='ReferralCode' value='" + referralCode + "'>"
				+ "  <button type='submit' class='btn btn-primary'>Submit</button>"
				+ "</form>");
		
		return buf.toString();
	}

	String thankYouSection(String name) {
		return "<section class='bg-gradient-primary text-white' style='max-width:600px'>"
				+ "  <div class='container py-5'>"
				+ "    <div class='row'>"
				+ "      <div class='col-lg-7'>"
				+ "        <h1 class='display-5 fw-semibold mb-3'>Success</h1>"
				+ "        Thank you, " + name + "! Your email address has been verified."
				+ "      </div>"
				+ "    </div>"
				+ "  </div>"
				+ "</section><p>"
				+ "<p>We appreciate your interest in adopting ChemVantage for your General Chemistry class.</p>"
				+ "<p>When your school or institution activates a new ChemVantage account, all students will be eligible for a one-semester free trial subscription (regular price: $8). "
				+ "Hurry! Offer ends September 30, 2026. See the official <a href='https://www.chemvantage.org/rewards_terms.html'>Referral Program Terms and Conditions.</a></p>"
				+ "<p>Next steps:<ul>"
				+ "  <li>Schedule a live demo of ChemVantage with our team by visiting <a href='https://calendly.com/chemvantage'>our Calendly calendar</a>.</li>"
				+ "  <li>Ask your instiutution's LMS account administrator to <a href='https://www.chemvantage.org/install.html'>install ChemVantage in your LMS</a> as an LTI Advantage tool</a>.</li>"
				+ "  <li>Learn more about ChemVantage by visiting our home page at <a href='https://www.chemvantage.org'>www.chemvantage.org</a>.</li>"
				+ "</ul></p>";
	}
	

private void sendVerificationEmail(String name, String email, String referralCode, Long referralId) {
		try {
			String baseURL = Subject.getProjectId().equals("dev-vantage-hrd") ? "https://dev-vantage-hrd.appspot.com" : "https://www.chemvantage.org";
			String verificationLURL = baseURL + "/rewards/" + referralCode + "?id=" + referralId;
			
			String emailBody = "<h2>Verify Your Email</h2>"
					+ "<p>Dear " + name + ",</p>"
					+ "<p>Thank you for your interest in ChemVantage. Please click the link below to verify your email address:</p>"
					+ "<p><a href='" + verificationLURL + "'>Verify Email</a></p>"
					+ "<p>If you did not initiate this request, please disregard this email.</p>"
					+ "<span style='display:none'>unsubscribe</span>"  // prevents copy to admin@chemvantage.org
					+ "<p>Best regards,<br/>The ChemVantage Team</p>";
			
			Utilities.sendEmail(name, email, "ChemVantage Email Verification", emailBody);
		} catch (IOException e) {
			System.err.println("Error sending verification email: " + e.getMessage());
		}
	}
}
