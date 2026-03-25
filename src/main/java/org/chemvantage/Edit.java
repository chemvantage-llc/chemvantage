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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Type;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/* 
 * Access to this servlet is restricted to ChemVantage admin users and the project service account
 * by specifying login: admin in a url handler of the project app.yaml file
 */
@WebServlet("/Edit")
public class Edit extends HttpServlet {

	private static final long serialVersionUID = 137L;
	private static final String VERTEX_AI_LOCATION = "us-central1";
	private static final String VERTEX_AI_DEFAULT_MODEL = "gemini-2.5-flash";
	//private static final String CORRECT_ANSWER_PROMPT_ID = "pmpt_69aca16a881081938f557752ab5ef5a107c7ea937e14ff76";
	//private static final String AI_VALIDATOR_PROVIDER = System.getProperty("cv.ai.validator", "gemini").trim().toLowerCase(); // gemini | chatgpt
	Map<Key<Question>,Question> questions = new HashMap<Key<Question>,Question>();
	Map<Key<Question>,Integer> pointValue = new HashMap<Key<Question>,Integer>();
	List<Concept> concepts = new ArrayList<Concept>();
	Map<Long,Concept> conceptMap = new HashMap<Long,Concept>();

	private static String getVertexAiModel() {
		String model = Subject.getGemModel();
		return model == null || model.isBlank() || "changeMe".equalsIgnoreCase(model)
				? VERTEX_AI_DEFAULT_MODEL
				: model;
	}
	
	public String getServletInfo() {
		return "This servlet is used by editors and admins to create, review, edit and delete question items.";
	}
	
/* ====================== NOTES ========================
 * 
 * The general strategy here is to provide a space for editors to receive/edit/activate/delete question items 
 * submitted from the community, and to create/edit/delete questions of their own.
 * Proposed questions are stored temporarily as ProposedQuestion items in the database, and if approved, are
 * then converted to regular Question items with a new ID number.
 * 
 */
	
	public void doGet(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		
		try {
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);
			
			out.println(Subject.getHeader(user));
			
			String userRequest = request.getParameter("UserRequest");
			if (userRequest == null) userRequest = "";

			switch (userRequest) {
			case "ManageConcepts":
				out.println(conceptsForm(request));
				break;
			case "ManageTopics": 
				out.println(topicsForm(request)); 
				break;
			case "ManageVideos": 
				out.println(videosForm()); 
				break;
			case "ManageOrphanQuestions":
				out.println(manageOrphanQuestions(request));
				break;
			case "EditVideo": 
				out.println(editVideoForm(request)); 
				break;
			case "ManageTexts": 
				out.println(textsForm(user,request)); 
				break;
			case "Preview": 
				out.println(previewQuestion(user,request)); 
				break;
			case "NewQuestionForm": 
				out.println(newQuestionForm(user,request)); 
				break;
			case "Review":
			case "Skip":
				out.println(reviewProposedQuestion(user,request)); 
				break;
			case "Edit": 
				out.println(editCurrentQuestion(user,request));
				break;
			case "Discard Question":
				try {
					long proposedQuestionId = Long.parseLong(request.getParameter("ProposedQuestionId"));
					ofy().delete().key(key(ProposedQuestion.class,proposedQuestionId));
				} catch (Exception e) {}
				out.println(reviewProposedQuestion(user,request));
				break;
			case "ValidateAssignmentWithAI":
				out.println(validateAssignmentWithAI(request)); // interactive validation that displays results on the page
				break;
			case "Refresh":
				questions.clear();
			default: out.println(editorsPage(user,request));
			}
			
		} catch (Exception e) {
			out.println(e.getMessage());
		}
		out.println(Subject.footer);
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException {
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";

		boolean isJsonRequest = "ValidateCorrectAnswerWithAI".equals(userRequest);
		response.setContentType(isJsonRequest ? "application/json" : "text/html");
		PrintWriter out = response.getWriter();
		
		try {
			String userId = "admin";
			User user = new User("https://"+request.getServerName(), userId);
			user.setIsChemVantageAdmin(true);

			if (isJsonRequest) {
				out.println(validateQuestionItemWithAI(request));
				return;
			}

			out.println(Subject.getHeader(user));
			
			switch (userRequest) {
			case "CreateTopic":
				createTopic(user,request);
				out.println(topicsForm(request));
				break;
			case "UpdateTopic": 
				updateTopic(user,request); 
				out.println(topicsForm(request));
				break;
			case "DeleteTopic": 
				deleteTopic(user,request);
				out.println(topicsForm(request));
				break;
			case "CreateConcept":
				createConcept(user,request);
				out.println(conceptsForm(request));
				break;
			case "UpdateConcept": 
				updateConcept(user,request); 
				out.println(conceptsForm(request));
				break;
			case "DeleteConcept": 
				deleteConcept(user,request);
				out.println(conceptsForm(request));
				break;
			case "Create Video":
				createVideo(user,request);
				out.println(videosForm()); 
				break;
			case "Update Video":
				updateVideo(request);
				out.println(videosForm()); 
				break;
			case "Delete Video":
				deleteVideo(user,request);
				out.println(videosForm()); 
				break;
			case "Create Quizlet":
				out.println(updateQuizlet(request)); 
				break;
			case "Update Quizlet":
				out.println(updateQuizlet(request)); 
				break;
			case "Delete Quizlet":
				out.println(deleteQuizlet(request)); 
				break;
			case "Create Text":
				createText(user,request);
				out.println(textsForm(user,request));
				break;
			case "Update Text":
				updateText(user,request);
				out.println(textsForm(user,request));
				break;
			case "Delete Text":
				deleteText(user,request);
				out.println(textsForm(user,request));
				break;
			case "Create Chapter":
				createChapter(request);
				out.println(textsForm(user,request));
				break;
			case "Update Chapter":
				updateChapter(request);
				out.println(textsForm(user,request));
				break;
			case "Delete Chapter":
				deleteChapter(request);
				out.println(textsForm(user,request));
				break;
			case "Preview": 
				out.println(previewQuestion(user,request)); 
				break;
			case "Save New Question":
				createQuestion(user,request);
				out.println(editorsPage(user,request)); 
				break;
			case "Update Question":
				updateQuestion(user,request);
				out.println(editorsPage(user,request)); 
				break;
			case "Delete Question":
				deleteQuestion(user,request);
				out.println(editorsPage(user,request)); 
				break;
			case "AssignToConcept":
				assignToConcept(user,request);
				out.println(manageOrphanQuestions(request)); 
				break;
			case "Hide":
				hideDuplicateQuestion(user,request);
				out.println(editorsPage(user,request)); 
				break;
			case "Activate This Question":
				createQuestion(user,request);
				try {
					long proposedQuestionId = Long.parseLong(request.getParameter("ProposedQuestionId"));
					ofy().delete().key(key(ProposedQuestion.class,proposedQuestionId)).now();
				} catch (Exception e) {}
				out.println(reviewProposedQuestion(user,request));
				break;
			case "Discard Question":
				try {
					long proposedQuestionId = Long.parseLong(request.getParameter("ProposedQuestionId"));
					ofy().delete().key(key(ProposedQuestion.class,proposedQuestionId)).now();
				} catch (Exception e) {}
				out.println(reviewProposedQuestion(user,request));
				break;
			case "CopyAllQuestions":
				copyQuestionsToPracticeExam(user,request);
				out.println(editorsPage(user,request));
				break;
			case "DeleteAllQuestions":
				deleteAllQuestions(user,request);
				out.println(editorsPage(user,request));
				break;
			case "Assign Key Concepts":
				assignKeyConcepts(user,request);
				out.println(editorsPage(user,request));
				break;
			case "ValidateAssignmentWithAI":
				validateAssignmentWithAI(request); // initiates a Cloud Task; results are emailed to the administrator and not displayed on the page
				break;
			default: out.println(editorsPage(user,request));
			}

		} catch (Exception e) {
			if (isJsonRequest) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				JsonObject error = new JsonObject();
				error.addProperty("success", false);
				error.addProperty("message", e.getMessage() == null ? e.toString() : e.getMessage());
				out.println(error);
				return;
			}
			out.println("Error: " + (e.getMessage()==null?e.toString():e.getMessage()));
			Utilities.sendEmail("ChemVantage Administrator", "admin@chemvantage.org", "Error in Edit servlet", "An error occurred in the Edit servlet:\n\n" + (e.getMessage()==null?e.toString():e.getMessage()));
		}
		if (!isJsonRequest) out.println(Subject.footer);
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
		String assignmentType = request.getParameter("AssignmentType");
		Long conceptId = null;
		try {
			Long requestedConceptId = Long.parseLong(request.getParameter("ConceptId"));
			if (requestedConceptId != null && requestedConceptId > 0L) conceptId = requestedConceptId;
		} catch (Exception e) {}
		String learn_more_url = request.getParameter("LearnMoreURL");
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
		q.conceptId = conceptId;
		q.learn_more_url = learn_more_url;
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
		q.authorId = request.getParameter("AuthorId");
		q.editorId = request.getParameter("EditorId");
		q.scrambleChoices = Boolean.parseBoolean(request.getParameter("ScrambleChoices"));
		q.strictSpelling = Boolean.parseBoolean(request.getParameter("StrictSpelling"));
		String checkedByAI = request.getParameter("CheckedByAI");
		q.checkedByAI = (checkedByAI==null?null:Boolean.parseBoolean(checkedByAI));
		q.validateFields();
		return q;
	}
void assignKeyConcepts(User user,HttpServletRequest request) throws Exception {
		long conceptId = Long.parseLong(request.getParameter("ConceptId"));
		String[] questionIdStrings = request.getParameterValues("QuestionId");
		List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
		for (int i=0; i<questionIdStrings.length; i++) questionKeys.add(key(Question.class,Long.parseLong(questionIdStrings[i])));
		List<Question> revised = new ArrayList<Question>();
		for (Key<Question> k : questionKeys) {
			Question q = this.questions.get(k);
			q.conceptId = conceptId;
			revised.add(q);
		}
		ofy().save().entities(revised);
	}

String assignmentTypeDropDownBox(String defaultType) {
		return assignmentTypeDropDownBox(defaultType,false);
	}

String assignmentTypeDropDownBox(String defaultType,boolean autoSubmit) {
	if (defaultType == null) defaultType = "";
	StringBuffer buf = new StringBuffer("\n<SELECT NAME=AssignmentType" + (autoSubmit?" onChange=submit()>":">"));
	if (defaultType.isEmpty()) buf.append("\n<OPTION VALUE=''>Select a type</OPTION>");
	buf.append("<OPTION" + (defaultType.equals("Quiz")?" SELECTED":"") + ">Quiz</OPTION>"
			+ "<OPTION" + (defaultType.equals("Homework")?" SELECTED":"") + ">Homework</OPTION>"
			+ "<OPTION" + (defaultType.equals("Custom")?" SELECTED":"") + ">Custom</OPTION>"
			+ "<OPTION" + (defaultType.equals("Sage")?" SELECTED":"") + ">Sage</OPTION>"
			+ "<OPTION" + (defaultType.equals("Exam")?" SELECTED":"") + ">Exam</OPTION>"
			+ "<OPTION" + (defaultType.equals("Poll")?" SELECTED":"") + ">Poll</OPTION>"
			+ "<OPTION" + (defaultType.equals("Video")?" SELECTED":"") + ">Video</OPTION>"
			+ "</SELECT>");
	return buf.toString();
}

