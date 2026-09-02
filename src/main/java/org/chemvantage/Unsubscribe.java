package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.Serial;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.googlecode.objectify.Key;

@WebServlet("/unsubscribe")
public class Unsubscribe extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
    
	public Unsubscribe() {
        super();
    }

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		boolean signMeUpAgain = Boolean.parseBoolean(request.getParameter("s"));
		StringBuffer buf = new StringBuffer(Subject.header() + "<h1>ChemVantage</h1>");
		try {
			Key<Contact> k = Key.valueOf(request.getParameter("k"));
			Contact c = ofy().load().key(k).safe();
			if (signMeUpAgain) {
				buf.append("<h2>Thanks. You are now subscribed to occasional ChemVantage messages.</h2>");
				c.unsubscribed = false;
				ofy().save().entity(c);
			} else {  // unsubscribe request
				if (c.unsubscribed) buf.append("<h2>You remain unsubscribed from ChemVantage messages.</h2>");
				else {
					c.unsubscribed = true;
					//c.vetted = true;
					ofy().save().entity(c);
					buf.append("<h2>You have been successfully unsubscribed from ChemVantage messages.</h2>");
				}
				String keyParameter = URLEncoder.encode(request.getParameter("k"), StandardCharsets.UTF_8);
				buf.append("<a href='/unsubscribe?k=" + keyParameter + "&amp;s=true'>Wait! I changed my mind. Please sign me up again.</a><br/><br/>");
			}
		} catch (Exception e) {
			buf.append("<h2>Sorry, we were unable to find your record in our system.</h2>");
		}
		buf.append(Subject.footer);
		response.getWriter().println(buf.toString());
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

}
