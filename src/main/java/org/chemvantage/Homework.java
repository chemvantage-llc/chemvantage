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
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

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
	private static final Pattern EMPTY_V2000_MOLFILE = Pattern.compile("\\n\\s*0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0999\\s+V2000");
	private static final Pattern EMPTY_V3000_MOLFILE = Pattern.compile("M\\s+V30\\s+COUNTS\\s+0\\s+0\\s+0\\s+0\\s+0");
	private static final Pattern NUMERIC_PREFIX = Pattern.compile("^\\s*([+-]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?)");

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

		String userRequest = request.getParameter("UserRequest");
		if (userRequest==null) userRequest = "";

		boolean isJsonRequest = "ValidateQuestionWithAI".equals(userRequest);
		response.setContentType(isJsonRequest ? "application/json" : "text/html");
		PrintWriter out = response.getWriter();

		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("Invalid user token (may have expired).");

			if (isJsonRequest) {
				out.println(validateQuestionItemWithAI(user,request));
				return;
			}

			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();

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
			case "Set Scoring Method":
				a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
				a.scoreWork = Boolean.parseBoolean(request.getParameter("ScoreWork"));
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
				if (Utilities.synchronizeScores(user,a)) out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				else out.println("Synchronization request failed for assignment " + aId + ".");
				break;
			case "Email Report":
				if (!user.isInstructor()) throw new Exception("You must be an instructor to perform this function.");
				//Utilities.synchronizeScores(user,a);
				showSummary(user,a,true);
				out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
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
			if (isJsonRequest) {
				JsonObject errorResponse = new JsonObject();
				errorResponse.addProperty("message", e.getMessage()==null?e.toString():e.getMessage());
				out.println(errorResponse);
			} else {
				out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
			}
		}
	}

	Question assembleQuestion(HttpServletRequest request) throws Exception {
		int questionType = Integer.parseInt(request.getParameter("QuestionType"));
		String assignmentType = request.getParameter("AssignmentType");
		Question q = new Question(questionType);
		try {
			q.id = Long.parseLong(request.getParameter("QuestionId"));
		} catch (Exception e) {}
		try {
			q.conceptId = Long.parseLong(request.getParameter("ConceptId"));
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
	
	String attemptTooSoon(User user,Assignment hwa,Question q,String qn,String qAnchor,String showWork,String studentAnswer) {
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
				+ (qAnchor==null||qAnchor.isBlank()?"":"<input type=hidden name=QAnchor value='" + qAnchor + "' />")
				+ (qn==null?"":"<input type=hidden name=QNumber value=" + qn + " />")  // this is the assigned question number on the page
				+ q.print(showWork,studentAnswer,null,hwa.scoreWork) + "<br>");

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

	String conceptDropDownBox(Long conceptId) {
		StringBuffer buf = new StringBuffer();
		buf.append("<SELECT NAME=ConceptId><OPTION VALUE=''>Select a concept (optional)</OPTION>");
		List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
		for (Concept c : concepts) {
			if (c.orderBy.startsWith(" 0")) continue;  // skip the "hidden" concepts
			buf.append("<OPTION VALUE=" + c.id + (c.id.equals(conceptId)?" SELECTED>":">") + c.title + "</OPTION>");
		}
		buf.append("</SELECT>");
		return buf.toString();
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
					+ "</form><br/>\n"
				);
			buf.append("Numeric questions are currently scored on " + (a.scoreWork?"the final answer AND the work shown.":"the final answer only.") + "<br/>"
					+ "<form action=/Homework method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<fieldset><legend>Scoring method for numeric questions:</legend>"
					+ "<label><input type=radio name=ScoreWork value=false " + (a.scoreWork?"":"checked") + " /> Final answer only</label>&nbsp;"
					+ "<label><input type=radio name=ScoreWork value=true " + (a.scoreWork?"checked":"") + " /> Final answer and work shown</label>&nbsp;"
					+ "</fieldset>"
					+ "<input type=submit name=UserRequest value='Set Scoring Method' />"
					+ "</form><br/>\n"
			);
			
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
				if (q.assignmentType.equals("Homework")) {
					Integer n = conceptQuestionCounts.get(q.conceptId);
					if (n==null) n = 0;
					conceptQuestionCounts.put(q.conceptId, n+1);
				} else if (q.assignmentType.equals("Custom")) {
					Integer n = conceptQuestionCounts.get(0L); // use 0L as the key for custom questions
					if (n==null) n = 0;
					conceptQuestionCounts.put(0L, n+1);
				}
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
						+ "<td align=center><a href='/Homework?UserRequest=AssignHomeworkQuestions&AssignmentType=Homework&sig=" + user.getTokenSignature() + "&ConceptId=" + cId + "'>Select Questions</a></td>"
						+ "</tr>");
			});
			int nCustomQuestions = ofy().load().type(Question.class).filter("assignmentType","Custom").filter("authorId",user.getId()).count();
			int nQuestionsInAssignment = conceptQuestionCounts.get(0L)==null?0:conceptQuestionCounts.get(0L);
			
			buf.append("<tr>"
				+ "<td>Custom Questions</td>"
				+ "<td align=center>" + nQuestionsInAssignment + " of " + nCustomQuestions + "</td>"
				+ "<td><a href='/Homework?UserRequest=AssignHomeworkQuestions&AssignmentType=Custom&sig=" + user.getTokenSignature() + "'>Create/Select Questions</a></td>"
				+ "</tr>"
				+ "</table><br/>");

			// Create a short form to select one additional key concept to include (will exclude the previous selection, if any)
			buf.append("<form method=post action=/Homework>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<input type=hidden name=UserRequest value=AddKeyConcept />"
					+ "<label for=ConceptIdSelect>You may include additional question items from:</label> "
					+ "<select id=ConceptIdSelect name=ConceptId><option value='Select'>Select a key concept</option>");
			for (Concept c : conceptList) {
				try {
					if (a.conceptIds.contains(c.id) || c.orderBy.startsWith(" 0")) continue;  // skip current and hidden conceptIds
					buf.append("<option value='" + c.id + "'>" + c.title + "</option>");
				} catch (Exception e) {}
			}
			buf.append("</select><input type=submit value='Add Concept' /></form><br/>");
			
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
	
	private static boolean isBlankStructureSubmission(String submittedStructure) {
		if (submittedStructure == null || submittedStructure.isBlank()) return true;
		return EMPTY_V2000_MOLFILE.matcher(submittedStructure).find() || EMPTY_V3000_MOLFILE.matcher(submittedStructure).find();
	}

	boolean isFinalAttempt(User user, Assignment hwa, Long questionId) {
		if (hwa==null || user.isInstructor() || hwa.attemptsAllowed == null || !hwa.questionKeys.contains(key(Question.class,questionId))) return false;
		int nAttempts = ofy().load().type(HWTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",hwa.id).filter("questionId",questionId).count();
		return nAttempts == hwa.attemptsAllowed;
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
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=7;submit();' VALUE='Essay'> "
					+ "<INPUT TYPE=BUTTON onCLick='document.NewQuestion.QuestionType.value=8;submit();' VALUE='Chemical Structure'>"
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
		case (8): buf.append("<h3>Chemical Structure " + assignmentType + " Question</h3>");
		buf.append("Fill in the question text, then draw the expected structure in the Ketcher frame. The molfile will be stored as the correct answer."); break;
		default: buf.append("An unexpected error occurred. Please try again.");
		}
		Question question = new Question(questionType);
		buf.append("<p><FORM METHOD=POST ACTION=/Homework>"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>"
				+ "<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + user.getId() + "'>");
		buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE=" + questionType + ">");

		question.pointValue = 1;
		question.nChoices = 5;
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
		
		long parameterSeed = ThreadLocalRandom.current().nextLong();
		if (q.requiresParser()) q.setParameters(parameterSeed);

		buf.append("<h1>Custom Homework Question</h1><h2>Preview</h2>");

		buf.append("<FORM ID=previewQuestionForm ACTION=/Homework METHOD=POST>");

		buf.append(q.printAll());

		if (q.id != null && q.getQuestionType() <= 5) {
			if (q.checkedByAI==null) {
				buf.append("<div id='AIAnswerContainer'><a href='#' onClick=\"validateQuestionWithAI(this,'" + parameterSeed + "')\">Validate with AI</a></div><br/>");
			} else if (q.checkedByAI) {
				buf.append("<div id='AIAnswerContainer'>&#x2705; Checked by AI. <a href='#' onClick=\"validateQuestionWithAI(this,'" + parameterSeed + "')\">recheck</a></div><br/>");
			} else {
				buf.append("<div id='AIAnswerContainer'>&#x26A0;&#xFE0F; Needs attention. <a href='#' onClick=\"validateQuestionWithAI(this,'" + parameterSeed + "')\">recheck</a></div><br/>");
			}
			buf.append("""
				<script>
				async function validateQuestionWithAI(link,parameterSeed) {
					const ai_answer_container = document.getElementById('AIAnswerContainer');
					ai_answer_container.textContent = 'Validating...';
					var result;
					try {
						// use the form's current field values so edits made since the last Preview are validated;
						// look up the form by id rather than link.closest('form') since malformed HTML in the
						// question text could otherwise break the DOM ancestry chain
						const form = document.getElementById('previewQuestionForm');
						if (!form) throw new Error('Could not find the question form on this page.');
						const params = new URLSearchParams(new FormData(form));
						params.set('UserRequest', 'ValidateQuestionWithAI');
						params.set('ParameterSeed', String(parameterSeed));
						const response = await fetch('/Homework', {
							method: 'POST',
							headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
							body: params
						});
						result = await response.json();
						if (!response.ok) throw new Error(result.message || 'Validation failed.');
						if (result.isCorrect) {
							ai_answer_container.innerHTML = '&#x2705; Checked by AI';
						} else {
							ai_answer_container.innerHTML = '&#x26A0;&#xFE0F; Needs attention. The best answer is ' + result.best_answer;
						}
					} catch (err) {
						console.error('Validate with AI failed:', err);
						const details = result ? (' ' + JSON.stringify(result)) : (' ' + err.message);
						ai_answer_container.textContent = 'Validation failed. ' + details;
					}
				}
				</script>
			""");
		}

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
		buf.append("Concept: " + conceptDropDownBox(q.conceptId) + "<br/>");
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
			if (hwa.scoreWork) 
				buf.append("<LI>You must show your work for each numeric problem to receive credit for the correct answer.</LI>");
			else buf.append("<LI>Numeric problems include a \"Show your work\" box that serves as optional scratch space for you.</LI>");
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
				String qAnchor = "q" + q.id;
				buf.append("<div id='" + qAnchor + "' style='display:table-row'><div style='display:table-cell;font-size:small'>");
				String hashMe = user.getId() + hwa.id;
				q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments

				Integer attemptsRemaining = null;
				if (hwa.attemptsAllowed!=null) {
					attemptsRemaining = hwa.attemptsAllowed - (priorAttempts.get(q.id)==null?0:priorAttempts.get(q.id));
					if (attemptsRemaining < 0) attemptsRemaining = 0;
				}

				if (solvedQuestions.contains(q.id)) buf.append("<IMG SRC=/images/checkmark.png ALT='Check mark' align=top>&nbsp;");
				
				buf.append("</div>");

				buf.append("<FORM METHOD=POST ACTION=/Homework class='homework-response-form' data-question-id='" + q.id + "' data-question-type='" + q.getQuestionType() + "' onsubmit='return validateHomeworkSubmit(this," + q.id + ");'>"
						+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
						+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "'>"
						+ "<input type=hidden name=QAnchor value='" + qAnchor + "' />"
						+ "<input type=hidden name=QNumber value=" + i + " />"  // this is the assigned question number on the page
						+ "<input type=hidden name=QuestionType value='" + q.getQuestionType() + "' />"
						+ (hwa==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + hwa.id + "'>")
						+ "<div style='display:table-cell;vertical-align:text-top;padding-right:10px;'><b>" + i + ".</b></div>"
						+ "<div style='display:table-cell'>" + q.print(workStrings.get(q.id),"",attemptsRemaining,hwa.scoreWork) 
						+ (q.id == hintQuestionId?"Hint:<br>" + q.getHint():"")
						+ "<INPUT id=sub" + q.id + " role='button' aria-label='submit this answer for scoring' aria-disabled='true' disabled TYPE=SUBMIT class='btn btn-primary' VALUE='Grade This Exercise'>"
						+ "<div id=submsg" + q.id + " style='font-size:0.9em;color:#666;margin-top:6px;'>Provide an answer to enable submit.</div><p>"
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
					String qAnchor = "q" + q.id;
					buf.append("<div id='" + qAnchor + "' style='display:table-row'><div style='display:table-cell;font-size:small'>");
					String hashMe = user.getId() + hwa.id;
					q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments

					Integer attemptsRemaining = null;
					if (hwa.attemptsAllowed!=null) {
						attemptsRemaining = hwa.attemptsAllowed - (priorAttempts.get(q.id)==null?0:priorAttempts.get(q.id));
						if (attemptsRemaining < 0) attemptsRemaining = 0;
					}

					if (solvedQuestions.contains(q.id)) buf.append("<IMG SRC=/images/checkmark.png ALT='Check mark' align=top>&nbsp;");
					
					buf.append("</div>");

					buf.append("<FORM METHOD=POST ACTION=/Homework class='homework-response-form' data-question-id='" + q.id + "' data-question-type='" + q.getQuestionType() + "' onsubmit='return validateHomeworkSubmit(this," + q.id + ");'>"
							+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
							+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "'>" 
							+ "<input type=hidden name=QAnchor value='" + qAnchor + "' />"
							+ "<input type=hidden name=QuestionType value='" + q.getQuestionType() + "' />"
							+ (hwa==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + hwa.id + "'>")
							+ "<div style='display:table-cell;vertical-align:text-top;padding-right:10px;'><b>" + i + ".</b></div>"
							+ "<div style='display:table-cell'>" + q.print(workStrings.get(q.id),"",attemptsRemaining,hwa.scoreWork) 
							+ (q.id == hintQuestionId?"Hint:<br>" + q.getHint():"")
							+ "<INPUT id=sub" + q.id + " role='button' aria-disabled='true' disabled TYPE=SUBMIT class='btn btn-primary' VALUE='Grade This Exercise'>"
							+ "<div id=submsg" + q.id + " style='font-size:0.9em;color:#666;margin-top:6px;'>Provide an answer to enable submit.</div><p>"
							+ "</div></div></FORM>\n");
				}
				if (i==0) buf.append("(none)");
				buf.append("</div>");  // end of assigned questions table
			} else if (!user.isAnonymous()) buf.append("<a href=/Homework?sig=" + user.getTokenSignature() + "&ShowOptional=true >Show Optional Questions</a>");
			
			buf.append("""
					<script>
						function showWorkBox(qid) {
							if (qid==0) return;
							document.getElementById('showWork'+qid).style.display='';
							document.getElementById('answer'+qid).placeholder='Enter your answer here';
						}

						const emptyV2000Molfile = /\\n\\s*0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0999\\s+V2000/;
						const emptyV3000Molfile = /M\\s+V30\\s+COUNTS\\s+0\\s+0\\s+0\\s+0\\s+0/;

	function isBlankStructureSubmission(submittedStructure) {							if (!submittedStructure || submittedStructure.trim() === '') return true;
							return emptyV2000Molfile.test(submittedStructure) || emptyV3000Molfile.test(submittedStructure);
						}

						function hasMeaningfulAnswerByName(form, fieldName) {
							const fields = form.querySelectorAll('[name="' + fieldName + '"]');
							if (!fields || fields.length === 0) return false;

							const first = fields[0];
							if (first.type === 'radio' || first.type === 'checkbox') {
								for (const f of fields) if (f.checked) return true;
								return false;
							}

							return (first.value || '').trim() !== '';
						}

						function evaluateSubmitEligibility(form) {
							const qid = form.getAttribute('data-question-id');
							const qType = parseInt(form.getAttribute('data-question-type') || '0', 10);
							const answerFieldName = String(qid);
							const answerField = form.querySelector('[name="' + answerFieldName + '"]');

							if (qType === 6) {
								const ratedField = form.querySelector('[name="RatingSelected' + qid + '"]');
								const ratingSelected = ratedField && ratedField.value === 'true';
								const ratingValue = answerField ? (answerField.value || '').trim() : '';
								return ratingSelected && ratingValue !== '';
							}

							if (qType === 8) {
								const structure = answerField ? (answerField.value || '') : '';
								if (!isBlankStructureSubmission(structure)) return true;

								// Allow submit after meaningful editor interaction even before molfile sync.
								const smilesField = form.querySelector('[id="structure_' + qid + '_smiles"]');
								if (smilesField && (smilesField.value || '').trim() !== '') return true;

								return false;
							}

							return hasMeaningfulAnswerByName(form, answerFieldName);
						}

						function setSubmitState(form, enabled) {
							const qid = form.getAttribute('data-question-id');
							const submit = document.getElementById('sub' + qid);
							const message = document.getElementById('submsg' + qid);
							if (!submit) return;

							submit.disabled = !enabled;
							submit.setAttribute('aria-disabled', enabled ? 'false' : 'true');
							if (message) message.style.display = enabled ? 'none' : 'block';
						}

						function refreshSubmitState(form) {
							setSubmitState(form, evaluateSubmitEligibility(form));
						}

						function initializeHomeworkSubmitGuards() {
							const forms = document.querySelectorAll('form.homework-response-form');
							for (const form of forms) {
								form.addEventListener('input', function() { refreshSubmitState(form); });
								form.addEventListener('change', function() { refreshSubmitState(form); });
								form.addEventListener('focusin', function() { refreshSubmitState(form); });
								form.addEventListener('click', function() { refreshSubmitState(form); });

								// Chemical structure composer interactions may not emit standard input/change events.
								const qType = parseInt(form.getAttribute('data-question-type') || '0', 10);
								if (qType === 8) {
									const qid = form.getAttribute('data-question-id');
									const smilesField = form.querySelector('[id="structure_' + qid + '_smiles"]');
									const ketcherFrame = form.querySelector('[id="structure_' + qid + '_frame"]');

									if (smilesField) {
										smilesField.addEventListener('input', function() { refreshSubmitState(form); });
										smilesField.addEventListener('change', function() { refreshSubmitState(form); });
									}

									if (ketcherFrame) {
										const markInteracted = function() {
											form.setAttribute('data-chem-interacted', 'true');
											refreshSubmitState(form);
										};
										ketcherFrame.addEventListener('focus', markInteracted);
										ketcherFrame.addEventListener('pointerdown', markInteracted);
									}
								}
								refreshSubmitState(form);
							}
						}

						function validateHomeworkSubmit(form, qid) {
							if (!evaluateSubmitEligibility(form)) {
								const message = document.getElementById('submsg' + qid);
								if (message) message.style.display = 'block';
								return false;
							}
							waitForScore(String(qid));
							return true;
						}

						document.addEventListener('DOMContentLoaded', initializeHomeworkSubmitGuards);
					</script>
				""");
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
		String originalStudentAnswer = null;
		
		String qn = null;
		String qAnchor = null;
		Question q = null;
		String hashMe = user.getId() + (user.isAnonymous()?0:hwa.id);  //used to setParameters
		String studentAnswer = null;
		JsonObject essay_score = new JsonObject(); // contains essay score and feedback
		ChemicalStructureScorer.ComparisonResult structureComparison = null;
		int studentScore = 0;
		Score s = null;
		
		/*
		 * Validate the student response
		 */
		try {
			// Check to see if a response was submitted
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			q = ofy().load().type(Question.class).id(questionId).safe();
			q.setParameters(hashMe.hashCode());  // creates different parameters for different assignments

			String answerParam = Long.toString(questionId);
			studentAnswer = originalStudentAnswer = orderResponses(request.getParameterValues(answerParam));
			
			qAnchor = request.getParameter("QAnchor");
			boolean noResponseSubmitted;
			switch (q.getQuestionType()) {
			case 6:
				boolean ratingSelected = Boolean.parseBoolean(request.getParameter("RatingSelected" + questionId));
				noResponseSubmitted = studentAnswer.isBlank() || (!ratingSelected && "3".equals(studentAnswer));
				break;
			case 8:
				noResponseSubmitted = isBlankStructureSubmission(studentAnswer);
				break;
			default:
				noResponseSubmitted = studentAnswer.isEmpty();
			}

			if (noResponseSubmitted) {
				buf.append("<h2>No response was submitted</h2>"
						+ "<a class='btn btn-primary' href=/Homework?AssignmentId=" + hwa.id 
						+ "&sig=" + user.getTokenSignature() + (qAnchor==null||qAnchor.isBlank()?"":"#" + qAnchor) + ">"
						+ "Go Back</a><br/><br/>");

				return buf.toString();
			}
			
			// Check to see if attemptsAllowed has been exceeded
			qn = request.getParameter("QNumber");
			if ((qAnchor == null || qAnchor.isBlank()) && qn != null && !qn.isBlank()) qAnchor = "q" + qn;
			String tooManyAttempts = tooManyAttempts(user,hwa,questionId);
			if (tooManyAttempts != null) {
				buf.append(tooManyAttempts);
				buf.append("<br/><a class='btn btn-primary' href=/Homework?AssignmentId=" + hwa.id 
						+ "&sig=" + user.getTokenSignature() + (qAnchor==null||qAnchor.isBlank()?"":"#" + qAnchor) + ">"
						+ "Continue with this assignment</a><br/><br/>");
				return buf.toString();
			}

			// Check to see if the retry delay has expired
			String showWork = request.getParameter("ShowWork"+q.id);
			String attemptTooSoon = attemptTooSoon(user,hwa,q,qn,qAnchor,showWork,studentAnswer);
			if (attemptTooSoon != null) {
				buf.append(attemptTooSoon);
				return buf.toString();
			}
			debug.append("1");
			// Everything is OK, score the studentAnswer
			switch (q.getQuestionType()) {
				case 5:  // Handle numeric response
				if (hwa != null && hwa.scoreWork) q.setShowWork(showWork);
				// Extract the numeric part of the student's answer, removing whitespace and any trailing units
				var matcher = NUMERIC_PREFIX.matcher(studentAnswer.replaceAll("\\s+", ""));
				studentAnswer = matcher.find() ? matcher.group(1) : studentAnswer;  // discard trailing units or preserve nonnumeric text
				studentScore = q.isCorrect(studentAnswer)?q.pointValue:0;
				break;
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
			case 8:  // Chemical structure comparison with CDK
				structureComparison = ChemicalStructureScorer.compare(q.correctAnswer, studentAnswer);
				studentScore = structureComparison.matched()?q.pointValue:0;
				break;
			default:
				studentScore = q.isCorrect(studentAnswer)?q.pointValue:0;
			}
			debug.append("2");
			
			if (!user.isAnonymous() && hwa != null) {
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
						Double.parseDouble(studentAnswer);  // throws exception for non-numeric answer
						if (!q.correctValue) buf.append("<div class='status-text'>Incorrect Answer</div>"
								+ "<p class='explanation-text'>"
								+ "Your answer does not " + (q.requiredPrecision==0?"exactly match the answer in the database. ":"agree with the answer in the database to within the required precision (" + q.requiredPrecision + "%).<br/><br/>")
								+ "<b>The answer submitted was: " + HtmlUtils.htmlEscape(originalStudentAnswer) + "</b>&nbsp;"
								+ "</p>");
						else if (!q.correctSigFigs) buf.append("<div class='status-text'>Almost There!</div>"
								+ "<p class='explanation-text'>"
								+ "It appears that you've done the calculation correctly, but your answer does not have the correct number of significant figures appropriate for the data given in the question. "
								+ "If your answer ends in a zero, be sure to include a decimal point to indicate which digits are significant or (better!) use <a href=https://en.wikipedia.org/wiki/Scientific_notation#E_notation>scientific E notation</a>.<br/><br/>"
								+ "<b>The answer submitted was: " + HtmlUtils.htmlEscape(originalStudentAnswer) + "</b>&nbsp;"
								+ "</p>");
						else if (!q.correctWork) buf.append("<div class='status-text'>Show Your Work!</div>"
								+ "<p class='explanation-text'>"
								+ "Your final answer is correct, but you did not include enough detail in the \"Show your work\" box to demonstrate that you used a valid method to solve the problem.<br/><br/>"
								+ "<b>The answer submitted was: " + HtmlUtils.htmlEscape(originalStudentAnswer) + "</b>&nbsp;"
								+ "</p>");
					} catch (Exception e2) {
						buf.append("<div class='status-text'>Wrong Format</div>"
								+ "<p class='explanation-text'>"
								+ "This question requires a numeric response expressed as an integer, decimal number, "
								+ "or in scientific E notation (example: 6.022E-23). Your answer was scored incorrect because the computer "
								+ "was unable to recognize your answer as one of these types.<br/>"
								+ "<b>The answer submitted was: " + HtmlUtils.htmlEscape(originalStudentAnswer) + "</b>&nbsp;"
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
				case 8:  // Chemical structure question
					buf.append("<div class='status-text'>Structure mismatch</div>"
							+ "<p class='explanation-text'>"
							+ (structureComparison==null?"The submitted structure could not be evaluated.":structureComparison.message())
							+ "<br/><br/></p>");
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
		if (studentScore==q.pointValue || isFinalAttempt(user, hwa, q.id) || user.isInstructor()) {
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
			
		boolean offerHint = studentScore==0 && q.hasHint() && user.isEligibleForHints(q.id) && !isFinalAttempt(user, hwa, q.id);

		buf.append("<a class='btn btn-primary' href=/Homework?AssignmentId=" + (hwa==null?0:hwa.id) + "&sig=" + user.getTokenSignature() + (offerHint?"&Q=" + q.id:"") + (qAnchor==null||qAnchor.isBlank()?"":"#" + qAnchor) + ">"
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
					+ "<img src='/images/badges/" + badgeName + ".png' height=260px width=260px alt='Fun cartoon character'>"
					+ "<span style='font-weight: bold;'>Your overall score on this assignment is " + s.getPctScore() + "%<br/>" + badgeName + "</span>"
					+ "</div>");
		}
		
		if (studentScore==q.pointValue) buf.append("</div>"); // end of badge+feedback container
		debug.append("6");
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
				+ "<OPTION VALUE=8" + (questionType==8?" SELECTED>":">") + "Chemical Structure</OPTION>"
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
						
						if (t.score==1) buf.append("<td><img src=/images/checkmark.png alt='checkmark' height=24 width=17></td>");
						else if (q.agreesToRequiredPrecision(t.studentAnswer)) buf.append("<td><img src=/images/partCredit.png alt='partial credit' height=25 width=25></td>");
						else buf.append("<td><img src=/images/xmark.png alt='x-mark' height=24 width=24></td>");
						buf.append("</tr>");
					}
					buf.append("</table><br/>");
				}
				buf.append("</td></tr>");
			}
			buf.append("</table><br/>");
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
		StringBuffer buf = new StringBuffer("<h1>Customize Homework Assignment</h1>");
		
		String assignmentType = request.getParameter("AssignmentType");
		Concept c = new Concept();
		try {
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			c = ofy().load().type(Concept.class).id(conceptId).now();
		} catch (Exception e) {
		}
			
		buf.append("<h2>" + (assignmentType.equals("Custom")?"Custom Questions": assignmentType + " Questions") + "</h2>");
		buf.append("<a href='/Homework?UserRequest=Instructor&sig=" + user.getTokenSignature() + "'>Return to the Instructor Page</a><br/><br/>");
		
		List<Question> questions = null;
		if (assignmentType.equals("Custom")) { // Load Custom questions for this instructor
			buf.append("<button class='btn btn-secondary' onclick=\"location.href='/Homework?UserRequest=CreateCustomQuestion&sig=" + user.getTokenSignature() + "';\">Create A New Custom Question</button>"
				+ " or "
				+ "<button class='btn btn-secondary' onclick=\"location.href='/Contribute?sig=" + user.getTokenSignature() + "';\">Upload Custom Questions in Bulk</button><br/><br/>");
			questions = ofy().load().type(Question.class).filter("assignmentType","Custom").filter("authorId",user.getId()).list();
		} else { // Load Homework questions for the selected concept
			questions = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("conceptId",c.id).list();
		}

		if (questions.isEmpty()) {
			buf.append("There are currently no questions available here.<br/>");
			return buf.toString();
		} else {  // Allow instructor to pick individual question items from all active questions:
			buf.append("Select/unselect the questions to be assigned for grading, then click the 'Use Selected Items' button.<br/>");
		}

		// Make 2 lists of Assigned and Optional questions:
		StringBuffer assignedQuestions = new StringBuffer();
		StringBuffer optionalQuestions = new StringBuffer();
		int i = 0;  // counter for assigned questions
		int j = 0;  // counter for optional questions
		
		// For Custom questions, we show optional (not already assigned) questions in 2 alternative ways: 
		// a) those with concepts that are included in the assignment concepts, or
		// b) all Custom questions for this instructor
		// Defaults to b) if the number of questions in option a) is zero or if 'ShowAllCustomQuestions' is contained in the request object
		int nUnassignedQuestionsWithConcepts = 0;
		if (assignmentType.equals("Custom")) {
			for (Question q : questions) {
				if (q.conceptId!=null && a.conceptIds.contains(q.conceptId) && !a.questionKeys.contains(key(q))) nUnassignedQuestionsWithConcepts++;
			}
		}
		
		// Option to display all cvustom questions for this instructor, even if they have concepts that are not included in the assignment concepts
		boolean showAllQuestions = request.getParameter("ShowAllCustomQuestions")!=null;

		if (questions.size()>1) Collections.sort(questions,new SortBySuccessPct());
		for (Question q : questions) {
			boolean assigned = a.questionKeys.remove(key(q));
			// Ignore an optional question if it has a concept that is not included in the assignment concepts, unless the instructor has requested to show all Custom questions
			if (!assigned && !showAllQuestions && !(q.conceptId!=null && a.conceptIds.contains(q.conceptId))) continue;
			
			StringBuffer qbuf = new StringBuffer();
			q.setParameters();  // creates randomly selected parameters
			int successRate = q.getPctSuccess();
			qbuf.append("<TR>"
					+ "<TD style='vertical-align:top;' NOWRAP>"
					+ "<label>"
					+ "<INPUT TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'"
					+ (assigned?" CHECKED />":" />")
					+ "<b>&nbsp;" + (assigned?i+1:j+1) + ".</b>"
					+ "</label><br/>"
					+ "<span style='font-size:0.5em'>" + (successRate==0?"new item&nbsp;&nbsp;":successRate + "% correct&nbsp;&nbsp;") + "</span><br/>"
					+ (assignmentType.equals("Custom")?"<a href=/Homework?UserRequest=Preview&sig=" + user.getTokenSignature() + "&QuestionId=" + q.id + ">Edit</a>":"")
					+ "</TD>"
					+ "<TD>" + q.printAll() + "</TD>"
					+ "</TR>");
			if (assigned) {
				i++;
				assignedQuestions.append(qbuf);
			} else {
				j++;
				optionalQuestions.append(qbuf);
			}
		}
		
		if (assignmentType.equals("Custom")) {
			buf.append(" Currently, there " + (i==1?"is":"are") + " " + i + " custom question " + (i==1?"item":"items") + " included in this assignment. ");
		} else {
			buf.append(" Currently, this concept has " + i + " assigned question " + (i==1?"item":"items") + " and " + j + " optional question " + (j==1?"item":"items") + ".");
		}
		buf.append("<br/><br/>");

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
				+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + c.id + "' />"  // Should be null for Custom questions, even if some/all of the questions are associated with a concept
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + a.id + "' />"
				+ "<INPUT TYPE=SUBMIT Value='Use Selected Items' /><br/><br/>");
	
		// Make a table of assigned and optional questions
		buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
		if (!assignedQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2><b>Assigned Questions:</b></TD></TR>");
			buf.append(assignedQuestions);
		}
		if (!optionalQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2>");
			if (assignmentType.equals("Custom")) {
				if (showAllQuestions) {
					buf.append("<b>All Available Custom Questions</b>");
					if (nUnassignedQuestionsWithConcepts != 0 && nUnassignedQuestionsWithConcepts != j) buf.append(" <a href='/Homework?UserRequest=AssignHomeworkQuestions&AssignmentType=Custom&sig=" + user.getTokenSignature() + "'>(show less)</a>");
				} else {
					buf.append("<b>Available Questions with Concepts Included in this Assignment</b>");
					if (questions.size() != i+j) buf.append(" <a href='/Homework?UserRequest=AssignHomeworkQuestions&AssignmentType=Custom&ShowAllCustomQuestions=true&sig=" + user.getTokenSignature() + "'>(show all)</a>");
				}
			} else {
				buf.append("Optional Questions");
			}
			buf.append("</TD></TR>");
			buf.append(optionalQuestions);
		} else if (assignmentType.equals("Custom") && questions.size() != i+j) {  // there are some questions that are not being displayed because they have concepts that are not included in the assignment concepts
			buf.append("<TR><TD COLSPAN=2><brt/><b>There " + (questions.size()-(i+j)==1?"is":"are") + " " + (questions.size()-(i+j)) + " additional question " + (questions.size()-(i+j)==1?"item":"items") 
				+ " that are not being displayed because they have not been assigned to concepts or have concepts that are not included in this assignment. You can "
				+ "<a href='/Homework?UserRequest=AssignHomeworkQuestions&AssignmentType=Custom&ShowAllCustomQuestions=true&sig=" + user.getTokenSignature() + "'>display all custom questions</a> "
				+ "and assign them to concepts using the 'Edit' links, if desired, or just use them as is in this assignment.</b></TD></TR>");
		}
		buf.append("</TABLE><br/><INPUT TYPE=SUBMIT Value='Use Selected Items'></FORM><br/>");
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
		return showSummary(user,a,false);
	}

	static String showSummary(User user, Assignment a, boolean showDetails) {
		StringBuffer buf = new StringBuffer();
		if (!user.isInstructor()) return "You must be logged in as the instructor to view this page.";
		try {
			buf.append("<h1>Homework Scores</h1>");
			buf.append("Title: "+ a.title + "<br/>");
			buf.append("Assignment ID: " + a.id + "<br/>");
			buf.append("Valid: " + new Date() + "<br/><br/>");
			
			Map<String,String> scores = LTIMessage.readMembershipScores(a);
			if (scores==null) throw new Exception("Failed to read membership scores from the LMS");  // this only works if we can get info from the LMS
			Map<String,String[]> membership = LTIMessage.getMembership(a);
			if (membership==null) throw new Exception("Failed to read membership from the LMS");  // there must be some members of this class
			Map<String,Key<Score>> keys = new HashMap<String,Key<Score>>();
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
			String platform_id = d.getPlatformId() + "/";
			for (String id : membership.keySet()) {
				keys.put(id,key(key(User.class,Subject.hashId(platform_id+id)),Score.class,a.id));
			}
			Map<Key<Score>,Score> cvScores = ofy().load().keys(keys.values());
			
			//if (showDetails)
				buf.append("<table style='text-align: center;'><tr><th>#</th><th>Name </th><th>Email </th><th>Role</th><th>LMS Score</th><th>CV Score</th><th>Submissions</tr>");
			
			int i=0;
			int nMismatched = 0;
			//String instructorEmail = null;
			//String currentUserLmsId = user.getId()==null?null:user.getId().substring(user.getId().lastIndexOf("/")+1);
			
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				/* 
				if (currentUserLmsId != null && currentUserLmsId.equals(entry.getKey()) && entry.getValue()!=null && entry.getValue().length>2) { // this is the current user, so save their email address for later
					String role = entry.getValue()[0];
					if (role != null && (role.contains("Instructor") || role.contains("Administrator"))) instructorEmail = entry.getValue()[2];
				}
				*/
				i++;
				String lmsScoreString = scores.get(entry.getKey());
				lmsScoreString = (lmsScoreString==null?" - ":lmsScoreString + "%");
				Score cvScore = cvScores.get(keys.get(entry.getKey()));
				String cvScoreString = cvScore==null?" - ":String.valueOf(cvScore.getPctScore() + "%");

				if ("Learner".equals(entry.getValue()[0]) // must be a Learner to be counted as a mismatched score
					&& !cvScoreString.equals(lmsScoreString) // the scores are different
					&& !(cvScoreString.equals(" - ") && Double.valueOf(scores.get(entry.getKey())) == 0.0) // except: 
				) nMismatched++;
				
				//if (showDetails)
					buf.append("<tr><td>" + i + ". </td>"
						+ "<td>" + entry.getValue()[1] + "</td>"
						+ "<td>" + entry.getValue()[2] + "</td>"
						+ "<td>" + entry.getValue()[0] + "</td>"
						+ "<td>" + lmsScoreString + "</td>"
						+ "<td>" + cvScoreString + "</td>"
						+ "<td><a href='/Homework?UserRequest=Review&ForUserName=" + URLEncoder.encode(entry.getValue()[1], "UTF-8") + "&ForUserId=" + platform_id + entry.getKey() + "&sig=" + user.getTokenSignature() + "'>View</a></td>"
						+ "</tr>");
			}
			//if (showDetails)
				buf.append("</table><br/>");
			
			if (nMismatched > 0) {
				buf.append("There " + (nMismatched == 1 ? "is 1 mismatched student score" : "are " + nMismatched + " mismatched student scores") + " between the LMS and ChemVantage. "
					+ "This may happen for one or more of the following reasons:<ul>"
					+ "<li>The instructor has manually overridden a score in the LMS grade book.</li>"
					+ "<li>A late student submission was not accepted by the LMS.</li>"
					+ "<li>The LMS was offline when ChemVantage tried to update the score.</li></ul><br/>");
				//if (!showDetails) 
					buf.append("<form method=post action=/Homework onsubmit=\"document.getElementById('syncScores').disabled=true;document.getElementById('syncScoresStatus').style.display='inline';return true;\">"
						+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
						+ "<input type=hidden name=UserRequest value='Synchronize Scores' />"
						+ "<input type=submit id=syncScores value='Synchronize Scores Now' />"
						+ "<span id='syncScoresStatus' style='display:none; margin-left:8px; color:#b20000;'>Synchronizing scores now. This may take a minute...</span>"
						+ "</form><br/><br/>");
			} else buf.append("All of the student ChemVantage scores are synchronized with the LMS grade book.<br/><br/>");
/* 
			if (instructorEmail == null || instructorEmail.isEmpty()) {
				buf.append("To protect privacy, individual scores are not shown.<br/><br/>");
			} else if (showDetails) {
				Utilities.sendEmail("",instructorEmail,"ChemVantage Homework Scores Report",buf.toString());
	return instructorPage(user,a);			} else {
				buf.append("<form id='emailReportForm' method=post action=/Homework onsubmit=\"document.getElementById('emailReport').disabled=true;document.getElementById('emailReportStatus').style.display='inline';return true;\">")
						.append("<input type=hidden name=sig value=" + user.getTokenSignature() + " />")
						.append("<input type=hidden name=UserRequest value='Email Report' />")
						.append("<input type=submit id=emailReport value='Get a detailed report via email' />")
						.append("<span id='emailReportStatus' style='display:none; margin-left:8px; color:#b20000;'>Sending the report now. This may take a minute...</span>")
						.append("</form>");
			} 
*/
		} catch (Exception e) {
			return buf.toString() + "<br/>Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>";
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
	
	JsonObject validateQuestionItemWithAI(User user, HttpServletRequest request) throws Exception {
		if (!user.isInstructor()) throw new Exception("You must be an instructor for this.");
		// always build the question from the form's current field values, so unsaved edits are validated
		Question q = assembleQuestion(request);
		try {
			long parameterSeed = Long.parseLong(request.getParameter("ParameterSeed"));
			if (q.requiresParser()) q.setParameters(parameterSeed);
		} catch (Exception e) {}

		JsonObject api_response = Edit.requestValidationFromGemini(q);
		boolean isCorrect = api_response.get("isCorrect").getAsBoolean() || q.isCorrect(api_response.get("best_answer").getAsString());
		// persist the AI result onto the actual saved entity by updating only the AI fields,
		// so unsaved edits to other fields are not written to the datastore
		if (q.id != null) {
			Question saved = ofy().load().type(Question.class).id(q.id).now();
			if (saved != null) {
				saved.checkedByAI = isCorrect;
				ofy().save().entity(saved).now();
			}
		}
		return api_response;
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