void assignToConcept(User user, HttpServletRequest request) {
		try {
			Long conceptId = Long.parseLong(request.getParameter("ConceptId"));
			Long questionId = Long.parseLong(request.getParameter("QuestionId"));
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			if (conceptId != null) {
				q.conceptId = conceptId;
				ofy().save().entity(q).now();
			}
		} catch (Exception e) {}
	}

	String conceptSelectBox() {
	StringBuffer buf = new StringBuffer();
	if (concepts.isEmpty() || concepts.size() != ofy().load().type(Concept.class).count()) {
		concepts = ofy().load().type(Concept.class).order("orderBy").list();
	}
	buf.append("<SELECT id=selectConcept NAME=ConceptId onChange=javascript:enableSubmit(); ><OPTION VALUE=0>Select a key concept</OPTION>");
	for (Concept c : concepts) buf.append("<OPTION VALUE=" + c.id + ">" + c.title + "</OPTION>");
	buf.append("</SELECT>");
	
	return buf.toString();
}

	String conceptSelectBox(Long conceptId) {
		StringBuffer buf = new StringBuffer("<select name=ConceptId>"
				+ "<option>Select a concept</option>");
		List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
		for (Concept c : concepts) buf.append("<option value=" + c.id + (c.id.equals(conceptId)?" selected>":">") + c.title + "</option>");
		buf.append("</select>");
		return buf.toString();
	}

	String conceptSelectBox(Topic t, long conceptId) {
		StringBuffer buf = new StringBuffer();
		if (concepts.isEmpty() || concepts.size() != ofy().load().type(Concept.class).count()) {
			concepts = ofy().load().type(Concept.class).order("orderBy").list();
		}
		buf.append("<SELECT NAME=ConceptId><OPTION VALUE=0>Select a key concept</OPTION>");
		for (Concept c : concepts) if (t.conceptIds.contains(c.id)) 
			buf.append("<OPTION VALUE=" + c.id + (c.id==conceptId?" SELECTED>":">") + c.title + "</OPTION>");
		buf.append("</SELECT>");
		
		return buf.toString();
	}

	String conceptsDropDownBox(Topic t) {
		StringBuffer buf = new StringBuffer();
		if (concepts.isEmpty() || concepts.size() != ofy().load().type(Concept.class).count()) {
			concepts = ofy().load().type(Concept.class).order("orderBy").list();
		}
		buf.append("<SELECT NAME=ConceptId SIZE=4 MULTIPLE>");
		for (Concept c : concepts) buf.append("<OPTION VALUE=" + c.id + (t.conceptIds.contains(c.id)?" SELECTED>":">") + c.title + "</OPTION>");
		buf.append("</SELECT>");
		return buf.toString();
	}

	String conceptsDropDownBox(Chapter ch) {
		StringBuffer buf = new StringBuffer();
		if (ch==null) ch = new Chapter();
		if (concepts.isEmpty() || concepts.size() != ofy().load().type(Concept.class).count()) {
			concepts = ofy().load().type(Concept.class).order("orderBy").list();
		}
		buf.append("<SELECT NAME=ConceptId SIZE=4 MULTIPLE>");
		for (Concept c : concepts) buf.append("<OPTION VALUE=" + c.id + (ch.conceptIds.contains(c.id)?" SELECTED>":">") + c.title + "</OPTION>");
		buf.append("</SELECT>");
		return buf.toString();
	}

	String conceptsForm(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			// print the table of key concepts for General Chemistry, roughly ordered by topic/chapter/semester, etc.:
			String assignmentType = request.getParameter("AssignmentType");
			if (assignmentType==null) assignmentType = "";
			buf.append("<h2>Key Concepts in General Chemistry</h2>\n"
					+ "<form method=get action=/Edit>"
					+ "<input type=hidden name=UserRequest value=ManageConcepts />"
					+ "Optional:" + assignmentTypeDropDownBox(assignmentType,true) + "<p>"
					+ "</form>");
			buf.append("<TABLE BORDER=0 CELLSPACING=3>"
					+ "<TR><TH>Order</TH><TH>Title</TH><TH>Action</TH>" + (assignmentType.isEmpty()?"":"<TH>nQuestions</TH>") + "</TR>");
			List<Concept> concepts = ofy().load().type(Concept.class).order("orderBy").list();
			for (Concept c : concepts) { // one row for each concept
				Integer nQuestions = null;
				if (!assignmentType.isEmpty()) {
					nQuestions = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("conceptId",c.id).count();
				}
				buf.append("<FORM NAME=ConceptsForm" + c.id + " METHOD=POST ACTION=/Edit>"
						+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=UpdateConcept />"
						+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + c.id + "' />");
				buf.append("<TR>"
						+ "<TD ALIGN=CENTER><INPUT NAME=OrderBy SIZE=4 VALUE='" + c.orderBy + "' /></TD>"
						+ "<TD ALIGN=CENTER><INPUT NAME=Title VALUE='" + Question.quot2html(c.title) + "' /></TD>"
						+ "<TD ALIGN=CENTER><INPUT TYPE=SUBMIT VALUE=Update />"
						+ "<INPUT TYPE=SUBMIT VALUE='Delete' onClick=\"javascript: document.ConceptsForm" + c.id + ".UserRequest.value='DeleteConcept';\" />"
						+ "</TD></FORM>"
						+ (nQuestions==null?"":"<TD>"+nQuestions+"</TD>")
						+ "</TR>");
			}

			//			print one-row form to add a new Concept:
			buf.append("<FORM METHOD=POST ACTION=/Edit><INPUT TYPE=HIDDEN NAME=UserRequest VALUE=CreateConcept>");
			buf.append("<TR>"
					+ "<TD ALIGN=CENTER><INPUT NAME=OrderBy SIZE=4></TD>"
					+ "<TD ALIGN=CENTER><INPUT NAME=Title></TD>"
					+ "<TD ALIGN=CENTER><INPUT TYPE=SUBMIT VALUE='Create'></TD></TR></FORM>");
			buf.append("</TABLE>");
		} catch (Exception e) {
			buf.append(e.getMessage());
		}
		return buf.toString();
	}

	private void copyQuestionsToPracticeExam(User user,HttpServletRequest request) {
		if (!user.isChemVantageAdmin()) return;
		String assignmentType = request.getParameter("AssignmentType");
		if (assignmentType==null || !(assignmentType.equals("Quiz") || assignmentType.equals("Homework"))) return;
		long topicId = Long.parseLong(request.getParameter("TopicId"));
		List<Question> questions = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("topicId",topicId).list();
		for (Question q : questions) {
			q.id = null;
			if (q.assignmentType.equals("Quiz")) q.pointValue = 2;
			else q.pointValue = 10;
			q.assignmentType = "Exam";		
		}
		ofy().save().entities(questions);
	}

	void createChapter(HttpServletRequest request) throws Exception {
			Text text = ofy().load().type(Text.class).id(Long.parseLong(request.getParameter("TextId"))).safe();
			if (text.chapters==null) text.chapters = new ArrayList<Chapter>();
			Chapter ch = new Chapter();
			ch.chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
			ch.title = request.getParameter("ChapterTitle");
			ch.url =  request.getParameter("ChapterUrl");
			String[] conceptIds = request.getParameterValues("ConceptId");
			ch.conceptIds.clear();
			for (String cId : conceptIds) ch.conceptIds.add(Long.parseLong(cId));
			
			int chapterIndex = 0;
			for (Chapter c : text.chapters) {
				if (c.chapterNumber > ch.chapterNumber) break;
				else chapterIndex++;
			}
			text.chapters.add(chapterIndex,ch);
			ofy().save().entity(text).now();
	}

	void createConcept(User user,HttpServletRequest request) {
		String title = request.getParameter("Title");
		if (title==null) title = "";
		String orderBy = request.getParameter("OrderBy");
		if (orderBy==null) orderBy = "";
		Concept c = new Concept(title,orderBy);		
		
		ofy().save().entity(c).now();
	}

	private void createQuestion(User user,HttpServletRequest request) { //previously type long
		try {
			Question q = assembleQuestion(request);
			q.isActive = true;
			ofy().save().entity(q).now();
	} catch (Exception e) {}
	}

	void createText(User user,HttpServletRequest request) {
		Text text = new Text(request.getParameter("Title"),request.getParameter("Author"),request.getParameter("Publisher"),request.getParameter("URL"),request.getParameter("ImgURL"),request.getParameter("PrintCopyURL"));
		ofy().save().entity(text).now();
	}

	void createTopic(User user,HttpServletRequest request) {
		String title = request.getParameter("Title");
		if (title==null) title = "";
		String orderBy = request.getParameter("OrderBy");
		if (orderBy==null) orderBy = "";
		int topicGroup = 0;
		String[] alignments = request.getParameterValues("TopicGroup");
		if (alignments != null) {
			for (String text : alignments) topicGroup += Integer.parseInt(text);
		}
		
		Topic t = new Topic(title,orderBy,topicGroup);
		
		ofy().save().entity(t).now();
	}

	void createVideo(User user,HttpServletRequest request) {
		Video v = new Video(request.getParameter("SerialNumber"),request.getParameter("Title"));
		v.orderBy = (request.getParameter("OrderBy"));
		v.breaks = new int[0];
		v.questionKeys = new ArrayList<Key<Question>>();
		ofy().save().entity(v).now();
	}

	private void deleteAllQuestions(User user, HttpServletRequest request) throws Exception {
		if (!user.isChemVantageAdmin()) return;
		String assignmentType = request.getParameter("AssignmentType");
		if (assignmentType==null) return;
		long topicId = Long.parseLong(request.getParameter("TopicId"));
		List<Key<Question>> questionKeys = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("topicId",topicId).keys().list();
		if (questionKeys.size()>0) ofy().delete().keys(questionKeys);
	}

	void deleteChapter(HttpServletRequest request) {
		Text text = ofy().load().type(Text.class).id(Long.parseLong(request.getParameter("TextId"))).safe();
		int chapterIndex = Integer.parseInt(request.getParameter("ChapterIndex"));
		text.chapters.remove(chapterIndex);
		ofy().save().entity(text).now();
	}

	void deleteConcept(User user,HttpServletRequest request) {
		try {
			Concept c = ofy().load().type(Concept.class).id(Long.parseLong(request.getParameter("ConceptId"))).safe();
			ofy().delete().entity(c).now();
		} catch (Exception e) {}
	}

	private void deleteQuestion(User user,HttpServletRequest request) {
		long questionId = 0;
		try {
			questionId = Long.parseLong(request.getParameter("QuestionId"));
			Key<Question> k = key(Question.class,questionId);
			ofy().delete().key(k).now();
			questions.remove(key(Question.class,questionId));
		} catch (Exception e) {
			return;
		}
	}

	String deleteQuizlet(HttpServletRequest request) {
		Video v = null; 
		int segment = 0;
		
		try {
			v= ofy().load().type(Video.class).id(Long.parseLong(request.getParameter("VideoId"))).safe();
			segment = Integer.parseInt(request.getParameter("Segment"));
			if (segment==0 && v.breaks.length==1) { // deleting the only segment
				v.breaks = null;
				v.nQuestions = null;
				v.questionKeys = null;
			} else {  // copy breaks and nQuestions arrays except for current segment
				int[] breaks = new int[v.breaks.length-1];
				int[] nQuestions = new int[v.breaks.length-1];
				for (int i=0;i<breaks.length;i++) {
					if (i==segment) continue;
					breaks[i] = i<segment?v.breaks[i]:v.breaks[i+1];
					nQuestions[i] = i<segment?v.nQuestions[i]:v.nQuestions[i+1];
				}
				// delete the appropriate questionKeys
				int count = 0;
				for (int i=0;i<segment;i++) count += nQuestions[i];
				for (int j=count;j<count+v.nQuestions[segment];j++) v.questionKeys.remove(count);
				
				// replace the arrays with the modified ones
				v.breaks = breaks;
				v.nQuestions = nQuestions;
			}
			ofy().save().entity(v).now();
		} catch (Exception e) {			
		}
		return editVideoForm(v,segment==0?0:segment-1);
	}

	void deleteText(User user,HttpServletRequest request) {
		ofy().delete().key(key(Text.class,Long.parseLong(request.getParameter("TextId")))).now();
	}

	void deleteTopic(User user,HttpServletRequest request) {	
		try {
			Topic t = ofy().load().type(Topic.class).id(Long.parseLong(request.getParameter("TopicId"))).safe();
			ofy().delete().entity(t).now();
		} catch (Exception e) {}
	}

	void deleteVideo(User user,HttpServletRequest request) {
		ofy().delete().key(key(Video.class,Long.parseLong(request.getParameter("VideoId")))).now();
	}

	String editCurrentQuestion (User user,HttpServletRequest request) {
		Long questionId = null;
		Question q = null;
		Long assignmentId = null;
		try {
			questionId = Long.parseLong(request.getParameter("QuestionId"));
			q = ofy().load().type(Question.class).id(questionId).safe();
			assignmentId = Long.parseLong(request.getParameter("AssignmentId"));
		} catch (Exception e) {}
		return editCurrentQuestion(user,q,assignmentId);
	}

	String editCurrentQuestion (User user,Question q, Long assignmentId) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			long questionId = q.id;
			Concept c = (q.conceptId==null || q.conceptId==0L)?null:ofy().load().type(Concept.class).id(q.conceptId).now();
			long parameterSeed = ThreadLocalRandom.current().nextLong();
			if (q.requiresParser()) q.setParameters(parameterSeed);
			buf.append("<h2>Current Question</h2>");
			buf.append("Assignment Type: " + q.assignmentType + " (" + q.pointValue + (q.pointValue>1?" points":" point") + ")<br>");
			buf.append(c==null?"":"Concept: " + c.title + "<br/>");
			if (q.learn_more_url != null && !q.learn_more_url.isEmpty()) buf.append("Learn more at: " + q.learn_more_url + "</br>");
			buf.append("Author: " + q.authorId + "<br>");
			buf.append("Editor: " + q.editorId + "<br>");
			buf.append("Success Rate: " + q.getSuccess() + "<br/>");
			if (q.getQuestionType()>5) {
				buf.append("<br/>");
			} else if (q.checkedByAI==null) {
				buf.append("<div id='AIAnswerContainer'><a href='#' onClick=\"validateCorrectAnswerWithAI('" + questionId + "','" + parameterSeed + "')\">Validate with AI</a></div><br/>");
			} else {
				buf.append("<div id='AIAnswerContainer'>" + (q.checkedByAI?"&#x2705; Validated":"&#x26A0;&#xFE0F; Flagged by AI. <a href='#' onClick=\"validateCorrectAnswerWithAI('" + questionId + "','" + parameterSeed + "')\">Recheck</a>") + "</div><br/>");
			}
			
			buf.append("<script>\n"
					+ "async function validateCorrectAnswerWithAI(questionId,parameterSeed) {\n"
					+ "  const ai_answer_container = document.getElementById('AIAnswerContainer');\n"
					+ "  ai_answer_container.textContent = 'Validating...';\n"
					+ "  var result;\n"
					+ "  try {\n"
					+ "    const response = await fetch('/Edit', {\n"
					+ "      method: 'POST',\n"
					+ "      headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},\n"
					+ "      body: new URLSearchParams({UserRequest: 'ValidateCorrectAnswerWithAI', QuestionId: String(questionId), ParameterSeed: String(parameterSeed)})\n"
					+ "    });\n"
					+ "    result = await response.json();\n"
					+ "    console.log(result);\n"
					+ "    if (!response.ok) throw new Error(result.message || 'Validation failed.');\n"
					+ "    ai_answer_container.innerHTML = (result.isCorrect ? '&#x2705; Checked by AI.' : '&#x26A0;&#xFE0F; Flagged by AI.') + ' The best answer is: ' + result.best_answer + '<br/>';\n"
					+ "  } catch (err) {\n"
					+ "    const details = result ? (' ' + JSON.stringify(result)) : '';\n"
					+ "    ai_answer_container.textContent = 'Validation failed. ' + details;\n"
					+ "  }\n"
					+ "}\n"
					+ "</script>");
			
			buf.append("<FORM Action=/Edit METHOD=POST>");
			
			buf.append(q.printAll());
			
			if (q.authorId==null) q.authorId="";
			if (q.editorId==null) q.editorId="";
			buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "' />");
			buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + q.editorId + "' />");
			buf.append((assignmentId==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + assignmentId + "' />"));
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE=" + questionId + " />");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Question' />");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit' />");
			
			buf.append("<hr><h2>Edit This Question</h2>");
			
			buf.append("Assignment Type:" + assignmentTypeDropDownBox(q.assignmentType) + "<br/>");
			buf.append(("Custom".equals(q.assignmentType)?"":"Concept:" + conceptSelectBox(q.conceptId) + "<br/>"));
			buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
			buf.append(" Point Value: " + pointValueSelectBox(q.pointValue) + "<br/>");
			buf.append("<label><input type=checkbox name=CheckedByAI value=true /> Approve manually</label><br/><br/>");

			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview />");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}

	String editVideoForm(HttpServletRequest request) {
		Video v = null;
		try {
			v = ofy().load().type(Video.class).id(Long.parseLong(request.getParameter("VideoId"))).safe();
		} catch (Exception e) {
			return "Video was not found, sorry.";
		}
		int segment = 0;		
		try {
			segment = Integer.parseInt(request.getParameter("Segment"));
		}catch (Exception e) {
			segment = v.breaks==null?0:v.breaks.length;  // default to create a new breakpoint and segment
		}
		return editVideoForm(v,segment);
	}
	
	String editVideoForm(Video v, int segment) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		if (v.breaks == null) {
			v.breaks = new int[0];
			v.nQuestions = new int[0];
			v.questionKeys = new ArrayList<Key<Question>>();
		}
		
		try {
			buf.append("<h2>Embed quiz questions in a video</h2>");

			buf.append("Video: " + v.title + "<p>");
			
			buf.append("Use the form below to create or edit breakpoints in this video where 2-question quizlets will be "
					+ "presented to the viewer. Each segment must be edited separately, and breakpoints must be created in "
					+ "increasing order of seconds from the start of the video. You may (optionally) create a quiz at the "
					+ "end of the video by entering 'end' or by leaving the breakpoint time blank.<ol>"
					+ "<li>Select a topic appropriate to the subject of the video (for selecting the questions)</li>"
					+ "<li>Select one of the existing video segments or create a new one</li>"
					+ "<li>If you selected an existing segment, wait for the page to reload</li>"
					+ "<li>Enter the breakpoint (in seconds from the start of the video) or 'end'</li>"
					+ "<li>Select several questions from below to be drawn at random for the quizlet</li>"
					+ "<li>Click the 'Update Video' button to submit the breakpoint value and question items</li>"
					+ "</ol>");

			buf.append("<form method=post action=/Edit>");  // This starts the master form for editing a single break point for hte video
			buf.append("<input type=hidden name=VideoId value=" + v.id + ">");
			buf.append("<input type=hidden name=Segment value=" + segment + ">");

			buf.append("Topic: " + topicSelectBox(v.topicId,true) + "<p>");				

			if (v.topicId == 0L) {
				buf.append("<input type=hidden name=UserRequest value='Update Quizlet'>");
			} else {
				// Print a table of existing segments/breakpoints, and add one row at the end to create a new one unless the end has already been specified
				// The number of table rows should be v.breaks.length if the last break is at the end of the video
				// Otherwise, it should be v.breaks.length +1 to allow for creating a new segment (default)
				int nRows = v.breaks.length;
				boolean addNew = (segment==v.breaks.length && (segment==0 || v.breaks[v.breaks.length-1]>0));
				if (addNew) nRows++;

				buf.append("<table><tr><th>Select</th><th>Segment</th><th>Start</th><th>End</th><th>#Questions</th><th>Action</th></tr>");

				for (int i=0;i<nRows;i++) {
					buf.append("<tr style='text-align:center'>"
							+ "<td>" + (i==segment?"edit&rarr;":"<a href=/Edit?UserRequest=EditVideo&VideoId=" + v.id + "&Segment=" + i + ">select</a>") + "</td>"
							+ "<td>" + (i==v.breaks.length && i==segment?"new":i) + "</td>"
							+ "<td>" + (i==0?0:v.breaks[i-1]) + "</td>"
							+ "<td>" + (i==segment?("<input type=text name=Seconds size=5 value=" + (segment==v.breaks.length?"":(v.breaks[i]<0?"end":v.breaks[i])) + ">"):(v.breaks[i]<0?"end":v.breaks[i])) + "</td>"
							+ "<td>" + (v.nQuestions.length>i?v.nQuestions[i]:0) + "</td>"
							+ "<td>" + (i==segment?"<input type=submit name=UserRequest value=" + (addNew?"'Create Quizlet'> ":"'Update Quizlet'> ") + (i==v.breaks.length-1?"<input type=submit name=UserRequest value='Delete Quizlet'> ":"") + "<a href=/Edit?UserRequest=ManageVideos>Done</a>":"") + "</td>"
							+ "</tr>");
				}
				buf.append("</table><p>");

				if (segment==v.breaks.length) buf.append("Select several questions below to be selected at random for the new quizlet:<p>");
				else buf.append("Select several questions below to be selected at random for the quizlet at " + (v.breaks[segment]<0?"the end of the video:":"t = " + v.breaks[segment] + " seconds:") + "<p>");

				buf.append("You may create/edit questions <a href=Edit?TopicId=" + v.topicId + "&AssignmentType=Video>here</a>.<p>");

				// Make a List of all of the available video questions
				List<Question> questions = ofy().load().type(Question.class).filter("assignmentType","Video").filter("topicId",v.topicId).list();

				// Now make a list of all the current questions for this quizlet, if any:
				List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();		
				int nPriorQuestions = 0;
				if (v.nQuestions.length>segment) {
					for (int j=0;j<segment;j++) nPriorQuestions += v.nQuestions[j];  // add up the number of questions in preceding quizlets
					for (int j=nPriorQuestions;j<nPriorQuestions+v.nQuestions[segment];j++) questionKeys.add(v.questionKeys.get(j));
				}

				// Make a table of available questions, marking the current questions as already selected
				buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
				int k=0;
				for (Question q : questions) {
					k++;
					q.setParameters();
					buf.append("\n<TR><TD VALIGN=TOP NOWRAP>"
							+ "<INPUT TYPE=CHECKBOX NAME=QuestionId VALUE=" + q.id + (questionKeys.contains(key(Question.class,q.id))?" CHECKED>":">") );
					buf.append("<b>&nbsp;" + k + ".</b></TD>");
					buf.append("\n<TD>" + q.printAll() + "</TD>");
					buf.append("</TR>");
				}
				buf.append("</TABLE>");
			}
			buf.append("</form>");
		} catch (Exception e) {
			return buf.toString() + e.getMessage();
		}
		return buf.toString();
	}

	/*
	static String importQuestions(HttpServletRequest request) throws Exception {
		StringBuffer buf = new StringBuffer("<h1>Import Questions From Sage</h1>");
		
		List<Concept> concepts = ofy().load().type(Concept.class).list();
		
		URL u = null;
		HttpURLConnection uc = null;
		BufferedReader reader = null;
		Gson gson = new Gson();
		List<Question> questions = new ArrayList<Question>();
		for (Concept c : concepts) {
			try {
				String url = "https://sage.chemvantage.org/sage?UserRequest=GetQuestions&Concept=" + URLEncoder.encode(c.title, "utf-8");
				buf.append(url);
				u = new URL(url);
				uc = (HttpURLConnection) u.openConnection();
				uc.setDoInput(true);
				uc.setRequestMethod("GET");
				uc.setRequestProperty("Accept", "application/json");
				uc.setRequestProperty("charset", "utf-8");
				uc.setUseCaches(false);
				uc.setReadTimeout(15000);  // waits up to 15 s for server to respond

				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));				
				JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
				reader.close();
				
				if (array==null || array.isEmpty()) continue;
				
				questions.clear();
				Iterator<JsonElement> iterator = array.iterator();
				while(iterator.hasNext()) {
					JsonObject question = iterator.next().getAsJsonObject();
					Question q = gson.fromJson(question, Question.class);
					q.id=null;
					q.conceptId = c.id;
					q.assignmentType = "Sage";
					questions.add(q);
				}				
				if (questions.size() > 0) ofy().save().entities(questions);
				buf.append(" - saved "+ questions.size() + " questions.<br/>");
			} catch (Exception e) {
				buf.append(" - " + e.getMessage()==null?e.toString():e.getMessage() + "<br/>");
			}
		} 
		return buf.toString();
	}
*/
	String editorsPage(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		Long assignmentId = null;
		List<Key<Question>> questionKeys = null;
		try {
			assignmentId = Long.parseLong(request.getParameter("AssignmentId"));
			questionKeys = loadQuestions(assignmentId);
			buf.append("<h2>Questions for Assignment " + assignmentId + "</h2>");
		} catch (Exception e) {}

		Long conceptId = null;
		String assignmentType = request.getParameter("AssignmentType");
		try {
			if (assignmentId != null) throw new Exception();  // if AssignmentId is present, ignore ConceptId parameter
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
			questionKeys = loadQuestions(assignmentType,conceptId);
		} catch (Exception e) {}
						
		if (assignmentId == null) {
			if (assignmentType == null) {
				int nPending = ofy().load().type(ProposedQuestion.class).count();
				buf.append("<a href=Edit?UserRequest=Review>"
						+ nPending + " items are currently pending editorial review.</a><br/>");
				buf.append("<a href=/Edit?UserRequest=ManageConcepts>Manage Concepts</a><br/>");
				buf.append("<a href=/Edit?UserRequest=ManageVideos>Manage Videos</a><br/>");
				buf.append("<a href=/Edit?UserRequest=ManageTexts>Manage Texts</a><br/>");
				buf.append("<a href=/Edit?UserRequest=ManageOrphanQuestions>Manage Orphan Questions</a><br/>");
				buf.append("<form><label>Assignment ID: <input type=text name=AssignmentId /></label> <input type=submit value=Select></form>");
			}

			// display a table to select questions by AssignmentType and Text/Chapter/Concept or directly by Concept:
			buf.append("<div style=display:table><div style=display:table-row>");
			buf.append("<form method=get action=/Edit>");
			
			Text text = null;
			Chapter chapter = null;
			Concept concept = null;
			
			// the first cell contains a set of radio buttons to select the AssignmentTypoe
			buf.append("<div style='display:table-cell;padding-right:25px;'><h4>Select an Assignment Type</h4>");
			buf.append("<label><input type=radio name=AssignmentType value=Quiz " + ("Quiz".equals(assignmentType)?"checked":"onClick=this.form.submit()") + " />Quiz</label><br/>");
			buf.append("<label><input type=radio name=AssignmentType value=Homework " + ("Homework".equals(assignmentType)?"checked":"onClick=this.form.submit()") + " />Homework</label><br/>");
			buf.append("<label><input type=radio name=AssignmentType value=Sage " + ("Sage".equals(assignmentType)?"checked":"onClick=this.form.submit()") + " />Sage</label><br/>");
			buf.append("<label><input type=radio name=AssignmentType value=Exam " + ("Exam".equals(assignmentType)?"checked":"onClick=this.form.submit()") + " />Exam</label><br/>");
			buf.append("</div>");  // end cell1
			
			if (assignmentType != null) {  // ask for text or concept
				// the second cell contains selectors for text and chapter (optional)
				Long textId = null;
				try {
					textId = Long.parseLong(request.getParameter("TextId"));
				} catch (Exception e) {}
				Text allTopics = null;
				List<Text> texts = ofy().load().type(Text.class).list();
				buf.append("<div style='display:table-cell;padding-right:25px;'><h4>Select a Text/Chapter</h4>");
				buf.append("<select id=tsel name=TextId onChange=this.form.submit();>"
						+ "<option>Select a text</option>");
				for (Text t : texts) {
					if (t.chapters.isEmpty()) continue;
					if (t.id.equals(textId)) text = t;
					if (t.title.equals("View All Topics")) {
						allTopics = t;
						continue;
					}
					buf.append("<option value=" + t.id + (t.id.equals(textId)?" selected>":">") + t.title + "</option>");
				}
				if (allTopics != null) buf.append("<option value=" + allTopics.id + (allTopics.id.equals(textId)?" selected>":">") + allTopics.title + "</option>");
				buf.append("</select>"
						+ "<button type=button onclick=document.getElementById('tsel').value='null';this.form.submit();>Reset</button>"
						+ "<br/><br/>");
				// next, present a selector for a chapter if the text is known
				Integer chapterNumber = null;
				if (text != null) {
					try {
						chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
					} catch (Exception e) {}
					buf.append("<select name=ChapterNumber onChange=this.form.submit();>"
							+ "<option>Select a chapter</option>");
					for (Chapter ch : text.chapters) {
						if (chapterNumber!=null && chapterNumber.equals(ch.chapterNumber)) chapter = ch;
						buf.append("<option value=" + ch.chapterNumber + (ch.equals(chapter)?" selected>":">") + ch.title + "</option>");
					}
					buf.append("</select>");
				}
				buf.append("</div>");  // end cell2

				// the third cell contains a selector for a Concept
				buf.append("<div style='display:table-cell;'><h4>Select a Key Concept</h4>");
				List<Key<Concept>> conceptKeys = ofy().load().type(Concept.class).order("orderBy").keys().list();
				Map<Key<Concept>,Concept> concepts = ofy().load().keys(conceptKeys);
				if (conceptId!=null) concept = concepts.get(key(Concept.class,conceptId));
				
				if (chapter!=null) {  // present a radio-style selector for chapter concepts
					for (Long cId : chapter.conceptIds) {
						int nQuestions = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("conceptId",cId).count();
						buf.append("<label><input type=radio name=ConceptId value=" + cId 
							+ (cId.equals(conceptId)?" checked />":" onClick=this.form.submit(); />") 
							+ concepts.get(key(Concept.class,cId)).title + " (" + nQuestions + ")</label><br/>");
					}
				} else if (text==null) {  // otherwise show a drop-down selector for all concepts
					buf.append("<select id=csel name=ConceptId onchange=document.getElementById('tsel').value='null';this.form.submit()><option>Select a concept</option>");
					for (Key<Concept> k : conceptKeys) {
						Concept c = concepts.get(k);
						buf.append("<option value=" + c.id + (c.id.equals(conceptId)?" selected>":">") + c.title + "</option>");
					}
					buf.append("</select>"
							+ "<button type=button onclick=document.getElementById('csel').value='null';this.form.submit();>Reset</button>");
				}
				buf.append("</div>");  // end cell3
			}
			buf.append("</form>");
			buf.append("</div></div><br/>"); // end row,table

			if (concept != null) {
				buf.append("<h3>Questions for Concept: " + concept.title + "</h3>");
				buf.append("<FORM NAME=NewQuestion METHOD=GET ACTION=/Edit>New: "
						+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=NewQuestionForm />"
						+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + concept.id + "'>"
						+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>"
						+ "<INPUT TYPE=HIDDEN NAME=QuestionType>"
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=1;submit()\" VALUE='Multiple Choice'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=2;submit()\" VALUE='True/False'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=3;submit()\" VALUE='Select Multiple'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=4;submit()\" VALUE='Fill in Word'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=5;submit()\" VALUE='Numeric'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=6;submit()\" VALUE='Five Star'> "
						+ "<INPUT TYPE=BUTTON onCLick=\"document.NewQuestion.QuestionType.value=7;submit()\" VALUE='Essay'> "
						+ "</FORM><br/>");
			}
		}
		if (questionKeys == null || questions.size() == 0) return buf.toString();
	
		buf.append("<TABLE BORDER=0 CELLSPACING=3 CELLPADDING=0>");
		int i=0;
		int pts = 0;
		for (Key<Question> k : questionKeys) {
			Question q = questions.get(k);
			q.setParameters();

			if ("Exam".equals(assignmentType) && q.pointValue != pts) { // print a header for new section of questions
				pts = q.pointValue;
				i=0;
				buf.append("<tr><td bgcolor=cyan>" + q.pointValue + "&nbsp;pt.&nbsp;questions:</td><td colspan=2>&nbsp;<p>&nbsp;</td></tr>");
			}
			i++;
			buf.append("\n<FORM METHOD=GET ACTION=/Edit>"
					+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + conceptId + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "' />"
					+ (assignmentId==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + assignmentId + "' />")
					+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "' />"
					+ "<TR id=q" + q.id + " VALIGN=TOP>"
					+ "<TD><INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Edit /><br/>"
					+ "<FONT SIZE=-2>" + q.getSuccess() + "</FONT><br/>"
					+ (q.checkedByAI==null?"":(q.checkedByAI?"&#x2705;":"&#x26A0;&#xFE0F;<span style='color:red'>Needs attention</span>"))
					+ "</TD>");
			buf.append("</FORM>");
			buf.append("<FORM METHOD=POST ACTION=/Edit>"
					+ "<INPUT TYPE=HIDDEN NAME=ConceptId VALUE='" + conceptId + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "' />"
					+ "<INPUT TYPE=HIDDEN NAME=QuestionId VALUE='" + q.id + "' />"
					+ "<TD style='padding-right:5px;'><INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Hide' />"
					+ "</TD>"
					+ "<TD ALIGN=RIGHT  style='padding-right:5px;'> " + i + ". </TD>");
			buf.append("<TD>" + q.printAll() + "</TD>");
			buf.append("</TR></FORM>");
		}
		buf.append("</TABLE><br/>");			
			
		return buf.toString();
	}
