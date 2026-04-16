/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2014 ChemVantage LLC
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

@WebServlet("/PracticeExam")
public class PracticeExam extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
	public String getServletInfo() {
		return "This servlet presents and scores an exam for the user.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
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
				case "AssignExamQuestions":
					if (!user.isInstructor()) throw new Exception();
					out.println(Subject.header("Select ChemVantage Practice Exam Topics") + selectExamQuestionsForm(user,request) + Subject.footer);
					break;
				case "ReviewExamScores":
					out.println(Subject.header("Review ChemVantage Practice Exam Scores") + reviewExamScores(user,a) + Subject.footer);
					break;
				case "ReviewExam":
					long practiceExamTransactionId = Long.parseLong(request.getParameter("PracticeExamTransactionId"));
					String studentUserId = request.getParameter("UserId");
					out.println(Subject.header("Review ChemVantage Practice Exam") + reviewExam(user,a,practiceExamTransactionId,studentUserId) + Subject.footer);
					break;
				case "SubmissionReview":
					User forUser = new User(user.platformId,request.getParameter("ForUserId"));
					out.println(Subject.header("ChemVantage Practice Exam") + submissionReview(user,forUser) + Subject.footer);
				default: 
					out.println(Subject.header("ChemVantage Practice Exam") + printExam(user,a,request) + Subject.footer);
			}
		} catch (Exception e) {
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

			switch(userRequest) {
			case "PrintExam":
				out.println(Subject.header("ChemVantage Practice Exam") + printExam(user,a,request) + Subject.footer);
				break;
			case "UpdateAssignment":
				if (a != null) {
					a.updateQuestions(request);
					out.println(Subject.header("ChemVantage Practice Exam") + instructorPage(user,a) + Subject.footer);
				}
				break;
			case "Submit Revised Exam Score":
				if (submitRevisedExamScore(user,a,request)) out.println(Subject.header("Review ChemVantage Practice Exam Scores") + reviewExamScores(user,a) + Subject.footer);
				else out.println("Sorry, an unexpected error occurred. Please go BACK and try again.");
				break;
			case "DeleteSubmission":
				if (user.isInstructor()) {
					try {
						Long tid = Long.parseLong(request.getParameter("tid"));
						ofy().delete().key(key(PracticeExamTransaction.class,tid)).now();
					} catch (Exception e) {}
					out.println(Subject.header("ChemVantage Instructor Page") + reviewExamScores(user,a) + Subject.footer);
				}
				break;
			case "Set Allowed Time":
				if (user.isInstructor() && a != null) {
					try {
						double minutes = Double.parseDouble(request.getParameter("TimeAllowed"));
						if (minutes > 300.) minutes = 300.;
						a.timeAllowed = minutes<1.0?60:(int)(minutes*60);  // time is entered in minutes, but recorded in seconds
					} catch (Exception e) {
						a.timeAllowed = 3600;
					}
					ofy().save().entity(a).now();
					out.println(Subject.header("ChemVantage Practice Exam") + instructorPage(user,a) + Subject.footer);
				}
				break;
			case "Set Allowed Attempts":
				if (user.isInstructor() && a != null) {
					try {
						a.attemptsAllowed = Integer.parseInt(request.getParameter("AttemptsAllowed"));
						if (a.attemptsAllowed<1) a.attemptsAllowed = null;
					} catch (Exception e) {
						a.attemptsAllowed = null;
					}
					ofy().save().entity(a).now();
					out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
				break;
			case "Set Password":
				if (user.isInstructor() && a != null) {
					a.password = request.getParameter("ExamPassword");
					if (a.password != null) a.password = a.password.trim();
					ofy().save().entity(a).now();
					out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				}
				break;
			default: out.println(Subject.header("ChemVantage Practice Exam Results") + printScore(user,a,request) + Subject.footer);
			}
		} catch (Exception e) {
			out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	static String instructorPage(User user,Assignment a) {
		StringBuffer buf = new StringBuffer();		
		try {
			buf.append(Subject.privacyPolicyBanner());
			buf.append("<h1>General Chemistry Exam</h1><h2>Instructor Page</h2>");
			
			if (a.conceptIds.isEmpty()) { // legacy assignment uses topicIds
				List<Topic> topics = new ArrayList<Topic>(ofy().load().type(Topic.class).ids(a.topicIds).values());
				for (Topic t : topics) {
					a.conceptIds.addAll(t.conceptIds);
				}
				ofy().save().entity(a).now();
			}
			
			buf.append("Students are permitted " + (a.timeAllowed==null?60:a.timeAllowed/60) + " minutes to complete this exam.<br/>");
			if (a.attemptsAllowed==null || a.attemptsAllowed<1) buf.append("Students may attempt this assignment an unlimited number of times to improve their score.<br/><br/>");
			else buf.append("Students may only attempt this assignment " + a.attemptsAllowed + (a.attemptsAllowed==1?" time":" times") + ".<br/><br/>");
			
			boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
			buf.append("From here, you may<UL>"
					+ "<LI><a href='/PracticeExam?UserRequest=AssignExamQuestions&sig=" + user.getTokenSignature() + "'>Customize this exam</a> "
					+ "to set the time allowed, number of submissions, an optional password and select the available question items.</LI>"
					+ (supportsMembership?"<LI><a href='/PracticeExam?UserRequest=ReviewExamScores&sig=" + user.getTokenSignature() + "'>Review the exam results</a> and (optionally) assign partial credit for answers</LI>":"")
					+ "</UL>");
			
			if (a.password != null && !a.password.isEmpty()) {
				buf.append("<b>The password for this exam is: " + a.password + "</b>");
			}
			
			buf.append("<a href='/PracticeExam?sig=" + user.getTokenSignature() + "' class='btn btn-primary'>Show This Assignment</a><br/><br/>");
			
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
	
	static String passwordPrompt(User user,String msg) {
		StringBuffer buf = new StringBuffer();
		buf.append("<h1>General Chemistry Exam</h1>");
		buf.append("<h2>Enter the password for this assignment</h2>");
		if (msg==null) msg="";
		buf.append("<div id='msgSpan' style='color:#EE0000'>" + msg + "</div><br/>");
		buf.append("Your instructor should provide you with the password.</br>"
				+ "<form method=post action=/PracticeExam >"
				+ "<input type=hidden name=sig value='" + user.getTokenSignature()  + "' />"
				+ "<input type=hidden name=UserRequest value=PrintExam />"
				+ "Password: <input type=password size=30 name='ExamPassword' /> "
				+ "<input id=start type=submit class='btn btn-primary' value='Begin the exam' disabled />"
				+ "</form><br/><br/>");	

  		buf.append("<script>"
 				+ "function enableSubmission() {"
  				+ "  document.getElementById('msgSpan').innerHTML = '';"
				+ "  document.getElementById('start').disabled=false;"
				+ "}"
				+ "if (document.getElementById('msgSpan').innerHTML === '') enableSubmission();"
				+ "else setTimeout(enableSubmission,5000);"
				+ "</script>");
		return buf.toString();
	}
	
	static String printExam(User user,Assignment a,HttpServletRequest request) throws IOException {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			// Check to see if a password is required to start the exam
			if (a==null || a.password == null || a.password.isEmpty() || a.password.equals(request.getParameter("ExamPassword")));  // continue
			else {
				String msg = (request.getParameter("ExamPassword")==null?"":"The password was not correct. Please wait...");
				return passwordPrompt(user,msg);
			}
			debug.append("1");
			
			if (a==null) { // anonymous user 
				a = new Assignment();
				a.id = 0L;
				a.assignmentType = "PracticeExam";
				Text text = ofy().load().type(Text.class).filter("smartText",true).first().now();
				for (int i=0;i<3;i++) {
					Chapter ch = text.chapters.get(i);
					a.conceptIds.addAll(ch.conceptIds);
					for (Long cId : ch.conceptIds) a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",cId).keys().list());
				}
			} else if (a.conceptIds.isEmpty()) { // legacy assignment uses topicIds
				List<Topic> topics = new ArrayList<Topic>(ofy().load().type(Topic.class).ids(a.topicIds).values());
				for (Topic t : topics) {
					a.conceptIds.addAll(t.conceptIds);
				}
				ofy().save().entity(a).now();
			}
			debug.append("2");
			
			// Check to see if the timeAllowed has been modified by the instructor:
			int timeAllowed = 3600;  // default value in seconds
			if (a.timeAllowed!=null) {
				timeAllowed = a.timeAllowed;  // instructor option, e.g. for student disability accommodations
				user = timeAllowed>3600?User.getUser(user.getTokenSignature(),timeAllowed/60+30):user;
				if (user==null) return Logout.message();
			}
			debug.append("3");
			
			// Check to see if this user has any pending exams:
			Date now = new Date();
			Date startTime = new Date(now.getTime()-timeAllowed*1000);  // about 1 hour ago depending on timeAllowed ago 
			List<PracticeExamTransaction> pets = ofy().load().type(PracticeExamTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",a.id).order("downloaded").list();
			if (pets == null) pets = new ArrayList<PracticeExamTransaction>();
			int nAttempts = pets.size();
			debug.append("4");
			
			//	get the most recent transaction and determine whether this is resuming a recent exam
			PracticeExamTransaction pt = nAttempts==0?null:pets.get(nAttempts-1);
			boolean resumingExam = false;
			if (pt != null && pt.graded==null && pt.downloaded.after(startTime)) resumingExam = true;
			else { // this is a new exam
				pt =  new PracticeExamTransaction(user,a);		  
				if (a.attemptsAllowed != null && nAttempts >= a.attemptsAllowed) {
					buf.append("<h1>General Chemistry Exam</h1>");
					buf.append("<h2>Sorry, you are only allowed " + a.attemptsAllowed + " attempt" + (a.attemptsAllowed==1?"":"s") + " on this assignment.</h2>");
					
					DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
					buf.append("<table><tr><th>Transaction Number</th><th>Downloaded</th><th>Placement Exam Score (percent)</th></tr>");
					for (PracticeExamTransaction pet : pets) {
						int pct = (pet.getPossibleScore()>0?pet.getScore()*100/pet.getPossibleScore():0);

						buf.append("<tr><td>" + pet.id + "</td><td>" + df.format(pet.downloaded) + "</td><td align=center>" + (pet.graded==null?"-":pct + "%") +  "</td></tr>");
					}
					buf.append("</table><br>Missing scores indicate assignments that were downloaded but not submitted for scoring.<br/><br/>");
					return buf.toString();
				}	
				ofy().save().entity(pt).now();	// need to save this to get the pt.id for setting Question parameters			
			}
			debug.append("5");
			
			// past this point we will present a practice exam to the student. 
			// first retrieve all the questions using the assignment.questionKeys List:
			Map<Key<Question>,Question> questions = new HashMap<Key<Question>,Question>();
			if (resumingExam && !pt.questionKeys.isEmpty()) questions = ofy().load().keys(pt.questionKeys);
			else questions = ofy().load().keys(a.questionKeys);  // this method tolerates keys for questions that have been deleted
			debug.append("6");
			
			// sort the question keys by point value:
			List<Key<Question>> questionKeys_02pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_10pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_15pt = new ArrayList<Key<Question>>();
			
			List<Key<Question>> remove = new ArrayList<Key<Question>>();
			for (Key<Question> k : new ArrayList<Key<Question>>(questions.keySet())) {
				Question q = questions.get(k);
				switch (q.pointValue) {
				case 2:  questionKeys_02pt.add(k); break;
				case 10: questionKeys_10pt.add(k); break;
				case 15: questionKeys_15pt.add(k); break;
				default: remove.add(k); // remove any keys having an invalid point value
				}
			}
			if (remove.size()>0) {
				a.questionKeys.removeAll(remove);
				ofy().save().entity(a);
			}
			debug.append("7");
			
			// Reduce the size of questionKeys Lists to the number of questions needed
			Random rand = new Random();  // create random number generator to select exam questions
			rand.setSeed(pt.id);  // random number generator seeded with PracticeExamTransaction id value
			while (questionKeys_02pt.size()>10) questionKeys_02pt.remove(rand.nextInt(questionKeys_02pt.size()));
			while (questionKeys_10pt.size()> 5) questionKeys_10pt.remove(rand.nextInt(questionKeys_10pt.size()));
			while (questionKeys_15pt.size()> 2) questionKeys_15pt.remove(rand.nextInt(questionKeys_15pt.size()));
			debug.append("8");
			
			buf.append("<script>function showWorkBox(qid){}</script>");  // prevents javascript error from Question.print()
			debug.append("9");
			
			buf.append("<h1>General Chemistry Exam</h1>");
			
			buf.append("This exam must be submitted for grading within " + timeAllowed/60 + " minutes of when it is first downloaded. ");
			if (resumingExam) buf.append("You are resuming an exam originally downloaded at " + pt.downloaded);
			
			buf.append("""
					
					<FORM NAME=PracticeExamForm METHOD=POST ACTION=/PracticeExam \
					onSubmit="return confirm('Submit this exam for grading now. Are you sure?')">""");

			buf.append("<div id='timer0' style='color:#EE0000'></div><div id=ctrl0 style='color:red;'><a role='button' href=javascript:toggleTimers()>hide timers</a><p></div>");
			buf.append("\n<input type=submit class='btn btn-primary' value='Grade This Practice Exam'><p>");

			buf.append("<input type=hidden name=sig value='" + user.getTokenSignature() + "'>");
			if (a!=null) buf.append("\n<input type=hidden name=AssignmentId value='" + a.id + "'>");
			
			// Randomly select the questions to be presented, eliminating each from the List of questionKeys as they are printed
			pt.putPossibleScore(0);
			
			// Two-point questions
			buf.append("<B>2 point questions:</B>");
			buf.append("<OL>\n");
			int nQuestions = 10;
			int i = 0;
			while (i<nQuestions && questionKeys_02pt.size()>0) {
				Key<Question> k = questionKeys_02pt.remove(resumingExam?0:rand.nextInt(questionKeys_02pt.size()));
				Question q = questions.get(k);
				i++;
				pt.addPossibleScore(q.pointValue);
				q.setParameters((int)(pt.id - q.id));
				buf.append("\n<li>" + q.print() + "<br></li>\n");
				if (!resumingExam) pt.questionKeys.add(k);
			}
			buf.append("</OL>");

			if (pt.questionShowWork==null) pt.questionShowWork = new HashMap<Key<Question>,String>();  // initialize this, if necessary
			
			// 10-point questions
			buf.append("<B>10 point questions:</B>");
			buf.append("<OL>\n");
			nQuestions = 5;
			i=0;
			while (i<nQuestions && questionKeys_10pt.size()>0) {
				Key<Question> k = questionKeys_10pt.remove(resumingExam?0:rand.nextInt(questionKeys_10pt.size()));
				Question q = questions.get(k);
				i++;
				pt.addPossibleScore(q.pointValue);
				q.setParameters((int)(pt.id - q.id));
				buf.append("\n<li>" + q.print() + "<br></li>\n");
				if (a.id>0) buf.append("<SCRIPT>"
						+ "document.getElementById('showWork" + q.id + "').style.display='';"
						+ "</SCRIPT>");
				if (!resumingExam) pt.questionKeys.add(k);
			}
			buf.append("</OL>");

			// 15-point questions
			buf.append("<B>15 point questions:</B>");
			buf.append("<OL>\n");
			nQuestions = 2;
			i = 0;
			while (i<nQuestions && questionKeys_15pt.size()>0) {
				Key<Question> k = questionKeys_15pt.remove(resumingExam?0:rand.nextInt(questionKeys_15pt.size()));
				Question q = questions.get(k);
				i++;
				pt.addPossibleScore(q.pointValue);
				q.setParameters((int)(pt.id - q.id));
				buf.append("\n<li>" + q.print() + "<br></li>\n");
				if (a.id>0) buf.append("<SCRIPT>"
						+ "document.getElementById('showWork" + q.id + "').style.display='';"
						+ "</SCRIPT>");
				if (!resumingExam) pt.questionKeys.add(k);
			}
			buf.append("</OL>");

			ofy().save().entity(pt).now();

			buf.append("\n<input type=hidden name='ExamId' value=" + pt.id + ">");
			buf.append("\n<input type=hidden name='UserRequest' value='GradeExam'>");
			buf.append("\n<input type=submit class='btn btn-primary' value='Grade This Practice Exam'>");
			buf.append("\n</form><br/>");
			
			buf.append("<div id='timer1' style='color:#EE0000'></div><div id=ctrl1 style='color:red;'><a role='button' href=javascript:toggleTimers()>hide timers</a><p></div>");
			long timerMillis = pt.downloaded.getTime() + timeAllowed*1000L - new Date().getTime();
			buf.append("<script>"
					+ "startTimers(" + timerMillis + ");"
					+ "function timesUp() {"
					+ "  try {"
					+ "	   document.getElementById('PracticeExamForm').submit();"
					+ "  } catch (Exception) {}"
					+ "}"
					+ "</script>");
			
		} catch (Exception e) {
			buf.append("Sorry, there was an unexpected error: " + e.getMessage()==null?e.toString():e.getMessage());
			Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Error during PracticeExam.printExam: ", e.getMessage()==null?e.toString():e.getMessage() + "<br/>" + debug.toString());
		}
		return buf.toString();
	}

	String printScore(User user,Assignment a,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			buf.append("<h1>General Chemistry Exam</h1><h2>Results</h2>");
			
			if (a==null) { // anonymous user 
				a = new Assignment();
				a.id = 0L;
				a.assignmentType = "PracticeExam";
				Text text = ofy().load().type(Text.class).filter("smartText",true).first().now();
				for (int i=0;i<3;i++) {
					Chapter ch = text.chapters.get(i);
					a.conceptIds.addAll(ch.conceptIds);
					for (Long cId : ch.conceptIds) a.questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",cId).keys().list());
				}
			}
			
			Date now = new Date();
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
			buf.append(df.format(now) + "<br>");
			
			long examId = Long.parseLong(request.getParameter("ExamId"));
			PracticeExamTransaction pt = ofy().load().type(PracticeExamTransaction.class).id(examId).safe();
			if (pt.graded != null) return "Sorry, this exam has been graded already.";
			
			int timeAllowed = 3600;
			try {
				timeAllowed = a.timeAllowed;
			} catch (Exception e) {} // catches eException if exam timeAllowed has not been customized
			
			if (now.getTime() - pt.downloaded.getTime() > 1000*(timeAllowed+300)) return "Sorry, the grading period for this exam has expired.";  // 5-minute grace period

			// create a buffer to hold the correct solutions to missed questions:
			StringBuffer missedQuestions = new StringBuffer();
			missedQuestions.append("The following questions were answered incorrectly. There may be additional questions (not shown) that were left unanswered.");			
			missedQuestions.append("<OL>");

			int wrongAnswers = 0;

			List<Key<Question>> questionKeys = pt.questionKeys;
			if (questionKeys==null || questionKeys.isEmpty()) {
				questionKeys = new ArrayList<Key<Question>>();
				for (Enumeration<?> e = request.getParameterNames();e.hasMoreElements();) {
					try {
						questionKeys.add(key(Question.class,Long.parseLong((String) e.nextElement())));
					} catch (Exception e2) {}
				}
				pt.questionKeys = questionKeys;
			}
			
			Map<Key<Question>,Question> examQuestions = ofy().load().keys(questionKeys);
			pt.putScore(0);
			
			// begin the main scoring loop:
			for (Key<Question> k : questionKeys) {
				Question q=null;
				String studentAnswer = orderResponses(request.getParameterValues(Long.toString(k.getId())));
				
				if (!studentAnswer.isEmpty()) { // an answer was submitted
					q = examQuestions.get(k);
					q.setParameters((int)(pt.id - q.id));
					int score = studentAnswer.length()==0?0:q.isCorrect(studentAnswer)?q.pointValue:0;
					if (q.getQuestionType()==5 && score==0 && q.agreesToRequiredPrecision(studentAnswer)) score = q.pointValue-1;
					
					if (score > 0) pt.addScore(score);
					
					pt.questionScores.put(k, score);
					pt.studentAnswers.put(k, studentAnswer);
					pt.correctAnswers.put(k, q.getCorrectAnswer());
					q.addAttemptsNoSave(1, score>0?1:0);
					if (score == 0) {
						// include question in list of incorrectly answered questions
						wrongAnswers++;
						missedQuestions.append("\n<LI>" + q.printAllToStudents(studentAnswer,!user.isAnonymous()) + "</LI>\n");				
					}
				}
				if (q!=null && q.pointValue > 2) pt.questionShowWork.put(k, request.getParameter("ShowWork" + k.getId()));
			}
			missedQuestions.append("</OL>\n");
			pt.graded = now;
			ofy().save().entity(pt).now();
			ofy().save().entities(examQuestions.values());
			
			if (a.id>0) try {
				Score s = Score.getInstance(user.getId(),a);
				ofy().save().entity(s).now();
				if (a.lti_ags_lineitem_url != null) LTIMessage.postUserScore(s,user.getId());
			} catch (Exception e) {}

			buf.append("<b>Your score on this exam is " + pt.getScore() + " out of a possible " + pt.getPossibleScore() + " points.</b><p>");
			if (pt.getScore() > 0 && pt.getScore() == pt.getPossibleScore()) buf.append ("<b>Congratulations on a perfect score!</b>");
			else if (wrongAnswers > 0) buf.append(missedQuestions); // list of missed questions with correct answers
			else buf.append("Some questions were left blank.");
			
			if (!user.isAnonymous()) {
				List<PracticeExamTransaction> pets = ofy().load().type(PracticeExamTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",a.id).order("downloaded").list();
				if (pets==null || pets.size()==0) {
					buf.append("Sorry, we did not find any records for you in the database for this assignment.<p>");
				} else {				
					Score s = null;
					try { // retrieve the score and ensure that it is up to date
						debug.append("1a");
						s = ofy().load().key(key(key(User.class,user.getHashedId()),Score.class,a.id)).safe();
						if (s.numberOfAttempts != pets.size()) throw new Exception();
					} catch (Exception e) { // create a fresh Score entity from scratch
						debug.append("1b");
						s = Score.getInstance(user.getId(), a);
						ofy().save().entity(s);
					}
	
					buf.append("<h3>Your Scores for This Practice Exam Assignment</h3>");
	
					buf.append("Your best score on this assignment is " + Math.round(s.getPctScore()) + "%.<br>");
	
					if (!user.isAnonymous()) {  // try to validate the score with the LMS grade book entry
						String lmsScore = null;
						try {
							double lmsPctScore = 0;
							boolean gotScoreOK = false;
	
							if (a.lti_ags_lineitem_url != null) {  // LTI version 1.3
								lmsScore = LTIMessage.readUserScore(a,user.getId());
								try {
									lmsPctScore = Double.parseDouble(lmsScore);
									gotScoreOK = true;
								} catch (Exception e) {
								}
							}
							
							if (gotScoreOK && Math.abs(lmsPctScore-s.getPctScore())<1.0) { // LMS readResult agrees to within 1%
								buf.append("This score is accurately recorded in the grade book of your class learning management system.<p>");
							} else if (gotScoreOK) { // there is a significant difference between LMS and ChemVantage scores. Please explain:
								buf.append("The score recorded in your class LMS is " + Math.round(10.*lmsPctScore)/10. + "%. The difference may be due to<br>"
										+ "enforcement of assignment deadlines, grading policies, a delay in posting the score and/or instructor discretion.<br>"
										+ "If you think this may be due to a stale score, you may submit this assignment for grading,<br>"
										+ "even for a score of zero, and ChemVantage will try to refresh your best score to the LMS.<p>");
							} else throw new Exception();
						} catch (Exception e) {
							buf.append("ChemVantage was unable to retrieve your score for this assignment from the LMS.<br>"
									+ "Sometimes it takes several seconds for the score to be posted in the LMS grade book.<br>");
							if (s.score==0 && s.numberOfAttempts<=1) buf.append("It appears that you may not have submitted a score for this assignment yet. ");
							if (user.isInstructor()) buf.append("Some LMS providers do not store scores for instructors.");
							buf.append("<br/><br/>");
						}
					}
				}
				debug.append("2");
				
				buf.append("<table><tr><th>Transaction Number</th><th>Downloaded</th><th>Practice Exam Score (percent)</th></tr>");
				if (pets != null) {
					for (PracticeExamTransaction pet : pets) {
						int pct = (pet.getPossibleScore()>0?pet.getScore()*100/pet.getPossibleScore():0);
						debug.append("a");
						buf.append("<tr><td>" + pet.id + "</td><td>" + df.format(pet.downloaded) + "</td><td align=center>" + (pet.graded==null?"-":pct + "%") +  "</td></tr>");
					}
				}
				buf.append("</table><br>Missing scores indicate assignments that were downloaded but not submitted for scoring.<p>");
			}
			debug.append("3");
			
		}
		catch (Exception e) {
			buf.append((e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
		}
		return buf.toString();
	}
	
	String submissionReview(User user,User forUser) {
		StringBuffer buf = new StringBuffer();

		if (!user.getId().equals(forUser.getId()) && !user.isInstructor()) return "Access denied.";

		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();

		List<PracticeExamTransaction> pets = ofy().load().type(PracticeExamTransaction.class).filter("userId",forUser.getHashedId()).filter("assignmentId",a.id).order("downloaded").list();
		if (pets.size()==0) {
			buf.append("Sorry, we did not find any records for " + (user.getId().equals(forUser.getId())?"you":"this user") + " in the database for this assignment.<p>");
		} else {				
			Score s = null;
			try { // retrieve the score and ensure that it is up to date
				s = ofy().load().key(key(key(User.class,user.getId()),Score.class,a.id)).safe();
				if (s.numberOfAttempts != pets.size()) throw new Exception();
			} catch (Exception e) { // create a fresh Score entity from scratch
				s = Score.getInstance(user.getId(), a);
				ofy().save().entity(s);
			}

			buf.append("<h1>General Chemistry Exam</h1><h2>Your Scores for This Assignment</h2>");

			buf.append("The best score for this user on this assignment is " + Math.round(s.getPctScore()) + "%.<br>");

			buf.append("<table><tr><th>Transaction Number</th><th>Downloaded</th><th>Practice Exam Score (percent)</th></tr>");

			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
			for (PracticeExamTransaction pet : pets) {
				int pct = (pet.getPossibleScore()>0?pet.getScore()*100/pet.getPossibleScore():0);
				buf.append("<tr><td>" + pet.id + "</td><td>" + df.format(pet.downloaded) + "</td><td align=center>" + (pet.graded==null?"-":pct + "%") +  "</td></tr>");
			}
			buf.append("</table><br>Missing scores indicate assignments that were downloaded but not submitted for scoring.<p>");
		}
		return buf.toString();
	}
	
	static String showExamScores(User user, Assignment a, String for_user_id) {
		// This method shows a table of PracticeExamTransaction entities for one user on the assignment
		// with links to review individual submissions
		StringBuffer buf = new StringBuffer();
		if (!user.isInstructor()) for_user_id = user.getId();
		try {
			buf.append("<h1>General Chemistry Exam</h1><h2>Transactions</h2>"
					+ "Exam: " + a.title + "<br/>"
					+ "Date: " + new Date() + "<br/><br/>");
			String for_user_hashed_id = Subject.hashId(for_user_id);
			List<PracticeExamTransaction> pets = ofy().load().type(PracticeExamTransaction.class).filter("userId",for_user_hashed_id).filter("assignmentId",a.id).order("downloaded").list();			
			if (pets.isEmpty()) throw new Exception("No transactions were found for this user on this assignment.");
			buf.append("<div style='display:table;text-align:center;'>");
			buf.append("<div style='display:table-row'>"  // header row
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Attempt</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Downloaded</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Elapsed Time</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Total Score</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'>Reviewed</div>"
					+ "<div style='display:table-cell;font-weight:bold;padding-right:20px;'></div>"
					+ "</div>"); // end of header row
			for (PracticeExamTransaction pet : pets) {
				String elapsedTime = pet.graded==null?" - ":String.valueOf((pet.graded.getTime()-pet.downloaded.getTime())/60000L) + " min.";
				String score = pet.graded==null?"no submission":String.valueOf(pet.getPossibleScore()>0?pet.getScore()*100/pet.getPossibleScore():0)+"%";
				buf.append("<div style='display:table-row'>"  // row for one transaction
						+ "<div style='display:table-cell;padding-right:20px;'>" + (pets.indexOf(pet)+1) + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + pet.downloaded + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + elapsedTime + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + score + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + (pet.reviewed==null?" - ":pet.reviewed) + "</div>"
						+ "<div style='display:table-cell;padding-right:20px;'>" + (user.isInstructor()?"<a href=/PracticeExam?UserRequest=ReviewExam&PracticeExamTransactionId=" + pet.id + "&sig=" + user.getTokenSignature() + "&UserId=" + for_user_id + ">Review</a>":"") + "</div>"
						+ "</div>");  // end of row
			}
			buf.append("</div><br/><br/>"); // end of table
		} catch (Exception e) {
			 buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}

	static String reviewExamScores(User user,Assignment a) {
		StringBuffer buf = new StringBuffer("<h1>General Chemistry Exam</h1>");
		try {
			if (!user.isInstructor()) return "<h2>Access Denied</h2>You must be an instructor to view this page.";

			if (a == null) return "Sorry, we did not find an assignment associated with this practice exam.";
			
			if (a.lti_nrps_context_memberships_url == null || a.lti_nrps_context_memberships_url.isEmpty()) {
				return "Sorry, your LMS does not support the Memberships service, so exams cannot be reviewed.";
			}
			buf.append("<h2>Results</h2>"
					+ "Assignment ID: " + a.id + "<br>"
					+ "Created: " + a.created + "<br>");
			
			// Get all of the PracticeExamTransactions associated with this assignment:
			List<PracticeExamTransaction> pets = ofy().load().type(PracticeExamTransaction.class).filter("assignmentId",a.id).list();			
			if (pets.size()==0) {
				buf.append("There are no transactions for this practice exam assignment yet.<p>");
				return buf.toString();
			}
			
			Map<String,String[]> membership = LTIMessage.getMembership(a);
			
			if (membership.size() == 0) {
				buf.append("The LMS returned 0 members of this group.");
				return buf.toString();
			}
			
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
			int i = 0;
			buf.append("<table><tr><th>User</th><th>Attempt</th><th>Downloaded</th><th>Elapsed Time</th>");
			buf.append("<th>Total Score</th><th>Review</th><th>Delete</th></tr>");
			
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				i++; // increment the user number
				String name = entry.getValue()[1];  // user's given and family name
				if (name==null) name = "";  // LMS does not provide names
				// make a short list of each user's PracticeExamTransactions
				List<PracticeExamTransaction> userpets = new ArrayList<PracticeExamTransaction>();
				String hashedUserId = Subject.hashId(user.platformId + "/" + entry.getKey());
				for (PracticeExamTransaction pet : pets) if (hashedUserId.equals(pet.userId)) userpets.add(pet);
				pets.removeAll(userpets);
				// put the user's transactions in order of decreasing download time:
				Collections.sort(userpets,new SortExams());
				if (userpets.isEmpty()) {  // place a blank line in the table with the user's name
					buf.append("<tr style='text-align: center;background-color: " + (i%2==0?"#EEEEEE":"#FFFDD0") + "'>"
							+ "<td>" + i + ".&nbsp;" + name + "</td>" + "<td colspan=6>(exam was not attempted)</td>");
					buf.append("</tr>");					
				} else {
					for (int k=userpets.size();k>0;k--) {  // enter the user's transactions into the table
						PracticeExamTransaction p = userpets.get(k-1);
						buf.append("<tr style='text-align: center;background-color: " + (i%2==0?"#EEEEEE":"#FFFDD0") + "'>");
						buf.append("<td>" + i + ".&nbsp;" + name + "</td><td>" + k + "</td><td>" + df.format(p.downloaded) + "&nbsp;UTC</td>");

						if (p.graded==null) buf.append("<td colspan=3>(exam was not submitted for scoring)</td>");
						else {
							buf.append("<td>" + (p.graded==null?"-":(p.graded.getTime()-p.downloaded.getTime())/60000 + " min.") + "</td>");
							buf.append("<td>" + String.valueOf(p.getPossibleScore()>0?p.getScore()*100/p.getPossibleScore():0) + "%</td>");
							buf.append("<td>" 
									+ (p.graded==null?" - ":(p.reviewed==null?"":df.format(p.reviewed)+"&nbsp;UTC<br/>"))
									+ "<a href=/PracticeExam?UserRequest=ReviewExam&PracticeExamTransactionId=" + p.id 
									+ "&sig=" + user.getTokenSignature() + "&UserId=" + user.platformId + "/" + entry.getKey() + ">Review</a></td>");
						}
						buf.append("<td><form method=post action=/PracticeExam onsubmit=\"return confirm('Permanently delete this record? This action cannot be undone.');\">"
								+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
								+ "<input type=hidden name=UserRequest value=DeleteSubmission />"
								+ "<input type=hidden name=tid value='" + p.id + "' />"
								+ "<input type=submit value=Delete />"
								+ "</form></td>");
						buf.append("</tr>");					
					}
				}
			}
			buf.append("</table><p>");
			buf.append("<p>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
	
	String reviewExam(User user, Assignment a, long practiceExamTransactionId, String studentUserId) {
		StringBuffer buf = new StringBuffer("<h1>General Chemistry Exam</h1>");
		try {
			if (!user.isInstructor()) return "<h2>Access Denied. You must be an instructor to view this page.</h2>";

			PracticeExamTransaction pet = ofy().load().type(PracticeExamTransaction.class).id(practiceExamTransactionId).safe();
			if (pet.assignmentId != user.getAssignmentId()) return "<h2>Access Denied</h2>Go back and relaunch this assignment from your LMS.";
			
			// Get the question keys from the PracticeExamTransaction and sort them into 3 lists by point value
			List<Key<Question>> questionKeys_02pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_10pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_15pt = new ArrayList<Key<Question>>();
			Map<Key<Question>,Question> examQuestions = ofy().load().keys(pet.questionKeys);
			
			for (Key<Question> k : pet.questionKeys) {
				Question q = examQuestions.get(k);
				if (q==null) continue;
				switch (q.pointValue) {
					case (2): questionKeys_02pt.add(k); break;
					case (10): questionKeys_10pt.add(k); break;
					case (15): questionKeys_15pt.add(k); break;
				}
			}
			/*
			// create a HashMap of all the questionIds and student's responses for all items submitted
			List<Response> responses = ofy().load().type(Response.class).filter("transactionId",pet.id).list();
			Map<Long,String> studentAnswers = new HashMap<Long,String>();
			for (Response r : responses) studentAnswers.put(r.questionId,r.studentResponse);
			*/
			
			buf.append("<h2>Review</h2>"
					+ "Assignment ID: " + a.id + "<br>"
					+ "Created: " + a.created + "<br>"
					+ "Key concepts covered:<ol>");	
			Map<Long,Concept> concepts = ofy().load().type(Concept.class).ids(a.conceptIds);
			for (Long cId : a.conceptIds) {
				Concept c = concepts.get(cId);
				if (c != null) buf.append("<li>" + c.title + "</li>");
			}
			buf.append("</ol>");
			
			buf.append("<form action=/PracticeExam method=post>"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=StudentUserId value=" + studentUserId + " />"
					+ "<input type=hidden name=PracticeExamTransactionId value=" + String.valueOf(practiceExamTransactionId) + " />");
			
			buf.append("Please review the student responses to the exam questions below. Use the sliders on the right to award "
					+ "partial credit or otherwise edit the scores as appropriate. When you are finished, click the button to "
					+ "<input type=submit name=UserRequest value='Submit Revised Exam Score'> to your LMS grade book.");
			
			int i=0; // counter for the question items
			
			// Two-point questions
			buf.append("<h3>2 point questions:</h3>");
			buf.append("<table>");
			for(Key<Question> k : questionKeys_02pt) {
				i++;
				Question q = examQuestions.get(k);
				q.setParameters((int)(pet.id - q.id));
				buf.append("<tr style='vertical-align:middle'><td><b>" + i + ". </b>" 
						+ q.printAllToStudents(pet.studentAnswers.get(key(q)),true) + "</td>");

				// Try to get the question score from the PracticeExamTransaction. If null, recompute it from the student's response
				int score = 0;
				if (pet.questionScores.get(k)!=null) score = pet.questionScores.get(k);
				else {  // recalculate the original score
					if (q.isCorrect(pet.studentAnswers.get(key(q)))) score = q.pointValue;
					else if (q.agreesToRequiredPrecision(pet.studentAnswers.get(key(q)))) score = q.pointValue-1;
				}
				
				buf.append("<td style='text-align:center'><label><span id='score" + q.id + "'>" + score + "</span> pts<br>"
						+ "<input type=range name=Range" + q.id + " value=" + score + " min=0 max=" + q.pointValue 
						+ " onchange=document.getElementById('score" + q.id + "').innerHTML=this.value; /></abel></td>");
				buf.append("</tr>");
			}
			buf.append("</table>");

			i=0;
			
			// Ten-point questions
			buf.append("<h3>10 point questions:</h3>");
			buf.append("<table>");
			for(Key<Question> k : questionKeys_10pt) {
				i++;
				Question q = examQuestions.get(k);
				q.setParameters((int)(pet.id - q.id));
				buf.append("<tr style='vertical-align:middle'><td><b>" + i + ". </b>" 
						+ q.printAllToStudents(pet.studentAnswers.get(key(q)),true,true,pet.questionShowWork.get(k)) + "</td>");

				// Try to get the question score from the PracticeExamTransaction. If null, recompute it from the student's response
				int score = 0;
				if (pet.questionScores.get(k)!=null) score = pet.questionScores.get(k);
				else {  // recalculate the original score
					if (q.isCorrect(pet.studentAnswers.get(key(q)))) score = q.pointValue;
					else if (q.agreesToRequiredPrecision(pet.studentAnswers.get(key(q)))) score = q.pointValue-1;
				}
				
				buf.append("<td style='text-align:center'><label><span id='score" + q.id + "'>" + score + "</span> pts<br>"
						+ "<input type=range name=Range" + q.id + " value=" + score + " min=0 max=" + q.pointValue 
						+ " onchange=document.getElementById('score" + q.id + "').innerHTML=this.value; /></label></td>");
				buf.append("</tr>");
			}
			buf.append("</table>");

			i=0;
			
			// Fifteen-point questions
			buf.append("<h3>15 point questions:</h3>");
			buf.append("<table>");
			for(Key<Question> k : questionKeys_15pt) {
				i++;
				Question q = examQuestions.get(k);
				q.setParameters((int)(pet.id - q.id));
				buf.append("<tr style='vertical-align:middle'><td><b>" + i + ". </b>" 
						+ q.printAllToStudents(pet.studentAnswers.get(key(q)),true,true,pet.questionShowWork.get(k)) + "</td>");

				// Try to get the question score from the PracticeExamTransaction. If null, recompute it from the student's response
				int score = 0;
				if (pet.questionScores.get(k)!=null) score = pet.questionScores.get(k);
				else {  // recalculate the original score
					if (q.isCorrect(pet.studentAnswers.get(key(q)))) score = q.pointValue;
					else if (q.agreesToRequiredPrecision(pet.studentAnswers.get(key(q)))) score = q.pointValue-1;
				}
				
				buf.append("<td style='text-align:center'><label><span id='score" + q.id + "'>" + score + "</span> pts<br>"
						+ "<input type=range name=Range" + q.id + " value=" + score + " min=0 max=" + q.pointValue 
						+ " onchange=document.getElementById('score" + q.id + "').innerHTML=this.value; /></label></td>");
				buf.append("</tr>");
			}
			buf.append("</table>");
			
			buf.append("<h3>When You Have Finished Reviewing This Exam</h3>"
					+ "Please click the button to <input type=submit name=UserRequest value='Submit Revised Exam Score'> to your LMS grade book.");

			buf.append("</form>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
	
	boolean submitRevisedExamScore(User instructor,Assignment a,HttpServletRequest request) throws Exception {
		try {
			// First do some validation to make sure that the user is the instructor for this assignment and the transaction is for this assignment:
			if (!instructor.isInstructor()) throw new Exception("You must be the instructor for this course.");
			long practiceExamTransactionId = Long.parseLong(request.getParameter("PracticeExamTransactionId"));
			PracticeExamTransaction pet = ofy().load().type(PracticeExamTransaction.class).id(practiceExamTransactionId).safe();
			if (!pet.assignmentId.equals(a.id)) throw new Exception("Mismatched assignment ID values");
			
			// reset the transaction scores arrays
			pet.putScore(0);
			pet.putPossibleScore(0);
			Map<Key<Question>,Question> examQuestions = ofy().load().keys(pet.questionKeys);
			
			// Iterate through all of the questions for this exam, getting scores from the range inputs on the review form and compiling the scores
			for (Key<Question> k : pet.questionKeys) {
				Question q = examQuestions.get(k);
				if (q==null) continue;
				int score = Integer.parseInt(request.getParameter("Range" + k.getId()));
				pet.questionScores.put(k, score);
				pet.addScore(score);
				pet.addPossibleScore(q.pointValue); 
			}
			
			// Record the timestamp for the exam review and save the revised transaction entity
			pet.reviewed = new Date();
			ofy().save().entity(pet).now();
			
			String studentUserId = request.getParameter("StudentUserId");
			
			// Create/store a new Score entity and submit it to the LMS grade book
			try {
				Score s = Score.getInstance(studentUserId,a);
				ofy().save().entity(s).now();
				if (a.lti_ags_lineitem_url != null) LTIMessage.postUserScore(s,studentUserId);
			} catch (Exception e) {}

		} catch (Exception e) {
			throw e;
		}
		return true;
	}

	String selectExamQuestionsForm(User user, HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<h1>General Chemistry Exam</h1><h2>Settings</h2>");
		try {
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
			
			
			if (a.timeAllowed==null) a.timeAllowed = 3600; // default time for completing the exam
			buf.append("The default time allowed to complete the exam is 60 minutes, but you may change this "
					+ "(e.g., to create a special assignment for a student requiring extended time up to 300 minutes).<br>");
			buf.append("<form action=/PracticeExam method=post><input type=hidden name=sig value=" + user.getTokenSignature() + ">" 
					+ "<label>Time allowed for this assignment: <input type=text size=5 name=TimeAllowed value=" + a.timeAllowed/60. + "> minutes.</label> "
					+ "<input type=submit name=UserRequest value='Set Allowed Time'><br>"
					+ "</form><br/>");

			buf.append("By default, students may attempt this practice exam as many times as they wish. This rewards students who persist "
					+ "to achieve a better score. However, you may limit the number of attempts here. Leave the field blank to permit unlimited attempts.<br/>"
					+ "<form action=/PracticeExam method=post><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label>Number of attempts allowed for this assignment: <input type=text size=10 name=AttemptsAllowed " 
					+ (a.attemptsAllowed==null?"placeholder=unlimited":"value=" + a.attemptsAllowed) + " /></label> "
					+ "<input type=submit name=UserRequest value='Set Allowed Attempts' /><br/>"
					+ "</form><br/>");
			
			buf.append("By default, students will view the exam immediately after clicking the assignment link in your LMS. However, "
					+ "you may (optionally) set a password required to start the exam:</br>"
					+ "<form method=post action=/PracticeExam >"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<label>Password: <input type=text name=ExamPassword value='" + (a.password==null || a.password.isEmpty()?"":a.password) + "' /></label>"
					+ "<input type=submit name=UserRequest value='Set Password' /></form><br/>");
			
			buf.append("Each practice exam consists of items selected at random from the items below:<ul>"
					+ "<li>10 quiz questions worth 2 points each</li>"
					+ "<li> 5 homework-style questions worth 10 points each</li>"
					+ "<li> 2 more challenging homework-style questions worth 15 points each</li></ul>"
					+ "for a total of 100 points. Select the items to be included in exams assigned to your class.<br/><br/>");
			
			// Show a List of concepts covered by this assignment
			Long newConceptId = null;
			try {  // add a new conceptId
				newConceptId = Long.parseLong(request.getParameter("ConceptId"));
				a.conceptIds.add(newConceptId);
			} catch (Exception e) {}

			List<Key<Concept>> conceptKeys = ofy().load().type(Concept.class).order("orderBy").keys().list();
			Map<Key<Concept>,Concept> keyConcepts = ofy().load().keys(conceptKeys);
			if (a.conceptIds.size()>0) {
				buf.append("The questions listed below cover the following key concepts:<ul>");
				for (Long cId : a.conceptIds) {
					Concept c = keyConcepts.get(key(Concept.class,cId));
					try {
						buf.append("<li>" + c.title + "</li>");
					} catch (Exception e) {
						a.conceptIds.remove(cId);  // remove id for null Concept
					}
				}
				buf.append("</ul>");
			}

			// Create a short form to select one additional key concept to include (will exclude the previous selection, if any)
			buf.append("<form method=get action=/PracticeExam>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<input type=hidden name=UserRequest value=AssignExamQuestions />"
					+ "You may include additional question items from the <label>key concept: "
					+ "<select name=ConceptId><option value='Select'>Select a key concept</option>");
			for (Key<Concept> k : conceptKeys) {
				try {
					if (a.conceptIds.contains(k.getId()) || keyConcepts.get(k).orderBy.startsWith(" 0")) continue;  // skip current and hidden conceptIds
					buf.append("<option value='" + k.getId() + "'" + (newConceptId!=null && k.getId()==newConceptId?" selected>":">") + keyConcepts.get(k).title + "</option>");
				} catch (Exception e) {}
			}
			buf.append("</select></label><input type=submit value='Include' /></form><hr>");
			
			// retrieve all of the question keys for the requested concepts
			List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
			for (long cId : a.conceptIds) {  // retrieve the keys for all Exam questions for the relevant key concepts
				questionKeys.addAll(ofy().load().type(Question.class).filter("assignmentType","Exam").filter("conceptId",cId).keys().list());
			}
			// add all existing assignment keys to the top of this List, removing any duplicates first
			questionKeys.removeAll(a.questionKeys);
			questionKeys.addAll(0,a.questionKeys);
			
			Map<Key<Question>,Question> questions = ofy().load().keys(questionKeys);
			
			// Sort the questionKeys by point Value
			List<Key<Question>> questionKeys_02pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_10pt = new ArrayList<Key<Question>>();
			List<Key<Question>> questionKeys_15pt = new ArrayList<Key<Question>>();

			List<Key<Question>> remove = new ArrayList<Key<Question>>();
			for (Key<Question> k : new ArrayList<Key<Question>>(questions.keySet())) {
				Question q = questions.get(k);
				switch (q.pointValue) {
				case 2:  questionKeys_02pt.add(k); break;
				case 10: questionKeys_10pt.add(k); break;
				case 15: questionKeys_15pt.add(k); break;
				default: remove.add(k); // remove any keys having an invalid point value
				}
			}
			if (remove.size()>0) {
				a.questionKeys.removeAll(remove);
				ofy().save().entity(a);
			}
			
			buf.append("<FORM NAME=DummyForm><label><INPUT TYPE=CHECKBOX NAME=SelectAll "
					+ "onClick=\"for (var i=0;i<document.Questions.QuestionId.length;i++)"
					+ "{document.Questions.QuestionId[i].checked=document.DummyForm.SelectAll.checked;}\""
					+ "> Select/Unselect All</label></FORM>");

			buf.append("<FORM NAME=Questions METHOD=POST ACTION=PracticeExam>"
					+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE='UpdateAssignment'>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE=" + user.getTokenSignature() + ">"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + a.id + "'>"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE=PracticeExam>"
					+ "<INPUT TYPE=SUBMIT Value='Use Selected Items'>"
					+ "<fieldset><legend><h2>Available Questions</h2></legend>");
			buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");

			Question q = null;
			int i = 0;

			// 2-point questions:
			buf.append("<TR><TD COLSPAN=2><B>2-point Questions: (select at least 10)</B></TD></TR>");
			i=0;
			for (Key<Question> k : questionKeys_02pt) {
				i++;
				q = questions.get(k);
				q.setParameters();
				buf.append("\n<TR><TD VALIGN=TOP NOWRAP>"
						+ "<label><INPUT TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'");
				buf.append(a.questionKeys.contains(key(Question.class,q.id))?" CHECKED>":">");
				buf.append("<b>&nbsp;" + i + ".</b></label></TD>");
				buf.append("\n<TD>" + q.printAll() + "</TD>");
				buf.append("</TR>");
			}
			// 10-point questions:
			buf.append("<TR><TD COLSPAN=2><B>10-point Questions: (select at least 5)</B></TD></TR>");
			i=0;
			for (Key<Question> k : questionKeys_10pt) {
				i++;
				q = questions.get(k);
				q.setParameters();
				buf.append("\n<TR><TD VALIGN=TOP NOWRAP>"
						+ "<label><INPUT TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'");
				buf.append(a.questionKeys.contains(key(Question.class,q.id))?" CHECKED>":">");
				buf.append("<b>&nbsp;" + i + ".</b></label></TD>");
				buf.append("\n<TD>" + q.printAll() + "</TD>");
				buf.append("</TR>");
			}
			// 15-point questions:
			buf.append("<TR><TD COLSPAN=2><B>15-point Questions: (select at least 2)</B></TD></TR>");
			i=0;
			for (Key<Question> k : questionKeys_15pt) {
				i++;
				q = questions.get(k);
				q.setParameters();
				buf.append("\n<TR><TD VALIGN=TOP NOWRAP>"
						+ "<label><INPUT TYPE=CHECKBOX NAME=QuestionId VALUE='" + q.id + "'");
				buf.append(a.questionKeys.contains(key(Question.class,q.id))?" CHECKED>":">");
				buf.append("<b>&nbsp;" + i + ".</b></label></TD>");
				buf.append("\n<TD>" + q.printAll() + "</TD>");
				buf.append("</TR>");
			}

			buf.append("</TABLE></fieldset><INPUT TYPE=SUBMIT Value='Use Selected Items'></FORM>");
		} catch (Exception e) {
			buf.append("Sorry, the assignment could not be found. " + e.getMessage());
		}
		return buf.toString();
	}
	
	String orderResponses(String[] answers) {
		String studentAnswer = "";
		try {
			Arrays.sort(answers);
			for (String a : answers) studentAnswer = studentAnswer + a;
		} catch (Exception e) {}
		return studentAnswer;
	}
}

class SortExams implements Comparator<PracticeExamTransaction> { 
    // Used for sorting exams in ascending order of userId, then descending order downloaded 
    public int compare(PracticeExamTransaction a, PracticeExamTransaction b) {
    	if (a.userId.equals(b.userId)) return a.downloaded.compareTo(b.downloaded);
    	else return a.userId.compareTo(b.userId); 
    } 
}
