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

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import org.springframework.web.util.HtmlUtils;

@Entity
public class UserReport implements Serializable {
	@Serial
	private static final long serialVersionUID = 137L;
	@Id 	Long id;
	@Index 	Date submitted;
			String userId;
			int stars;
			long questionId;
			int[] params;
			String studentAnswer;
			String comments = "";
			float riskScore = -1.0f;
	
	UserReport() {}
	
	UserReport(String userId,long questionId,String studentAnswer,String comments) {
		this.userId = Subject.hashId(userId);
		this.questionId = questionId;
		this.studentAnswer = studentAnswer;
		this.comments = comments;
		this.submitted = new Date();
	}
	
	UserReport(String userId,long questionId,int[] params,String studentAnswer,String comments) {
		this.userId = Subject.hashId(userId);
		this.questionId = questionId;
		this.params = params;
		this.studentAnswer = studentAnswer;
		this.comments = comments;
		this.submitted = new Date();
	}
	
	public UserReport(String userId,int stars,String comments) {
		this.userId = Subject.hashId(userId);
		this.stars = stars;
		this.comments = comments;
		this.submitted = new Date();
	}
	
	public String view() {
		StringBuffer buf = new StringBuffer();
		buf.append("On " + submitted + " a user said:<br>");

		if (stars>0) buf.append(" (" + stars + " stars)<br>");
		buf.append("<FONT COLOR=RED>" + HtmlUtils.htmlEscape(comments == null ? "" : comments) + "</FONT><br>");
		if (this.questionId>0) {			
			Question q = ofy().load().type(Question.class).id(this.questionId).safe();
			q.parameters = this.params;

			buf.append(q.printAllToStudents(studentAnswer,true,false));
		}
		return buf.toString();
	}

	public String view(User user) {
		
		if (!user.isChemVantageAdmin()) return null;
		
		StringBuffer buf = new StringBuffer();
		
		try {
			buf.append("On " + submitted + " a user said:<br>");
			
			if (stars>0) buf.append(" (" + stars + " stars)<br>");
			buf.append("<FONT COLOR=RED>" + HtmlUtils.htmlEscape(comments == null ? "" : comments) + "</FONT><br>");
			
			if (this.questionId>0) {			
				Question q = ofy().load().type(Question.class).id(this.questionId).safe();
				q.parameters = this.params;
				buf.append(q.printAllToStudents(studentAnswer,true,false));
				buf.append("<a href=Edit?UserRequest=Edit&QuestionId=" + this.questionId + "&AssignmentType=" + q.assignmentType + ">Edit Question</a>&nbsp;or&nbsp;");
			}
			buf.append("<FORM METHOD=POST style='display: inline' ACTION=Feedback>"
					+ "<INPUT TYPE=HIDDEN NAME=ReportId VALUE=" + this.id + ">"
					+ "<INPUT TYPE=SUBMIT NAME=UserRequest VALUE='Delete Report'>"
					+ "<INPUT TYPE=HIDDEN NAME=sig VALUE='" + user.getTokenSignature() + "'>"
					+ "</FORM><p>");
					
			if (riskScore >= 0) {
				buf.append("Risk Score: " + riskScore);
			}

		} catch (Exception e) {
			buf.append("<br>" + e.toString());
		}
		return buf.toString();
	}
}