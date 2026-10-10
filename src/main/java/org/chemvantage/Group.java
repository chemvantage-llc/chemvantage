/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2022 ChemVantage LLC
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Group {
	@Id 	String id;       // lti_ags_lineitems_url
	@Index	Date valid;
			String organization;
			String lms_type;
			String label;
			String title;
			int nAdministrators;
			int nInstructors;
			int nLearners;
			
	Group() {}
	
	Group(String id) {
		this.id = id;
	}
	
	static Group getInstance(String id) {
		Group g = null;
		try {
			g = ofy().load().type(Group.class).id(id).safe();
		} catch (Exception e) {
			g = new Group(id);
		}
		return g;
	}
	
	static void update(Deployment d, Assignment a) {
		String group_id = a.lti_ags_lineitems_url;
		if (group_id==null) return;
		Group g = getInstance(group_id);
		g.valid = new Date();
		g.organization = d.organization==null?"undisclosed":d.organization;
		g.lms_type = d.lms_type;
		g.label = "undisclosed";
		g.title = "undisclosed";
		// use the NPRS MembershipContainer to complete the remaining Group fields
		if (a.lti_nrps_context_memberships_url==null) {
			ofy().save().entity(g);
			return;
		}
		JsonObject membershipContainer = LTIMessage.getMembershipContainer(a);
		try {  // although the "context" object and id are required, label and title are optional
			g.label = membershipContainer.get("context").getAsJsonObject().get("label").getAsString();
		} catch (Exception e) {}
		try {
			g.title = membershipContainer.get("context").getAsJsonObject().get("title").getAsString();
		} catch (Exception e) {}

		g.nLearners = 0;
		g.nInstructors = 0;
		g.nAdministrators = 0;
		try {
			JsonArray members = membershipContainer.get("members").getAsJsonArray();
			Iterator<JsonElement> iterator = members.iterator();
			while(iterator.hasNext()){
				JsonObject member = iterator.next().getAsJsonObject();
				String roles = member.get("roles").getAsJsonArray().toString().toLowerCase();
				String role = roles.contains("administrator")?"Administrator":roles.contains("instructor")?"Instructor":"Learner";			
				switch (role) {
				case "Administrator": g.nAdministrators++; break;
				case "Instructor": g.nInstructors++; break;
				default: g.nLearners++;
				}		
			}
			ofy().save().entity(g);
		} catch (Exception e) {}
	}
	
	static String enrollmentReport() {
		StringBuffer buf = new StringBuffer("<h3>Active Group Enrollments (past 30 days)</h3>");
		try {
			Date now = new Date();
			Date oneMonthAgo = new Date(now.getTime()-2592000000L);  // 30 days ago
			List<Group> activeGroups = ofy().load().type(Group.class).filter("valid >",oneMonthAgo).list();
			int nLearners = 0;
			int nInstructors = 0;
			int nAdministrators = 0;
			if (activeGroups.size() > 0) {
				buf.append("<table><tr><th>Organization</th><th>LMS</th><th>Course Label</th><th>Title</th><th>Learners</th><th>Instructors</th><th>Administrators</th></tr>");
				for (Group g : activeGroups) {
					buf.append("<tr style='text-align: center;'><td>" + g.organization + "</td><td>" + g.lms_type + "</td><td>" + g.label + "</td><td>" + g.title + "</td>"
							+ "<td>" + g.nLearners + "</td><td>" + g.nInstructors + "</td><td>" + g.nAdministrators + "</td></tr>");
					nLearners += g.nLearners;
					nInstructors += g.nInstructors;
					nAdministrators += g.nAdministrators;
				}
				buf.append("<tr style='text-align: center;'><td></td><td></td><td></td><td></td><td style='border: 1px solid;'>" + nLearners + "</td><td style='border: 1px solid;'>" + nInstructors + "</td><td style='border: 1px solid;'>" + nAdministrators + "</td></tr>");
				buf.append("</table><br/>");
			} else buf.append("There are no active groups.</br>");
		} catch (Exception e) {
			buf.append("Report failed: " + e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}
	
	private record PaidSubscriptionTotals(long users, long amount) {}

	private static Set<String> getSmartTextUserIds(Date qStart, Date qEnd) {
		Set<String> userIds = new HashSet<>();
		for (STTransaction transaction : ofy().load().type(STTransaction.class)
				.filter("graded >=",qStart).filter("graded <=",qEnd)
				.project("userId").distinct(true)) {
			if (transaction.userId != null) userIds.add(transaction.userId);
		}
		return userIds;
	}

	private static PaidSubscriptionTotals getPaidSubscriptionTotals(Set<String> smartTextUserIds, List<String> groupOrgs, Date qStart, Date qEnd) {
		List<String> userIds = new ArrayList<>(smartTextUserIds);
		Set<String> organizations = new HashSet<>(groupOrgs);
		organizations.remove(null);
		long users = 0;
		long amount = 0;

		for (String organization : organizations) {
			for (int offset = 0; offset < userIds.size(); offset += 30) {
				List<Key<PremiumUser>> userKeyBatch = new ArrayList<>();
				for (String userId : userIds.subList(offset, Math.min(offset + 30, userIds.size()))) {
					userKeyBatch.add(ObjectifyService.key(PremiumUser.class, userId));
				}
				for (PremiumUser premiumUser : ofy().load().type(PremiumUser.class)
						.filterKey("IN", userKeyBatch)
						.filter("org", organization)
						.filter("start >=",qStart).filter("start <=",qEnd)
						.filter("paid >",0)) {
					users++;
					amount += premiumUser.paid;
				}
			}
		}
		return new PaidSubscriptionTotals(users, amount);
	}

	static String openStaxCSVReport() { // this generates a CSV version of openStaxReport for uploading to OpenStax
		StringBuffer buf = new StringBuffer();
		try {
			// Find the current quarter
			Calendar cal = Calendar.getInstance(Locale.US);
			int year = cal.get(Calendar.YEAR);
			int month = cal.get(Calendar.MONTH);
			int quarter = (month / 3) + 1;
			
			// Find the prior quarter and year
			int priorQuarter = quarter - 1 + (quarter==1?4:0);
			year = year - (quarter==1?1:0);
			
			// reset the Calendar to the beginning of the prior quarter
			cal = new GregorianCalendar(year,(priorQuarter-1)*3,1,0,0);
			Date qStart = cal.getTime();
			// now move the calendar ahead to the end of the prior quarter
			cal.add(Calendar.MONTH,3);
			cal.add(Calendar.MINUTE,-1);
			Date qEnd = cal.getTime();
			
			SimpleDateFormat df = new SimpleDateFormat("MMM d, yyyy");
			Date now = new Date();
			
			buf.append("\"ChemVantage LLC\"\n"
					+ "\"Reporting Period: " + df.format(qStart) + " - " + df.format(qEnd) + "\"\n"
					+ "\"Report Date: " + df.format(now) + "\"\n"
					+ "\"Contact: Charles Wight (admin@chemvantage.org)\"\n\n");
			
			// Find enrollment data for the report
			   // Total current ChemVantage users
			List<Group> activeGroups = ofy().load().type(Group.class).filter("valid >",qStart).list();
			List<String> groupIds = new ArrayList<String>();
			List<String> groupOrgs = new ArrayList<String>();
			
			StringBuffer table = new StringBuffer("\"Institution\",\"Course\",\"Users\"\n");
			int gUsers = 0; // number of users in each group (course)
			int nUsers = 0; // number of active ChemVantage users (all groups)
			int sUsers = 0; // number of users with active smartText assignments
			for (Group g : activeGroups) {
				if (g.organization.contains("ChemVantage")) continue;
				groupIds.add(g.id);  // this is the group organization (e.g., university name)
				groupOrgs.add(g.organization);
				gUsers = g.nLearners + g.nInstructors + g.nAdministrators;
				nUsers += gUsers;
				boolean usesSmartText = ofy().load().type(Assignment.class).filter("assignmentType","SmartText").filter("valid >",qStart).filter("lti_ags_lineitems_url",g.id).count() > 0;
				if (usesSmartText) {
					table.append("\"" + g.organization + "\",\"" + g.label + "\",\"" + gUsers + "\"\n");
					sUsers += gUsers;
				}
			}
			
			// Find the number and amount of paid subscriptions for this period
			Set<String> smartTextUserIds = getSmartTextUserIds(qStart, qEnd);
			PaidSubscriptionTotals totals = getPaidSubscriptionTotals(smartTextUserIds, groupOrgs, qStart, qEnd);
			long pUsers = totals.users();
			long paid = totals.amount();
			double receipts = (double)paid;
			
			buf.append("\"Total active ChemVantage users\",,\"" + nUsers + "\"\n"
					+ "\"Total active ChemVantage/OpenStax SmartText users\",,\"" + sUsers + "\"\n"
					+ "\"Total paid SmartText subscriptions for this reporting period\",,\"" + pUsers + "\"\n"
					+ "\"Total subscription receipts for this reporting period\",,\"$" + "%,.2f".formatted(receipts) + "\"\n"
					+ "\"OpenStax Mission Support Fee for this reporting period\",,\"$" + "%,.2f".formatted(receipts / 10) + "\"\n"
					+ "\"Mission Suport Fees carried forward from prior periods\",,\"\"\n"
					+ "\"Total amount due to OpenStax\",,\"\"\n"
					+ "\"Amount carried forward to the next reporting period\",,\"\"\n\n\n");
			
			buf.append(table);
			
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}
	
	static String openStaxReport() { // this generates a readable version of the quarterly OpenStax Ally Partner Report
		StringBuffer buf = new StringBuffer("<h1>ChemVantage</h1>");

		try {
			// Find the current quarter
			Calendar cal = Calendar.getInstance(Locale.US);
			int year = cal.get(Calendar.YEAR);
			int month = cal.get(Calendar.MONTH);
			int quarter = (month / 3) + 1;
			
			// Find the prior quarter and year
			int priorQuarter = quarter - 1 + (quarter==1?4:0);
			year = year - (quarter==1?1:0);
			
			// reset the Calendar to the beginning of the prior quarter
			cal = new GregorianCalendar(year,(priorQuarter-1)*3,1,0,0);
			Date qStart = cal.getTime();
			// now move the calendar ahead to the end of the prior quarter
			cal.add(Calendar.MONTH,3);
			cal.add(Calendar.MINUTE,-1);
			Date qEnd = cal.getTime();
			
			SimpleDateFormat df = new SimpleDateFormat("MMM d, yyyy");
			Date now = new Date();
			
			buf.append("<h2>OpenStax Ally Partner Report - " + year + "Q" + priorQuarter + "</h2>"
					+ "ChemVantage LLC<br/>"
					+ "Reporting Period: " + df.format(qStart) + " - " + df.format(qEnd) + "<br/>"
					+ "Report Date: " + df.format(now) + "<br/>"
					+ "Contact: Charles Wight (admin@chemvantage.org)<br/><br/>");
			
			// Find enrollment data for the report
			   // Total current ChemVantage users
			List<Group> activeGroups = ofy().load().type(Group.class).filter("valid >",qStart).list();
			List<String> groupIds = new ArrayList<String>();
			List<String> groupOrgs = new ArrayList<String>();
			
			StringBuffer table = new StringBuffer("<table><tr><th>Institution</th><th>Course</th><th>Users</th></tr>");
			int gUsers = 0; // number of users in each group (course)
			int nUsers = 0; // number of active ChemVantage users (all groups)
			int sUsers = 0; // number of users with active smartText assignments
			for (Group g : activeGroups) {
				if (g.organization.contains("ChemVantage")) continue;
				groupIds.add(g.id);  // this is the group organization (e.g., university name)
				groupOrgs.add(g.organization);
				gUsers = g.nLearners + g.nInstructors + g.nAdministrators;
				nUsers += gUsers;
				boolean usesSmartText = ofy().load().type(Assignment.class).filter("assignmentType","SmartText").filter("valid >",qStart).filter("lti_ags_lineitems_url",g.id).count() > 0;
				if (usesSmartText) {
					table.append("<tr><td style='padding-right:20px'>" + g.organization + "</td><td>" + g.label + "</td><td style='text-align:center'>" + gUsers + "</td></tr>");
					sUsers += gUsers;
				}
			}
			table.append("</table>");
			
			// Find the number and amount of paid subscriptions for this period
			Set<String> smartTextUserIds = getSmartTextUserIds(qStart, qEnd);
			PaidSubscriptionTotals totals = getPaidSubscriptionTotals(smartTextUserIds, groupOrgs, qStart, qEnd);
			long pUsers = totals.users();
			long paid = totals.amount();
			double receipts = (double)paid;
			
			buf.append("<table>"
					+ "<tr><td>Total active ChemVantage users</td><td style='text-align:right'>" + nUsers + "</td></tr>"
					+ "<tr><td>Total active ChemVantage/OpenStax SmartText users</td><td style='text-align:right'>" + sUsers + "</td></tr>"
					+ "<tr><td>Total paid SmartText subscriptions for this reporting period</td><td style='text-align:right'>" + pUsers + "</td></tr>"
					+ "<tr><td>Total subscription receipts for this reporting period</td><td style='text-align:right'>$" + "%,.2f".formatted(receipts) + "</td></tr>"
					+ "<tr><td>OpenStax Mission Support Fee for this reporting period</td><td style='text-align:right'>$" + "%,.2f".formatted(receipts / 10) + "</td></tr>"
					+ "<tr><td>Mission Suport Fees carried forward from prior periods</td><td style='text-align:right'></td></tr>"
					+ "<tr><td>Total amount due to OpenStax</td><td style='text-align:right'></td></tr>"
					+ "<tr><td>Amount carried forward to the next reporting period</td><td style='text-align:right'></td></tr>"
					+ "</table><br/><br/>");
			
			buf.append(table);
			
		} catch (Exception e) {
			buf.append(e.getMessage()==null?e.toString():e.getMessage());
		}
		return buf.toString();
	}
}
	
