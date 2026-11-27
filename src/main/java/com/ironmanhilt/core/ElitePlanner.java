/*
	* Copyright (c) 2026, OSRS Ironman Combat Achievement Progression Guide contributors
	* All rights reserved.
	*
	* Redistribution and use in source and binary forms, with or without
	* modification, are permitted provided that the following conditions are met:
	* 1. Redistributions of source code must retain the above copyright notice,
	*    this list of conditions and the following disclaimer.
	* 2. Redistributions in binary form must reproduce the above copyright notice,
	*    this list of conditions and the following disclaimer in the documentation
	*    and/or other materials provided with the distribution.
	*
	* THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
	* AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
	* IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
	* ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
	* LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
	* DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
	* SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
	* CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
	* OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
	* OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
	*/
package com.ironmanhilt.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ElitePlanner
{
	private final GuideCatalog catalogue;
	private final Planner planner;

	ElitePlanner(GuideCatalog catalogue, Planner planner)
	{
		this.catalogue = catalogue;
		this.planner = planner;
	}

	Planner.Route plan(GuideState state, LiveSnapshot live, RouteStyle style)
	{
		RewardGoal goal = state.rewardGoal();
		GrandmasterLedger finalLedger = goal == RewardGoal.GRANDMASTER ? planner.grandmaster(state, live) : null;
		if (live == null || !live.mode.supported())
		{
			if (finalLedger != null)
			{
				return new Planner.Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0,
					Set.of(), Set.of(), Set.of(), finalLedger, Map.of());
			}
			return new Planner.Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0);
		}
		boolean finishesAll = finalLedger != null;
		boolean lateGuide = goal.supportsRaidLight();
		int gap = Math.max(0, catalogue.threshold(goal) - planner.currentPoints(state, live));
		Set<Integer> done = planner.completedForGuide(state, live);
		Map<String, Integer> counters = observedCounters(goal, live);
		Set<Integer> skipped = state.knownIds("skipTaskIDs", catalogue.tasks.keySet());
		List<Integer> pool = new ArrayList<>();
		Set<Integer> unchecked = new HashSet<>(), missing = new HashSet<>();
		Set<Integer> partyWaiting = new HashSet<>(), attemptWaiting = new HashSet<>();
		Map<Integer, String> blockers = new LinkedHashMap<>();
		Map<Integer, String> deferred = new LinkedHashMap<>();
		Map<String, List<String>> teamChecks = new LinkedHashMap<>();
		Map<String, List<String>> access = new HashMap<>();
		List<Visit> candidates = new ArrayList<>();
		int prepared = 0, available = 0;
		for (JsonElement value : catalogue.trips(goal))
		{
			JsonObject trip = value.getAsJsonObject();
			String activity = trip.get("activity").getAsString();
			boolean activitySkipped = state.skipped(activity);
			boolean wildernessDeferred = RiskPolicy.wilderness(activity) && !state.wildernessEnabled();
			if (!finishesAll && (activitySkipped || wildernessDeferred))
			{
				continue;
			}
			List<Integer> ids = ReadinessEngine.ints(trip.getAsJsonArray("ids"));
			List<Integer> eligible = new ArrayList<>();
			Set<Integer> waiting = new HashSet<>();
			for (int id : ids)
			{
				JsonObject rule = catalogue.rule(id, goal);
				if (done.contains(id) || finishesAll && !finalLedger.required.contains(id))
				{
					continue;
				}
				boolean skippedTask = activitySkipped || skipped.contains(id);
				boolean inStyle = catalogue.inRouteStyle(id, goal, style);
				String risk = RiskPolicy.eliteBlocker(rule, live.mode);
				if (finishesAll && live.mode.hardcore() &&
					rule.getAsJsonObject("risk").get("deliberateDamageOrUnprovenMargin").getAsBoolean())
				{
					risk = "Hardcore risk · review the task before attempting it.";
				}
				if (skippedTask || wildernessDeferred || !inStyle || !risk.isEmpty())
				{
					if (finishesAll)
					{
						if (skippedTask)
						{
							deferred.put(id, "Skipped · restore it to include it in the route.");
						}
						else if (wildernessDeferred)
						{
							deferred.put(id, "Wilderness deferred · opt in when ready.");
						}
						else if (!risk.isEmpty())
						{
							deferred.put(id, risk);
						}
						else if (style == RouteStyle.SOLO)
						{
							partyWaiting.add(id);
							blockers.put(id, planner.partyBlocker(id, state, style, live));
						}
						else
						{
							deferred.put(id, "Full raid deferred · use Solo or With a group to schedule it.");
						}
					}
					continue;
				}
				pool.add(id);
				eligible.add(id);
				String party = planner.partyBlocker(id, state, style, live);
				if (!party.isEmpty())
				{
					waiting.add(id);
					if (lateGuide)
					{
						partyWaiting.add(id);
						blockers.put(id, party);
					}
					List<String> checks = teamChecks.computeIfAbsent(activity, ignored -> new ArrayList<>());
					if (!checks.contains(party))
					{
						checks.add(party);
					}
				}
			}
			if (eligible.isEmpty())
			{
				continue;
			}

			List<String> shared = access.computeIfAbsent(activity, key ->
			{
				List<String> requirements = planner.access(key, state, live);
				if (!finishesAll)
				{
					requirements.addAll(planner.strategy(key, state, live));
				}
				return requirements;
			});
			for (int id : eligible)
			{
				List<String> task = planner.taskGates(id, state, live);
				if (!shared.isEmpty() || !task.isEmpty())
				{
					if (lateGuide)
					{
						shared.forEach(gate -> recordBlocker(id, gate, gate.startsWith("Confirm "), blockers, unchecked, missing));
						task.forEach(gate -> recordBlocker(id, gate, gate.startsWith("Confirm "), blockers, unchecked, missing));
					}
					else
					{
						blockers.put(id, shared.isEmpty() ? task.get(0) : shared.get(0));
					}
				}
			}
			for (Planner.ItemRequirement accessCheck : planner.advancedAccessChecks(activity, eligible, state, live))
			{
				if (!accessCheck.check.ready())
				{
					if (accessCheck.required)
					{
						String status = Planner.kitStatus(accessCheck.check);
						if (lateGuide)
						{
							accessCheck.taskIds.forEach(id -> recordBlocker(id, status,
								ReadinessEngine.UNKNOWN.equals(accessCheck.check.status), blockers, unchecked, missing));
						}
						else
						{
							accessCheck.taskIds.forEach(id -> blockers.putIfAbsent(id, status));
						}
					}
					else
					{
						waiting.addAll(accessCheck.taskIds);
						if (lateGuide)
						{
							attemptWaiting.addAll(accessCheck.taskIds);
							accessCheck.taskIds.forEach(id -> blockers.putIfAbsent(id, Planner.kitStatus(accessCheck.check)));
						}
					}
				}
			}
			for (int id : eligible)
			{
				if (!blockers.containsKey(id))
				{
					available += catalogue.tasks.get(id).points;
				}
			}
			Set<Integer> bankItems = new HashSet<>();
			for (Planner.ItemRequirement item : planner.itemRequirements(activity, eligible, state, live))
			{
				if (!item.required)
				{
					continue;
				}
				if (item.check.fromBank)
				{
					bankItems.addAll(item.taskIds);
				}
				if (!item.check.ready())
				{
					String status = Planner.kitStatus(item.check);
					if (lateGuide)
					{
						item.taskIds.forEach(id -> recordBlocker(id, status, ReadinessEngine.UNKNOWN.equals(item.check.status),
							blockers, unchecked, missing));
					}
					else
					{
						item.taskIds.forEach(id -> blockers.putIfAbsent(id, status));
					}
				}
			}
			List<Integer> ready = new ArrayList<>(), practice = new ArrayList<>(), future = new ArrayList<>();
			boolean reserve = trip.get("role").getAsString().equals("reserve");
			for (int id : eligible)
			{
				if (waiting.contains(id))
				{
					if (lateGuide && !partyWaiting.contains(id) && (!reserve || !missing.contains(id) && !unchecked.contains(id)))
					{
						future.add(id);
					}
					continue;
				}
				if (blockers.containsKey(id))
				{
					if (!reserve || finishesAll)
					{
						future.add(id);
					}
				}
				else
				{
					JsonObject rule = catalogue.rule(id, goal);
					(rule.has("practiceRecommended") ? practice : ready).add(id);
					prepared += catalogue.tasks.get(id).points;
				}
			}
			addVisit(candidates, trip, goal, ready, ids.size(), false, false, bankItems, counters);
			addVisit(candidates, trip, goal, practice, ids.size(), false, true, bankItems, counters);
			addVisit(candidates, trip, goal, future, ids.size(), true, false, bankItems, counters);
		}
		unchecked.removeAll(missing);
		List<Planner.Entry> route = new ArrayList<>();
		int planned = 0;
		Set<String> used = new HashSet<>();
		String pinned = ReadinessEngine.str(state.data, "pinnedActivity", "");
		while ((finishesAll || planned < gap) && !candidates.isEmpty())
		{
			int plannedSoFar = planned;
			// Re-evaluate marginal cost after each visit so an already-planned setup only counts once.
			Visit next = candidates.stream().min(Comparator
				.comparingInt((Visit v) -> priority(v, pinned))
				.thenComparingDouble(v -> estimatedMinutes(v, used, live.mode, v.ids, counters, goal) /
					(goal == RewardGoal.MASTER ? Math.min(v.points, gap - plannedSoFar) : v.points))
				.thenComparingInt(v -> v.phase)
				.thenComparingInt(v -> v.ids.get(0))).orElseThrow();
			candidates.remove(next);
			List<Integer> ids = new ArrayList<>();
			int points = 0;
			for (int id : next.ids)
			{
				if (!finishesAll && planned + points >= gap)
				{
					break;
				}
				ids.add(id);
				points += catalogue.tasks.get(id).points;
			}
			Map<Integer, String> visitBlockers = new LinkedHashMap<>();
			for (int id : ids)
			{
				if (blockers.containsKey(id))
				{
					visitBlockers.put(id, blockers.get(id));
				}
			}
			double minutes = estimatedMinutes(next, used, live.mode, ids, counters, goal);
			if (lateGuide)
			{
				// The cost model can reuse credited kill progress. Only the game can set an earned task flag.
				for (int id : ids)
				{
					mergeCounter(catalogue.rule(id, goal), counters);
				}
			}
			route.add(new Planner.Entry(next.activity, ids, points, List.copyOf(visitBlockers.values()),
				List.of(), List.of(), visitBlockers, minutes, points / minutes, next.reserve));
			used.add(next.activity);
			if (next.future)
			{
				used.add(next.profile);
			}
			planned += points;
		}
		return new Planner.Route(route, pool, teamChecks, blockers, available, prepared, planned,
			unchecked, partyWaiting, attemptWaiting, finalLedger, deferred);
	}

	private static void recordBlocker(int id, String reason, boolean unknown, Map<Integer, String> blockers,
		Set<Integer> unchecked, Set<Integer> missing)
	{
		if (unknown)
		{
			unchecked.add(id);
			blockers.putIfAbsent(id, reason);
		}
		else
		{
			if (missing.add(id))
			{
				blockers.put(id, reason);
			}
		}
	}

	private void addVisit(List<Visit> visits, JsonObject trip, RewardGoal goal, List<Integer> ids, int originalCount,
		boolean future, boolean practice, Set<Integer> bankItems, Map<String, Integer> counters)
	{
		if (!ids.isEmpty())
		{
			double oldEstimate = goal == RewardGoal.ELITE ? remainingEffort(ids, counters, goal) : 0;
			Visit visit = new Visit(trip, ids, catalogue.points(ids), originalCount, future, practice,
				!Collections.disjoint(ids, bankItems), oldEstimate);
			if (goal == RewardGoal.GRANDMASTER)
			{
				for (int id : ids)
				{
					JsonObject rule = catalogue.rule(id, goal);
					JsonArray range = rule.getAsJsonArray("effort");
					double minutes = (range.get(0).getAsDouble() + range.get(1).getAsDouble()) / 2;
					JsonObject counter = rule.getAsJsonObject("counter");
					if (counter == null)
					{
						visit.fixedEffort = Math.max(visit.fixedEffort, minutes);
					}
					else
					{
						visit.counterEffort.add(new CounterEffort(counter.get("key").getAsString(),
							counter.get("target").getAsInt(), minutes));
					}
					visit.groupEffort |= catalogue.needsGroup(id, goal);
				}
			}
			visits.add(visit);
		}
	}

	private Map<String, Integer> observedCounters(RewardGoal goal, LiveSnapshot live)
	{
		Map<String, Integer> counters = new HashMap<>();
		if (live.ca.verifiedFor(goal))
		{
			for (int id : live.ca.completed)
			{
				mergeCounter(catalogue.rule(id, goal), counters);
			}
		}
		if (goal.supportsRaidLight())
		{
			for (int id : catalogue.tasks.keySet())
			{
				JsonObject counter = catalogue.rule(id, goal).getAsJsonObject("counter");
				if (counter != null && counter.has("observedKey"))
				{
					int total = live.context.getOrDefault(counter.get("observedKey").getAsString(), 0);
					counters.merge(counter.get("key").getAsString(), total, Math::max);
				}
			}
		}
		return counters;
	}

	private static void mergeCounter(JsonObject rule, Map<String, Integer> counters)
	{
		if (rule != null && rule.has("counter"))
		{
			JsonObject counter = rule.getAsJsonObject("counter");
			int value = counter.get("target").getAsInt();
			counters.merge(counter.get("key").getAsString(), value, Math::max);
			if (counter.has("includes"))
			{
				counter.getAsJsonArray("includes").forEach(key -> counters.merge(key.getAsString(), value, Math::max));
			}
		}
	}

	private double estimatedMinutes(Visit visit, Set<String> used, AccountMode mode, List<Integer> ids,
		Map<String, Integer> counters, RewardGoal goal)
	{
		if (goal == RewardGoal.ELITE)
		{
			return visit.minutes(used, mode, ids.size());
		}
		if (goal == RewardGoal.GRANDMASTER)
		{
			// Grandmaster schedules each whole visit. Read the task effort once per plan.
			double effort = visit.fixedEffort;
			for (CounterEffort counter : visit.counterEffort)
			{
				int units = Math.max(1, counter.target - counters.getOrDefault(counter.key, 0));
				effort = Math.max(effort, units * counter.minutes);
			}
			if (visit.groupEffort && !used.contains(visit.activity))
			{
				effort += 15;
			}
			return effort + visit.setupMinutes(used, mode);
		}
		double effort = 2;
		for (int id : ids)
		{
			JsonObject rule = catalogue.rule(id, goal);
			JsonArray range = rule.getAsJsonArray("effort");
			JsonObject counter = rule.getAsJsonObject("counter");
			int units = counter == null ? 1 : Math.max(1, counter.get("target").getAsInt() -
				counters.getOrDefault(counter.get("key").getAsString(), 0));
			effort = Math.max(effort, units * (range.get(0).getAsDouble() + range.get(1).getAsDouble()) / 2);
		}
		if (!used.contains(visit.activity) && ids.stream().anyMatch(id -> catalogue.needsGroup(id, goal)))
		{
			effort += 15;
		}
		return effort + visit.setupMinutes(used, mode);
	}

	private double remainingEffort(List<Integer> ids, Map<String, Integer> counters, RewardGoal goal)
	{
		double worst = 0.25;
		for (int id : ids)
		{
			JsonObject counter = catalogue.rule(id, goal).getAsJsonObject("counter");
			if (counter == null)
			{
				return 1;
			}
			int target = counter.get("target").getAsInt();
			int seen = counters.getOrDefault(counter.get("key").getAsString(), 0);
			// Count a lower credited milestone only when it belongs to the same counter.
			if (seen < target)
			{
				worst = Math.max(worst, 1 - (double)seen / target);
			}
			else
			{
				return 1; // The current task flag is still authoritative.
			}
		}
		return worst;
	}

	private static int priority(Visit visit, String pinned)
	{
		if (visit.future)
		{
			return 4;
		}
		if (RiskPolicy.wilderness(visit.activity))
		{
			return 3;
		}
		if (visit.activity.equals(pinned))
		{
			return 0;
		}
		return visit.practice ? 2 : 1;
	}

	private static final class Visit
	{
		private final String activity, profile;
		private final int phase, points, originalCount, unlockMinutes;
		private final double attemptMinutes;
		private final boolean reserve, future, practice, bank;
		private final List<Integer> ids;
		private final List<CounterEffort> counterEffort = new ArrayList<>();
		private double fixedEffort = 2;
		private boolean groupEffort;

		private Visit(JsonObject value, List<Integer> ids, int points, int originalCount, boolean future, boolean practice,
			boolean bank, double remainingEffort)
		{
			this.ids = ids;
			this.points = points;
			this.originalCount = originalCount;
			this.future = future;
			this.practice = practice;
			this.bank = bank;
			activity = value.get("activity").getAsString();
			profile = value.get("profile").getAsString();
			phase = value.get("phase").getAsInt();
			unlockMinutes = value.get("unlockMinutes").getAsInt();
			reserve = value.get("role").getAsString().equals("reserve");
			JsonArray minutes = value.getAsJsonArray("minutes");
			attemptMinutes = minutes == null ? 0 :
				remainingEffort * (minutes.get(0).getAsInt() + minutes.get(1).getAsInt()) / 2.0;
		}

		private double minutes(Set<String> used, AccountMode mode, int taskCount)
		{
			return Math.max(2, attemptMinutes * Math.max(0.5, (double) taskCount / originalCount)) + setupMinutes(used, mode);
		}

		private double setupMinutes(Set<String> used, AccountMode mode)
		{
			double cost = 0;
			if (!used.contains(activity))
			{
				cost += bank ? 7 : 4;
				if (mode == AccountMode.ULTIMATE)
				{
					cost += 7; // Repack carried supplies and switches between activities.
				}
				cost += RiskPolicy.penalty(activity, mode);
			}
			if (future && !used.contains(profile))
			{
				cost += (mode == AccountMode.NORMAL ? 10 : 15) + unlockMinutes;
			}
			return cost;
		}
	}

	private static final class CounterEffort
	{
		private final String key;
		private final int target;
		private final double minutes;

		private CounterEffort(String key, int target, double minutes)
		{
			this.key = key;
			this.target = target;
			this.minutes = minutes;
		}
	}
}
