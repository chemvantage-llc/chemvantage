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
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Concept implements Serializable {
	private static final Logger logger = Logger.getLogger(Concept.class.getName());
	@Serial
	private static final long serialVersionUID = 137L;
	@Id 	Long id;
	@Index 	String orderBy;
	 		String title;
	 		String summary;
	
	Concept() {}

	Concept(String title,String orderBy) {
		this.title = title;
		this.orderBy = orderBy;
	}
	
	String getSummary() {
		if (summary != null) return summary;

		try {
			String generatedSummary = parseSummaryResponse(requestSummaryFromGemini());
			this.summary = generatedSummary;
			ofy().save().entity(this);
			return this.summary;
		} catch (Exception e) {
			logger.log(Level.WARNING, "Unable to generate summary for concept " + id, e);
			return "Sorry, Sage is unable to provide a summary of this concept at the moment.";
		}
	}

	static String parseSummaryResponse(String responseText) throws IOException {
		if (responseText == null || responseText.isBlank()) {
			throw new IOException("Google Gen AI response did not contain summary text.");
		}
		JsonObject json = JsonParser.parseString(responseText.trim()).getAsJsonObject();
		JsonElement summaryElement = json.get("summary");
		if (summaryElement == null || !summaryElement.isJsonPrimitive()
				|| !summaryElement.getAsJsonPrimitive().isString()
				|| summaryElement.getAsString().isBlank()) {
			throw new IOException("Google Gen AI response did not contain a valid summary.");
		}
		String generatedSummary = Question.sanitizeExplanationHtml(summaryElement.getAsString());
		if (generatedSummary.isBlank()) {
			throw new IOException("Google Gen AI summary contained no displayable content.");
		}
		return generatedSummary;
	}

	String requestSummaryFromGemini() throws IOException {
		Map<String,Object> responseSchema = Map.of(
				"type", "object",
				"properties", Map.of("summary", Map.of("type", "string")),
				"required", List.of("summary")
		);
		GenerateContentConfig generationConfig = GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.responseJsonSchema(responseSchema)
				.candidateCount(1)
				.build();
		String prompt = "You are Sage, a general chemistry tutor. Provide a concise, accurate summary of "
				+ "key_concept for a college-level general chemistry student. Explain the central ideas, "
				+ "relevant chemical relationships or equations, and a helpful example when appropriate. "
				+ "Format the summary as an HTML fragment using paragraphs, lists, subscript and superscript "
				+ "as needed, without scripts, links, markdown or code fences. "
				+ "Return ONLY a JSON object with a single string field named summary.\n\n"
				+ "key_concept:\n" + this.title;

		try (Client client = Client.builder()
				.enterprise(true)
				.project(Subject.getProjectId())
				.location(Subject.getGemModelLocation())
				.httpOptions(HttpOptions.builder().apiVersion("v1").build())
				.build()) {
			GenerateContentResponse response = client.models.generateContent(Subject.getGemModel(), prompt, generationConfig);
			return response.text();
		}
	}
}