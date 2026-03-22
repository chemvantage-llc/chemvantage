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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import jakarta.servlet.http.HttpServletRequest;

public class Logout {

	static String message() {
		return Subject.header()
				+ "<h1>Logout</h1>"
				+ "<h2>You have successfully signed out of ChemVantage</h2>"
				+ "If this happened unexpectedly, it is likely that your browser's web "
				+ "session timed out after a period of inactivity, or the access token "
				+ "exchanged between your learning management system (LMS) and ChemVantage "
				+ "has expired (after a period of typically 90 minutes)."
				+ "<p>"
				+ "You can activate a new session and token by returning to your learning "
				+ "management system (LMS) and clicking the link for any assignment there.<p>"
				+ "If you are having technical difficulty using ChemVantage, <a href=/Feedback>"
				+ "please tell us</a> so we can fix the problem."
				+ Subject.footer;
	}
	
	static String now(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<h1>Logout</h1>");
		String sig = request.getParameter("sig");
		try {
			ofy().delete().entity(User.getUser(sig)).now();
			buf.append("<h2>You are now logged out of ChemVantage</h2>");
		} catch (Exception e) {
			buf.append("<h2>Failed</h2>"
					+ "An attempt to log you out of ChemVantage failed, most likely because you were not logged in at the time.<br/><br/>");
		}
		return buf.toString();
	}

	static String now(HttpServletRequest request,Exception exception) {
		StringBuffer buf = new StringBuffer();
		buf.append("<section class='bg-gradient-primary text-white' style='max-width:800px'>"
			+ "<div class='container py-5 col-lg-7'>"
			+ "  <h1 class='display-5 fw-semibold mb-3'>401&nbsp;(Unauthorized)</h1>"
			+ "</div>"
			+ "</section><br/>");
		
		buf.append(exception.getMessage()==null?exception.toString():exception.getMessage());
		String sig = request.getParameter("sig");
		try {
			Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
			sig = JWT.require(algorithm).build().verify(sig).getSubject();
			ofy().delete().key(key(User.class, Long.parseLong(sig))).now();	
			buf.append("<h2>You are now logged out of ChemVantage</h2>"
				+ "If this happened unexpectedly, it is likely that the access token "
				+ "exchanged between your learning management system (LMS) and ChemVantage "
				+ "has expired (after a period of typically 90 minutes)."
				+ "<p>You can launch the assignment again by clicking the assignment "
				+ "link in your learning management system (LMS).<p>"
				+ "If you are having technical difficulty using ChemVantage, "
				+ "<a href=/Feedback>please tell us</a> so we can fix the problem.");
		} catch (Exception e) {
			buf.append("<h2>You are now logged out of ChemVantage</h2>");
			buf.append("If you are having technical difficulty using ChemVantage, "
					+ "<a href=/Feedback>please tell us</a> so we can fix the problem.");
		}
		
		return buf.toString();
	}

	static String now(User user) {
		StringBuffer buf = new StringBuffer(Subject.header() + Subject.banner);
		buf.append("<h1>Logout</h1>");
		try {
			ofy().delete().entity(user).now();
		} catch (Exception e) {}
		buf.append("<h2>You are now logged out of ChemVantage.</h2>");
		buf.append(Subject.footer);
		return buf.toString();
	}

}