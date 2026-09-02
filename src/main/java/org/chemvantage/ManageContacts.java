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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.web.util.HtmlUtils;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.googlecode.objectify.Key;

/*
 * Access to this servlet is restricted to admin@chemvantage.org.
 */
@WebServlet("/contacts")
public class ManageContacts extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
       
    public ManageContacts() {
        super();
    }
   
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			// Defense-in-depth: verify admin authentication
			if (!isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, 
					"Admin access required");
				logSecurityEvent("UNAUTHORIZED_ADMIN_ACCESS_ATTEMPT", request);
				return;
			}
			
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);
		//user.setToken();
		
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Contacts</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		int nContacts = ofy().load().type(Contact.class).count();
		int nUnsubscribed = ofy().load().type(Contact.class).filter("unsubscribed",true).count();
		
		buf.append("There are " + nContacts + " contacts in the database, " + nUnsubscribed + " of whom are unsubscribed.");

		boolean clean = Boolean.parseBoolean(request.getParameter("Clean"));
		if (clean) {
			buf.append(cleanContacts());
		}
		
		buf.append(searchContacts());
		
		
		String email = request.getParameter("Email");
		if (email!=null) {
			// This search works by retrieving Contact keys and examining the embedded name (email) for partial matches
			List<Key<Contact>> contactKeys = ofy().load().type(Contact.class).limit(1000).keys().list();
			List<Key<Contact>> matchingContactKeys = new ArrayList<Key<Contact>>();
			while (contactKeys.size()>0) {
				for (Key<Contact> k : contactKeys) {
					if (k.getName().toLowerCase().contains(email.toLowerCase())) matchingContactKeys.add(k);
				}
				Key<Contact> lastKey = contactKeys.get(contactKeys.size()-1);
				contactKeys = ofy().load().type(Contact.class).filterKey(">", lastKey).limit(1000).keys().list();
			}
			if (matchingContactKeys.size()>0) {  // allow editing
				buf.append(editExistingContact(ofy().load().key(matchingContactKeys.get(0)).now()));
			}
			if (matchingContactKeys.size()>1) {
				buf.append("There are " + matchingContactKeys.size() + " contacts matching " + email + ":<br/>");
				if (matchingContactKeys.size()>50) matchingContactKeys.subList(50, matchingContactKeys.size()).clear();
				matchingContactKeys.forEach((key) -> buf.append(key.getName() + "<br/>"));
				buf.append("<br/>");
			}
		}
		buf.append(addNewContact());
		buf.append(pasteNewContact());
		buf.append(exportCSVFile());
			
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		out.println(Subject.getHeader(user) + buf.toString() + Subject.footer);
		} catch (Exception e) {
			response.setContentType("text/html");
			response.getWriter().println("Error: " + e.getMessage());
		}
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
			
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);
		//user.setToken();
		
		String userRequest = request.getParameter("UserRequest");
		String email = request.getParameter("Email");
		if (email != null) email = email.trim().toLowerCase();
		try {
			switch (userRequest) {
			case "Add New Contact":
				addNewContact(request);
				response.sendRedirect("/contacts?Email=" + email);
				break;
			case "Paste New Contact":
				email = pasteNewContact(request);
				response.sendRedirect("/contacts?Email=" + email);
			case "Save Revised Contact":
				saveRevisedContact(request);
				response.sendRedirect("/contacts?Email=" + request.getParameter("Email"));
				break;
			case "Delete This Contact":
				deleteContact(request);
				response.sendRedirect("/contacts?Email=" + request.getParameter("Email"));
				break;
			case "Export CSV File":
				exportCSVFile(request,response);
				break;
			default: throw new Exception("Bad User request.");
			}
		} catch (Exception e) {
			response.getWriter().println("Operation Failed. " + e.toString() + " " + e.getMessage());
		}
		} catch (Exception e) {
			response.setContentType("text/html");
			response.getWriter().println("Error: " + e.getMessage());
		}
	}

	String addNewContact() {
		return "<h4>Add New Contact</h4>"
				+ "<form method=post action=/contacts>"
				+ "<input type=text name=FirstName placeholder='First Name' /> "
				+ "<input type=text name=LastName placeholder='Last Name' />"
				+ "<input type=text name=Email placeholder='Email' /><br/>"
				+ "Role: <select name=Role>"
				+ "<option value=''>Unknown</option>"
				+ "<option value=faculty selected>Faculty</option>"
				+ "<option value=chair>Dept Chair</option>"
				+ "</select><br/>"
				+ "<input type=submit name=UserRequest value='Add New Contact' />"
				+ "</form><br/><br/>";
	}
	
	String pasteNewContact() {
		return """
				<h4>Paste New Faculty Contacts</h4>\
				<form method=post action=/contacts>\
				<textarea rows=10 cols=80 name=Paste placeholder='First Name [tab] Last Name [tab] Email
				First Name [tab] Last Name [tab] Email' ></textarea> \
				<br/>\
				<input type=hidden name=Role value=faculty />\
				<input type=submit name=UserRequest value='Paste New Contact' />\
				</form><br/><br/>"""; 
	}
	
	String exportCSVFile() {
		return "<h4>Export CSV File</h4>"
				+ "<form method=post action=/contacts>"
				+ "<input type=radio name=Domain value=All checked /> All contacts<br/>"
				+ "<input type=radio name=Domain value=Summary /> Summary by domain<br/>"
				+ "<input type=submit name=UserRequest value='Export CSV File' /><br/><br/>";
	}
	
	void addNewContact(HttpServletRequest request) throws Exception {
		Contact c = null;
		String email = request.getParameter("Email").trim().toLowerCase();
		try {
			c = ofy().load().type(Contact.class).id(email).safe();
			c.firstName = request.getParameter("FirstName");
			c.lastName = request.getParameter("LastName");
		} catch (Exception e) {
			c = new Contact(request.getParameter("FirstName"),request.getParameter("LastName"),email);
		}
		c.role = request.getParameter("Role");
		c.vetted = true;
		ofy().save().entity(c).now();
	}
	
	String pasteNewContact(HttpServletRequest request) {
		String url = "";
		Contact c = null;
		try {
			String[] lines = request.getParameter("Paste").split("\n");
			for (int i=0;i<lines.length;i++) {
				String[] paste = lines[i].split("\t");
				c = new Contact(paste[0].trim(),paste[1].trim(),paste[2].trim().toLowerCase());
				c.role = request.getParameter("Role");
				c.vetted = true;
				ofy().save().entity(c).now();
				url += (i==0?c.email:"&Email=" + c.email);
			}
		} catch (Exception e) {
		}
		return url;
	}
	
	void exportCSVFile(HttpServletRequest request,HttpServletResponse response) throws IOException {
		String domain = request.getParameter("Domain");
		List<Contact> contacts = ofy().load().type(Contact.class).limit(1000).list();
		PrintWriter out = response.getWriter();
		response.setContentType("text/csv");
		
		switch (domain) {
		case "All":
			out.println("\"Firstname\",\"Lastname\",\"Email\",\"Unsubscribed\"");
			while (contacts.size()>0) {
				for (Contact c : contacts) {
					out.println("\"" + c.firstName + "\",\"" + c.lastName + "\",\"" + c.email + "\"" + (c.unsubscribed?",\"unsubscribed\"":""));
				}
				String email = contacts.get(contacts.size()-1).email;
				contacts = ofy().load().type(Contact.class).filterKey(">",key(Contact.class,email)).limit(1000).list();
			}
			break;
		case "Summary":
			out.println("\"Domain\",\"NContacts\"");
			Map<String,Integer> domainMap = new HashMap<String,Integer>();
			while (contacts.size()>0) {
				for (Contact c : contacts) {
					String domainName = c.email.substring(c.email.indexOf("@") + 1);
				domainMap.merge(domainName, 1, (a, b) -> a + b);
				}
				String email = contacts.get(contacts.size()-1).email;
				contacts = ofy().load().type(Contact.class).filterKey(">",key(Contact.class,email)).limit(1000).list();
			}
			for (Entry<String,Integer> e : domainMap.entrySet()) out.println("\"" + e.getKey() + "\"," + e.getValue());
			break;
		}
	}
	
	String editExistingContact(Contact c) {
		return "<h4>Edit Contact</h4>"
				+ "<form method=post action=/contacts>"
				+ "<input type=text name=FirstName value='" + HtmlUtils.htmlEscape(c.firstName == null ? "" : c.firstName) + "' /> "
				+ "<input type=text name=LastName value='" + HtmlUtils.htmlEscape(c.lastName == null ? "" : c.lastName) + "' />"
				+ "<input type=text name=Email value='" + HtmlUtils.htmlEscape(c.email == null ? "" : c.email) + "' /><br/>"
				+ "Role: <select name=Role>"
				+ "<option value=''>Unknown</option>"
				+ "<option value='faculty'" + ("faculty".equals(c.role)?" selected":"") + ">Faculty</option>"
				+ "<option value='chair'" + ("chair".equals(c.role)?" selected":"") + ">Dept. Chair</option>"
				+ "</select>"
				+ "<input type=checkbox name='Unsubscribed' value=true" + (c.unsubscribed?" checked":"") + ">Unsubscribed<br/>"
				+ "<input type=submit name=UserRequest value='Save Revised Contact' />&nbsp;"
				+ "<input type=submit name=UserRequest value='Delete This Contact' />"
				+ "</form><br/><br/>";
	}
	
	void saveRevisedContact(HttpServletRequest request) throws Exception {
		try {
			Contact c = ofy().load().type(Contact.class).id(request.getParameter("Email")).safe();
			c.firstName = request.getParameter("FirstName");
			c.lastName = request.getParameter("LastName");
			c.role = request.getParameter("Role");
			c.vetted = true;
			c.unsubscribed = Boolean.parseBoolean(request.getParameter("Unsubscribed"));
			ofy().save().entity(c).now();
		} catch (Exception e) {
			addNewContact(request);
		}
	}
	
	void deleteContact(HttpServletRequest request) {
		ofy().delete().type(Contact.class).id(request.getParameter("Email")).now();
	}
	
	String searchContacts() {
		return "<h4>Search Contacts</h4>"
				+ "<form method=get action=/contacts>"
				+ "Email: <input type=text name=Email />&nbsp;<input type=submit value='Search' />"
				+ "</form><br/><br/>";
	}
	
	String cleanContacts() {
		StringBuffer buf = new StringBuffer("<h4>The following contacts were modified by trimming the ID value</h4>");
		List<Key<Contact>> contactKeys = ofy().load().type(Contact.class).limit(500).keys().list();  // 500 is max allowed by ofy().delete()
		while (contactKeys.size()>0) {
			List<Key<Contact>> toDeleteKeys = new ArrayList<Key<Contact>>();
			for (Key<Contact> k : contactKeys) {
				String id = k.getName();
				if (id.equals(id.trim())) continue;  // everything is OK
				else toDeleteKeys.add(k);
			}
			Map<Key<Contact>,Contact> contacts = ofy().load().keys(toDeleteKeys);
			List<Contact> toSaveContacts = new ArrayList<Contact>();
			for (Map.Entry<Key<Contact>,Contact> entry : contacts.entrySet()) {
				Contact c = entry.getValue();
				c.email = c.email.trim().toLowerCase();
				toSaveContacts.add(c);
				buf.append(c.email + "<br/>");
			}
			ofy().save().entities(toSaveContacts);
			ofy().delete().keys(toDeleteKeys);
			Key<Contact> lastKey = contactKeys.get(contactKeys.size()-1);
			contactKeys = ofy().load().type(Contact.class).filterKey(">", lastKey).limit(500).keys().list();
		}
		return buf.toString();
	}

	private boolean isAdminAuthenticated(HttpServletRequest request) {
		return Utilities.isAdminAuthenticated(request);
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
