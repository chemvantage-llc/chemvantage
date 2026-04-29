package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static com.googlecode.objectify.ObjectifyService.key;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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

@WebServlet("/SmartText")
public class SmartText extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();

        try {
            User user = User.getUser(request.getParameter("sig"));
            if (user==null) throw new Exception("User token expired.");

            Assignment a = null;
            try {
                a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).safe();
            } catch (Exception e) {
            	Text text = ofy().load().type(Text.class).first().now();
				a = new Assignment("SmartText",null);
				a.textId = text.id;
				a.chapterNumber = 1;
				for (Chapter ch : text.chapters) {
					if (ch.chapterNumber == a.chapterNumber) {
						a.title = ch.title;
						for (Long conceptId : ch.conceptIds) {
							a.conceptIds.add(conceptId);
							List<Key<Question>> conceptQuestionKeys = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",conceptId).keys().list();
							if (!conceptQuestionKeys.isEmpty()) a.questionKeys.addAll(conceptQuestionKeys);
						}
						break;
					}
				}
				a.valid = new Date();
			}
            
            String userRequest = request.getParameter("UserRequest");
            if (userRequest==null) userRequest = "";

            switch (userRequest) {
            case "ReviewScores":
                out.println(Subject.header() + reviewScores(user,a) + Subject.footer);
                return;
            default:
            }

            out.println(Subject.header("ChemVantage Key Concept Question") + printQuestion(user,a) + Subject.footer);

        } catch (Exception e) {
            out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
        }
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();

        try {
            User user = User.getUser(request.getParameter("sig"));
            if (user==null) throw new Exception("Invalid token (may have expired after 90 minutes).");

            long aId = user.getAssignmentId();       
            Assignment a = aId==0?null:ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();

            String userRequest = request.getParameter("UserRequest");
            if (userRequest==null) userRequest = "";

            switch (userRequest) {
            case "GradeQuestion":
                out.println(Subject.header("ChemVantage Key Concept Response") + printScore(user,a,request) + Subject.footer);
                break;
            case "UpdateConcepts":
                if (user.isInstructor() && a != null) {
                    try {
                        a.conceptIds.clear();
                        String[] conceptIdParams = request.getParameterValues("ConceptId");
                        if (conceptIdParams != null) {
                            for (String cIdStr : conceptIdParams) {
                                try {
                                    Long conceptId = Long.parseLong(cIdStr);
                                    a.conceptIds.add(conceptId);
                                } catch (NumberFormatException e) {}
                            }
                        }
                        ofy().save().entity(a).now();
                    } catch (Exception e) {}
                }
                out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a,"Changes saved successfully.") + Subject.footer);
                break;
            case "Synchronize Scores":
                if (synchronizeScores(user,a,request)) out.println(Subject.header("ChemVantage Instructor Page") + instructorPage(user,a) + Subject.footer);
                else out.println("Synchronization request failed.");
                break;
            default:
            }

        } catch (Exception e) {
            out.println(Subject.header() + Logout.now(request,e) + Subject.footer);
        }
    }

	static String instructorPage(User user,Assignment a) {
        return instructorPage(user,a,"");
    }

    static String instructorPage(User user,Assignment a,String msg) {
        if (!user.isInstructor()) return "<h2>You must be logged in as an instructor to view this page</h2>";

        StringBuilder buf = new StringBuilder();
        try {
            buf.append(Subject.privacyPolicyBanner());
            
            if (a==null) return "Sorry, we were unable to find the assignment.";
            Text text = ofy().load().type(Text.class).id(a.textId).now();
            Chapter chapter = null;
            for (Chapter ch : text.chapters) {
                if (ch.chapterNumber == a.chapterNumber) {
                    chapter = ch;
                    break;
                }
            }
            if (chapter==null) return "Sorry, we were unable to find the assigned chapter for this textbook.";

            buf.append("<h1>Reading Assignment</h1>");
            buf.append("<h2>Instructor Page</h2>");
            buf.append("<a href='/SmartText?sig=" + user.getTokenSignature() + "' class='btn btn-primary'>View This Assignment</a><br/><br/>");     
            buf.append("<h3>Text: " + text.title + "<br/>");
            buf.append("Chapter " + chapter.chapterNumber + ": " + chapter.title + "</h3>");
            
            buf.append("Select/unselect the key concepts to include in this assignment:<br/>");

            // Build a form with checkboxes for concepts in this chapter
            buf.append("<form method=post action=/SmartText>");
            buf.append("<input type=hidden name=sig value='" + user.getTokenSignature() + "' />");
            buf.append("<input type=hidden name=UserRequest value='UpdateConcepts' />");

            Map<Long,Concept> conceptMap = ofy().load().type(Concept.class).ids(chapter.conceptIds);

            if (chapter.conceptIds.isEmpty()) {
                buf.append("<p><b>This chapter has no concepts available.</b></p>");
            } else {
                for (Long conceptId : chapter.conceptIds) {
                    Concept c = conceptMap.get(conceptId);
                    if (c != null) {
                        buf.append("<div><label><input type=checkbox name=ConceptId value='" + conceptId + "' "
                        + (a.conceptIds.contains(conceptId)?"checked ":"") + "/> " + c.title + "</label></div>");
                    }
                }
                buf.append("<span id=successMsg>" + msg + "</span><br/>"
                + "<input type=submit value='Save Changes' class='btn btn-secondary' onclick='showSuccess()' />");
            }

            buf.append("</form><br/><br/>");

            boolean supportsMembership = a.lti_nrps_context_memberships_url != null;
            buf.append((supportsMembership?"<a href='/SmartText?UserRequest=ReviewScores&sig=" + user.getTokenSignature() + "'>Review your students' SmartText scores</a><br/>":""));
            buf.append("Need help? Please <a href=/Feedback?sig=" + user.getTokenSignature() + "&AssignmentId=" + a.id + ">submit a comment, question or request here</a>.<br/><br/>");

            buf.append("<script>"
            + "var successMsg = document.getElementById('successMsg');"
            + "setTimeout(function() { successMsg.style.display = 'none'; }, 3000);"
            + "</script>");

        } catch (Exception e) {
            buf.append("<p>Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "</p>");
        }
        return buf.toString();
    }

    static String printTextHeader(Text t,Chapter c) {
        StringBuffer buf = new StringBuffer();
        buf.append("<div style=display:table><div style='display:table-row;'><div style='display:table-cell;vertical-align:top;width:450px;padding-right:20px'>");
        buf.append("<h1>Reading Assignment</h1>");
        buf.append("Textbook: <b>" + t.title + "</b><br/>"
            + "Author: " + t.author + "<br/>"
            + "<h2>Chapter " + c.chapterNumber + ": " + c.title + "</h2>");
        buf.append("<ul>");
        if (c.url != null) buf.append("<li><a aria-label='Opens in a new tab' href='" + c.url + "' target=_blank>Read this chapter online</a></li>");
        buf.append("<li><a aria-label='Opens in a new tab' href='" + t.printCopyUrl + "' target=_blank>Order a print copy of this book</a></li>");
        buf.append("</ul>");
        buf.append("</div><div style='display:table-cell;vertical-align:top;'>");
        buf.append("<img src='" + t.imgUrl + "' alt='Textbook cover art'>");
        buf.append("</div></div></div>");

        return buf.toString();
    }

    static String printQuestion(User user,Assignment a) {
        StringBuffer buf = new StringBuffer();
        try {
            if (a==null) return "Sorry, we were unable to find the assignment.";
            Text text = ofy().load().type(Text.class).id(a.textId).now();
            Chapter chapter = null;
            for (Chapter ch : text.chapters) {
                if (ch.chapterNumber == a.chapterNumber) {
                    chapter = ch;
                    break;
                }
            }
            if (chapter==null) return "Sorry, we were unable to find the assigned chapter for this textbook.";
            buf.append(printTextHeader(text,chapter));
            buf.append("<hr>");
    
            buf.append("<h2>Key Concept Questions</h2>");
            // load the SmartText transaction entity for this user if one exists
            STTransaction st = null;
            try {
                st = ofy().load().type(STTransaction.class).filter("userId",user.hashedId).filter("assignmentId",a.id).first().safe();
                // bulletproofing in case of change in assignment
                if (!st.conceptIds.equals(a.conceptIds)) {
                    // Create new arrays for the updated assignment
                    int[] newScores = new int[a.conceptIds.size()];
                    int[] newPossibleScores = new int[a.conceptIds.size()];
                    int[] newMissedQuestions = new int[a.conceptIds.size()];
                    Arrays.fill(newPossibleScores, 2);
    
                    // Copy over scores for conceptIds that still exist
                    for (int i = 0; i < a.conceptIds.size(); i++) {
                        Long newConceptId = a.conceptIds.get(i);
                        int oldIndex = st.conceptIds.indexOf(newConceptId);
                        if (oldIndex >= 0) {
                            newScores[i] = st.scores[oldIndex];
                            newPossibleScores[i] = st.possibleScores[oldIndex];
                            newMissedQuestions[i] = st.missedQuestions[oldIndex];
                        } else { // new conceptId; initialize arrays
                            newScores[i] = 0;
                            newPossibleScores[i] = 2;
                            newMissedQuestions[i] = 0;
                        }
                    }
    
                    // Update the transaction with the new arrays
                    st.conceptIds = a.conceptIds;
                    st.scores = newScores;
                    st.possibleScores = newPossibleScores;
                    st.missedQuestions = newMissedQuestions;
                }
            } catch (Exception e) {
                st = new STTransaction(user.getHashedId(),a);
                buf.append("Complete all key concept questions to score 100% for this assignment.<br/><br/>");
            }
    
            // Count the missed questions and make a List of completed conceptIds:
            List<Long> completed = new ArrayList<Long>();
            boolean complete = true;
            for (Long cId : a.conceptIds) {
                int index = a.conceptIds.indexOf(cId);
                int qCount = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",cId).count();
                st.possibleScores[index] = qCount > 2?2:qCount;
                if (st.scores[index] >= st.possibleScores[index]) completed.add(cId);
                else complete = false;
            }
    
            if (complete) {
                buf.append("<b>This assignment is complete. Your score is 100%. You may continue just for practice.</b><br/><br/>");
                completed.clear();
            }
    
            // Create a random number generator and (possibly) seed it with st.graded to prevent question-shopping
            Random random = new Random();
            if (!complete && st.graded!=null) random.setSeed(st.graded.getTime());
    
            Question q = null;
            Long conceptId = null;
            List<Long> availableConceptIds = new ArrayList<Long>(a.conceptIds);
            if (!completed.isEmpty()) availableConceptIds.removeAll(completed);
    
            while (q==null) {
                // Randomly select a conceptId from the remaining concepts
                conceptId = availableConceptIds.get(random.nextInt(availableConceptIds.size()));
    
                // get all the question keys for the chosen conceptId
                List<Key<Question>> questionKeys = ofy().load().type(Question.class).filter("assignmentType","Quiz").filter("conceptId",conceptId).keys().list();
                
                // Remove any question keys already answered correctly and select one remaining key at random
                if (!complete) {  // only prune if the assignment is not yet complete
                    List<Key<Question>> prunedKeys = new ArrayList<Key<Question>>(questionKeys);
                    prunedKeys.removeAll(st.answeredKeys);
                    if (!prunedKeys.isEmpty()) questionKeys = prunedKeys;
                }

				if (questionKeys.isEmpty()) {
					availableConceptIds.remove(conceptId);
					if (availableConceptIds.isEmpty()) return "Sorry, there are no available questions.";
    				continue;
				}
				
                Key<Question> questionKey = questionKeys.get(random.nextInt(questionKeys.size()));
                q = ofy().load().key(questionKey).now();
            }
            // At this point we should have a valid question q.
            int p = random.nextInt();
            q.setParameters(p);
    
            buf.append("<form method=post action=/SmartText>"
                + "<input type=hidden name=sig value='" + user.getTokenSignature() + "' />"
                + "<input type=hidden name=ConceptId value='" + conceptId + "' />"
                + "<input type=hidden name=QuestionId value='" + q.id + "' />"
                + "<input type=hidden name=Parameter value='" + p + "' />"
                + "<input type=hidden name=UserRequest value='GradeQuestion' />"
                + q.print()
                + "<input aria-label='submit the answer for scoring' type=submit class='btn btn-primary' onclick=this.style.opacity=0.2; />"
                + "</form><br/>");
            st.armed = true;
            ofy().save().entity(st).now();
        } catch (Exception e) {
            buf.append("Error: " + (e.getMessage()==null?e.toString():e.getMessage()));
        }
        return buf.toString();
    }
    
    String printScore(User user, Assignment a,HttpServletRequest request) {
        StringBuffer buf = new StringBuffer();
        StringBuffer debug = new StringBuffer("Debug: ");
        try {
            long assignmentId;
            if (a==null) { // example assignment for anonymous user
                a = new Assignment();
                List<Text> texts = ofy().load().type(Text.class).list();
                for (Text t : texts) {
                    if (t.smartText) {
                        a.textId = t.id;
                        a.chapterNumber=1;
                        break;
                    }
                }
                assignmentId=0;
            } else assignmentId = a.id;
            Text text = ofy().load().type(Text.class).id(a.textId).now();
            Chapter chapter = null;
            for (Chapter ch : text.chapters) {
                if (ch.chapterNumber == a.chapterNumber) {
                    chapter = ch;
                    break;
                }
            }
            buf.append(printTextHeader(text,chapter) + "<hr>");
            buf.append("<h3>Key Concept Questions</h3>");
            long conceptId = Long.parseLong(request.getParameter("ConceptId"));
            long questionId = Long.parseLong(request.getParameter("QuestionId"));
            int p = Integer.parseInt(request.getParameter("Parameter"));
    
            Question q = ofy().load().type(Question.class).id(questionId).safe();
            q.setParameters(p);
    
            String studentAnswer = orderResponses(request.getParameterValues(Long.toString(questionId)));
            STTransaction st = ofy().load().type(STTransaction.class).filter("userId",user.getHashedId()).filter("assignmentId",assignmentId).first().now();
    
            // determine if this assignment has already been completed
            boolean completed = true;
            for (int i=0;i<st.conceptIds.size();i++) {
                if (st.scores[i]<st.possibleScores[i]) {
                    completed = false;
                    break;
                }
            }
    
            //  get the index of st.conceptIds that corresponds to the conceptId for this question:
            int index = st.conceptIds.indexOf(conceptId);
            boolean isCorrect = q.isCorrect(studentAnswer);
    
            if (!st.armed) buf.append("<b>Sorry, it looks like this question has already been scored.</b><br/>");
            else if (isCorrect) {
                if (!completed) {
                    st.scores[index]++;
                    st.answeredKeys.add(key(Question.class,q.id));
                }
                q.addAttemptSave(true);
                buf.append("<b>Congratulations! Your answer was correct.</b><br/>");
            } else {
                st.missedQuestions[index]++;
                buf.append("<b>Sorry, your answer was incorrect.</b>"
                    + " (<a href=# onClick=\"document.getElementById('correctAnswer').style='display:block';return false;\">show me</a>)<br/>");
                buf.append("<div id=correctAnswer style='display:none'><b>Here is the correct answer:</b><br/><br/>" 
                    + q.printAllToStudents(studentAnswer) + "</div><br/>");
                q.addAttemptSave(false);
            }
    
            int score = 0;
            int possibleScore = 0;
            for (int i=0; i<st.scores.length; i++) {
                score += st.scores[i];
                possibleScore += st.possibleScores[i];
            }
    
            String continueButton = "<form method=get action=/SmartText>"
                + "<input type=hidden name=UserRequest value=PrintQuestion />"
                + "<input type=hidden name=STTransactionId value=" + st.id + " />"
                + "<input type=hidden name=sig value=" + user.getTokenSignature() + " />"
                + "<button id=btn aria-label='continue to the next question' type=submit class='btn btn-primary' "
                + "onclick=this.style.opacity=0.2;>Continue to the Next Question</button></form><br/>";
    
            if (score==possibleScore) { // finished
                if (completed) {  // this was just for practice
                    buf.append(continueButton);
                } else {  // just completed for first time
                    buf.append("You have answered all of the key concept questions for this assignment. Your score is 100%.<br/><br/>");
                    buf.append(Feedback.fiveStars(user.getTokenSignature()));
                }
            } else if (st.missedQuestions[index]<2) {  // continue to the next question
                buf.append("This assignment is " + 100*score/possibleScore + "% complete.<br/><br/>");
                buf.append(continueButton);
            } else { // missed 2 questions; go back to the text
                Concept c = ofy().load().type(Concept.class).id(conceptId).safe();
                buf.append("You missed 2 questions on the key concept: <b>" + c.title + "</b>.<br/><br/>");
                //     + "Please return to the textbook and review this chapter. ");
                if (chapter != null) {
                    buf.append("<a href='" + chapter.url + "' target=_blank>"
                        + "<button style='border: none; color: white; padding: 10px 10px; margin: 4px 2px; font-size: 16px; cursor: pointer; border-radius: 10px; background-color: blue;'>"
                        + "To continue, click here to review this chapter in the textbook."
                        + "</button>"
                        + "</a><br/>");
                }
                if (completed) buf.append("Don't worry. Your score on this assignment is still 100%.<br/><br/>");
                else buf.append("Don't worry. You can still earn 100% by relaunching this assignment after completing your review.<br/><br/>");
                st.missedQuestions[index]=0; // reset the missed questions for this concept only
                ofy().delete().entity(user).now();
            }
            st.armed = false;
    
            if (!completed && !user.isAnonymous()) {
                st.graded = new Date();
                ofy().save().entity(st).now();
                Utilities.createTask("/ReportScore","AssignmentId=" + assignmentId + "&UserId=" + URLEncoder.encode(user.getId(),"UTF-8"));
            } else ofy().save().entity(st).now();
    
        } catch (Exception e) {
            buf.append("Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
        }
    
        return buf.toString();
    }
    
    static String reviewScore(User user, Assignment a, String for_user_id) {
        StringBuffer buf = new StringBuffer();
        buf.append("<h1>Reading Assignment</h1>"
            + "<h2>" + (a.title==null?"":a.title) + "</h2>");
        buf.append("Valid: " + new Date() + "<p>");
    
        // students can only view their own scores
        if (!user.isInstructor()) for_user_id = user.getId();
    
        // calculate the user's current Score for this assignment
        Score myScore = Score.getInstance(for_user_id, a);
    
        buf.append("ChemVantage Score: " + (myScore==null?"0":myScore.getPctScore() + "%<br/>"));
    
        String lmsScore = null;
        try {
            lmsScore = LTIMessage.readUserScore(a, for_user_id);
            double lmsPctScore = Double.parseDouble(lmsScore);
            if (myScore != null && Math.abs(lmsPctScore-myScore.getPctScore())<1.0) buf.append("This score is correctly recorded in your LMS grade book.<br/><br/>");
            else buf.append("The score recorded in your class LMS is " + Math.round(10.*lmsPctScore)/10. + "%.<br/>The difference may be due to "
                + "enforcement of assignment deadlines, grading policies and/or instructor discretion.<br/><br/>");
        } catch (Exception e) {
            buf.append("The score in your LMS grade book is unavailable.<br/><br/>");
        }
    
        return buf.toString();
    }
    
    static String reviewScores(User user, Assignment a) throws Exception {
        if (!user.isInstructor()) throw new Exception("Unauthorized.");
        // from here on, User is the instructor
    
        StringBuffer buf = new StringBuffer();
        buf.append("<h1>Reading Assignment</h1>"
            + "<h2>" + (a.title==null?"":a.title) + "</h2>");
        buf.append("Valid: " + new Date() + "<p>");
    
        try {
            if (a.lti_nrps_context_memberships_url==null) throw new Exception("No Names and Roles Provisioning support.");
    
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
            buf.append("<table><tr><th>&nbsp;</th><th>Name</th><th>Email</th><th>Role</th><th>LMS Score</th><th>CV Score</th></tr>");
            int i=0;
            boolean synched = true;
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
                    + "<td align=center>" + (cvScore == null?" - ":String.valueOf(cvScore.getPctScore()) + "%") + "</td>"
                    + "</tr>");
                // Flag this score set as unsynchronizde only if there is one or more non-null ChemVantage Learner score that is not equal to the LMS score
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
                    + "<form method=post action=/Homework >"
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
                Utilities.createTask("/ReportScore","AssignmentId=" + a.id + "&UserId=" + URLEncoder.encode(platform_id + entry.getKey(),"UTF-8"));
                //QueueFactory.getDefaultQueue().add(withUrl("/ReportScore").param("AssignmentId",String.valueOf(a.id)).param("UserId",URLEncoder.encode(platform_id + entry.getKey(),"UTF-8")));  // put report into the Task Queue
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }
    
    String orderResponses(String[] answers) {
        if (answers==null) return "";
        Arrays.sort(answers);
        String studentAnswer = "";
        for (String a : answers) studentAnswer = studentAnswer + a;
        return studentAnswer;
    }
}