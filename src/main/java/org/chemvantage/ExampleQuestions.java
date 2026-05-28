package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@WebServlet(urlPatterns = {"/example-questions", "/example-questions/"})
public class ExampleQuestions extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	private static final int DEFAULT_QUESTION_COUNT = 3;
	private static final int MAX_QUESTION_COUNT = 3;
	private static final String[] VISITOR_ASSIGNMENT_TYPES = {"Quiz", "Homework"};
	private static final String[] TOPIC_GROUP_NAMES = {
			"Essential Ideas",
			"Atoms, molecules and Ions",
			"Composition of Matter",
			"Stoichiometry",
			"Thermochemistry",
			"Electronic Structure",
			"Periodic Properties",
			"Chemical bonding",
			"Gases",
			"Liquids and Solids",
			"Solutions",
			"Kinetics",
			"Equilibrium",
			"Acid-Base Equilibria",
			"Solubility",
			"Electrochemistry",
			"Representative Metals",
			"Nonmetals and Metalloids",
			"Organic Chemistry",
			"Nuclear Chemistry"
	};

	@Override
	public String getServletInfo() {
		return "This servlet serves public ChemVantage example questions.";
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("text/html;charset=UTF-8");
		response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();

		int nQuestions = requestedQuestionCount(request);
		Long conceptId = requestedConceptId(request);
		try {
			JsonObject library = loadSampleQuestionLibrary();
			if (library != null) {
				List<Concept> concepts = sampleConcepts(library);
				Map<Long, Integer> questionCounts = sampleQuestionCounts(library);
				Map<Long, Concept> conceptMap = conceptMap(concepts);
				if (conceptId != null && (!conceptMap.containsKey(conceptId) || !hasSampleQuestions(conceptId, questionCounts))) conceptId = null;
				List<Question> questions = selectSampleQuestions(library, conceptId, nQuestions);
				out.println(Subject.publicHeader("Sample Questions", "Three free General Chemistry questions with instant feedback.", "Samples") + renderPage(concepts, conceptMap, questionCounts, questions, conceptId, nQuestions, null) + Subject.publicFooter());
				return;
			}
			List<Concept> concepts = loadConcepts();
			if (concepts.isEmpty()) concepts = sampleConcepts();
			Map<Long, Concept> conceptMap = conceptMap(concepts);
			if (conceptId != null && !conceptMap.containsKey(conceptId)) conceptId = null;
			List<Question> questions = selectQuestions(conceptId, nQuestions);
			if (questions.isEmpty()) questions = selectSampleQuestions(conceptId, nQuestions);
			out.println(Subject.publicHeader("Sample Questions", "Three free General Chemistry questions with instant feedback.", "Samples") + renderPage(concepts, conceptMap, null, questions, conceptId, nQuestions, null) + Subject.publicFooter());
		} catch (Exception e) {
			List<Concept> concepts = sampleConcepts();
			Map<Long, Concept> conceptMap = conceptMap(concepts);
			List<Question> questions = selectSampleQuestions(null, nQuestions);
			out.println(Subject.publicHeader("Sample Questions", "Three free General Chemistry questions with instant feedback.", "Samples") + renderPage(concepts, conceptMap, null, questions, null, nQuestions,
					"Example questions could not be loaded from the datastore in this environment.") + Subject.publicFooter());
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("text/html;charset=UTF-8");
		response.setCharacterEncoding("UTF-8");
		PrintWriter out = response.getWriter();

		int nQuestions = requestedQuestionCount(request);
		Long conceptId = requestedConceptId(request);
		try {
			JsonObject library = loadSampleQuestionLibrary();
			if (library != null) {
				List<Concept> concepts = sampleConcepts(library);
				Map<Long, Integer> questionCounts = sampleQuestionCounts(library);
				Map<Long, Concept> conceptMap = conceptMap(concepts);
				if (conceptId != null && (!conceptMap.containsKey(conceptId) || !hasSampleQuestions(conceptId, questionCounts))) conceptId = null;
				out.println(Subject.publicHeader("Sample Question Results", "Check your work and try a fresh set.", "Samples") + renderResults(request, concepts, conceptMap, questionCounts, conceptId, nQuestions) + Subject.publicFooter());
				return;
			}
			List<Concept> concepts = loadConcepts();
			if (concepts.isEmpty()) concepts = sampleConcepts();
			Map<Long, Concept> conceptMap = conceptMap(concepts);
			if (conceptId != null && !conceptMap.containsKey(conceptId)) conceptId = null;
			out.println(Subject.publicHeader("Sample Question Results", "Check your work and try a fresh set.", "Samples") + renderResults(request, concepts, conceptMap, null, conceptId, nQuestions) + Subject.publicFooter());
		} catch (Exception e) {
			List<Concept> concepts = sampleConcepts();
			Map<Long, Concept> conceptMap = conceptMap(concepts);
			List<Question> questions = selectSampleQuestions(null, nQuestions);
			out.println(Subject.publicHeader("Sample Questions", "Three free General Chemistry questions with instant feedback.", "Samples") + renderPage(concepts, conceptMap, null, questions, null, nQuestions,
					"Submitted answers could not be checked because the datastore is unavailable in this environment.") + Subject.publicFooter());
		}
	}

	private String renderPage(List<Concept> concepts,Map<Long,Concept> conceptMap,Map<Long,Integer> questionCounts,List<Question> questions,Long selectedConceptId,int nQuestions,String message) {
		StringBuffer buf = new StringBuffer();
		buf.append("<main id='main-content' class='container py-4' style='max-width:960px;'>");
		buf.append("<div class='mb-4'>");
		buf.append("<h2 class='h4 mt-3'>Choose a topic</h2>");
		buf.append("</div>");
		buf.append(renderChooser(concepts, questionCounts, selectedConceptId, nQuestions));
		if (message != null && !message.isEmpty()) buf.append("<div class='alert alert-warning' role='alert'>" + html(message) + "</div>");
		if (questions.isEmpty()) {
			buf.append("<div class='alert alert-info' role='status'>No answerable example questions were found for this selection. Choose another concept or try random questions.</div>");
		} else {
			buf.append(renderQuestionForm(conceptMap, questions, selectedConceptId, nQuestions));
		}
		buf.append("</main>");
		buf.append(renderScripts());
		return buf.toString();
	}

	private String renderChooser(List<Concept> concepts,Map<Long,Integer> questionCounts,Long selectedConceptId,int nQuestions) {
		StringBuffer buf = new StringBuffer();
		buf.append("<form class='border rounded-3 p-3 mb-4 bg-light' method='get' action='/example-questions'>");
		buf.append("<label class='form-label' for='ConceptSearch'>Concept</label>");
		buf.append("<div class='position-relative'>");
		buf.append("<input class='form-control' id='ConceptSearch' type='search' autocomplete='off' role='combobox' aria-expanded='false' aria-controls='ConceptOptions' placeholder='Any concept' value='").append(html(conceptTitle(conceptMap(concepts), selectedConceptId))).append("' />");
		buf.append("<input id='ConceptId' name='ConceptId' type='hidden' value='").append(selectedConceptId == null?"":selectedConceptId).append("' />");
		buf.append("<div id='ConceptOptions' class='example-concept-menu position-absolute w-100 shadow-sm' style='display:none;'>");
		buf.append("<button class='example-concept-chip example-any-concept' type='button' data-concept-id='' data-concept-title='Any concept'>Any concept</button>");
		buf.append(renderGroupedConceptBrowser(concepts, questionCounts));
		buf.append("</div>");
		buf.append("</div>");
		buf.append("</form>");
		return buf.toString();
	}

	private String renderGroupedConceptBrowser(List<Concept> concepts,Map<Long,Integer> questionCounts) {
		StringBuffer buf = new StringBuffer();
		buf.append("<div id='ConceptGroups' class='example-concept-groups'>");
		for (String groupName : TOPIC_GROUP_NAMES) {
			List<Concept> groupConcepts = conceptsForTopicGroup(concepts, groupName, questionCounts);
			if (groupConcepts.isEmpty()) continue;
			buf.append("<section class='example-concept-group' data-group-title='").append(html(groupName)).append("'>");
			buf.append("<div class='example-group-title'>").append(html(groupName)).append("</div>");
			buf.append("<div class='example-group-links'>");
			for (Concept c : groupConcepts) {
				buf.append("<button class='example-concept-chip' type='button' data-concept-id='").append(c.id).append("' data-concept-title='").append(html(c.title)).append("'>").append(html(c.title)).append("</button>");
			}
			buf.append("</div>");
			buf.append("</section>");
		}
		buf.append("</div>");
		return buf.toString();
	}

	private List<Concept> conceptsForTopicGroup(List<Concept> concepts,String groupName,Map<Long,Integer> questionCounts) {
		List<Concept> groupConcepts = new ArrayList<>();
		for (Concept c : concepts) if (isInTopicGroup(c, groupName) && hasSampleQuestions(c, questionCounts)) groupConcepts.add(c);
		return groupConcepts;
	}

	private boolean hasSampleQuestions(Concept c,Map<Long,Integer> questionCounts) {
		return c != null && c.id != null && hasSampleQuestions(c.id, questionCounts);
	}

	private boolean hasSampleQuestions(Long conceptId,Map<Long,Integer> questionCounts) {
		return questionCounts == null || (conceptId != null && questionCounts.getOrDefault(conceptId, 0) > 0);
	}

	private boolean isInTopicGroup(Concept c,String groupName) {
		int chapter = conceptChapter(c);
		return switch (groupName) {
			case "Essential Ideas" -> chapter == 1;
			case "Atoms, molecules and Ions" -> chapter == 2;
			case "Composition of Matter" -> chapter == 3;
			case "Stoichiometry" -> chapter == 4;
			case "Thermochemistry" -> chapter == 5;
			case "Electronic Structure" -> chapter == 6 && !orderByStartsWith(c, "6.5");
			case "Periodic Properties" -> orderByStartsWith(c, "6.5");
			case "Chemical bonding" -> chapter == 7 || chapter == 8;
			case "Gases" -> chapter == 9;
			case "Liquids and Solids" -> chapter == 10;
			case "Solutions" -> chapter == 11;
			case "Kinetics" -> chapter == 12;
			case "Equilibrium" -> chapter == 13;
			case "Acid-Base Equilibria" -> chapter == 14;
			case "Solubility" -> chapter == 15;
			case "Electrochemistry" -> chapter == 17;
			case "Representative Metals" -> orderByStartsWith(c, "18.1") || orderByStartsWith(c, "18.2");
			case "Nonmetals and Metalloids" -> chapter == 18 && !orderByStartsWith(c, "18.1") && !orderByStartsWith(c, "18.2");
			case "Organic Chemistry" -> chapter == 20;
			case "Nuclear Chemistry" -> chapter == 21;
			default -> false;
		};
	}

	private boolean orderByStartsWith(Concept c,String prefix) {
		return c != null && c.orderBy != null && c.orderBy.trim().startsWith(prefix);
	}

	private int conceptChapter(Concept c) {
		if (c == null || c.orderBy == null) return 0;
		try {
			String orderBy = c.orderBy.trim();
			int decimal = orderBy.indexOf('.');
			String chapter = decimal < 0?orderBy:orderBy.substring(0, decimal);
			return Integer.parseInt(chapter);
		} catch (Exception e) {
			return 0;
		}
	}

	private String renderQuestionForm(Map<Long,Concept> conceptMap,List<Question> questions,Long selectedConceptId,int nQuestions) {
		StringBuffer buf = new StringBuffer();
		Random rand = new Random();
		buf.append("<form method='post' action='/example-questions' onsubmit='return false;'>");
		buf.append("<input type='hidden' name='n' value='").append(nQuestions).append("' />");
		if (selectedConceptId != null) buf.append("<input type='hidden' name='ConceptId' value='").append(selectedConceptId).append("' />");
		int questionNumber = 1;
		for (Question q : questions) {
			long parameter = Math.abs(rand.nextLong());
			q.setParameters(parameter);
			buf.append("<section class='border rounded-3 p-4 mb-3 bg-white'>");
			buf.append("<div class='d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3'>");
			buf.append("<h2 class='h5 mb-0'>Question ").append(questionNumber++).append("</h2>");
			String conceptTitle = conceptTitle(conceptMap, q.conceptId);
			if (!conceptTitle.isEmpty()) buf.append("<span class='badge text-bg-light border'>").append(html(conceptTitle)).append("</span>");
			buf.append("</div>");
			buf.append("<input type='hidden' name='QuestionId' value='").append(q.id).append("' />");
			buf.append("<input type='hidden' name='p").append(q.id).append("' value='").append(parameter).append("' />");
			buf.append(q.print());
			buf.append(renderHintControls(q));
			buf.append("</section>");
		}
		buf.append("</form>");
		return buf.toString();
	}

	private String renderResults(HttpServletRequest request,List<Concept> concepts,Map<Long,Concept> conceptMap,Map<Long,Integer> questionCounts,Long selectedConceptId,int nQuestions) {
		StringBuffer buf = new StringBuffer();
		buf.append("<main id='main-content' class='container py-4' style='max-width:960px;'>");
		buf.append("<div class='mb-4'>");
		buf.append("<h2 class='h4 mt-3'>Example Question Results</h2>");
		buf.append("</div>");
		buf.append(renderChooser(concepts, questionCounts, selectedConceptId, nQuestions));

		String[] questionIds = request.getParameterValues("QuestionId");
		if (questionIds == null || questionIds.length == 0) {
			buf.append("<div class='alert alert-warning' role='alert'>No questions were submitted.</div>");
			buf.append("</main>").append(renderScripts());
			return buf.toString();
		}

		int correct = 0;
		List<String> questionResults = new ArrayList<>();
		for (String questionId : questionIds) {
			try {
				long qid = Long.parseLong(questionId);
				Question q = qid < 0L?sampleQuestion(qid):ofy().load().type(Question.class).id(qid).safe();
				if (!isVisitorQuestion(q)) continue;
				long parameter = parseLong(request.getParameter("p" + qid), System.currentTimeMillis());
				q.setParameters(parameter);
				String answer = orderResponses(request.getParameterValues(String.valueOf(qid)));
				String showWork = request.getParameter("ShowWork" + qid);
				boolean isCorrect = q.isCorrect(answer);
				if (isCorrect) correct++;
				questionResults.add(renderQuestionResult(q, conceptMap, answer, showWork, isCorrect, questionResults.size() + 1));
			} catch (Exception e) {
				questionResults.add("<section class='border rounded-3 p-4 mb-3 bg-white'><h2 class='h5'>Question " + (questionResults.size() + 1) + "</h2><div class='alert alert-warning'>This question could not be scored.</div></section>");
			}
		}

		buf.append("<div class='alert alert-primary' role='status'>You answered <b>").append(correct).append("</b> of <b>").append(questionResults.size()).append("</b> questions correctly.</div>");
		for (String questionResult : questionResults) buf.append(questionResult);
		buf.append("<div class='d-flex flex-wrap gap-2 mb-5'>");
		buf.append("<a class='btn btn-primary btn-lg' href='").append(newQuestionUrl(selectedConceptId, DEFAULT_QUESTION_COUNT)).append("'>Get 5 New Questions</a>");
		buf.append("<a class='btn btn-outline-secondary btn-lg' href='/example-questions'>Try Random Questions</a>");
		buf.append("</div>");
		buf.append("</main>");
		buf.append(renderScripts());
		return buf.toString();
	}

	private String renderQuestionResult(Question q,Map<Long,Concept> conceptMap,String answer,String showWork,boolean isCorrect,int questionNumber) {
		StringBuffer buf = new StringBuffer();
		buf.append("<section class='border rounded-3 p-4 mb-3 bg-white'>");
		buf.append("<div class='d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3'>");
		buf.append("<h2 class='h5 mb-0'>Question ").append(questionNumber).append("</h2>");
		buf.append("<span class='badge ").append(isCorrect?"text-bg-success":"text-bg-danger").append("'>").append(isCorrect?"Correct":"Incorrect").append("</span>");
		String conceptTitle = conceptTitle(conceptMap, q.conceptId);
		if (!conceptTitle.isEmpty()) buf.append("<span class='badge text-bg-light border'>").append(html(conceptTitle)).append("</span>");
		buf.append("</div>");
		buf.append(q.printAllToStudents(studentAnswerHtml(answer), true, false, html(showWork)));
		buf.append("</section>");
		return buf.toString();
	}

	private String renderScripts() {
		return "<script>"
				+ "function showWorkBox(qid){var el=document.getElementById('showWork'+qid);if(el)el.style.display='block';}"
				+ "function waitForExampleScore(){var b=document.getElementById('SubmitButton');if(b){b.disabled=true;b.innerText='Checking answers...';}return true;}"
				+ "function normalizeConceptText(s){return (s||'').toLowerCase().replace(/[^a-z0-9]+/g,'');}"
				+ "function editDistance(a,b){var m=a.length,n=b.length,dp=[];for(var i=0;i<=m;i++){dp[i]=[i];}for(var j=1;j<=n;j++)dp[0][j]=j;for(i=1;i<=m;i++){for(j=1;j<=n;j++){var cost=a.charAt(i-1)===b.charAt(j-1)?0:1;dp[i][j]=Math.min(dp[i-1][j]+1,dp[i][j-1]+1,dp[i-1][j-1]+cost);}}return dp[m][n];}"
				+ "function fuzzyConceptMatch(title,query){query=normalizeConceptText(query);title=normalizeConceptText(title);if(!query)return true;if(title.indexOf(query)>=0)return true;var j=0;for(var i=0;i<title.length&&j<query.length;i++)if(title.charAt(i)===query.charAt(j))j++;if(j===query.length)return true;var maxDistance=query.length<5?1:2;if(editDistance(title.substring(0,Math.max(query.length-1,1)),query)<=maxDistance)return true;var words=title.match(/[a-z0-9]+/g)||[];return words.some(function(word){return editDistance(word,query)<=maxDistance||word.indexOf(query)>=0;});}"
				+ "function groupMatch(group,query){var title=group.dataset.groupTitle||'';return fuzzyConceptMatch(title,query)||normalizeConceptText(title).indexOf(normalizeConceptText(query))>=0;}"
				+ "function jumpToGroup(query){if(!query||!query.trim())return false;var groups=[].slice.call(document.querySelectorAll('[data-group-title]'));var group=groups.find(function(g){return groupMatch(g,query);});if(!group)return false;group.scrollIntoView({behavior:'smooth',block:'nearest'});group.classList.add('example-group-focus');setTimeout(function(){group.classList.remove('example-group-focus');},1200);return true;}"
				+ "function wireConceptSearch(){var input=document.getElementById('ConceptSearch'),value=document.getElementById('ConceptId'),menu=document.getElementById('ConceptOptions');if(!input||!value||!menu)return;var items=[].slice.call(menu.querySelectorAll('[data-concept-title]'));var groups=[].slice.call(menu.querySelectorAll('[data-group-title]'));function visibleItem(){return items.find(function(item){return item.offsetParent!==null&&item.style.display!=='none';});}function showAll(){items.forEach(function(item){item.style.display='inline-block';});groups.forEach(function(group){group.style.display='block';});menu.scrollTop=0;}function choose(item,submit){if(!item)return;value.value=item.dataset.conceptId;input.value=item.dataset.conceptTitle==='Any concept'?'':item.dataset.conceptTitle;menu.style.display='none';if(submit)input.form.submit();}function show(){menu.style.display='block';input.setAttribute('aria-expanded','true');showAll();}function hide(){setTimeout(function(){menu.style.display='none';input.setAttribute('aria-expanded','false');},150);}function filter(){var q=input.value,shown=0,matchedGroup=false;items.forEach(function(item){var match=fuzzyConceptMatch(item.dataset.conceptTitle,q);item.style.display=match?'inline-block':'none';if(match)shown++;});groups.forEach(function(group){var chips=[].slice.call(group.querySelectorAll('[data-concept-title]'));var groupOnly=q&&groupMatch(group,q);if(groupOnly){matchedGroup=true;chips.forEach(function(chip){chip.style.display='inline-block';});}var hasVisible=chips.some(function(chip){return chip.style.display!=='none';});group.style.display=(hasVisible||groupOnly)?'block':'none';});menu.style.display='block';if(matchedGroup||!shown)jumpToGroup(q);}items.forEach(function(item){item.addEventListener('click',function(){choose(item,true);});});input.addEventListener('focus',show);input.addEventListener('click',show);input.addEventListener('input',function(){value.value='';menu.style.display='block';input.setAttribute('aria-expanded','true');filter();});input.addEventListener('blur',hide);input.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();var first=visibleItem();if(first)choose(first,true);else if(!jumpToGroup(input.value))input.form.submit();}});input.form.addEventListener('submit',function(e){if(!value.value&&input.value.trim()){filter();var first=visibleItem();if(first)choose(first,false);else{e.preventDefault();jumpToGroup(input.value);}}});}"
				+ "function showNextExampleHint(qid,total){var state=document.getElementById('hintState'+qid),button=document.getElementById('hintButton'+qid);if(!state||!button)return;var next=parseInt(state.value||'0',10)+1;var hint=document.getElementById('hint'+qid+'_'+next);if(hint)hint.style.display='block';state.value=next;if(next>=total){button.disabled=true;button.innerText='All hints shown';}else button.innerText='Get hint '+(next+1)+' (of '+total+')';}"
				+ "document.addEventListener('DOMContentLoaded',wireConceptSearch);"
				+ "</script>"
				+ "<style>"
				+ ".example-hint{border:1px solid #a8d8ea;border-left:5px solid #0b84a5;background:linear-gradient(90deg,#f0fbff 0%,#ffffff 75%);border-radius:.5rem;padding:.85rem 1rem;box-shadow:0 .35rem 1rem rgba(11,132,165,.08);}"
				+ ".example-hint-label{font-size:.72rem;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:#0b6982;margin-bottom:.2rem;}"
				+ ".example-hint-button{border-color:#0b84a5;color:#0b6982;font-weight:600;}"
				+ ".example-hint-button:hover,.example-hint-button:focus{background:#0b84a5;border-color:#0b84a5;color:#fff;}"
				+ ".example-hint-button:disabled{background:#e8f6fa;border-color:#a8d8ea;color:#457987;opacity:1;}"
				+ ".example-concept-menu{z-index:1000;max-height:28rem;overflow:auto;background:#fff;border:1px solid #cfdde2;border-radius:.5rem;padding:.75rem;}"
				+ ".example-concept-groups{border-top:1px solid #d8e2e7;padding-top:.75rem;margin-top:.65rem;}"
				+ ".example-concept-group{border:1px solid #d8e2e7;border-radius:.5rem;background:#fff;margin-bottom:.75rem;padding:.75rem;transition:box-shadow .2s,border-color .2s;}"
				+ ".example-concept-group.example-group-focus{border-color:#0b84a5;box-shadow:0 0 0 .2rem rgba(11,132,165,.16);}"
				+ ".example-group-title{font-size:.8rem;font-weight:700;color:#37515c;text-transform:uppercase;letter-spacing:.04em;margin-bottom:.5rem;}"
				+ ".example-group-links{display:flex;flex-wrap:wrap;gap:.4rem;}"
				+ ".example-concept-chip{border:1px solid #c9d9df;background:#f8fbfc;color:#24424f;border-radius:999px;padding:.25rem .65rem;font-size:.85rem;line-height:1.35;}"
				+ ".example-any-concept{margin-bottom:.15rem;}"
				+ ".example-concept-chip:hover,.example-concept-chip:focus{border-color:#0b84a5;background:#eef9fc;color:#073f4e;}"
				+ "</style>";
	}

	private String renderHintControls(Question q) {
		List<String> hints = hints(q);
		if (hints.isEmpty()) return "";
		StringBuffer buf = new StringBuffer();
		buf.append("<div class='mt-3'>");
		buf.append("<input id='hintState").append(q.id).append("' type='hidden' value='0' />");
		for (int i=0;i<hints.size();i++) {
			buf.append("<div id='hint").append(q.id).append("_").append(i+1).append("' class='example-hint mb-2' style='display:none;'>");
			buf.append("<div class='example-hint-label'>Hint ").append(i+1).append("</div>");
			buf.append("<div>").append(hints.get(i)).append("</div>");
			buf.append("</div>");
		}
		buf.append("<button id='hintButton").append(q.id).append("' class='btn btn-outline-primary btn-sm example-hint-button' type='button' onclick='showNextExampleHint(\"")
				.append(q.id).append("\",").append(hints.size()).append(");'>Get hint 1 (of ").append(hints.size()).append(")</button>");
		buf.append("</div>");
		return buf.toString();
	}

	private List<String> hints(Question q) {
		List<String> hints = new ArrayList<>();
		if (q == null || q.hint == null || q.hint.isBlank()) return hints;
		String[] pieces = q.hint.split("\\s*(?:\\|\\||\\r?\\n|<br\\s*/?>)\\s*");
		for (String piece : pieces) {
			if (piece == null || piece.isBlank()) continue;
			hints.add(q.parseString(piece.trim()));
		}
		return hints;
	}

	private JsonObject loadSampleQuestionLibrary() {
		InputStream input = getServletContext().getResourceAsStream("/sampleQuestionLibrary.json");
		if (input == null) input = getClass().getClassLoader().getResourceAsStream("META-INF/resources/sampleQuestionLibrary.json");
		if (input == null) return null;
		try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	private List<Concept> sampleConcepts(JsonObject library) {
		List<Concept> concepts = new ArrayList<>();
		if (library == null || !library.has("concepts")) return concepts;
		JsonArray conceptsJson = library.getAsJsonArray("concepts");
		for (JsonElement conceptElement : conceptsJson) {
			JsonObject conceptJson = conceptElement.getAsJsonObject();
			Concept concept = sampleConcept(
					conceptJson.get("id").getAsLong(),
					conceptJson.get("title").getAsString(),
					conceptJson.has("orderBy")?conceptJson.get("orderBy").getAsString():"");
			concepts.add(concept);
		}
		return concepts;
	}

	private List<Question> selectSampleQuestions(JsonObject library,Long conceptId,int count) {
		List<Question> questions = sampleQuestions(library, conceptId);
		if (questions.size() > count) return new ArrayList<>(questions.subList(0, count));
		return questions;
	}

	private Map<Long,Integer> sampleQuestionCounts(JsonObject library) {
		Map<Long,Integer> questionCounts = new HashMap<>();
		if (library == null || !library.has("concepts")) return questionCounts;
		JsonArray conceptsJson = library.getAsJsonArray("concepts");
		for (JsonElement conceptElement : conceptsJson) {
			JsonObject conceptJson = conceptElement.getAsJsonObject();
			long cid = conceptJson.get("id").getAsLong();
			int count = 0;
			JsonArray questionsJson = conceptJson.getAsJsonArray("questions");
			for (JsonElement questionElement : questionsJson) {
				try {
					Question q = sampleQuestion(questionElement.getAsJsonObject(), cid);
					if (isRenderableQuestion(q)) count++;
				} catch (Exception e) {}
			}
			questionCounts.put(cid, count);
		}
		return questionCounts;
	}

	private List<Question> sampleQuestions(JsonObject library,Long conceptId) {
		List<Question> questions = new ArrayList<>();
		if (library == null || !library.has("concepts")) return questions;
		JsonArray conceptsJson = library.getAsJsonArray("concepts");
		for (JsonElement conceptElement : conceptsJson) {
			JsonObject conceptJson = conceptElement.getAsJsonObject();
			long cid = conceptJson.get("id").getAsLong();
			if (conceptId != null && !conceptId.equals(cid)) continue;
			JsonArray questionsJson = conceptJson.getAsJsonArray("questions");
			for (JsonElement questionElement : questionsJson) {
				try {
					Question q = sampleQuestion(questionElement.getAsJsonObject(), cid);
					if (isRenderableQuestion(q)) questions.add(q);
					if (conceptId == null && questions.size() >= MAX_QUESTION_COUNT) return questions;
				} catch (Exception e) {}
			}
			if (conceptId != null) break;
		}
		return questions;
	}

	private boolean isRenderableQuestion(Question q) {
		if (q == null || q.id == null || q.type == null) return false;
		int questionType = q.getQuestionType();
		if (questionType < Question.MULTIPLE_CHOICE || questionType > Question.ESSAY) return false;
		if (questionType != Question.ESSAY && q.hasNoCorrectAnswer()) return false;
		if (questionType == Question.MULTIPLE_CHOICE || questionType == Question.SELECT_MULTIPLE) {
			if (q.choices == null || q.nChoices == null || q.choices.size() < q.nChoices || q.nChoices < 2) return false;
			for (char answer : q.correctAnswer.toCharArray()) {
				int index = answer - 'a';
				if (index < 0 || index >= q.nChoices) return false;
			}
		}
		return true;
	}

	private Question sampleQuestion(JsonObject questionJson,long conceptId) {
		String type = questionJson.get("type").getAsString();
		Question q = sampleQuestionBase(
				questionJson.get("id").getAsLong(),
				conceptId,
				Question.getQuestionType(type),
				questionJson.get("text").getAsString(),
				questionJson.get("correctAnswer").getAsString());
		if (questionJson.has("choices") && questionJson.get("choices").isJsonArray()) {
			q.choices = new ArrayList<>();
			JsonArray choicesJson = questionJson.getAsJsonArray("choices");
			for (JsonElement choice : choicesJson) q.choices.add(choice.getAsString());
			q.nChoices = q.choices.size();
		}
		if (questionJson.has("requiredPrecision")) q.requiredPrecision = questionJson.get("requiredPrecision").getAsDouble();
		if (questionJson.has("significantFigures")) q.significantFigures = questionJson.get("significantFigures").getAsInt();
		if (questionJson.has("tag")) q.tag = questionJson.get("tag").getAsString();
		if (questionJson.has("parameterString")) q.parameterString = questionJson.get("parameterString").getAsString();
		if (questionJson.has("solution")) q.solution = questionJson.get("solution").getAsString();
		if (questionJson.has("scrambleChoices")) q.scrambleChoices = questionJson.get("scrambleChoices").getAsBoolean();
		if (questionJson.has("strictSpelling")) q.strictSpelling = questionJson.get("strictSpelling").getAsBoolean();
		if (questionJson.has("hints")) {
			List<String> hints = new ArrayList<>();
			JsonArray hintsJson = questionJson.getAsJsonArray("hints");
			for (JsonElement hint : hintsJson) hints.add(hint.getAsString());
			q.hint = String.join("||", hints);
		}
		return q;
	}

	private List<Question> selectQuestions(Long conceptId,int count) {
		Random rand = new Random();
		List<Question> candidates = new ArrayList<>();
		if (conceptId != null) {
			candidates.addAll(loadConceptQuestions(conceptId));
		} else {
			List<Concept> concepts = loadConcepts();
			Collections.shuffle(concepts, rand);
			for (Concept concept : concepts) {
				if (concept.id == null) continue;
				List<Question> conceptQuestions = loadConceptQuestions(concept.id);
				Collections.shuffle(conceptQuestions, rand);
				int nConceptQuestions = Math.min(2, conceptQuestions.size());
				candidates.addAll(conceptQuestions.subList(0, nConceptQuestions));
				if (candidates.size() >= count * 6) break;
			}
		}
		Collections.shuffle(candidates, rand);
		if (candidates.size() > count) return new ArrayList<>(candidates.subList(0, count));
		return candidates;
	}

	private List<Question> loadConceptQuestions(Long conceptId) {
		List<Question> eligible = new ArrayList<>();
		List<Question> active = new ArrayList<>();
		for (String assignmentType : VISITOR_ASSIGNMENT_TYPES) {
			List<Question> questions = ofy().load().type(Question.class).filter("conceptId", conceptId).filter("assignmentType", assignmentType).list();
			for (Question q : questions) {
				if (!isVisitorQuestion(q)) continue;
				eligible.add(q);
				if (q.isActive) active.add(q);
			}
		}
		return active.isEmpty()?eligible:active;
	}

	private List<Question> selectSampleQuestions(Long conceptId,int count) {
		List<Question> samples = sampleQuestions();
		if (conceptId != null) samples.removeIf(q -> !conceptId.equals(q.conceptId));
		Collections.shuffle(samples, new Random());
		if (samples.size() > count) return new ArrayList<>(samples.subList(0, count));
		return samples;
	}

	private Question sampleQuestion(long questionId) {
		JsonObject library = loadSampleQuestionLibrary();
		for (Question q : sampleQuestions(library, null)) if (q.id != null && q.id == questionId) return q;
		for (Question q : sampleQuestions()) if (q.id != null && q.id == questionId) return q;
		return null;
	}

	private boolean useSampleQuestions(HttpServletRequest request) {
		String serverName = request.getServerName();
		return "localhost".equals(serverName) || "127.0.0.1".equals(serverName);
	}

	private List<Concept> sampleConcepts() {
		List<Concept> concepts = new ArrayList<>();
		concepts.add(sampleConcept(-101L, "Atoms and Molecules", " 01"));
		concepts.add(sampleConcept(-102L, "Measurement and Units", " 02"));
		concepts.add(sampleConcept(-103L, "Atomic Structure", " 03"));
		concepts.add(sampleConcept(-104L, "Periodic Trends", " 04"));
		concepts.add(sampleConcept(-105L, "Ions and Ionic Compounds", " 05"));
		concepts.add(sampleConcept(-106L, "Molecular Compounds", " 06"));
		concepts.add(sampleConcept(-107L, "Chemical Reactions", " 07"));
		concepts.add(sampleConcept(-108L, "Stoichiometry", " 08"));
		concepts.add(sampleConcept(-109L, "Aqueous Solutions", " 09"));
		concepts.add(sampleConcept(-110L, "Thermochemistry", " 10"));
		concepts.add(sampleConcept(-111L, "Electronic Structure", " 11"));
		concepts.add(sampleConcept(-112L, "Chemical Bonding", " 12"));
		concepts.add(sampleConcept(-113L, "Molecular Geometry", " 13"));
		concepts.add(sampleConcept(-114L, "Gases", " 14"));
		concepts.add(sampleConcept(-115L, "Liquids and Solids", " 15"));
		concepts.add(sampleConcept(-116L, "Solutions and Colligative Properties", " 16"));
		concepts.add(sampleConcept(-117L, "Chemical Kinetics", " 17"));
		concepts.add(sampleConcept(-118L, "Chemical Equilibrium", " 18"));
		concepts.add(sampleConcept(-119L, "Acids and Bases", " 19"));
		concepts.add(sampleConcept(-120L, "Solubility Equilibria", " 20"));
		concepts.add(sampleConcept(-121L, "Thermodynamics", " 21"));
		concepts.add(sampleConcept(-122L, "Electrochemistry", " 22"));
		concepts.add(sampleConcept(-123L, "Nuclear Chemistry", " 23"));
		concepts.add(sampleConcept(-124L, "Organic Chemistry", " 24"));
		return concepts;
	}

	private Concept sampleConcept(long id,String title,String orderBy) {
		Concept c = new Concept(title, orderBy);
		c.id = id;
		return c;
	}

	private List<Question> sampleQuestions() {
		List<Question> questions = new ArrayList<>();
		questions.add(withHints(sampleMultipleChoice(-1001L, -101L,
				"Which particle has a negative electric charge?",
				List.of("proton", "neutron", "electron", "nucleus"), "c"),
				"Look for the subatomic particle that moves around the nucleus.",
				"Protons are positive, neutrons are neutral, and electrons are negative."));
		questions.add(withHints(sampleNumeric(-1002L, -102L,
				"How many milliliters are in 2.50 L?", "2500", "mL"),
				"Use 1 L = 1000 mL.",
				"Multiply 2.50 by 1000."));
		questions.add(withHints(sampleMultipleChoice(-1003L, -103L,
				"Which subatomic particle determines the identity of an element?",
				List.of("electron", "neutron", "proton", "photon"), "c"),
				"An element's atomic number is the count you need.",
				"Atomic number equals the number of protons."));
		questions.add(withHints(sampleMultipleChoice(-1004L, -104L,
				"Which element has the larger atomic radius?",
				List.of("F", "Cl", "Br", "I"), "d"),
				"Atomic radius increases down a group.",
				"Iodine is lowest in this halogen list."));
		questions.add(withHints(sampleMultipleChoice(-1005L, -105L,
				"What is the formula of magnesium chloride?",
				List.of("MgCl", "MgCl2", "Mg2Cl", "Mg2Cl2"), "b"),
				"Magnesium forms Mg2+ and chloride forms Cl-.",
				"Two chloride ions are needed to balance one magnesium ion."));
		questions.add(withHints(sampleMultipleChoice(-1006L, -106L,
				"Which molecule is carbon dioxide?",
				List.of("CO", "CO2", "C2O", "C2O2"), "b"),
				"The prefix di- means two.",
				"Carbon dioxide has one carbon atom and two oxygen atoms."));
		questions.add(withHints(sampleMultipleChoice(-1007L, -107L,
				"In the balanced equation 2 H2 + O2 -> 2 H2O, what coefficient belongs in front of H2O?",
				List.of("1", "2", "3", "4"), "b"),
				"Count oxygen atoms on each side.",
				"One O2 molecule provides two oxygen atoms, so two water molecules are formed."));
		questions.add(withHints(sampleSelectMultiple(-1008L, -107L,
				"Which observations commonly indicate that a chemical reaction has occurred?",
				List.of("gas bubbles form", "a precipitate forms", "mass disappears", "temperature changes"), "abd"),
				"Look for signs that new substances formed.",
				"Mass is conserved, so it should not disappear."));
		questions.add(withHints(sampleNumeric(-1009L, -108L,
				"How many moles are in 18.0 g of H2O?", "1.00", "mol"),
				"The molar mass of water is 18.0 g/mol.",
				"moles = mass divided by molar mass."));
		questions.add(withHints(sampleTrueFalse(-1010L, -109L,
				"A strong electrolyte dissociates extensively into ions in water.", true),
				"Electrolytes conduct because ions move in solution.",
				"Strong means nearly complete dissociation."));
		questions.add(withHints(sampleMultipleChoice(-1011L, -110L,
				"An exothermic process has which sign for enthalpy change?",
				List.of("positive", "negative", "zero", "undefined"), "b"),
				"Exothermic processes release heat.",
				"Released heat leaves the system, so delta H is negative."));
		questions.add(withHints(sampleMultipleChoice(-1012L, -111L,
				"How many electrons can fit in a single orbital?",
				List.of("1", "2", "6", "8"), "b"),
				"Use the Pauli exclusion principle.",
				"Two electrons can share an orbital if their spins are opposite."));
		questions.add(withHints(sampleMultipleChoice(-1013L, -112L,
				"What type of bond forms when electrons are shared?",
				List.of("ionic", "covalent", "metallic", "hydrogen"), "b"),
				"Shared electrons are typical of molecular compounds.",
				"Covalent bonds involve sharing electron pairs."));
		questions.add(withHints(sampleMultipleChoice(-1014L, -113L,
				"What is the electron-domain geometry around carbon in CH4?",
				List.of("linear", "trigonal planar", "tetrahedral", "octahedral"), "c"),
				"Count the electron domains around carbon.",
				"Four bonding domains give a tetrahedral arrangement."));
		questions.add(withHints(sampleNumeric(-1015L, -114L,
				"What volume does 1.00 mol of an ideal gas occupy at STP?", "22.4", "L"),
				"Recall the molar volume of an ideal gas at STP.",
				"At STP, 1 mol occupies 22.4 L."));
		questions.add(withHints(sampleMultipleChoice(-1016L, -115L,
				"Which intermolecular force is present in all substances?",
				List.of("London dispersion", "hydrogen bonding", "ion-dipole", "covalent bonding"), "a"),
				"Even nonpolar atoms and molecules attract one another weakly.",
				"London dispersion forces arise from temporary electron fluctuations."));
		questions.add(withHints(sampleMultipleChoice(-1017L, -116L,
				"Adding a nonvolatile solute to a solvent usually does what to the boiling point?",
				List.of("lowers it", "raises it", "does not change it", "sets it to 0 C"), "b"),
				"Think colligative properties.",
				"Boiling point elevation raises the boiling point."));
		questions.add(withHints(sampleMultipleChoice(-1018L, -117L,
				"What happens to reaction rate when temperature is usually increased?",
				List.of("it decreases", "it increases", "it becomes zero", "it is unrelated"), "b"),
				"Higher temperature means particles have more kinetic energy.",
				"More particles can overcome activation energy."));
		questions.add(withHints(sampleTrueFalse(-1019L, -118L,
				"At equilibrium, forward and reverse reaction rates are equal.", true),
				"Equilibrium is dynamic, not stopped.",
				"Equal rates keep concentrations constant."));
		questions.add(withHints(sampleFillInWord(-1020L, -119L,
				"A substance that donates H+ ions in water is called a(n) ________.", "acid"),
				"Use the Arrhenius acid definition.",
				"Acids increase the concentration of H+ in water."));
		questions.add(withHints(sampleNumeric(-1021L, -119L,
				"What is the pH of a solution with [H+] = 1.0E-3 M?", "3.0", ""),
				"pH = -log[H+].",
				"-log(1.0E-3) equals 3.0."));
		questions.add(withHints(sampleMultipleChoice(-1022L, -120L,
				"What happens when Qsp is greater than Ksp?",
				List.of("more solute dissolves", "a precipitate forms", "nothing can happen", "the solution is unsaturated"), "b"),
				"Compare the ion product with the solubility limit.",
				"If Qsp exceeds Ksp, ions must leave solution as a solid."));
		questions.add(withHints(sampleMultipleChoice(-1023L, -121L,
				"A spontaneous process at constant temperature and pressure has which sign for delta G?",
				List.of("positive", "negative", "zero only", "always positive infinity"), "b"),
				"Use Gibbs free energy as the spontaneity criterion.",
				"Spontaneous processes have delta G less than zero."));
		questions.add(withHints(sampleMultipleChoice(-1024L, -122L,
				"Oxidation is best described as what?",
				List.of("gain of electrons", "loss of electrons", "gain of protons", "loss of neutrons"), "b"),
				"Remember OIL RIG.",
				"Oxidation Is Loss of electrons."));
		questions.add(withHints(sampleMultipleChoice(-1025L, -123L,
				"What particle is emitted in beta-minus decay?",
				List.of("electron", "proton", "neutron", "alpha particle"), "a"),
				"Beta-minus decay converts a neutron into a proton.",
				"An electron is emitted in beta-minus decay."));
		questions.add(withHints(sampleMultipleChoice(-1026L, -124L,
				"Which formula represents methane?",
				List.of("CH4", "C2H6", "CH3OH", "CO2"), "a"),
				"Methane is the simplest alkane.",
				"Alkanes follow CnH2n+2; for n=1, the formula is CH4."));
		return questions;
	}

	private Question withHints(Question q,String... hints) {
		q.hint = String.join("||", hints);
		return q;
	}

	private Question sampleMultipleChoice(long id,long conceptId,String text,List<String> choices,String correctAnswer) {
		Question q = sampleQuestionBase(id, conceptId, Question.MULTIPLE_CHOICE, text, correctAnswer);
		q.nChoices = choices.size();
		q.choices = new ArrayList<>(choices);
		return q;
	}

	private Question sampleTrueFalse(long id,long conceptId,String text,boolean correctAnswer) {
		return sampleQuestionBase(id, conceptId, Question.TRUE_FALSE, text, correctAnswer?"true":"false");
	}

	private Question sampleSelectMultiple(long id,long conceptId,String text,List<String> choices,String correctAnswer) {
		Question q = sampleQuestionBase(id, conceptId, Question.SELECT_MULTIPLE, text, correctAnswer);
		q.nChoices = choices.size();
		q.choices = new ArrayList<>(choices);
		return q;
	}

	private Question sampleFillInWord(long id,long conceptId,String text,String correctAnswer) {
		Question q = sampleQuestionBase(id, conceptId, Question.FILL_IN_WORD, text, correctAnswer);
		q.strictSpelling = false;
		return q;
	}

	private Question sampleNumeric(long id,long conceptId,String text,String correctAnswer,String units) {
		Question q = sampleQuestionBase(id, conceptId, Question.NUMERIC, text, correctAnswer);
		q.requiredPrecision = 2.0;
		q.significantFigures = 0;
		q.tag = units;
		return q;
	}

	private Question sampleQuestionBase(long id,long conceptId,int type,String text,String correctAnswer) {
		Question q = new Question(type);
		q.id = id;
		q.conceptId = conceptId;
		q.assignmentType = "Quiz";
		q.text = text;
		q.correctAnswer = correctAnswer;
		q.pointValue = 1;
		q.parameterString = "";
		q.hint = "";
		q.solution = "";
		q.tag = "";
		q.nChoices = 0;
		q.requiredPrecision = 0.0;
		q.significantFigures = 0;
		q.isActive = true;
		return q;
	}

	private boolean isVisitorQuestion(Question q) {
		if (q == null || q.id == null || q.type == null || q.assignmentType == null || q.hasNoCorrectAnswer()) return false;
		if (!"Quiz".equals(q.assignmentType) && !"Homework".equals(q.assignmentType)) return false;
		int questionType = q.getQuestionType();
		return questionType > 0 && questionType < 6;
	}

	private List<Concept> loadConcepts() {
		List<Concept> allConcepts = ofy().load().type(Concept.class).order("orderBy").list();
		List<Concept> publicConcepts = new ArrayList<>();
		for (Concept c : allConcepts) if (isPublicConcept(c)) publicConcepts.add(c);
		return publicConcepts;
	}

	private boolean isPublicConcept(Concept c) {
		if (c == null || c.id == null || c.title == null || c.title.isBlank()) return false;
		return c.orderBy == null || !c.orderBy.startsWith(" 0");
	}

	private Map<Long, Concept> conceptMap(List<Concept> concepts) {
		Map<Long, Concept> conceptMap = new HashMap<>();
		for (Concept c : concepts) if (c.id != null) conceptMap.put(c.id, c);
		return conceptMap;
	}

	private String conceptTitle(Map<Long,Concept> conceptMap,Long conceptId) {
		if (conceptId == null) return "";
		Concept c = conceptMap.get(conceptId);
		return c == null || c.title == null?"":c.title;
	}

	private int requestedQuestionCount(HttpServletRequest request) {
		int nQuestions = (int) parseLong(request.getParameter("n"), DEFAULT_QUESTION_COUNT);
		if (nQuestions < 1) return 1;
		return Math.min(nQuestions, MAX_QUESTION_COUNT);
	}

	private Long requestedConceptId(HttpServletRequest request) {
		try {
			String value = request.getParameter("ConceptId");
			if (value == null || value.isBlank()) return null;
			long conceptId = Long.parseLong(value);
			return conceptId == 0L?null:conceptId;
		} catch (Exception e) {
			return null;
		}
	}

	private String newQuestionUrl(Long conceptId,int count) {
		String url = "/example-questions?n=" + Math.max(1, Math.min(count, MAX_QUESTION_COUNT));
		if (conceptId != null) url += "&ConceptId=" + conceptId;
		return url;
	}

	private long parseLong(String value,long defaultValue) {
		try {
			return Long.parseLong(value);
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private String orderResponses(String[] answers) {
		if (answers == null || answers.length == 0) return "";
		Arrays.sort(answers);
		String studentAnswer = "";
		for (String answer : answers) if (answer != null) studentAnswer += answer;
		return studentAnswer;
	}

	private String html(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	private String studentAnswerHtml(String s) {
		if (s == null) return "";
		return s.replace("<", "&lt;").replace(">", "&gt;");
	}
}
