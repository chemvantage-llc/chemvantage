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

import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Referral {
	@Id Long id;
	@Index String referralCode;        // 8-character hex code
	@Index Date created;
	@Index String email;            // client email address
	String name;                    // client name
	String orgName;                 // organization name
	String orgHomePage;             // organization home page URL
	String referrerEmail;           // email address of the person who referred this client
	@Index boolean isVerified;      // email validation status
	
	
	Referral() {}
	
	Referral(String referralCode, String name, String email, String orgName, String orgHomePage) {
		this.referralCode = referralCode;
		this.created = new Date();
		this.name = name;
		this.email = email;
		this.orgName = orgName;
		this.orgHomePage = orgHomePage;
		this.isVerified = false;
		Contact contact = ofy().load().type(Contact.class).filter("referralCode",referralCode).first().now();
		if (contact != null) {
			this.referrerEmail = contact.getEmail();
		}
	}
	
	String getReferralCode() {
		return this.referralCode;
	}
	
	String getName() {
		return this.name;
	}
	
	String getEmail() {
		return this.email;
	}
	
	String getOrgName() {
		return this.orgName;
	}
	
	String getOrgHomePage() {
		return this.orgHomePage;
	}
	
	boolean getIsVerified() {
		return this.isVerified;
	}
	
	void setReferrerEmail(String referrerEmail) {
		this.referrerEmail = referrerEmail;
	}
	
	String getReferrerEmail() {
		return this.referrerEmail;
	}
	
	void setIsVerified(boolean verified) {
		this.isVerified = verified;
	}
}
