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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.Key;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/Homework")
public class Homework extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;	
	static int retryDelayMinutes = 1;  // minimum time between answer submissions for any single question

	public String getServletInfo() {
		return "This servlet presents a homework assignment for the user.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		String sig = request.getParameter("sig");

		try {
			User user = User.getUser(sig);
			if (user==null) throw new Exception("Invalid user token (may have expired).");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			
			switch (userRequest) {
			case "GetExplanation":
				long questionId = Long.parseLong(request.getParameter("QuestionId"));
				long parameter = Long.parseLong(request.getParameter("Parameter"));
				Question q = ofy().load().type(Question.class).id(questionId).now();
				if (q.requiresParser()) q.setParameters(parameter);
				out.println(q.getExplanation());
				break;
			case "ShowScores":
				String forUserId = request.getParameter("ForUserId");
				out.println(Subject.header("ChemVantage Scores") + showScores(user,a,forUserId) + Subject.footer);
				break;
			case "ShowSummary":
				out.println(Subject.header("Your Class ChemVantage Scores") + showSummary(user, a) + Subject.footer);
				break;
			case "Review":
				forUserId = request.getParameter("ForUserId");
				String forUserName = request.getParameter("ForUserName");
				out.println(Subject.header("Homework Review") + reviewSubmissions(user,a,forUserId,forUserName));
				break;
			case "AssignHomeworkQuestions":
				if (user.isInstructor()) out.println(Subject.header("Customize ChemVantage Homework Assignment") + selectQuestionsForm(user,a,request) + Subject.footer);
				else out.println(Subject.header("Customize ChemVantage Homework Assignment") + "<h2>Forbidden</h2>You must be signed in as the instructor to perform this functuon." + Subject.footer);
				break;
			case "SynchronizeScore":
				out.println(synchronizeScore(user,a,request.getParameter("ForUserId")));
				break;
			case "CreateCustomQuestion":
				out.println(Subject.header("Create Question") + newQuestionForm(user,request) + Subject.footer);
				break;
			case "Preview":
				out.println(Subject.header() + previewQuestion(user,request) + Subject.footer);
				break;
			case "Instructor":
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			default:
				long hintQuestionId = 0L;
				try {
					hintQuestionId = Long.parseLong(request.getParameter("Q"));
				} catch (Exception e) {}
				boolean showOptional = Boolean.parseBoolean(request.getParameter("ShowOptional"));
				out.println(Subject.header("Homework") + printHomework(user,a,hintQuestionId,showOptional) + Subject.footer);
			}
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        	out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();

		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("Invalid user token (may have expired).");
			
			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";

			switch (userRequest) {
			case "UpdateAssignment":
				if (a!=null) a.updateConceptQuestions(user,request);
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			case "Save New Title":
				if (a!=null) a.title = request.getParameter("AssignmentTitle");
				ofy().save().entity(a).now();
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			case "Set Allowed Attempts":
				a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
				try {
					a.attemptsAllowed = Integer.parseInt(request.getParameter("AttemptsAllowed"));
					if (a.attemptsAllowed<1) a.attemptsAllowed = null;
				} catch (Exception e) {
					a.attemptsAllowed = null;
				}
				ofy().save().entity(a).now();
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			case "Save Question":
				saveQuestion(user,request);
				out.println(Subject.header("ChemVantage Instructor Page") + selectQuestionsForm(user,a,request) + Subject.footer);
				break;
			case "Delete Question":
				deleteQuestion(user,request);
				out.println(Subject.header("ChemVantage Instructor Page") + selectQuestionsForm(user,a,request) + Subject.footer);
				break;
			case "Quit":
				out.println(Subject.header("Customize ChemVantage Homework Assignment") + selectQuestionsForm(user,a,request) + Subject.footer);
				break;
			case "Preview":
				out.println(Subject.header() + previewQuestion(user,request) + Subject.footer);
				break;
			case "Synchronize Scores":
				if (synchronizeScores(user,a,request)) out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				else out.println("Synchronization request failed.");
				break;
			case "IncludeCustomQuestions":
				if (user.isInstructor()) {
					includeCustomQuestions(user,a,request);
					out.println(Subject.header("Customize ChemVantage Homework Assignment") + selectQuestionsForm(user,a,request) + Subject.footer);
				}
				break;
			case "AddKeyConcept":
				if (user.isInstructor()) {
					try {
						long conceptId = Long.parseLong(request.getParameter("ConceptId"));
						if (a!=null &&!a.conceptIds.contains(conceptId)) {
							a.conceptIds.add(conceptId);
							a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",conceptId).keys().list());	
							ofy().save().entity(a).now();
						}
					} catch (Exception e) {}
					out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
				break;
			default: out.println(Subject.header("ChemVantage Homework Results") + printScore(user,a,request) + Subject.footer);
			}
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        	out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	Question assembleQuestion(HttpServletRequest request) throws Exception {
		int questionType = Integer.parseInt(request.getParameter("QuestionType"));
		String assignmentType = request.getParameter("AssignmentType");
		Question q = new Question(questionType);
		try {
			q.id = Long.parseLong(request.getParameter("QuestionId"));
		} catch (Exception e) {}
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
		double requiredPrecision = 0.; // percent
		int significantFigures = 0;
		int pointValue = 1;
		try {
			requiredPrecision = Double.parseDouble(request.getParameter("RequiredPrecision"));
		} catch (Exception e) {
		}
		try {
			significantFigures = Integer.parseInt(request.getParameter("SignificantFigures"));
		} catch (Exception e) {
		}
		String correctAnswer = "";
		try {
			String[] allAnswers = request.getParameterValues("CorrectAnswer");
			for (int i = 0; i < allAnswers.length; i++) correctAnswer += allAnswers[i];
		} catch (Exception e) {
			correctAnswer = request.getParameter("CorrectAnswer");
		}
		String parameterString = request.getParameter("ParameterString");
		if (parameterString == null) parameterString = "";
		
		q.setQuestionType(questionType);
		q.assignmentType = assignmentType;
		q.text = questionText;
		q.nChoices = nChoices;
		q.choices = choices;
		q.requiredPrecision = requiredPrecision;
		q.significantFigures = significantFigures;
		q.correctAnswer = correctAnswer;
		q.tag = request.getParameter("QuestionTag");
		q.pointValue = pointValue;
		q.parameterString = parameterString;
		q.hint = request.getParameter("Hint");
		q.solution = request.getParameter("Solution");
		q.notes = "";
		q.authorId = request.getParameter("AuthorId");
		q.editorId = request.getParameter("EditorId");
		q.scrambleChoices = Boolean.parseBoolean(request.getParameter("ScrambleChoices"));
		q.strictSpelling = Boolean.parseBoolean(request.getParameter("StrictSpelling"));
		q.validateFields();
		return q;
	}
	
	void deleteQuestion(User user, HttpServletRequest request) {
		if (!user.isInstructor()) return;
		Question q = null;
		try {
			q = ofy().load().type(Question.class).id(Long.parseLong(request.getParameter("QuestionId"))).safe();
		} catch (Exception e) {}
		if (q != null && user.getId().equals(q.authorId)) ofy().delete().entity(q).now();
	}
	
	void includeCustomQuestions(User user, Assignment a, HttpServletRequest request) {
		if (!user.isInstructor()) return;
		String[] questionIds = request.getParameterValues("QuestionId");
		if (questionIds == null) return;
		for (String id : questionIds) {  // eliminate any duplicate question keys
			try {
				Key<Question> k = key(Question.class,Long.parseLong(id));
				if (!a.questionKeys.contains(k)) a.questionKeys.add(k);
			} catch (Exception e) {}
		}
		ofy().save().entity(a).now();
	}
	
	static String instructorPage(User user,Assignment a) {
		if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		
		StringBuffer buf = new StringBuffer();		
		try {
			buf.append(Subject.privacyPolicyBanner());
			buf.append("<h1>Homework</h1>"
					+ "<h2>" + a.title + "</h2>"
					+ "<h3>Instructor Page</h3>"
					+ "<a href='/Homework?sig=" + user.getTokenSignature() + "' class='btn btn-primary'>Show This Assignment</a><br/><br/>"
					+ "Assignment ID: " + a.id + "<br/>"
					+ "<form action=/Homework method=post>"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label><b>Title:</b>&nbspHomework - <input type=text size=25 name=AssignmentTitle value='" + a.title + "' /></label>&nbsp;"
					+ "<input type=submit name=UserRequest value='Save New Title' /></form><br/>\n"
					+ "Currently, the number of attempts allowed for each question is " + (a.attemptsAllowed==null?"unlimited":a.attemptsAllowed) + ".<br/>"
					+ "<form action=/Homework method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label>Attempts allowed:&nbsp;<input type=text size=10 name=AttemptsAllowed " 
					+ (a.attemptsAllowed==null?"placeholder=unlimited":"value=" + a.attemptsAllowed) + " /></label> "
					+ "<input type=submit name=UserRequest value='Set Allowed Attempts' />"
					+ "</form><br/>\n");
			
			Map<Key<Question>, Question> questionMap = ofy().load().keys(a.questionKeys);
			
			// Ensure that all question keys in the assignment are valid; if not, remove them
			if (a.questionKeys.size() != questionMap.size()) {
				a.questionKeys = new ArrayList<Key<Question>>(questionMap.keySet());
				ofy().save().entity(a).now();
			}
			buf.append("Currently, this assignment has " + a.questionKeys.size() + " assigned question items:<br/>");
			
			// Ensure that the assignment concepts include all concepts in the text chapter
			Text text = ofy().load().type(Text.class).id(a.textId).now();
			Chapter ch = text.getChapterByNumber(a.chapterNumber);
			List<Long> chapterConceptsToAdd = new ArrayList<>();
			for (Long cId : ch.conceptIds) {
				if (!a.conceptIds.contains(cId)) chapterConceptsToAdd.add(cId);
			}
			if (chapterConceptsToAdd.size() > 0) {
				a.conceptIds.addAll(chapterConceptsToAdd);
				ofy().save().entity(a).now();
			}

			// Create a map of conceptId to number of questions assigned for that concept
			Map<Long,Integer> conceptQuestionCounts = new HashMap<Long,Integer>();
			questionMap.values().forEach(q -> {
				Integer n = conceptQuestionCounts.get(q.conceptId);
				if (n==null) n = 0;
				conceptQuestionCounts.put(q.conceptId, n+1);
			});

			// Print a table of concepts with the number of assigned questions for each concept
			List<Concept> conceptList = ofy().load().type(Concept.class).list();
			Map<Long, Concept> conceptMap = new HashMap<>();
			for (Concept c : conceptList) {
				conceptMap.put(c.id, c);
			}

			buf.append("<table style='padding: 5px 5px;'>"
					+ "<tr><th>Concept</th><th># Questions</th><th style='text-align: center;'>Customize</th></tr>");
			a.conceptIds.forEach(cId -> {
				Concept c = conceptMap.get(cId);
				int nQuestions = conceptQuestionCounts.get(cId)==null?0:conceptQuestionCounts.get(cId);
				int nTotalQuestions = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",cId).count();
				buf.append("<tr>"
						+ "<td>" + (c==null?"(deleted concept)":c.title) + "</td>"
						+ "<td align=center>" + nQuestions + " of " + nTotalQuestions + "</td>"
						+ "<td align=center><a href='/Homework?UserRequest=AssignHomeworkQuestions&sig=" + user.getTokenSignature() + "&ConceptId=" + cId + "'>Select Questions</a></td>"
						+ "</tr>");
			});
			int nCustomQuestions = ofy().load().type(Question.class).filter("assignmentType","Custom").filter("authorId",user.getId()).count();
			int nQuestionsInAssignment = conceptQuestionCounts.get(null)==null?0:conceptQuestionCounts.get(null);
			
			buf.append("<tr>"
				+ "<td>Custom Questions</td>"
				+ "<td align=center>" + nQuestionsInAssignment + " of " + nCustomQuestions + "</td>"
				+ "<td><a href='/Homework?UserRequest=" + (nCustomQuestions==0?"CreateCustomQuestion":"AssignHomeworkQuestions") + "&sig=" + user.getTokenSignature() + "&ConceptId'>Create/Select Questions</a></td>"
				+ "</tr>"
				+ "</table><br/>");

			// Create a short form to select one additional key concept to include (will exclude the previous selection, if any)
			buf.append("<form method=post action=/Homework>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<input type=hidden name=UserRequest value=AddKeyConcept />"
					+ "<label>You may include additional question items from: "
					+ "<select name=ConceptId><option value='Select'>Select a key concept</option>");
			for (Concept c : conceptList) {
				try {
					if (a.conceptIds.contains(c.id) || c.orderBy.startsWith(" 0")) continue;  // skip current and hidden conceptIds
					buf.append("<option value='" + c.id + "'>" + c.title + "</option>");
				} catch (Exception e) {}
			}
			buf.append("</select></label><input type=submit value='Add Concept' /></form><br/>");
			
			// Link to review student scores if membership service is supported
			boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
			if (supportsMembership) buf.append("<a href='/Homework?UserRequest=ShowSummary&sig=" + user.getTokenSignature() + "'>Review your students' homework scores</a><br/>");
			
			buf.append("Need help? Please <a href=/Feedback?sig=" + user.getTokenSignature() + "&AssignmentId=" + a.id + ">submit a comment, question or request here</a>.<br/><br/>");			
			
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
			if (d.price > 0 && d.nLicensesRemaining > 0) {		
				buf.append("Your account has " + d.nLicensesRemaining + " unclaimed student license" + (d.nLicensesRemaining>1?"s":"") + " remaining.<br/><br/>");
			}
			
		} catch (Exception e) {
			buf.append("<br/>Instructor page error: " + e.getMessage());
		}
		return buf.toString();
	}
	
	String newQuestionForm(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<h1>Create Custom Homework Question</h1>");
		if (!user.isInstructor()) return null;
		
		String assignmentType = "Custom";
		
		int questionType = 0;
		try {
			questionType = Integer.parseInt(request.getParameter("QuestionType"));
		} catch (Exception e) {
			buf.append("Use this tool to create your own custom homework question for this assignment.<br/>"
					+ "First, choose the type of question to create:<br/>");
			
			buf.append("<FORM NAME=NewQuestion METHOD=GET ACTION=/Homework>");
			buf.append("<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=CreateCustomQuestion>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + user.getAssignmentId() + "'>"
					+ "<INPUT TYPE=HIDDEN NAME=QuestionType>"
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=1;submit();' VALUE='Multiple Choice'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=2;submit();' VALUE='True/False'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=3;submit();' VALUE='Select Multiple'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=4;submit();' VALUE='Fill in Word'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=5;submit();' VALUE='Numeric'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=6;submit();' VALUE='Five Star'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=7;submit();' VALUE='Essay'>"
					+ "</FORM><p><p>");
			
			return buf.toString();  // done
		}
		
		// if questionType has already been selected:
		switch (questionType) {
		case (1): buf.append("<h3>Multiple-Choice " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text and the possible answers "
				+ "(up to a maximum of 5). Be sure to select the single best "
				+ "answer to the question."); break;
		case (2): buf.append("<h3>True-False " + assignmentType + " Question</h3>");
		buf.append("Write the question as an affirmative statement. Then "
				+ "indicate below whether the statement is true or false."); break;
		case (3): buf.append("<h3>Select-Multiple " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text and the possible answers "
				+ "(up to a maximum of 5). Be sure to "
				+ "select all of the correct answers to the question."); break;
		case (4): buf.append("<h3>Fill-in-Word " + assignmentType + " Question</h3>");
		buf.append("Start the question text in the upper textarea box. Indicate "
				+ "the correct answer (and optionally, an alternative correct answer) in "
				+ "the middle boxes, and the end of the question text below that.  The answers "
				+ "are not case-sensitive or punctuation-sensitive, but spelling must "
				+ "be exact."); break;
		case (5): buf.append("<h3>Numeric " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text in the upper textarea box and "
				+ "the correct numeric answer below. Also indicate the required precision "
				+ "of the student's response in percent (default = 2%). Use the bottom "
				+ "textarea box to finish the question text and/or to indicate the "
				+ "expected dimensions or units of the student's answer."); break;
		case (6): buf.append("<h3>Five Star " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text. The user will be asked to provide a rating "
				+ "from 1 to 5 stars."); break;
		case (7): buf.append("<h3>Essay " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text. The user will be asked to provide a short "
				+ "essay response."); break;
		default: buf.append("An unexpected error occurred. Please try again.");
		}
		Question question = new Question(questionType);
		buf.append("<p><FORM METHOD=POST ACTION=/Homework>"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>"
				+ "<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + user.getId() + "'>");
		buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE=" + questionType + ">");

		question.pointValue = 1;
		buf.append(question.edit());
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview'></FORM>");

		return buf.toString();
	}

	String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	String previewQuestion(User user,HttpServletRequest request) throws Exception {
		if (!user.isInstructor()) throw new Exception("You must be an instructor for this.");
		StringBuffer buf = new StringBuffer();
		
		Question q = null;
		if (request.getParameter("QuestionType") != null) {
			q = assembleQuestion(request);
		} else { 
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			q = ofy().load().type(Question.class).id(questionId).now();
		}
		
		if (q.requiresParser()) q.setParameters();

		buf.append("<h1>Custom Homework Question</h1><h2>Preview</h2>");

		buf.append("<FORM ACTION=/Homework METHOD=POST>");

		buf.append(q.printAll());

		if (q.authorId==null) q.authorId = user.getId();
		buf.append("<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>");
		buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "'>");
		buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + user.getId() + "'>");
		buf.append("<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='Custom' />");
		
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Save Question' />&nbsp;");		
		if (q.id != null) {
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "' />");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Question' />&nbsp;");
		}
		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit' />");

		buf.append("<hr><h2>Continue Editing</h2>");
		buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()) + "<br/>");
		
		buf.append(q.edit());

		buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview' />");
		buf.append("</FORM>");
		return buf.toString();
	}

	String printHomework(User user, Assignment hwa, long hintQuestionId, boolean showOptional) throws Exception  {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		
		try {
			if (hwa==null) {  // anonymous user; print an assignment on Chapter 1 of the first smartText entity
				hwa = new Assignment();
				hwa.id = 0L;
				hwa.assignmentType = "Homework";
				Text text = ofy().load().type(Text.class).filter("smartText",true).first().now();
				Chapter ch = text.chapters.get(0);
				hwa.title = ch.title;
				hwa.textId = text.id;
				hwa.chapterNumber = ch.chapterNumber;
				hwa.conceptIds = ch.conceptIds;
				for (Long cId : hwa.conceptIds) hwa.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",cId).keys().list());
				Random rand = new Random(user.sig);  // use a random number generator to select 10 keys
				while (hwa.questionKeys.size() > 10) hwa.questionKeys.remove(rand.nextInt(hwa.questionKeys.size()));
			} else if (hwa.title==null) {  // legacy Homework assignment only provided topicId
				Topic t = ofy().load().type(Topic.class).id(hwa.topicId).now();
				hwa.title = t.title;
				if (hwa.conceptIds.isEmpty()) hwa.conceptIds = t.conceptIds;
				ofy().save().entity(hwa).now();
			}
						
			// START the presentation of the Homework assignment
			buf.append("<h1>Homework Exercises</h1><h2>" + hwa.title + "</h2>");
			debug.append("User exp: " + new Date(User.encode(user.sig)) + "<br/>");
			buf.append("Homework Rules<UL>");
			if (hwa.attemptsAllowed==null)
				buf.append("<LI>You may rework problems and resubmit answers as many times as you wish, to improve your score.</LI>");
			else buf.append("<LI>For each problem you are allowed " + hwa.attemptsAllowed + (hwa.attemptsAllowed==1?" attempt.":" attempts.") + "</LI>");
			buf.append("<LI>There is a retry delay of " + retryDelayMinutes + " minute" +(retryDelayMinutes==1?"":"s") + " between answer submissions for any single question.</LI>");
			buf.append("<LI>Most questions are customized, so the correct answers are different for each student.</LI>");
			if (!user.isAnonymous()) buf.append("\n<LI>A checkmark will appear to the left of each correctly solved problem.</LI>");
			buf.append("</UL>");

			// Review the HWTransactions for this user to record which problems have been solved for this assignment and retrieve the current showWork strings:
			List<Long> solvedQuestions = new ArrayList<Long>();
			Map<Long,String> workStrings = new HashMap<Long,String>();
			List<HWTransaction> hwTransactions = ofy().load().type(HWTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",hwa.id).order("-graded").list();
			Map<Long,Integer> priorAttempts = new HashMap<Long,Integer>();
			
			for (HWTransaction ht : hwTransactions) {
				int att = priorAttempts.get(ht.questionId)==null?1:priorAttempts.get(ht.questionId)+1; // prior attempts of this question
				priorAttempts.put(ht.questionId, att); // maintain a Map of prior attempts for each question
				if (solvedQuestions.contains(ht.questionId)) continue;
				if (ht.score > 0) solvedQuestions.add(ht.questionId);
				if (workStrings.containsKey(ht.questionId)) continue;
				workStrings.put(ht.questionId,ht.showWork);
			}
			
			Map<Key<Question>,Question> questions = ofy().load().keys(hwa.questionKeys);  // container for the questions to be presented
			
			buf.append("<h2>Assigned Exercises</h2>");
			buf.append("<div style='display:table'>"); // start the table of questions
			// This is the main loop for presenting assigned questions in order of increasing difficulty:
			int i=0;
			for (Key<Question> k : hwa.questionKeys) {
				Question q = questions.get(k); 
				if (q==null) continue;
				i++;
				buf.append("<div id=q" + i + " style='display:table-row'><div style='display:table-cell;font-size:small'>");
				String hashMe = user.getId() + hwa.id;
				q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments

				Integer attemptsRemaining = null;
				if (hwa.attemptsAllowed!=null) {
					attemptsRemaining = hwa.attemptsAllowed - (priorAttempts.get(q.id)==null?0:priorAttempts.get(q.id));
					if (attemptsRemaining < 0) attemptsRemaining = 0;
				}

				if (solvedQuestions.contains(q.id)) buf.append("<IMG SRC=https://images.chemvantage.org/checkmark.png ALT='Check mark' align=top>&nbsp;");
				
				buf.append("</div>");

				buf.append("<FORM METHOD=POST ACTION=/Homework onsubmit=waitForScore('" + q.id + "'); >"
						+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
						+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "'>"
						+ "<input type=hidden name=QNumber value=" + i + " />"  // this is the assigned question number on the page
						+ (hwa==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + hwa.id + "'>")
						+ "<div style='display:table-cell;vertical-align:text-top;padding-right:10px;'><b>" + i + ".</b></div>"
						+ "<div style='display:table-cell'>" + q.print(workStrings.get(q.id),"",attemptsRemaining) 
						+ (q.id == hintQuestionId?"Hint:<br>" + q.getHint():"")
						+ "<INPUT id=sub" + q.id + " role='button' aria-label='submit this answer for scoring' TYPE=SUBMIT class='btn btn-primary' VALUE='Grade This Exercise'><p>"
						+ "</div></div></FORM>\n");
			}
			if (i==0) buf.append("(none)");
			buf.append("</div>");  // end of assigned questions table
			
			if (showOptional) {
				List<Key<Question>> allQuestionKeys = new ArrayList<Key<Question>>();
				for (Long cId : hwa.conceptIds) allQuestionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",cId).keys().list());
				allQuestionKeys.removeAll(hwa.questionKeys);
				questions = ofy().load().keys(allQuestionKeys); 
				
				buf.append("<h2>Optional Exercises</h2>");
				buf.append("<div style='display:table'>"); // start the table of questions
				// This is the main loop for presenting assigned questions in order of increasing difficulty:
				i=0;
				for (Key<Question> k : allQuestionKeys) {
					Question q = questions.get(k); 
					if (q==null) continue;
					i++;
					buf.append("<div style='display:table-row'><div style='display:table-cell;font-size:small'>");
					String hashMe = user.getId() + hwa.id;
					q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments

					Integer attemptsRemaining = null;
					if (hwa.attemptsAllowed!=null) {
						attemptsRemaining = hwa.attemptsAllowed - (priorAttempts.get(q.id)==null?0:priorAttempts.get(q.id));
						if (attemptsRemaining < 0) attemptsRemaining = 0;
					}

					if (solvedQuestions.contains(q.id)) buf.append("<IMG SRC=https://images.chemvantage.org/checkmark.png ALT='Check mark' align=top>&nbsp;");
					
					buf.append("</div>");

					buf.append("<FORM METHOD=POST ACTION=/Homework onsubmit=waitForScore('" + q.id + "'); >"
							+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
							+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "'>" 
							+ (hwa==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + hwa.id + "'>")
							+ "<div style='display:table-cell;vertical-align:text-top;padding-right:10px;'><b>" + i + ".</b></div>"
							+ "<div style='display:table-cell'>" + q.print(workStrings.get(q.id),"",attemptsRemaining) 
							+ (q.id == hintQuestionId?"Hint:<br>" + q.getHint():"")
							+ "<INPUT id=sub" + q.id + " role='button' TYPE=SUBMIT class='btn btn-primary' VALUE='Grade This Exercise'><p>"
							+ "</div></div></FORM>\n");
				}
				if (i==0) buf.append("(none)");
				buf.append("</div>");  // end of assigned questions table
			} else if (!user.isAnonymous()) buf.append("<a href=/Homework?sig=" + user.getTokenSignature() + "&ShowOptional=true >Show Optional Questions</a>");
			
			buf.append("""
					<script>function showWorkBox(qid) {
						if (qid==0) return;
					    document.getElementById('showWork'+qid).style.display='';
					    document.getElementById('answer'+qid).placeholder='Enter your answer here';
					}</script>""");
			} catch (Exception e) {
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Error during Homework.printHomework: ", e.getMessage()==null?e.toString():e.getMessage() + "<br/>" + debug.toString() + "<br/>" + user.getId());
			return Logout.now(user);
		}
		return buf.toString();
	}

	String printScore(User user,Assignment hwa,HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer("""
				<style>\
				.response-container {
				    max-width: 900px;\s
				    box-sizing: border-box;
				    padding: 15px;
				}
				.status-text {
				    text-align: center;
				    font-size: 3rem;
				    font-weight: bold;
				    color: white;
				    background-color: blue;
				    width: 100%;
				}
				.explanation-text {
				    font-size: 1rem;
				    color: black;
				    background-color: white;
				    text-align: left;
				}
				.badge-container {
				    display: flex;
				    flex-direction: column;
				    align-items: center;
				    text-align: center;
				}
				.solution-container {
				    background-color: white;
				    border: 3px solid blue;
				    padding: 15px;
				    text-align: left;
				    width: 100%\
				}
				.feedback-container {
				    text-align: left;
				    width: 60%;
				}
				</style>""");
		StringBuffer debug = new StringBuffer("Homework.printScore...");
		debug.append("User exp: " + new Date(User.encode(user.sig)) + "<br/>");
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
		Date now = new Date();
		
		String qn = null;
		Question q = null;
		String hashMe = user.getId() + (user.isAnonymous()?0:hwa.id);  //used to setParameters
		String studentAnswer = null;
		JsonObject essay_score = new JsonObject(); // contains essay score and feedback
		int studentScore = 0;
		Score s = null;
		
		/*
		 * Validate the student response
		 */
		try {
			// Check to see if a response was submitted
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			studentAnswer = orderResponses(request.getParameterValues(Long.toString(questionId)));
			if (studentAnswer.isEmpty()) {
				buf.append("<h2>No response was submitted</h2>"
						+ "<a class='btn btn-primary' href=/Homework?AssignmentId=" + hwa.id 
						+ "&sig=" + user.getTokenSignature() + (qn==null?"":"#q" + qn) + ">"
						+ "Go Back</a><br/><br/>");

				return buf.toString();
			}
			
			// Check to see if attemptsAllowed has been exceeded
			qn = request.getParameter("QNumber");
			q = ofy().load().type(Question.class).id(questionId).safe();
			q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments
			String tooManyAttempts = tooManyAttempts(user,hwa,questionId);
			if (tooManyAttempts != null) {
				buf.append(tooManyAttempts);
				buf.append("<br/><a class='btn btn-primary' href=/Homework?AssignmentId=" + hwa.id 
						+ "&sig=" + user.getTokenSignature() + (qn==null?"":"#q" + qn) + ">"
						+ "Continue with this assignment</a><br/><br/>");
				return buf.toString();
			}

			// Check to see if the retry delay has expired
			String showWork = request.getParameter("ShowWork"+q.id);
			if (showWork==null) showWork="";  // required because later we check to see if showWork.isEmpty()
			String attemptTooSoon = attemptTooSoon(user,hwa,q,qn,showWork,studentAnswer);
			if (attemptTooSoon != null) {
				buf.append(attemptTooSoon);
				return buf.toString();
			}
			debug.append("1");
			// Everything is OK, score the studentAnswer
			switch (q.getQuestionType()) {
			case 6:  // Handle five-star rating response
				studentScore = q.pointValue;  // full marks for submitting a response
				break;
			case 7:  // New section for scoring essay questions with Chat GPT
				debug.append("essay question...");
				if (studentAnswer.length()>800) studentAnswer = studentAnswer.substring(0,799);

				BufferedReader reader = null;

				// Construct the API request JSON:
				JsonObject api_request = new JsonObject();
				api_request.addProperty("model", Subject.getGPTModel());
				JsonObject prompt = new JsonObject();
				prompt.addProperty("id", "pmpt_68b05dd3c7e88190b02ec3c4a41e412003d177cd13da4c5d");
				JsonObject variables = new JsonObject();
				variables.addProperty("question_item", q.printForSage());
				variables.addProperty("student_answer", studentAnswer);
				prompt.add("variables", variables);
				api_request.add("prompt", prompt);
				String response_format = "{'format':{'type':'json_schema','name':'essay_evaluation','schema':{'type':'object','properties':{'score':{'type':'integer'},'feedback':{'type':'string'}},'required':['score','feedback'],'additionalProperties':false},'strict':true}}";
				api_request.add("text", JsonParser.parseString(response_format).getAsJsonObject());
				
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
				debug.append("HTTP Response Code: " + response_code);

				JsonObject api_response = null;
				if (response_code/100==2) {
					reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
					api_response = JsonParser.parseReader(reader).getAsJsonObject();
					debug.append(api_response.toString());
					reader.close();
				} else {
					reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
					debug.append(JsonParser.parseReader(reader).getAsJsonObject().toString());
					reader.close();
				}

				// Find the output text buried in the response JSON:
				if (api_response != null) {
					JsonArray output = api_response.get("output").getAsJsonArray();
					JsonObject message = null;
					JsonObject output_text = null;
					for (JsonElement o : output) {
						message = o.getAsJsonObject();
						if (message.has("content")) {
							JsonArray content = message.get("content").getAsJsonArray();
							for (JsonElement c : content) {
								output_text = c.getAsJsonObject();
								if (output_text.has("text")) {
									String textContent = output_text.get("text").getAsString();  // this is the essay score JSON string
									essay_score = JsonParser.parseString(textContent).getAsJsonObject();
									studentScore = essay_score.get("score").getAsInt()>=4?q.pointValue:0;
									break;
								} else if (output_text.has("refusal")) {
									debug.append("API Error: " + output_text.get("refusal").getAsString());
								}
							}
							break;
						}
					}
				}
				debug.append("e");
				break;
			default:
				studentScore = q.isCorrect(studentAnswer)?q.pointValue:0;
			}
			debug.append("2");
			
			if (!user.isAnonymous()) {
				HWTransaction ht = new HWTransaction(q.id,user.getHashedId(),now,studentScore,hwa.id,q.pointValue,showWork);
				ht.studentAnswer = studentAnswer;
				ht.correctAnswer = q.getCorrectAnswer();				
				ofy().save().entity(ht).now();
			
				// create/update/store a Score object and report the score to the LMS
				Key<Question> k = key(Question.class,questionId);
				if (!user.isAnonymous() && hwa.questionKeys.contains(k) && hwa.lti_ags_lineitem_url != null) {
					q.addAttemptSave(studentScore>0);
					s = Score.getInstance(user.getId(),hwa);
					ofy().save().entity(s).now();
					String payload = "AssignmentId=" + hwa.id + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8");
					Utilities.createTask("/ReportScore",payload);
				}
			}
			
			debug.append("3");
			
			// Send a response to the student:
			buf.append("<div class='response-container'>"
					+ "<h1>" + (hwa==null?"Homework":hwa.title) + "</h1>"
					+ df.format(now) + "<br/><br/>"
					+ "<div class='score-container'>");
			if (studentScore==q.pointValue) {  // studentAnswer is correct
				String msg = null;
				int rand = new Random().nextInt(10);
				switch(rand) {
				case 0: msg = "Correct!"; break;
				case 1: msg = "That's Right!"; break;
				case 2: msg = "Great Job!"; break;
				case 3: msg = "Excellent!"; break;
				case 4: msg = "Spot On!"; break;
				case 5: msg = "Nailed It!"; break;
				case 6: msg = "You Got It!"; break;
				case 7: msg = "Fantastic!"; break;
				case 8: msg = "Awesome!"; break;
				case 9: msg = "Right On!"; break;
				}
				buf.append("<div class='status-text'>" + (q.getQuestionType()==6?"Thank you!":msg) + "</div>");
				
			} else {  // studentAnswer is incorrect or incomplete
				switch (q.getQuestionType()) {
				case 5:  // Numeric question
					try {
						@SuppressWarnings("unused")
						double dAnswer = Double.parseDouble(q.parseString(studentAnswer));  // throws exception for non-numeric answer
						if (!q.agreesToRequiredPrecision(studentAnswer)) buf.append("<div class='status-text'>Incorrect Answer</div>"
								+ "<p class='explanation-text'>"
								+ "Your answer does not " + (q.requiredPrecision==0?"exactly match the answer in the database. ":"agree with the answer in the database to within the required precision (" + q.requiredPrecision + "%).<br/><br/>")
								+ "</p>");
						else if (!q.hasCorrectSigFigs(studentAnswer)) buf.append("<div class='status-text'>Almost There!</div>"
								+ "<p class='explanation-text'>"
								+ "It appears that you've done the calculation correctly, but your answer does not have the correct number of significant figures appropriate for the data given in the question. "
								+ "If your answer ends in a zero, be sure to include a decimal point to indicate which digits are significant or (better!) use <a href=https://en.wikipedia.org/wiki/Scientific_notation#E_notation>scientific E notation</a>.<br/><br/>"
								+ "</p>");
					}
					catch (Exception e2) {
						buf.append("<div class='status-text'>Wrong Format</div>"
								+ "<p class='explanation-text'>"
								+ "This question requires a numeric response expressed as an integer, decimal number, "
								+ "or in scientific E notation (example: 6.022E-23). Your answer was scored incorrect because the computer "
								+ "was unable to recognize your answer as one of these types.<br/>"
								+ "</p>");
					}
					break;
				case 6:  // Five star rating
					buf.append("<div class='status-text'>No rating was submitted for this item.</div>");
					break;
				case 7:  // Essay question
					int score = essay_score.get("score").getAsInt();
					if (score<=1) buf.append("<div class='status-text'>Your answer is incorrect.</div>");
					else buf.append("<div class='status-text'>Your answer needs improvement</div><br/>");
					buf.append("<p class='explanation-text'>" + essay_score.get("feedback").getAsString() + "<br/><br/></p>");
					break;
				default:  // All other types of questions
					buf.append("<div class='status-text'>Incorrect Answer</div>"
							+ "<p class='explanation-text'>"
							+ "Your answer was scored incorrect because it does not agree with the answer in the database.<br/>"
							+ "</p>");
				}
			}
		} catch (Exception e) {
			buf.append("Sorry, there was an unexpected error: " + e.getMessage()==null?e.toString():e.getMessage());
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Error during Homework.printScore: ", e.getMessage()==null?e.toString():e.getMessage() + "<br/>" + debug.toString() + "<br/>" + user.getId());
			return Logout.now(request,e);
		}
		buf.append("</div>"); // end of score-container
		debug.append("4");
			
		/*
		 * Display the correct solution to the problem, if appropriate
		 */
		if (studentScore==q.pointValue || user.isInstructor()) {
			if (user.isInstructor() && studentScore!=q.pointValue) {
				buf.append("<div id='solution-link'>Instructor Only: "
						+ "<a href=# onclick=this.style='display:none';document.querySelector('.solution-container').style='display:block';>show the solution.</a></div>"
						+ "<div class='solution-container' style='display:none'>");
			} else {
				buf.append("<div class='solution-container'>");

			}
			
			buf.append(q.printAllToStudents(studentAnswer) + "<br/>");
			
			if (q.getQuestionType()==7) { // ESSAY
				int essayScore = essay_score.get("score").getAsInt();
				buf.append("<br/><b>Feedback: </b>" + essay_score.get("feedback").getAsString()
				+ (essayScore<4?"":"<br/><br/><b>Score: </b>" + essay_score.get("score").getAsInt() + "/5 (full credit)") + "<br/>");
			} else if (q.getQuestionType()<6) {
				buf.append("<div class='ai-explanation'>"
						+ " <button id=explainThis class='btn btn-primary' onclick=getExplanation();>Please explain this answer</button>"
						+ "</div>");
				buf.append("<script>"
						+ "async function getExplanation() {"
						+ "  document.getElementById('explainThis').innerHTML='Please wait a moment...';\n"
						+ "  try {\n"
						+ "    const url = '/Homework?sig=" + user.getTokenSignature() + "&UserRequest=GetExplanation&QuestionId=" + q.id + "&Parameter=" + hashMe.hashCode() + "';\n"
						+ "    const response = await fetch(url);\n"
						+ "    if (!response.ok) {\n"
						+ "      throw new Error(`HTTP error! status: ${response.status}`);\n"
						+ "    }\n"
						+ "    const textData = await response.text();\n"
						+ "    document.querySelector('.ai-explanation').innerHTML = textData;\n"  // Sage explanation
						+ "    var mathjax = document.createElement('script');\n"
						+ "    mathjax.type = 'text/javascript';\n"
						+ "    mathjax.src = 'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js';\n"
						+ "    document.head.appendChild(mathjax);\n"
						+ "    return;\n"
						+ "  } catch (error) {\n"
						+ "    document.querySelector('.ai-explanation').innerHTML = error.message;\n"  // Sage explanation
						+ "    return;\n"
						+ "  }"
						+ "}\n"
						+ "</script>");
			}
			buf.append("</div>"); // end of solution-container
		}
		
		// This will contain the badge and feedback containers:
		if (studentScore==q.pointValue) buf.append("<div style='display:flex;justify-content:center;align-items: center;width: 800px;max-width: 100%;gap: 20px;'>"); 
		
		/*
		 * Display feedback request and Continue button
		 */
		buf.append("<div class='feedback-container'>");
		
		if (studentScore==q.pointValue) {
			buf.append(Feedback.fiveStars(user.getTokenSignature()));
		} else {
			buf.append("<p><br/>Please take a moment to <a href=/Feedback?sig=" + user.getTokenSignature() + ">tell us about your ChemVantage experience</a>.<br/></p>");
		}
		debug.append("5");
			
		boolean offerHint = studentScore==0 && q.hasHint() && user.isEligibleForHints(q.id);

		buf.append("<a class='btn btn-primary' href=/Homework?AssignmentId=" + (hwa==null?0:hwa.id) + "&sig=" + user.getTokenSignature() + (offerHint?"&Q=" + q.id:"") + (qn==null?"":"#q" + qn) + ">"
				+ (offerHint?"Please give me a hint":"Continue with this assignment") 
				+ "</a><br/>");

		buf.append("</div>"); // end of feedback-container
		/*
		 * If the answer was correct, display the current badge
		 */
		if (studentScore==q.pointValue && s != null) {
			int decileScore = (int) Math.floor(s.getPctScore() / 10.0);
			String badgeName = null;
			switch (decileScore) {
			case 0: badgeName="Turbo-Penguin-Alchemist";break;
			case 1: badgeName="Glitter-Shark-Wizard";break;
			case 2: badgeName="Electric-Llama-Prophet";break;
			case 3: badgeName="Flamingo-Karate-Detective";break;
			case 4: badgeName="Space-Hamster-Captain";break;
			case 5: badgeName="Ninja-Cactus-Cowboy";break;
			case 6: badgeName="Galactic-Taco-Wizard";break;
			case 7: badgeName="Pirate-Samurai-Unicorn";break;
			case 8: badgeName="Disco-Viking-Platypus";break;
			case 9: badgeName="Quantum-Donut-Knight";break;
			case 10: badgeName="Turbo-Racoon-Overlord";break;
			}
			buf.append("<div class='badge-container'>"
					+ "<img src='https://images.chemvantage.org/badges/" + badgeName + ".png' height=260px width=260px alt='Fun cartoon character'>"
					+ "<span style='font-weight: bold;'>Your score is " + s.getPctScore() + "%<br/>" + badgeName + "</span>"
					+ "</div>");
		}
		
		if (studentScore==q.pointValue) buf.append("</div>"); // end of badge+feedback container
		debug.append("6");
		return buf.toString();
	}
	
	String attemptTooSoon(User user,Assignment hwa,Question q,String qn,String showWork,String studentAnswer) {
		StringBuffer buf = new StringBuffer();
		Date now = new Date();
		Date minutesAgo = new Date(now.getTime()-retryDelayMinutes*60000);  // about 1 minute ago
		HWTransaction lastTransaction = ofy().load().type(HWTransaction.class).filter("userId",user.getHashedId()).filter("questionId",q.id).filter("graded >",minutesAgo).first().now();
		if (lastTransaction==null || lastTransaction.score>0) return null;
		long secondsRemaining = retryDelayMinutes*60 - (now.getTime()-lastTransaction.graded.getTime())/1000L;
		
		buf.append("<h2>Please Wait For The Retry Delay To Complete</h2>");
		buf.append("<span id=timer0 style='color: #EE0000'></span><br/>");
		buf.append("Please take these few moments to check your work carefully.  You can sometimes find alternate routes to the "
				+ "same solution, or it may be possible to use your answer to back-calculate the data given in the problem.<br/><br/>");
		buf.append("<FORM NAME=Homework METHOD=POST ACTION=Homework onsubmit=waitForRetryScore(); >"
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" +(hwa.id==null?0:hwa.id) + "'>"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + ">"
				+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "'>" 
				+ (qn==null?"":"<input type=hidden name=QNumber value=" + qn + " />")  // this is the assigned question number on the page
				+ q.print(showWork,studentAnswer) + "<br>");

		buf.append("<INPUT TYPE='submit' id='RetryButton' class='btn btn-primary' DISABLED=true VALUE='Please wait' /></FORM><br/><br/>");
		buf.append("<script>"
				+ "startTimers(" + (secondsRemaining*1000L) + ");"
				+ "function timesUp() {"
				+ "document.getElementById('RetryButton').disabled=false;"
				+ "document.getElementById('RetryButton').value='Grade This Exercise';"
				+ "}"
				+ "</script>");

		return buf.toString();
	}
	
	String questionTypeDropDownBox(int questionType) {
		StringBuffer buf = new StringBuffer();
		buf.append("\n<SELECT NAME=QuestionType>"
				+ "<OPTION VALUE=1" + (questionType==1?" SELECTED>":">") + "Multiple Choice</OPTION>"
				+ "<OPTION VALUE=2" + (questionType==2?" SELECTED>":">") + "True/False</OPTION>"
				+ "<OPTION VALUE=3" + (questionType==3?" SELECTED>":">") + "Select Multiple</OPTION>"
				+ "<OPTION VALUE=4" + (questionType==4?" SELECTED>":">") + "Fill in word/phrase</OPTION>"
				+ "<OPTION VALUE=5" + (questionType==5?" SELECTED>":">") + "Numeric</OPTION>"
				+ "<OPTION VALUE=6" + (questionType==6?" SELECTED>":">") + "Five Star</OPTION>"
				+ "<OPTION VALUE=7" + (questionType==7?" SELECTED>":">") + "Essay</OPTION>"
				+ "</SELECT>");
		return buf.toString();
	}
	
	static String reviewSubmissions(User user, Assignment a, String forUserId, String forUserName) {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			// this line restricts non-instructor users to viewing their own scores
			String forUserHashedId = user.isInstructor()?Subject.hashId(forUserId):user.getHashedId();
			
			Map<Key<Question>,Question> questions = ofy().load().keys(a.questionKeys);
			List<HWTransaction> transactions = ofy().load().type(HWTransaction.class).filter("userId",forUserHashedId).filter("assignmentId",a.id).order("-graded").list();
			
			buf.append("<h1>Homework Submissions</h1>"
					+ (forUserName==null || forUserName.isEmpty()?"":"Name: " + forUserName + "<br/>")
					+ "Assignment: " + a.title + "<br/>"
					+ "Date: " + new Date() + "<br/><br/>");
			debug.append("0");
			
			buf.append("<table>");
			for (Key<Question> k : a.questionKeys) {  // this is the main loop through the assigned questions
				Question q = questions.get(k);
				String hashMe = forUserId + a.id;
				q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments
				debug.append("1");
				
				List<HWTransaction> qTransactions = new ArrayList<HWTransaction>();
				for (HWTransaction t : transactions) if (q.id.longValue() == t.questionId) qTransactions.add(t);
				debug.append("2");
				
				String studentAnswer = null;
				String showWork = null;
				HWTransaction hwt = qTransactions.isEmpty()?null:qTransactions.get(0);
				if (hwt!=null) {
					showWork = hwt.showWork;
					studentAnswer = hwt.studentAnswer;
				}
				debug.append("3");
				
				buf.append("<tr><td style='text-align:right;vertical-align:text-top;padding-right:10px;'><b>" + (a.questionKeys.indexOf(k)+1) + ".</b></td><td>" + q.printAllToStudents(studentAnswer,true,true,showWork) + "<br/></td></tr>");
				
				// print a small table of student submissions for this question
				buf.append("<tr><td></td><td>");
				if (!qTransactions.isEmpty()) {
					buf.append("<table style='text-align: center'><tr><th style='padding-right:20px'>Timestamp</th><th style='padding-right:20px'>Student Response</th><th style='padding-right:20px'>Correct Answer</th><th>Correct</th></tr>");
					for (HWTransaction t : qTransactions) {
						if (t.studentAnswer==null) buf.append("<tr><td style='padding-right:20px'>" + t.graded + "</td><td colspan=2 style='padding-right:20px'>(response detail is unavailable)</td>");
						else buf.append("<tr><td style='padding-right:20px'>" + t.graded + "</td><td style='padding-right:20px'>" + t.studentAnswer + "</td><td style='padding-right:20px'>" + t.correctAnswer + "</td>");
						
						if (t.score==1) buf.append("<td><img src=https://images.chemvantage.org/checkmark.png alt='checkmark' height=24 width=17></td>");
						else if (q.agreesToRequiredPrecision(t.studentAnswer)) buf.append("<td><img src=https://images.chemvantage.org/partCredit.png alt='partial credit' height=25 width=25></td>");
						else buf.append("<td><img src=https://images.chemvantage.org/xmark.png alt='x-mark' height=24 width=24></td>");
						buf.append("</tr>");
					}
					buf.append("</table><br/>");
				}
				buf.append("</td></tr>");
			}
			buf.append("</table><br/>");
			//buf.append(ajaxJavaScript(user.getTokenSignature()));
		} catch (Exception e) {
			buf.append("Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
		}
		return buf.toString();
	}
	
	void saveQuestion(User user, HttpServletRequest request) {
		if (!user.isInstructor()) return;
		try {
			Question q = assembleQuestion(request);
			q.isActive = true;
			ofy().save().entity(q).now();
			long assignmentId = user.getAssignmentId();
			Assignment a = ofy().load().type(Assignment.class).id(assignmentId).safe();
			a.questionKeys.add(0, key(q));
			ofy().save().entity(a).now();
		} catch (Exception e) {}
	}

	String selectQuestionsForm(User user,Assignment a,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		Concept c = null;
		try {
			buf.append("<h1>Customize Homework Assignment</h1>");
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			c = ofy().load().type(Concept.class).id(conceptId).now();
		} catch (Exception e) {
			c = new Concept("Custom","0");
		}
			
		buf.append("<h2>" + (c.title.equals("Custom")?"Custom Questions":"Concept: " + c.title) + "</h2>");
		buf.append("<a href='/Homework?UserRequest=Instructor&sig=" + user.getTokenSignature() + "'>Return to the Instructor Page</a><br/><br/>");
		
		if (c.title.equals("Custom")) {
			buf.append("<button class='btn btn-secondary' "
				+ "onclick=\"location.href='/Homework?UserRequest=CreateCustomQuestion&sig=" + user.getTokenSignature() + "';\">Create New Custom Question</button><br/><br/>");
		}

		List<Question> questions = null;
		if (c.title.equals("Custom")) {
			questions = ofy().load().type(Question.class).filter("assignmentType","Custom").filter("authorId",user.getId()).list();
		} else {
			questions = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",c.id).list();
		}
		
		if (questions.isEmpty()) {
			buf.append("There are currently no active questions available.<br/>");
			return buf.toString();
		} else {  // Allow instructor to pick individual question items from all active questions:
			buf.append("Select/unselect the homework questions below to be assigned for grading, "
				+ "then click the 'Use Selected Items' button. All questions have equal point value.");
			if (!c.title.equals("Custom")) buf.append(" Questions not selected will be available to students as optional practice problems.<p>");
		}

		// Show questions for only the selected concept. Make 2 lists of Assigned and Optional questions:
		StringBuffer assignedQuestions = new StringBuffer();
		StringBuffer optionalQuestions = new StringBuffer();
		int i = 1;  // counter for assigned questions
		int j = 1;  // counter for optional questions
		
		if (questions.size()>1) Collections.sort(questions,new SortBySuccessPct());
		for (Question q : questions) {
			boolean assigned = a.questionKeys.remove(key(q));
			StringBuffer qbuf = new StringBuffer();
			q.setParameters();  // creates randomly selected parameters
			int successRate = q.getPctSuccess();
			qbuf.append("<TR>"
					+ "<TD style='vertical-align:top;' NOWRAP>"
					+ "<label>"
					+ "<INPUT TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'"
					+ (assigned?" CHECKED />":" />")
					+ "<b>&nbsp;" + (assigned?i:j) + ".</b>"
					+ "</label><br/>"
					+ "<span style='font-size:0.5em'>" + (successRate==0?"new item&nbsp;&nbsp;":successRate + "% correct&nbsp;&nbsp;") + "</span><br/>"
					+ (c.title.equals("Custom")?"<a href=/Homework?UserRequest=Preview&sig=" + user.getTokenSignature() + "&QuestionId=" + q.id + ">Edit</a>":"")
					+ "</TD>"
					+ "<TD>" + q.printAll() + "</TD>"
					+ "</TR>");
			if (assigned) {
				assignedQuestions.append(qbuf);
				i++;
			} else {
				optionalQuestions.append(qbuf);
				j++;
			}
		}
		
		if (c.title.equals("Custom")) buf.append(" Currently, there are " + (i-1) + " custom question items included in this assignment.<br/><br/>");
		else buf.append(" Currently, this concept has " + (i-1) + " assigned question items and " + (j-1) + " optional questions.<br/><br/>");
			
		// This dummy form uses javascript to select/deselect all questions
		buf.append("<FORM style='display:inline;' NAME=DummyForm><label><INPUT id=selectAll TYPE=CHECKBOX NAME=SelectAll "
			+ "onClick='for (var i=0;i<document.Questions.QuestionId.length;i++)"
			+ "{document.Questions.QuestionId[i].checked=document.DummyForm.SelectAll.checked;}'"
			+ "> Select/Unselect All</label></FORM>&nbsp;&nbsp;&nbsp;");
		buf.append("<script>document.getElementById('selectAll').indeterminate=true;</script>");
			
		// Make a list of individual questions that can be selected or deselected for this assignment
		buf.append("<FORM style='display:inline;' NAME=Questions METHOD=POST ACTION=/Homework />"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + " />"
				+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE='UpdateAssignment' />"
				+ (c.title.equals("Custom")?"":"<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + c.id + "' />")
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + a.id + "' />"
				+ "<INPUT TYPE=SUBMIT Value='Use Selected Items' /><br/><br/>");
	
		// Make a table of assigned and optional questions
		buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
		if (!assignedQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2><b>Assigned Questions:</b></TD></TR>");
			buf.append(assignedQuestions);
		}
		if (!optionalQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2><b>" + (c.title.equals("Custom")?"Available Questions":"Optional Questions") + ":</b></TD></TR>");
			buf.append(optionalQuestions);
		}
		buf.append("</TABLE><INPUT TYPE=SUBMIT Value='Use Selected Items'></FORM><br/>");
		return buf.toString();
	}

	static String showScores(User user, Assignment a, String forUserId) {
		if (!user.isInstructor() || forUserId==null) forUserId = user.getId();  // user is viewing their own scores
		
		StringBuffer buf = new StringBuffer("<h1>Homework Transactions</h1>");
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
		Date now = new Date();
		
		try {
			buf.append("<h2>Topic: "+ a.title + "</h2>");
			buf.append("Assignment Number: " + a.id + "<br>");
			buf.append("Valid: " + df.format(now) + "<p>");
			
			List<HWTransaction> hwts = ofy().load().type(HWTransaction.class).filter("userId",Subject.hashId(forUserId)).filter("assignmentId",a.id).order("graded").list();
			
			if (hwts.size()==0) {
				buf.append("Sorry, we did not find any records for this user in the database for this assignment.<p>");
				return buf.toString();
			} else {
				Score s = null;
				try { // retrieve the score and ensure that it is up to date
					s = ofy().load().key(key(key(User.class,forUserId),Score.class,a.id)).safe();
					if (s.numberOfAttempts != hwts.size()) throw new Exception();
				} catch (Exception e) { // create a fresh Score entity from scratch
					s = Score.getInstance(forUserId, a);
					ofy().save().entity(s);
				}
				
				buf.append("This user's overall score on the assignment is " + 10.*Math.round(s.getPctScore())/10. + "%.<br>");

				try {
					double lmsPctScore = 0;
					String lmsScore = null;
					boolean gotScoreOK = false;

					if (a.lti_ags_lineitem_url != null) {  // LTI version 1p3
						lmsScore = LTIMessage.readUserScore(a, forUserId);
						try {
							lmsPctScore = Double.parseDouble(lmsScore);
							gotScoreOK = true;
						} catch (Exception e) {
							//buf.append("The LMS returned: " + lmsScore + "<br/>");
						}
					}

					if (gotScoreOK && Math.abs(lmsPctScore-s.getPctScore())<1.0) { // LMS readResult agrees to within 1%
						buf.append("This score is accurately recorded in the grade book of your class learning management system.<p>");
					} else if (gotScoreOK) { // there is a significant difference between LMS and ChemVantage scores. Please explain:
						buf.append("The score recorded in your class LMS is " + Math.round(10.*lmsPctScore)/10. + "%. The difference may be due to<br>"
								+ "enforcement of assignment deadlines, grading policies and/or instructor discretion.<br>"
								+ "If you think this may be due to a stale score, you may submit this assignment for grading,<br>"
								+ "even for a score of zero, and ChemVantage will try to refresh the best score to the LMS.<p>");
					} else throw new Exception();
				} catch (Exception e) {
					if (s.score==0 && s.numberOfAttempts==0) buf.append("It appears that this assignment may not have been submitted for a score yet.<br/>");
					buf.append("<br/>");
				}
				buf.append("<table><tr><th>QuestionID</th><th>Graded</th><th>Score</th></tr>");
				for (HWTransaction hwt : hwts) {
					buf.append("<tr align=center><td>" + hwt.questionId + "</td><td>" + df.format(hwt.graded) + "</td><td>" + hwt.score +  "</td></tr>");
				}
				buf.append("</table><br/><br/>");
				
				if (a.attemptsAllowed != null) buf.append("The maximum number of submissions for each question on this assignment is " + a.attemptsAllowed + "<br/><br/>");
				
			}
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
	
	static String showSummary(User user,Assignment a) {
		StringBuffer buf = new StringBuffer();
		if (a==null) return "No assignment was specified for this request.";

		if (!user.isInstructor()) return "You must be logged in as the instructor to view this page.";

		try {
			if (a.lti_nrps_context_memberships_url==null) throw new Exception("No Names and Roles Provisioning support.");

			buf.append("<h1>Homework Scores</h1>");
			buf.append("Title: " + a.title + "<br/>");
			buf.append("Assignment ID: " + a.id + "<br/>");
			buf.append("Valid: " + new Date() + "<p>");
			buf.append("The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, "
					+ "and may or may not include user's names or emails, depending on the settings of your LMS.<br/><br/>");

			Map<String,String> scores = LTIMessage.readMembershipScores(a);
			if (scores==null) scores = new HashMap<String,String>();  // in case service call fails

			Map<String,String[]> membership = LTIMessage.getMembership(a);
			if (membership==null) membership = new HashMap<String,String[]>(); // in case service call fails

			Map<String,Key<Score>> keys = new HashMap<String,Key<Score>>();
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
			String platform_id = d.getPlatformId() + "/";
			for (String id : membership.keySet()) {
				keys.put(id,key(key(User.class,Subject.hashId(platform_id+id)),Score.class,a.id));
			}
			Map<Key<Score>,Score> cvScores = ofy().load().keys(keys.values());
			buf.append("<table><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th><th>Scores Detail</th></tr>");
			int i=0;
			int nMismatched = 0;
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				String lmsScoreString = scores.get(entry.getKey());
				lmsScoreString = (lmsScoreString==null?" - ":lmsScoreString + "%");
				Score cvScore = cvScores.get(keys.get(entry.getKey()));
				String cvScoreString = cvScore==null?" - ":String.valueOf(cvScore.getPctScore() + "%");
				boolean synched = !"Learner".equals(entry.getValue()[0]) || cvScoreString.equals(lmsScoreString);
				String forUserId = platform_id + entry.getKey();  // only send hashed values through links
				i++;
				buf.append("<tr><td>" + i + ".&nbsp;</td>"
						+ "<td>" + entry.getValue()[1] + "</td>"
						+ "<td>" + entry.getValue()[2] + "</td>"
						+ "<td>" + entry.getValue()[0] + "</td>"
						+ "<td align=center>" + lmsScoreString + "</td>"
						+ "<td align=center>" + cvScoreString + "</td>"
						+ "<td align=center><a href=/Homework?UserRequest=Review&sig=" + user.getTokenSignature() + "&ForUserId=" + forUserId + "&ForUserName=" + entry.getValue()[1].replaceAll(" ","+") + ">show</a></td>"
						+ (synched?"":"<td><span id='cell" + forUserId + "'><button onClick=this.disabled=true;this.style.opacity=0.5;synchronizeScore('" + forUserId + "','" + user.getTokenSignature() + "','/Homework'); >sync</button></span></td>")
						+ "</tr>");
				// Flag this score set as unsynchronized only if there is one or more non-null ChemVantage Learner score that is not equal to the LMS score
				// Ignore Instructor scores because the LMS often does not report them, and ignore null cvScore entities because they cannot be reported.
				if (!synched) nMismatched++;
			}
			buf.append("</table><br/>");
			if (nMismatched > 0) {
				//buf.append(ajaxJavaScript(user.getTokenSignature()));
				buf.append("You may use the individual 'sync' buttons above to resubmit any ChemVantage score to the LMS. Note that in some cases, mismatched scores are expected (e.g., when "
						+ "the instructor overrides a score or when a late submission is not accepted by the LMS). You may have to adjust the settings in your LMS to accept the "
						+ "revised score (e.g., change the due date, grade override or allowed number of submissions). ");
			}
			if (nMismatched>1) {
				buf.append("Use the button below to synchronize all of the Learner scores. This might take a minute, depending on the number of mismatches.<br/>"
					+ "<form method=post action=/Homework onsubmit=waitforSync(); >"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=UserRequest value='Synchronize Scores' />"
					+ "<input type=submit id=syncAll value='Synchronize All Scores' />"
					+ "</form>");
			}
				return buf.toString();
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
	
	String synchronizeScore(User user, Assignment a, String forUserId) {
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();  // can only do this for a known assignment
			if (LTIMessage.postUserScore(Score.getInstance(forUserId,a), forUserId).contains("Success")) return "OK";
		} catch (Exception e) {}
		return "Failed. Check assignment settings in the LMS.";
	}

	boolean synchronizeScores(User user,Assignment a,HttpServletRequest request) {
		// This method looks for assignment scores that are different from the LMS scores and resubmits the score to the LMS
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();  // can only do this for a known assignment
			if (a.lti_ags_lineitem_url == null || a.lti_nrps_context_memberships_url == null) throw new Exception(); // need both of these to work
			Map<String,String> scores = LTIMessage.readMembershipScores(a);
			if (scores==null || scores.size()==0) throw new Exception();  // this only works if we can get info from the LMS
			Map<String,String[]> membership = LTIMessage.getMembership(a);
			if (membership==null || membership.size()==0) throw new Exception();  // there must be some members of this class
			Map<String,Key<Score>> keys = new HashMap<String,Key<Score>>();
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
			String platform_id = d.getPlatformId() + "/";
			for (String id : membership.keySet()) {
				String hashedUserId = Subject.hashId(platform_id + id);
				keys.put(id,key(key(User.class,hashedUserId),Score.class,a.id));
			}
			Map<Key<Score>,Score> cvScores = ofy().load().keys(keys.values());
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				Score cvScore = cvScores.get(keys.get(entry.getKey()));
				if (cvScore==null) continue;
				String s = scores.get(entry.getKey());
				if (String.valueOf(cvScore.getPctScore()).equals(s)) continue;  // the scores match (good!)
				String payload = "AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8");
				Utilities.createTask("/ReportScore",payload);
				//QueueFactory.getDefaultQueue().add(withUrl("/ReportScore").param("AssignmentId",String.valueOf(a.id)).param("UserId",URLEncoder.encode(platform_id + entry.getKey(),"UTF-8")));  // put report into the Task Queue
			}
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	String tooManyAttempts(User user, Assignment hwa, Long questionId) {
		StringBuffer buf = new StringBuffer();
		// Return null if anonymous user or instructor or attemptsAllowed==null or this is an optional question
		if (hwa==null || user.isInstructor() || hwa.attemptsAllowed == null || !hwa.questionKeys.contains(key(Question.class,questionId))) return null;
		
		List<HWTransaction> transactions = ofy().load().type(HWTransaction.class).filter("userId",user.getHashedId()).filter("questionId",questionId).list();
		List<HWTransaction> priorAttempts = new ArrayList<HWTransaction>();
		for (HWTransaction t : transactions) if (t.assignmentId==hwa.id.longValue()) priorAttempts.add(t);
		if (priorAttempts.size() < hwa.attemptsAllowed) return null;
		
		// Make a List of prior attempts with a message
		buf.append("<h2>Sorry, you are only allowed " + hwa.attemptsAllowed + " attempt" + (hwa.attemptsAllowed==1?"":"s") + " for this question.</h2>");
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
		buf.append("<table><tr><th>Transaction Number</th><th>Graded</th><th>Score</th></tr>");
		for (HWTransaction hwt : priorAttempts) buf.append("<tr><td>" + hwt.id + "</td><td>" + df.format(hwt.graded) + "</td><td align=center>" + hwt.score +  "</td></tr>");
		buf.append("</table><br/>");

		return buf.toString();
	}
	
	class SortBySuccessPct implements Comparator<Question> {
		
		SortBySuccessPct() {}
		
		public int compare(Question q1, Question q2) {
			int rank = q2.getPctSuccess() - q1.getPctSuccess(); 
			if (rank==0) rank = q2.id.compareTo(q1.id);
			return rank;
		}
	}	
}

