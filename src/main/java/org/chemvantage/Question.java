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

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.bestcode.mathparser.IMathParser;
import com.bestcode.mathparser.MathParserFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.OnLoad;

@Entity
public class Question implements Serializable, Cloneable {
	@Serial
	private static final long serialVersionUID = 137L;
	@Id 	Long id;
	@Index	long topicId;
	@Index	Long conceptId; 
	@Index	String assignmentType;
	@Index	String text;
			String type;
			Integer nChoices;
			List<String> choices = new ArrayList<String>();
			double requiredPrecision=0;
			Integer significantFigures;
			String correctAnswer;
			String tag;
	@Index	Integer pointValue;
			String parameterString;
			String hint;
			String solution;
	@Index	String authorId;
			String contributorId;
			String editorId;
			String notes;
			String learn_more_url;
			String sageAdvice;
			String explanation;
			boolean scrambleChoices;
			boolean strictSpelling;
	@Index  Boolean checkedByAI; // true="valid", false="flagged", null="not checked
	private Integer nCorrectAnswers = null;
	private Integer nTotalAttempts = null;
			int[] parameters = {0,0,0,0};
	@Index	boolean isActive = false;
	
	@OnLoad
	void onLoad() {
		// Ensure primitive fields have sensible defaults if null in datastore
		// This handles legacy entities that may not have these fields
		if (nChoices == null) nChoices = 0;
		if (significantFigures == null) significantFigures = 0;
		if (pointValue == null) pointValue = 1;
		if (parameters == null) parameters = new int[]{0,0,0,0};
		if (choices == null) choices = new ArrayList<String>();
	}
	
	public static final int MULTIPLE_CHOICE = 1;
	public static final int TRUE_FALSE = 2;
	public static final int SELECT_MULTIPLE = 3;
	public static final int FILL_IN_WORD = 4;
	public static final int NUMERIC = 5;
	public static final int FIVE_STAR = 6;
	public static final int ESSAY = 7;
	public static final int CHEMICAL_STRUCTURE = 8;

	Question() {}

	Question(int t) {
		switch (t) {
		case (1): this.type = "MULTIPLE_CHOICE"; break;
		case (2): this.type = "TRUE_FALSE"; break;
		case (3): this.type = "SELECT_MULTIPLE"; break;
		case (4): this.type = "FILL_IN_WORD"; break;
		case (5): this.type = "NUMERIC";
				  this.significantFigures = 3;
				  this.requiredPrecision = 2; // percent
				  break;
		case (6): this.type = "FIVE_STAR"; break;
		case (7): this.type = "ESSAY"; break;
		case (8): this.type = "CHEMICAL_STRUCTURE"; break;
		default:  this.type = null;
		}
		this.correctAnswer = "";
		this.parameterString = "";
		this.pointValue = 1;
		this.isActive = false;
	}

	Question (long conceptId,String text,String type,int nChoices,List<String> choices,
			double requiredPrecision,int significantFigures,String correctAnswer,String tag,int pointValue,String parameterString,
			String hint,String solution,String authorId,String contributorId,String editorId,String notes) {
		this.conceptId = conceptId;
		this.text = text;
		this.type = type;
		this.nChoices = nChoices;
		this.choices = choices;
		this.requiredPrecision = requiredPrecision;
		this.significantFigures = significantFigures;
		this.correctAnswer = correctAnswer;
		this.tag = tag;
		this.pointValue = pointValue;
		this.parameterString = parameterString;
		this.hint = hint;
		this.solution = solution;
		this.authorId = authorId;
		this.contributorId = contributorId;
		this.editorId = editorId;
		this.notes = "";
		this.isActive = false;
	}

	public void validateFields() {
		if (assignmentType==null) assignmentType="";
		if (text==null) text="";
		if (type==null) type="";
		if (correctAnswer==null) correctAnswer="";
		if (tag==null) tag="";
		if (parameterString==null) parameterString="";
		if (hint==null) hint="";
		if (solution==null) solution="";
		if (authorId==null) authorId="";
		if (contributorId==null) contributorId="";
		if (editorId==null) editorId="";
		if (notes==null) notes="";
	}
	public Long getId() {
		return this.id;
	}
	
	public int getPointValue() {
		return this.pointValue;
	}
	
	public String getCorrectAnswer() {
		switch (getQuestionType()) {
		case 4: // FILL-IN-WORD
			String[] answers = correctAnswer.split(",");
			return answers[0];
		case 5: // NUMERIC
			if (requiredPrecision == 0) return parseString(correctAnswer); // exact answer
			int sf = significantFigures>0?significantFigures:(int)Math.ceil(-Math.log10(requiredPrecision/100.))+2;
			try {
				double numericValue = Double.parseDouble(parseString(correctAnswer));
				return ("%." + sf + "G").formatted(numericValue);
			} catch (NumberFormatException e) {
				return parseString(correctAnswer); // return as-is if can't parse as double
			}
		default: return correctAnswer;
		}
	}
	
	public boolean requiresParser() {
		return text.contains("#") || (this.parameterString != null && !this.parameterString.isEmpty());
	}
	
	public void setParameters() {
		if (this.requiresParser()) setParameters(-1); // set parameters with a random seed based on the current time
	}

	public void setParameters(long seed) {
		if (!this.requiresParser()) return;     // bulletproofing
		if (this.parameterString==null || this.parameterString.isEmpty()) {
			this.parameterString = "";
			return;
		}

		Random rand = new Random();
		// use seed=-1 for randomly fluctuating parameters, non-zero for deterministic pseudo-random
		if (seed != -1) rand.setSeed((long)seed);

		char p = 'a';
		while (p <= 'd') {
			try { // choose parameters from a string like "a 3:6 b 5:8 c -3:3 d 1:1"
				int i = parameterString.indexOf(p);       // find the index of parameter 'a'
				int j = parameterString.indexOf(':',i);   // find the index of the separator
				int k = parameterString.indexOf(" ",j);   // find the trailing white space
				if (k < 0) k = parameterString.length();  // or the end of the string
				int low = Integer.parseInt(parameterString.substring(i+2,j)); // extract the low limit 3
				int hi = Integer.parseInt(parameterString.substring(j+1,k));  // extract the high limit 6
				parameters[p-'a'] = low + rand.nextInt(hi-low+1);             // randomly select the parameter
				p++;                                      // repreat the process for the next parameter
			}
			catch (Exception e) {
				parameters[p-'a'] = 1;    // if exception is thrown, set parameter value to 1
				p++;                      // and try the next parameter
			}
		}  
	}
	
	int getNumericItemType() {
		if (requiredPrecision==0.0 && significantFigures==0) return 0;      // Q: rules/format  A: exact value match
		else if (requiredPrecision==0.0 && significantFigures!=0) return 1; // Q: show sig figs A: exact value match
		else if (requiredPrecision!=0.0 && significantFigures==0) return 2; // Q: rules/format  A: value agrees to %
		else if (requiredPrecision!=0.0 && significantFigures!=0) return 3; // Q: show sig figs A: value agrees to %
		return 0; //default case
	}
	
	public String parseString(String raw) {
		int itemType = getNumericItemType();
		switch (itemType) {
			case 0: return parseString(raw,1);  // return with historical rules-based display
			case 1: return parseString(raw,2);  // output with sig figs
			case 2: return parseString(raw,1);  // output with historical rules-based display
			case 3: return parseString(raw,2);  // output with sig figs
			default: return "";  // invalid item type
		}
	}
	
	public String parseString(String raw, int outputType) {  
		// this section uses a fully licensed version of the Jbc Math Parser
		// from bestcode.com (license purchased by C. Wight on Nov 18, 2007)

		raw = parseFractions(raw);  // converts a fraction like (|3|2|) to readable HTML form
		raw = parseNumber(raw);		// converts entire input like "forty-two" to "42"
		
		IMathParser parser = MathParserFactory.create();
		try {
			parser.setVariable("a",parameters[0]);
			parser.setVariable("b",parameters[1]);
			parser.setVariable("c",parameters[2]);
			parser.setVariable("d",parameters[3]);
		}
		catch (Exception e) { // parser parameters not set properly; bail on parsing
			return raw==null?"":raw;
		}

		if (raw == null) raw = "";
		String[] pieces = raw.split("#");
		StringBuffer buf = new StringBuffer();
		
		for (int i=0;i<pieces.length;i++) {
			try {
				parser.setExpression(pieces[i]);
				double value = parser.getValue();
				String fmt = "%." + significantFigures + "G";
				/*  There are three styles of parser output:
				 *  0 - raw output from the JbcParser unit
				 *  1 - ChemVantage historical rules-based output for integers, floats and exponential styles
				 *  2 - Output containing the specified number of significant digits
				 */
				switch (outputType) {
					case 0:	buf.append(value);   //buf.append(pieces[i]);
							break;
					case 1:	DecimalFormat df = new DecimalFormat();
							if ((Math.abs(value) < 100) && (value - Math.floor(value) == 0)) df.applyPattern("0"); // small integer output
							else if ((Math.abs(value) < 1.0E5) && (Math.abs(value) > 1.0E-2)) df.applyPattern("0.0#####"); // use decimal number
							else df.applyPattern("0.####E0"); // use scientific notation
							buf.append(df.format(value)); 
							break;
					case 2: buf.append(fmt.formatted(value));
							break;
					case 3: buf.append(fmt.formatted(value));
							break;
					default:		
				}
			} catch (Exception e) {
				buf.append(pieces[i]);  // expression could not be parsed; probably text - return unchanged
			}
		}
		return buf.toString();
	}
	
	public String print() {
		return print("","");
	}
	
	public String print(String showWork,String studentAnswer) {
		return print(showWork,studentAnswer,null);
	}
	