/* 
	private boolean extractCorrectAnswerValue(String aiResponseText) {
		if (aiResponseText == null) return false;
		String text = aiResponseText.trim();
		if (text.isEmpty()) return false;

		if (text.startsWith("```")) {
			int firstNewline = text.indexOf('\n');
			if (firstNewline > 0) text = text.substring(firstNewline + 1).trim();
			if (text.endsWith("```")) text = text.substring(0, text.length() - 3).trim();
		}

		try {
			JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
			if (obj.has("isCorrect") && !obj.get("isCorrect").isJsonNull()) return obj.get("isCorrect").getAsBoolean();
			if (obj.has("correct_answer") && !obj.get("correct_answer").isJsonNull()) return obj.get("correct_answer").getAsBoolean();
			return false;
		} catch (Exception e) {
			return false;
		}

		try {
			JsonElement element = JsonParser.parseString(text);
			if (element.isJsonPrimitive()) return element.getAsString().trim();
			if (element.isJsonObject()) {
				JsonObject obj = element.getAsJsonObject();
				String[] keys = {"correct_answer", "correctAnswer", "answer", "value"};
				for (String key : keys) {
					if (obj.has(key) && !obj.get(key).isJsonNull()) {
						JsonElement value = obj.get(key);
						if (value.isJsonPrimitive()) return value.getAsString().trim();
						if (value.isJsonArray() && value.getAsJsonArray().size() > 0 && value.getAsJsonArray().get(0).isJsonPrimitive()) {
							return value.getAsJsonArray().get(0).getAsString().trim();
						}
						return value.toString().trim();
					}
				}
			}
		} catch (Exception e) {
		}

		String lower = text.toLowerCase();
		int idx = lower.indexOf("correct answer");
		if (idx >= 0) {
			int colon = text.indexOf(':', idx);
			if (colon >= 0 && colon + 1 < text.length()) return text.substring(colon + 1).trim();
		}
		return text;
*/



	private void hideDuplicateQuestion(User user,HttpServletRequest request) {  // moves question to Concept called duplicates (hidden from instructors but accessible to assignments
		try {
			// find the Concept that holds duplicate questions in escrow
			Long conceptId = null;
			List<Concept> concepts = ofy().load().type(Concept.class).filter("orderBy <"," 1").list();
			for (Concept c : concepts) {
				if (c.title.equals("Hide")) {
					conceptId = c.id;
					break;
				}
			}
			if (conceptId!=null) {
				Long questionId = Long.parseLong(request.getParameter("QuestionId"));
				Question q = ofy().load().type(Question.class).id(questionId).now();
				q.conceptId = conceptId;
				ofy().save().entity(q).now();
			}
		} catch (Exception e) {}
	}
	
	List<Key<Question>> loadQuestions(Long assignmentId) throws Exception {
		Assignment a = ofy().load().type(Assignment.class).id(assignmentId).now();
		questions.clear();
		if (a.questionKeys.size()>0) {
			questions = ofy().load().keys(a.questionKeys);
		}
		return a.questionKeys;
	}

	List<Key<Question>> loadQuestions(String assignmentType,Long conceptId) throws Exception {
		List<Key<Question>> keys = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("conceptId",conceptId).keys().list();
		questions.clear();
		if (keys.size()>0) {
			questions = ofy().load().keys(keys);
			Collections.sort(keys, new SortByQuestionText());
		}
		return keys;
	}

	String manageOrphanQuestions(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		buf.append("<h2>Question Without A ConceptId</h2>");
		// find an orphan Question where conceptId==null or conceptId==0 
		String text = request.getParameter("text");  // starting point for search
		if (text==null) text = "";
		
		String assignmentType = request.getParameter("AssignmentType");
		if (assignmentType==null) {
			buf.append("<form method=get action=/Edit>"
					+ "<input type=hidden name=UserRequest value=ManageOrphanQuestions />");
			buf.append(assignmentTypeDropDownBox(null,true));
			buf.append("</form>");
			return buf.toString();
		}
		List<Question> questions = null;
		Question orphan = null;
		while (orphan==null) {
			questions = ofy().load().type(Question.class).filter("assignmentType",assignmentType).filter("text >=",text).limit(200).list();  // next 10 questions
			if (questions.size()<3) return "Done with " + assignmentType + " questions.";
			for (int i=1;i<questions.size()-1;i++) {
				Question q = questions.get(i);
				if (q.conceptId==null) {
					orphan = q;
					break;
				} else text = q.text;
			}
		}

		orphan.setParameters();
		buf.append("<table><tr><td valign=top>");
		buf.append("Assignment Type: " + orphan.assignmentType + "<br/>");
		Topic topic = null;
		try {
			topic = ofy().load().type(Topic.class).id(orphan.topicId).safe();
			buf.append("Topic: " + topic.title + "<br/>");
		} catch (Exception e) {
			buf.append("Topic: unknown<br/>");
		}
		buf.append("Question ID: " + orphan.id + "<br/>"
				+ "<a href=/Edit?UserRequest=Edit&QuestionId=" + orphan.id + ">Edit this questipn</a><br/>");
		buf.append("</td><td width=20px;></td><td>");
		buf.append("<form id=conceptForm method=post action=/Edit>"
				+ "<input type=hidden name=AssignmentType value='" + orphan.assignmentType + "' />"
				+ "<input type=hidden name=QuestionId value=" + orphan.id + " />"
				+ "<input type=hidden name=text value='" + orphan.text + "' />"
				+ "<input type=hidden name=UserRequest value='AssignToConcept' />");
		
		if (this.concepts.isEmpty()) concepts = ofy().load().type(Concept.class).order("orderBy").list();
		if (this.conceptMap.isEmpty()) for (Concept c : concepts) conceptMap.put(c.id, c);
		Concept hide = null;
		for (Concept c : concepts) if ("Hide".equals(c.title)) hide = c;
		
		if (topic != null) for (Long cId : topic.conceptIds) {
			Concept c = conceptMap.get(cId);
			if (c==null) continue;
			buf.append("<label><input type=radio name=ConceptId value=" + cId + " onclick=document.getElementById('conceptForm').submit(); />" + conceptMap.get(cId).title + "</label><br/>");
		}
	if (hide != null) {
		buf.append("<label><input type=radio name=ConceptId value=" + hide.id + " onclick=document.getElementById('conceptForm').submit(); />Hide</label><br/>");
	}
	
	buf.append("<input type=submit value='Assign' />"
		+ "</form>");
		
		StringBuffer comp = new StringBuffer();
		Question prev = (questions != null) ? questions.get(questions.indexOf(orphan)-1) : null;
		Question next = (questions != null) ? questions.get(questions.indexOf(orphan)+1) : null;
		
		comp.append("<div style='display:table'><div style='display:table-cell;width:400px'>");
		comp.append("<h3>Previous Question</h3>");
	if (prev != null) {
		prev.setParameters();
		comp.append("Assignment Type: " + prev.assignmentType + "<br/>");
		try {
			Topic t = ofy().load().type(Topic.class).id(prev.topicId).safe();
			comp.append("Topic: " + t.title + "<br/>");
		} catch (Exception e) {
			comp.append("Topic: unknown<br/>");
		}
		try {
			Concept c = ofy().load().type(Concept.class).id(prev.conceptId).safe();
			comp.append("Concept: " + c.title + "<br/>");
		} catch (Exception e) {}
		comp.append("Question ID: " + prev.id + "<br/>");
		comp.append(prev.printAll());
	} else {
		comp.append("No previous question available.<br/>");
	}
		
	comp.append("<h3>Next Question</h3>");
	if (next != null) {
		next.setParameters();
		comp.append("Assignment Type: " + next.assignmentType + "<br/>");
		try {
			Topic t = ofy().load().type(Topic.class).id(next.topicId).safe();
			comp.append("Topic: " + t.title + "<br/>");
		} catch (Exception e) {
			comp.append("Topic: unknown<br/>");
		}
		try {
			Concept c = ofy().load().type(Concept.class).id(next.conceptId).safe();
			comp.append("Concept: " + c.title + "<br/>");
		} catch (Exception e) {}
		comp.append("Question ID: " + next.id + "<br/>");
		comp.append(next.printAll());
	} else {
		comp.append("No next question available.<br/>");
	}
	comp.append("</div></div>");
		
		buf.append(comp);

		return buf.toString();
}
	
	String newQuestionForm(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		buf.append("<h2>New Question</h2>");
		Long conceptId = null;
		try {
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
		} catch (Exception e) {}		
		String assignmentType = request.getParameter("AssignmentType");
	
		int questionType = 0;
		try {
			questionType = Integer.parseInt(request.getParameter("QuestionType"));
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
			buf.append("<p><FORM METHOD=POST ACTION=Edit>"
					+ "<INPUT TYPE=HIDDEN NAME=AssignmentType VALUE='" + assignmentType + "'>"
					+ "<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + user.getId() + "'>");
			buf.append("<INPUT TYPE=HIDDEN NAME=QuestionType VALUE=" + questionType + ">");
			
			buf.append("Concept: " + conceptSelectBox(conceptId) + "<br>");
			
			buf.append("Point Value: " + pointValueSelectBox(assignmentType) + "<br>");
			buf.append(question.edit());
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Preview'></FORM>");
		} catch (Exception e) {
			return buf.toString() + "<br>" + e.getMessage();
		}
		return buf.toString();
	}

	String pointValueSelectBox() {
		return pointValueSelectBox(0);
	}

	String pointValueSelectBox(String assignmentType) {
		switch (assignmentType) {
		  case "Quiz": return pointValueSelectBox(1);
		  case "Homework": return pointValueSelectBox(1);
		  case "Exam": return pointValueSelectBox(2); 
		  case "Video": return pointValueSelectBox(1);
		  default: return null;
		}		
	}

	String pointValueSelectBox(int points) {
		return "<SELECT NAME=PointValue>"
		+ "<OPTION" + (points==1?" SELECTED":"") + ">1</OPTION>"
		+ "<OPTION" + (points==2?" SELECTED":"") + ">2</OPTION>"
		+ "<OPTION" + (points==4?" SELECTED":"") + ">4</OPTION>"
		+ "<OPTION" + (points==10?" SELECTED":"") + ">10</OPTION>"
		+ "<OPTION" + (points==15?" SELECTED":"") + ">15</OPTION>"
		+ "</SELECT>";
	}

	String previewQuestion(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Administration</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			long questionId = 0;
			boolean current = false;
			boolean proposed = false;
			try {
				questionId = Long.parseLong(request.getParameter("QuestionId"));
				current = true;
			} catch (Exception e2) {}
			
			Long assignmentId = null;
			try {
				assignmentId = Long.parseLong(request.getParameter("AssignmentId"));
			} catch (Exception e) {}
			
			long proposedQuestionId = 0;
			try {
				proposedQuestionId = Long.parseLong(request.getParameter("ProposedQuestionId"));
				proposed = true;
				current = false;
			} catch (Exception e2) {}
			
			Long conceptId = null;
			try {
				conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {}
			
			Question q = assembleQuestion(request);
			if (q.requiresParser()) q.setParameters();
			
			buf.append("<h2>Preview Question</h2>");
			
			q.assignmentType = request.getParameter("AssignmentType");
			if (q.assignmentType==null || q.assignmentType.isEmpty()) q.assignmentType = "Quiz";
			buf.append("Assignment Type: " + q.assignmentType);
			if (q.assignmentType.equals("Exam")) { // validate point value
				if (q.pointValue < 2) q.pointValue = 2;
				buf.append(" (" + q.pointValue + " points)<br/>");
			} else {
				q.pointValue = 1;
				buf.append(" (1 point)<br>");
			}
			Concept c = conceptId==null?null:ofy().load().type(Concept.class).id(conceptId).now();
			buf.append(c==null?"":"Concept: " + c.title + "<br/>");
			buf.append("Author: " + q.authorId + "<br/>");
			buf.append("Editor: " + user.getId() + "<br/>");
			buf.append((q.checkedByAI==null?"":(q.checkedByAI?"&#x2705; Validated<br/>":"&#x26A0;&#xFE0F; Flagged by AI<br/>")) + "<br/>");

			buf.append("<FORM ACTION=/Edit#q" + questionId + " METHOD=POST>");
			
			buf.append(q.printAll());
			
			if (q.authorId==null) q.authorId="";
			buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "'>");
			buf.append("<INPUT TYPE=HIDDEN NAME=EditorId VALUE='" + user.getId() + "'>");
			buf.append((assignmentId==null?"":"<INPUT TYPE=HIDDEN NAME=AssignmentId VALUE='" + assignmentId + "' />"));
			
			if (current) {
				buf.append("<INPUT TYPE=HIDDEN NAME=QuestionId VALUE=" + questionId + ">");
				buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Update Question'>");
			}
			if (proposed) {
				buf.append("<INPUT TYPE=HIDDEN NAME=ProposedQuestionId VALUE=" + proposedQuestionId + ">");
				buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Activate This Question'>");
			} else buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Save New Question'>");
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit'>");
			
			buf.append("<hr><h2>Continue Editing</h2>");
			buf.append("Assignment Type:" + assignmentTypeDropDownBox(q.assignmentType) + "<br>");
			buf.append("Custom".equals(q.assignmentType)?"":"Concept:" + conceptSelectBox(conceptId) + "<br>");
			buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
			
			if (q.assignmentType.equals("Exam")) {
				if (q.pointValue!=2 && q.pointValue!=4 && q.pointValue!=10 && q.pointValue!=15) q.pointValue = 2;
			} else q.pointValue = 1;
			buf.append(" Point Value: " + pointValueSelectBox(q.pointValue) + "<br>");
			
			buf.append("<label><input type=checkbox name=CheckedByAI value=true " + (Boolean.TRUE.equals(q.checkedByAI)?" checked":"") + " /> Approve manually</label><br/><br/>");

			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview>");
			buf.append("</FORM>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
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

	private JsonObject requestValidationFromGemini(Question q) throws Exception {
		String questionItem = q.printForSage();
		if (questionItem == null || questionItem.isEmpty()) throw new Exception("Question text is empty");

		Schema responseSchema = Schema.newBuilder()
				.setType(Type.OBJECT)
				.putProperties("best_answer", Schema.newBuilder().setType(Type.STRING).build())
				.putProperties("isCorrect", Schema.newBuilder().setType(Type.BOOLEAN).build())
				.addRequired("best_answer")
				.addRequired("isCorrect")
				.build();

		GenerationConfig generationConfig = GenerationConfig.newBuilder()
				.setResponseMimeType("application/json")
				.setResponseSchema(responseSchema)
				.build();

		String prompt = "You are validating a chemistry question and a proposed answer. "
				+ "Determine the single best answer to the question_item, then evaluate whether proposed_answer matches that best answer. "
				+ "Return ONLY a valid JSON object (no markdown, no code fences, no extra text) with this exact schema:\n"
				+ "{\n"
				+ "  \"best_answer\": \"string\",\n"
				+ "  \"isCorrect\": true|false\n"
				+ "}\n\n"
				+ "Rules:\n"
				+ "- best_answer must be concise and unambiguous.\n"
				+ "- isCorrect is true only if proposed_answer is equivalent to best_answer in chemistry meaning.\n"
				+ "- If units or significant figures are required, enforce them.\n"
				+ "- Do not include units in the best_answer.\n"
				+ "question_item:\n" + questionItem + "\n\n"
				+ "proposed_answer:\n" + q.getCorrectAnswer();

		try (VertexAI vertexAI = new VertexAI(Subject.getProjectId(), VERTEX_AI_LOCATION)) {
			GenerativeModel model = new GenerativeModel.Builder()
					.setModelName(getVertexAiModel())
					.setVertexAi(vertexAI)
					.setGenerationConfig(generationConfig)
					.build();
			GenerateContentResponse response = model.generateContent(prompt);
			String text = ResponseHandler.getText(response);
			if (text == null || text.trim().isEmpty()) throw new Exception("Vertex AI response did not contain answer text.");
			try {
				return JsonParser.parseString(text.trim()).getAsJsonObject();
			} catch (Exception parseException) {
				throw new Exception("Vertex AI response was not valid JSON object: " + text.trim());
			}
		} catch (Exception e) {
			throw new Exception("Vertex AI API error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}

	String reviewProposedQuestion (User user, HttpServletRequest request) {
		// identifies a ProposedQuestion item, either from a specific questionId or next in the list
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editor</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		buf.append("<h2>Proposed Question</h2>");
		try {
			List<Key<ProposedQuestion>> pendingQuestionKeys = ofy().load().type(ProposedQuestion.class).keys().list();
			if (pendingQuestionKeys.size()==0) return editorsPage(user,request);  // done!
			
			String questionId = request.getParameter("NextQuestionId");
			ProposedQuestion q = null;
			if (questionId==null) { // get the first question in the list
				q = ofy().load().key(pendingQuestionKeys.get(0)).safe();
				questionId = String.valueOf(q.id);
			} else { // get the designated question in the list
				q = ofy().load().type(ProposedQuestion.class).id(Long.parseLong(questionId)).now();
			}
			
			if (q.assignmentType == null) { // assign a default type
				q.assignmentType = q.getQuestionType()<5?"Quiz":"Homework";
			}
			
			switch (q.getQuestionType()) {
			case 1:
			case 3:
				q.scrambleChoices = true;
				q.nChoices = q.choices.size();
				break;
			case 5:
				if (q.requiresParser()) q.setParameters();
				break;	
			}
			
			// If the list contains more than one proposed question, get the index of the next one
			String nextQuestionId = null;
			if (pendingQuestionKeys.size()>1) {
				Key<ProposedQuestion> k = key(ProposedQuestion.class,q.id);
				int i = (pendingQuestionKeys.indexOf(k) + 1)%pendingQuestionKeys.size();				
				nextQuestionId = String.valueOf(pendingQuestionKeys.get(i).getId());
			}
			
			Concept c = null;
			if (q.conceptId != null) c = ofy().load().type(Concept.class).id(q.conceptId).now();
			if (q.authorId == null) q.authorId = "ChatGPT";
			
			buf.append("Concept: " + (c==null?"null":c.title) + "<br>");
			buf.append("Assignment Type: " + q.assignmentType + " (" + q.pointValue + (q.pointValue>1?" points":" point") + ")<br>");
			buf.append("Author: " + q.authorId + "<p>");
			buf.append("<FORM id=review Action=Edit METHOD=GET>");
			
			buf.append(q.printAll());
			
			buf.append("<INPUT TYPE=HIDDEN NAME=ProposedQuestionId VALUE='" + questionId + "'>");
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Discard Question'> "
					+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Activate This Question' onclick=document.getElementById('review').method='post'; /> ");
			
			if (nextQuestionId != null) {
				buf.append("<INPUT TYPE=HIDDEN NAME=NextQuestionId VALUE='" + nextQuestionId + "'>");
				buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Skip> ");
			}
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Quit'>");
			buf.append("<INPUT TYPE=HIDDEN NAME=AuthorId VALUE='" + q.authorId + "'>");
			
			buf.append("<hr><h2>Edit This Question</h2>");
			buf.append("Assignment Type:" + assignmentTypeDropDownBox(q.assignmentType) + "<br>");
			buf.append("Concept:" + conceptSelectBox(c==null?null:c.id) + "<br>");
			
			buf.append("Question Type:" + questionTypeDropDownBox(q.getQuestionType()));
			buf.append(" Point Value: " + pointValueSelectBox(q.pointValue) + "<br>");
			
			buf.append(q.edit());
			
			buf.append("<INPUT TYPE=SUBMIT NAME=UserRequest VALUE=Preview />");
			buf.append("</FORM>");
	
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}

	String textsForm(User user,HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		try {
			long textId = Long.parseLong(request.getParameter("TextId"));
			Text t = ofy().load().type(Text.class).id(textId).safe();
			buf.append("<h2>Manage Chapters</h2>");
			buf.append("<a href=/Edit?UserRequest=ManageTexts>Return to Manage Texts</a><br/><br/>");
			buf.append("Title: " + t.title + "<br/>"
				+ "Author: " + t.author + "<br/>"
				+ "Publisher: " + t.publisher + "<br/>"
				+ "URL: " + t.URL + "<br/><br/>");
			
			if (t.chapters==null) t.chapters = new ArrayList<Chapter>();
			
			buf.append("<table>"
				+ "<tr><th>Chapter</th><th>Title</th><th>URL</th><th>Key Concepts</th><th>Action</th></tr>");
			for (Chapter c : t.chapters) {
				buf.append("<tr><form action=/Edit method=post>"
					+ "<input type=hidden name=TextId value=" + textId + " />"
					+ "<input type=hidden name=ChapterIndex value=" + t.chapters.indexOf(c) + " />"
					+ "<td><input type=text size=4 name=ChapterNumber value='" + c.chapterNumber + "' /></td>"
					+ "<td><input type=text size=20 name=ChapterTitle value='" + c.title + "' /></td>"
					+ "<td><input type=text size=20 name=ChapterUrl value='" + c.url + "' /></td>"
					+ "<td>" + conceptsDropDownBox(c) + "</td>"
					+ "<td><input type=submit name=UserRequest value='Update Chapter'/><input type=submit name=UserRequest value='Delete Chapter'/></td>"
					+ "</form></tr>");
			}
			Chapter c = new Chapter();
			buf.append("<tr><form action=/Edit method=post>"		// extra row to add a new chapter
					+ "<input type=hidden name=TextId value=" + textId + " />"
					+ "<td><input type=text size=4 name=ChapterNumber value=" + (t.chapters.size()+1) + " /></td>"
					+ "<td><input type=text size=20 name=ChapterTitle /></td>"
					+ "<td><input type=text size=20 name=ChapterUrl /></td>"
					+ "<td>" + conceptsDropDownBox(c) + "</td>"
					+ "<td><input type=submit name=UserRequest value='Create Chapter'/></td>"
					+ "</form></tr>");
			
			buf.append("</table>");
			
			return buf.toString();
		} catch (Exception e) {
		buf.append("This is a list of textbooks served by ChemVantage, especially as SmartText objects.");
		try {
			buf.append("<h3>Manage Texts</h3>");
			Query<Text> texts = ofy().load().type(Text.class);
			buf.append("<TABLE BORDER=1 CELLSPACING=0><TR><TH>Title</TH><TH>Author</TH><TH>Publisher</TH><TH>Text URL</TH><TH>Image URL</TH><TH>Print Copy URL</TH><TH>Smart</TH><TH></TH></TR>");
			for (Text text : texts) {
				buf.append("<FORM ACTION=Edit METHOD=POST>"
						+ "<INPUT TYPE=HIDDEN NAME=TextId VALUE=" + text.id + " />"
						+ "<TR><TD><INPUT TYPE=TEXT NAME=Title VALUE='" + Question.quot2html(text.title) + "' /></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=Author VALUE='" + Question.quot2html(text.author) + "' /></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=Publisher VALUE='" + Question.quot2html(text.publisher) + "' /></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=URL VALUE='" + text.URL + "' /></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=ImgURL VALUE='" + text.imgUrl + "' /></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=PrintCopyURL VALUE='" + text.printCopyUrl + "' /></TD>"
						+ "<TD style=text-align:center><INPUT TYPE=CHECKBOX NAME=SmartText VALUE=True " + (text.smartText?"CHECKED ":"") + " /></TD>"
						+ "<TD><INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Update Text'/ > "
						+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Text' /> "
						+ "<a href=/Edit?UserRequest=ManageTexts&TextId=" + text.id + ">Chapters</a>&nbsp;</TD></TR>"
						+ "</FORM>");
			}
			buf.append("<FORM ACTION=Edit METHOD=POST><TR>"
					+ "<TD><INPUT TYPE=TEXT NAME=Title></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=Author></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=Publisher></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=URL></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=ImgURL></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=PrintCopyURL></TD>"
					+ "<TD style=text-align:center><INPUT TYPE=CHECKBOX NAME=SmartText VALUE=True /></TD>"
					+ "<TD><INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Create Text'></TD></TR>"
					+ "</FORM>");
			buf.append("</TABLE>");
		} catch (Exception e2) {
			buf.append(e2.toString());
		}
		return buf.toString();
		}
	}

	/********* temporary utility for Microsoft Partnership *************************

	JsonArray sampleQuestions(int n) {
		JsonArray questions = new JsonArray();
		List<Key<Question>> keys = ofy().load().type(Question.class).keys().list();
		Map<Long,Concept> concepts = getConcepts();
		Random random = new Random();
		while (questions.size() < n) {
			int i = random.nextInt(keys.size());  // choose a random position in the List
			Key<Question> k = keys.remove(i);     // remove it from the List
			Question q = ofy().load().key(k).now();  // get the Question entity
			
			if (q.conceptId==null || concepts.get(q.conceptId)==null) continue;  // must be authentic concept
			JsonObject question = new JsonObject();
			JsonArray choices = new JsonArray();
			question.addProperty("concept", concepts.get(q.conceptId).title);
			switch (q.getQuestionType()) {
			case(1):
				question.addProperty("question_type", "multiple_choice");
				question.addProperty("text",q.text);
				for (String choice:q.choices) choices.add(choice);
				question.add("choices", choices);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case(2):
				question.addProperty("question_type", "true_false");
				question.addProperty("text",q.text);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case(3):
				question.addProperty("question_type", "checkbox");
				question.addProperty("text",q.text);
				for (String choice:q.choices) choices.add(choice);
				question.add("choices", choices);
				question.addProperty("correct_answer", q.correctAnswer);
				break;
			case(4):
				question.addProperty("question_type", "fill_in_blank");
				question.addProperty("text",q.text);
				question.addProperty("correct_answer", q.getCorrectAnswer());
				if (q.tag!=null) question.addProperty("tag", q.tag);
				break;
			case(5):
				q.setParameters();
				question.addProperty("question_type", "numeric");
				question.addProperty("text",q.parseString(q.text));
				question.addProperty("correct_answer", q.getCorrectAnswer());
				if (q.tag!=null) question.addProperty("units", q.parseString(q.tag));
				if (q.solution!=null) question.addProperty("solution", q.parseString(q.solution));
				break;
			default: continue;
			}		
			questions.add(question);
		}
		return questions;
	}
	
	Map<Long,Concept> getConcepts() {
		Map<Long,Concept> conceptMap = new HashMap<Long,Concept>();
		List<Concept> conceptList = ofy().load().type(Concept.class).list();
		for (Concept c : conceptList) {
			if (c.orderBy.compareTo("1")>0) conceptMap.put(c.id, c); // skips early concepts
		}
		return conceptMap;
	}
**********************************************************************************/
	
	String topicSelectBox(long topicId) {
		return topicSelectBox(topicId,false);
	}
	
	String topicSelectBox(long topicId,boolean autoSubmit) {
		StringBuffer buf = new StringBuffer("\n<SELECT NAME=TopicId" + (autoSubmit?" onChange=submit()>":">"));
		if (topicId == 0) buf.append("\n<OPTION VALUE=''>Select a topic</OPTION>");
		Query<Topic> topics = ofy().load().type(Topic.class).order("orderBy");
		for (Topic t : topics) {
			buf.append("<OPTION VALUE=" + t.id + (t.id.equals(topicId)?" SELECTED>":">")
					+ t.title + "</OPTION>\n");
		}
		buf.append("</SELECT>");
		return buf.toString();
	}
	
	String topicsForm(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		buf.append("<h2>Manage Quiz/Homework/Exam Topics</h2>");
		try {
			// print the table of topics for this subject:
			buf.append("<b>" + Subject.getTitle() + "</b>\n");
			buf.append("<TABLE BORDER=0 CELLSPACING=3>"
					+ "<TR><TH COLSPAN=3>&nbsp;</TH><TH COLSPAN=2>View/Add/Edit Questions</TH><TH></TH></TR>"
					+ "<TR><TH>Order (in use)</TH><TH>Title</TH><TH>Action</TH><TH>Quiz</TH><TH>HW</TH><TH>Exam</TH><TH>Video</TH><TH>OpenStax</TH><TH>Concepts</TH></TR>\n");
			Query<Topic> topics = ofy().load().type(Topic.class).order("orderBy");
				for (Topic t : topics) { // one row for each topic
					int nQuiz = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("topicId",t.id).count();
					int nHW = ofy().load().type(Question.class).filter("assignmentType","Homework").filter("topicId",t.id).count();
					int nExam = ofy().load().type(Question.class).filter("assignmentType","Exam").filter("topicId",t.id).count();
					int nVideo = ofy().load().type(Question.class).filter("assignmentType","Video").filter("topicId",t.id).count();
					buf.append("\n<FORM NAME=TopicsForm" + t.id + " METHOD=POST ACTION=/Edit>"
							+ "<INPUT TYPE=HIDDEN NAME=UserRequest VALUE=UpdateTopic>"
							+ "<INPUT TYPE=HIDDEN NAME=TopicId VALUE='" + t.id + "'>");
					buf.append("\n<TR>"
							+ "<TD ALIGN=CENTER><INPUT NAME=OrderBy SIZE=4 VALUE='" + t.orderBy + "'> (" + ofy().load().type(Assignment.class).filter("topicId",t.id).count() + ")</TD>"
							+ "<TD ALIGN=CENTER><INPUT NAME=Title VALUE='" + Question.quot2html(t.title) + "'></TD>"
							+ "<TD ALIGN=CENTER><INPUT TYPE=SUBMIT VALUE=Update>"
							+ ((nQuiz==0 && nHW==0 && nExam==0 && nVideo==0)?"<INPUT TYPE=SUBMIT VALUE='Delete' "
									+ "onClick=\"javascript: document.TopicsForm" + t.id + ".UserRequest.value='DeleteTopic';\" />":"")
									+ "</TD>");
					buf.append("<TD ALIGN=CENTER><a href=Edit?AssignmentType=Quiz&TopicId=" + t.id + "><b>" + nQuiz + "</b></a></TD>");
					buf.append("<TD ALIGN=CENTER><a href=Edit?AssignmentType=Homework&TopicId=" + t.id + "><b>" + nHW + "</b></a></TD>");
					buf.append("<TD ALIGN=CENTER><a href=Edit?AssignmentType=Exam&TopicId=" + t.id + "><b>" + nExam + "</b></a></TD>");
					buf.append("<TD ALIGN=CENTER><a href=Edit?AssignmentType=Exam&TopicId=" + t.id + "><b>" + nVideo + "</b></a></TD>");
					// The next column is a checkbox to indicate if the topic is aligned with OpenStax textbook
					buf.append("<TD ALIGN=CENTER><INPUT TYPE=CHECKBOX NAME=TopicGroup VALUE=1" + (t.topicGroup%2/1==1?" CHECKED":"") + "></TD>");
					// Add a drop-down to select Concepts for this Topic
					buf.append("<TD>" + conceptsDropDownBox(t) + "</TD>");
					buf.append("</FORM></TR>");
				}
			//}
			// print one-row form to add a new topic (quiz):
			buf.append("<FORM METHOD=POST ACTION=/Edit><INPUT TYPE=HIDDEN NAME=UserRequest VALUE=CreateTopic>");
			buf.append("<TR>"
					+ "<TD ALIGN=CENTER><INPUT NAME=OrderBy SIZE=4></TD>"
					+ "<TD ALIGN=CENTER><INPUT NAME=Title></TD>"
					+ "<TD ALIGN=CENTER><INPUT TYPE=SUBMIT VALUE='Create New Topic'></TD><TD ALIGN=CENTER>0</TD><TD ALIGN=CENTER>0</TD><TD ALIGN=CENTER>0</TD><TD ALIGN=CENTER>0</TD><TD ALIGN=CENTER><INPUT TYPE=CHECKBOX NAME=TopicGroup VALUE=1></TD></TR></FORM>");
			buf.append("</TABLE>");
			buf.append("<FONT SIZE=-1 COLOR=RED>Notes:<OL>"
					+ "<LI>The alphanumeric value of Order controls the order that topics are arranged.</LI>"
					+ "<LI>To hide a topic from students, change the value of Order to 'Hide'.</LI>"
					+ "<LI>A topic can be deleted only if all its questions have been deleted or moved.</LI>"
					+ "</OL></FONT>");
		} catch (Exception e) {
			buf.append(e.getMessage());
		}
		return buf.toString();
	}
	
	void updateChapter(HttpServletRequest request) {
		try {
			Text text = ofy().load().type(Text.class).id(Long.parseLong(request.getParameter("TextId"))).safe();
			int chapterIndex = Integer.parseInt(request.getParameter("ChapterIndex"));
			Chapter ch = text.chapters.get(chapterIndex);
			ch.chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
			ch.title = request.getParameter("ChapterTitle");
			ch.url =  request.getParameter("ChapterUrl");
			String[] conceptIds = request.getParameterValues("ConceptId");
			ch.conceptIds.clear();
			for (String cId : conceptIds) ch.conceptIds.add(Long.parseLong(cId));
			
			text.chapters.set(chapterIndex,ch);
			ofy().save().entity(text).now();
		} catch (Exception e) {}
	}

	void updateConcept(User user,HttpServletRequest request) {
		long conceptId = 0;
		try {
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
			Concept c = ofy().load().type(Concept.class).id(conceptId).safe();
			c.title = request.getParameter("Title");
			if (c.title == null) c.title = "";
			c.orderBy = request.getParameter("OrderBy");
			if (c.orderBy == null) c.orderBy = "";
			
			ofy().save().entity(c).now();
		} catch (Exception e) {}
	}

	private void updateQuestion(User user,HttpServletRequest request) {
		long questionId = 0;
		try {
			questionId = Long.parseLong(request.getParameter("QuestionId"));	
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			q = assembleQuestion(request,q);
			q.editorId = user.getId();
			q.isActive = true;
			ofy().save().entity(q).now();
			questions.remove(key(q));
		} catch (Exception e) {
			return;
		}
	}

	String updateQuizlet(HttpServletRequest request) { 		
		StringBuffer buf = new StringBuffer();
		Video v = null; 
		int segment = -1;
		
		try {
			v= ofy().load().type(Video.class).id(Long.parseLong(request.getParameter("VideoId"))).safe();
			buf.append("Video: " + v.title + "<br>");
			
			String stringTopicId = request.getParameter("TopicId");
			if (stringTopicId != null) v.topicId = Long.parseLong(stringTopicId);
			
			try {
				segment = Integer.parseInt(request.getParameter("Segment"));
			} catch (Exception e) {}
	
			int time=-1; // negative value indicates "end of video" (unreachable time)
			try {
				time = Integer.parseInt(request.getParameter("Seconds")); // position of the new breakpoint for this segment
			} catch (Exception e) {}
	
			if (segment>=0) {
				// Determine the number of breaks: 1) first break (1), revised break (v.breaks.length), or new break (v.breaks.length+1)
				String[] questionIds = request.getParameterValues("QuestionId");			
				if (questionIds==null) questionIds = new String[0];
	
				int nBreaks = (v.breaks==null?1:(segment<v.breaks.length?v.breaks.length:v.breaks.length+1));
				int[] breaks = new int[nBreaks];
				int[] nQuestions = new int[nBreaks];
	
				// Copy the breakpoints from the current video, insert or append the new breakpoint, and copy back to the video:
				for (int i=0;i<breaks.length;i++) {
					breaks[i] = (i==segment?time:v.breaks[i]);
					nQuestions[i] = (i==segment?questionIds.length:v.nQuestions[i]);
				}
	
				buf.append("Created/updated the breakpoint.<br>");
	
				// Create a new List of questionKeys for the video
				ArrayList<Key<Question>> questionKeys = new ArrayList<Key<Question>>();
				int counter = 0;
				for (int i = 0;i<nBreaks;i++) {
					if (i==segment) {
						for (String id : questionIds) questionKeys.add(key(Question.class,Long.parseLong(id)));
						counter += (v.nQuestions==null || i==v.nQuestions.length)?0:v.nQuestions[i];  // skip this many keys that are being replaced
					} else {
						for (int j=counter;j<counter+v.nQuestions[i];j++) questionKeys.add(v.questionKeys.get(j));
						counter += v.nQuestions[i];
					}
				}
				buf.append("Created/updated the List of question keys.<br>");
	
				// Update the video parameters to the new values:
				v.breaks = breaks;
				v.nQuestions = nQuestions;
				v.questionKeys = questionKeys;
			}
			ofy().save().entity(v).now();
		} catch (Exception e) {
			return buf.toString() + e.getMessage();
		}
		return editVideoForm(v,v.breaks[segment]<0?segment:segment+1);
	}

	void updateText(User user,HttpServletRequest request) {
		try {
			Text text = ofy().load().type(Text.class).id(Long.parseLong(request.getParameter("TextId"))).safe();
			text.title = request.getParameter("Title");
			text.author = request.getParameter("Author");
			text.publisher = request.getParameter("Publisher");
			text.URL = request.getParameter("URL");
			text.imgUrl = request.getParameter("ImgURL");
			text.printCopyUrl = request.getParameter("PrintCopyURL");
			text.smartText = Boolean.parseBoolean(request.getParameter("SmartText"));
			ofy().save().entity(text).now();
		} catch (Exception e) {}
	}

	void updateTopic(User user,HttpServletRequest request) {
		long topicId = 0;
		try {
			topicId = Long.parseLong(request.getParameter("TopicId"));
			Topic t = ofy().load().type(Topic.class).id(topicId).safe();
			t.title = request.getParameter("Title");
			if (t.title == null) t.title = "";
			t.orderBy = request.getParameter("OrderBy");
			if (t.orderBy == null) t.orderBy = "";
			t.topicGroup = 0;
			String[] alignments = request.getParameterValues("TopicGroup");
			if (alignments != null) {
				for (String text : alignments) t.topicGroup += Integer.parseInt(text);
			}
			String[] conceptIds = request.getParameterValues("ConceptId");
			if (conceptIds != null) {
				t.conceptIds.clear();
				for (String cId : conceptIds) t.conceptIds.add(Long.parseLong(cId));
			}
			
			ofy().save().entity(t).now();
		} catch (Exception e) {}
	}

	void updateVideo(HttpServletRequest request) {
		Video v = ofy().load().type(Video.class).id(Long.parseLong(request.getParameter("VideoId"))).safe();
		v.title = request.getParameter("Title");
		v.orderBy = request.getParameter("OrderBy");
		ofy().save().entity(v).now();
	}

	String validateAssignmentWithAI(HttpServletRequest request) throws Exception {
		// This method performs a remote task to validate all of the question items in a new Assignment
		if (Subject.getProjectId().equals("dev-vantage-hrd")) return "Validation skipped for dev server.";  // skip validation for dev environment
		
		Assignment a = ofy().load().type(Assignment.class).id(Long.parseLong(request.getParameter("AssignmentId"))).safe();
		String subject = "ChemVantage AI Validation Report for Assignment " + a.id;
		StringBuilder buf = new StringBuilder("<h1>" + subject + "</h1>");
		
		// Get the Assignment and its question items
		if (a.questionKeys.size()==0) return buf.append("There are no question items for this assignment.").toString();
		if (!a.assignmentType.equals("Homework")) return buf.append("The assignmrent type must be Homework.").toString(); // only validate homework assignments for now
		// For each question in the assignment, send the question text and correct answer to the AI and ask if the correct answer is correct.
		// If any answer is deemed incorrect by the AI, send email to the ChemVantage administrator with a list of links to edit the questions.
		List<Key<Question>> flaggedQuestionKeys = new ArrayList<Key<Question>>();
		List<Key<Question>> failedQuestionKeys = new ArrayList<Key<Question>>();
		for (Key<Question> k : a.questionKeys) {
			Question q = ofy().load().key(k).now();
			if (q==null || q.getQuestionType() > 5 || (q.checkedByAI != null && q.checkedByAI)) continue;
			try {
				JsonObject aiValidation = requestValidationFromGemini(q);
				q.checkedByAI = aiValidation.get("isCorrect").getAsBoolean();
				ofy().save().entity(q);
			} catch (Exception e) {
				failedQuestionKeys.add(k);
				continue;
			}
		}
		if (flaggedQuestionKeys.size()==0 && failedQuestionKeys.size()==0) return buf.append("All questions in this assignment passed AI validation.").toString();
		
		String message = "<a href=https://" + (Subject.getProjectId().equals("chem-vantage-hrd")?"www.chemvantage.org":"dev-vantage-hrd.appspot.com") + "/Edit?AssignmentId=" + a.id + ">View Assignment</a> or "
		+ "<a href=https://" + (Subject.getProjectId().equals("chem-vantage-hrd")?"www.chemvantage.org":"dev-vantage-hrd.appspot.com") + "/Edit?UserRequest=ValidateAssignmentWithAI&AssignmentId=" + a.id + ">Run Validation Interactively</a><br/><br/>";
		if (flaggedQuestionKeys.size()>0) {
			message += "The following questions in Assignment " + a.id + " were flagged by the AI validator as potentially having incorrect answers:<br/>";
			for (Key<Question> k : flaggedQuestionKeys) {
				Long questionId = k.getId();
				message += "Question ID: " + questionId + " <a href=https://" + (Subject.getProjectId().equals("chem-vantage-hrd")?"www.chemvantage.org":"dev-vantage-hrd.appspot.com") + "/Edit?UserRequest=Edit&AssignmentId=" + a.id + "&QuestionId=" + questionId + ">Edit</a><br/>";
			}
		} else {
			message += "The AI validator did not flag any questions in Assignment " + a.id + " as potentially having incorrect answers.<br/>";
		}
		message += "<br/>";

		if (failedQuestionKeys.size()>0) {
			message += "The AI validator encountered errors when validating the following questions in Assignment " + a.id + ":<br/>";
			for (Key<Question> k : failedQuestionKeys) {
				Long questionId = k.getId();
				message += "Question ID: " + questionId + " <a href=https://" + (Subject.getProjectId().equals("chem-vantage-hrd")?"www.chemvantage.org":"dev-vantage-hrd.appspot.com") + "/Edit?UserRequest=Edit&AssignmentId=" + a.id + "&QuestionId=" + questionId + ">Edit</a><br/>";
			}
		}

		try {
			Utilities.sendEmail("ChemVantage Administrator", "admin@chemvantage.org", subject, message);
		} catch (IOException e) {
			System.err.println("Failed to send AI validation report email: " + e.getMessage());
		}

		buf.append(message);
		return buf.toString();
	}
/* 
	private void validateCorrectAnswerWithAI(HttpServletRequest request,HttpServletResponse response) throws IOException {
		response.setContentType("application/json");
		JsonObject api_response = new JsonObject();
		try {
			long questionId = Long.parseLong(request.getParameter("QuestionId"));
			long parameterSeed = Long.parseLong(request.getParameter("ParameterSeed"));
			Question q = ofy().load().type(Question.class).id(questionId).safe();
			if (q.requiresParser()) q.setParameters(parameterSeed);
			String aiGeneratedAnswer = "chatgpt".equals(AI_VALIDATOR_PROVIDER)
					? requestCorrectAnswerFromChatGPT(q)
					: requestCorrectAnswerFromGemini(q);
			
			if (extractCorrectAnswerValue(aiGeneratedAnswer)) {
				q.checkedByAI = true;
				ofy().save().entity(q);
			}
			api_response.addProperty("success", true);
			api_response.addProperty("questionId", questionId);
			api_response.addProperty("checkedByAI", q.checkedByAI);
			api_response.addProperty("aiGeneratedAnswer", aiGeneratedAnswer==null?"":aiGeneratedAnswer);
			if (!q.checkedByAI) {
				api_response.addProperty("message", "The AI-generated answer did not match the instructor's correct answer. Please review the AI-generated answer and update the question if needed.");
				api_response.addProperty("correctAnswer", q.getCorrectAnswer());
				api_response.addProperty("questionText", q.printForSage());
			}
		} catch (Exception e) {
			response.setStatus(500);
			api_response.addProperty("success", false);
			api_response.addProperty("message", e.getMessage()==null?e.toString():e.getMessage());
		}
		response.getWriter().println(api_response.toString());
	}
*/
	JsonObject validateQuestionItemWithAI(HttpServletRequest request) throws Exception{
		Question q = ofy().load().type(Question.class).id(Long.parseLong(request.getParameter("QuestionId"))).now();
		Long parameterSeed = null;
		try {
			parameterSeed = Long.parseLong(request.getParameter("ParameterSeed"));
			q.setParameters(parameterSeed);
		} catch (Exception e) {}
		JsonObject api_response = requestValidationFromGemini(q);
		q.checkedByAI = api_response.get("isCorrect").getAsBoolean();
		ofy().save().entity(q);
		return api_response;
	}

	String videosForm() {
		StringBuffer buf = new StringBuffer("<section class='bg-gradient-primary text-white' style='max-width:500px'>"
				+ "      <div class='container py-5'>"
				+ "          <div class='col-lg-7'>"
				+ "            <h1 class='display-5 fw-semibold mb-3'>Editors</h1>"
				+ "          </div>"
				+ "        </div>"
				+ "    </section><p>");
		buf.append("<h2>Manage Videos</h2>");
		try {
			List<Video> videos = ofy().load().type(Video.class).order("orderBy").list();
			
			buf.append("<TABLE BORDER=1 CELLSPACING=0><TR><TH>OrderBy</TH><TH>Title</TH><TH>Serial No.</TH><TH>Breaks</TH><TH>Questions</TH><TH>Action</TH></TR>");
			for (Video v : videos) {
				buf.append("<FORM ACTION=Edit METHOD=POST>"
						+ "<INPUT TYPE=HIDDEN NAME=VideoId VALUE='" + v.id + "'>"
						+ "<TR><TD><INPUT TYPE=TEXT NAME=OrderBy VALUE='" + v.orderBy + "'></TD>"
						+ "<TD><INPUT TYPE=TEXT NAME=Title VALUE='" + Question.quot2html(v.title) + "'></TD>"
						+ "<TD>" + v.serialNumber + "</TD>"
						+ "<TD>" + (v.breaks==null?"0":v.breaks.length) + "</TD>"
						+ "<TD>" + (v.questionKeys==null?0:v.questionKeys.size()) + "</TD>"
						+ "<TD><a href=/Edit?UserRequest=EditVideo&VideoId=" + v.id +">Select Questions</a> "
						+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Update Video'> "
						+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Video'></TD></TR>"
						+ "</FORM>");
			}
			buf.append("<FORM ACTION=Edit METHOD=POST>"
					+ "<TR><TD><INPUT TYPE=TEXT NAME=OrderBy></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=Title></TD>"
					+ "<TD><INPUT TYPE=TEXT NAME=SerialNumber></TD>"
					+ "<TD></TD>"
					+ "<TD></TD>"
					+ "<TD><INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Create Video'></TD></TR>"
					+ "</FORM>");
			buf.append("</TABLE>");
		} catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}

	class SortByQuestionText implements Comparator<Key<Question>> {
		public int compare(Key<Question> k1,Key<Question> k2) {
			Question q1 = questions.get(k1);
			Question q2 = questions.get(k2);
			
			if (pointValue.get(k1)==null) pointValue.put(k1, q1.pointValue);
			if (pointValue.get(k2)==null) pointValue.put(k2, q2.pointValue);
			int rank = pointValue.get(k1) - pointValue.get(k2);
			
			if (rank==0) rank = q1.text.compareTo(q2.text); // alphabetize on Question.text
			if (rank==0) rank = k1.compareTo(k2); // tie breaker
			
			return rank;  
		}
	}

}
