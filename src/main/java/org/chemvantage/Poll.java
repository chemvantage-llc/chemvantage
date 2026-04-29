/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2020 ChemVantage LLC
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.googlecode.objectify.Key;

@WebServlet("/Poll")
public class Poll extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception();
			
			response.setContentType("text/html");
			response.setCharacterEncoding("UTF-8");
			PrintWriter out = response.getWriter();			
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			long conceptId = 0;
			try {
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {}
			
			
			switch (userRequest) {
			case "EditPoll":
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "NewQuestion":
				out.println(Subject.header() + newQuestionForm(user,request) + Subject.footer);
				break;
			case "Preview":
				out.println(Subject.header() + previewQuestion(user,request) + Subject.footer);
				break;
			case "PrintPoll":
				out.println(Subject.header() + showPollQuestions(user,a,request) + Subject.footer);
				break;
			case "ViewResults":
				if (user.isInstructor()) out.println(Subject.header() + resultsPage(user,a) + Subject.footer);
				break;
			case "ShowSummary":
				out.println(Subject.header("Your Class ChemVantage Scores") + showSummary(user,request) + Subject.footer);
				break;
			case "Review":
				String forUserHashedId = request.getParameter("ForUserHashedId");
				out.println(Subject.header("Poll Submission Review") + resultsPage(user,forUserHashedId,a));
				break;
			case "Edit":
				out.println(Subject.header() + editQuestion(user,request) + Subject.footer);
				break;
			default:
				if (user.isInstructor()) out.println(Subject.header() + instructorPage(user,a) + Subject.footer);
				else {
					if (a.pollIsClosed) out.println(Subject.header() + waitForPoll(user) + Subject.footer);
					else out.println(Subject.header() + showPollQuestions(user,a,request) + Subject.footer);
				}
			}
			} catch (Exception e) {
				response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
			}
		}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception();

			response.setContentType("text/html");
			response.setCharacterEncoding("UTF-8");
			PrintWriter out = response.getWriter();			
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest==null) userRequest = "";
			
			Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
			long conceptId = 0;
			try {
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {}
			
			switch (userRequest) {
			case "Close the Poll":
				if (!user.isInstructor()) break;
				a.pollIsClosed = true;
				ofy().save().entity(a).now();
				if ("InstructorPage".equals(request.getParameter("Destination"))) out.println(Subject.header() + instructorPage(user,a) + Subject.footer);
				else out.println(Subject.header() + resultsPage(user,a) + Subject.footer);
				break;
			case "Open the Poll":
				if (!user.isInstructor()) break;
				try { a.pollClosesAt = new Date(new Date().getTime() + Long.parseLong(request.getParameter("TimeLimit"))*60000L); } catch (Exception e) { a.pollClosesAt = null; }
				a.pollIsClosed = false;
				ofy().save().entity(a).now();
				out.println(Subject.header() + showPollQuestions(user,a,request) + Subject.footer);
				break;
			case "Reset":
				if (!user.isInstructor()) break;
				try { a.pollClosesAt = new Date(new Date().getTime() + Long.parseLong(request.getParameter("TimeLimit"))*60000L); } catch (Exception e) { a.pollClosesAt = null; }
				a.pollIsClosed = false;
				ofy().save().entity(a).now();
				out.println(Subject.header() + instructorPage(user,a) + Subject.footer);
				break;
			case "Save New Question":
				if (!user.isInstructor()) break;
				long qid = createQuestion(user,request);
				if (qid > 0) {
					a.questionKeys.add(key(Question.class,qid));
					ofy().save().entity(a).now();
				}
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "Update Question":
				if (!user.isInstructor()) break;
				Question q = assembleQuestion(request);
				q.id = Long.parseLong(request.getParameter("QuestionId"));
				ofy().save().entity(q).now();
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "SubmitResponses":
				PollTransaction pt = submitResponses(user,a,request);
				if (pt!=null && pt.completed!=null) {
					out.println(Subject.header() + waitForResults(user,a) + Subject.footer);
				}
				else out.println(Subject.header() + "<h1>Poll</h1>" 
						+ "<h2>Sorry, the poll closed before you submitted your responses.</h2>" 
						+ Subject.footer);
				break;
			case "AddQuestions":
				if (!user.isInstructor()) break;
				addQuestions(user,a,request);
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "Remove":
				if (!user.isInstructor()) break;
				removeQuestion(user,a,request);
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "View the Poll Results":
				out.println(Subject.header() + resultsPage(user,a) + Subject.footer);
				break;
			case "MvUp":
				moveUp(user,a,request);
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "MvDn":
				moveDn(user,a,request);
				out.println(Subject.header() + editPage(user,a,conceptId) + Subject.footer);
				break;
			case "Preview":
			case "Quit":
				doGet(request,response);
				break;
			}
		} catch (Exception e) {
			response.getWriter().println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	static String instructorPage(User user,Assignment a) {
		StringBuffer buf = new StringBuffer();
		
		if (!user.isInstructor()) return "You must be an instructor to view this page.";
		
		buf.append(Subject.privacyPolicyBanner());
		buf.append("<h1>Class Poll</h1><h2>Instructor Page</h2>");
		
		if (a.questionKeys.size()==0) return editPage(user,a,0);
		else {
			buf.append("You may review and edit the questions for this poll by <a href=/Poll?UserRequest=EditPoll&sig=" + user.getTokenSignature() + ">clicking this link</a>.<br/><br/>");
		}
		
		buf.append("This Poll assignment allows you to pose questions to your class and get real-time responses without the use of clicker devices. "
				+ "Students will need a laptop, tablet or smartphone that is logged into your course LMS. The poll is useful for<ul>"
				+ "<li>posing quiz questions to verify students' mastery of content knowledge</li>"
				+ "<li>posing open-ended questions to gauge students' opinions</li>"
				+ "<li>show students how their answers compare to their classmates</li>"
				+ "<li>take class attendance and discourage tardiness</li>"
				+ "</ul>"
				+ "When the poll is open, students can view the poll questions and submit responses.<br/>"
				+ "When the poll is closed, responses are not accepted and students are provided a link to view the poll results.<br/><br/>");
		
		buf.append("Need help? Please <a href=/Feedback?sig=" + user.getTokenSignature() + "&AssignmentId=" + a.id + ">submit a comment, question or request here</a>.<br/><br/>");			
		
		int nSubmissions = ofy().load().type(PollTransaction.class).filter("assignmentId",a.id).count();
		boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
		buf.append("There are currently " + nSubmissions + " completed submissions for this poll. ");
		if (a.pollIsClosed) {
			buf.append("<br/>You may <a href=/Poll?UserRequest=ViewResults&sig=" + user.getTokenSignature() + ">view the poll results</a> "
					+ (supportsMembership?"or <a href='/Poll?UserRequest=ShowSummary&sig=" + user.getTokenSignature() + "'>review your students' scores</a>":"") 
					+ ".<br/><br/>");
		} else {
			buf.append("<a href=/Poll?sig=" + user.getTokenSignature() + ">Refresh this page</a><br/><br/>");
		}
		
		// If the poll is open, provide a quick way to close it while staying on this page
		buf.append("<b>This class poll is currently " + (a.pollIsClosed?"closed.</b>":"open.</b> "
				+ "<form style='display:inline;' method=post action=/Poll >"
				+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
				+ "<input name=UserRequest type=submit value='Close the Poll' /></form> "
				+ "<div id='timer0' style='display:inline;color:#EE0000'>&nbsp;</div><br/>") + "<br/>");

		// Here is a simpler version of the tool below
		if (a.pollIsClosed) buf.append("<form style=display:inline method=post action=/Poll >"
				+ "<label>Set a time limit for this poll (in minutes): <input type=text size=8 name=TimeLimit placeholder=unlimited /></label>"
				+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
				+ "<input type=submit name=UserRequest value='Open the Poll' /> " 
				+ "</form><br/><br/>");

		// This is a hidden form submitted by javascript when time expires and results are shown
		buf.append("<form id=pollForm method=post action=/Poll><input type=hidden name=sig value=" + user.getTokenSignature() + " />"
				+ "<input type=hidden name=UserRequest value='Close the Poll' /></form>");
		
		// This is the big blue button to view the assignment (almost) like students see it
		buf.append("<a style='text-decoration: none' href='/Poll?UserRequest=PrintPoll&sig=" + user.getTokenSignature() + "'>"
				+ "<button class='btn btn-primary'>Show This Assignment</button></a><br/><br/>");
		
		Deployment d = ofy().load().type(Deployment.class).id(a.domain).now();
		if (d.price > 0 && d.nLicensesRemaining > 0) {		
			buf.append("Your account has " + d.nLicensesRemaining + " unclaimed student license" + (d.nLicensesRemaining>1?"s":"") + " remaining.<br/><br/>");
		}
		
		if (!a.pollIsClosed && a.pollClosesAt != null) {
			buf.append("<script>"
					+ "function timesUp() {"
					+ "  document.getElementById('pollForm').submit();"
					+ "}"
					+ "startTimers(" + (a.pollClosesAt.getTime() - new Date().getTime() + 3000L) + ");"
					+ "</script>");
		}
		return buf.toString();
	}
	
	String waitForPoll(User user) {
		StringBuffer buf = new StringBuffer();
		buf.append("<h1>Class Poll</h1><h2>The poll is closed.</h2>");
		
		if (user.isInstructor()) {
			buf.append("When ready, open the poll so you and your students can view the poll questions.<br/><br/>");
			buf.append("<form method=post action=/Poll />"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<input type=submit name=UserRequest value='Open the Poll' /> "
					+ "<a href=/Poll?sig=" + user.getTokenSignature() + ">Cancel</a>"
					+ "</form><br/><br/>");
		} else if (user.isTeachingAssistant()) {
			buf.append("<a href=/Poll?UserRequest=ViewResults&sig=" + user.getTokenSignature() + ">View the Results</a>");
		} else {
			buf.append("Please wait. Your instructor should inform you when the poll is open.<br/>"
					+ "At that time you can click the button below to view the poll questions.<br/><br/>");
			buf.append("<form method=get action=/Poll />"
					+ "<input type=hidden name=sig class='btn btn-primary' value='" + user.getTokenSignature() + "' />"
					+ "<input type=submit value='View the Poll' /> "
					+ "</form><br/><br/>");
		}
		return buf.toString();
	}
	
	String showPollQuestions(User user, Assignment a,HttpServletRequest request) {
		/*
		 * This method is reached by Learners or Instructors who launch the correct LTI Resource Link in the LMS, thereby binding
		 * the assignmentId to their User entity.
		 */
		
		if (a.pollIsClosed) return waitForPoll(user);  // nobody gets in without the instructor opening the poll first
		
		StringBuffer buf = new StringBuffer();
		
		buf.append("<h1>Class Poll</h1>");
		buf.append("<div id='timer0' style='color: #EE0000'></div><br/>");
		
		if (user.isInstructor()) {
			buf.append("<b>Please tell your students that the poll is now open so they can view the poll questions.</b><br/>");
			
			if (a.pollClosesAt!=null) buf.append("The poll will close automatically when the timer reaches zero.<br/>");
			
			buf.append("<form method=post action='/Poll' >"
					+ "<a href=/Poll?sig=" + user.getTokenSignature() + ">Return to the instructor page</a>, or "
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<input type=hidden name=UserRequest value='Close the Poll' />"
					+ "<input type=submit class='btn btn-primary' value='Close the Poll' />"
					+ "</form>");
		}
		buf.append("<div id=questions>");
		buf.append("<OL>");
		int possibleScore = 0;
		buf.append("<form id=pollForm method=post action='/Poll' onSubmit='return confirmSubmission(" + a.questionKeys.size() + ")'>"
				+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
		
		// see if this user already has a submission for this assignment; else get a new PollTransaction
		PollTransaction pt = getPollTransaction(user,a);
		
		Map<Key<Question>,Question> questions = ofy().load().keys(a.questionKeys);		
		for (Key<Question> k : a.questionKeys) {  // main loop to present questions
			Question q = questions.get(k); // this should nearly always work
			if (q==null) continue;		 // but skip the question if it has been deleted
			q.setParameters(a.id % Integer.MAX_VALUE);
			String studentResponse = pt.studentAnswers.get(k);
			if (studentResponse==null) studentResponse = "";
			buf.append("<li>" + q.print(null,studentResponse) + "<br/></li>");
			possibleScore += q.correctAnswer==null || q.correctAnswer.isEmpty()?0:q.pointValue;
			pt.questionKeys.add(k);
		}
		buf.append("</OL>");
		
		buf.append("<div id='timer1' style='color: #EE0000'></div><br/>");
		buf.append(confirmSubmission(user)); 

		buf.append("<input type=hidden name=PossibleScore value='" + possibleScore + "' />");
		buf.append("<input type=hidden name=UserRequest value='SubmitResponses' />");
		buf.append("<input type=submit id=pollSubmit class='btn btn-primary' value='Submit My Responses Now' />");
		buf.append("</form>");
		buf.append("</div>");
		if (a.pollClosesAt != null) 
			buf.append("<script>"
					+ "function timesUp() {"
					+ "  document.getElementById('pollForm').submit();"
					+ "  document.getElementById('questions').innerHTML='<h2>Time Expired</h2>Your responses were autosubmitted.';"
					+ "}"
					+ "startTimers(" + (a.pollClosesAt.getTime() - new Date().getTime() + (user.isInstructor()?3000L:0L)) + ");"
					+ "</script>");
		
		return buf.toString();
	}
/*	
	static String confirmSubmission(User u) {
		return "<SCRIPT>"
				+ "function confirmSubmission(nQuestions) {"
				+ "  var elements = document.getElementById('pollForm').elements;"
				+ "  var nAnswers;"
				+ "  var i;"
				+ "  var checkboxes;"
				+ "  var lastCheckboxIndex;"
				+ "  nAnswers = 0;"
				+ "  for (i=0;i<elements.length;i++) {"
				+ "    if (isNaN(elements[i].name)) continue;"
				+ "    if (elements[i].type=='text' && elements[i].value.length>0) nAnswers++;"
				+ "    else if (elements[i].type=='radio' && elements[i].checked) nAnswers++;"
				+ "    else if (elements[i].type=='checkbox') {"
				+ "      checkboxes = document.getElementsByName(elements[i].name);"
				+ "      lastCheckboxIndex = i + checkboxes.length - 1;"
				+ "      for (j=0;j<checkboxes.length;j++) if (checkboxes[j].checked==true) {"
				+ "        nAnswers++;"
				+ "        i = lastCheckboxIndex;"
				+ "        break;"
				+ "      }"
				+ "    }"
				+ "  }"
				+ "  if (nAnswers<nQuestions) return confirm('Submit your responses now? ' + (nQuestions-nAnswers) + ' answers may have been left blank.');"
				+ "  else return true;"
				+ "}"
				+ "function showWorkBox(qid) {}" 
				+ "</SCRIPT>"; 
	}
*/
	static String confirmSubmission(User u) {
		return "<SCRIPT>"
				+ "function confirmSubmission(nQuestions) {"
				+ "  var elements = document.getElementById('pollForm').elements;"
				+ "  var nAnswers;"
				+ "  var i;"
				+ "  var checkboxes;"
				+ "  var lastCheckboxIndex;"
				+ "  nAnswers = 0;"
				+ "  for (i=0;i<elements.length;i++) {"
				+ "    if (isNaN(elements[i].name)) continue;"
				+ "    switch (elements[i].type) {"
				+ "    case 'hidden':"
				+ "    case 'textarea':"
				+ "    case 'text':"
				+ "      if (elements[i].value.length>0) nAnswers++;"
				+ "      break;"
				+ "    case 'radio':"
				+ "      if (elements[i].checked) nAnswers++;"
				+ "      break;"
				+ "    case 'checkbox':"
				+ "      checkboxes = document.getElementsByName(elements[i].name);"
				+ "      lastCheckboxIndex = i + checkboxes.length - 1;"
				+ "      for (j=0;j<checkboxes.length;j++) if (checkboxes[j].checked==true) {"
				+ "        nAnswers++;"
				+ "        i = lastCheckboxIndex;"
				+ "        break;"
				+ "      }"
				+ "      break;"
				+ "    }"
				+ "  }"
				+ "  if (nAnswers<nQuestions) return confirm('Submit your responses now? ' + (nQuestions-nAnswers) + ' answers may have been left blank.');"
				+ "  else return true;"
				+ "}"
				+ "function showWorkBox(qid) {}" 
				+ "</SCRIPT>"; 
	}

	
	PollTransaction submitResponses(User user,Assignment a,HttpServletRequest request) {
		if (a.pollIsClosed) return null;
		
		PollTransaction pt = getPollTransaction(user,a);
		pt.completed = new Date();
		pt.nSubmissions++;
		
		int score = 0;
		int possibleScore = 0;
		pt.questionKeys = a.questionKeys;
		
		Map<Key<Question>,Question> questions = ofy().load().keys(a.questionKeys);		
		for (Key<Question> k : pt.questionKeys) {
			try {
				Question q = questions.get(k);
				if (q==null) continue;
				possibleScore += q.pointValue;
				String studentAnswer = orderResponses(request.getParameterValues(Long.toString(k.getId())));
				if (!studentAnswer.isEmpty()) {
					pt.studentAnswers.put(k, studentAnswer);
					q.setParameters(a.id % Integer.MAX_VALUE);
					score += q.isCorrect(studentAnswer) || !q.hasACorrectAnswer()?q.pointValue:0;
				}
				if (q.hasACorrectAnswer()) pt.correctAnswers.put(k, q.getCorrectAnswer());
			} catch (Exception e) {}
		}
		if (possibleScore != pt.possibleScore) pt.possibleScore = possibleScore;
		if (score > pt.score) pt.score = score; 
		ofy().save().entity(pt).now();
		try {
			if (user.isAnonymous()) throw new Exception();  // don't save Scores for anonymous users
			if (a.lti_ags_lineitem_url != null) {
				Utilities.createTask("/ReportScore","AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8"));
				//QueueFactory.getDefaultQueue().add(withUrl("/ReportScore").param("AssignmentId",String.valueOf(a.id)).param("UserId",URLEncoder.encode(user.getId(),"UTF-8")));  // put report into the Task Queue
			}
		} catch (Exception e) {}
		return pt;
	}
	
	static PollTransaction getPollTransaction(User user,Assignment a) {
		PollTransaction pt = ofy().load().type(PollTransaction.class).filter("assignmentId",a.id).filter("userId",user.getHashedId()).first().now();
		if (pt == null) pt = new PollTransaction(user.getId(),new Date(),user.getAssignmentId());
		return pt;
	}
/*	
	Question getQuestion(Key<Question> k) {
		Question q = pollQuestions.get(k);
		if (q == null) {
			q = ofy().load().key(k).now();
			if (q != null) pollQuestions.put(k,q);
		}
		return q;  // returns null only if the question has been deleted
	}
	
	void cacheQuestions(Assignment a) {
		List<Key<Question>> newKeys = new ArrayList<Key<Question>>();
		for (Key<Question> k : a.questionKeys) {
			if (!this.pollQuestions.containsKey(k)) newKeys.add(k);
		}
		if (newKeys.size()>0) pollQuestions.putAll(ofy().load().keys(newKeys));
		return;
	}
*/	
	static String waitForResults(User user, Assignment a) {
		
		if (a.pollIsClosed) return resultsPage(user,a);
		if (user.isInstructor() && a.pollClosesAt!=null && a.pollClosesAt.before(new Date())) {
			a.pollIsClosed = true;
			ofy().save().entity(a).now();
			return resultsPage(user,a);
		}
		
		StringBuffer buf = new StringBuffer("<h1>Poll</h1>");
		
		buf.append("<h2>Please wait for the poll to close.</h2>"
				+ "<div id='timer0' style='color: #EE0000'></div><br/>");
		
		buf.append("<form id=pollForm method=post action='/Poll' >"
				+ (user.isInstructor()?"Whenever submissions are complete, you can ":"Your instructor will tell you when can ")
				+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
				+ "<input type=hidden name=UserRequest value='" + (user.isInstructor()?"Close the Poll":"View the Poll Results") + "' />"
				+ "<input type=submit value='" + (user.isInstructor()?"Close the Poll":"View the Poll Results") + "' /> ");
		if (user.isInstructor()) buf.append("and view the results,<br/>or you can <a href=/Poll?sig=" + user.getTokenSignature() + ">return to the instructor page</a>.<br/><br/>");
		buf.append("</form>");
		
		if (!a.pollIsClosed && a.pollClosesAt != null) {
			buf.append("<script>"
					+ "function timesUp() {"
					+ "  document.getElementById('pollForm').submit();"
					+ "}"
					+ "startTimers(" + (a.pollClosesAt.getTime() - new Date().getTime() + 3000L) + ");"
					+ "</script>");
		}		
		return buf.toString();	
	}
/*	
	static String timer(User u) {
		return "\n<SCRIPT>"
				+ "var seconds;"
				+ "var minutes;"
				+ "var oddSeconds;"
				+ "var endMillis;"
				+ "var clock;"
				+ "var timer0 = document.getElementById('timer0');"
				+ "var timer1 = document.getElementById('timer1');"
				+ "var form = document.getElementById('pollForm');"
				+ "function countdown() {"
				+ "	var seconds=Math.round((endMillis-Date.now())/1000);"
				+ "	var minutes = seconds<0?Math.ceil(seconds/60.):Math.floor(seconds/60.);"
				+ "	var oddSeconds = seconds%60;"
				+ " if (oddSeconds<10) oddSeconds = '0'+ oddSeconds;"
				+ " clock = seconds<=0?'0:00':minutes + ':' + oddSeconds;"
				+ " if (timer0!=null) timer0.innerHTML = 'Time remaining: ' + clock;"
				+ " if (timer1!=null) timer1.innerHTML = 'Time remaining: ' + clock;"
				+ "	if (seconds <= 0) form.submit();"
				+ " else setTimeout(() => countdown(), 1000);"
				+ "}\n"
				+ "function synchTimer() {"
				+ "  var xmlhttp=new XMLHttpRequest();"
				+ "  if (xmlhttp==null) {"
				+ "    alert ('Sorry, your browser does not support AJAX!');"
				+ "    return false;"
				+ "  }"
				+ "  xmlhttp.onreadystatechange=function() {"
				+ "    if (xmlhttp.readyState==4) {"
				+ "     const serverNowMillis = xmlhttp.responseText.trim();"  // server returned new Date().getTime()
				+ "     endMillis += Date.now() - serverNowMillis;"          // corrects for fast or slow browser clock
				+ "    }"
				+ "  }\n"
				+ "  var url = 'Poll?UserRequest=Synch&sig=" +u.getTokenSignature() + "';"
				+ "  timer0.innerHTML = 'synchronizing clocks...';"
				+ "  xmlhttp.open('GET',url,true);"
				+ "  xmlhttp.send(null);"
				+ "  return false;"
				+ "}\n"
				+ "</SCRIPT>";
	}
*/	
	static String resultsPage(User user,Assignment a) {
		return resultsPage(user,null,a);
	}
	
	static String resultsPage(User user,String forUserHashedId,Assignment a) {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug:");

		try {
			if (!user.isInstructor() && !a.pollIsClosed) return waitForResults(user,a);
			//if (!user.isInstructor() && forUserHashedId==null && !a.pollIsClosed) return waitForResults(user,a);
			
			buf.append("<h1>Class Poll</h1><h2>Results</h2>");
			if (user.isInstructor() && forUserHashedId==null) {
				if (a.pollIsClosed) buf.append("<b>Be sure to tell your students that the poll is now closed</b> and to click the button to view the poll results.<br/>");
				else buf.append("The poll is still open. ");
				buf.append("You can <a href=/Poll?sig=" + user.getTokenSignature() + ">return to the instructor page</a> at any time.<br/><br/> ");
			}
			debug.append("b.");

			PollTransaction pt = null;
			if (!user.isInstructor() || forUserHashedId==null) pt = getPollTransaction(user,a);
			else if (user.isInstructor()) {
				pt = ofy().load().type(PollTransaction.class).filter("assignmentId",a.id).filter("userId",forUserHashedId).first().now();
				if (pt==null) {
					buf.append("<br/>There was no poll submission for this user.");
					return buf.toString();
				} else {
					buf.append("Assignment ID: " + a.id + "<br/>"
							+ "Transaction ID: " + pt.id + "<br/>"
							+ "Submissions: " + pt.nSubmissions + "<br/>"
							+ (pt.nSubmissions>1?"First Submitted: " + pt.downloaded + "<br/>Last ":"")
							+ "Submitted: " + pt.completed + "<br/><br/>");	
				}
			} else return "Unauthorized.";
			
			int pctScore = pt.possibleScore==0?0:pt.score*100/pt.possibleScore;
			buf.append((user.isInstructor()?"The ":"Your ") + "score for this assignment is: " + pt.score + " out of " + pt.possibleScore + " points ("+ pctScore + "%).<br/><br/>");
		
			List<PollTransaction> pts = ofy().load().type(PollTransaction.class).filter("assignmentId",a.id).list();
			buf.append("\n");
			buf.append("<script>"
					+ "function ajaxSubmit(url,id,params,studentAnswer,note,email) {\n"
					+ "  var xmlhttp;\n"
					+ "  if (url.length==0) return false;\n"
					+ "  xmlhttp=GetXmlHttpObject();\n"
					+ "  if (xmlhttp==null) {\n"
					+ "    alert ('Sorry, your browser does not support AJAX!');\n"
					+ "    return false;\n"
					+ "  }\n"
					+ "  xmlhttp.onreadystatechange=function() {\n"
					+ "    if (xmlhttp.readyState==4) {\n"
					+ "      document.getElementById('feedback' + id).innerHTML="
					+ "      '<FONT COLOR=RED><b>Thank you. An editor will review your comment.</b></FONT><p>';\n"
					+ "    }\n"
					+ "  }\n"
					+ "  url += '&QuestionId=' + id + '&Params=' + params + '&sig=" + user.getTokenSignature() + "&Notes=' + note + '&Email=' + email + '&StudentAnswer=' + studentAnswer;\n"
					+ "  xmlhttp.open('GET',url,true);\n"
					+ "  xmlhttp.send(null);\n"
					+ "  return false;\n"
					+ "}\n"
					+ "function GetXmlHttpObject() {\n"
					+ "  if (window.XMLHttpRequest) { // code for IE7+, Firefox, Chrome, Opera, Safari\n"
					+ "    return new XMLHttpRequest();\n"
					+ "  }\n"
					+ "  if (window.ActiveXObject) { // code for IE6, IE5\n"
					+ "    return new ActiveXObject('Microsoft.XMLHTTP');\n"
					+ "  }\n"
					+ "  return null;\n"
					+ "}\n"
					+ "</script>");	
			buf.append("\n");

			int i=0;
			buf.append("<div style='display: table'>"); // big-table
			buf.append("<div style='display: table-row;'>"
					+ "<div style='display: table-cell'></div>"  // column for question number
					+ "<div style='display: table-cell'><h3>Questions</h3></div>"
					+ "<div style='display: table-cell'>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</div>"  // horizontal buffer
					+ "<div style='display: table-cell'><h3>Responses</h3></div>"
					+ "</div>");  // end of header row
			debug.append("c.");
			Map<Key<Question>,Question> questions = ofy().load().keys(a.questionKeys);		
			for (Key<Question> k : a.questionKeys) {
				buf.append("\n");
				try {
					Question q = questions.get(k);
					if (q==null) continue;
					q.setParameters(a.id % Integer.MAX_VALUE);
					if (q.correctAnswer==null) q.correctAnswer = "";
					i++;
					buf.append("<div style='display: table-row;vertical-align: top;'>");
					buf.append("<div style='display: table-cell;vertical-align: top;'><br/>" + i + ".&nbsp;</div>"); // number cell

					buf.append("<div style='display: table-cell;vertical-align: top;width: 400px;'><br/>"); // question cell

					String userResponse = "";
					try {
						userResponse = pt.studentAnswers.get(k);
					} catch (Exception e) {}

					buf.append(q.hasNoCorrectAnswer()?q.print():q.printAllToStudents(userResponse));
					//buf.append(q.printAll());

					buf.append("</div>"   // end of question cell
							+ "<div style='display: table-cell;vertical-align: top;'></div>");  // horizontal buffer

					// This is where we will construct a histogram showing the distribution of responses
					debug.append("start.");

					Map<String,Integer> histogram = new HashMap<String,Integer>();
					//String correctResponse = q.getCorrectAnswer();
					String otherResponses = null;
					char choice = 'a';
					//int chart_height = 150;
					debug.append("1.");

					switch (q.getQuestionType()) {
					case Question.MULTIPLE_CHOICE:
						for (int j = 0; j < q.nChoices; j++) {
							histogram.put(String.valueOf(choice),0);
							choice++;
						}
						debug.append("2a.");
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							histogram.put(t.studentAnswers.get(k),histogram.get(t.studentAnswers.get(k))+1);
						}
						break;
					case Question.TRUE_FALSE:
						histogram.put("true", 0);
						histogram.put("false", 0);
						//chart_height = 100;
						debug.append("2b.");
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							histogram.put(t.studentAnswers.get(k),histogram.get(t.studentAnswers.get(k))+1);
						}
						break;
					case Question.SELECT_MULTIPLE:
						for (int j = 0; j < q.nChoices; j++) {
							histogram.put(String.valueOf(choice),0);
							choice++;
						}
						debug.append("2c.");
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							String response = t.studentAnswers.get(k);
							debug.append(response + ".");
							for (int m=0; m<response.length();m++) {
								debug.append("4.");
								histogram.put(String.valueOf(response.charAt(m)),histogram.get(String.valueOf(response.charAt(m)))+1);
							}
						}
						break;
					case Question.FILL_IN_WORD:
						histogram.put("correct", 0);
						histogram.put("incorrect", 0);
						//chart_height = 100;
						debug.append("2d.");
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							if (q.isCorrect(t.studentAnswers.get(k))) histogram.put("correct",histogram.get("correct")+1);
							else {
								histogram.put("incorrect", histogram.get("incorrect") + 1);
								if (otherResponses==null) otherResponses = t.studentAnswers.get(k);
								else if (otherResponses.length()<500 && t.studentAnswers.get(k) != null && !otherResponses.toLowerCase().contains(t.studentAnswers.get(k).toLowerCase())) otherResponses += "; " + t.studentAnswers.get(k);
							}
						}
						break;
					case Question.NUMERIC:
						histogram.put("correct", 0);
						histogram.put("incorrect", 0);
						//chart_height = 100;
						debug.append("2e.");
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							if (q.isCorrect(t.studentAnswers.get(k))) histogram.put("correct",histogram.get("correct")+1);
							else {
								histogram.put("incorrect", histogram.get("incorrect") + 1);
								if (otherResponses==null) otherResponses = t.studentAnswers.get(k);
								else if (otherResponses.length()<500 && t.studentAnswers.get(k) != null && !otherResponses.toLowerCase().contains(t.studentAnswers.get(k).toLowerCase())) otherResponses += "; " + t.studentAnswers.get(k);
							}
						}
						break;
					case Question.FIVE_STAR:
						for (char nStars='1'; nStars<'6'; nStars++) histogram.put(String.valueOf(nStars),0);
						for (PollTransaction t : pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							histogram.put(t.studentAnswers.get(k), histogram.get(t.studentAnswers.get(k))+1);
						}
						break;
					case Question.ESSAY:
						int nEssays = 0;
						for (PollTransaction t :pts) {
							if (t.completed==null || t.studentAnswers==null || t.studentAnswers.get(k)==null) continue;
							nEssays++;
						}
						histogram.put("N", nEssays);
						break;
					default:
					}
					debug.append("histogram initialized.");

					// Calculate a scale factor for the maximum width of the graph bars based on the max % response

					int maxValue = 0;
					int totalValues = 0;
					for (Entry<String,Integer> e : histogram.entrySet()) {
						totalValues += e.getValue();
						if (e.getValue() > maxValue) maxValue = e.getValue();
					}
					debug.append("maxValue="+maxValue+".totalValues="+totalValues+".");
					buf.append("\n");

					buf.append("<div id=chart_div" + i + " style='display: table-cell;vertical-align: top;'><br/>");  // histogram cell
					if (totalValues>0) {
						// Print a histogram as a table containing a horizontal bar graph:
						switch (q.getQuestionType()) {
						case Question.MULTIPLE_CHOICE:
						case Question.TRUE_FALSE:
						case Question.SELECT_MULTIPLE:
							buf.append("Summary&nbsp;of&nbsp;responses&nbsp;received&nbsp;for&nbsp;this&nbsp;question:<p></p>");
							buf.append("<table>");
							for (Entry<String,Integer> e : histogram.entrySet()) {
								buf.append("<tr><td>");
								buf.append(e.getKey() + "&nbsp;");
								buf.append("</td><td>");
								buf.append("<div style='background-color: blue;display: inline-block; width: " + 150*e.getValue()/(totalValues+1) + "px;'>&nbsp;</div>");
								buf.append("&nbsp;" + e.getValue() + "</td></tr>");
							}
							buf.append("</table>");
							break;
						case Question.FILL_IN_WORD:
						case Question.NUMERIC:
							buf.append("Summary&nbsp;of&nbsp;responses&nbsp;received&nbsp;for&nbsp;this&nbsp;question:<p></p>");
							if (q.hasACorrectAnswer()) {
								buf.append("<table>");
								buf.append("<tr><td>");
								buf.append("correct" + "&nbsp;");
								buf.append("</td><td>");
								buf.append("<div style='background-color: blue;display: inline-block; width: " + 150*histogram.get("correct")/(totalValues+1) + "px;'>&nbsp;</div>");
								buf.append("&nbsp;" + histogram.get("correct") + "</td></tr>");
								buf.append("<tr><td>");
								buf.append("incorrect" + "&nbsp;");
								buf.append("</td><td>");
								buf.append("<div style='background-color: blue;display: inline-block; width: " + 150*histogram.get("incorrect")/(totalValues+1) + "px;'>&nbsp;</div>");
								buf.append("&nbsp;" + histogram.get("incorrect") + "</td></tr>");
								if (otherResponses != null) buf.append("<tr><td colspan=2><br />Incorrect Responses: " + otherResponses + "</td></tr>");							
								buf.append("</table>");
							} else buf.append(otherResponses);
							break;	
						case Question.FIVE_STAR:
							buf.append("Summary&nbsp;of&nbsp;responses&nbsp;received&nbsp;for&nbsp;this&nbsp;question:<p></p>");
							buf.append("<table>");
							for (char nStars='5'; nStars>='1'; nStars--) {
								buf.append("<tr><td>");
								buf.append(String.valueOf(nStars) + (nStars=='1'?"&nbsp;star":"&nbsp;stars") + "&nbsp;");
								buf.append("</td><td>");
								buf.append("<div style='background-color: blue;display: inline-block; width: " + 150*histogram.get(String.valueOf(nStars))/(totalValues+1) + "px;'>&nbsp;</div>");
								buf.append("&nbsp;" + histogram.get(String.valueOf(nStars)) + "</td></tr>");
							}
							buf.append("</table>");
							break;
						case Question.ESSAY:
							buf.append(histogram.get("N") + (histogram.get("N")==1?" response was ":" responses were ") + "submitted for this question.");
							break;
						}
					} else buf.append("No responses were submitted for this question.");

					buf.append("</div></div>"); // end of table cell and row
					debug.append("endOfHistogram.");

				} catch (Exception e) {
					buf.append(e.toString() + " " + e.getMessage() + "" + debug.toString() + "</div>");
				}
			}
			buf.append("</div>");  // end of table
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage() + "<br/>" + debug.toString());
		}
		return buf.toString();
	}
	
	static String editPage(User user, Assignment a) {
		return editPage(user,a,0);
	}
	
	static String editPage(User user,Assignment a,long conceptId) {
		StringBuffer buf = new StringBuffer();
		if (!user.isInstructor()) return "Not Authorized";
		buf.append("<h1>Class Poll</h1>");
		
		buf.append("<h2>Add Question Items</h2>");
		
		List<Question> addQuestions = new ArrayList<Question>();
		if (conceptId>0) addQuestions = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",conceptId).list();
		
		// Display a selector to display candidate questions by key concept:
		List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
		buf.append("<form method=get action=/Poll>"
				+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
				+ "<input type=hidden name=UserRequest value=EditPoll>"
				+ "<label>Add questions related to:"
				+ "<select name=ConceptId><option value=0>Select a key concept</option>");
		for (Concept c : concepts) {
			if (c.orderBy.startsWith(" 0")) continue; // skip reserved concepts
			buf.append("<option value=" + c.id + (c.id.equals(conceptId)?" selected>":">") + c.title + "</option>");
		}
		buf.append("</select></label><input type=submit value='Add Questions' /></form>");

		int i=0;
		if (addQuestions.isEmpty()) {  // give option to create a new question and show current questions
			buf.append("<form method=get action='/Poll'>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE='NewQuestion' />"
					+ "or <input type=submit value='Create a custom question of your own' /> "
					+ "</form>or <a href=/Poll?sig=" + user.getTokenSignature() + ">Done Editing</a>");
			
			if (conceptId!=0) buf.append("<br/><br/><span style=background-color:yellow;font-weight:bold;>Sorry, there are no avalable questions for the selected key concept.</span>");
			
			buf.append("<h2>Current Questions For This Poll</h2>");
			int possibleScore = 0;
			Map<Key<Question>,Question> currentQuestions = ofy().load().keys(a.questionKeys);
			for (Key<Question> k : a.questionKeys) {  // main loop to present questions
				Question q = currentQuestions.get(k);
				if (q==null) { // somehow the question has been deleted from the database
					a.questionKeys.remove(k);
					ofy().save().entity(a);
					continue;
				}
				q.setParameters();
				i++;
				buf.append("<div style='display: table-row'>");
				buf.append("<div style='display: table-cell;width: 75px;padding-right:20px;align: center'>" 
						+ "<div style='text-align: right';>" + i + ".</div>"
						+ "<form action=/Poll method=post>"
						+ "<input type=hidden name=QuestionId value='" + q.id + "' />"
						+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
						+ (a.questionKeys.indexOf(k)>0?"<input type=submit name=UserRequest value='MvUp' />":"")
						+ (a.questionKeys.indexOf(k)<a.questionKeys.size()-1?"<input type=submit name=UserRequest value='MvDn' />":"")
						+ "<br/><input type=submit name=UserRequest value='Remove' />"
						+ "</form>");
				if (user.getId().equals(q.authorId)) {  // give a chance to edit the question
					buf.append("<a href=/Poll?sig=" + user.getTokenSignature() + "&UserRequest=Edit&QuestionId=" + q.id + ">Edit</a>");
				}
				buf.append("</div>");
				buf.append("<div style='display: table-cell'>" + q.printAll() + "</div>");
				buf.append("</div><br/>"); // end of row
				possibleScore += q.pointValue;
			}
			
			if (a.questionKeys.size()>0) buf.append("<hr/>This poll is worth a possible " + possibleScore + " points. "
					+ "<a href=/Poll?sig=" + user.getTokenSignature() + ">Done Editing</a><br/><br/>");		
		} else {  // present candidate questions for including in the Poll
			buf.append("<form method=post action='/Poll'><input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
			buf.append("<input type=hidden name=UserRequest value='AddQuestions' />");
			buf.append("<input type=submit value='Include the selected items below in the poll' /> "
					+ "or <a href=/Poll?UserRequest=EditPoll&sig=" + user.getTokenSignature() + ">Cancel</a><br/><br/>");
			for (Question q : addQuestions) {
				i++;
				q.setParameters(a.id % Integer.MAX_VALUE);
				buf.append("<div style='display: table-row'>");
				buf.append("<div style='display: table-cell;width: 55px;'><input type=checkbox name=QuestionId value='" + q.id + "' />&nbsp;" + i + ".</div>");
				buf.append("<div style='display: table-cell'>" + q.printAll() + "</div>");
				buf.append("</div>");  // end of row
			}
			buf.append("<input type=submit value='Include the selected items above in the poll' /> "
					+ "or <a href=/Poll?UserRequest=EditPoll&sig=" + user.getTokenSignature() + ">Cancel</a><br/><br/>");
			buf.append("</form>");
		} 
		return buf.toString();
	}
	void addQuestions(User user,Assignment a,HttpServletRequest request) {
		String[] qids = request.getParameterValues("QuestionId");
		List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
		for (String qid : qids) {
			try {
				questionKeys.add(key(Question.class,Long.parseLong(qid)));
			} catch (Exception e) {}
		}
		if (questionKeys.size()>0) {
			if (a.questionKeys == null) a.questionKeys = questionKeys;
			else a.questionKeys.addAll(questionKeys);
			ofy().save().entity(a).now();
		}
	}
	
	void removeQuestion(User user,Assignment a,HttpServletRequest request) {
		try {
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			Key<Question> k = key(Question.class,questionId);
			if (a.questionKeys.remove(k)) ofy().save().entity(a).now();
		} catch (Exception e) {}
	}
	
	String newQuestionForm(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		String assignmentType = "Poll";
		int questionType = 0;
		try {
			questionType = Integer.parseInt(request.getParameter("QuestionType"));
			switch (questionType) {
			case (1): buf.append("<h3>New Multiple-Choice " + assignmentType + " Question</h3>");
			buf.append("Fill in the question text and the possible answers "
					+ "(up to a maximum of 5). Be sure to select the single best "
					+ "answer to the question."); break;
			case (2): buf.append("<h3>New True-False " + assignmentType + " Question</h3>");
			buf.append("Write the question as an affirmative statement. Then "
					+ "indicate below whether the statement is true or false."); break;
			case (3): buf.append("<h3>New Select-Multiple " + assignmentType + " Question</h3>");
			buf.append("Fill in the question text and the possible answers "
					+ "(up to a maximum of 5). Be sure to "
					+ "select all of the correct answers to the question."); break;
			case (4): buf.append("<h3>New Fill-in-Word " + assignmentType + " Question</h3>");
			buf.append("Start the question text in the upper textarea box. Indicate "
					+ "the correct answer (and optionally, an alternative correct answer) in "
					+ "the middle boxes, and the end of the question text below that.  The answers "
					+ "are not case-sensitive or punctuation-sensitive, but spelling must "
					+ "be exact."); break;
			case (5): buf.append("<h3>New Numeric " + assignmentType + " Question</h3>");
			buf.append("Fill in the question text in the upper textarea box and "
					+ "the correct numeric answer below. Also indicate the required precision "
					+ "of the student's response in percent (default = 2%). Use the bottom "
					+ "textarea box to finish the question text and/or to indicate the "
					+ "expected dimensions or units of the student's answer."); break;
			case (6): buf.append("<h3>New 5-Star Rating " + assignmentType + " Question</h3>");
			buf.append("Fill in the question text with a request to rate something."); break;
			case (7): buf.append("<h3>New Essay " + assignmentType + " Question</h3");
			buf.append("Fill in the question text with instructions to write a short essay."); break;
			
			default: buf.append("An unexpected error occurred. Please try again.");
			}
			Question question = new Question(questionType);
			buf.append("<p><FORM METHOD=GET ACTION='/Poll'>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + user.getId() + "' />");
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE=" + questionType + " />");
			
			buf.append("Assignment Type: Poll<br />");
			buf.append("Point Value: <input type=text size=2 name=PointValue value=1 /><br />");
			buf.append(question.edit());
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview' />"
					+ "</FORM>");
		} catch (Exception e) {
			buf.append("<h2>Create a Custom Question</h2>");
			buf.append("<FORM NAME=NewQuestion METHOD=GET ACTION='/Poll'>");
			buf.append("Select one of the following question types:<br />"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=NewQuestion />"
					+ "<INPUT TYPE=HIDDEN NAME=QuestionType />"
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=1;submit()\" VALUE='Multiple Choice' /> "
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=2;submit()\" VALUE='True/False' /> "
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=3;submit()\" VALUE='Select Multiple' /> "
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=4;submit()\" VALUE='Fill in Word' /> "
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=5;submit()\" VALUE='Numeric' />"
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=6;submit()\" VALUE='Five Star Rating' />"
					+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=7;submit()\" VALUE='Essay' />"
					+ "</FORM>");
		}
		return buf.toString();
	}

	String editQuestion (User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		try {
			long questionId = Long.parseLong(request.getParameter("QuestionId"));
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			
			if (!user.isInstructor() || !user.getId().equals(q.authorId)) throw new Exception("Access denied.");
			
			if (q.requiresParser()) q.setParameters();
			buf.append("<h3>Current Question</h3>");
			buf.append("Assignment Type: Poll<br>");
			buf.append("Author: " + q.authorId + "<br>");
			buf.append("Editor: " + q.editorId + "<br>");
			
			buf.append("<FORM Action=/Poll METHOD=POST>"
					+ "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
			
			buf.append(q.printAll());
			
			if (q.authorId==null) q.authorId="";
			if (q.editorId==null) q.editorId="";
			buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "' />");
			buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + q.editorId + "' />");
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + questionId + "' />");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Question' />");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit' />");
			
			buf.append("<hr><h3>Edit This Question</h3>");
			
			buf.append("Assignment Type: Poll<br>");
			
			buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
			buf.append(" Point Value: 1<br>");
			
			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview />");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}
	
	String previewQuestion(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug:");
		try {
			long questionId = 0;
			boolean current = false;
			boolean proposed = false;
			try {
				questionId = Long.parseLong(request.getParameter("QuestionId"));
				current = true;
			} catch (Exception e2) {
				debug.append("a");
			}
			long proposedQuestionId = 0;
			try {
				proposedQuestionId = Long.parseLong(request.getParameter("ProposedQuestionId"));
				proposed = true;
				current = false;
			} catch (Exception e2) {
				debug.append("b");
			}
			
			Question q = assembleQuestion(request);
			debug.append("c");
			if (q.requiresParser()) q.setParameters();
			debug.append("d");
			
			buf.append("<h3>Preview Poll Question</h3>");
			
			q.assignmentType = "Poll";
				
			buf.append("Author: " + q.authorId + "<br />");
			buf.append("Editor: " + user.getId() + "<p>");
			
			buf.append("<FORM Action='/Poll' METHOD=POST>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "' />");
			
			buf.append(q.printAll());
			
			if (q.authorId==null) q.authorId="";
			buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "' />");
			buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + user.getId() + "' />");
			
			
			if (current) {
				buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE=" + questionId + " />");
				buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Update Question' />");
			}
			if (proposed) {
				buf.append("<INPUT TYPE=HIDDEN NAME=ProposedQuestionId VALUE=" + proposedQuestionId + " />");
				buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Activate This Question' />");
			} else buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Save New Question' />");
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit' />");
			
			buf.append("<hr><h3>Continue Editing</h3>");
			buf.append("Assignment Type: Poll<br />");
			
			buf.append("<br />");
			buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
			
			buf.append(" Point Value: <input type=text size=2 name=PointValue value='" + q.pointValue + "' /><br />");
			
			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview />");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append("<br/>Error: " + e.getMessage()==null?e.toString():e.getMessage() + "<br/>" + debug.toString());
		}
		return buf.toString();
	}

	private Question assembleQuestion(HttpServletRequest request) {
		try {
			int questionType = Integer.parseInt(request.getParameter("QuestionType"));
			return assembleQuestion(request,new Question(questionType)); 
		} catch (Exception e) {
			return null;
		}
	}
	
	private Question assembleQuestion(HttpServletRequest request,Question q) {
		String assignmentType = "Poll";
		int type = q.getQuestionType();
		try {
			type = Integer.parseInt(request.getParameter("QuestionType"));
		}catch (Exception e) {}
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
		boolean scrambleChoices = false;
		int pointValue = 1;
		try {
			pointValue = Integer.parseInt(request.getParameter("PointValue"));
		} catch (Exception e) {
		}
		try {
			requiredPrecision = Double.parseDouble(request.getParameter("RequiredPrecision"));
		} catch (Exception e) {
		}
		try {
			significantFigures = Integer.parseInt(request.getParameter("SignificantFigures"));
		} catch (Exception e) {
		}
		try {
			scrambleChoices = Boolean.parseBoolean(request.getParameter("ScrambleChoices"));
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
		
		q.assignmentType = assignmentType;
		q.setQuestionType(type);
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
		q.scrambleChoices = scrambleChoices;
		q.authorId = request.getParameter("AuthorId");
		q.editorId = request.getParameter("EditorId");
		q.validateFields();
		return q;
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
	
	long createQuestion(User user,HttpServletRequest request) { //previously type long
		try {
			Question q = assembleQuestion(request);
			q.isActive = true;
			ofy().save().entity(q).now();
			return q.id;
		} catch (Exception e) {
			return 0;
		}
	}
	
	String orderResponses(String[] answers) {
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String showSummary(User user, HttpServletRequest request) {
		StringBuffer buf = new StringBuffer();
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
		if (a==null) return "No assignment was specified for this request.";
		if (!user.isInstructor()) return "You must be logged in as the instructor to view this page.";
		if (a.lti_nrps_context_memberships_url==null) return "Sorry, your LMS does not support the Names and Roles Provisioning Service.";

		try {
			buf.append("<h3>" + a.assignmentType + "</h3>");
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
			buf.append("<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th><th>Scores Detail</th></tr>");
			int i=0;
			boolean synched = true;
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				String s = scores.get(entry.getKey());
				Score cvScore = cvScores.get(keys.get(entry.getKey()));
				String forUserHashedId = Subject.hashId(platform_id + entry.getKey());  // only send hashed values through links
				i++;
				buf.append("<tr><td>" + i + ".&nbsp;</td>"
						+ "<td>" + entry.getValue()[1] + "</td>"
						+ "<td>" + entry.getValue()[2] + "</td>"
						+ "<td>" + entry.getValue()[0] + "</td>"
						+ "<td align=center>" + (s == null?" - ":s + "%") + "</td>"
						+ "<td align=center>" + (cvScore == null?" - ":String.valueOf(cvScore.getPctScore()) + "%") + "</td>"
						+ "<td align=center>" + (cvScore == null?" - ":"<a href=/Poll?UserRequest=Review&sig=" + user.getTokenSignature() + "&ForUserHashedId=" + forUserHashedId + "&ForUserName=" + entry.getValue()[1].replaceAll(" ","+") + ">show</a>") + "</td>"
						+ "</tr>");
				// Flag this score set as unsynchronized only if there is one or more non-null ChemVantage Learner score that is not equal to the LMS score
				// Ignore Instructor scores because the LMS often does not report them, and ignore null cvScore entities because they cannot be reported.
				synched = synched && (!"Learner".equals(entry.getValue()[0]) || (cvScore!=null?String.valueOf(cvScore.getPctScore()).equals(s):true));
			}
			buf.append("</table><br/>");
			if (!synched) {
				buf.append("If any of the Learner scores above are not synchronized, you may use the button below to launch a background task " 
						+ "where ChemVantage will resubmit them to your LMS. This can take several seconds to minutes depending on the "
						+ "number of scores to process. Please note that you may have to adjust the settings in your LMS to accept the "
						+ "revised scores. For example, in Canvas you may need to change the assignment settings to Unlimited Submissions. "
						+ "This may also cause the submission to be counted as late if the LMS assignment deadline has passed.<br/>"
						+ "<form method=post action=/Poll >"
						+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
						+ "<input type=hidden name=UserRequest value='Synchronize Scores' />"
						+ "<input type=submit value='Synchronize Scores' />"
						+ "</form>");
			}
			return buf.toString();
		} catch (Exception e) {
			buf.append(e.toString());
		}

		return buf.toString();
	}
	
	void moveUp(User user,Assignment a,HttpServletRequest request) {
		// this method moves a Poll question earlier in the Poll
		if (!user.isInstructor()) return;
		try {
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			Key<Question> k = key(Question.class,questionId);
			int i = a.questionKeys.indexOf(k);
			if(i>0) {
				Collections.swap(a.questionKeys, i, i-1);
				ofy().save().entity(a).now();
			}
		} catch (Exception e) {			
		}
	}
	
	void moveDn(User user,Assignment a,HttpServletRequest request) {
		// this method moves a Poll question later in the Poll
		if (!user.isInstructor()) return;
		try {
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			Key<Question> k = key(Question.class,questionId);
			int i = a.questionKeys.indexOf(k);
			if(i<a.questionKeys.size()-1) {
				Collections.swap(a.questionKeys, i, i+1);
				ofy().save().entity(a).now();
			}
		} catch (Exception e) {			
		}
	}
	
}
