package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.key;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
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

@WebServlet("/Sage")
public class Sage extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 1L;
	private static List<Concept> conceptList = null;
	private static Map<Long,Concept> conceptMap = null;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
				
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		refreshConcepts();
		
		//StringBuffer debug = new StringBuffer("Debug: ");
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("User token expired.");
			
			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			
			if (userRequest.equals("GetExplanation")) {  // AJAX request
				long questionId = Long.parseLong(request.getParameter("QuestionId"));
				long parameter = Long.parseLong(request.getParameter("Parameter"));
				Question q = ofy().load().type(Question.class).id(questionId).now();
				if (q.requiresParser()) q.setParameters(parameter);
				out.println(q.getExplanation());
				return;
			}
			
			// Get the SageTransaction for this assignment
			if (a == null) throw new Exception("Assignment not found.");
			SageTransaction st = null;
			try {
				st = ofy().load().type(SageTransaction.class).filter("userId",user.hashedId).filter("assignmentId",a.id).first().safe();
				if (!st.conceptIds.equals(a.conceptIds)) {  // the assignment was revised
					st = revisedTransaction(a,st); // synchronize st to the revised assignment
					ofy().save().entity(st).now();
				}
			} catch (Exception e) {  // first time visiting this assignment
				st = new SageTransaction(user.hashedId,a.id,a.conceptIds);
				ofy().save().entity(st).now();
				if (userRequest.isEmpty()) {
					out.println(welcomePage(user));
					return;
				}
			}
			
			Long conceptId = null;
			try {  // request from menuPage
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {
				if (userRequest.isEmpty()) userRequest = "menu";
			}
			Concept concept = conceptId == null ? null : conceptMap.get(conceptId);
			
			switch (userRequest) {
			case "AssignSageConcepts":
				out.println(assignConcepts(user,a));
				break;
			case "ShowSummary":
				out.println(showSummary(user,a));
				break;
			case "ConceptDescription":
				out.println(printConceptDescription(user,concept));
				break;
			case "menu":
				out.println(menuPage(user,st));
				break;
			case "InstructorPage":
				out.println(instructorPage(user,a));
				break;
			case "SynchronizeScore":
				out.println(synchronizeScore(user,a,request.getParameter("ForUserId")));
				break;
			default:
				boolean getHelp = Boolean.parseBoolean(request.getParameter("Help"));
				out.println(poseQuestion(user,st,concept,getHelp));
			}
		} catch (Exception e) {
			out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		refreshConcepts();
		
		StringBuffer debug = new StringBuffer("Debug: ");

		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("User token expired.");

			long aId = user.getAssignmentId();		
			Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
			if (a == null) throw new Exception("Assignment not found.");
			
			SageTransaction st = ofy().load().type(SageTransaction.class).filter("userId",user.hashedId).filter("assignmentId",a.id).first().safe();
				
			Concept concept = null;
			try {  // request from menuPage
				Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
				concept = conceptMap.get(conceptId);
			} catch (Exception e) {}
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";
			debug.append("1");

			switch (userRequest) {
			case "AddConcept":
				if (concept != null && !a.conceptIds.contains(concept.id)) {
					a.conceptIds.add(concept.id);
					ofy().save().entity(a).now();
				}
				out.println(assignConcepts(user,a));
				break;
			case "Ask Sage":
				try {
					if (concept == null) throw new Exception("Concept not found.");
					String userPrompt = request.getParameter("UserPrompt");
					if (userPrompt.isEmpty()) throw new Exception("The question was blank.");
					String nonce = request.getParameter("Nonce");
					if (!Nonce.isUnique(nonce)) throw new Exception("Only one question is allowed per concept level.");
					int score = st.scores[st.conceptIds.indexOf(concept.id)];
					out.println(askSage(user,concept.id,score,userPrompt));
					return;
				} catch (Exception e) {
					out.println(Subject.header("Sage")
							+ "<h1>Sorry, Sage cannot answer your question.</h1>"
							+ (e.getMessage()==null?e.toString():e.getMessage()) + "<p>"
							+ "Your session will continue in a moment."
							+ "<script>"
							+ " setTimeout(() => { window.location.replace('/Sage?sig=" + user.getTokenSignature() + "&ConceptId=" + (concept != null ? concept.id : 0) + "'); }, 2000);"  // pause, then continue
							+ "</script>"
							+ Subject.footer);
				}
				break;
			case "DeleteConcept":
				if (concept != null && a.conceptIds.remove(concept.id)) ofy().save().entity(a).now();
				out.println(assignConcepts(user,a));
				break;
			case "Score This Response":  
				try {			
					if (concept == null) throw new Exception("Concept not found.");
					out.println(printScore(user,request,concept.id,st));
				} catch (Exception e) {
					out.println(e.getMessage()==null?e.toString():e.getMessage());
				}
				break;
			case "Synchronize Scores":
				if (synchronizeScores(user,a)) out.println(instructorPage(user,a));
				else out.println("Synchronization request failed.");
				break;
			default: throw new Exception("Invalid request");
			}
		} catch (Exception e) {
			out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
		}
	}

	static String askAQuestion(User user, Long conceptId, String nonce) {
		StringBuffer buf = new StringBuffer();
		buf.append("<button id=askButton class='btn btn-primary' onClick=showAskForm(); >Ask Sage a Question</button>");
		buf.append("<div id=askForm style='display:none;' >"
				+ "If you have any question for Sage about <b>" + conceptMap.get(conceptId).title + "</b> you may ask it here:<br/>"
				+ "<form method=post action=/Sage onsubmit='waitForScore();'>"
				+ "<input type=hidden name=Nonce value='" + nonce + "' />"
				+ "<input type=hidden name=ConceptId value='" + conceptId + "' />"
				+ "<input type=hidden name=UserRequest value='Ask Sage' />"
				+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
				+ "<textarea rows=4 cols=80 name=UserPrompt ></textarea><br/>"
				+ "<input id=ask type=submit class='btn btn-primary' value='Ask Sage' />"
				+ "</form><p>"
				+ "</div>\n");
		
		buf.append("""
				<script>\
				function showAskForm() {\
				 document.getElementById('askButton').style='display:none;';\
				 document.getElementById('askForm').style='display:inline;';\
				}\
				function waitForScore() {
				 let b = document.getElementById('ask');
				 b.disabled = true;
				 b.value = 'Please wait a moment for Sage to answer.';
				}
				</script>"""); 
				
		return buf.toString();
	}

	static String askSage(User user, Long conceptId, int score, String userPrompt) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));

		JsonObject api_request = new JsonObject();
		api_request.addProperty("model", Subject.getGPTModel());
		JsonObject prompt = new JsonObject();
		prompt.addProperty("id", "pmpt_68b211507be881969a8a3e68371ff61e0f2a0de4d11aa01f");
		JsonObject variables = new JsonObject();
		variables.addProperty("student_question", userPrompt);
		variables.addProperty("key_concept", conceptMap.get(conceptId).title);
		prompt.add("variables", variables);
		api_request.add("prompt", prompt);

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
		if (response_code/100==2) {
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
		} else {
			reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
			api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
			throw new Exception(api_response.toString());
		}

		// Find the output text buried in the response JSON:
		JsonArray output = api_response.get("output").getAsJsonArray();
		JsonObject message = null;
		JsonObject output_text = null;
		String sage_answer = null;
		for (JsonElement element0 : output) {
			message = element0.getAsJsonObject();
			if (message.has("content")) {
				JsonArray content = message.get("content").getAsJsonArray();
				for (JsonElement element1 : content) {
					output_text = element1.getAsJsonObject();
					if (output_text.has("text")) {
						sage_answer = output_text.get("text").getAsString();
						break;
					}
				}
				break;
			}
		}
		
		/*
		BufferedReader reader = null;
		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model",Subject.getGPTModel());
		
		JsonArray messages = new JsonArray();
		JsonObject m1 = new JsonObject();  // api request message
		m1.addProperty("role", "system");
		m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
				+ "You must restrict your response to the topic " + conceptMap.get(conceptId).title + " in General Chemistry.");
		messages.add(m1);;
		JsonObject m2 = new JsonObject();  // api request message
		m2 = new JsonObject();  // api request message
		m2.addProperty("role", "user");
		m2.addProperty("content",userPrompt);
		messages.add(m2);
		api_request.add("messages", messages);
		URL u = new URL("https://api.openai.com/v1/chat/completions");
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

		reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		JsonObject api_response = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
*/
		//String content = api_response.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();

		if (sage_answer==null || sage_answer.isEmpty()) throw new Exception("It appears that the Sage was stumped!");
		
		buf.append("<h1>Sage Response</h1>");

		buf.append("<div style='width:800px;' >");
		buf.append("<img src=https://images.chemvantage.org/sage.png alt='Confucius Parrot' style='margin-left:20px;float:right;' />" + sage_answer);	
		buf.append("</div>");
		buf.append("<div id=helpful>"
				+ "<span><b>Was this answer helpful?</b></span> " 
				+ "<a role='button' href=#  style='vertical-align:middle' onclick=wasHelpful(true);><img src=https://images.chemvantage.org/thumbs_up.png alt='thumbs up' style='height:30px' /></a>&nbsp;"
				+ "<a role='button' href=#  style='vertical-align:middle' onclick=wasHelpful(false);><img src=https://images.chemvantage.org/thumbs_down.png alt='thumbs down' style='height:30px' /></a>"
				+ "</div><p>");
		// include some javascript to process the response
		buf.append("<script>"
				+ "var mathjax = document.createElement('script');\n"
				+ "mathjax.type = 'text/javascript';\n"
				+ "mathjax.src = 'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js';\n"
				+ "document.head.appendChild(mathjax);\n"
				+ "function wasHelpful(response) {"
				+ " document.getElementById('helpful').innerHTML='<br/><b>Thank you for the feedback.</b>';"
				+ " setTimeout(() => { window.location.replace('/Sage?sig=" + user.getTokenSignature() + "&ConceptId=" + conceptId + (score==100?"&UserRequest=menu":"") + "'); }, 1000);"  // pause, then continue
				+ " try {"
				+ "  var xmlhttp = new XMLHttpRequest();"
				+ "  xmlhttp.open('GET','/feedback?UserRequest=HelpfulAnswer&Response=' + response,true);"
				+ "  xmlhttp.send(null);"
				+ " } catch (error) {}"
				+ "}"
				+ "</script>");
		return buf.toString() + Subject.footer;
	}
	
	static String assignConcepts(User user, Assignment a) {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		try {
			buf.append("<h1>" + a.title + "</h1>"
					+ "<h2>Select Key Concepts For This Assignment</h2>"
					+ "The key concepts covered in this Sage tutoring assignment are listed below. "
					+ "You may delete any of these or add new concepts to customize this assignment "
					+ "for your class. Students may complete the concepts in any order.<p>");
			buf.append("<ol>");

			if (a.conceptIds.isEmpty()) buf.append("<b>This assignment has no key concepts.</b><p>");
			else {
				for (Long conceptId : a.conceptIds) {
					buf.append("<li>"
							+ "<form method=post action=/Sage>" + conceptMap.get(conceptId).title + "&nbsp;"
							+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
							+ "<input type=hidden name=ConceptId value=" + conceptId + " />"
							+ "<input type=hidden name=UserRequest value=DeleteConcept />"
							+ "<input type=submit value=Delete class='btn btn-primary' />"
							+ "</form>"
							+ "</li>");
				}
				buf.append("</ol>");
			}

			buf.append("<form method=post action=/Sage>Add a new concept to this assignment: "
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=UserRequest value=AddConcept />");
			buf.append("<select name=ConceptId><option>Select a concept</option>");
			for (Concept c : conceptList) {
				if (a.conceptIds.contains(c.id)) continue;
				buf.append("<option value=" + c.id + ">" + c.title + "</option>");
			}
			buf.append("</select>&nbsp;"
					+ "<input type=submit value='Add' class='btn btn-primary' />"
					+ "</form><p>");

			buf.append("<a id=backToInstPage href='/Sage?sig=" + user.getTokenSignature() + "&UserRequest=InstructorPage' class='btn btn-primary' onclick=waitAMoment('backToInstPage');>Return to the Instructor Page</a><p>");
			} catch (Exception e) {
			buf.append("<p>" + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Subject.footer;
	}
	
	static String getHelp(Question q) throws Exception {
		if (q.sageAdvice != null) return q.sageAdvice; // stored AI response

		JsonObject api_request = new JsonObject();
		api_request.addProperty("model", Subject.getGPTModel());
		JsonObject prompt = new JsonObject();
		prompt.addProperty("id", "pmpt_68b2145d09f0819793b40a1d0bec756d0c302820e63a43bd");
		JsonObject variables = new JsonObject();
		variables.addProperty("question_item", q.printForSage());
		prompt.add("variables", variables);
		api_request.add("prompt", prompt);

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
		if (response_code/100==2) {
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
		} else {
			reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
			api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
			return api_response.toString();
		}

		// Find the output text buried in the response JSON:
		JsonArray output = api_response.get("output").getAsJsonArray();
		JsonObject message = null;
		JsonObject output_text = null;
		String sage_answer = null;
		for (JsonElement element0 : output) {
			message = element0.getAsJsonObject();
			if (message.has("content")) {
				JsonArray content = message.get("content").getAsJsonArray();
				for (JsonElement element1 : content) {
					output_text = element1.getAsJsonObject();
					if (output_text.has("text")) {
						sage_answer = output_text.get("text").getAsString();
						break;
					}
				}
				break;
			}
		}
		
		
		/*
		BufferedReader reader = null;
		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model",Subject.getGPTModel());
		
		JsonArray messages = new JsonArray();
		JsonObject m1 = new JsonObject();  // api request message
		m1.addProperty("role", "system");
		m1.addProperty("content","You are a tutor assisting a college student taking General Chemistry. "
				+ "The student is requesting your help to answer the following question item:\n"
				+ q.printForSage()
				+ "\nPlease guide the student in the right general direction, "
				+ "but do not give the correct answer to the question.\n");
		messages.add(m1);;
		api_request.add("messages", messages);
		URL u = new URL("https://api.openai.com/v1/chat/completions");
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
			
		reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		JsonObject api_response = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		 */
		
		// Save the response for the next time a user needs help with this question
		if (!q.requiresParser()) {
			q.sageAdvice = sage_answer;
			ofy().save().entity(q);
		}
		
		return sage_answer;
	}

	static Long getNewQuestionId(SageTransaction st, Long conceptId) throws Exception {
		// We select the next question by calculating the user's scoreQuintile (1-5) and selecting
		// a question at random from those having a similar degree of difficulty (1-5). The selection 
		// process is random, but a bias is imposed to ensure that there is a 50% chance of selecting 
		// a question where the difficulty is the same as the user's scoreQuintile.
		Long questionId = null;
		StringBuffer debug = new StringBuffer();
		
		int[][] qSelCutoff = { {10,17,20,20},{4,14,18,20},{1,5,15,19},{0,2,6,16},{0,0,3,10} };
		debug.append("1");
		int score = st.scores[st.conceptIds.indexOf(conceptId)];
		debug.append("2");
		int scoreQuintile = score==100?4:score/20;			// ranges from 0-4
		int nConceptQuestions = ofy().load().type(Question.class).filter("assignmentType","Sage").filter("conceptId",conceptId).count();
		debug.append("3");
		if (nConceptQuestions == 0) throw new Exception("Sorry, there are no questions for this Concept.");

		// select a level of difficulty between 0-4 based on user's scoreQuintile
		Random rand = new Random();
		int r = rand.nextInt(20);
		int difficulty = 4;
		for (int i=0;i<4;i++) {
			if (r < qSelCutoff[scoreQuintile][i]) {
				difficulty = i;
				break;
			}
		}
		debug.append("4");

		int nQuintileQuestions =  ofy().load().type(Question.class).filter("assignmentType","Sage").filter("conceptId",conceptId).filter("difficulty",difficulty).count();

		List<Key<Question>> questionKeys = null;
		if (nQuintileQuestions >4) questionKeys = ofy().load().type(Question.class).filter("assignmentType","Sage").filter("conceptId",conceptId).filter("difficulty",difficulty).keys().list();
		else questionKeys = ofy().load().type(Question.class).filter("assignmentType","Sage").filter("conceptId",conceptId).keys().list();
		debug.append("5");

		Random random = new Random(st.random);
		debug.append("6");
		questionId = questionKeys.get(random.nextInt(questionKeys.size())).getId();
		Key<Concept> conceptKey = key(Concept.class,conceptId);
		List<Long> answeredConceptQuestionIds = st.answeredQuestionIds.get(conceptKey);
		debug.append("7");
		while (answeredConceptQuestionIds != null && answeredConceptQuestionIds.contains(questionId)) {
			answeredConceptQuestionIds.remove(questionId);
			questionId = questionKeys.get(random.nextInt(questionKeys.size())).getId();
		}
		debug.append("8");
			
		return questionId;
	}
	
	static String instructorPage(User user, Assignment a) {
	if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";
		refreshConcepts();
		
		StringBuffer buf = new StringBuffer();		
		try {
			buf.append(Subject.privacyPolicyBanner());
			
			if (a.title==null) {  // legacy assignment only provided topicId
				Topic t = ofy().load().type(Topic.class).id(a.topicId).now();
				a.title = t.title;
				if (a.conceptIds.isEmpty()) a.conceptIds = t.conceptIds;
				ofy().save().entity(a).now();
			}
			boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
			
			buf.append("<h1>Sage Tutor</1><h2>" + a.title + "</h2>");
			buf.append("<h3>Instructor Page</h3>"
					+ "Sage is an intelligent tutoring app for General Chemistry. You may customize this assignment by selecting the "
					+ "key concepts that will be covered by the tutoring sessions. Students will be introduced to each concept and then "
					+ "presented with a series of questions and problems of progressively increasing difficulty. The AI-powered tutor, "
					+ "Sage, is available to provide guidance when needed, and will provide a detailed step-by-step solutiuon to each "
					+ "problem after the student submits an answer. Each time the student reaches a higher quintile score (20%, 40%, etc) "
					+ "they are offered an opportunity to ask any open-ended question related to the key concept being covered.<p>");
			
			buf.append("From here, you may<UL>"
					+ "<LI><a href='/Sage?UserRequest=AssignSageConcepts&sig=" + user.getTokenSignature() + "'>Customize this assignment</a> by selecting key concepts to be covered.</LI>"
					+ (supportsMembership?"<LI><a href='/Sage?UserRequest=ShowSummary&sig=" + user.getTokenSignature() + "'>Review your students' Sage scores</a></LI>":"")
					+ "</UL><br/>");
			
			buf.append("<script>"
					+ "function wait() {"
					+ "  let b = document.getElementById('showAssignment');"
					+ "  b.innerHTML = 'Preparing your assignment...';"
					+ "}"
					+ "</script>");
			buf.append("<a id=showAssignment href='/Sage?UserRequest=menu&sig=" + user.getTokenSignature() + "' class='btn btn-primary' onclick=wait();>Show This Assignment</a><br/><br/>");
			
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

	static String menuPage (User user, SageTransaction st) {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));

		try {
			buf.append("<h1>Sage Is Your Tutor</h1>"
					+ "Select any of the key concepts listed below for this assignment.<br/>"
					+ "Your current scores are indicated in parentheses.<p>");

			//	construct an ordered list of assigned key concepts
			buf.append("<ol>");
			for (Long cId : st.conceptIds) {
				buf.append("<li><a href=/Sage?ConceptId=" + cId + "&sig=" + user.getTokenSignature() + (st.scores[st.conceptIds.indexOf(cId)]==0?"&UserRequest=ConceptDescription":"") + ">" 
						+ conceptMap.get(cId).title + "</a>" 
						+ "&nbsp;(" + st.scores[st.conceptIds.indexOf(cId)] + "%)</li>\n");
				if (st.scores[st.conceptIds.indexOf(cId)] == 100) {  // purge the answeredQuestionIds for this concept
					st.answeredQuestionIds.remove(key(Concept.class,cId));
				}
			}
			buf.append("</ol>");

			return buf.toString() + Subject.footer;
		} catch (Exception e) {
			return e.getMessage()==null?e.toString():e.getMessage();
		}
	}

	static String orderResponses(String[] answers) {
		if (answers==null) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String a : answers) studentAnswer = studentAnswer + a;
		return studentAnswer;
	}

	static String poseQuestion(User user, SageTransaction st, Concept concept, boolean getHelp) {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			Long questionId = getNewQuestionId(st,concept.id);
			debug.append("a");
			Question q = ofy().load().type(Question.class).id(questionId).now();
			debug.append("b");
			q.setParameters(st.random);
			debug.append("c");
			
			buf.append("<h1>" + concept.title + "</h1>");
			
			buf.append("<div style='width:800px; height=300px; overflow=auto; display:flex; align-items:center;'>");
			if (getHelp) {
				buf.append("<script id='Mathjax-script' async src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>");						
				buf.append("<div>"
						+ getHelp(q)
						+ "</div>"
						+ "<img src=https://images.chemvantage.org/sage.png alt='Confucius Parrot' style='float:right'>"
						+ "</div>");
				
				buf.append("<div id=helpful>"
						+ "<span><b>Is this helpful?</b></span> " 
						+ "<a role='button' href=#  style='vertical-align:middle' onclick=wasHelpful(true);><img src=https://images.chemvantage.org/thumbs_up.png alt='thumbs up' style='height:30px' /></a>&nbsp;"
						+ "<a role='button' href=#  style='vertical-align:middle' onclick=wasHelpful(false);><img src=https://images.chemvantage.org/thumbs_down.png alt='thumbs down' style='height:30px' /></a>"
						+ "</div><p>");
				// include some javascript to process the response
				buf.append("<script>"
						+ "function wasHelpful(response) {"
						+ " document.getElementById('helpful').innerHTML='<br/>Thank you for the feedback. ' "
						+ "  + (response?'I&apos;m always happy to help.':'I&apos;ll try to do better next time.');"
						+ " try {"
						+ "  var xmlhttp = new XMLHttpRequest();"
						+ "  xmlhttp.open('GET','/feedback?UserRequest=HelpfulHint&QuestionId=" + q.id + "&Response=' + response,true);"
						+ "  xmlhttp.send(null);"
						+ " } catch (error) { console.error(error); }"
						+ "}"
						+ "</script>");
			} else {
				buf.append("<div>"
						+ "Please submit your answer to the question below.<p>"
						+ "If you get stuck, I am here to help you, but your score will be higher if you do it by yourself.<p>"
						+ "<a id=help class='btn btn-primary' role=button href=/Sage?sig=" + user.getTokenSignature() + "&Help=true&ConceptId=" + concept.id + "&p=" + st.random + " onclick=waitForHelp(); >Please help me with this question</a>"
						+ "</div>"
						+ "<img src=https://images.chemvantage.org/sage.png alt='Confucius Parrot' style='float:right'>"
						+ "</div>");
				// include some javascript to change the submit button
				buf.append("<script>"
						+ "function waitForHelp() {"
						+ " let a = document.getElementById('help');"
						+ " a.innerHTML = 'Please wait a moment for Sage to answer.';"
						+ "}"
						+ "function showWorkBox(qid) {return;}"  // do nothing
						+ "</script>");
				}
			
			buf.append("<hr style='width:800px;margin-left:0'>");  // break between Sage helper panel and question panel
			debug.append("d");
			
			// Print the question for the student
			buf.append("<form method=post style='max-width:800px;' onsubmit='waitForScore();' >"
					+ "<input type=hidden name=QuestionId value='" + q.id + "' />"
					+ "<input type=hidden name=ConceptId value='" + q.conceptId + "' />"
					+ "<input type=hidden name=Parameter value='" + st.random + "' />"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=UserRequest value='Score This Response' />"
					+ q.print()
					+ "<input role='button' aria-label='submit button' id='sub" + q.id + "' type=submit class='btn btn-primary' />"
					+ "</form><p>");
			
			// include some javascript to change the submit button
			buf.append("<script>"
					+ "function waitForScore() {\n"
					+ " let b = document.getElementById('sub" + q.id + "');\n"
					+ " b.disabled = true;\n"
					+ " b.value = 'Please wait a moment while we score your response.';\n"
					+ "}\n"
					+ "</script>");
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage() + "<p>");  // + debug.toString());
		}
		return buf.toString() + Subject.footer;	
	}

	static String printConceptDescription(User user, Concept concept) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		try {
			if (concept == null) throw new Exception("No concept was specified for this requeat.");
			buf.append("""
					<script id='Mathjax-script' async src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>\
					<script>\
					function wait() {\
					  let b = document.getElementById('continueButton');\
					  b.innerHTML = 'Preparing your assignment...';\
					}
					</script>""");
			buf.append("<h1>" + concept.title + "</h1>"
					+ "<div style='max-width:800px'>"
					+ "<img src=https://images.chemvantage.org/sage.png alt='Confucius Parrot' style='float:right;margin:20px;'>"
					+ concept.getSummary() + "<p>"
					+ "<a class='btn btn-primary' id=continueButton onclick=wait(); href='/Sage?sig=" + user.getTokenSignature() + "&ConceptId=" + concept.id + "'>Continue</a>"
					+ "</div>");
	
		} catch (Exception e) {
			buf.append("<p>Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString() + Subject.footer;
	}

	static String printScore(User user, HttpServletRequest request, Long conceptId, SageTransaction st) throws Exception {
		// Prepare a section that allows the user to ask Sage a question
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		
		int rawScore = 0;  // result is 0, 1 or 2. Tne partial score 1 is for wrong sig figs.
		
		Long questionId = Long.parseLong(request.getParameter("QuestionId"));
		
		String[] responses = request.getParameterValues(Long.toString(questionId));
		String studentAnswer = orderResponses(responses);
		
		buf.append("""
				
				<script>\
				function wait(buttonId,message) {
				  let b = document.getElementById(buttonId);
				  b.innerHTML = message;
				}
				</script>
				""");
		
		if (studentAnswer == null || studentAnswer.isEmpty()) {
			buf.append("<h1>No answer was submitted</h1>\n"
					+ "<a id=tryAgain class='btn btn-primary' onclick=wait('tryAgain','Here we go!'); href='/Sage?sig=" + user.getTokenSignature() + "&ConceptId=" + conceptId + "'>Try Again</a><p>");
			return buf.toString() + Subject.footer;
		};
		
		Question q = ofy().load().type(Question.class).id(questionId).safe();
		long p = 0L;
		if (q.requiresParser()) {
			p = Long.parseLong(request.getParameter("Parameter"));
			q.setParameters(p);
		}
		
		// Construct a link that either reveals the static solution or submits a request to generate a full AI solution for parameterized questions
		StringBuffer showMeLink = new StringBuffer("<div>");
				
		// Get the raw score for the student's answer
		switch (q.getQuestionType()) {
		case 1:
		case 2:
		case 3:
		case 4:
		case 5:
			rawScore = q.isCorrect(studentAnswer)?2:q.agreesToRequiredPrecision(studentAnswer)?1:0;
			showMeLink.append("""
					<script>\
					function showAnswer() {
					  document.getElementById('link').style.display='none';
					  document.getElementById('shortAnswer').style.display='inline';
					}
					</script>
					""");
			showMeLink.append("<a id=link class='btn btn-primary' role='button' href=# onclick=showAnswer();>Show Me</a>\n");
			showMeLink.append("<div id=shortAnswer style='display:none';>\n"
					+ q.printAllToStudents(studentAnswer)
					+ " <p>\n"
					+ " <div id=explanation>"
					+ " <button id=explainThis class='btn btn-primary' onclick=getExplanation();>Please explain this answer</button>"
					+ " </div>\n"
					+ "<script>\n"
					+ "function getExplanation() {\n"
					+ "  document.getElementById('explainThis').innerHTML='Please wait a moment for Sage to respond.';\n"
					+ "  try {\n"
					+ "    var xmlhttp = GetXmlHttpObject();\n"
					+ "    if (xmlhttp==null) {\n"
					+ "      alert('Sorry, your browser does not support AJAX!');\n"
					+ "	     return false;\n"
					+ "    }\n"
					+ "	   xmlhttp.onreadystatechange=function() {\n"
					+ "      if (xmlhttp.readyState==4) {\n"
					+ "        document.getElementById('explanation').innerHTML = xmlhttp.responseText;\n"  // Sage explanation
					+ "        var mathjax = document.createElement('script');\n"
					+ "        mathjax.type = 'text/javascript';\n"
					+ "        mathjax.src = 'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js';\n"
					+ "        document.head.appendChild(mathjax);\n"
					+ "      }\n"
					+ "    }\n"
					+ "  } catch (error) {}\n"
					+ "  xmlhttp.open('GET','/Sage?sig=" + user.getTokenSignature() + "&UserRequest=GetExplanation&QuestionId=" + q.id + "&Parameter=" + p + "',true);\n"
					+ "  xmlhttp.send(null);\n"
					+ "}\n"
					+ "</script>\n"
					+ "</div><p>\n");
			break;
		case 6:  // Handle five-star rating response
			try {
				if (Integer.parseInt(studentAnswer) > 0) rawScore = 2;  // full marks for submitting a response
			} catch (Exception e) {}
			break;
		case 7:  // New section for scoring essay questions with Chat GPT
			JsonObject api_score = scoreEssayQuestion(q.text,studentAnswer);  // these are used to score essay questions using ChatGPT
			rawScore = api_score.get("score").getAsInt()/2; 	// scale 0-2
			showMeLink.append(api_score.get("feedback").getAsString());  // displays feedback in lieu of link
			break;
		default: throw new Exception("Unable to determine question type");
		}

		showMeLink.append("</div>");
		
		// Update and save the Score object
		boolean level_up = st.update(rawScore,conceptId);
		// Save the questionId in a List of answered questoinIds in the transaction
		List<Long> answeredConceptQuestionIds = st.answeredQuestionIds.get(key(Concept.class,conceptId));
		if (answeredConceptQuestionIds == null) {
			answeredConceptQuestionIds = new ArrayList<Long>();
			answeredConceptQuestionIds.add(questionId);
		} else if (!answeredConceptQuestionIds.contains(questionId)) answeredConceptQuestionIds.add(questionId);
		st.random = new Random().nextLong();
		// Save the updated transaction
		ofy().save().entity(st).now();
		
		try {
			if (q.getQuestionType() == 6) {
				buf.append(rawScore==2?"<h1>Thank you for your rating.</h1><b>You received full credit for this question.</b>":"<h1>No rating was submitted.</h1><b>You did not receive credit for this question.</b>");
			} else {
				switch (rawScore) {  // 0, 1 or 2
				case 2:  // correct answer
					buf.append("<h1>Congratulations!</h1>\n"
							+ "<b>Your answer is correct. </b><IMG SRC=https://images.chemvantage.org/checkmark.png ALT='Check mark' align=bottom /><p>\n"
							+ "<div style='width:800px;display:flex;align-items:center;'>\n"
							
							+ showMeLink
							+ "<img id=polly src='https://images.chemvantage.org/parrot.png' alt='Parrot character' style='margin-left:20px;'>\n"
							+ "</div>\n");
					break;
				case 1: // partial credit			
					buf.append("<h1>Your answer is partially correct</h1>\n"
							+ "<b>You received half credit.</b><p>\n"
							+ "<div style='width:800px;display:flex; align-items:center;'>\n"
							+ showMeLink
							+ "<img id=polly src='https://images.chemvantage.org/parrot1.png' alt='Parrot character' style='margin-left:20px;'>\n"
							+ "</div>\n");
					break;
				case 0: // wrong answer
					buf.append("<h1>Sorry, your answer is not correct.<IMG SRC=https://images.chemvantage.org/xmark.png ALT='X mark' align=middle></h1>\n"
							+ "<div style='width:800px;display:flex; align-items:center;'>\n"
							+ showMeLink
							+ "<img id=polly src='https://images.chemvantage.org/parrot0.png' alt='Parrot character' style='margin-left:20px;'>\n"
							+ "</div>\n");
					break;
				}
			}
			
			int score = st.scores[st.conceptIds.indexOf(conceptId)];
			if (score == 100) { 
				buf.append("<h2>Your score is 100%</h2>");
				buf.append("You have mastered the concept: <b>" + conceptMap.get(conceptId).title +"</b>.<p>");
				if (level_up) buf.append(askAQuestion(user, conceptId, Nonce.generateNonce()));
			} else if (level_up) {
				buf.append("<h2>You have moved up to Level " + (score/20 + 1) + ".</h2>"
						+ "<b>Your current score on this concept is " + score + "%.</b><p>");
				buf.append(askAQuestion(user,conceptId,Nonce.generateNonce()) + " Otherwise...");
				// Report the user's score to the LMS
				String payload = "AssignmentId=" + user.getAssignmentId() + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8");
				Utilities.createTask("/ReportScore",payload);
			} else {
				buf.append("<p><b>Your current score on this concept is " + score + "%.</b>&nbsp;");
			}
			// print a button to continue
			buf.append("<a id=continue class='btn btn-primary' onclick=wait('continue','Please wait a moment...'); href='/Sage?sig=" + user.getTokenSignature() + "&ConceptId=" + conceptId + (score==100?"&UserRequest=menu":"") + "'>Continue</a><p>");
		} catch (Exception e) {
			buf.append("<p>" + e.getMessage()==null?e.toString():e.getMessage());
		}
		
		return buf.toString() + Subject.footer;
	}

	static void refreshConcepts() {
		if (conceptMap == null) {
    		conceptList = ofy().load().type(Concept.class).order("orderBy").list();
    		conceptList.removeIf(obj -> obj != null && obj.orderBy != null && obj.orderBy.startsWith(" 0"));
    		conceptMap = new HashMap<Long,Concept>();
    		for (Concept c : conceptList) conceptMap.put(c.id, c);
    	}
	}
	
	static SageTransaction revisedTransaction(Assignment a, SageTransaction st) throws Exception {
		// this method creates a revised version of the SageTransaction 
		// in case the assignment conceptIds is revised by the instructor
		SageTransaction revised = new SageTransaction(st.userId, a.id, a.conceptIds);
		for (int i=0; i<a.conceptIds.size(); i++) {  // copy the scores if they exist
			int j = st.conceptIds.indexOf(a.conceptIds.get(i)); // index of conceptId in old st; may be -1
			revised.scores[i] = j==-1?0:st.scores[j];
		}
		revised.id = st.id;
		revised.answeredQuestionIds = st.answeredQuestionIds;
		revised.created = st.graded;
		revised.graded = st.graded;
		revised.random = st.random;
		revised.helped = st.helped;
		return revised;
	}
	
	static JsonObject scoreEssayQuestion(String questionText, String studentAnswer) throws Exception {
		if (studentAnswer.length()>800) studentAnswer = studentAnswer.substring(0,799);
		
		JsonObject api_request = new JsonObject();
		api_request.addProperty("model", Subject.getGPTModel());
		JsonObject prompt = new JsonObject();
		prompt.addProperty("id", "pmpt_68b05dd3c7e88190b02ec3c4a41e412003d177cd13da4c5d");
		JsonObject variables = new JsonObject();
		variables.addProperty("question_item", questionText);
		variables.addProperty("student_answer", studentAnswer);
		prompt.add("variables", variables);
		api_request.add("prompt", prompt);

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
		if (response_code/100==2) {
			reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			api_response = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
		} else {
			reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
			reader.close();
		}

		// Find the output text buried in the response JSON:
		if (api_response == null) throw new Exception("Invalid API response.");
		JsonArray output = api_response.get("output").getAsJsonArray();
		JsonObject message = null;
		JsonObject output_text = null;
		JsonObject essay_score = new JsonObject();
		for (JsonElement element0 : output) {
			message = element0.getAsJsonObject();
			if (message.has("content")) {
				JsonArray content = message.get("content").getAsJsonArray();
				for (JsonElement element1 : content) {
					output_text = element1.getAsJsonObject();
					if (output_text.has("text")) {
						essay_score = JsonParser.parseString(output_text.get("text").getAsString()).getAsJsonObject();
						break;
					}
				}
				break;
			}
		}
		
		/*
		JsonObject api_request = new JsonObject();  // these are used to score essay questions using ChatGPT
		api_request.addProperty("model",Subject.getGPTModel());
		JsonObject m = new JsonObject();  // api request message
		m.addProperty("role", "user");
		String prompt = "Question: \"" + questionText +  "\"\n My response: \"" + studentAnswer + "\"\n "
				+ "Using JSON format, give a score for my response (integer in the range 0 to 5) "
				+ "and feedback for how to improve my response.";
		m.addProperty("content", prompt);
		JsonArray messages = new JsonArray();
		messages.add(m);
		api_request.add("messages", messages);
		URL u = new URL("https://api.openai.com/v1/chat/completions");
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
		
		BufferedReader reader = null;
		reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		JsonObject api_response = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();
		
		// get the ChatGPT score from the response:
		JsonObject api_score = null;
		try {
			String content = api_response.get("choices").getAsJsonArray().get(0).getAsJsonObject().get("message").getAsJsonObject().get("content").getAsString();
			api_score = JsonParser.parseString(content).getAsJsonObject();
			return api_score;
		} catch (Exception e) {
			api_score = new JsonObject();
			api_score.addProperty("score", 0);
			api_score.addProperty("feedback", "Sorry, an error occurred: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		*/
		
		return essay_score;
	}
	
	static String showSummary(User user,Assignment a) {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		if (a==null) return "No assignment was specified for this request.";

		if (!user.isInstructor()) return "You must be logged in as the instructor to view this page.";

		try {
			if (a.lti_nrps_context_memberships_url==null) throw new Exception("No Names and Roles Provisioning support.");

			buf.append("<h1>Sage Tutor Scores</h1>");
			buf.append("Title: " + a.title + "<br/>");
			buf.append("Assignment ID: " + a.id + "<br/>");
			buf.append("Valid: " + new Date() + "<p>");
			buf.append("The roster below is obtained using the Names and Role Provisioning service offered by your learning management system, "
					+ "and may or may not include user's names or emails, depending on the settings of your LMS.<br/><br/>");

			Map<String,String> scores = LTIMessage.readMembershipScores(a);
			if (scores==null) scores = new HashMap<String,String>();  // in case service call fails

			Map<String,String[]> membership = LTIMessage.getMembership(a);
			if (membership==null) membership = new HashMap<String,String[]>(); // in case service call fails

			List<SageTransaction> sTList = ofy().load().type(SageTransaction.class).filter("assignmentId",a.id).list();
			Map<String,SageTransaction> stMap = new HashMap<String,SageTransaction>();
			for (SageTransaction sT : sTList) stMap.put(sT.userId, sT);
			
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
			String platform_id = d.getPlatformId() + "/";
			
			buf.append("<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>");
			int i=0;
			int nMismatched = 0;
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				String lmsScoreString = scores.get(entry.getKey());
				lmsScoreString = (lmsScoreString==null?" - ":lmsScoreString + "%");
				String hashedId = Subject.hashId(platform_id + entry.getKey());
				SageTransaction st = stMap.get(hashedId);
				int cvScore = 0;  // overall total score
				if (st != null) {
					for (Long conceptId : a.conceptIds) {
						int j = st.conceptIds.indexOf(conceptId);
						cvScore += j==-1?0:st.scores[j];  // add scores for all concepts in the assignment
					}
				}
				
				String cvScoreString = cvScore==0?" - ":String.valueOf(Math.round(10.*cvScore/a.conceptIds.size())/10.) + "%";
				boolean synched = !"Learner".equals(entry.getValue()[0]) || cvScoreString.equals(lmsScoreString);
				String forUserId = platform_id + entry.getKey();  // only send hashed values through links
				i++;
				buf.append("<tr><td>" + i + ".&nbsp;</td>"
						+ "<td>" + entry.getValue()[1] + "</td>"
						+ "<td>" + entry.getValue()[2] + "</td>"
						+ "<td>" + entry.getValue()[0] + "</td>"
						+ "<td align=center>" + lmsScoreString + "</td>"
						+ "<td align=center>" + cvScoreString + "</td>"
						//+ "<td align=center><a href=/Homework?UserRequest=Review&sig=" + user.getTokenSignature() + "&ForUserId=" + forUserId + "&ForUserName=" + entry.getValue()[1].replaceAll(" ","+") + ">show</a></td>"
						+ (synched?"":"<td><span id='cell" + forUserId + "'><button onClick=this.disabled=true;this.style.opacity=0.5;synchronizeScore('" + forUserId + "','" + user.getTokenSignature() + "','/Sage'); >sync</button></span></td>")
						+ "</tr>");
				// Flag this score set as unsynchronized only if there is one or more non-null ChemVantage Learner score that is not equal to the LMS score
				// Ignore Instructor scores because the LMS often does not report them, and ignore null cvScore entities because they cannot be reported.
				if (!synched) nMismatched++;
			}
			buf.append("</table><br/>");
			if (nMismatched > 0) {
				buf.append("You may use the individual 'sync' buttons above to resubmit any ChemVantage score to the LMS. Note that in some cases, mismatched scores are expected (e.g., when "
						+ "the instructor overrides a score or when a late submission is not accepted by the LMS). You may have to adjust the settings in your LMS to accept the "
						+ "revised score (e.g., change the due date, grade override or allowed number of submissions).<p>");
			}
			if (nMismatched>1) {
				buf.append("Use the button below to synchronize all of the Learner scores. This might take a minute, depending on the number of mismatches.<br/>"
					+ "<form method=post action=/Sage onsubmit=waitforSync(); >"
					+ "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
					+ "<input type=hidden name=UserRequest value='Synchronize Scores' />"
					+ "<input type=submit id=syncAll value='Synchronize All Scores' />"
					+ "</form><p>");
			}
			buf.append("<a id=backToInstPage href='/Sage?sig=" + user.getTokenSignature() + "&UserRequest=InstructorPage' class='btn btn-primary' onclick=waitAMoment('backToInstPage');>Return to the Instructor Page</a><p>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString() + Subject.footer;
	}
	
	static String synchronizeScore(User user, Assignment a, String forUserId) {
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();  // can only do this for a known assignment
			if (LTIMessage.postUserScore(Score.getInstance(forUserId,a), forUserId).contains("Success")) return "OK";
		} catch (Exception e) {}
		return "Failed. Check assignment settings in the LMS.";
	}

	static boolean synchronizeScores(User user,Assignment a) {
		// This method looks for assignment scores that are different from the LMS scores and resubmits the score to the LMS
		try {
			if (!user.isInstructor()) throw new Exception();  // only instructors can use this function
			if (a==null) throw new Exception();  // can only do this for a known assignment
			if (a.lti_ags_lineitem_url == null || a.lti_nrps_context_memberships_url == null) throw new Exception(); // need both of these to work
			Map<String,String> scores = LTIMessage.readMembershipScores(a);
			if (scores==null || scores.size()==0) throw new Exception();  // this only works if we can get info from the LMS
			Map<String,String[]> membership = LTIMessage.getMembership(a);
			if (membership==null || membership.size()==0) throw new Exception();  // there must be some members of this class
			
			Deployment d = ofy().load().type(Deployment.class).id(a.domain).safe();
			String platform_id = d.getPlatformId() + "/";
			
			List<SageTransaction> sTList = ofy().load().type(SageTransaction.class).filter("assignmentId",a.id).list();
			Map<String,SageTransaction> stMap = new HashMap<String,SageTransaction>();
			for (SageTransaction sT : sTList) stMap.put(sT.userId, sT);
			
			for (Map.Entry<String,String[]> entry : membership.entrySet()) {
				if (entry == null) continue;
				String lmsScoreString = scores.get(entry.getKey());
				lmsScoreString = (lmsScoreString==null?" - ":lmsScoreString + "%");
				String hashedId = Subject.hashId(platform_id + entry.getKey());
				SageTransaction st = stMap.get(hashedId);
				int cvScore = 0;  // overall total score
				if (st != null) {
					for (Long conceptId : a.conceptIds) {
						int j = st.conceptIds.indexOf(conceptId);
						cvScore += j==-1?0:st.scores[j];  // add scores for all concepts in the assignment
					}
				}
				
				String cvScoreString = cvScore==0?" - ":String.valueOf(Math.round(10.*cvScore/a.conceptIds.size())/10.) + "%";
				boolean synched = !"Learner".equals(entry.getValue()[0]) || cvScoreString.equals(lmsScoreString);
				if (synched) continue;  // scores match; no action required
				
				String payload = "AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8");
				Utilities.createTask("/ReportScore",payload);
			}
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	static String welcomePage(User user) throws Exception {
		StringBuffer buf = new StringBuffer(Subject.header("Sage"));
		buf.append("<script>"
				+ "function wait() {"
				+ "  let b = document.getElementById('start');"
				+ "  b.innerHTML = 'Starting your session...';"
				+ "}"
				+ "</script>");
		buf.append("<h1>Welcome to Sage</h1>"
				+ "<h2>Sage is an intelligent tutor for General Chemistry.</h2>"
				+ "<div style='max-width:800px;'>"
				+ "<img src=https://images.chemvantage.org/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "This assignment covers one or more key concepts that have been assigned by your "
				+ "instructor. For each concept you will be guided through a series of questions and problems, "
				+ "with the Sage at your side, ready to provide help whenever you need it. "
				+ "Each concept has 5 levels. Whenever you complete a level or finish a concept with a score of 100%, "
				+ "you will have an opportunity to ask Sage a question of your choice about that concept.<p>"
				+ "<a id=start onclick=wait(); href='/Sage?UserRequest=menu&sig=" + user.getTokenSignature() + "' class='btn btn-primary'>Continue</a>"
				+ "</div>");
		return buf.toString() + Subject.footer;
	}

}
