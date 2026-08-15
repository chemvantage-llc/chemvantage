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

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/*
 * Access to this servlet is restricted to admin@chemvantage.org.
 */
@WebServlet("/ValidateQuestions")
public class ValidateQuestions extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
    private static final int BATCH_SIZE = 20;
    
	public String getServletInfo() {
		return "This servlet validates question items in the datastore using ChatGPT.";
	}

	public void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			if (!Utilities.isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required");
				return;
			}
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			StringBuffer buf = new StringBuffer();
			buf.append(Subject.header());
			buf.append("<h1>Question Validation Tool</h1>");
			buf.append("<p>This tool validates question items in the datastore by sending them to ChatGPT in batches of " + BATCH_SIZE + ".</p>");
			
			if ("StartValidation".equals(userRequest)) {
				try {
					buf.append(startValidationTask(request, response));
					buf.append("<p><a href='/ValidateQuestions'>Back</a></p>");
				} catch (Exception e) {
					buf.append("<p style='color: red;'>Error starting validation: " + e.getMessage() + "</p>");
				}
			}
			// Show status and options
			buf.append("<form method='get'>");
			buf.append("<input type='hidden' name='UserRequest' value='StartValidation'>");
			buf.append("<input type='submit' value='Continue'>");
			buf.append("</form>");
			
			buf.append(Subject.footer);
			out.println(buf.toString());
		} catch (Exception e) {
			System.err.println("DEBUG: Exception in doGet: " + e.getMessage());
			e.printStackTrace();
			response.setContentType("text/html");
			response.getWriter().println("Error: " + e.getMessage());
		}
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
	    try {
			if (!Utilities.isAdminAuthenticated(request)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required");
				return;
			}

			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
            switch (userRequest) {
                case "MarkAsValid":
                    String questionIdStr = request.getParameter("QuestionId");
                    if (questionIdStr != null) {
                        long questionId = Long.parseLong(questionIdStr);
                        Question q = ofy().load().type(Question.class).id(questionId).now();
                        if (q != null) {
                            q.checkedByAI = true;
                            ofy().save().entity(q).now();
                            response.sendRedirect("/ValidateQuestions?UserRequest=StartValidation");
							return;
                        } else {
                            out.println("<p style='color: red;'>Question not found.</p>");
                        }
                    } else {
                        out.println("<p style='color: red;'>No QuestionId provided.</p>");
                    }
                    out.println("<p><a href='/ValidateQuestions'>Back to Validation Tool</a></p>");
                    break;
                default:
                    out.println("<p style='color: red;'>Unknown UserRequest: " + userRequest + "</p>");
                    out.println("<p><a href='/ValidateQuestions'>Back to Validation Tool</a></p>");
                    break;
            }
		} catch (Exception e) {
            System.err.println("DEBUG: Exception in doPost: " + e.getMessage());
            e.printStackTrace();
            response.setContentType("text/html");
            response.getWriter().println("Error: " + e.getMessage());
        }
    }

	/**
	 * Starts the validation task by queuing the first question
	 */
	private String startValidationTask(HttpServletRequest request, HttpServletResponse response) throws Exception {
		StringBuffer buf = new StringBuffer();
		
		int offset = ofy().load().type(Question.class).filter("checkedByAI",true).count();
		// Load questions starting from offset - don't filter at datastore level
		List<Question> allQuestions = ofy().load().type(Question.class).offset(offset).limit(BATCH_SIZE).list();
		List<Question> uncheckedQuestions = new ArrayList<Question>();
		try {
			for (Question q : allQuestions) {
				// Include questions where checkedByAI is false or has default value (false for booleans)
				if (!q.checkedByAI) {
					uncheckedQuestions.add(q);
				}
			}
			buf.append("Checking " + uncheckedQuestions.size() + " questions (starting at offset " + offset + ")...<p></p>");

        List<Question> validatedQuestions = new ArrayList<Question>();

        if (uncheckedQuestions.size() == 0) {
            buf.append("<p style='color: green;'><strong>All questions have already been validated!</strong></p>");
            return buf.toString();
        }

        for (Question q : uncheckedQuestions) {
            try {
                String validation = validateQuestionWithChatGPT(q);
                if (validation.equalsIgnoreCase("VALID")) {
                    // Mark as checked
                    q.checkedByAI = true;
                    validatedQuestions.add(q);
                    buf.append("Question " + q.id + ": <span style='color: green;'>VALID</span><br/>");
                } else {
                    buf.append("Question " + q.id + ": <span style='color: red;'>INVALID</span> "
                        + "<a href='/Edit?UserRequest=Edit&QuestionId=" + q.id + "'>Edit</a> " + validation + "<p></p>"
                        + q.printForSage() + "Answer: " + q.getCorrectAnswerForSage() + "<p></p>"
                        + "<form method=post action='/ValidateQuestions'>"
                        + "<input type=hidden name=UserRequest value='MarkAsValid'>"
                        + "<input type=hidden name=QuestionId value='" + q.id + "'>"
                        + "<input type=submit value='Mark as VALID'>"
                        + "</form>");
                    
                    break;
                }
            } catch (Exception e) {
                System.err.println("DEBUG: Error validating question " + q.id + ": " + e.getMessage());
                e.printStackTrace();
                buf.append("<p style='color: red;'>Error validating question " + q.id + ": " + e.getMessage() + "</p>");
                try {
                    buf.append(q.printForSage() + "Answer: " + q.getCorrectAnswerForSage() + "<p></p>");
                } catch (Exception e2) {
                    buf.append("<p style='color: red;'>Could not display question details: " + e2.getMessage() + "</p>");
                }
                break;
            }
        };
        
        if (validatedQuestions.size() > 0) {
            ofy().save().entities(validatedQuestions).now();
        }
        
        // Check if there are more questions beyond this batch
        List<Question> nextBatchCheck = ofy().load().type(Question.class).offset(offset + BATCH_SIZE * 5).limit(1).list();
        if (nextBatchCheck.size() > 0) {
            // Display button to continue to next batch
            int nextOffset = offset + BATCH_SIZE * 5;
            buf.append("<p style='margin-top: 20px;'><strong>Batch complete.</strong></p>");
            buf.append("<form method='get'>");
            buf.append("<input type='hidden' name='UserRequest' value='StartValidation'>");
            buf.append("<input type='hidden' name='Offset' value='" + nextOffset + "'>");
            buf.append("<input type='submit' value='Continue to Next Batch'>");
            buf.append("</form>");
        } else {
            buf.append("<p style='color: green;'><strong>All questions have been validated!</strong></p>");
        }
        
        return buf.toString();
        } catch (Exception e) {
            throw new Exception(buf.toString() + "<br/>Error during validation: " + e.getMessage());
        }
	}

	/**
	 * Validates a single question using ChatGPT
	 * Returns true if the question and answer are valid, false otherwise
	 */
	private String validateQuestionWithChatGPT(Question q) throws Exception {
		// Prepare the validation prompt
		try {
            if(q.requiresParser()) q.setParameters();
			String questionText = q.printForSage();
			String answerText = q.getCorrectAnswerForSage();
			
			if (questionText == null || questionText.isEmpty()) {
				throw new Exception("Question text is empty");
			}
			if (answerText == null || answerText.isEmpty()) {
				throw new Exception("Answer text is empty");
			}
	        
	        JsonObject api_request = new JsonObject();
			api_request.addProperty("model", Subject.getGPTModel());
			JsonObject prompt = new JsonObject();
			prompt.addProperty("id", "pmpt_69504bb26d688194aadc39da02c6e6a0006cd482ecca43c0");
			JsonObject variables = new JsonObject();
			variables.addProperty("question_item", questionText);
	        variables.addProperty("answer", answerText);
			prompt.add("variables", variables);
			api_request.add("prompt", prompt);
		
			// Send request to OpenAI API
			URL u = new URI("https://api.openai.com/v1/responses").toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setRequestMethod("POST");
			uc.setDoInput(true);
			uc.setDoOutput(true);
			uc.setRequestProperty("Authorization", "Bearer " + Subject.getOpenAIKey());
			uc.setRequestProperty("Content-Type", "application/json");
			uc.setRequestProperty("Accept", "application/json");
			
			OutputStream os = uc.getOutputStream();
			byte[] json_bytes = api_request.toString().getBytes("utf-8");
			os.write(json_bytes, 0, json_bytes.length);
			os.close();
			
			int response_code = uc.getResponseCode();
			
			JsonObject api_response = null;
			BufferedReader reader = null;
			if (response_code / 100 == 2) {
				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
				api_response = JsonParser.parseReader(reader).getAsJsonObject();
				reader.close();
			} else {
				reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
				api_response = JsonParser.parseReader(reader).getAsJsonObject();
				reader.close();
				throw new Exception("OpenAI API error: " + api_response.toString());
			}
			
			// Extract response text
			try {
			    JsonArray output = api_response.get("output").getAsJsonArray();
			    JsonObject message = null;
			    JsonObject output_text = null;
		    	String validation = null;
		    	for (JsonElement element0 : output) {
			    	message = element0.getAsJsonObject();
			        if (message.has("content")) {
					    JsonArray content = message.get("content").getAsJsonArray();
					    for (JsonElement element1 : content) {
				    		output_text = element1.getAsJsonObject();
				    		if (output_text.has("text")) {
				    			validation = output_text.get("text").getAsString();
					    		break;
					    	}
					    }
					    break;
				    }
		        }
                return validation;
			} catch (Exception e) {
				throw new Exception("Error parsing OpenAI response: " + e.getMessage());
			}
		} catch (Exception e) {
			System.err.println("DEBUG: Exception in validateQuestionWithChatGPT for question " + q.id + ": " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
}