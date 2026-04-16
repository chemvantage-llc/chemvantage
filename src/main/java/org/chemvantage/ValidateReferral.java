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

@WebServlet("/ValidateReferral")
public class ValidateReferral extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet handles email validation for referral rewards.";
	}

	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String referralCode = request.getParameter("code");
			String validationToken = request.getParameter("token");
			
			if (referralCode == null || referralCode.isEmpty() ||
				validationToken == null || validationToken.isEmpty()) {
				
				out.println(Subject.header("Invalid Validation Request") 
						+ "<p>The validation request is missing required parameters.</p>" 
						+ Subject.footer);
				return;
			}
			
			// Look up the Referral entity
			Referral referral = ofy().load().type(Referral.class).id(referralCode).now();
			
			if (referral == null) {
				out.println(Subject.header("Referral Not Found") 
						+ "<p>The referral code does not exist.</p>" 
						+ Subject.footer);
				return;
			}
			
			// Set isValidated to true
			referral.setIsValidated(true);
			ofy().save().entity(referral);
			
			out.println(Subject.header("Email Validated") 
					+ "<p>Thank you! Your email address has been successfully validated.</p>"
					+ "<p>We appreciate your interest in ChemVantage. Our team will contact you shortly to discuss "
					+ "adoption options for your General Chemistry class.</p>" 
					+ Subject.footer);
		} catch (Exception e) {
			response.getWriter().println(Subject.header("Error") 
					+ "<p>An error occurred while validating your email: " + e.getMessage() + "</p>" 
					+ Subject.footer);
		}
	}
}
