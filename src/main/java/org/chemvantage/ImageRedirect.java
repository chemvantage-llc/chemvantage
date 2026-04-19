package org.chemvantage;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet to handle permanent (301) redirects from old /images/* URLs 
 * to the new GCP Storage Bucket at https://images.chemvantage.org/*
 * 
 * This maintains backward compatibility for clients that cached the old URLs.
 */
@WebServlet("/images/*")
public class ImageRedirect extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final String NEW_IMAGE_BASE = "https://images.chemvantage.org";
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		// Extract the path after /images
		String pathInfo = request.getPathInfo();
		if (pathInfo == null) pathInfo = "";
		
		// Construct the new URL
		String newUrl = NEW_IMAGE_BASE + pathInfo;
		
		// Send 301 Moved Permanently redirect
		response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
		response.setHeader("Location", newUrl);
		response.setHeader("Cache-Control", "public, max-age=31536000"); // Cache redirect for 1 year
	}
	
	@Override
	protected void doHead(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		doGet(request, response);
	}
}
