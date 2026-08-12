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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

@Entity
public class Score {    // this object represents a best score achieved by a user on a quiz or homework
	@Id	Long assignmentId;      // from the datastore.
	@Parent Key<User> owner;
	@Index	boolean lisReportComplete;
			int score;
			int maxPossibleScore;
			int numberOfAttempts;
	@Index	Date mostRecentAttempt;
	
	Score() {
		lisReportComplete=false;
		score = 0;
		maxPossibleScore = 0;
		numberOfAttempts = 0;
		mostRecentAttempt = null;
	}	
	
	public static Score getInstance(String userId,Assignment a) {
		String hashedId = Subject.hashId(userId);
		Score s = new Score();
		s.assignmentId = a.id;
		s.owner = key(User.class,hashedId);
		
		switch (a.assignmentType) {
		case "Quiz":
			List<QuizTransaction> quizTransactions = ofy().load().type(QuizTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			for (QuizTransaction qt : quizTransactions) {
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				s.score = (qt.score>s.score?qt.score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || qt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = qt.downloaded;
					s.maxPossibleScore = qt.possibleScore;
				}
			}
			break;
		case "Sage":
			SageTransaction sgt = ofy().load().type(SageTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).first().now();
			s.score = 0;
			for (Long conceptId : a.conceptIds) {
				int j = sgt.conceptIds.indexOf(conceptId);
				s.score += j==-1?0:sgt.scores[j];  // add scores for all concepts in the assignment
			}
			s.maxPossibleScore = 100*a.conceptIds.size();
			s.mostRecentAttempt = new Date();

			break;
		case "Homework":
			List<HWTransaction> hwTransactions = ofy().load().type(HWTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			List<Key<Question>> assignmentQuestionKeys = new ArrayList<Key<Question>>();
			assignmentQuestionKeys.addAll(a.questionKeys);  // clones the assignment List of question keys
			
			// Check a.questionKeys for possible inclusion of deleted question
			Map<Key<Question>, Question> qmap = ofy().load().keys(a.questionKeys);
			if (a.questionKeys.size() != qmap.size()) {
				a.questionKeys = new ArrayList<Key<Question>>(qmap.keySet());
				ofy().save().entity(a);
			}
			s.maxPossibleScore = a.questionKeys.size();
			for (HWTransaction ht : hwTransactions) {				
				s.numberOfAttempts++;
				if (ht.score > 0 && assignmentQuestionKeys.remove(key(Question.class,ht.questionId))) s.score++; 
				if (s.mostRecentAttempt == null || ht.graded.after(s.mostRecentAttempt)) s.mostRecentAttempt = ht.graded;  // most recent transaction
			}
			break;
		case "PracticeExam":
			List<PracticeExamTransaction> practiceExamTransactions = ofy().load().type(PracticeExamTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			for (PracticeExamTransaction pt : practiceExamTransactions) {
				if (pt.graded==null) continue;
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				int score = pt.getScore();
				s.score = (score>s.score?score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || pt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = pt.downloaded;
					s.maxPossibleScore = pt.getPossibleScore();
				}				
			}			
			break;
		case "PlacementExam":
			List<PlacementExamTransaction> placementExamTransactions = ofy().load().type(PlacementExamTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			for (PlacementExamTransaction pt : placementExamTransactions) {
				if (pt.graded==null) continue;
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				int score = 0;
				for (int i=0;i<pt.scores.length;i++) score += pt.scores[i];
				s.score = (score>s.score?score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || pt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = pt.downloaded;
					int possibleScore = 0;
					for (int i=0;i<pt.possibleScores.length;i++) possibleScore += pt.possibleScores[i];
					s.maxPossibleScore = possibleScore;
				}				
			}			
			break;
		case "VideoQuiz":
			List<VideoTransaction> videoTransactions = ofy().load().type(VideoTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			for (VideoTransaction vt : videoTransactions) {
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				s.score = (vt.score>s.score?vt.score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || vt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = vt.downloaded;
					s.maxPossibleScore = vt.possibleScore;
				}
			}		
			break;
		case "Poll":
			List<PollTransaction> pollTransactions = ofy().load().type(PollTransaction.class).filter("assignmentId",a.id).filter("userId",hashedId).list();
			for (PollTransaction pt : pollTransactions) {
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				s.score = (pt.score>s.score?pt.score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || pt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = pt.downloaded;
					s.maxPossibleScore = pt.possibleScore;
				}
			}
			break;
		case "SmartText":
			List<STTransaction> stTransactions = ofy().load().type(STTransaction.class).filter("userId",hashedId).filter("assignmentId",a.id).list();
			for (STTransaction st : stTransactions) {
				if (st.graded==null) continue;  // skip transactions that have not yet been graded
				s.numberOfAttempts++;  // number of pre-deadline quiz attempts
				if (s.mostRecentAttempt==null || st.graded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = st.graded;
					s.maxPossibleScore = 0;
					s.score = 0;
					for (int i=0;i<st.scores.length;i++) {
						s.score += st.scores[i];
						s.maxPossibleScore += st.possibleScores[i];
					}
				}
			}
			break;	
		}
		
		if (s.score > s.maxPossibleScore) s.score = s.maxPossibleScore;  // max really is the limit for LTI reporting
		return s;
	}
		
	public static Score updateQuizScore(String userId,QuizTransaction qt) {
		Score s = null;
		userId = Subject.hashId(userId);
		try {
			Key<Score> k = key(key(User.class,userId),Score.class,qt.assignmentId);
			s = ofy().load().key(k).safe();
			if (s.mostRecentAttempt.equals(qt.downloaded)) return s; // everything is up to date
			else if (s.mostRecentAttempt.before(qt.downloaded)) {  // update the Score with this new QuizTransaction
				s.numberOfAttempts = ofy().load().type(QuizTransaction.class).filter("userId",userId).filter("assignmentId",qt.assignmentId).count();
				s.score = (qt.score>s.score?qt.score:s.score);  // keep the best (max) score
				if (s.mostRecentAttempt==null || qt.downloaded.after(s.mostRecentAttempt)) {  // this transaction is the most recent so far
					s.mostRecentAttempt = qt.downloaded;
					s.maxPossibleScore = qt.possibleScore;
				}
				s.lisReportComplete = false;
			} else throw new Exception(); // this is not the newest QuizTransaction, so recalculate the Score from scratch
		} catch (Exception e) {
			Assignment a = ofy().load().type(Assignment.class).id(qt.assignmentId).safe();
			s = Score.getInstance(userId, a);
		}
		ofy().save().entity(s);
		return s;
	}
	
	public String getScore() {
		return numberOfAttempts>0?Integer.toString(score):"-";
	}
	
	public double getPctScore() {
		if (maxPossibleScore>0) return Math.round(1000.*score/maxPossibleScore)/10.;
		else return 0.;
	}
	
	public boolean needsLisReporting() {
		try {
			Assignment a = ofy().load().type(Assignment.class).id(this.assignmentId).safe();
			if (!lisReportComplete && a.lti_ags_lineitem_url != null) return true;
		} catch (Exception e) {
			ofy().delete().entity(this);
		}
		return false;
	}
	
    boolean equals(Score s) {
    	return s.score == this.score
    			&& s.assignmentId == this.assignmentId
    			&& s.owner.equals(this.owner)
    			&& s.score == this.score
    			&& s.maxPossibleScore == this.maxPossibleScore
    			&& s.numberOfAttempts == this.numberOfAttempts
    			&& s.mostRecentAttempt.equals(s.mostRecentAttempt);
    }
}