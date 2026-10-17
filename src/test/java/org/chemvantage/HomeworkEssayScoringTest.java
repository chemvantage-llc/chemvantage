package org.chemvantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class HomeworkEssayScoringTest {

	@Test
	void extractsScoreAfterNonTextOutputContent() throws IOException {
		JsonObject response = JsonParser.parseString("""
				{
				  "output": [
				    {"content": [{"type": "summary_text", "text": "I will evaluate this answer."}]},
				    {"content": [{"type": "output_text", "text": "{\\"score\\":4,\\"feedback\\":\\"Good explanation.\\"}"}]}
				  ]
				}
				""").getAsJsonObject();

		JsonObject score = Homework.extractEssayScore(response);

		assertEquals(4, score.get("score").getAsInt());
		assertEquals("Good explanation.", score.get("feedback").getAsString());
	}

	@Test
	void rejectsEssayOutputWithoutScore() {
		JsonObject response = JsonParser.parseString("""
				{"output": [{"content": [{"type": "output_text", "text": "{\\"feedback\\":\\"Try again.\\"}"}]}]}
				""").getAsJsonObject();

		assertThrows(IOException.class, () -> Homework.extractEssayScore(response));
	}

	@Test
	void rejectsEssayScoresOutsideFivePointRange() {
		JsonObject response = JsonParser.parseString("""
				{"output": [{"content": [{"type": "output_text", "text": "{\\"score\\":6,\\"feedback\\":\\"Good work.\\"}"}]}]}
				""").getAsJsonObject();

		assertThrows(IOException.class, () -> Homework.extractEssayScore(response));
	}
}
