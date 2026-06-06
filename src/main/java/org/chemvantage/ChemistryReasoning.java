package org.chemvantage;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = {"/chemistry-reasoning", "/chemistry-reasoning/"})
public class ChemistryReasoning extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest request,HttpServletResponse response) throws ServletException, IOException {
		if ("/chemistry-reasoning".equals(request.getRequestURI())) {
			response.setStatus(HttpServletResponse.SC_FOUND);
			response.setHeader("Location", "/chemistry-reasoning/");
			return;
		}
		request.getRequestDispatcher("/chemistry-reasoning/index.html").forward(request, response);
	}
}
