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

import java.util.Date;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Contact {
	@Id String email;         // this serves as the unique id to prevent duplicates
	@Index	Date created;
		String firstName;
		String lastName;
		String institution;   // ucsb.edu
	@Index	String role;          // faculty,chair
	@Index	boolean vetted;   // verified instructor
	@Index	String referralCode;  // for rewards program
	@Index	boolean unsubscribed = false;		
			
	Contact() {}
	
	Contact(String firstName,String lastName, String email) {
		this.created = new Date();
		this.firstName = firstName;
		this.lastName = lastName;
		this.email = email.toLowerCase();
		this.institution = email.substring(email.indexOf("@")+1);
		this.referralCode = Integer.toHexString(email.hashCode());
	}
	
	String getFullName() {
		return "".concat(firstName).concat(" ").concat(lastName).trim();
	}
	
	String getEmail() {
		return this.email;
	}
	
	String getEnhancedEmail() {
		return "\"" + getFullName() + "\" <" + email + ">";
	}
}
