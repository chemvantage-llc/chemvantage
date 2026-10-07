/*  ChemVantage - A web application for online chemistry education
*   Copyright (C) 2011 ChemVantage LLC
*
*   This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*/

package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.cloud.datastore.Cursor;
import com.google.cloud.datastore.QueryResults;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.cmd.Query;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/QuestionTimingStats")
public class QuestionTimingStats extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	private static final int TRANSACTION_BATCH_SIZE = 1000;
	private static final int WRITE_BATCH_SIZE = 500;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (!Utilities.isAdminAuthenticated(request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Admin access required");
			return;
		}

		try {
			RefreshResult result = refreshStatistics();
			response.setContentType("text/plain");
			response.getWriter().printf(
					"Updated timing statistics for %d questions from %d homework transactions.%n",
					result.questionCount(), result.transactionCount());
		} catch (Exception e) {
			getServletContext().log("Failed to refresh question timing statistics.", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Failed to refresh question timing statistics.");
		}
	}

	private RefreshResult refreshStatistics() {
		Map<Long, List<Long>> elapsedTimesByQuestion = new HashMap<>();
		Map<Long, List<Long>> firstTryCorrectTimesByQuestion = new HashMap<>();
		Map<AttemptKey, FirstAttempt> firstAttempts = new HashMap<>();
		Set<Long> questionIds = new HashSet<>();
		long transactionCount = 0;

		Query<HWTransaction> query = ofy().load().type(HWTransaction.class).filter("presented !=", null).limit(TRANSACTION_BATCH_SIZE);
		while (true) {
			QueryResults<HWTransaction> results = query.iterator();
			int batchCount = 0;
			while (results.hasNext()) {
				HWTransaction transaction = results.next();
				batchCount++;
				transactionCount++;
				if (transaction.questionId > 0) questionIds.add(transaction.questionId);

				Long elapsedMs = elapsedTimeMs(transaction);
				if (elapsedMs != null && transaction.questionId > 0) {
					elapsedTimesByQuestion
							.computeIfAbsent(transaction.questionId, id -> new ArrayList<>())
							.add(elapsedMs);
				}

				if (transaction.questionId > 0 && transaction.userId != null) {
					AttemptKey attemptKey = new AttemptKey(transaction.userId,
							transaction.assignmentId, transaction.questionId);
					FirstAttempt firstAttempt = firstAttempts.computeIfAbsent(attemptKey,
							id -> new FirstAttempt());
					firstAttempt.consider(transaction);
				}
			}

			if (batchCount < TRANSACTION_BATCH_SIZE) break;
			Cursor nextCursor = results.getCursorAfter();
			query = ofy().load().type(HWTransaction.class)
					.filter("presented !=", null)
					.limit(TRANSACTION_BATCH_SIZE).startAt(nextCursor);
		}

		for (FirstAttempt firstAttempt : firstAttempts.values()) {
			HWTransaction transaction = firstAttempt.transaction;
			if (firstAttempt.hasUndatedAttempt || transaction == null
					|| transaction.scoreOverride
					|| transaction.possibleScore <= 0
					|| transaction.score < transaction.possibleScore) continue;

			Long elapsedMs = elapsedTimeMs(transaction);
			if (elapsedMs != null) {
				firstTryCorrectTimesByQuestion
						.computeIfAbsent(transaction.questionId, id -> new ArrayList<>())
						.add(elapsedMs);
			}
		}

		Date computedAt = new Date();
		List<QuestionTimingStatsData> statsToSave = new ArrayList<>();
		for (Long questionId : questionIds) {
			List<Long> allTimes = elapsedTimesByQuestion.getOrDefault(questionId, List.of());
			QuestionTimingStatsData stats = new QuestionTimingStatsData();
			stats.questionId = questionId;
			stats.elapsedTimeCount = allTimes.size();
			stats.computedAt = computedAt;
			setPercentiles(allTimes, stats, false);

			List<Long> firstTryCorrectTimes =
					firstTryCorrectTimesByQuestion.getOrDefault(questionId, List.of());
			stats.etCorrect1stTryCount = firstTryCorrectTimes.size();
			setPercentiles(firstTryCorrectTimes, stats, true);
			statsToSave.add(stats);
		}

		saveInBatches(statsToSave);
		removeStaleStatistics(questionIds);
		return new RefreshResult(questionIds.size(), transactionCount);
	}

	private static Long elapsedTimeMs(HWTransaction transaction) {
		if (transaction.presented == null || transaction.graded == null) return null;
		long elapsedMs = transaction.graded.getTime() - transaction.presented.getTime();
		return elapsedMs > 0 ? elapsedMs : null;
	}

	private static void setPercentiles(List<Long> times, QuestionTimingStatsData stats, boolean firstTryCorrect) {
		if (times.isEmpty()) return;
		times.sort(Comparator.naturalOrder());
		if (firstTryCorrect) {
			stats.p05ETCorrect1stTryMs = percentile(times, 0.05);
			stats.p10ETCorrect1stTryMs = percentile(times, 0.10);
			stats.p20ETCorrect1stTryMs = percentile(times, 0.20);
		} else {
			stats.p05ElapsedTimeMs = percentile(times, 0.05);
			stats.p10ElapsedTimeMs = percentile(times, 0.10);
			stats.p20ElapsedTimeMs = percentile(times, 0.20);
		}
	}

	private static long percentile(List<Long> sortedTimes, double percentile) {
		int rank = (int) Math.ceil(percentile * sortedTimes.size()) - 1;
		return sortedTimes.get(Math.max(0, rank));
	}

	private static void saveInBatches(List<QuestionTimingStatsData> stats) {
		for (int i = 0; i < stats.size(); i += WRITE_BATCH_SIZE) {
			ofy().save().entities(stats.subList(i, Math.min(i + WRITE_BATCH_SIZE, stats.size()))).now();
		}
	}

	private static void removeStaleStatistics(Set<Long> currentQuestionIds) {
		List<Key<QuestionTimingStatsData>> staleKeys = new ArrayList<>();
		for (Key<QuestionTimingStatsData> statsKey : ofy().load().type(QuestionTimingStatsData.class).keys().list()) {
			if (!currentQuestionIds.contains(statsKey.getId())) staleKeys.add(statsKey);
		}
		for (int i = 0; i < staleKeys.size(); i += WRITE_BATCH_SIZE) {
			ofy().delete().keys(staleKeys.subList(i, Math.min(i + WRITE_BATCH_SIZE, staleKeys.size()))).now();
		}
	}

	private record AttemptKey(String userId, long assignmentId, long questionId) {}

	private static class FirstAttempt {
		private HWTransaction transaction;
		private boolean hasUndatedAttempt;

		private void consider(HWTransaction candidate) {
			if (candidate.graded == null) {
				hasUndatedAttempt = true;
				return;
			}
			if (transaction == null
					|| candidate.graded.before(transaction.graded)
					|| (candidate.graded.equals(transaction.graded)
							&& candidate.id != null
							&& (transaction.id == null || candidate.id < transaction.id))) {
				transaction = candidate;
			}
		}
	}

	private record RefreshResult(int questionCount, long transactionCount) {}
}

@Entity(name = "QuestionTimingStats")
class QuestionTimingStatsData implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	@Id Long questionId;
	Long p05ElapsedTimeMs;
	Long p10ElapsedTimeMs;
	Long p20ElapsedTimeMs;
	long elapsedTimeCount;
	Long p05ETCorrect1stTryMs;
	Long p10ETCorrect1stTryMs;
	Long p20ETCorrect1stTryMs;
	long etCorrect1stTryCount;
	Date computedAt;

	QuestionTimingStatsData() {}
}
