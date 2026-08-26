/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2014 ChemVantage LLC
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
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.googlecode.objectify.Key;


/*
 * Access to this servlet is restricted to admin@chemvantage.org.
 */
@WebServlet("/DataStoreCleaner")
public class DataStoreCleaner extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 137L;
	private static String schedulerServiceAccount() {
		return "cloud-run-scheduler-invoker@" + Subject.getProjectId() + ".iam.gserviceaccount.com";
	}
	Date sixMonthsAgo;
	Date oneYearAgo;
	Date threeYearsAgo;
	int querySizeLimit = 100;
	
	public String getServletInfo() {
		return "ChemVantage servlet that performs monthly maintenance of the datastore.";
	}
	
	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		// Verify admin authentication
		if (!isAdminAuthenticated(request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, 
				"Admin access required");
			logSecurityEvent("UNAUTHORIZED_DATASTORE_CLEANER_ACCESS_ATTEMPT", request);
			return;
		}
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		String task = request.getParameter("Task");		
		if (task != null) doPost(request,response);
		else out.println(Subject.header("Datastore Cleaner") + interactiveMenu() + Subject.footer);		
	} 

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		// Verify admin authentication
		if (!isAdminAuthenticated(request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, 
				"Admin access required");
			logSecurityEvent("UNAUTHORIZED_DATASTORE_CLEANER_ACCESS_ATTEMPT", request);
			return;
		}
		
		// This servlet is called by the cron daemon once each month, or manually by this servlet.
		Date now = new Date();
		sixMonthsAgo = new Date(now.getTime()- 15768000000L);
		oneYearAgo = new Date(now.getTime()- 31536000000L);
		threeYearsAgo = new Date(now.getTime()- 94608000000L);

		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		StringBuffer buf = new StringBuffer(Subject.header("Datastore Cleaner"));
		buf.append("<h2>Data Store Cleaner</h2>");
		
		// This section handles individual tasks executed from the Task Queue
		String task = request.getParameter("Task");
		if (task==null) return;
		
		boolean testOnly = Boolean.parseBoolean(request.getParameter("TestOnly"));
		
		try {
		switch (task) {
		case "CleanTransactions": buf.append(cleanTransactions(testOnly,request)); break;
		case "CleanScores": buf.append(cleanScores(testOnly)); break;
		case "CleanAssignments": buf.append(cleanAssignments(testOnly,request)); break;
		//case "CleanDeployments": buf.append(cleanDeployments(testOnly)); break;
		case "CleanUsers": buf.append(cleanUsers(testOnly)); break;
		case "CleanAll":
			Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TestOnly="+testOnly);
			Utilities.createTask("/DataStoreCleaner","Task=CleanScores&TestOnly="+testOnly);
			Utilities.createTask("/DataStoreCleaner","Task=CleanAssignments&TestOnly="+testOnly);
			//Utilities.createTask("/DataStoreCleaner","Task=CleanDeployments&TestOnly="+testOnly);
			Utilities.createTask("/DataStoreCleaner","Task=CleanUsers&TestOnly="+testOnly);
			
			buf.append("5 background tasks launched to scrub all obsolete entity types from the datastore.");
			break;
		}
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		buf.append("<br>" + Subject.footer);
		
		out.println(buf.toString());
	}
	
	String interactiveMenu() {
		StringBuffer buf = new StringBuffer();
		buf.append("<h2>Data Store Cleaner</h2>");
		
		buf.append("<form method=post action=/DataStoreCleaner>");
		
		buf.append("<label><input type=radio name=TestOnly value=true checked> Test only (no deletions)</label><br>");
		buf.append("<label><input type=radio name=TestOnly value=false> Delete entities (cannot be undone)</label><p>");
		
		buf.append("Entities to be scrubbed from the datastore:<br>");
		buf.append("<label><input type=radio name=Task value=CleanTransactions> Transactions with no existing Assignment entity</label><br>");
		buf.append("<label><input type=radio name=Task value=CleanScores> Scores older than 1 year</label><br>");
		buf.append("<label><input type=radio name=Task value=CleanAssignments> Assignments unused more than 1 year with no lineitem_url</label><br>");
		buf.append("<label><input type=radio name=Task value=CleanDeployments> Deployments with no logins for more than 1 year</label><br>");
		buf.append("<label><input type=radio name=Task value=CleanUsers> Users whose tokens have expired</label><p>");
		buf.append("<label><input type=radio name=Task value=CleanAll>All of the entities above (launches background job)</label><p>");
		buf.append("<input type=submit><br>");
		buf.append("</form>");

		return buf.toString();
	}

	private String cleanTransactions(boolean testOnly, HttpServletRequest request) throws Exception {
		// this method deletes all transactions older than 1 year or have no corresponding assignment
		
		StringBuffer buf = new StringBuffer();
		buf.append("<h2>Clean Transactions</h2>");
		
		String transactionType = request.getParameter("TransactionType");
		if (transactionType==null) transactionType = "all";
		Long startId = null;
		try {
			startId = Long.parseLong(request.getParameter("StartId"));
		} catch (Exception e) {}
		
		switch (transactionType) {
		case "QuizTransaction":
			buf.append(cleanQuizTransactions(testOnly, startId));
			break;
		case "HWTransaction":
			buf.append(cleanHWTransactions(testOnly, startId));
			break;
		case "STTransaction":
			buf.append(cleanSTTransactions(testOnly, startId));
			break;
		case "PracticeExamTransaction":
			buf.append(cleanPracticeExamTransactions(testOnly, startId));
			break;
		case "VideoTransaction":
			buf.append(cleanVideoTransactions(testOnly, startId));
			break;
		case "PollTransaction":
			buf.append(cleanPollTransactions(testOnly, startId));
			break;
		case "PlacementExamTransaction":
			buf.append(cleanPlacementExamTransactions(testOnly, startId));
			break;
		default:
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=QuizTransaction", 0);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=HWTransaction", 10);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=STTransaction", 20);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=PracticeExamTransaction", 30);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=VideoTransaction", 40);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=PollTransaction", 50);
			Utilities.createTask("/DataStoreCleaner", "Task=CleanTransactions&TransactionType=PlacementExamTransaction", 60);
			buf.append("Started 7 background tasks.");
		}
		return buf.toString();
	}
	
	String cleanQuizTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<QuizTransaction>> tKeys = ofy().load().type(QuizTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(QuizTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<QuizTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(QuizTransaction.class).limit(1000).list();
		else ts = ofy().load().type(QuizTransaction.class).filterKey(">",key(QuizTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (QuizTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=QuizTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " QuizTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanHWTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<HWTransaction>> tKeys = ofy().load().type(HWTransaction.class).filter("graded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(HWTransaction.class).filter("graded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<HWTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(HWTransaction.class).limit(1000).list();
		else ts = ofy().load().type(HWTransaction.class).filterKey(">",key(HWTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (HWTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=HWTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " HWTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanSTTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<STTransaction>> tKeys = ofy().load().type(STTransaction.class).filter("created <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(STTransaction.class).filter("created <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<STTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(STTransaction.class).limit(1000).list();
		else ts = ofy().load().type(STTransaction.class).filterKey(">",key(STTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (STTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=STTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " STTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanPracticeExamTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<PracticeExamTransaction>> tKeys = ofy().load().type(PracticeExamTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(PracticeExamTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<PracticeExamTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(PracticeExamTransaction.class).limit(1000).list();
		else ts = ofy().load().type(PracticeExamTransaction.class).filterKey(">",key(PracticeExamTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (PracticeExamTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=PracticeExamTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " PracticeExamTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanVideoTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<VideoTransaction>> tKeys = ofy().load().type(VideoTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(VideoTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<VideoTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(VideoTransaction.class).limit(1000).list();
		else ts = ofy().load().type(VideoTransaction.class).filterKey(">",key(VideoTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (VideoTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=VideoTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " VideoTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanPollTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<PollTransaction>> tKeys = ofy().load().type(PollTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(PollTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<PollTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(PollTransaction.class).limit(1000).list();
		else ts = ofy().load().type(PollTransaction.class).filterKey(">",key(PollTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (PollTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=PollTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " PollTransaction entities.<br/>");
		return buf.toString();
	}
	
	String cleanPlacementExamTransactions(boolean testOnly, Long startId) throws Exception {
		StringBuffer buf = new StringBuffer();
		int count = 0;
		// Delete all transactions older than one year
		List<Key<PlacementExamTransaction>> tKeys = ofy().load().type(PlacementExamTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		while(tKeys.size()>0) {
			count += tKeys.size();
			ofy().delete().keys(tKeys).now();
			tKeys = ofy().load().type(PlacementExamTransaction.class).filter("downloaded <", oneYearAgo).limit(500).keys().list();
		}
		
		// Delete all transactions not associated with a current Assignment
		List<PlacementExamTransaction> ts = null;
		if (startId==null) ts = ofy().load().type(PlacementExamTransaction.class).limit(1000).list();
		else ts = ofy().load().type(PlacementExamTransaction.class).filterKey(">",key(PlacementExamTransaction.class,startId)).limit(1000).list();
		
		List<Long> currentAssignmentIds = new ArrayList<Long>();
		List<Long> deletedAssignmentIds = new ArrayList<Long>();	
		for (PlacementExamTransaction t : ts) {
			if (t.assignmentId==0 || deletedAssignmentIds.contains(t.assignmentId) ) {
				tKeys.add(key(t));  // slated for deletion
			} else if (currentAssignmentIds.contains(t.assignmentId)) {
				 // do nothing
			} else { // classify the new assaignmentId
				boolean exists = ofy().load().filterKey(key(Assignment.class,t.assignmentId)).count() == 1;
				if (exists) currentAssignmentIds.add(t.assignmentId);
				else {
					deletedAssignmentIds.add(t.assignmentId);
					tKeys.add(key(t));  // slated for deletion
				}
			}
			if (tKeys.size() >= 500) break;  // 
		}
		if (!testOnly) {
			ofy().delete().keys(tKeys);
			if (tKeys.size() >= 500) {
				try {
					Utilities.createTask("/DataStoreCleaner","Task=CleanTransactions&TransactionType=PlacementExamTransaction&StartId=" + ts.get(ts.size()-1).id, 60);
				} catch (Exception e) {}
			}
		}
		count += tKeys.size();
		buf.append((testOnly?"Found ":"Deleted ") + count + " PlacementExamTransaction entities.<br/>");
		return buf.toString();
	}
	
	
		
	private String cleanScores(boolean testOnly) {
		// This method deletes all Score entities older than one year

		StringBuffer buf = new StringBuffer();
		buf.append("<h2>Clean Scores</h2>");
		try {
			List<Key<Score>> keys = ofy().load().type(Score.class).filter("mostRecentAttempt <",oneYearAgo).keys().list();
			if (keys.size() > 0 && !testOnly) {  // delete all the expired keys in batches of 500 (max allowed by ofy().delete)
				int nBatches = keys.size()/500;
				for (int i=0;i<nBatches;i++) ofy().delete().keys(keys.subList(i*500, (i+1)*500));
				ofy().delete().keys(keys.subList(nBatches*500, keys.size()));
			}
			
			buf.append(keys.size() + " Scores more than one year old" + (testOnly?" identified":" deleted") + ".<br>");
			buf.append("Done.<br>");
		} catch (Exception e) {
			buf.append("Error: " + e.toString());
		}
		return buf.toString();
	}

	private String cleanAssignments(boolean testOnly, HttpServletRequest request) {
		// This method matches assignments to the current lineitem container from the LMS; validates the good and deletes the missing
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		buf.append("<h2>Clean Assignments</h2>");
		Date now = new Date();
		sixMonthsAgo = new Date(now.getTime()-15768000000L);
		oneYearAgo = new Date(now.getTime()-31536000000L);
		
		if (request.getParameter("AssignmentId") != null) {
			try {  // LTIv1p3Launch starts this Task with AssignmentId for any assignment not validated in the last month
				   // This Task gathers all Assignment entities with the same lti_ags_lineitems_url and compares them with the
				   // lineitems provided by the LMS. Any matching lineitems are updated ans saved, while extras are deleted from the datastore.
				debug.append("AssignmentId=" + request.getParameter("AssignmentId") + "...");
				Assignment member = ofy().load().type(Assignment.class).id(Long.parseLong(request.getParameter("AssignmentId"))).safe();
				String lti_ags_lineitems_url = member.lti_ags_lineitems_url;
				debug.append("lti_ags_lineitems_url=" + lti_ags_lineitems_url + "...");
				
				// find the deployment because we will need to get the lineitem container
				String platform_deployment_id = member.domain;
				Deployment d = Deployment.getInstance(platform_deployment_id);
				debug.append("platform_deployment_id=" + platform_deployment_id + "...");
				
				if (lti_ags_lineitems_url==null) lti_ags_lineitems_url = getLineitemsUrl(member.lti_ags_lineitem_url,d.lms_type);
				if (lti_ags_lineitems_url==null || platform_deployment_id==null) return "Error: The Assignment must contain lti_ags_lineitems_url and domain.";
				buf.append("Deployment: " + d.platform_deployment_id + "<br/>");
				buf.append("Lineitems URL: " + lti_ags_lineitems_url + "<br/>");
				
				// initially put all assignments for the deployment (all Groups) into a List
				List<Assignment> domainAssignments = ofy().load().type(Assignment.class).filter("domain",platform_deployment_id).list();
				// Create a Map for all the Group assignments (all assignments with a lineitem_url starting with the Group lineitems_url)
				// It is possible that newly created assignments won't be included because they haven't been launched yet; that's OK
				// because the routine monthly cleanAssignments finds assignments older than 1 year with no lineitem_url
				Map<String,Assignment> groupAssignments = new HashMap<String,Assignment>();
				int indexOfQuery = lti_ags_lineitems_url.indexOf("?");
				String base_url = indexOfQuery>0?lti_ags_lineitems_url.substring(0,indexOfQuery):lti_ags_lineitems_url;
				for (Assignment a : domainAssignments) {
					if (a.lti_ags_lineitem_url!=null && a.lti_ags_lineitem_url.startsWith(base_url)) groupAssignments.put(a.lti_ags_lineitem_url,a);
				}
				
				buf.append("Identified " + groupAssignments.size() + " assignments for this group.<br/>");

				// get the lineitem container from the LMS
				JsonArray lineitem_container = LTIMessage.getLineItemContainer(d, lti_ags_lineitems_url);
				if (lineitem_container==null) throw new Exception("Could not retrieve lineitem container from " + d.platform_deployment_id);
				buf.append("Retrieved lineitem container with " + lineitem_container.size() + " lineitems:<br/>");
				
               //  iterate over the lineitem container, saving matching assignments and removing them from the groupAssignments Map
				Iterator<JsonElement> iterator = lineitem_container.iterator();
				List<Assignment> assignmentsToBeSaved = new ArrayList<Assignment>();
				while (iterator.hasNext()) {
					JsonObject lineitem = iterator.next().getAsJsonObject();
					String lineitem_id = lineitem.get("id").getAsString();
					Assignment a = groupAssignments.get(lineitem_id);
					if (a==null) continue;
					a.valid = now;
					a.lti_ags_lineitems_url = lti_ags_lineitems_url;
					assignmentsToBeSaved.add(a);
					groupAssignments.remove(lineitem_id);
				}
				// Save the updated assignments and delete any leftover assignments (presumably deleted from the LMS)
				if (!assignmentsToBeSaved.isEmpty() && !testOnly) ofy().save().entities(assignmentsToBeSaved);
				
				// temporarily change valid to new Date(0) instead of deleting (safety first!)
				for (Map.Entry<String, Assignment> e : groupAssignments.entrySet()) e.getValue().valid = new Date(0);
				//if (!groupAssignments.isEmpty() && !testOnly) ofy().delete().entities(groupAssignments);

				buf.append(assignmentsToBeSaved.size() + " assignments were " + (testOnly?"identified for updating.":"updated.") + "<br/>");
				buf.append(groupAssignments.size() + " assignments were " + (testOnly?"identified for deletion.":"deleted.") + "<br/>");
			} catch (Exception e) {
				buf.append("Error: " + (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>" + debug.toString());
			}
		} else {  // routine cleaning of Assignments unused more than 1 year and no lti_ags_lineitem_url
			try {
				List<Assignment> oldAssignments = ofy().load().type(Assignment.class).filter("created <",oneYearAgo).limit(500).list();
				
				List<Key<Assignment>> assignmentKeys = new ArrayList<Key<Assignment>>();
				StringBuffer exp = new StringBuffer();
				for (Assignment a : oldAssignments) {													// Mark for deletion if
					if (a.created.before(oneYearAgo) && a.lti_ags_lineitem_url==null) assignmentKeys.add(key(Assignment.class,a.id));	// No lineitem_url
					else if ((a.valid==null && a.created.before(sixMonthsAgo)) || a.valid.before(oneYearAgo)) assignmentKeys.add(key(Assignment.class,a.id));	// unused for >1 year
					else if (ofy().load().key(key(Deployment.class,a.domain)).now()==null) assignmentKeys.add(key(Assignment.class,a.id));	// parent deployment doesn't exist
					if (assignmentKeys.contains(key(a))) exp.append(a.id + " " + a.domain + " " + a.lti_ags_lineitem_url + "</br/>");
				}
				
				buf.append("Found " + oldAssignments.size() + " old assignments, of which " + assignmentKeys.size() + " appear to have expired.<br/>");
				buf.append(exp + "<br/>");
				
				// delete all the expired keys in batches of 500
				if (!testOnly) {
					int nBatches = assignmentKeys.size()/500;
					for (int i=0;i<nBatches;i++) ofy().delete().keys(assignmentKeys.subList(i*500, (i+1)*500));
					ofy().delete().keys(assignmentKeys.subList(nBatches*500, assignmentKeys.size()));
				}
				
				buf.append(assignmentKeys.size() + " expired Assignments" + (testOnly?" identified":" deleted") + ".<br>");
				buf.append("Done.<br>");

			} catch (Exception e) {
				buf.append("Error: " + e.toString());
			}
			return buf.toString();
		}
		return buf.toString();

	}

	String getLineitemsUrl(String lineitem_url, String lms_type) {
		try {
			URL lineitemUrl = new URI(lineitem_url).toURL();  // just test to see if this  is a valid URL
			String query = lineitemUrl.getQuery();			
			String lineitems_url;
			
			switch (lms_type) { 
			case "canvas":
				lineitems_url = lineitem_url.substring(0,lineitem_url.lastIndexOf("/line_items")+11); // strips everything after "/line_items"
				break;
			default:
				lineitems_url = lineitem_url.substring(0,lineitem_url.lastIndexOf("/lineitems")+10); // strips everything after "/lineitems"
			}			
			return lineitems_url + (query==null?"":"?"+query);
		} catch (Exception e) {
			return null;
		}
	}
/*	============= Deleted because query included new Deployments where lastLogin is blank ============
	private String cleanDeployments(boolean testOnly) {
		// This method searches for Deployment entities with no logins for at least 1 year
		
		StringBuffer buf = new StringBuffer();
		buf.append("<h2>Clean Deployments</h2>");
		try {
			List<Key<Deployment>> deploymentKeys = ofy().load().type(Deployment.class).filter("lastLogin <",oneYearAgo).keys().list();					
			if (deploymentKeys.size() > 0 && !testOnly) {  // delete all the old deployments in batches of 500 (max allowed by ofy().delete)
				int nBatches = deploymentKeys.size()/500;
				for (int i=0;i<nBatches;i++) ofy().delete().keys(deploymentKeys.subList(i*500, (i+1)*500));
				ofy().delete().keys(deploymentKeys.subList(nBatches*500, deploymentKeys.size()));
			}
			
			buf.append(deploymentKeys.size() + " Deployments unused for more than one year" + (testOnly?" identified":" deleted") + ".<br>");
			buf.append("Done.<br>");

		}catch (Exception e) {
			buf.append("Error: " + e.toString());
		}
		return buf.toString();
	}
*/	
	private String cleanUsers(boolean testOnly) {
		// This method clears all User entity tokens that have expired
		
		StringBuffer buf = new StringBuffer();
		buf.append("<h2>Clean Users</h2>");
		try {
			Date now = new Date();
			List<Key<User>> keys = ofy().load().type(User.class).filter("exp <", now).keys().list();
			if (keys.size() > 0 && !testOnly) {  // delete all the expired keys in batches of 500 (max allowed by ofy().delete)
				int nBatches = keys.size()/500;
				for (int i=0;i<nBatches;i++) ofy().delete().keys(keys.subList(i*500, (i+1)*500));
				ofy().delete().keys(keys.subList(nBatches*500, keys.size()));
			}
			
			buf.append(keys.size() + " Expired user tokens" + (testOnly?" identified":" deleted") + ".<br>");
			buf.append("Done.<br>");
		
		} catch (Exception e) {
			buf.append("Error: " + e.toString());
		}
		return buf.toString();
	}
	
	/**
	 * Verify the request is from the ChemVantage admin account or scheduler service account.
	 */
	private boolean isAdminAuthenticated(HttpServletRequest request) {
		return Utilities.isAdminAuthenticated(request)
				|| Utilities.isAuthenticatedEmail(request, schedulerServiceAccount());
	}

	/**
	 * Log security events for audit trail
	 */
	private void logSecurityEvent(String eventType, HttpServletRequest request) {
		try {
			System.err.println("SECURITY_EVENT: %s | IP: %s | Time: %s".formatted(
			eventType,
			getClientIp(request),
			System.currentTimeMillis()
			));
		} catch (Exception e) {
			// Silently fail logging
		}
	}

	/**
	 * Extract real client IP, accounting for proxies
	 */
	private String getClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("X-Real-IP");
		}
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		// Return only the first IP if multiple are present
		return ip.split(",")[0].trim();
	}
}