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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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

@WebServlet("/VideoQuiz")
public class VideoQuiz extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	int nQuestions = 2;  // this is the default number of questions per quizlet at the video breaks
	
	public String getServletInfo() {
		return "This servlet presents a video with embedded quizzes for the user.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		User user = null;
		
		try {
			user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception();
			
			long videoId = 0L;
			Assignment a = null;
			long assignmentId = user.getAssignmentId();
			if (assignmentId==0L) {  // anonymous user; select a random video only when none was provided
				try {
					videoId = Long.parseLong(request.getParameter("VideoId"));
				} catch (Exception e) {
					List<Video> videos = ofy().load().type(Video.class).filter("breaks >",0).list();
					Random rand = new Random();
					videoId = videos.get(rand.nextInt(videos.size())).id;
				}
			} else {
				a = ofy().load().type(Assignment.class).id(assignmentId).now();
				videoId = a.videoId;
			}
			
			int segment = 0;
			try {
				segment = Integer.parseInt(request.getParameter("Segment"));
			} catch (Exception e) {
			}
			
			String userRequest = request.getParameter("UserRequest");		
			if (userRequest==null) userRequest = "";
			
			switch (userRequest) {
			case "ShowVideo":
				out.println(Subject.header("Video") + showVideo(user,a,videoId,segment) + Subject.footer);
				break;
			case "ShowQuizlet":
				out.println(showQuizlet(user,videoId,segment,response));
				break;
			case "ShowScores":
				out.println(Subject.header("Video assignment scores") + showScores(user,request) + Subject.footer);
				break;
			case "ShowSummary":
				out.println(Subject.header("Class video scores") + showSummary(user,a) + Subject.footer);
				break;
			case "SynchronizeScore":
				out.println(synchronizeScore(user,a,request.getParameter("ForUserId")));
				break;
			case "FinishQuizlet":
				VideoTransaction vt = ofy().load().type(VideoTransaction.class).filter("userId",user.getHashedId()).filter("videoId",videoId).order("-downloaded").first().now();
				out.println(finishQuizlet(user,vt,response));
				break;
			default:
				if (user.isInstructor()) out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				else out.println(Subject.header("Video") + showVideo(user,a,videoId,segment) + Subject.footer);
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
			if (user==null) throw new Exception();

			String userRequest = request.getParameter("UserRequest");		
			if (userRequest==null) userRequest = "";

			switch (userRequest) {

			case "Synchronize Scores":
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
				if (synchronizeScores(user,a,request)) out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
				else out.println("Synchronization request failed.");
				break;
			default:
				out.println(scoreQuizlet(user,request,response));
			}
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        	out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	static String instructorPage(User user, Assignment a) {
		if (!user.isInstructor()) return "<h1>You must be logged in as an instructor to view this page</h1>";
		
		StringBuffer buf = new StringBuffer();		
		try {
			buf.append(Subject.privacyPolicyBanner());
			
			Video v = ofy().load().type(Video.class).id(a.videoId).safe();
			
			boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
			
			buf.append("<h1>General Chemistry Video</h1><h2>" + v.title + "</h2><h3>Instructor Page</h3>");
			
			if (supportsMembership) buf.append("From here, you may<UL>"
					+ "<LI><a href='/VideoQuiz?UserRequest=ShowSummary&sig=" + user.getTokenSignature() + "'>Review your students' video scores</a></LI>"
					+ "</UL>");
			buf.append("<a style='text-decoration: none' href='/VideoQuiz?UserRequest=ShowVideo&sig=" + user.getTokenSignature() + "'>"
					+ "<button class='btn btn-primary'>Show This Assignment</button></a><br/><br/>");
			
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
	
	public String showVideo(User user, Assignment a, long videoId, int segment) throws Exception {
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer();
		
		try {
			int start = 0;
			int end = -1;
			
			Video v = ofy().load().type(Video.class).id(videoId).now();
			String videoSerialNumber = v.serialNumber;

			if (a != null && a.title == null) {
				a.title = v.title;
				ofy().save().entity(a);
			}

			if (v.breaks == null) v.breaks = new int[0];
			String breaks = Arrays.toString(v.breaks);

			if (segment > 0)
				start = v.breaks[segment - 1]; // start at the end of the last segment
			if (v.breaks.length > segment)
				end = v.breaks[segment]; // play to this value and stop

			debug.append("start:" + start + " end:" + end + " breaks:" + breaks + " ");

			buf.append("""
					<h1>Video</h1>
					<div id=video_div style='width:560px;height:315px'></div>
					<br>
					<div id=quiz_div style='width:560px;background-color:white;min-height:315;display:none'></div><br/>\
					<div style='font-size:small'>If the YouTube screen is black, try using the player controls to show full screen.</div>
					<p>""");
			buf.append("<script src='/js/five_star_radios.js?v=3'></script>\n");
			buf.append("<script type=text/javascript>\n"
					+ "var tag = document.createElement('script'); tag.src='https://www.youtube.com/iframe_api';\n"
					+ "var firstScriptTag = document.getElementsByTagName('script')[0]; firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);\n"
					+ "var player;\n"
					+ "var quiz_div = document.getElementById('quiz_div');\n"
					+ "var serverUrl = '" + Subject.getServerUrl() + "';\n"
					+ "var pageOrigin = window.location.origin;\n"
					+ "var playerOrigin = pageOrigin.includes('localhost') ? pageOrigin : serverUrl;\n"
					+ "var sig = '" + user.getTokenSignature() + "';\n"
					+ "var segment = " + segment + ";\n"
					+ "var breaks = " + breaks + ";\n"
					+ "var videoSerialNumber = '" + videoSerialNumber + "';\n"
					+ "var start = " + start + ";\n"
					+ "var end = " + end + ";\n"
					+ "\n"
					+ "function onYouTubeIframeAPIReady() {\n"
					+ "  player = new YT.Player('video_div', {\n"
					+ "	height: '315',\n"
					+ "	width: '560',\n"
					+ "	videoId: '" + videoSerialNumber + "',\n"
					+ "	playerVars: {\n"
					+ "	  'enablejsapi': 1,\n"
					+ "	  'autoplay': 0,\n"
					+ "	  'start': " + start + ",\n"
					+ "	  'end': " + end + ",\n"
					+ "	  'modestbranding': 1,\n"
				+ "	  'origin': playerOrigin\n"
					+ "	},\n"
					+ "	events: {\n"
					+ "		'onReady': onPlayerReady,\n"
					+ "        'onStateChange': onPlayerStateChange\n"
					+ "    }\n"
					+ "  });\n"
					+ "}\n"
					+ "\n"
					+ "function onPlayerReady(event) {\n"
					+ "	start = segment == 0?0:breaks[segment-1];\n"
					+ "	end = breaks.length <= segment?-1:breaks[segment];\n"
					+ "	player.loadVideoById({'videoId':videoSerialNumber,'startSeconds':start,'endSeconds':end});\n"
					+ "	ajaxLoadQuiz();\n"
					+ "}\n"
					+ "\n"
					+ "function onPlayerStateChange(event) {\n"
					+ "	switch (event.data) {\n"
					+ "	  case YT.PlayerState.ENDED:\n"
					+ "    var fsElement = document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement;\n"
					+ "    try {\n"
					+ "      if (fsElement) {\n"
					+ "        if (document.exitFullscreen) document.exitFullscreen().catch(function(){ });\n"
					+ "        else if (document.webkitExitFullscreen) document.webkitExitFullscreen();\n"
					+ "        else if (document.mozCancelFullScreen) document.mozCancelFullScreen();\n"
					+ "        else if (document.msExitFullscreen) document.msExitFullscreen();\n"
					+ "      }\n"
					+ "    } catch (e) {}\n"
					+ "		video_div.style.display = 'none';\n"
					+ "		quiz_div.style.display = '';\n"
					+ "		break;\n"
					+ "	  case YT.PlayerState.PLAYING:\n"
					+ "		video_div.style.display = '';\n"
					+ "		quiz_div.style.display = 'none';\n"
					+ "		break;\n"
					+ "	  default:\n"
					+ "    }\n"
					+ "}\n"
					+ "\n"
					+ "function ajaxLoadQuiz() {\n"
					+ "  var xmlhttp=GetXmlHttpObject();\n"
					+ "  quiz_div.innerHTML = \"Loading questions...\";\n"
					+ "  if (xmlhttp==null) {\n"
					+ "	    alert ('Sorry, your browser does not support AJAX. To access the video quiz, switch to a supported browser like Chrome or Safari.');\n"
					+ "	    return false;\n"
					+ "  }\n"
					+ "  xmlhttp.onreadystatechange=function() {\n"
					+ "	if (xmlhttp.readyState==4) {\n"
					+ "	  quiz_div.innerHTML=xmlhttp.responseText;\n"
					+ "	}\n"
					+ "  }\n"
					+ "  xmlhttp.open('GET','/VideoQuiz?VideoId='+'" + videoId + "'+'&UserRequest=ShowQuizlet&Segment='+segment+'&sig='+sig,true);\n"
					+ "  xmlhttp.send(null);\n"
					+ "  return true;\n"
					+ "}\n"
					+ "\n"
					+ "function ajaxSubmitQuiz() {\n"
					+ "  try {\n"
					+ "	  var xmlhttp=GetXmlHttpObject();\n"
					+ "	  xmlhttp.onreadystatechange=function() {\n"
					+ "	   if (xmlhttp.readyState==4) {\n"
					+ "		quiz_div.style.display = start>=0?'none':'';\n"
					+ "		quiz_div.innerHTML = xmlhttp.responseText;\n"
					+ "	   }\n"
					+ "	  }\n"
					+ "	  xmlhttp.open('POST','/VideoQuiz',true);\n"
					+ "	  xmlhttp.setRequestHeader('Content-Type','application/x-www-form-urlencoded');\n"
					+ "	  var formData = new FormData(document.getElementById('quizlet'));\n"
					+ "	  xmlhttp.send(urlencodeFormData(formData));\n"
					+ "  } catch (e) {\n"
					+ "	  quiz_div.innerHTML = e.message;  \n"
					+ "  }\n"
					+ "  segment++;\n"
					+ "  start = breaks[segment-1];\n"
					+ "  end = (breaks.length > segment?breaks[segment]:-1);  // play to this value or stop at end\n"
					+ "  try {\n"
					+ "	  if (start>=0) player.loadVideoById({'videoId':videoSerialNumber,'startSeconds':start,'endSeconds':end});\n"
					+ "  } catch (e) {}\n"
					+ "  \n"
					+ "  return false;\n"
					+ "}\n"
					+ "\n"
					+ "function urlencodeFormData(fd){\n"
					+ "    var params = new URLSearchParams();\n"
					+ "    var pair;\n"
					+ "    for(pair of fd.entries()){\n"
					+ "        typeof pair[1]=='string' && params.append(pair[0], pair[1]);\n"
					+ "    }\n"
					+ "    return params.toString();\n"
					+ "}\n"
					+ "function showWorkBox(qid) {}\n"
					+ "</script>");
		} catch (Exception e) {
			return (e.getMessage()==null?e.toString():e.getMessage()) + debug.toString();
		}
		return buf.toString();
	}

	public String showQuizlet(User user,long videoId,int segment,HttpServletResponse response) {
		StringBuffer buf = new StringBuffer();
		try {
			Assignment a = null;
			long assignmentId = user.getAssignmentId();  // should be non-zero for LTI user
			if (assignmentId>0) {
				a = ofy().load().type(Assignment.class).id(assignmentId).now();
				videoId = a.videoId;
			}
		
			Video v = ofy().load().type(Video.class).id(videoId).now();
			if (v.breaks==null) v.breaks = new int[0];
			if (v.nQuestions==null) v.nQuestions = new int[0];
			if (v.questionKeys==null) v.questionKeys = new ArrayList<Key<Question>>();

			buf.append("<h2>Please answer these questions before resuming the video:</h2>\n");

			// Check to see if this user has any pending videos on this topic in the last 90 minutes.
			// Normally, a new VideoTransaction is created when the LTILaunchRequest is made, but sometimes things happen...
			Date now = new Date();
			Date then = new Date(now.getTime()-90*60000);  // 90 minutes ago
			VideoTransaction vt = ofy().load().type(VideoTransaction.class).filter("userId",user.getHashedId()).filter("videoId",v.id).order("-downloaded").first().now();

			if (vt == null || vt.graded != null || vt.downloaded.before(then)) {  // create a new VideoTransaction
				int possibleScore = 0;
				for (int i=0;i<v.breaks.length;i++) possibleScore += (v.nQuestions[i]<this.nQuestions?v.nQuestions[i]:this.nQuestions);
				vt = new VideoTransaction(v.id,v.title,v.breaks.length,user.getId(),assignmentId,possibleScore);
				ofy().save().entity(vt).now();
			}

			if (segment == v.breaks.length) {
				return finishQuizlet(user, vt, response);
			}

			// get a List of available Question keys for this segment of this video
			int counter = 0; // skip over this many questions to the ones in the current quizlet
			for (int i=0;i<segment;i++) counter += v.nQuestions[i];
			List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
			for (int j=counter;j<counter+v.nQuestions[segment];j++) questionKeys.add(v.questionKeys.get(j));

			if (questionKeys.size()==0) return "Sorry, there are no questions at this point. <a href='/VideoQuiz?VideoId=" + videoId + "&Segment=" + segment+1 + "&sig=" + user.getTokenSignature() + "'>Continue the video</a><p>";

			// Randomly select the questions to be presented, eliminating each from questionKeys as they are printed
			Random rand = new Random(vt.id % Integer.MAX_VALUE);  // create random number generator to select quiz questions
			nQuestions = (nQuestions < questionKeys.size()?nQuestions:questionKeys.size());
		
		//buf.append("Questions to be presented="+nQuestions+".<hr>");
		
		buf.append("<form id=quizlet method=post action='/VideoQuiz' onSubmit=\"document.getElementById('submitButton').disabled=true;return ajaxSubmitQuiz();\" >");
				
		int i = 0;
		buf.append("<style>"
				+ ".video-quizlet-list{list-style:none;margin:0;padding-left:0;counter-reset:videoQuizletItem;}"
				+ ".video-quizlet-list li{counter-increment:videoQuizletItem;display:flex;align-items:flex-start;gap:0.5em;margin-bottom:0.75em;}"
				+ ".video-quizlet-list li .video-quizlet-number{font-weight:600;line-height:1.2;padding-top:0.15em;min-width:1.75em;text-align:right;flex:0 0 auto;}"
				+ ".video-quizlet-list li .video-quizlet-number::before{content:counter(videoQuizletItem) '.';}"
				+ ".video-quizlet-list li .video-quizlet-question{flex:1 1 auto;}"
				+ "</style>");
		buf.append("<OL class='video-quizlet-list'>\n");
		while (i<nQuestions && questionKeys.size()>0) {
			Key<Question> k = questionKeys.remove(rand.nextInt(questionKeys.size()));
			Question q = ofy().load().key(k).safe();
			// by this point we should have a valid question
			i++;  // this counter keeps track of the number of questions presented so far
			// the parameterized questions are seeded with a value based on the ids for the quizTransaction and the question
			// in order to make the value reproducible for grading but variable for each quiz and from one question to the next
			long seed = Math.abs(vt.id - q.id);
			if (seed==-1) seed--;  // -1 is a special value for randomly seeded Random generator; avoid this (unlikely) situation
			q.setParameters(seed); // the values are subtracted to prevent (unlikely) overflow

			buf.append("\n<li><span class='video-quizlet-number'></span><div class='video-quizlet-question'>" + q.print() + "<br></div></li>\n");
		}
		buf.append("</OL>");

		if (a!=null) buf.append("<input type=hidden name=AssignmentId value='" + a.id + "'>");
		
		buf.append("<input type=hidden name=sig value=" + user.getTokenSignature() + ">");
		buf.append("<input type=hidden name=VideoId value=" + v.id + ">");
		buf.append("<input type=hidden name=VideoTransactionId value=" + vt.id + ">");
		buf.append("<input type=hidden name=Segment value=" + segment + ">");
		buf.append("<input type=submit id=submitButton class='btn btn-primary' value='Submit and Resume the Video'> or "
				+ "<a href=/VideoQuiz?Segment=" + segment + "&VideoId=" + v.id + "&sig=" + user.getTokenSignature() + ">Replay This Segment</a>");
		buf.append("</form>");
		} catch (Exception e) {
			buf.append(e.getMessage());
		}
		return buf.toString();
	}

	public String scoreQuizlet(User user, HttpServletRequest request, HttpServletResponse response) throws Exception {
		StringBuffer debug = new StringBuffer("starting...");
		Date now = new Date();
		VideoTransaction vt = null;
		int segment = 0;
		try {
		// Retrieve the videoTransaction and segment
		try {
			segment = Integer.parseInt(request.getParameter("Segment"));
			debug.append("segment.");
			vt = ofy().load().type(VideoTransaction.class).id(Long.parseLong(request.getParameter("VideoTransactionId"))).safe();
			debug.append("vt.");
		} catch (Exception e) {  // this section is reached if a quizlet is not included at the end (no questions)
			debug.append("caught.");
			Date then = new Date(now.getTime()-90*60000);  // 90 minutes ago
			long videoId = Long.parseLong(request.getParameter("VideoId"));
			debug.append("vId.");
			vt = ofy().load().type(VideoTransaction.class).filter("userId",user.getHashedId()).filter("videoId",videoId).filter("graded",null).filter("downloaded >",then).first().now();
			debug.append("vt.");
		}
		
		// Make a list of the question keys. Non-numeric inputs are ignored (catch and continue).
		List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
		for (Enumeration<?> e = request.getParameterNames();e.hasMoreElements();) {
			try {
				questionKeys.add(key(Question.class,Long.parseLong((String) e.nextElement())));
			} catch (Exception e2) {}
		}
		debug.append("qkeys.");
		
		StringBuffer missedQuestions = new StringBuffer();	// contains solutions to questions answered incorrectly		
		
		if (questionKeys.size()>0) {  // This is the main scoring loop:			
			int quizletScore = 0;
			Map<Key<Question>,Question> quizQuestions = ofy().load().keys(questionKeys);
			for (Key<Question> k : questionKeys) {
				try {
					String studentAnswer = orderResponses(request.getParameterValues(Long.toString(k.getId())));
					if (!studentAnswer.isEmpty()) {
						Question q = quizQuestions.get(k);
						long seed = Math.abs(vt.id - q.id);
						if (seed==-1) seed--;  // -1 is a special value for randomly seeded Random generator; avoid this (unlikely) situation
						q.setParameters(seed);
						int score = q.isCorrect(studentAnswer)?q.pointValue:0;
						vt.questionKeys.add(k);
						vt.questionScores.put(k, score);
						vt.studentAnswers.put(k, studentAnswer);
						vt.correctAnswers.put(k, q.getCorrectAnswer());
						q.addAttemptsNoSave(1,score>0?1:0);
						quizletScore += score;
						if (score == 0) {  
							// include question in list of incorrectly answered questions
							missedQuestions.append("\n<LI>" + q.printAllToStudents(studentAnswer) + "</LI>\n");
						}
					}
				} catch (Exception e2) {
					continue;  // this parameter does not correspond to a questionId
				}
			}
			debug.append("scoring done.");
			vt.quizletScores.set(segment,quizletScore);
			vt.missedQuestions.set(segment,missedQuestions.toString());
		}
		ofy().save().entity(vt).now();
		debug.append("done.");
		} catch (Exception e) {
			debug.append(e.getMessage());
			return debug.toString();
		}
		return showQuizlet(user,vt.videoId,segment+1,response);  // return the next quizlet
	}
	
	String finishQuizlet(User user, VideoTransaction vt, HttpServletResponse response) {
		StringBuffer buf = new StringBuffer();  // prepare a summary of the quizlet scores and missed questions

		boolean noQuizlets = vt.possibleScore==0;

		if (noQuizlets && vt.assignmentId>0) {  // give 1 pt credit for watching the assignment
			vt.score = 1;
			vt.possibleScore = 1;
		} else {
			vt.score = 0;
			for (int s : vt.quizletScores) vt.score += s;
		}				
		vt.graded = new Date();  
		
		ofy().save().entity(vt).now();  // save vt now to calculate Score and in case noQuizlets
		// Try to post the score to the student's LMS:
		// Retrieve the assignment, if it exists (may be null for anonymous users)
		boolean reportScoreToLms = false;
		Assignment a = null;
		long assignmentId = user.getAssignmentId();

		if (assignmentId>0L) {
			a = ofy().load().type(Assignment.class).id(assignmentId).now();
			Score s = Score.getInstance(user.getId(),a);
			ofy().save().entity(s).now();
			reportScoreToLms = a.lti_ags_lineitem_url != null;
		}
		if (noQuizlets) {
			buf.append("<h4>Thanks for watching</h4>");
			if (vt.score == 1) buf.append("You have received full credit for watching the video.<br/><br/>");

			buf.append("Please take a moment to <a href=/Feedback?sig=" + user.getTokenSignature() + ">tell us about your ChemVantage experience</a>.<p>");
		} else {

			buf.append("<h3>Video Quiz Results - " + vt.videoTitle + "</h3>\n");

			if (user.isAnonymous()) buf.append("<font color=red>Anonymous User</font><br>");
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
			buf.append(df.format(new Date()));

			// Seek some feedback for ChemVantage:
			if (vt.possibleScore > 0 && vt.score == vt.possibleScore) {
				buf.append("<H3>Congratulations on a perfect score! Good job.</H3>\n");
				
				buf.append(Feedback.fiveStars(user.getTokenSignature()));
			} else {
				buf.append("<h4>Your score on this quiz is " + vt.score + " point" + (vt.score==1?"":"s") + " out of a possible " + vt.possibleScore + " points.</h4>\n");

				buf.append("Please take a moment to <a href=/Feedback?sig=" + user.getTokenSignature() + ">tell us about your ChemVantage experience</a>.<p>");

				String missedQuestions = "";
				for (String mq : vt.missedQuestions) missedQuestions += mq;

				if (!missedQuestions.isEmpty()) { // Give the correct answers to missed questions:
					buf.append("The following questions were answered incorrectly:<ol>" + missedQuestions + "</ol>");
				}
			}
			vt.missedQuestions.clear();
			ofy().save().entity(vt).now();
		}
		if (reportScoreToLms && a != null) {
			try {
				Utilities.createTask("/ReportScore","AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8"));
			} catch (Exception e) {}
		} else if (!user.isAnonymous()) {
			buf.append("<b>Please note:</b> Your score was not reported back to the grade book of your class "
					+ "LMS because the LTI launch request did not contain enough information to do this. "
					+ (user.isInstructor()?"For instructors this is common.":"") + "<p>");				
		}

		// Give a chance to review previous scores on this assignment
		if (a != null) buf.append("You may <a href=/VideoQuiz?UserRequest=ShowScores&sig=" + user.getTokenSignature() + ">review all your scores on this assignment</a>.<p>") ;


		// If a==null this is an anonymous user, otherwise is an LTI user:
		buf.append((a==null?"<a href=/VideoQuiz?VideoId=" + vt.videoId + "&Segment=0&sig=" + user.getTokenSignature() + ">Watch this video again</a> or go back to the <a href=/>ChemVantage home page</a> " :
				"You may view this video again by clicking the assignment link in your learning management system ")			
				+ "or <a href=/?sig=" + user.getTokenSignature() + ">logout of ChemVantage</a>.");

		return buf.toString();	

	}
	
	String showScores (User user, HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<h2>Your Video Quiz Transactions</h2>");
		DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.FULL);
		Date now = new Date();
		
		Assignment a = null;
		try {
			long assignmentId = user.getAssignmentId();
			a = ofy().load().type(Assignment.class).id(assignmentId).safe();
		} catch (Exception e) {
			buf.append("Invalid assignment.");
			return buf.toString();
		}

		try {
			buf.append("Assignment Number: " + a.id + "<br>");
			Video v = ofy().load().type(Video.class).id(a.videoId).safe();
			buf.append("Title: "+ v.title + "<br>");
			buf.append("Valid: " + df.format(now) + "<p>");
			
			List<VideoTransaction> vts = ofy().load().type(VideoTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",a.id).order("downloaded").list();
			
			if (vts.size()==0) {
				buf.append("Sorry, we did not find any records for you in the database for this assignment.<p>"
						+ "<a href=/VideoQuiz?AssignmentId=" + a.id 
						+ "&sig=" + user.getTokenSignature() 
						+ ">Take me back to the video now.</a>");
			} else {
				// create a fresh Score entity to calculate the best score on this assignment
				Score s = Score.getInstance(user.getId(), a);

				buf.append("Your best score on this assignment is " + Math.round(10*s.getPctScore())/10. + "%.<br>");

				// try to validate the score with the LMS grade book entry
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
								+ "enforcement of assignment deadlines, grading policies and/or instructor discretion.<br>"
								+ "If you think this may be due to a stale score, you may submit this assignment for grading,<br>"
								+ "even for a score of zero, and ChemVantage will try to refresh your best score to the LMS.<p>");
					} else throw new Exception();
				} catch (Exception e) {
					buf.append("ChemVantage was unable to retrieve your score for this assignment from the LMS.<br>");
					if (s.score==0 && s.numberOfAttempts<=1) buf.append("It appears that you may not have submitted a score for this quiz yet. ");
					if (user.isInstructor()) buf.append("Some LMS providers do not store scores for instructors.");
					buf.append("<p>");
				}

				buf.append("<table><tr><th>Transaction Number</th><th>Downloaded</th><th>Quiz Score</th></tr>");
				for (VideoTransaction vt : vts) {
					buf.append("<tr><td>" + vt.id + "</td><td>" + df.format(vt.downloaded) + "</td><td align=center>" + (vt.graded==null?"-":100.*vt.score/vt.possibleScore + "%") +  "</td></tr>");
				}
				buf.append("</table><br>Missing scores indicate quizzes that were downloaded but not submitted for scoring.<p>");
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

			buf.append("<h1>Video Scores</h1>");
			if (a.title!=null) buf.append("Title: " + a.title + "<br/>");
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
			buf.append("<table><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>");
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
						+ (synched?"<td></td>":"<td><span id='cell" + forUserId + "'><button onClick=this.disabled=true;this.style.opacity=0.5;synchronizeScore('" + forUserId + "','" + user.getTokenSignature() + "','/VideoQuiz'); >sync</button></span></td>")
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
					+ "<form method=post action='/VideoQuiz' onsubmit='waitforSync()'; >"
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
	
	static boolean synchronizeScores(User user, Assignment a, HttpServletRequest request) {
		// This method looks for assignment scores that are different from the LMS scores and resubmits the score to the LMS
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();
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
			}
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	static String synchronizeScore(User user, Assignment a, String forUserId) {
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();  // can only do this for a known assignment
			if (LTIMessage.postUserScore(Score.getInstance(forUserId,a), forUserId).contains("Success")) return "OK";
		} catch (Exception e) {}
		return "Failed. Check assignment settings in the LMS.";
	}
	

/*
	String showSummary(User user,HttpServletRequest request) {
		if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		
		StringBuffer buf = new StringBuffer();
		
		Assignment a = null;
		try {
			long assignmentId = user.getAssignmentId();
			a = ofy().load().type(Assignment.class).id(assignmentId).safe();
		} catch (Exception e) {
			buf.append("Invalid assignment.");
			return buf.toString();
		}
		if (a==null) return "No assignment was specified for this request.";
		
		Video v = ofy().load().type(Video.class).id(a.videoId).now();
		
		if (!user.isInstructor()) return "You must be logged in as the instructor to view this page.";

		if (a.lti_ags_lineitem_url != null && a.lti_nrps_context_memberships_url != null) {
			try { // code for LTI version 1.3
				buf.append("<h3>" + a.assignmentType + " - " + v.title + "</h3>");
				buf.append("Assignment ID: " + a.id + "<br>");
				buf.append("Valid: " + new Date() + "<p>");
				buf.append("The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, "
						+ "and may or may not include user's names or emails, depending on the settings of your LMS. The easiest way to "
						+ "resolve any discrepancies between scores reported by the LMS grade book and ChemVantage is for the user to "
						+ "submit the assignment again (even for a score of zero). This causes ChemVantage to recalculate the "
						+ "user's best score and report it to the LMS. However, some discrepancies are to be expected, for example "
						+ "if the instructor adjusts a score in the LMS manually or if an assignment was submitted after the "
						+ "deadline and was not accepted by the LMS.<p>");

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
				
				buf.append("<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>");
				int i=0;
				for (Map.Entry<String,String[]> entry : membership.entrySet()) {
					if (entry == null) continue;
					String s = scores.get(entry.getKey());
					Score cvScore = cvScores.get(keys.get(entry.getKey()));
					i++;
					buf.append("<tr><td>" + i + ".&nbsp;</td>"
							+ "<td>" + entry.getValue()[1] + "</td>"
							+ "<td>" + entry.getValue()[2] + "</td>"
							+ "<td>" + entry.getValue()[0] + "</td>"
							+ "<td align=center>" + (s == null?" - ":s + "%") + "</td>"
							+ "<td align=center>" + (cvScore == null?" - ":String.valueOf(cvScore.getPctScore()) + "%") + "</td></tr>");
				}
				buf.append("</table>");
				return buf.toString();
			} catch (Exception e) {
				buf.append(e.toString());
			}
		} else {
			buf.append("Sorry, there is not enough information available from your LMS to support this request.<p>");			
		}
		return buf.toString();
	}
*/	
	String orderResponses(String[] answers) {
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

}
