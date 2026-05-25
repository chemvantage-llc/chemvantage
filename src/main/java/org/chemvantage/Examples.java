package org.chemvantage;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.Date;
import java.util.Random;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@WebServlet("/examples")
public class Examples extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet is used to launch example ChemVantage assignments.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		String launch = request.getParameter("launch");
		if (launch != null) {
			if (!isAllowedLaunchPath(launch)) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			response.sendRedirect(launch + (launch.contains("?") ? "&" : "?") + "sig=" + getSig());
			return;
		}

		response.setContentType("application/json");
		PrintWriter out = response.getWriter();
		out.println("{\"sig\":\"" + getSig() + "\"}");
		out.flush();
		out.close();
	}

	private static boolean isAllowedLaunchPath(String launch) {
		return launch.equals("/Homework")
				|| launch.equals("/Quiz")
				|| launch.equals("/VideoQuiz")
				|| launch.startsWith("/VideoQuiz?");
	}
	
	static String getSig() {
		long encrypt = new Date().getTime() + 5400000L;
		try {
			long mask = 0xfffL;
			long iv = encrypt & mask;
			long code;
			for (int i=0;i<3;i++) { 
				code = (mask & encrypt) << 12;
				encrypt = encrypt ^ code;
				mask = mask << 12;
			}
			mask = 0xfffffffff000L;
			encrypt = encrypt ^ (new Random(iv).nextLong() & mask); 
			mask = 0xfffffffffL;
			code = (encrypt & mask) << 12;
			encrypt = encrypt ^ code;  
		} catch (Exception e) {
			encrypt = 0L;
		}
		return Long.toHexString(encrypt);
	}
}
