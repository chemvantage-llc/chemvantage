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
import static com.googlecode.objectify.ObjectifyService.key;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.googlecode.objectify.Key;

@WebServlet("/Quiz")
public class Quiz extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet presents a quiz for the user.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		response.setContentType("text/html");
		response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();			
		
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("Invalid user token (may have expired).");
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			
			switch (userRequest) {
			case "ShowQuiz":
				String quizTransactionId = request.getParameter("QuizTransactionId");
				out.println(Subject.header("Quiz") + showQuiz(user,a,quizTransactionId) + Subject.footer);
				break;
			case "ShowScores":
				String forUserHashedId = request.getParameter("ForUserHashedId");
				out.println(Subject.header("Scores") + showScores(user,a,forUserHashedId,null) + Subject.footer);
				break;
			case "ShowSummary":
				out.println(Subject.header("Class Scores") + showSummary(user,a) + Subject.footer);
				break;
			case "AssignQuizQuestions":
				if (user.isInstructor()) out.println(Subject.header("Customize Quiz") + selectQuestionsForm(user,a,request) + Subject.footer);
				break;
			case "SynchronizeScore":
				out.println(synchronizeScore(user,a,request.getParameter("ForUserId")));
				break;
			case "Logout":
				out.println(Logout.now(user));
				break;
			case "Instructor":
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			default: 
				out.println(Subject.header("Quiz") + printQuiz(user,a) + Subject.footer);
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
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			
			switch (userRequest) {
			case "UpdateAssignment":
				if (a!=null) a.updateConceptQuestions(user,request);
				out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			case "Save New Title":
				if (a!=null) {
					a.title = request.getParameter("AssignmentTitle");
					ofy().save().entity(a).now();
					out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
				break;
			case "Set Allowed Time":
				if (a != null) {
					try {
						double minutes = Double.parseDouble(request.getParameter("TimeAllowed"));
						if (minutes > 60.) minutes = 60.;
						a.timeAllowed = minutes<1.0?60:(int)(minutes*60);
					} catch (Exception e) {
						a.timeAllowed = 900;
					}
					ofy().save().entity(a).now();
					out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
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
				out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				break;
			case "Synchronize Scores":
				if (synchronizeScores(user,a)) out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				else out.println("Synchronization request failed.");
				break;
			case "AddKeyConcept":
				if (user.isInstructor()) {
					try {
						long conceptId = Long.parseLong(request.getParameter("ConceptId"));
						if (a!=null &&!a.conceptIds.contains(conceptId)) {
							a.conceptIds.add(conceptId);
							a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",conceptId).keys().list());	
							ofy().save().entity(a).now();
						}
					} catch (Exception e) {}
					out.println(Subject.header("Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
				break;
			default:
				out.println(Subject.header("Quiz Results") + printScore(user,a,request) + Subject.footer);
			}
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        	out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}
	
	static String instructorPage(User user,Assignment a) {
		if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		
		StringBuffer buf = new StringBuffer();		
		try {
			buf.append(Subject.privacyPolicyBanner());
			buf.append("<h1>Quiz</h1>"
					+ "<h2>" + a.title + "</h2>"
					+ "<h3>Instructor Page</h3>"
					+ "<a href='/Quiz?sig=" + user.getTokenSignature() + "' class='btn btn-primary'>Show This Assignment</a><br/><br/>"
					+ "<form action=/Quiz method=post>"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label><b>Title:</b>&nbsp;Quiz - <input type=text size=25 name=AssignmentTitle value='" + a.title + "' /></label>&nbsp;"
					+ "<input type=submit name=UserRequest value='Save New Title' /></form><br/>\n"
					+ "The number of attempts allowed for this quiz is " + (a.attemptsAllowed==null?"unlimited":a.attemptsAllowed) + ".<br/>"
					+ "<form action=/Quiz method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label><b>Attempts allowed:</b>&nbsp;<input type=text size=10 name=AttemptsAllowed " 
					+ (a.attemptsAllowed==null?"placeholder=unlimited":"value=" + a.attemptsAllowed) + " /></label> "
					+ "<input type=submit name=UserRequest value='Set Allowed Attempts' />"
					+ "</form><br/>\n");
			
			Map<Key<Question>, Question> questionMap = ofy().load().keys(a.questionKeys);
			
			// Ensure that all question keys in the assignment are valid; if not, remove them
			if (a.questionKeys.size() != questionMap.size()) {
				a.questionKeys = new ArrayList<Key<Question>>(questionMap.keySet());
				ofy().save().entity(a).now();
			}
			buf.append("The quiz draws " + (a.questionKeys.size()>10?"10 questions at random":"all questions") + " from a bank of " + a.questionKeys.size() + " assigned question items:<br/>");
			
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
				int nTotalQuestions = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",cId).count();
				buf.append("<tr>"
						+ "<td>" + (c==null?"(deleted concept)":c.title) + "</td>"
						+ "<td align=center>" + nQuestions + " of " + nTotalQuestions + "</td>"
						+ "<td align=center><a href='/Quiz?UserRequest=AssignQuizQuestions&sig=" + user.getTokenSignature() + "&ConceptId=" + cId + "'>Select Questions</a></td>"
						+ "</tr>");
			});
			buf.append("</table><br/>");

			// Create a short form to select one additional key concept to include (will exclude the previous selection, if any)
			buf.append("<form method=post action=/Quiz>"
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
			if (supportsMembership) buf.append("<a href='/Quiz?UserRequest=ShowSummary&sig=" + user.getTokenSignature() + "'>Review your students' quiz scores</a><br/>");
			
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

	String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String printQuiz(User user, Assignment qa) {
		if (user == null) return "<h2>Launch failed because user was not authorized.</h2>";
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			if (qa==null) {  // anonymous user; print a quiz on Chapter 1 of the first smartText entity
				qa = new Assignment();
				qa.id = 0L;
				qa.assignmentType = "Quiz";
				Text text = ofy().load().type(Text.class).filter("smartText",true).first().now();
				Chapter ch = text.chapters.get(0);
				qa.title = ch.title;
				for (Long cId : ch.conceptIds) qa.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",cId).keys().list());
			} else if (qa.title==null) {  // legacy Quiz only provided topicId
				Topic t = ofy().load().type(Topic.class).id(qa.topicId).now();
				qa.title = t.title;
				if (qa.conceptIds.isEmpty()) qa.conceptIds = t.conceptIds;
				ofy().save().entity(qa).now();
			}
			debug.append("1");
			// Check to see if the timeAllowed has been modified by the instructor:
			int timeAllowed = 900; // default value in seconds
			if (qa.timeAllowed != null) {
				timeAllowed = qa.timeAllowed; // instructor option, e.g. for student disability accommodations
				user = User.getUser(user.getTokenSignature(), timeAllowed / 60);
			}
			debug.append("2");
			
			// Check to see if this user has any pending quizzes on this topic:
			Date now = new Date();
			Date t15minAgo = new Date(now.getTime() - timeAllowed * 1000); // 15 minutes ago or whatever time was allowed
			
			QuizTransaction qt = null;
			int nAttempts = 0;
			try {  // check for existing live quiz
				qt = ofy().load().type(QuizTransaction.class).filter("userId", user.getHashedId()).filter("assignmentId", qa.id).filter("graded", null).filter("downloaded >", t15minAgo).first().safe();
				debug.append("a");
			} catch (Exception e) {  // make a new QuizTransaction
				nAttempts = qa.attemptsAllowed==null?0:ofy().load().type(QuizTransaction.class).filter("assignmentId",qa.id).filter("userId",user.getHashedId()).count();
				if (nAttempts>0 && nAttempts >= qa.attemptsAllowed) {
					return "<h1>Sorry, you are only allowed " + qa.attemptsAllowed + " attempt" + (qa.attemptsAllowed==1?"":"s") + " on this assignment.</h1>" + showScores(user,qa,user.getId(),null) + "<br/><br/>";
				}
				qt = new QuizTransaction(qa.id,user.getHashedId());
				ofy().save().entity(qt).now(); // creates a long id value to use in random number generator
				nAttempts++;			
				debug.append("b");
			}
			debug.append("3");

			buf.append("<h1>Quiz</h1>"
					+ "<h2>" + qa.title + "</h2>");

			buf.append("Quiz Rules"
					+ "	<ul>"
					+ "	<li>Each quiz must be completed within " + (timeAllowed / 60) + " minutes of the time when it is first downloaded.</LI>"
					+ (qa.attemptsAllowed==null?"<li>You may repeat this quiz as many times as you wish, to improve your score.</LI>":"<LI>You are allowed " + qa.attemptsAllowed + " attempts of this quiz assignment. This is attempt #" + nAttempts + ".</LI>")
					+ "	<li>ChemVantage always reports your best score on this assignment to your class LMS.</LI>"
					+ "	</ul>\n");
			
			buf.append("<div id='timer0' style='color: #B20000'></div>"
					+ "	<div id='ctrl0' style='color: #B20000'><a role='button' href=javascript:toggleTimers() >hide timers</a><p></div>");
			
			debug.append("5");
			
			buf.append("<FORM NAME=Quiz id=quizForm METHOD=POST ACTION='/Quiz' onSubmit='return confirmSubmission()' >"
					+ "<INPUT TYPE=HIDDEN NAME='sig' VALUE='" + user.getTokenSignature() + "' />"
					+ "<INPUT TYPE=HIDDEN NAME='AssignmentId' VALUE='" + qa.id + "' />"
					+ "<input type=hidden name='QuizTransactionId' value='" + qt.getId() + "' />"
					+ "<input type=submit class='btn btn-primary'value='Grade This Quiz' />");
			
			// Randomly select the keys to questions to be presented
			Random rand = new Random(); // create random number generator to select quiz questions
			rand.setSeed(qt.getId()); // random number generator seeded with QuizTransaction id value
			List<Key<Question>> allQuestionKeys = new ArrayList<Key<Question>>(qa.questionKeys);  // clone the List
			List<Key<Question>> myQuestionKeys = new ArrayList<Key<Question>>();
			while (myQuestionKeys.size()<10 && allQuestionKeys.size()>0) {
				Key<Question> k = allQuestionKeys.get(rand.nextInt(allQuestionKeys.size()));
				myQuestionKeys.add(k);
				allQuestionKeys.remove(k);
			}
			debug.append("5");
			
			// At this point we should have 10 (or max number) randomly selected questionKeys
			Map<Key<Question>, Question> quizQuestions = ofy().load().keys(myQuestionKeys);
			
			// Housekeeping: if any of the questions does not exist, remove the key from the assignment:
			if (!quizQuestions.keySet().containsAll(myQuestionKeys)) {
				for (Key<Question> k : myQuestionKeys) if (!quizQuestions.keySet().contains(k)) qa.questionKeys.remove(k);
				ofy().save().entity(qa);
			}
			
			int possibleScore = 0;
			buf.append("<OL>");
			
			while (myQuestionKeys.size() > 0) {
				Key<Question> k = myQuestionKeys.get(rand.nextInt(myQuestionKeys.size()));
				myQuestionKeys.remove(k);
				Question q = quizQuestions.get(k);
				possibleScore += q.getPointValue();
				// the parameterized questions are seeded with a value based on the ids for the quizTransaction and the question
				// in order to make the value reproducible for grading but variable for each quiz and from one question to the next
				long seed = Math.abs(qt.getId() - q.getId());
				if (seed == -1) seed--; // -1 is a special value for randomly seeded Random generator; avoid this (unlikely) situation
				q.setParameters(seed); // the values are subtracted to prevent (unlikely) overflow
				
				buf.append("<li>" + q.print() +  "<br/></li>");
			}
			
			qt.putPossibleScore(possibleScore);
			ofy().save().entity(qt).now();
			buf.append("</OL>\n");
		
			buf.append("<div id='timer1' style='color: #B20000'></div>"
					+ "	<div id='ctrl1' style='color: #B20000'><a role='button' href=javascript:toggleTimers() >hide timers</a><p></div>");
			
			buf.append("<input type=submit class='btn btn-primary' value='Grade This Quiz'/>"
					+ "</FORM>");
			
			buf.append("<script>"
					+ "startTimers(" + (new Date(qt.getDownloaded().getTime() + timeAllowed * 1000).getTime() - new Date().getTime()) + ");"
					+ "function timesUp() {"
					+ "document.getElementById('Quiz').submit();}"
					+ "</script>");
			
		} catch (Exception e) {
			return "<h2>Sorry, the quiz failed</h2>" + e.getMessage()==null?e.toString():e.getMessage() + buf.toString() + "<br/>" + debug.toString(); 
		}
		buf.append("<script>function showWorkBox() {}</script>"); // prevents javascript error showing the ShowWork box for numeric questions
		return buf.toString();
	}
	
	String printScore(User user,Assignment qa,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		boolean premiumUser = true;
		try {
			Date now = new Date();
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
			
			long transactionId = Long.parseLong(request.getParameter("QuizTransactionId"));
			QuizTransaction qt = ofy().load().type(QuizTransaction.class).id(transactionId).safe();
			
			// if this Quiz has already been graded, stop here.
			if (qt.graded != null) {
				return "<h2>No Score</h2>"
						+ "Sorry, this quiz was graded on " + df.format(qt.graded) + " and cannot be regraded.<p>"
						+ "Your score on this quiz was " + qt.score + " out of a possible " + qt.possibleScore + " points.<br/><br/>";
				}

			if (qa==null) {  // anonymous user; use the quiz on Chapter 1 of the first smartText entity
				qa = new Assignment();
				qa.id = 0L;
				qa.assignmentType = "Quiz";
				Text text = ofy().load().type(Text.class).filter("smartText",true).first().now();
				qa.title = text.chapters.get(0).title;
			}
			
			int timeAllowed = 900;  // default time to complete the quiz, in seconds
			try {
				timeAllowed = qa.timeAllowed>0?qa.timeAllowed:900;  // override the default timeAllowed if qa.tinmeAllowed exists
			} catch (Exception e) {}
			
			boolean timeExpired = now.getTime() - qt.downloaded.getTime() > (timeAllowed*1000+10000);
			
			int studentScore = 0;
			int wrongAnswers = 0;

			buf.append("<h1>Quiz Results</h1><h2>" + qa.title + "</h2>");
			
			buf.append(df.format(now) + "<br/>");
			
			// Create a StringBuffer to contain correct answers to questions answered correctly
			List<String> missedQuestions = new ArrayList<String>();	 // questions with correct answers
			
			// For each question the form contains a parameter: (questionId,studentAnswer)
			// Make a list of the question keys. Non-numeric inputs are ignored (catch and continue).
			List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
			for (Enumeration<?> e = request.getParameterNames();e.hasMoreElements();) {
				try {
					questionKeys.add(key(Question.class,Long.parseLong((String) e.nextElement())));
				} catch (Exception e2) {}
			}
			Map<Key<Question>,Question> quizQuestions = ofy().load().keys(questionKeys);
			
			// This is the main scoring loop:
			for (Key<Question> k : questionKeys) {
				try {
					String studentAnswer = orderResponses(request.getParameterValues(Long.toString(k.getId())));
					if (!studentAnswer.isEmpty()) {
						Question q = quizQuestions.get(k);
						long seed = Math.abs(qt.id - q.id);
						if (seed==-1) seed--;  // -1 is a special value for randomly seeded Random generator; avoid this (unlikely) situation
						q.setParameters(seed);
						int score = q.isCorrect(studentAnswer)?q.pointValue:0;

						q.addAttemptsNoSave(1, score>0?1:0);  // update Question stats
						
						qt.questionKeys.add(k);
						qt.questionScores.put(k, score);
						qt.studentAnswers.put(k, studentAnswer);
						qt.correctAnswers.put(k, q.getCorrectAnswer());
						
						studentScore += score;
						if (score == 0) {  
							// include question in list of incorrectly answered questions
							wrongAnswers++;
							missedQuestions.add("<LI>" + q.printAllToStudents(studentAnswer,premiumUser) + "</LI>");
						}
					}
				} catch (Exception e2) {
					continue;  // this parameter does not correspond to a questionId
				}
			}
			qt.graded = now;
			qt.score = timeExpired?0:studentScore;
			ofy().save().entity(qt);
			ofy().save().entities(quizQuestions.values());  // save updated questions
			
			// Try to post the score to the student's LMS:
			try {
				if (user.isAnonymous()) throw new Exception();  // don't save Scores for anonymous users
				Score.updateQuizScore(user.getId(),qt);
				if (qa.lti_ags_lineitem_url != null) {
					Utilities.createTask("/ReportScore","AssignmentId=" + qa.id + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8"));
				}
			} catch (Exception e) {}

			if (timeExpired) buf.append("<b>Your score on this quiz is 0 points because it was submitted after the allowed time of " + timeAllowed/60 + " minutes.</b><br/>");
			else buf.append("<b>Your score on this quiz is " + studentScore 
					+ " point" + (studentScore==1?"":"s") + " out of a possible " + qt.possibleScore + " points.</b><br/>");

			if (studentScore == qt.possibleScore && !timeExpired) {
				buf.append("<h2>Congratulations on a perfect score! Good job.</h2>");
			} else {
				int leftBlank = qt.possibleScore - studentScore - wrongAnswers;
				if (leftBlank>0) buf.append(leftBlank + " question" 
						+ (leftBlank>1?"s were":" was") + " left unanswered (blank).<br/>");
				buf.append(wrongAnswers + " question" + (wrongAnswers==1?" was":"s were") + " answered incorrectly. ");

				if (wrongAnswers>0 && !user.isAnonymous()) {
					// Display the correct answers to missed problems. However, discourage submission of empty or deliberately wrong answers:
					// If no answers were correct, give no correct answers.
					// If 1 answer was correct, give up to 2 correct answers.
					// If 2 answers were correct, give up to 4 correct answers.
					// If 3 answers were correct, give up to 5 correct answers.
					// If 4 answers were correct, give up to 6 correct answers.
					int nAnswersEligible = 0;
					switch (studentScore) {
					case 0: nAnswersEligible = 0; break;
					case 1: nAnswersEligible = Math.min(wrongAnswers,2); break;
					case 2: nAnswersEligible = Math.min(wrongAnswers,4); break;
					case 3: nAnswersEligible = Math.min(wrongAnswers,5); break;
					default: nAnswersEligible = wrongAnswers;
					if (!premiumUser) nAnswersEligible = wrongAnswers; // but correct answers are not displayed...
					}
					
					if (nAnswersEligible > 0) {
						buf.append("<a id=wrongAnsLink role='button' class='btn btn-primary' href=# onClick=document.getElementById('wrongAnsLink').style='display:none';document.getElementById('wrongAnsDiv').style='display:inline'>show me</a> ");
						buf.append("<div id=wrongAnsDiv style='display:none'>");
						buf.append("The correct answer" + (nAnswersEligible>1?"s ":" ") + (nAnswersEligible<wrongAnswers?"to " + nAnswersEligible + " of these ":"") + (nAnswersEligible==1?"is":"are") + " shown below. ");
						if (nAnswersEligible < wrongAnswers) buf.append("The more questions you answer correctly, the more correct answers to missed questions will be displayed.");
						buf.append("<OL>");
						for (int i=0;i<wrongAnswers;i++) {
							if (nAnswersEligible > 0) buf.append(missedQuestions.get(i));
							nAnswersEligible --;
						}
						buf.append("</OL>");
						buf.append("</div>");
					}  else buf.append("You must answer at least one question correctly to view the correct answers to questions that you missed. ");
				}
				buf.append("<br/><br/>");
			}
			
			// if the user response was correct, seek five-star feedback:
			if (studentScore == qt.possibleScore && !timeExpired) buf.append(Feedback.fiveStars(user.getTokenSignature()));
			else buf.append("Please take a moment to <a href=/Feedback?sig=" + user.getTokenSignature() + ">tell us about your ChemVantage experience</a>.<p>");

			if (!user.isAnonymous()) buf.append("You may <a href=/Quiz?UserRequest=ShowScores&sig=" + user.getTokenSignature() + ">review all your scores on this assignment</a>.<p>") ;

			if (!user.isAnonymous() && qa.lti_ags_lineitem_url == null) {
				buf.append("<b>Please note:</b> Your score was not reported back to the grade book of your class "
						+ "LMS because the LTI launch request did not contain enough information to do this. "
						+ (user.isInstructor()?"For instructors this is common.":"") + "<p>");				
			}
			
			if (!user.isAnonymous()) buf.append("Use the assignment link in your learning management system to repeat this quiz, or <a href=/Quiz?sig=" + user.getTokenSignature() + "&UserRequest=Logout >logout of ChemVantage</a>");
		} catch (Exception e) {
			buf.append("Sorry, this quiz could not be scored.<br>" + e.getMessage());
		}
		return buf.toString();
	}

	String selectQuestionsForm(User user,Assignment a,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		Concept c = null;
		try {
			buf.append("<h1>Customize Quiz</h1>");
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			c = ofy().load().type(Concept.class).id(conceptId).now();
		} catch (Exception e) {
			buf.append("Error: " + e.getMessage());
			return buf.toString();
		}
			
		buf.append("<h2>Concept: " + c.title + "</h2>");
		buf.append("<a href='/Quiz?UserRequest=Instructor&sig=" + user.getTokenSignature() + "'>Return to the Instructor Page</a><br/><br/>");
		
		List<Question> questions =  ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",c.id).list();
		if (questions.isEmpty()) {
			buf.append("There are currently no active questions available.<br/>");
			return buf.toString();
		} else {  // Allow instructor to pick individual question items from all active questions:
			buf.append("Select/unselect the quiz questions below to be assigned for grading, "
				+ "then click the 'Use Selected Items' button. All questions have equal point value. ");
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
					+ (c.title.equals("Custom")?"<a href=/Quiz?UserRequest=Preview&sig=" + user.getTokenSignature() + "&QuestionId=" + q.id + ">Edit</a>":"")
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
		
		buf.append("Currently, this concept has " + (i-1) + " assigned question items and " + (j-1) + " unassigned questions.<br/><br/>");
			
		// This dummy form uses javascript to select/deselect all questions
		buf.append("<FORM style='display:inline;' NAME=DummyForm><label><INPUT id=selectAll TYPE=CHECKBOX NAME=SelectAll "
			+ "onClick='for (var i=0;i<document.Questions.QuestionId.length;i++)"
			+ "{document.Questions.QuestionId[i].checked=document.DummyForm.SelectAll.checked;}'"
			+ "> Select/Unselect All</label></FORM>&nbsp;&nbsp;&nbsp;");
		buf.append("<script>document.getElementById('selectAll').indeterminate=true;</script>");
			
		// Make a list of individual questions that can be selected or deselected for this assignment
		buf.append("<FORM style='display:inline;' NAME=Questions METHOD=POST ACTION=/Quiz />"
				+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + " />"
				+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE='UpdateAssignment' />"
				+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + c.id + "' />"
				+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + a.id + "' />"
				+ "<INPUT TYPE=SUBMIT Value='Use Selected Items' /><br/><br/>");
	
		// Make a table of assigned and optional questions
		buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
		if (!assignedQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2><b>Assigned Questions:</b></TD></TR>");
			buf.append(assignedQuestions);
		}
		if (!optionalQuestions.isEmpty()) {
			buf.append("<TR><TD COLSPAN=2><b>Available Questions:</b></TD></TR>");
			buf.append(optionalQuestions);
		}
		buf.append("</TABLE><INPUT TYPE=SUBMIT Value='Use Selected Items'></FORM><br/>");
		return buf.toString();
	}

/* 
	String selectQuestionsForm(User user,Assignment a, HttpServletRequest request) {
		if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		
		StringBuffer buf = new StringBuffer();
		try {
			buf.append("<h1>Customize Quiz</h1>");
			buf.append("<form action=/Quiz method=post>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<label><b>Title:</b>&nbsp;<input type=text size=25 name=AssignmentTitle value='" + a.title + "' />&nbsp;"
					+ "<input type=submit name=UserRequest value='Save New Title' /></label></form><br/>");
					
			if (a.timeAllowed==null) a.timeAllowed = 900; // default time for completing the exam
			
			// Allow instructor to pick individual question items from all active questions:
			buf.append("Each quiz consists of 10 questions selected at random from the items below. The default time allowed "
					+ "to complete each quiz is 15 minutes, but you may change this (e.g., to create a special assignment for "
					+ "a student requiring extended time up to 60 minutes).<br>");
			buf.append("<form action=/Quiz method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label>Time allowed for this assignment: <input type=text size=5 name=TimeAllowed value=" + a.timeAllowed/60. + "></label> minutes. "
					+ "<input type=submit name=UserRequest value='Set Allowed Time'><br>"
					+ "</form><p>");
			buf.append("By default, students may attempt this quiz as many times as they wish. This rewards students who persist "
					+ "to achieve a better score. However, you may limit the number of attempts here. Leave the field blank to permit unlimited attempts.<br/>"
					+ "<form action=/Quiz method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label>Number of attempts allowed for this assignment: <input type=text size=10 name=AttemptsAllowed " 
					+ (a.attemptsAllowed==null?"placeholder=unlimited":"value=" + a.attemptsAllowed) + " /></label> "
					+ "<input type=submit name=UserRequest value='Set Allowed Attempts' />"
					+ "</form><br/>");
			
			buf.append("You may select the items that will be used for this assignment by checking the boxes in the left column below. "
					+ "Students are provided answers to the items that they answer incorrectly. Therefore, the total number of questions should be "
					+ "larger than 10, but not much larger than 50.  Experience shows that 40 items is about right in most cases. "
					+ "If you don't see a question you want to include, you may "
					+ "<a href=/Contribute?AssignmentType=Quiz&sig=" + user.getTokenSignature() + ">contribute a new question item</a> to the database.<br/><br/>");
	
			// make a List of conceptIds covered by this assignment
			List<Long> conceptIds = a.conceptIds;
			// Include any conceptId included in this request:
			Long newConceptId = null;
			try {
				newConceptId = Long.parseLong(request.getParameter("ConceptId"));
				conceptIds.add(newConceptId);
			} catch (Exception e) {}
	
			// Make a list of key concepts already covered by this assignment:
			List<Key<Concept>> conceptKeys = ofy().load().type(Concept.class).order("orderBy").keys().list();
			Map<Key<Concept>,Concept> keyConcepts = ofy().load().keys(conceptKeys);
			if (conceptIds.size()>0) {
				buf.append("The questions listed below cover the following key concepts:<ul>");
				for (Long cId : conceptIds) buf.append("<li>" + keyConcepts.get(key(Concept.class,cId)).title + "</li>");
				buf.append("</ul>");
			}
			// Create a short form to select one additional key concept to include (will exclude the previous selection, if any)
			buf.append("<form method=get action=/Quiz>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<label for='new_concept_id'>You may include additional question items from: </label>"
					+ "<input type=hidden name=UserRequest value=AssignQuizQuestions />"
					+ "<select id=new_concept_id name=ConceptId onchange=this.form.submit();><option value='Select'>Select a key concept</option>");
			for (Key<Concept> k : conceptKeys) {
				try {
					if (conceptIds.contains(k.getId()) || keyConcepts.get(k).orderBy.startsWith(" 0")) continue;  // skip current and hidden conceptIds
					buf.append("<option value='" + k.getId() + "'" + (newConceptId!=null && k.getId()==newConceptId?" selected>":">") + keyConcepts.get(k).title + "</option>");
				} catch (Exception e) {}
			}
			buf.append("</select></form><hr><br/>");
						
			// now we have all of the relevant conceptIds. Make a list of questions carrying these attributes:
			List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
			for (Long cId : conceptIds) questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",cId).keys().list());
	
			Map<Key<Question>,Question> questions = ofy().load().keys(questionKeys);
	
			if (!questionKeys.containsAll(a.questionKeys)) {  // might be missing a few questions due to customization
				questions.putAll(ofy().load().keys(a.questionKeys));
				questionKeys = new ArrayList<Key<Question>>(questions.keySet()); // this avoids duplicate keys
			}
	
			// This dummy form uses javascript to select/deselect all questions
			buf.append("<FORM NAME=DummyForm>"
					+ "<label><INPUT id=selectAll TYPE=CHECKBOX NAME=SelectAll "
					+ "onClick='for (var i=0;i<document.Questions.QuestionId.length;i++)"
					+ "{document.Questions.QuestionId[i].checked=document.DummyForm.SelectAll.checked;}' /> "
					+ "Select/Unselect All</label></FORM>");
			buf.append("<script>document.getElementById('selectAll').indeterminate=true;</script>");
			
			// Make a list of individual questions that can be selected or deselected for this assignment
			buf.append("<FORM NAME=Questions METHOD=POST ACTION=/Quiz>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + ">"
					+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE='UpdateAssignment'>"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + a.id + "'>"
					+ "<INPUT TYPE=SUBMIT Value='Use Selected Items'>");
			buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
	
			int i=0;
			for (Question q : questions.values()) {
				i++;
				q.setParameters();
				buf.append("<TR><TD VALIGN=TOP NOWRAP>"
						+ "<INPUT id='q" + i + "' TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'");
				buf.append(a.questionKeys.contains(key(Question.class,q.id))?" CHECKED>":">");
				buf.append("<b>&nbsp;<label for='q" + i + "'>" + i + "</label>.</b></TD>");
				buf.append("<TD>" + q.printAll() + "</TD>");
				buf.append("</TR>");
			}
			buf.append("</TABLE><INPUT TYPE=SUBMIT Value='Use Selected Items'></FORM>");
		} catch (Exception e) {
			buf.append(e.toString() + " " + e.getMessage());
		}
		return buf.toString();
	}
*/
	static String showQuiz (User user, Assignment a, String quizTransactionId) {
		StringBuffer buf = new StringBuffer();
		try {
			Long qtId = Long.parseLong(quizTransactionId);
			QuizTransaction qt = ofy().load().type(QuizTransaction.class).id(qtId).safe();
			
			buf.append("<h1>Quiz Results</h1>");
			buf.append("Topic: " + a.title + "<br/>");
			buf.append("Submitted: " + qt.graded + "<br/>");
			buf.append("Score: " + String.valueOf(qt.score>0?qt.score*100/qt.possibleScore:0)+"%<br/>");
			
			buf.append("<ol>");
			
			List<Question> questions = new ArrayList<Question>(ofy().load().keys(qt.questionKeys).values());
			for (Question q : questions) {
				q.setParameters(Math.abs(qt.getId() - q.getId()));
				buf.append("<li>" + q.printAllToStudents(qt.studentAnswers.get(key(q))) + "</li>");
			}
			buf.append("</ol>");
			
			int nUnanswered = qt.possibleScore - questions.size();
			if (questions.size()==0) buf.append("The detailed responses to this quiz are not available.");
			else if (nUnanswered>0) buf.append(String.valueOf(nUnanswered) + " question" + (nUnanswered>1?"s were":" was") + " left blank.");
			
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}
	
	static String showScores(User user, Assignment a, String for_user_hashed_id, String for_user_name) {
		// This method shows a table of QuizTransaction entities for one user on the assignment
		// with links to view individual Quiz submissions
		StringBuffer buf = new StringBuffer();
		
		if (!user.isInstructor() || for_user_hashed_id == null) for_user_hashed_id = user.getHashedId();  // non-instructors can only view their own records
		
		try {
			buf.append("<h1>Quiz Transactions</h1>");
			if (for_user_name != null) buf.append("Name: " + for_user_name + "<br/>");
			buf.append("<h2>Topic: "+ a.title + "</h2>");
			buf.append("Assignment ID: " + a.id + "<br/>");
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
			buf.append("Valid: " + df.format(new Date()) + "<p>");
			
			List<QuizTransaction> qts = ofy().load().type(QuizTransaction.class).filter("userId",for_user_hashed_id).filter("assignmentId",a.id).order("downloaded").list();
			if (qts.size()==0) {
				buf.append("Sorry, we did not find any records for this user on this assignment.<p>");
				return buf.toString();
			}	
		
			buf.append("<div style='display:table;text-align:center;'>");
			buf.append("<div style='display:table-row'>"  // header row
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Attempt</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Downloaded</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Elapsed Time</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Total Score</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'></div>"
					+ "</div>"); // end of header row
			for (QuizTransaction qt : qts) {
				String elapsedTime = qt.graded==null?" - ":String.valueOf((qt.graded.getTime()-qt.downloaded.getTime())/60000L) + " min " + String.valueOf((qt.graded.getTime()-qt.downloaded.getTime())/1000L%60L) + " s";
				String score = qt.graded==null?"no submission":String.valueOf(qt.score>0?qt.score*100/qt.possibleScore:0)+"%";
				buf.append("<div style='display:table-row'>"  // row for one transaction
						+ "<div style='display:table-cell;padding-right:20px;'>" + (qts.indexOf(qt)+1) + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + qt.downloaded + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + elapsedTime + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + score + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + (qt.studentAnswers.size()>0?"<a href=/Quiz?UserRequest=ShowQuiz&QuizTransactionId=" + qt.id + "&sig=" + user.getTokenSignature() + "&ForUserHashedId=" + for_user_hashed_id + ">View Quiz</a>":"") + "</div>"
						+ "</div>");  // end of row
			}
			buf.append("</div><br/><br/>"); // end of table
		} catch (Exception e) {
			 buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}

	static String showSummary(User user,Assignment a) {
		if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		
		StringBuffer buf = new StringBuffer();
		if (a==null) return "No assignment was specified for this request.";
		
		if (a.lti_ags_lineitem_url != null && a.lti_nrps_context_memberships_url != null) {
			try { 
				buf.append("<h1>Quiz</h1><h2>" + a.title + "</h2>");
				buf.append("Assignment ID: " + a.id + "<br>");
				buf.append("Valid: " + new Date() + "<p>");
				buf.append("The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, "
						+ "and may or may not include user's names or emails, depending on the settings of your LMS.<br/><br/>");

				Map<String,String> scores = LTIMessage.readMembershipScores(a);
				if (scores==null) scores = new HashMap<String,String>();  // in case service call fails
				
				buf.append("We downloaded " + scores.size() + " scores from your LMS.<br>");
				
				Map<String,String[]> membership = LTIMessage.getMembership(a);
				if (membership==null) membership = new HashMap<String,String[]>(); // in case service call fails
				
				buf.append("There are " + membership.size() + " members of this group.<p>");
				
				Map<String,Key<Score>> keys = new HashMap<String,Key<Score>>();
				Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
				String platform_id = d.getPlatformId() + "/";
				
				for (String id : membership.keySet()) {
					String hashedUserId = Subject.hashId(platform_id + id);
					keys.put(id,key(key(User.class,hashedUserId),Score.class,a.id));
				}
				Map<Key<Score>,Score> cvScores = ofy().load().keys(keys.values());
				
				buf.append("<table><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th><th>Details</th></tr>");
				int i=0;
				int nMismatched = 0;
				for (Map.Entry<String,String[]> entry : membership.entrySet()) {
					if (entry == null) continue;
					String s = scores.get(entry.getKey());
					String lmsScoreString = (s==null?" - ":s + "%");
					Score cvScore = cvScores.get(keys.get(entry.getKey()));
					String cvScoreString = cvScore==null?" - ":String.valueOf(cvScore.getPctScore() + "%");
					String forUserHashedId = keys.get(entry.getKey()).getParent().getName();
					boolean synched = !"Learner".equals(entry.getValue()[0]) || cvScoreString.equals(lmsScoreString);
					if (!synched) nMismatched++;  // number of Learner scores not same in CV and LMS
					String forUserId = platform_id + entry.getKey();  // only send hashed values through links					
					i++;
					buf.append("<tr><td>" + i + ".&nbsp;</td>"
							+ "<td>" + entry.getValue()[1] + "</td>"
							+ "<td>" + entry.getValue()[2] + "</td>"
							+ "<td>" + entry.getValue()[0] + "</td>"
							+ "<td align=center>" + (s == null?" - ":lmsScoreString) + "</td>"
							+ "<td align=center>" + (cvScore == null?" - ":String.valueOf(cvScore.getPctScore()) + "%") + "</td>"
							+ "<td>" +  (cvScore == null?"":"<a href=/Quiz?UserRequest=ShowScores&sig=" + user.getTokenSignature() + "&ForUserHashedId=" + forUserHashedId + ">Show Scores</a>") + "</td>"
							+ (synched?"":"<td><span id='cell" + forUserId + "'><button onClick=this.disabled=true;synchronizeScore('" + forUserId + "','" + user.getTokenSignature() + "','/Quiz'); >sync</button></span></td>")
							+ "</tr>");
				}
				buf.append("</table><br/>");
				if (nMismatched > 0) {
					buf.append("You may use the individual 'sync' buttons above to resubmit any ChemVantage score to the LMS. Note that in some cases, mismatched scores are expected (e.g., when "
							+ "the instructor overrides a score or when a late submission is not accepted by the LMS). You may have to adjust the settings in your LMS to accept the "
							+ "revised score (e.g., change the due date, grade override or allowed number of submissions). ");
				}
				if (nMismatched>1) {
					buf.append("Use the button below to synchronize all of the Learner scores. This might take a minute, depending on the number of mismatches.<br/>"
						+ "<form method=post action=/Quiz onsubmit=waitforSync(); >"
						+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
						+ "<input type=hidden name=UserRequest value='Synchronize Scores' />"
						+ "<input type=submit id=syncAll value='Synchronize All Scores' />"
						+ "</form>");
				}
				return buf.toString();
			} catch (Exception e) {
				buf.append(e.toString());
			}
		} else {
			buf.append("Sorry, there is not enough information available from your LMS to support this request.<p>");			
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
	
	boolean synchronizeScores(User user,Assignment a) {
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
				Utilities.createTask("/ReportScore","AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8"));
			}
		} catch (Exception e) {
			return false;
		}
		return true;
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
