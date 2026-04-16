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
import java.util.Random;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/rewards/*")
public class Rewards extends HttpServlet {

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
			
			out.println(Subject.header("ChemVantage Adoption Rewards Program") 
					+ rewardForm(referralCode) 
					+ Subject.footer);
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
			
			String referralCode = request.getParameter("ReferralCode");
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
			if (!referralCode.matches("[0-9a-fA-F]{8}")) {
				out.println(Subject.header("Invalid Referral Code") 
						+ "<p>The referral code is invalid.</p>" 
						+ Subject.footer);
				return;
			}
			
			// Create and store the Referral entity
			Referral referral = new Referral(referralCode, name, email, orgName, orgHomePage);
			ofy().save().entity(referral);
			
			// Generate a validation token
			String validationToken = generateValidationToken();
			
			// Send validation email
			sendValidationEmail(name, email, referralCode, validationToken);
			
			out.println(Subject.header("Thank You") 
					+ "<p>Thank you for your interest in ChemVantage! "
					+ "We have sent a validation email to " + email + ". "
					+ "Please check your email and click the link to validate your email address.</p>" 
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
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>ChemVantage Adoption Rewards</h1>"
				+ "            <p>Thank you for your interest in adopting ChemVantage for your General Chemistry class. "
				+ "            Please provide your contact information to verify your email and institutional affiliation.</p>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		
		buf.append("<form method='post' action='/rewards/" + referralCode + "' style='max-width:500px'>"
				+ "  <div class='mb-3'>"
				+ "    <label for='Name' class='form-label'>Name:</label>"
				+ "    <input type='text' class='form-control' id='Name' name='Name' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='Email' class='form-label'>Email:</label>"
				+ "    <input type='email' class='form-control' id='Email' name='Email' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='OrgName' class='form-label'>Your school, business or organization:</label>"
				+ "    <input type='text' class='form-control' id='OrgName' name='OrgName' placeholder='Org Name' required>"
				+ "  </div>"
				+ "  <div class='mb-3'>"
				+ "    <label for='OrgHomePage' class='form-label'>Home Page:</label>"
				+ "    <input type='url' class='form-control' id='OrgHomePage' name='OrgHomePage' required>"
				+ "  </div>"
				+ "  <input type='hidden' name='ReferralCode' value='" + referralCode + "'>"
				+ "  <button type='submit' class='btn btn-primary'>Submit</button>"
				+ "</form>");
		
		return buf.toString();
	}

	private String generateValidationToken() {
		// Generate a random token (could use UUID or random string)
		Random random = new Random();
		StringBuilder token = new StringBuilder();
		String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		for (int i = 0; i < 32; i++) {
			token.append(characters.charAt(random.nextInt(characters.length())));
		}
		return token.toString();
	}

	private void sendValidationEmail(String name, String email, String referralCode, String validationToken) {
		try {
			String baseURL = Subject.getProjectId().equals("dev-vantage-hrd") ? "https://dev-vantage-hrd.appspot.com" : "https://www.chemvantage.org";
			String validationLink = baseURL + "/ValidateReferral?code=" + referralCode 
					+ "&token=" + validationToken;
			
			String emailBody = "<h2>Verify Your Email</h2>"
					+ "<p>Dear " + name + ",</p>"
					+ "<p>Thank you for your interest in ChemVantage. Please click the link below to verify your email address:</p>"
					+ "<p><a href='" + validationLink + "'>Validate Email</a></p>"
					+ "<p>If you did not initiate this request, please disregard this email.</p>"
					+ "<p>Best regards,<br/>The ChemVantage Team</p>";
			
			Utilities.sendEmail(name, email, "ChemVantage Email Validation", emailBody);
		} catch (IOException e) {
			System.err.println("Error sending validation email: " + e.getMessage());
		}
	}
}
