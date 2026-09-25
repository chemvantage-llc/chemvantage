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

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class HWTransaction implements Serializable {
	@Serial
	private static final long serialVersionUID = 137L;
	@Id		Long id;
	@Index	long questionId;
	@Index	long topicId;
	@Index	String userId;
	@Index	Date graded;
	@Index	double score;
	@Index	long assignmentId;
	@Index	Date presented;
			int possibleScore;
			String topicTitle;
			String showWork;
			String studentAnswer;
			String correctAnswer;
			boolean scoreOverride;
	
    HWTransaction() {}
    
	HWTransaction(long questionId,String hashedId,Date presented,Date graded,double score,long assignmentId,int possibleScore,String showWork) {
    	this.questionId = questionId;
    	this.userId = hashedId;
        this.presented = presented;
        this.graded = graded;
        this.score = score;
        this.assignmentId = assignmentId;
        this.possibleScore = possibleScore;
        this.showWork = showWork;
    }

}