	public String print(String showWork,String studentAnswer,Integer attemptsRemaining) {
		StringBuffer buf = new StringBuffer();
		String placeholder = attemptsRemaining==null?"":(" (" + attemptsRemaining + " attempt" + (attemptsRemaining==1?"":"s") + " remaining)");
		char choice = 'a';
		List<Character> choice_keys = new ArrayList<Character>();
		Random rand = new Random();
		switch (getQuestionType()) {
		case 1: // Multiple Choice
			buf.append(text + "<br/>");
			for (int i=0; i<nChoices; i++) choice_keys.add(Character.valueOf((char)('a'+i)));
			buf.append("<fieldset><legend style='color:#B20000;font-size: small;'>Select only the best answer:</legend>");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<label><input type=radio name=" + this.id + " value=" + choice + (studentAnswer.indexOf(choice)>=0?" CHECKED /> ":" /> ") + choices.get(choice-'a') + "</label><br/>");
			}
			buf.append("</fieldset>");
			if (!placeholder.isEmpty()) buf.append("<span style='color: gray; font-size: 0.8em;'>" + placeholder + "</span><br/>");
			break;
		case 2: // True/False
			buf.append(text);
			buf.append("<br/>");
			buf.append("<fieldset><legend style='color:#B20000;font-size: small;'>Select true or false:</legend>");
			buf.append("<label><input type=radio name=" + this.id + " value='true'" + (studentAnswer.equals("true")?" CHECKED />":" />") + " True</label><br/>");
			buf.append("<label><input type=radio name=" + this.id + " value='false'" + (studentAnswer.equals("false")?" CHECKED />":" />") + " False</label><br/>");
			buf.append("</fieldset>");
			if (!placeholder.isEmpty()) buf.append("<span style='color: gray; font-size: 0.8em;'>" + placeholder + "</span><br/>");
			break;
		case 3: // Select Multiple
			buf.append(text + "<br/>");
			for (int i=0; i<nChoices; i++) choice_keys.add(Character.valueOf((char)('a'+i)));
			buf.append("<fieldset><legend style='color:#B20000;font-size: small;'>Select all of the correct answers:</legend>");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<label><input type=checkbox name=" + this.id + " value=" + choice + (studentAnswer.indexOf(choice)>=0?" CHECKED /> ":" /> ") + choices.get(choice-'a') + "</label><br/>");
			}
			buf.append("</fieldset>");
			if (!placeholder.isEmpty()) buf.append("<span style='color: gray; font-size: 0.8em;'>" + placeholder + "</span><br/>");
			break;
		case 4: // Fill-in-the-Word
			buf.append("<label for=" + this.id + ">" + text + "</label>");
			buf.append("<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Enter the correct word or phrase:</span><br/>");
			buf.append("<input id=" + this.id + " type=text aria-label='student answer' name=" + this.id + " value='" + quot2html(studentAnswer) + "' placeholder='" + placeholder + "' />");
			buf.append("&nbsp;" + tag + "<br/><br/>");
			break;
		case 5: // Numeric Answer
			buf.append(parseString(text));
			buf.append("<br/>");
			buf.append("<div id=showWork" + this.id + " style='display:none'>"
					+ "<label for=ShowWork'" + this.id + "'>Show your work:</label><br/><TEXTAREA NAME=ShowWork" + this.id + " ROWS=5 COLS=50 WRAP=SOFT "
					+ "maxlength=500 placeholder='Show your work here' aria-label='show your work here'>" + (showWork==null?"":showWork) + "</TEXTAREA>"
					+ "<br/></div>"
					+ "<label for='answer" + this.id + "'>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='color:#B20000;font-size: small;'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='color:#B20000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}
			buf.append("</label><br/><input aria-label='student answer' size=25 type=text name=" + this.id + " id=answer" + this.id + " value='" + studentAnswer + "' placeholder='" + placeholder + "' onFocus=showWorkBox('" + this.id + "'); />");
			buf.append("&nbsp;" + parseString(tag) + "<br/><br/>");
			break;        
		case 6: // FIVE_STAR rating
			buf.append(text);
			buf.append("<br/>");
			buf.append("<label for=" + this.id + "><span id='vote" + this.id + "' style='color:#990000;font-size:small;'>(click a star):</span></label><br/>");
						
			for (int i=1;i<6;i++) {
				buf.append("<img src='images/star1.gif' id='star" + i + String.valueOf(this.id) + "' style='width:30px; height:30px;' alt='star " + i + " for rating' "        // properties
						+ "onmouseover=showStars" + this.id + "(" + i + ") onmouseout=showStars" + this.id + "(0) onclick=showStars" + this.id + "(" + i + ",true) />" ); // mouse actions
			}
			
			buf.append("&nbsp;&nbsp;&nbsp;&nbsp;<input id=" + this.id + " name=" + this.id + " type=range min=1 max=5 style='opacity:0' onfocus=this.style='opacity:1';this.value=4;showStars" + this.id + "(4,true); oninput='showStars" + this.id + "(this.value,true);' />");
			buf.append("<br clear='all'><br/>");
			buf.append("<script>"
					+ "var fixed" + this.id + " = false;"
					+ "function showStars" + this.id + "(nStars,clicked=false) {"
					+ "  if (fixed" + this.id + " && !clicked) return;"
					+ "  document.getElementById('vote" + this.id + "').innerHTML=(nStars==0?'(click a star)':nStars+(nStars>1?' stars':' star'));"  // unary operator + converts string to int
					+ "  for (i=1;i<6;i++) document.getElementById('star'+i+'" + this.id + "').src = (nStars<i?'https://images.chemvantage.org/star1.gif':'https://images.chemvantage.org/star2.gif');"
					+ "  fixed" + this.id + " = clicked;"
					+ "  if (clicked) document.getElementById('" + this.id + "').value=nStars;"
					+ "}"
					+ "</script>\n");
			
			/*
			buf.append("<input id=" + this.id + " type=hidden name=" + this.id + " />");
			buf.append("&nbsp;&nbsp;&nbsp;&nbsp;<input type=range min=1 max=5 style='opacity:0' onfocus=this.style='opacity:1' oninput='showStars" + this.id + "(this.value,true);' />");
			buf.append("<br clear='all'>");
			buf.append("<script>"
					+ "var fixed" + this.id + " = false;"
					+ "function showStars" + this.id + "(nStars,clicked=false) {"
					+ "  if (fixed" + this.id + " && !clicked) return;"
					+ "  document.getElementById('vote" + this.id + "').innerHTML=(nStars==0?'(click a star)':nStars+(nStars>1?' stars':' star'));"  // unary operator + converts string to int
					+ "  for (i=1;i<6;i++) document.getElementById('star'+i+'" + this.id + "').src = (nStars<i?'images/star1.gif':'images/star2.gif');"
					+ "  fixed" + this.id + " = clicked;"
					+ "  if (clicked) document.getElementById('" + this.id + "').value=nStars;"
					+ "}"
					+ "</script>\n");
			*/
			
			int initialStars = 0;
			try { 
				initialStars = Integer.parseInt(studentAnswer);
				buf.append("<script>showStars" + this.id + "(" + initialStars + ",true);</script>");
			} catch (Exception e) {}
			break;
		case 7: // Short ESSAY question
			buf.append(text);
			buf.append("<br/>");
			buf.append("<span style='color:#B20000;font-size:small;'><label for='" + this.id + "'>Write a short essay (800 characters max):</label></span><br/>");
			buf.append("<textarea id=" + this.id + " aria-label='enter your essay here' name=" + this.id 
					+ " rows=5 cols=60 wrap=soft placeholder='Enter your answer here' maxlength=800 >" + studentAnswer + "</textarea><br>");
			break;
		case 8: // Chemical Structure
			buf.append(text + "<br/>");
			//buf.append("<span style='color:#B20000;font-size: small;'>Draw the requested chemical structure in the window below, or enter a SMILES string using the keyboard-only option. For help with the drawing tool, see the <a href='https://github.com/epam/ketcher/blob/v3.15.0/documentation/help.md#ketcher-molecules-mode' target='_blank'>Ketcher Help Page</a>.</span><br/>");
			buf.append(renderChemicalStructureComposer(String.valueOf(this.id), studentAnswer, false, false, false));
			if (!placeholder.isEmpty()) buf.append("<span style='color: gray; font-size: 0.8em;'>" + placeholder + "</span><br/>");
			break;
		}
		return buf.toString();
	}

	String getHint() {
		return parseString(hint) + "<br/>";
	}
	
	String printAll() {
		// use this method to display an example of the question, correct answer and solution
		StringBuffer buf = new StringBuffer();
		char choice = 'a';
		List<Character> choice_keys = new ArrayList<Character>();
		for (int i=0; i<nChoices; i++) choice_keys.add(Character.valueOf((char)('a'+i)));
		Random rand = new Random();
		switch (getQuestionType()) {
		case 1: // Multiple Choice
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select only the best answer:</span><br/>");
			buf.append("<UL" + (scrambleChoices?" style=color:#B20000":"") + ">");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<LI>" 
						+ (correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#767676>")
						+ quot2html(choices.get(choice-'a'))
						+ (correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>")
						+ "</LI>");
			}
			buf.append("</UL>");
			break;
		case 2: // True/False
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select true or false:</span><UL>");
			buf.append("<LI>" 
					+ (correctAnswer.equals("true")?"<B>True</B>":"<FONT COLOR=#767676>True</FONT>") 
					+ "</LI>");
			buf.append("<LI>" 
					+ (correctAnswer.equals("false")?"<B>False</B>":"<FONT COLOR=#767676>False</FONT>") 
					+ "</LI>");
			buf.append("</UL>");
			break;
		case 3: // Select Multiple
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select all of the correct answers:</span>");
			buf.append("<UL" + (scrambleChoices?" style=color:#B20000":"") + ">");
			while (choice_keys.size()>0) {
				choice = choice_keys.remove(scrambleChoices?rand.nextInt(choice_keys.size()):0);
				buf.append("<LI>"
						+ (correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#767676>")
						+ quot2html(choices.get(choice-'a'))
						+ (correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>")
						+ "</LI>");
			}
			buf.append("</UL>");
			break;
		case 4: // Fill-in-the-Word
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Enter the correct word or phrase:</span><br/>");
			buf.append("<span style='border: 1px solid black'>"
					+ "<b>" + (this.hasACorrectAnswer()?quot2html(correctAnswer):"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;") + "</b>"
					+ "</span>");
			buf.append("&nbsp;" + tag + "<br/>"
					+ (this.hasACorrectAnswer() && this.strictSpelling?"Spelling: strict<br/><br/>":"<br/>"));
			break;
		case 5: // Numeric Answer
			buf.append(parseString(text) + "<br/>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='color:#B20000;font-size: small;'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='color:#B20000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}			
			buf.append("<span style='border: 1px solid black'>"
					+ "<b>" + (this.hasACorrectAnswer()?getCorrectAnswer():"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;") + "</b>"
					+ "</span>");
			buf.append("&nbsp;" + parseString(tag) + "<br/><br/>");
			if (hint != null && hint.length()>0) {
				buf.append("Hint: " + parseString(hint) + "<br/><br/>");
			}
			if (solution != null && solution.length()>0) {
				buf.append("Solution:<br/>" + parseString(solution) + "<br/><br/>");
			}
			break;        
		case 6: // FIVE_STAR rating
			buf.append(text);
			buf.append("<br/>");
			buf.append("<label for=" + this.id + "><span id='vote" + this.id + "' style='color:#990000;font-size:small;'>(click a star):</span></label><br/>");
			buf.append("<script src='/js/star-rating.js'></script>\n");
						
			for (int i=1;i<6;i++) {
				buf.append("<img src='images/star1.gif' id='star" + i + String.valueOf(this.id) + "' style='width:30px; height:30px;' alt='star " + i + " for rating' "        // properties
						+ "onmouseover=\"showStarsRating(" + this.id + "," + i + ")\" onmouseout=\"showStarsRating(" + this.id + ",0)\" onclick=\"showStarsRating(" + this.id + "," + i + ",true)\" />" ); // mouse actions
			}
			
			buf.append("&nbsp;&nbsp;&nbsp;&nbsp;<input id=" + this.id + " name=" + this.id + " type=range min=1 max=5 style='opacity:0' onfocus=\"this.style='opacity:1';this.value=4;showStarsRating(" + this.id + ",4,true);\" oninput=\"showStarsRating(" + this.id + ",this.value,true);\" />");
			buf.append("<br clear='all'><br/>");
			break;
		case 7: // Short ESSAY question
			buf.append(text);
			buf.append("<br/>");
			buf.append("<label><span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
			buf.append("<textarea id=" + this.id + " name=" + this.id + " rows=5 cols=60 wrap=soft placeholder='Enter your answer here' "				
					+ "onKeyUp=document.getElementById('" + this.id + "').value=document.getElementById('" + this.id + "').value.substring(0,800);}>"
					+ "</textarea></label><br/><br/>");
			break;
		case 8: // Chemical Structure
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Draw the correct structure and submit the molfile data for scoring.</span><br/>");
			buf.append(renderChemicalStructurePreview(correctAnswer, "Correct structure", false) + "<br/>");
			break;
		}
		return buf.toString();
	}

	String printAllToStudents(String studentAnswer) {
		return printAllToStudents(studentAnswer,true,true,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails) {
		return printAllToStudents(studentAnswer,showDetails,true,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails,boolean reportable) {
		return printAllToStudents(studentAnswer,showDetails,reportable,"");
	}
	
	String printAllToStudents(String studentAnswer,boolean showDetails,boolean reportable,String showWork) {
		// use this method to display an example of the question, correct answer and solution
		// this differs from printAll() because only the first of several 
		// correct fill-in-word answers is presented, and choices are not scrambled
		// showDetails enables display of Solution to numeric problems (default = true)
		StringBuffer buf = new StringBuffer("<a name=" + this.id + "></a>");
		char choice = 'a';
		switch (getQuestionType()) {
		case 1: // Multiple Choice
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select only the best answer:</span><br/>");
			for (int i = 0; i < nChoices; i++) {
				buf.append("&nbsp;" + choice + ". "
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#767676>")
						+ quot2html(choices.get(i))
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>") + "<br/>");
				choice++;
			}
			break;
		case 2: // True/False
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select true or false:</span><UL>");
			buf.append("<LI>" 
					+ (showDetails && correctAnswer.equals("true")?"<B>True</B>":"<FONT COLOR=#767676>True</FONT>") 
					+ "</LI>");
			buf.append("<LI>" 
					+ (showDetails && correctAnswer.equals("false")?"<B>False</B>":"<FONT COLOR=#767676>False</FONT>")
					+ "</LI>");
			buf.append("</UL>");
			break;
		case 3: // Select Multiple
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Select all of the correct answers:</span><br/>");
			for (int i = 0; i < nChoices; i++) {
				buf.append("&nbsp;" + choice + ". "
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"<B>":"<FONT COLOR=#767676>")
						+ quot2html(choices.get(i))
						+ (showDetails && correctAnswer.indexOf(choice)>=0?"</B>":"</FONT>") + "<br/>");
				choice++;
			}
			break;
		case 4: // Fill-in-the-Word
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Enter the correct word or phrase:</span><br/>");
			String[] answers = correctAnswer.split(",");
			buf.append("<span style='border: 1px solid black'>"
					+ (showDetails?"<b>" + quot2html(answers[0]) + "</b>":"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
					+ "</span>");
			if (tag.length() > 0) buf.append("&nbsp;" + tag + "<br/>");
			break;
		case 5: // Numeric Answer
			buf.append(parseString(text) + "<br/>");
			switch (getNumericItemType()) {
			case 0: buf.append("<span style='color:#B20000;font-size: small;'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 1: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("<span style='color:#B20000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			case 3: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
			default:
			}			
			buf.append("<span style='border: 1px solid black'>"
					+ (showDetails?"<b>" + getCorrectAnswer() + "</b>":"&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;")
					+ "</span>");
			buf.append("&nbsp;" + parseString(tag) + "<br/>");
			break;        
		case 6: // FIVE_STAR rating
			buf.append(parseString(text) + "<br/>");
			int nStars = 0;
			try {
				nStars = Integer.parseInt(studentAnswer);
			} catch (Exception e) {}
			for (int i=1;i<6;i++) {
				buf.append("<img " + (i<=nStars?"src='images/star2.gif'":"src='images/star1.gif'") + " style='width:30px; height:30px;' alt='star" + i + "' />");
			}
			if (studentAnswer!=null) buf.append("&nbsp;(" + nStars +  (nStars==1?" star":" stars)"));
			buf.append("<br/>");
			break;
		case 7: //Short ESSAY question
			buf.append(text);
			buf.append("<br/>");
			buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
			break;
		case 8: // Chemical Structure
			buf.append(text + "<br/>");
			buf.append("<span style='color:#B20000;font-size: small;'>Draw the correct structure.</span><br/>");
			if (showDetails && hasACorrectAnswer()) buf.append(renderChemicalStructurePreview(correctAnswer, "Correct structure", false));
			else buf.append("<div style='border:1px solid #c7c7c7;background:#fff;width:320px;height:240px;display:flex;align-items:center;justify-content:center;'>Chemical structure</div>");
			break;
		}
		
		buf.append("<br/>");
		if (showWork != null && !showWork.isEmpty()) buf.append("<b>Student work:</b><br/><div style='border-style: solid; border-width: thin; white-space: pre-wrap;'>" + showWork + "</div>");	
		if (studentAnswer==null || studentAnswer.isEmpty()) buf.append("<b>No answer was submitted for this question item.</b><p></p>");
		else if (getQuestionType()<6) {
			buf.append("<b>The answer submitted was: " + studentAnswer + "</b>&nbsp;");
			if (this.isCorrect(studentAnswer)) buf.append("&nbsp;<IMG SRC=https://images.chemvantage.org/checkmark.png ALT='Check mark' align=bottom>");
			else if (this.agreesToRequiredPrecision(studentAnswer)) buf.append("<IMG SRC=https://images.chemvantage.org/partCredit.png ALT='minus 1 sig figs' align=middle>"
					+ "<br/>Your answer must have exactly " + significantFigures + " significant digits.<br/>If your answer ends in a zero, then it must also have a decimal point to indicate which digits are significant.");
			else buf.append("<IMG SRC=https://images.chemvantage.org/xmark.png ALT='X mark' align=middle>");
			buf.append("<br/><br/>");
		} else if (getQuestionType()==7) {
			buf.append("<b>The answer submitted was: </b><br/>" + studentAnswer + "<br/>");
		} else if (getQuestionType()==8) {
			buf.append("<b>The structure submitted was:</b><br/>" + renderChemicalStructurePreview(studentAnswer, "Submitted structure", false) + "<br/>");
		}
		
		if (reportable) {
			try {
				studentAnswer = URLEncoder.encode(studentAnswer,"UTF-8");  // to send with URL
			} catch (Exception e) {}
			buf.append("<div id='feedback" + this.id + "'>");
			buf.append("<FORM id='suggest" + this.id + "' >"
					+ "<a id='link" + this.id + "' aria-label='Report a problem with this question' href=# "
					+ "onClick=getElementById('form" + this.id + "').style.display='block';"
					+ "getElementById('link" + this.id + "').style='display:none';>"
					+ "Report a problem with this question</a>"
					+ "<div id='form" + this.id + "' style='display:none'>");

			buf.append("<span style='color:#B20000'><br/>");
			switch (getQuestionType()) {
			case 1: buf.append("Reminder: The correct answer is shown in bold print above."); break; // MULTIPLE_CHOICE
			case 2: buf.append("Reminder: The correct answer is shown in bold print above."); break; // TRUE_FALSE
			case 3: buf.append("Reminder: The correct answers are shown in bold print above. You must select all of them."); break; // SELECT_MULTIPLE
			case 4: buf.append("Reminder: The correct answer will always form a complete, grammatically correct sentence."); break; // FILL_IN_WORD
			case 5: // NUMERIC
				switch (getNumericItemType()) {
				case 0: buf.append("Reminder: Your answer must have exactly the same value as the correct answer."); break;
				case 1: buf.append("Reminder: Your answer must have exactly the same value as the correct answer and must have " + significantFigures + " significant figures."); break;
				case 2: buf.append("Reminder: Your answer must be within " + requiredPrecision + "% of the correct answer."); break;
				case 3: buf.append("Reminder: Your answer must have " + significantFigures + " significant figures and be within " + requiredPrecision + "% of the correct answer."); break;
				default:
				}
			default:
			}		
			buf.append("</span><br/>");

			buf.append("<label>Your Comment: <INPUT TYPE=TEXT SIZE=60 NAME=Notes /></label><br/>");
			buf.append("<label>Your Email: <INPUT TYPE=TEXT SIZE=40 PLACEHOLDER=' optional, if you want a response' NAME=Email /></label><br/>");
			buf.append("<INPUT TYPE=BUTTON VALUE='Submit Feedback' "
					+ "onClick=\" return ajaxSubmit('/Feedback?UserRequest=ReportAProblem','" + this.id + "','" + Arrays.toString(this.parameters) + "','" + studentAnswer + "',encodeURIComponent(document.getElementById('suggest" + this.id + "').Notes.value),encodeURIComponent(document.getElementById('suggest" + this.id + "').Email.value)); return false;\" />"
					+ "</div></FORM><br/>");
			buf.append("</div>");
		}
		return buf.toString(); 
	}

	String getExplanation() {
		// if an explanation was stored previously 
		if (!this.requiresParser() && this.explanation != null && !this.explanation.isEmpty()) return this.explanation;
		// Otherwise, compute an explanation
		StringBuffer buf = new StringBuffer();
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			BufferedReader reader = null;
			JsonObject api_request = new JsonObject();
			api_request.addProperty("model", Subject.getGPTModel());
			  JsonObject prompt = new JsonObject();
			  prompt.addProperty("id", "pmpt_68ae17560ce08197a4584964c31e79510acd7153761d1f7b");
			    JsonObject variables = new JsonObject();
			    variables.addProperty("question_item", this.printForSage());
			    variables.addProperty("correct_answer", this.getCorrectAnswerForSage());
			  prompt.add("variables", variables);
			api_request.add("prompt", prompt);
			String response_format = "{'format':{'type':'json_schema','name':'answer_explanation','schema':{'type':'object','properties':{'explanation':{'type':'string'}},'required':['explanation'],'additionalProperties':false},'strict':true}}";
			api_request.add("text", JsonParser.parseString(response_format).getAsJsonObject());
			
			URL u = new URI("https://api.openai.com/v1/responses").toURL();
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setRequestMethod("POST");
			uc.setDoInput(true);
			uc.setDoOutput(true);
			uc.setRequestProperty("Authorization", "Bearer " + Subject.getOpenAIKey());
			uc.setRequestProperty("Content-Type", "application/json");
			uc.setRequestProperty("Accept", "application/json");
			OutputStream os = uc.getOutputStream();
			byte[] json_bytes = api_request.toString().getBytes("utf-8");
			os.write(json_bytes, 0, json_bytes.length);           
			os.close();
			
			int response_code = uc.getResponseCode();
			debug.append("HTTP Response Code: " + response_code);
			
			JsonObject api_response = null;
			if (response_code/100==2) {
				reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
				api_response = JsonParser.parseReader(reader).getAsJsonObject();
				debug.append(api_response.toString());
				reader.close();
			} else {
				reader = new BufferedReader(new InputStreamReader(uc.getErrorStream()));
				debug.append(JsonParser.parseReader(reader).getAsJsonObject().toString());
				reader.close();
			}
			
			// Find the output text buried in the response JSON:
			if (api_response != null) {
				JsonArray output = api_response.get("output").getAsJsonArray();
				for (JsonElement o : output) {
					JsonObject message = o.getAsJsonObject();
					if (message.get("type").getAsString().equals("message") && message.has("content")) {
						JsonArray content = message.get("content").getAsJsonArray();
						for (JsonElement c : content) {
							JsonObject output_text = c.getAsJsonObject();
							if (output_text.has("text")) {
								String textContent = output_text.get("text").getAsString();  // this is the essay score JSON string
								JsonObject explanation = JsonParser.parseString(textContent).getAsJsonObject();
								this.explanation = explanation.get("explanation").getAsString();
								break;
							} else if (output_text.has("refusal")) {
								this.explanation = "Sorry, an explanation is not available at this time.";
								break;
							}
						}
						break;
					}
				}
			}
						
			if (!this.requiresParser()) ofy().save().entity(this);
			buf.append(this.explanation);
			//buf.append("<br/>" + (api_response==null?"api_response was null":api_response.toString()));
		} catch (Exception e) {
			buf.append("<br/>Sorry, an explanation is not available at this time. " + (e.getMessage()==null?e.toString():e.toString()) + "<p>" + debug.toString() + "<p>");
		}
		return buf.toString();
	}

	String printForSage() {
		StringBuffer buf = new StringBuffer();
		List<Character> choice_keys = new ArrayList<Character>();
		switch (getQuestionType()) {
		case 1: // Multiple Choice
			buf.append(text + "<br/>");
			buf.append("Select only the best answer:<br/>");
			for (int i=0; i<nChoices; i++) {
				buf.append(Character.valueOf((char)('a'+i)) + ") " + choices.get(i) + "<br/>");
			}
			break;
		case 2: // True/False
			buf.append(text + "<br/>");
			buf.append("True or false?<br/>");
			break;
		case 3: // Select Multiple
			buf.append(text + "\n");
			for (int i=0; i<nChoices; i++) choice_keys.add(Character.valueOf((char)('a'+i)));
			buf.append("Select all of the correct answers:<br/>");
				for (int i=0; i<nChoices; i++) {
				buf.append(Character.valueOf((char)('a'+i)) + ") " + choices.get(i) + "<br/>");
			}
			break;
		case 4: // Fill-in-the-Word
			buf.append("Fill in the blank with the correct word or phrase:\n" 
					+ text + "_______________" + tag + "<br/>");
			break;
		case 5: // Numeric Answer
			buf.append(parseString(text) + "<br/>");
			switch (getNumericItemType()) {
			case 0: buf.append("Enter the exact value: "); break;
			case 1: buf.append("Enter the value with the appropriate number of significant figures: "); break;
			case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
				buf.append("Include at least " + sf + " significant figures in your answer: "); break;
			case 3: buf.append("Enter the value with the appropriate number of significant figures"); break;
			default:
			}
			buf.append("____________" + parseString(tag) + "<br/>");
			break;        
		case 6: // FIVE_STAR rating
			buf.append(text + "<br/>");
			buf.append("Enter your rating from 1 to 5 stars: ______<br/>");
			break;
		case 7: // Short ESSAY question
			buf.append(text + "<br/>");
			buf.append("Enter your answer in 800 characters or less: _____________________<br/>");
			break;
		case 8: // Chemical Structure
			buf.append(text + "<br/>");
			buf.append("Draw the correct structure and submit the molfile data for scoring.<br/>");
			break;
		}
		return buf.toString();	
	}
	
	public void addAttemptSave(boolean isCorrect) {
		if (nTotalAttempts==null) initializeCounters();
		nTotalAttempts++;
		if(isCorrect) nCorrectAnswers++;
		ofy().save().entity(this);
	}
	
	public void addAttemptsNoSave(int nTotal,int nCorrect) {
		if (nTotalAttempts==null) initializeCounters();
		this.nTotalAttempts += nTotal;
		this.nCorrectAnswers += nCorrect;
	}
	
	public String getSuccess() {
		if (nTotalAttempts==null) initializeCounters();
		return String.valueOf(nCorrectAnswers) + "/" + String.valueOf(nTotalAttempts) + "&nbsp;(" + getPctSuccess() + "%)";
	}
	
	public int getPctSuccess() {
		if (nTotalAttempts==null) initializeCounters();
		return nTotalAttempts==0?0:100*nCorrectAnswers/nTotalAttempts;
	}
	
	private void initializeCounters() {
		nTotalAttempts = 0;
		nCorrectAnswers = 0;
	}
	
	public boolean hasSolution() {
		if (solution == null) solution = "";
		return solution.length()>0?true:false;
	}

	public boolean hasHint() {
		if (hint == null) hint = "";
		return hint.length()>0?true:false;
	}

	int getQuestionType() {
		return getQuestionType(this.type);
	}
	
	static int getQuestionType(String type) {
		if (type.equals("MULTIPLE_CHOICE")) return 1;
		if (type.equals("TRUE_FALSE")) return 2;
		if (type.equals("SELECT_MULTIPLE")) return 3;
		if (type.equals("FILL_IN_WORD")) return 4;
		if (type.equals("NUMERIC")) return 5;
		if (type.equals("FIVE_STAR")) return 6;
		if (type.equals("ESSAY")) return 7;
		if (type.equals("CHEMICAL_STRUCTURE")) return 8;
		else return 0;
	}

	static String getQuestionType(int type) {
		switch (type) {
			case (1): return "MULTIPLE_CHOICE";
			case (2): return "TRUE_FALSE";
			case (3): return "SELECT_MULTIPLE";
			case (4): return "FILL_IN_WORD";
			case (5): return "NUMERIC";
			case (6): return "FIVE_STAR";
			case (7): return "ESSAY";
			case (8): return "CHEMICAL_STRUCTURE";
			default: return "";
		}
	}
	
	public void setQuestionType(int t) { // create a blank form for a new question of type t
		switch (t) {
		case (1): type = "MULTIPLE_CHOICE"; break;
		case (2): type = "TRUE_FALSE"; break;
		case (3): type = "SELECT_MULTIPLE"; break;
		case (4): type = "FILL_IN_WORD"; break;
		case (5): type = "NUMERIC"; break;
		case (6): type = "FIVE_STAR"; break;
		case (7): type = "ESSAY"; break;
		case (8): type = "CHEMICAL_STRUCTURE"; break;
		default:  type = "";
		}
	}

	public String edit() {
		StringBuffer buf = new StringBuffer();
		this.validateFields();
		try {
			String[] choiceNames = {"ChoiceAText","ChoiceBText","ChoiceCText","ChoiceDText","ChoiceEText"};
			char choice = 'a';
			switch (this.getQuestionType()) {
			case 1: // Multiple Choice
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" 
						+ amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#B20000;font-size: small;'>Select only the best choice:</span><br/>");
				for (int i=0;i<5;i++) { 
					if (i < nChoices) {
						buf.append("<input type=radio name=CorrectAnswer value='" + choice + "'");
						if (correctAnswer.indexOf(choice) >= 0) buf.append(" CHECKED");
						buf.append("/><input size=30 name=" + choiceNames[i] + " value='"); 
						if (choices.size() > i) buf.append(quot2html(amp2html(choices.get(i))));
						buf.append("'/><br/>");
					}
					else buf.append("<input type=radio name=CorrectAnswer value=" + choice + " />"
							+ "<input size=30 name=" + choiceNames[i] + " /><br/>");
					choice++;
				}
				buf.append("<label>Check here to scramble the choices: <input type=checkbox name=ScrambleChoices value=true " + (this.scrambleChoices?"CHECKED":"") + " /></label><br/>");
				break;
			case 2: // True/False
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" 
						+ amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#B20000;font-size: small;'>Select true or false:</span><br/>");
				buf.append("<input type=radio name=CorrectAnswer value='true'");
					if (correctAnswer.equals("true")) buf.append(" CHECKED");
				buf.append("/> True<br/>");
				buf.append("<input type=radio name=CorrectAnswer value='false'");
				if (correctAnswer.equals("false")) buf.append(" CHECKED");
				buf.append("/> False<br/>");
				break;
			case 3: // Select Multiple
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" 
						+ amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#B20000;font-size: small;'>Select all of the correct answers:</span><br/>");
				for (int i=0;i<5;i++){
					if (i < nChoices) {
						buf.append("<input type=checkbox name=CorrectAnswer value='" + choice + "'");
						if (correctAnswer.indexOf(choice) >= 0) buf.append(" CHECKED");
						buf.append("/><input size=30 name=" + choiceNames[i] + " value='"); 
						if (choices.size() > i) buf.append(quot2html(amp2html(choices.get(i))));
						buf.append("'/><br/>");
					}
					else buf.append("<input type=checkbox name=CorrectAnswer value=" + choice + " />"
							+ "<input size=30 name=" + choiceNames[i] + " /><br/>");
					choice++;
				}
				buf.append("<label>Check here to scramble the choices: <input type=checkbox name=ScrambleChoices value=true " + (this.scrambleChoices?"CHECKED":"") + " /></label><br/>");
				break;
			case 4: // Fill-in-the-Word
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" 
						+ amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#B20000;font-size: small;'>Enter the correct word or phrase.<br/>"
						+ "Multiple correct answers can be entered as a comma-separated list.</span><br/>");
				buf.append("<input type=text name=CorrectAnswer value=\"" 
						+ quot2html(amp2html(correctAnswer)) + "\"'/><br/>");
				buf.append("<TEXTAREA name=QuestionTag rows=5 cols=60 wrap=soft>" 
						+ amp2html(tag) + "</TEXTAREA><br/>");
				buf.append("<label><input type=checkbox name=StrictSpelling value=true " + (this.strictSpelling?"CHECKED":"") + " />Strict spelling</label><br/><br/>");
				break;
			case 5: // Numeric Answer
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=60 wrap=soft>" 
						+ amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<FONT SIZE=-2>Significant figures: <input size=5 name=SignificantFigures value='" + significantFigures + "'/> Required precision: <input size=5 name=RequiredPrecision value='" + requiredPrecision + "'/>% (set to zero to require exact answer)</FONT><br/>");
				switch (getNumericItemType()) {
				case 0: buf.append("<span style='color:#B20000;font-size: small;'>Enter the exact value. <a role='button' href=# onclick=\"alert('Your answer must have exactly the correct value. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 1: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 2: int sf = (int)Math.ceil(-Math.log10(requiredPrecision/100.))+1;
					buf.append("<span style='color:#B20000;font-size: small;'>Include at least " + sf + " significant figures in your answer. <a role='button' href=# onclick=\"alert('To be scored correct, your answer must agree with the correct answer to at least " + sf + " significant figures. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				case 3: buf.append("<span style='color:#B20000;font-size: small;'>Enter the value with the appropriate number of significant figures. <a role='button' href=# onclick=\"alert('Use the information in the problem to determine the correct number of sig figs in your answer. You may use scientific E notation. Example: enter 3.56E-12 to represent the number 3.56\u00D7\u0031\u0030\u207B\u00B9\u00B2');return false;\">&#9432;</a></span><br/>"); break;
				default:
				}			
				buf.append("Correct answer:");
				buf.append("<INPUT TYPE=TEXT NAME=CorrectAnswer VALUE='" + correctAnswer + "'/> ");
				buf.append(" Units:<INPUT TYPE=TEXT NAME=QuestionTag SIZE=8 VALUE='" 
						+ quot2html(amp2html(tag)) + "'/><br/>");
				buf.append("Parameters:<input name=ParameterString value='" 
						+ parameterString + "'/><FONT SIZE=-2><a href=# onClick=\"javascript:document.getElementById('detail1').innerHTML="
						+ "'You may embed up to 4 parameters (a b c d) in a question using a parameter string like<br/>"
						+ "a 111:434 b 7:39<br/>"
						+ "This will randomly select integers for variables a and b from the specified ranges.<br/>"
						+ "Use these in math expressions with the pound sign delimeter (#) to create randomized data.<br/>"
						+ "Example: Compute the mass of sodium in #a# mL of aqueous #b/10# M NaCl solution.<br/>"
						+ "Correct answer: #22.9898*a/1000*b/10# g<p></p>"
						+ "You can also display fractions in vertical format using encoding like (|numerator|denominator|)<br/><br/>'\";>What's This?</a></FONT>");
				buf.append("<div id=detail1></div>");
				buf.append("Hint:<br/><TEXTAREA NAME=Hint ROWS=3 COLS=60 WRAP=SOFT>"
						+ amp2html(hint) + "</TEXTAREA><br/>");
				buf.append("Solution:<br/><TEXTAREA NAME=Solution ROWS=10 COLS=60 WRAP=SOFT>" 
						+ amp2html(solution) + "</TEXTAREA><br/>");
				break;
			case 6:  // 5-Star rating
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" + amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span id='vote' style='color:#990000;font-size:small;'>(click a star):</span><br/>");
				for (int istar=1;istar<6;istar++) {
					buf.append("<img src='https://images.chemvantage.org/star1.gif' id='" + istar + "' style='width:30px; height:30px;' alt='empty star' />");
				}
				buf.append("<br/>");
				break;
			case 7:  // Short ESSAY
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=50 wrap=soft>" + amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#990000;font-size:small;'>(800 characters max):</span><br/>");
				buf.append("<div style='border: solid 2px;width:300px;height:100px'></div>");
				buf.append("<br/>");
				break;
			case 8:  // Chemical Structure
				buf.append("Question Text:<br/><TEXTAREA name=QuestionText rows=5 cols=60 wrap=soft>" + amp2html(text) + "</TEXTAREA><br/>");
				buf.append("<span style='color:#B20000;font-size: small;'>Draw the expected structure in Ketcher or load it from a SMILES string, and keep the molfile data below as the stored correct answer.</span><br/>");
				buf.append(renderChemicalStructureComposer("CorrectAnswer", correctAnswer, false, true, true));
				break;
			}
		}
		catch (Exception e) {
			buf.append(e.toString());
		}
		return buf.toString();
	}

	// The composer uses a same-origin bridge page that loads the hosted Ketcher assets and synchronizes molfile data back into the form.
	String renderChemicalStructureComposer(String fieldName, String molfile, boolean readOnly, boolean showMolfileDataPanel, boolean showControls) {
		StringBuffer buf = new StringBuffer();
		String preparedMolfile = ChemicalStructureScorer.prepareMolfileForEditor(molfile);
		String safeMolfile = amp2html(preparedMolfile == null ? "" : preparedMolfile);
		String textareaId = "structure_" + fieldName.replaceAll("[^A-Za-z0-9]", "_");
		String frameId = textareaId + "_frame";
		String statusId = textareaId + "_status";
		String detailsId = textareaId + "_details";
		String smilesId = textareaId + "_smiles";
		String syncId = textareaId + "_sync";
		String clearId = textareaId + "_clear";
		String drawModeId = textareaId + "_mode_draw";
		String smilesModeId = textareaId + "_mode_smiles";
		String drawPanelId = textareaId + "_panel_draw";
		String smilesPanelId = textareaId + "_panel_smiles";
		boolean useModeSelector = !readOnly && !showControls;
		String bridgeNonce = Long.toHexString(Double.doubleToLongBits(Math.random()));
		buf.append("<div style='margin:0.75rem 0;'>");
		if (useModeSelector) {
			buf.append("<div style='color:#B20000;font-size:small;margin-bottom:0.5rem;'>");
			buf.append("Choose input mode: ");
			buf.append("<a href='#' id='" + drawModeId + "'>Draw structure in a Ketcher window</a>");
			buf.append(" &nbsp;|&nbsp; ");
			buf.append("<a href='#' id='" + smilesModeId + "'>Enter structure as SMILES text</a>");
			buf.append("</div>");

			buf.append("<div id='" + smilesPanelId + "' style='display:none;margin-bottom:0.75rem;'>");
			buf.append("<label for='" + smilesId + "' style='display:block;font-size:small;color:#555;margin-bottom:0.25rem;'>Enter a SMILES string: </label>");
			buf.append("<div style='display:flex;flex-wrap:wrap;gap:0.5rem;align-items:flex-start;margin-bottom:0.5rem;'>");
			buf.append("<input id='" + smilesId + "' type='text' inputmode='text' spellcheck='false' autocapitalize='off' autocomplete='off' aria-label='Enter chemical structure as a SMILES string' placeholder='Example SMILES: C1=CC=CC=C1' style='flex:1 1 26rem;min-width:16rem;max-width:42rem;' />");
			buf.append("</div>");
			buf.append("<div style='font-size:small;'><a href='https://www.daylight.com/dayhtml/doc/theory/theory.smiles.html' target='_blank' rel='noopener noreferrer'>SMILES help and tutorial</a></div>");
			buf.append("</div>");

			buf.append("<div id='" + drawPanelId + "' style='display:none;margin-bottom:0.75rem;'>");
			buf.append("<iframe id='" + frameId + "' title='Ketcher chemical structure editor' src='about:blank' data-bridge-src='/ketcher-bridge.html?cv=" + bridgeNonce + "' style='width:100%;max-width:750px;height:520px;border:1px solid #c7c7c7;background:#fff;'></iframe>");
			buf.append("<div style='font-size:small;margin-top:0.5rem;'><a href='https://github.com/epam/ketcher/blob/v3.15.0/documentation/help.md#ketcher-molecules-mode' target='_blank' rel='noopener noreferrer'>Ketcher drawing help</a></div>");
			buf.append("</div>");
		} else {
			if (!readOnly) {
				buf.append("<label for='" + smilesId + "' style='display:block;font-size:small;color:#555;margin-bottom:0.25rem;'>Enter a SMILES string. It will be loaded automatically when you click Preview.</label>");
				buf.append("<div style='display:flex;flex-wrap:wrap;gap:0.5rem;align-items:flex-start;margin-bottom:0.5rem;'>");
				buf.append("<input id='" + smilesId + "' type='text' inputmode='text' spellcheck='false' autocapitalize='off' autocomplete='off' aria-label='Enter chemical structure as a SMILES string' placeholder='Example SMILES: C1=CC=CC=C1' style='flex:1 1 26rem;min-width:16rem;max-width:42rem;' />");
				buf.append("</div>");
				buf.append("<div style='font-size:small;margin-bottom:0.5rem;'><a href='https://www.daylight.com/dayhtml/doc/theory/theory.smiles.html' target='_blank' rel='noopener noreferrer'>SMILES help and tutorial</a></div>");
			}
			buf.append("<iframe id='" + frameId + "' title='Ketcher chemical structure editor' src='/ketcher-bridge.html?cv=" + bridgeNonce + "' style='width:100%;max-width:750px;height:520px;border:1px solid #c7c7c7;background:#fff;'></iframe>");
			if (!readOnly) buf.append("<div style='font-size:small;margin-top:0.5rem;margin-bottom:0.75rem;'><a href='https://github.com/epam/ketcher/blob/v3.15.0/documentation/help.md#ketcher-molecules-mode' target='_blank' rel='noopener noreferrer'>Ketcher drawing help</a></div>");
		}
		buf.append("<span id='" + statusId + "' style='display:none' aria-hidden='true'></span>");
		buf.append("<button type='button' id='" + syncId + "' style='display:none' aria-hidden='true'>Sync structure now</button>");
		buf.append("<button type='button' id='" + clearId + "' style='display:none' aria-hidden='true'>Clear editor</button>");
		if (showMolfileDataPanel) {
			buf.append("<details id='" + detailsId + "'><summary>Advanced: view stored molfile data</summary>");
			buf.append("<label for='" + textareaId + "'>" + (readOnly?"Stored molfile data":"Molfile data used for scoring") + ":</label><br/>");
			buf.append("<textarea id='" + textareaId + "'" + (fieldName==null?"":" name='" + fieldName + "'") + (readOnly?" readonly":"") + " rows=12 cols=80 wrap=off placeholder='Structure molfile data is synchronized automatically.'>" + safeMolfile + "</textarea>");
			buf.append("</details>");
		} else {
			buf.append("<textarea id='" + textareaId + "'" + (fieldName==null?"":" name='" + fieldName + "'") + (readOnly?" readonly":"") + " style='display:none' aria-hidden='true'>" + safeMolfile + "</textarea>");
		}
		if (!readOnly) {
			buf.append("<script>(function(){"
					+ "const frame=document.getElementById('" + frameId + "');"
					+ "const field=document.getElementById('" + textareaId + "');"
					+ "const status=document.getElementById('" + statusId + "');"
					+ "const smilesField=document.getElementById('" + smilesId + "');"
					+ "const drawModeLink=document.getElementById('" + drawModeId + "');"
					+ "const smilesModeLink=document.getElementById('" + smilesModeId + "');"
					+ "const drawPanel=document.getElementById('" + drawPanelId + "');"
					+ "const smilesPanel=document.getElementById('" + smilesPanelId + "');"
					+ "const useModeSelector=" + useModeSelector + ";"
					+ "const syncButton=document.getElementById('" + syncId + "');"
					+ "const clearButton=document.getElementById('" + clearId + "');"
					+ "const hostForm=frame?frame.closest('form'):null;"
					+ "const hostSessionId='sess-' + Date.now() + '-' + Math.random().toString(36).slice(2);"
					+ "let ready=false;"
					+ "let editorLoadRequested=!useModeSelector;"
					+ "let readyProbeTimer=null;"
					+ "let readyProbeCount=0;"
					+ "let expectedPreloadRequestId='';"
					+ "let expectedStructureRequestId='';"
					+ "let expectedSyncRequestId='';"
					+ "let pendingStructureText='';"
					+ "let pendingSubmit=false;"
					+ "let pendingSubmitter=null;"
					+ "let submitTimeout=null;"
					+ "let submitReadyWaitTimer=null;"
					+ "let bypassSubmitHook=false;"
					+ "let allowTemplateOverwrite=false;"
					+ "function updateStatus(message,color){status.textContent=message;if(color)status.style.color=color;}"
					+ "function nextRequestId(prefix){return prefix + '-' + Date.now() + '-' + Math.random().toString(36).slice(2);}"
					+ "function post(type,payload){if(frame&&frame.contentWindow){frame.contentWindow.postMessage(Object.assign({source:'chemvantage-ketcher-host',type:type,sessionId:hostSessionId},payload||{}),'*');}}"
					+ "function isEmptyTemplate(mol){if(!mol||!String(mol).trim())return true;const text=String(mol);if(/\\n\\s*0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0\\s+0999\\s+V2000/.test(text))return true;if(/M\\s+V30\\s+COUNTS\\s+0\\s+0\\s+0\\s+0\\s+0/.test(text))return true;return false;}"
					+ "function stopReadyProbes(){if(readyProbeTimer){clearInterval(readyProbeTimer);readyProbeTimer=null;}}"
					+ "function ensureEditorLoaded(){if(!frame||editorLoadRequested)return;editorLoadRequested=true;const bridgeSrc=frame.getAttribute('data-bridge-src');if(bridgeSrc&&frame.getAttribute('src')!==bridgeSrc){frame.setAttribute('src',bridgeSrc);}if(!ready)startReadyProbes();}"
					+ "function showMode(mode){if(!useModeSelector)return;ensureEditorLoaded();if(mode==='draw'){if(drawPanel)drawPanel.style.display='';if(smilesPanel)smilesPanel.style.display='none';}else if(mode==='smiles'){if(smilesPanel)smilesPanel.style.display='';if(drawPanel)drawPanel.style.display='none';}}"
					+ "function startReadyProbes(){if(ready||readyProbeTimer)return;readyProbeCount=0;post('readyCheck',{requestId:nextRequestId('ready')});readyProbeTimer=window.setInterval(function(){if(ready){stopReadyProbes();return;}readyProbeCount++;post('readyCheck',{requestId:nextRequestId('ready')});if(readyProbeCount>50){stopReadyProbes();updateStatus('Ketcher editor is taking longer than expected to load.','#B20000');}},350);}"
					+ "function beginPreload(){if(!ready||!field.value)return;expectedPreloadRequestId=nextRequestId('preload');updateStatus('Loading saved structure...','#555');post('setMolfile',{molfile:field.value,requestId:expectedPreloadRequestId});}"
					+ "function loadStructureText(structureText){var structure=(structureText||'').trim();if(!structure){updateStatus('Enter a SMILES string to load a structure.','#B20000');return;}pendingStructureText=structure;ensureEditorLoaded();if(!ready){updateStatus('Waiting for editor before loading SMILES...','#555');startReadyProbes();return;}expectedPreloadRequestId='';expectedStructureRequestId=nextRequestId('structure');updateStatus('Loading structure from SMILES...','#555');post('setStructure',{structure:structure,requestId:expectedStructureRequestId});}"
					+ "function requestSyncForSubmit(){if(!ready)return;expectedPreloadRequestId='';expectedSyncRequestId=nextRequestId('sync');post('getMolfile',{requestId:expectedSyncRequestId});}"
					+ "function finalizeSubmit(){if(!pendingSubmit||!hostForm)return;pendingSubmit=false;if(submitTimeout){clearTimeout(submitTimeout);submitTimeout=null;}if(submitReadyWaitTimer){clearInterval(submitReadyWaitTimer);submitReadyWaitTimer=null;}bypassSubmitHook=true;if(hostForm.requestSubmit){if(pendingSubmitter)hostForm.requestSubmit(pendingSubmitter);else hostForm.requestSubmit();}else hostForm.submit();}"
					+ "window.addEventListener('message',function(event){const data=event.data||{};if(data.source!=='chemvantage-ketcher-bridge')return;"
					+ "if(frame&&event.source!==frame.contentWindow)return;"
					+ "if(data.type!=='ready'&&data.sessionId&&data.sessionId!==hostSessionId)return;"
					+ "if(data.type==='ready'){if(ready)return;ready=true;stopReadyProbes();updateStatus('Editor ready. Structure data syncs automatically.','#0a6');if(field.value)beginPreload();else if(pendingStructureText)loadStructureText(pendingStructureText);if(pendingSubmit&&!expectedSyncRequestId&&!expectedStructureRequestId){updateStatus('Syncing structure before submit...','#555');requestSyncForSubmit();}if(window.location.hash){var _anchor=document.getElementById(window.location.hash.substring(1));if(_anchor)_anchor.scrollIntoView();}return;}"
					+ "if(data.type==='setMolfileResult'){const requestId=String(data.requestId||'');const isPreloadReply=expectedPreloadRequestId&&(!requestId||requestId===expectedPreloadRequestId);if(isPreloadReply){expectedPreloadRequestId='';const loaded=data.molfile||'';if(!isEmptyTemplate(loaded)){field.value=loaded;allowTemplateOverwrite=false;updateStatus('Structure synchronized.','#0a6');}else{updateStatus('Saved structure load could not be confirmed.','#B20000');}return;}updateStatus(field.value?'Saved structure loaded.':'Editor cleared.','#0a6');return;}"
					+ "if(data.type==='setStructureResult'){const requestId=String(data.requestId||'');if(expectedStructureRequestId&&requestId&&requestId!==expectedStructureRequestId)return;expectedStructureRequestId='';const loaded=data.molfile||'';if(!isEmptyTemplate(loaded)){field.value=loaded;allowTemplateOverwrite=false;pendingStructureText='';updateStatus('Structure loaded from SMILES.','#0a6');if(pendingSubmit&&!expectedSyncRequestId){updateStatus('Syncing structure before submit...','#555');requestSyncForSubmit();}}else{updateStatus('Unable to load the SMILES string into the editor.','#B20000');if(pendingSubmit)finalizeSubmit();}return;}"
					+ "if(data.type==='molfile'){const requestId=String(data.requestId||'');if(!expectedSyncRequestId)return;if(requestId&&requestId!==expectedSyncRequestId)return;expectedSyncRequestId='';const incoming=data.molfile||'';const incomingEmpty=isEmptyTemplate(incoming);const existingEmpty=isEmptyTemplate(field.value||'');if(incomingEmpty&&field.value&&!existingEmpty&&!allowTemplateOverwrite){updateStatus('Ignoring empty template from editor sync.','#555');if(pendingSubmit)finalizeSubmit();return;}field.value=incoming;updateStatus(field.value&&!incomingEmpty?'Structure synchronized.':'Editor is empty.','#0a6');if(field.value&&!incomingEmpty)allowTemplateOverwrite=false;if(pendingSubmit)finalizeSubmit();return;}"
					+ "else if(data.type==='error'){if(data.requestId&&expectedSyncRequestId&&data.requestId!==expectedSyncRequestId&&!String(data.requestId).startsWith('initial-'))return;if(data.requestId&&data.requestId===expectedSyncRequestId)expectedSyncRequestId='';updateStatus(data.message||'Unable to communicate with Ketcher.','#B20000');if(pendingSubmit)finalizeSubmit();}});"
					+ "if(drawModeLink)drawModeLink.addEventListener('click',function(event){event.preventDefault();showMode('draw');});"
					+ "if(smilesModeLink)smilesModeLink.addEventListener('click',function(event){event.preventDefault();showMode('smiles');});"
					+ "if(syncButton)syncButton.addEventListener('click',function(){if(!ready){updateStatus('Waiting for editor to finish loading...','#555');startReadyProbes();return;}updateStatus('Syncing structure...','#555');requestSyncForSubmit();});"
					+ "if(clearButton)clearButton.addEventListener('click',function(){allowTemplateOverwrite=true;field.value='';if(ready)post('clear',{requestId:nextRequestId('clear')});updateStatus('Editor cleared.','#0a6');});"
					+ "if(frame)frame.addEventListener('load',function(){if(editorLoadRequested&&!ready)startReadyProbes();});"
					+ "if(hostForm)hostForm.addEventListener('submit',function(event){if(bypassSubmitHook){bypassSubmitHook=false;return;}expectedPreloadRequestId='';pendingSubmit=true;pendingSubmitter=event.submitter||null;event.preventDefault();const smilesModeActive=useModeSelector&&smilesPanel&&smilesPanel.style.display!=='none';const shouldLoadSmilesForSubmit=!!(smilesField&&smilesField.value&&smilesField.value.trim()&&(!useModeSelector||smilesModeActive));if(shouldLoadSmilesForSubmit){loadStructureText(smilesField.value);submitTimeout=window.setTimeout(function(){updateStatus('Timed out waiting for editor sync; submitting current molfile.','#B20000');finalizeSubmit();},12000);return;}if(!editorLoadRequested){finalizeSubmit();return;}if(!ready){updateStatus('Waiting for editor before submit...','#555');startReadyProbes();if(submitReadyWaitTimer){clearInterval(submitReadyWaitTimer);}submitReadyWaitTimer=window.setInterval(function(){if(!pendingSubmit){clearInterval(submitReadyWaitTimer);submitReadyWaitTimer=null;return;}if(ready&&!expectedSyncRequestId&&!expectedStructureRequestId){clearInterval(submitReadyWaitTimer);submitReadyWaitTimer=null;updateStatus('Syncing structure before submit...','#555');requestSyncForSubmit();}},250);}else{updateStatus('Syncing structure before submit...','#555');requestSyncForSubmit();}submitTimeout=window.setTimeout(function(){updateStatus('Timed out waiting for editor sync; submitting current molfile.','#B20000');finalizeSubmit();},12000);});"
					+ "if(!useModeSelector)ensureEditorLoaded();"
					+ "})();</script>");
		}
		buf.append("</div>");
		return buf.toString();
	}

	String renderChemicalStructurePreview(String molfile, String caption) {
		return renderChemicalStructurePreview(molfile, caption, true);
	}

	String renderChemicalStructurePreview(String molfile, String caption, boolean showMolfileData) {
		if (molfile == null || molfile.isBlank()) return "<div style='border:1px solid #c7c7c7;padding:0.75rem;background:#fff;'>No structure data provided.</div>";
		StringBuffer buf = new StringBuffer();
		String svg = ChemicalStructureScorer.renderSvg(molfile);
		if (svg != null && !svg.isEmpty()) {
			svg = svg.replaceFirst("<svg\\s+", "<svg style='display:block;width:100%;height:auto;max-width:100%;' ");
		}
		buf.append("<div style='border:1px solid #c7c7c7;padding:0.75rem;background:#fff;display:inline-block;width:100%;max-width:300px;box-sizing:border-box;'>");
		if (caption != null && !caption.isEmpty()) buf.append("<div style='font-size:small;color:#555;margin-bottom:0.5rem;'>" + caption + "</div>");
		if (svg == null || svg.isEmpty()) buf.append("<div style='color:#990000;'>Unable to render this structure. The raw molfile is shown below.</div>");
		else buf.append(svg);
		buf.append("</div>");
		if (showMolfileData) buf.append("<details><summary>View molfile data</summary><textarea rows=12 cols=80 wrap=off readonly>" + amp2html(molfile) + "</textarea></details>");
		return buf.toString();
	}

	boolean hasNoCorrectAnswer() {
		return this.correctAnswer == null || this.correctAnswer.isEmpty();
	}
	
	boolean hasACorrectAnswer() {
		return !hasNoCorrectAnswer();
	}
	
	boolean isCorrect(String studentAnswer){
		if (studentAnswer == null || studentAnswer.isEmpty() || hasNoCorrectAnswer()) return false;
		switch (getQuestionType()) {
		case 4:  // Fill-in-the-word
			Collator compare = Collator.getInstance();
			compare.setStrength(Collator.PRIMARY);
			studentAnswer = studentAnswer.replaceAll("\\W", "");
			String[] correctAnswers = correctAnswer.split(","); // break comma-separated list into array
			for (int i=0;i<correctAnswers.length;i++) {
				correctAnswers[i] = correctAnswers[i].replaceAll("\\W","");
				if (compare.equals(studentAnswer,correctAnswers[i])) return true;
				else if (!strictSpelling && closeEnough(studentAnswer.toLowerCase(),correctAnswers[i].toLowerCase())) return true;
			}
			return false;
		case 5: // Numeric Answer
			return hasCorrectSigFigs(studentAnswer) && agreesToRequiredPrecision(studentAnswer);
		case 6: // Five star rating
			return !studentAnswer.isEmpty();
		case 7: // ESSAY
			return false;  // graded separately in Homework to get feedback
		case 8: // Chemical Structure
			return ChemicalStructureScorer.compare(correctAnswer, studentAnswer).matched();
		default:  // exact match to non-numeric answer (MULTIPLE_CHOICE, TRUE_FALSE, SELECT_MULTIPLE)
			return correctAnswer.equals(studentAnswer);
		}
	}
	
	boolean closeEnough(String studentAnswer,String correctAnswer) {
		if (correctAnswer.length() < 4) return false;  			// exact answer needed for 3-char answers
		int maxEditDistance = correctAnswer.length()<6?1:2;		// 1 error allowed for 4,5-char answers; otherwise 2 errors allowed

		if (Math.abs(correctAnswer.length()-studentAnswer.length())>maxEditDistance) return false;   // trivial estimate of min edit distance

		if (editDist(studentAnswer,correctAnswer,studentAnswer.length(),correctAnswer.length(),0,maxEditDistance) > maxEditDistance) return false;

		return true;
	}
	
	static int editDist(String str1, String str2, int m, int n, int d, int maxEditDistance) {
		/* 
		 * Modified Levenshtein algorithm for computing the edit distance between 2 strings of length m and n, and comparing it to a
		 * maxEditDistance, keeping in mind that insertions or deletions that increase the distance from the m=n diagonal incur extra cost.
		 */
		if (m == 0) return n; 	  
		if (n == 0) return m; 

		if (str1.charAt(m - 1) == str2.charAt(n - 1)) return editDist(str1, str2, m - 1, n - 1, d, maxEditDistance); 
		else if (d >= maxEditDistance) return d+1;
		else return 1 + min(d+1+Math.abs(n-1-m)>maxEditDistance?maxEditDistance:editDist(str1, str2, m, n-1, d+1, maxEditDistance),  // Insert 
							d+1+Math.abs(n-m+1)>maxEditDistance?maxEditDistance:editDist(str1, str2, m-1, n, d+1, maxEditDistance),  // Remove 
							d+1+Math.abs(n-m)>maxEditDistance?maxEditDistance:editDist(str1, str2, m-1, n-1, d+1, maxEditDistance)); // Replace 
	}

	static int min(int x, int y, int z) { /*This code is contributed by Rajat Mishra*/
		if (x <= y && x <= z) return x; 
		if (y <= x && y <= z) return y; 
		else return z; 
	} 

	boolean hasCorrectSigFigs(String studentAnswer) {
		if (significantFigures==0) return true;  // no sig figs required
		
		studentAnswer = studentAnswer.replaceAll(",", "").replaceAll("\\s", "");  // removes comma separators and whitespace from numbers
		
		int exponentPosition = studentAnswer.toUpperCase().indexOf("E");  		// turns "e" to "E"
		String mantissa = exponentPosition>=0?studentAnswer.substring(0,exponentPosition):studentAnswer;
		
		// check to see if the value has a trailing zero before the decimal place
		// this check has been disabled to forgive lack of a trailing decimal
		//if (mantissa.indexOf(".")==-1 && mantissa.endsWith("0")) return false;
		
		// strip leading (non-significant) zeros, decimals and signs
		while (mantissa.startsWith("0") || mantissa.startsWith(".") || mantissa.startsWith("-") || mantissa.startsWith("+")) mantissa = mantissa.substring(1);
		
		// remove embedded decimal point, if any
		mantissa = mantissa.replace(".","");
		
		// see if number of remaining digits matches this.significantFigures
		if (mantissa.length()==this.significantFigures) return true;
		
		return false;
	}
	
	boolean agreesToRequiredPrecision(String studentAnswer) {
		// This method is used for numeric questions to determine if the student's response agrees with the correct answer to within the required precision
		if (!"NUMERIC".equals(type) || studentAnswer == null || studentAnswer.isEmpty() || hasNoCorrectAnswer()) return false;
		if (studentAnswer.length()<3 && (studentAnswer.endsWith("+") || studentAnswer.endsWith("-"))) {  // deal with oxidation state like 5+ or 3-
			char sign = studentAnswer.charAt(studentAnswer.length()-1);
			studentAnswer = sign + studentAnswer.substring(0,studentAnswer.length()-1);
		}
		try {
			studentAnswer = studentAnswer.replaceAll(",", "").replaceAll("\\s", "").toUpperCase();  // removes comma separators and whitespace from numbers, turns e to E
			double dStudentAnswer = Double.parseDouble(parseString(studentAnswer,0));
			double dCorrectAnswer = Double.parseDouble(parseString(correctAnswer));
			if (requiredPrecision==0.) return Double.compare(dStudentAnswer,dCorrectAnswer)==0.; // exact match required
			if (dCorrectAnswer==0.) return dStudentAnswer==0.;  // exact match is always required if the correct answer is zero; avoids divide-by-zero in next line
			else return (Math.abs((dStudentAnswer-dCorrectAnswer)/dCorrectAnswer)*100 <= requiredPrecision?true:false);  // checks for agreement to required precision
		} catch (Exception e) {
			return false;
		}
	}
	
	String parseNumber(String input) {  // converts input like "forty-two" to "42"
		if (input==null || input.isEmpty()) return input;
		String words = input.replaceAll("-", " ").toLowerCase().replaceAll(" and", " ");
		String[] splitParts = words.trim().split("\\s+");
		int n = 0;
		int m = 0;
		for (String s : splitParts) {
			switch  (s) {
			case "zero": n += 0; break;
			case "one": n += 1; break;
			case "two": n += 2; break;
			case "three": n += 3; break;
			case "four": n += 4; break;
			case "five": n += 5; break;
			case "six": n += 6; break;
			case "seven": n += 7; break;
			case "eight": n += 8; break;
			case "nine": n += 9; break;
			case "ten": n += 10; break;
			case "eleven": n += 11; break;
			case "twelve": n += 12; break;
			case "thirteen": n += 13; break;
			case "fourteen": n += 14; break;
			case "fifteen": n += 15; break;
			case "sixteen": n += 16; break;
			case "seventeen": n += 17; break;
			case "eighteen": n += 18; break;
			case "nineteen": n += 19; break;
			case "twenty": n += 20; break;
			case "thirty": n += 30; break;
			case "forty": n += 40; break;
			case "fifty": n += 50; break;
			case "sixty": n += 60; break;
			case "seventy": n += 70; break;
			case "eighty": n += 80; break;
			case "ninety": n += 90; break;
			case "hundred": n *= 100; break;
			case "thousand": 
				n *= 1000; 
				m += n; 
				n=0; 
				break;
			case "million": 
				n *= 1000000; 
				m += n; 
				n=0; 
				break;
			default: return input;  // a word was not recognized
			}
		}
		return Integer.toString(m+n);
	}
	
	public Question clone() {
		try {
			return (Question) super.clone();
		} catch (Exception e) {
			return null;
		}
	}
	
	// The following methods are from the original CharHider class to guard against
	// inadvertent mistakes in interpreting user input, especially in Question items.

	static String quot2html(String oldString) {
		if (oldString == null) return "";
		// recursive method replaces single quotes with &#39; for HTML pages
		int i = oldString.indexOf('\'',0);
		return i<0?oldString:quot2html(new StringBuffer(oldString).replace(i,i+1,"&#39;").toString(),i);
	}

	static String quot2html(String oldString,int fromIndex) {
		// recursive method replaces single quotes with &#39; for HTML pages
		int i = oldString.indexOf('\'',fromIndex);
		return i<0?oldString:quot2html(new StringBuffer(oldString).replace(i,i+1,"&#39;").toString(),i);
	}

	static String amp2html(String oldString) {
		if (oldString == null) return "";
		// recursive method replaces ampersands with &amp; for preloading Greek/special characters in text fields in HTML forms
		int i = oldString.indexOf('&',0);
		//		    return i<0?oldString:new StringBuffer(oldString).replace(i,i+1,"&amp;").toString();
		return i<0?oldString:amp2html(new StringBuffer(oldString).replace(i,i+1,"&amp;").toString(),i+1);
	}

	static String amp2html(String oldString,int fromIndex) {
		// recursive method replaces ampersands with &amp; for preloading Greek/special characters in text fields in HTML forms
		int i = oldString.indexOf('&',fromIndex);
		return i<0?oldString:amp2html(new StringBuffer(oldString).replace(i,i+1,"&amp;").toString(),i+1);
	}
	
	String parseFractions(String expression) {
		return parseFractions(expression,0);
	}
	
	String parseFractions(String expression, int startIndex) {
		// This method uses parentheses and the pipe character (|) to identify numerator and denominator of a fraction
		// to be displayed in a vertical format. The encoding is like (| numerator | denominator |)
		final String num = "<span style='display: inline-block;vertical-align: middle;font-size: smaller'><div style='text-align: center;border-bottom: 1px solid black;'>";
		final String pip = "</div><div style='text-align: center;'>";
		final String den = "</div></span>";
		
		int i = expression.indexOf("(|",startIndex);  	// marks the first start of a numerator
		if (i<0) return expression;						// quick return if no fractions found
		
		int i2 = expression.indexOf("(|",i+2);			// marks the second start of a numerator
		int k = expression.indexOf("|)",i+2);			// marks the end of a denominator
		if (i2>0 && i2<k) {								// second start is before first end; start recursion
			expression = parseFractions(expression,i2);
			k = expression.indexOf("|)",i+2);			// recalculate the end due to substitutions made
		}
		if (k<0) return expression; 					// there must be and end-of-fraction marker to proceed
		
		int j = expression.indexOf("|",i+2);			// marks separator between numerator and denominator
		
		// Replace the markers with CSS style tags:
		if (j>0 && j<k) {
			expression = expression.substring(0,i) + num + expression.substring(i+2,j) + pip + expression.substring(j+1,k) + den + expression.substring(k+2);
			k = k - 5 + num.length() + pip.length() + den.length();
		}
		
		// Test to see if there is another fraction at this level:
		return parseFractions(expression,k+1);
	}
	
	public String getCorrectAnswerForSage() { 
		// similar to getCorrectAnswer but expands MULTIPLE_CHOICE and SELECT_MULTIPLE answers
		switch (getQuestionType()) {
		case 1: // MULTIPLE_CHOICE
		case 3: // SELECT_MULTIPLE
			return correctAnswer;
		case 4: // FILL-IN-WORD
			String[] answers = correctAnswer.split(",");
			return answers[0];
		case 5: // NUMERIC
			if (requiredPrecision == 0) return parseString(correctAnswer); // exact answer
			double numericValue = Double.parseDouble(parseString(correctAnswer));
			int sf = significantFigures>0?significantFigures:(int)Math.ceil(-Math.log10(requiredPrecision/100.))+2;
			return ("%." + sf + "G").formatted(numericValue);
		default: return correctAnswer;
		}
	}
	
}
