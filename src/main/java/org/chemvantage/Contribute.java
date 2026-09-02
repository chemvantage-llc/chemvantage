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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/Contribute")
public class Contribute extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet is used by users to contribute new Quiz and Homework questions.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
			
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null || !user.isInstructor()) throw new Exception("You must be logged in through your LMS to as an instructor to use this utility.");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			switch (userRequest) {
			default:
				out.println(Subject.header("Upload Question Items") + uploadQuestionsForm(user) + Subject.footer);		
			}
		} catch (Exception e) {
			out.println(Subject.header("Upload Question Items") + e.getMessage() + Subject.footer);
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();

		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null || !user.isInstructor()) throw new Exception("You must be logged in through your LMS to as an instructor to use this utility.");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			switch (userRequest) {
			default:
				out.println(Subject.header("Upload Question Items") + processBatchUpload(user,request) + Subject.footer);
			}
		} catch (Exception e) {
			out.println(Subject.header("Upload Question Items") + e.getMessage() + Subject.footer);
		}
	}

	String uploadQuestionsForm(User user) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Upload Question Items</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		
				buf.append("You can upload many custom questions simultaneously by pasting a JSON array below. All custom questions are sequestered for "
				+ "your exclusive use in your ChemVantage assignments.<br/><br/>The JSON array must be formatted as follows:<br/>"
				+ "Each member of the array must be a JSON object with the fields:<ul>"
				+ "<li>concept - optional string that identifies the key concept for the question item; if omitted, ChemVantage will attempt to assign one automatically using AI</li>"
				+ (user.isEditor()?
					"<li>assignmentType - required string that identifies the assignment type for the question item (e.g., Quiz, Homework)</li>":"")
				+ "<li>type - required string that identifies the type of question item (MULTIPLE_CHOICE, TRUE_FALSE, SELECT_MULTIPLE, FILL_IN_WORD, NUMERIC, ESSAY)</li>"
				+ "<li>text - required string that contains the main prompt of the question item</li>"
				+ "<li>choices - optional JSON array of up to 5 strings (required for MULTIPLE_CHOICE and SELECT_MULTIPLE types)</li>"
				+ "<li>nChoices - optional integer that indicates the number of choices in the choices array (required for MULTIPLE_CHOICE and SELECT_MULTIPLE types)</li>"
				+ "<li>scrambleChoices - optional boolean that indicates whether the choices should be scrambled (default is false)</li>"
				+ "<li>correctAnswer - string representing the correct response, choices (\"b\" or \"acd\"), numeric value (\"4.25E-6\") or boolean (not used for ESSAY type)</li>"
				+ "<li>tag - optional string ending the question item for FILL_IN_WORD or required units for NUMERIC questions</li>"
				+ "</ul>"
				+ "Download the <a href='/docs/question-json-ingest.md'>detailed instructions for the JSON format</a>.<br/>You should be able to "
				+ "use this file with an AI agent to convert your text file to the required JSON array format and paste it in the form below.<br/>");
		buf.append("<form method=post action=/Contribute >"
				+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
				+ "<input type=hidden name=UserRequest value=Batch />"
				+ "<textarea id=box name=QuestionJson rows=20 cols=80 placeholder = 'Paste your JSON array here'></textarea></br>"
				+ "<input type=submit onclick=replaceCurlies(); >" 
				+ "</form><p>");
		
		buf.append("<script>"
				+ "function replaceCurlies() {"
				+ "  var box = document.getElementById('box');"
				+ "  box.value = box.value.replace(/\\u201c/g,'\"').replace(/\\u201d/g,'\"').replace(/\\u2019/g,'&apos;');"
				+ "}"
				+ "</script>");
				
		return buf.toString();
	}
	
	String processBatchUpload(User user, HttpServletRequest request) {
		List<Concept> concepts = ofy().load().type(Concept.class).list();
		Map<String,Long> conceptIds = new HashMap<String,Long>();
		for (Concept c : concepts) conceptIds.put(c.title, c.id);
		List<Question> questions = new ArrayList<Question>();

		StringBuffer buf = new StringBuffer();
		String json = null;
		Question q = null;
		try {
			json = request.getParameter("QuestionJson");
			
			JsonArray questionArray = JsonParser.parseString(json).getAsJsonArray();
			for (int i=0;i<questionArray.size();i++) {
				try {
					JsonObject question = questionArray.get(i).getAsJsonObject();
					if (question.has("concept")) {
						Long conceptId = conceptIds.get(question.get("concept").getAsString());
						if (conceptId != null) question.addProperty("conceptId", conceptId);
						question.remove("concept");
					}
					if (user.isEditor()) {
						q = new Gson().fromJson(question, ProposedQuestion.class);
						q.authorId = user.getId();
						q.pointValue = 1;
					} else {
						q = new Gson().fromJson(question, Question.class);
						q.assignmentType = "Custom";
						q.isActive = true;
						q.authorId = user.getId();
						q.pointValue = 1;
					}
					if (q.type == null) throw new Exception("Missing required field: type");
					if (q.text == null || q.text.isEmpty()) throw new Exception("Missing required field: text");
					if ((q.type.equals("MULTIPLE_CHOICE") || q.type.equals("SELECT_MULTIPLE")) && (q.choices == null || q.choices.isEmpty())) throw new Exception("Missing required field: choices for MULTIPLE_CHOICE or SELECT_MULTIPLE type");
					questions.add(q);
					ofy().save().entity(q);
				} catch (Exception e) {
					buf.append("Error on question " + (questions.size() + 1) + ": " + e.getMessage()==null?e.toString():e.getMessage());
				}
			}
			ofy().save().entities(questions);
		} catch (Exception e) {
			buf.append("Error: " + e.getMessage()==null?e.toString():e.getMessage() + "<p>" + json);
		}

		assignMissingConcepts(questions, concepts, conceptIds, buf);

		int n = questions.size();
		buf.append(n + " question " + (n == 1 ? "item was" : "items were") + " uploaded successfully.<br/>");
		if (user.isChemVantageAdmin()) {
			buf.append("<a href='/Edit?UserRequest=Review&sig=" + user.getTokenSignature() + "'>Review the ProposedQuestion items</a>.");
		} else {
			String assignmentType = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now().assignmentType;
			buf.append("You can view/edit/select " + (n == 1 ? "it" : "them") + " by using the "
					+ "<a href='/" + assignmentType + "?UserRequest=AssignHomeworkQuestions&AssignmentType=Custom&sig=" + user.getTokenSignature() + "'>Custom Questions link</a> "
					+ "on the Instructor page of your Homework or Quiz assignment or "
					+ "<a href='/Contribute?sig=" + user.getTokenSignature() + "'>upload another JSON</a>");
		
		}
		return buf.toString();
	}

	// Uses Gemini to assign a conceptId to any question item that does not already have one.
	void assignMissingConcepts(List<Question> questions, List<Concept> concepts, Map<String,Long> conceptIds, StringBuffer buf) {
		if (concepts.isEmpty()) return;

		List<Question> pending = new ArrayList<Question>();
		for (Question q : questions) {
			if (q.conceptId != null) continue;
			try {
				q.setParameters();
				String questionItem = q.printForSage();
				if (questionItem == null || questionItem.isEmpty()) throw new Exception("Question text is empty");
				pending.add(q);
			} catch (Exception e) {
				buf.append("Could not assign a concept to question \"" + q.text + "\": " + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>");
			}
		}
		if (pending.isEmpty()) return;

		try {
			JsonObject json = requestConceptAssignmentsFromGemini(pending, concepts);
			JsonArray assignments = json.getAsJsonArray("assignments");
			if (assignments == null || assignments.isEmpty()) {
				throw new Exception("Google Gen AI returned no assignments.");
			}

			boolean[] assigned = new boolean[pending.size()];
			List<Question> updated = new ArrayList<Question>();
			for (int i = 0; i < assignments.size(); i++) {
				JsonObject assignment = assignments.get(i).getAsJsonObject();
				if (!assignment.has("question_index") || !assignment.has("concept")) continue;

				int questionIndex = assignment.get("question_index").getAsInt();
				if (questionIndex < 0 || questionIndex >= pending.size()) continue;
				if (assigned[questionIndex]) continue;

				String conceptTitle = assignment.get("concept").getAsString();
				Long conceptId = conceptIds.get(conceptTitle);
				if (conceptId == null) {
					Question q = pending.get(questionIndex);
					buf.append("Could not assign a concept to question \"" + q.text + "\": Gemini returned an unrecognized concept: " + conceptTitle + "<br/>");
					continue;
				}

				Question q = pending.get(questionIndex);
				q.conceptId = conceptId;
				updated.add(q);
				assigned[questionIndex] = true;
			}

			if (!updated.isEmpty()) ofy().save().entities(updated);

			for (int i = 0; i < pending.size(); i++) {
				if (!assigned[i]) {
					Question q = pending.get(i);
					buf.append("Could not assign a concept to question \"" + q.text + "\": Gemini returned no assignment.<br/>");
				}
			}
		} catch (Exception e) {
			String error = e.getMessage()==null?e.toString():e.getMessage();
			for (Question q : pending) {
				buf.append("Could not assign a concept to question \"" + q.text + "\": " + error + "<br/>");
			}
		}
	}

	private JsonObject requestConceptAssignmentsFromGemini(List<Question> questions, List<Concept> concepts) throws Exception {
		StringBuilder conceptList = new StringBuilder();
		for (Concept c : concepts) conceptList.append("- ").append(c.title).append("\n");

		StringBuilder questionList = new StringBuilder();
		for (int i = 0; i < questions.size(); i++) {
			questionList.append("question_index: ").append(i).append("\n");
			questionList.append("question_item:\n").append(questions.get(i).printForSage()).append("\n\n");
		}

		Map<String,Object> responseSchema = Map.of(
				"type", "object",
				"properties", Map.of(
						"assignments", Map.of(
								"type", "array",
								"items", Map.of(
										"type", "object",
										"properties", Map.of(
												"question_index", Map.of("type", "integer"),
												"concept", Map.of("type", "string")
										),
										"required", List.of("question_index", "concept")
								)
						)
				),
				"required", List.of("assignments")
		);

		GenerateContentConfig generationConfig = GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.responseJsonSchema(responseSchema)
				.candidateCount(1)
				.build();

		String prompt = "You are categorizing chemistry question items by key concept. "
				+ "For each question, choose the single best matching concept from this list and return the title exactly as shown:\n"
				+ conceptList
				+ "\nReturn ONLY a valid JSON object (no markdown, no code fences, no extra text) with this exact schema:\n"
				+ "{\n"
				+ "  \"assignments\": [\n"
				+ "    { \"question_index\": 0, \"concept\": \"string\" }\n"
				+ "  ]\n"
				+ "}\n\n"
				+ "Provide one assignment object for each question_index.\n\n"
				+ "Questions:\n" + questionList;

		try {
			Client client = Client.builder()
					.enterprise(true)
					.project(Subject.getProjectId())
					.location(Subject.getGemModelLocation())
					.httpOptions(HttpOptions.builder().apiVersion("v1").build())
					.build();
			GenerateContentResponse response = client.models.generateContent(Subject.getGemModel(), prompt, generationConfig);
			String text = response.text();
			if (text == null || text.trim().isEmpty()) throw new Exception("Google Gen AI response did not contain concept text.");
			try {
				return JsonParser.parseString(text.trim()).getAsJsonObject();
			} catch (Exception parseException) {
				throw new Exception("Google Gen AI response was not valid JSON object: " + text.trim());
			}
		} catch (Exception e) {
			throw new Exception("Google Gen AI API error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}
	/* 
	String newQuestionForm(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Authors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			// The values of assignmentType, questionType and topicKey are required to start editing
			String assignmentType = request.getParameter("AssignmentType");
			if (assignmentType==null) assignmentType = "";
			int questionType = 0;
			try {
				questionType = Integer.parseInt(request.getParameter("QuestionType"));
			} catch (Exception e) {}
			long conceptId = 0;
			try {
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {}
			String questionText = request.getParameter("QuestionText");
			ArrayList<String> choices = new ArrayList<String>();
			char choice = 'A';
			int nChoices = 0;
			for (int i=0;i<5;i++) {
				String choiceText = request.getParameter("Choice"+ choice +"Text");
				if (choiceText==null) choiceText = "";
				choices.add(choiceText);
				if (choiceText.length() > 0) {
					nChoices = i+1;
				}
				choice++;
			}
			String correctAnswer = "";
			try {
				String[] allAnswers = request.getParameterValues("CorrectAnswer");
				for (int i = 0; i < allAnswers.length; i++) correctAnswer += allAnswers[i];
			} catch (Exception e2) {
				correctAnswer = request.getParameter("CorrectAnswer");
			}
			int significantFigures = 3;
			double requiredPrecision = 2.0;
			try {
				significantFigures = Integer.parseInt(request.getParameter("SignificantFigures"));
				requiredPrecision = Double.parseDouble(request.getParameter("RequiredPrecision"));
			} catch (Exception e) {}
						
			String questionTag = request.getParameter("QuestionTag");
			String parameterString = request.getParameter("ParameterString");
			if (parameterString == null) parameterString = "";
			String hint = request.getParameter("Hint");
			String solution = request.getParameter("Solution");

			ProposedQuestion q = null;
			boolean preview = false;
			buf.append("<FORM NAME=NewQuestion ACTION=Contribute METHOD=POST>");
			
			buf.append("<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + ">");
			
			if (assignmentType.length()>0 && questionType>0 && conceptId>0) { // create the question object
				q = new ProposedQuestion(questionType);
				q.conceptId = conceptId;
				q.assignmentType = assignmentType;
				q.text = questionText;
				q.nChoices = nChoices;
				q.choices = choices;
				q.correctAnswer = correctAnswer;
				q.significantFigures = significantFigures;
				q.requiredPrecision = requiredPrecision;
				q.tag = questionTag;
				q.parameterString = parameterString;
				q.hint = hint;
				q.solution = solution;
				q.authorId = user.getId();
				q.contributorId = user.getId();
				q.editorId = "";
				q.notes = "";
				q.validateFields();

				if (request.getParameter("QuestionText")!=null) {  // preview the formatted question
					Concept concept = ofy().load().type(Concept.class).id(conceptId).now();
					buf.append("<h2>" + assignmentType + " Question Preview</h2>Topic: " + org.springframework.web.util.HtmlUtils.htmlEscape(concept.title) + "<p>");
					preview = true;
					q.setParameters();
					buf.append(q.printAll());
					buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Save'><hr>");
				}
			}

			if (preview) buf.append("<h2>Continue Editing</h2>");
			else {
				buf.append("<h2>Contribute a New Question</h2>");
				buf.append("All instructors are encouraged to contribute new question items to the "
						+ "ChemVantage database for quizzes and homework assignments.  Your contribution "
						+ "will be approved by an editor before is appears in production. By using this form, "
						+ "you certify that the question item is original (not previously copyrighted), and that "
						+ "you assign the copyrights to ChemVantage LLC with the understanding that it will be "
						+ "shared openly through a <a href=http://creativecommons.org/licenses/by/3.0/us/>"
						+ "Creative Commons Attribution 3.0 License</a>. "
						+ "For details, please see our <a href=/copyright.html>copyright policy</a>.<p>");
				if (user.isChemVantageAdmin()) buf.append("Click <a href='/Contribute?UserRequest=Batch&sig=" + user.getTokenSignature() + "'>here</a> for batch uploads.<p>");
			}

			buf.append("<b>Concept: </b>");
			
			if (conceptId>0L) {
				Concept c = ofy().load().type(Concept.class).id(conceptId).now();
				buf.append(org.springframework.web.util.HtmlUtils.htmlEscape(c.title) + "<input type=hidden name=ConceptId value=" + c.id + " /><br/>");
			} else {
				List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
				buf.append("<SELECT NAME=ConceptId><OPTION VALUE=0>Select a key concept:</OPTION>");
				for (Concept c : concepts) {
					if (c.orderBy.startsWith(" 0")) continue;
					buf.append("<OPTION VALUE='" + c.id + "'>" + org.springframework.web.util.HtmlUtils.htmlEscape(c.title) + "</OPTION>");
				}
				buf.append("</SELECT><br/><br/>");
			}
			
			
			if (assignmentType.length()==0) {
				buf.append("<b>Assignment Type: </b><br>"
						+ "<INPUT TYPE=RADIO NAME=AssignmentType VALUE='Quiz'>Quiz<br>"
						+ "<INPUT TYPE=RADIO NAME=AssignmentType VALUE='Homework'>Homework<p>");
			}
			else {
				buf.append("<b>Assignment Type: </b>" + assignmentType + "<br>");
				buf.append("<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>");
			}

			if (questionType > 0) {
				buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE='" + questionType + "'>");
				buf.append("<b>Question Type: </b>");
				switch (questionType) {
				case (1): buf.append("Multiple-Choice<br/>");
				buf.append("Fill in the question text and the possible answers "
						+ "(up to a maximum of 5). Be sure to select the single best "
						+ "answer to the question.<p>"); break;
				case (2): buf.append("True-False<br/>");
				buf.append("Write the question as an affirmative statement. Then "
						+ "indicate below whether the statement is true or false.<p>"); break;
				case (3): buf.append("Select-Multiple<br />");
				buf.append("Fill in the question text and the possible answers "
						+ "(up to a maximum of 5). Be sure to select all of the "
						+ "correct answers to the question.<p>"); break;
				case (4): buf.append("Fill-in-Word<br />");
				buf.append("Start the question text in the upper textarea box. Indicate "
						+ "the correct answer (and optionally, an alternative correct answer) in "
						+ "the middle boxes, and the end of the question text below that.  The answers "
						+ "are not case-sensitive or punctuation-sensitive, but spelling must "
						+ "be exact.<p>"); break;
				case (5): buf.append("Numeric<br />");
				buf.append("Fill in the question text in the upper textarea box and "
						+ "the correct numeric answer below. Also indicate the required precision "
						+ "of the student's response in percent (default = 2%). Use the Units box "
						+ "to indicate the expected dimensions or units of the student response or "
						+ "to finish any part of the question text that comes last.<p>"); break;
				case (7): buf.append("Essay<br />");
				buf.append("Pose a free-response question that the learner can answer in 800 characters "
						+ "or less. The response will be scored by AI.<p>"); break;
				default:  buf.append("An unexpected error occurred. "
						//+ "Please <a href=Contribute" + (cvsToken==null?"":"&CvsToken=" + cvsToken) + ">try again</a>.");
						+ "Please <a href=Contribute?sig=" + user.getTokenSignature() + ">try again</a>.");
				}
			}
			else {
				buf.append("<b>Question Type: </b><br>"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=1>Multiple Choice</label><br />"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=2>True/False</label><br />"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=3>Select Multiple</label><br />"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=4>Fill in Word</label><br />"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=5>Numeric</label><br />"
						+ "<label><INPUT TYPE=RADIO NAME=QuestionType VALUE=7>Essay</label><br />");
				}

			if (q != null) buf.append(q.edit() 
					+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview This Question'>");
			else buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Continue>");

			buf.append("</FORM");

		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
*/

/* 	
	String submitQuestion(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		//String cvsToken = request.getSession().isNew()?user.getCvsToken():null;
		ProposedQuestion q = null;
		try {
			String assignmentType = request.getParameter("AssignmentType");
			long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			int questionType = 0;
			questionType = Integer.parseInt(request.getParameter("QuestionType"));
			q = new ProposedQuestion(questionType);
			String questionText = request.getParameter("QuestionText");
			ArrayList<String> choices = new ArrayList<String>();
			int nChoices = 0;
			char choice = 'A';
			for (int i=0;i<5;i++) {
				String choiceText = request.getParameter("Choice"+ choice +"Text");
				if (choiceText==null) choiceText = "";
				if (choiceText.length() > 0) {
					choices.add(choiceText);
					nChoices++;
				}
				choice++;
			}
			double requiredPrecision = 0.0; // percent
			int significantFigures = 3;
			int pointValue = 1;
			try {
				Integer.parseInt(request.getParameter("PointValue"));
			} catch (Exception e) {};
			try {
				requiredPrecision = Double.parseDouble(request.getParameter("RequiredPrecision"));
			} catch (Exception e2) {
			}
			try {
				significantFigures = Integer.parseInt(request.getParameter("SignificantFigures"));
			} catch (Exception e) {
			}
			String correctAnswer = "";
			try {
				String[] allAnswers = request.getParameterValues("CorrectAnswer");
				for (int i = 0; i < allAnswers.length; i++) correctAnswer += allAnswers[i];
			} catch (Exception e2) {
				correctAnswer = request.getParameter("CorrectAnswer");
			}
			String questionTag = request.getParameter("QuestionTag");
			String parameterString = request.getParameter("ParameterString");
			if (parameterString == null) parameterString = "";
			String hint = request.getParameter("Hint");
			String solution = request.getParameter("Solution");

			q.conceptId = conceptId;
			q.assignmentType = assignmentType;
			q.text = questionText;
			q.nChoices = nChoices;
			q.choices = choices;
			q.requiredPrecision = requiredPrecision;
			q.significantFigures = significantFigures;
			q.correctAnswer = correctAnswer;
			q.tag = questionTag;
			q.pointValue = pointValue;
			q.parameterString = parameterString;
			q.hint = hint;
			q.solution = solution;
			q.authorId = user.getId();
			q.contributorId = user.getId();
			q.editorId = "";
			q.notes = "";
			q.validateFields();
			
			ofy().save().entity(q);
		} catch (Exception e) {
			buf.append(e.toString());
		}
		if (buf.length() == 0) { // no errors reported
			buf.append("<h3>Question Submitted Successfully</h3>"
			+ "Thank you for contributing this question item to ChemVantage.<br>"
			+ "Your contribution will be reviewed by an editor before it is added to the database.<br>"
			+ "<a href=Contribute?sig=" + user.getTokenSignature() + ">Contribute another question item</a>.");
		}
		return buf.toString();
	}
		*/
}
