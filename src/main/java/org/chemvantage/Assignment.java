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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Assignment implements java.lang.Cloneable {
	@Id 	Long id;
	@Index	public String domain;
	@Index	public String assignmentType;
	@Index	long topicId;
	@Index	String resourceLinkId;
	@Index 	Date created;
	public	long videoId;    // used only for video assignments
	public	Integer timeAllowed; // to complete assignment, in seconds default = 900 for Quiz, 3600 for PracticeExam
	public 	Integer attemptsAllowed;
	@Index	public 	String lti_ags_lineitems_url;
	@Index	public	String lti_ags_lineitem_url;
	public	String lti_nrps_context_memberships_url;
	@Index	Date valid;
			String title;
			Long textId = null;;			// textId and chapterNumber are used to specify a SmartText assignment
			int chapterNumber;
			boolean pollIsClosed=false;
			Date pollClosesAt = null;
			String password;
			boolean scoreWork = false;
			List<Long> conceptIds = new ArrayList<Long>();
			List<Long> topicIds = new ArrayList<Long>(); // used for practice exams which have multiple topicIds
			List<String> resourceLinkIds = new ArrayList<String>();  // deprecated
			List<Key<Question>> questionKeys = new ArrayList<Key<Question>>();

	Assignment() {}

	Assignment(String assignmentType, String platform_deployment_id) {
		this.assignmentType = assignmentType;
		this.domain = platform_deployment_id;
		this.created = new Date();
	}
	
	public long getTopicId() {
		return topicId;
	}
	
	public List<Key<Question>> getQuestionKeys() {
		return questionKeys==null?new ArrayList<Key<Question>>():this.questionKeys;
	}

	void updateConceptQuestions(User user, HttpServletRequest request) {
		if (!user.isInstructor()) return;
		List<Key<Question>> qkeys = null;
		Long conceptId = null;
		try {
			conceptId = Long.parseLong(request.getParameter("ConceptId"));
			qkeys = ofy().load().type(Question.class).filter("assignmentType",this.assignmentType).filter("conceptId",conceptId).keys().list();
		} catch (Exception e) {
			qkeys = ofy().load().type(Question.class).filter("assignmentType","Custom").filter("authorId",user.getId()).keys().list();
		}
		this.questionKeys.removeAll(qkeys);  // remove any existing question keys for this conceptId
		String[] questionIds = request.getParameterValues("QuestionId");
		if (questionIds != null) {
			for (String id : questionIds) {  // eliminate any duplicate question keys
				try {
					Key<Question> k = key(Question.class,Long.parseLong(id));
					if (!this.questionKeys.contains(k)) this.questionKeys.add(k);
				} catch (Exception e) {}
			}
		} else {
			// no questions selected for this conceptId
			try {
				Text text = ofy().load().type(Text.class).id(this.textId).now();
				Chapter chapter = text.getChapterByNumber(this.chapterNumber);
			if (conceptId != null && !chapter.conceptIds.contains(conceptId)) this.conceptIds.remove(conceptId);
			} catch (Exception e) {
				if (conceptId != null) this.conceptIds.remove(conceptId);
			}
		}
		// Create a Task to validate the question items for this assignment asynchronously, since there may be many questions and it can take a long time to validate them all
		validateQuestionItemsWithAI();
		
		ofy().save().entity(this).now();	
	}
	
	void updateQuestions(HttpServletRequest request) {
		try {
			String[] questionIds = request.getParameterValues("QuestionId");
			this.questionKeys.clear();
			if (questionIds != null) 
				for (String id : questionIds) {  // eliminate any duplicate question keys
					try {
					Key<Question> k = key(Question.class,Long.parseLong(id));
					if (!this.questionKeys.contains(k)) this.questionKeys.add(k);
					} catch (Exception e) {}
				}
			List<Question> questions = new ArrayList<Question>(ofy().load().keys(this.questionKeys).values());
			this.conceptIds.clear();
			for (Question q : questions) if (q.conceptId!=null && q.conceptId>0 && !conceptIds.contains(q.conceptId)) conceptIds.add(q.conceptId);
			ofy().save().entity(this).now();	
		} catch (Exception e) {}
	}

	boolean equivalentTo(Assignment a) {
		// This method compares another Assignment to this one and ensures that all fields are either the same or both null
		// except for primitive (long) fields, which may not be null and must be equal (possibly 0L).
		if (a==null) return false;
		
		return	((this.id != null && this.id.equals(a.id)) 																								|| (a.id == null && this.id == null)) &&
				((this.domain != null && this.domain.equals(a.domain)) 																					|| (a.domain == null && this.domain == null)) &&
				((this.assignmentType != null && this.assignmentType.equals(a.assignmentType)) 															|| (a.assignmentType == null && this.assignmentType == null)) &&
				(this.topicId == a.topicId) &&
				(this.videoId == a.videoId) &&
				((this.resourceLinkId != null && this.resourceLinkId.equals(a.resourceLinkId)) 															|| (a.resourceLinkId == null && this.resourceLinkId == null)) &&
				((this.lti_ags_lineitems_url != null && this.lti_ags_lineitems_url.equals(a.lti_ags_lineitems_url)) 									|| (a.lti_ags_lineitems_url == null && this.lti_ags_lineitems_url == null)) &&
				((this.lti_ags_lineitem_url != null && this.lti_ags_lineitem_url.equals(a.lti_ags_lineitem_url)) 										|| (a.lti_ags_lineitem_url == null && this.lti_ags_lineitem_url == null)) &&
				((this.lti_nrps_context_memberships_url != null && this.lti_nrps_context_memberships_url.equals(a.lti_nrps_context_memberships_url)) 	|| (a.lti_nrps_context_memberships_url == null && this.lti_nrps_context_memberships_url == null)) &&
				((this.topicIds != null && this.topicIds.equals(a.topicIds)) 																			|| (a.topicIds == null && this.topicIds == null)) &&
				((this.questionKeys != null && this.questionKeys.equals(a.questionKeys)) 																|| (a.questionKeys == null && this.questionKeys == null)) &&
				(this.valid != null && a.valid != null && this.valid.getTime()-a.valid.getTime()<2635200000L);	// less than 1 month since last validation		
	}
	
	boolean isValid() {
		// this method verifies that the Assignment has a valid assignmentType and appropriate topicId(s)
		if (this.assignmentType==null) return false;
		
		switch (this.assignmentType) {
		case "Quiz":
		case "Homework":
		case "PracticeExam":
		case "PlacementExam": 
			return !questionKeys.isEmpty() || !conceptIds.isEmpty();
		case "SmartText":
			return textId!=null && chapterNumber!=0;
		case "VideoQuiz": 
			return videoId>0;
		case "Sage":
		case "Poll": 
			return true;
		default: return false;
		}
	}
	
	void validateQuestionItemsWithAI() {
		try {
			Utilities.createTask("/Edit", "UserRequest=ValidateAssignmentWithAI&AssignmentId="+this.id, 10);
		} catch (IOException e) {
			System.err.println("Failed to create task for AI validation: " + e.getMessage());
		}
	}

	protected Assignment clone() throws CloneNotSupportedException {
		return (Assignment) super.clone();
	}

}