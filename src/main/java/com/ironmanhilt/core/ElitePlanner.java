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
		if (live == null || !live.mode.supported())
		{
			return new Planner.Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0);
		}
		int gap = Math.max(0, catalogue.eliteThreshold - planner.currentPoints(state, live));
		Set<Integer> done = planner.completedForGuide(state, live);
		Map<String, Integer> counters = live.ca.valid ? observedCounters(live.ca.completed) : Map.of();
		Set<Integer> skipped = state.knownIds("skipTaskIDs", catalogue.tasks.keySet());
		boolean group = style == RouteStyle.GROUP;
		List<Integer> pool = new ArrayList<>();
		Map<Integer, String> blockers = new LinkedHashMap<>();
		Map<String, List<String>> teamChecks = new LinkedHashMap<>();
		Map<String, List<String>> access = new HashMap<>();
		List<Visit> candidates = new ArrayList<>();
		int prepared = 0, available = 0;
		for (JsonElement value : catalogue.eliteTrips())
		{
			JsonObject trip = value.getAsJsonObject();
			String activity = trip.get("activity").getAsString();
			if (state.skipped(activity) || RiskPolicy.wilderness(activity) && !state.wildernessEnabled())
			{
				continue;
			}
			List<Integer> ids = ReadinessEngine.ints(trip.getAsJsonArray("ids"));
			List<Integer> eligible = new ArrayList<>();
			Set<Integer> waiting = new HashSet<>();
			for (int id : ids)
			{
				JsonObject rule = catalogue.rule(id, RewardGoal.ELITE);
				if (done.contains(id) || skipped.contains(id) || catalogue.needsGroup(id, RewardGoal.ELITE) && !group ||
					!RiskPolicy.eliteBlocker(rule, live.mode).isEmpty())
				{
					continue;
				}
				pool.add(id);
				eligible.add(id);
				String party = planner.elitePartyBlocker(id, state, style);
				if (!party.isEmpty())
				{
					waiting.add(id);
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
				requirements.addAll(planner.strategy(key, state, live));
				return requirements;
			});
			for (int id : eligible)
			{
				List<String> task = planner.taskGates(id, state, live);
				if (!shared.isEmpty() || !task.isEmpty())
				{
					blockers.put(id, shared.isEmpty() ? task.get(0) : shared.get(0));
				}
			}
			for (Planner.ItemRequirement accessCheck : planner.eliteAccessChecks(activity, eligible, state, live))
			{
				if (!accessCheck.check.ready())
				{
					if (accessCheck.required)
					{
						String status = Planner.kitStatus(accessCheck.check);
						accessCheck.taskIds.forEach(id -> blockers.putIfAbsent(id, status));
					}
					else
					{
						waiting.addAll(accessCheck.taskIds);
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
					item.taskIds.forEach(id -> blockers.putIfAbsent(id, status));
				}
			}
			List<Integer> ready = new ArrayList<>(), practice = new ArrayList<>(), future = new ArrayList<>();
			boolean reserve = trip.get("role").getAsString().equals("reserve");
			for (int id : eligible)
			{
				if (waiting.contains(id))
				{
					continue;
				}
				if (blockers.containsKey(id))
				{
					if (!reserve)
					{
						future.add(id);
					}
				}
				else
				{
					JsonObject rule = catalogue.rule(id, RewardGoal.ELITE);
					(rule.has("practiceRecommended") ? practice : ready).add(id);
					prepared += catalogue.tasks.get(id).points;
				}
			}
			addVisit(candidates, trip, ready, ids.size(), false, false, bankItems, counters);
			addVisit(candidates, trip, practice, ids.size(), false, true, bankItems, counters);
			addVisit(candidates, trip, future, ids.size(), true, false, bankItems, counters);
		}
		List<Planner.Entry> route = new ArrayList<>();
		int planned = 0;
		Set<String> used = new HashSet<>();
		String pinned = ReadinessEngine.str(state.data, "pinnedActivity", "");
		while (planned < gap && !candidates.isEmpty())
		{
			// Re-evaluate marginal cost after each visit so an already-planned setup only counts once.
			Visit next = candidates.stream().min(Comparator
				.comparingInt((Visit v) -> priority(v, pinned))
				.thenComparingDouble(v -> v.minutes(used, live.mode, v.ids.size()) / v.points)
				.thenComparingInt(v -> v.phase)
				.thenComparingInt(v -> v.ids.get(0))).orElseThrow();
			candidates.remove(next);
			List<Integer> ids = new ArrayList<>();
			int points = 0;
			for (int id : next.ids)
			{
				if (planned + points >= gap)
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
			double minutes = next.minutes(used, live.mode, ids.size());
			route.add(new Planner.Entry(next.activity, ids, points, List.copyOf(visitBlockers.values()),
				List.of(), List.of(), visitBlockers, minutes, points / minutes, next.reserve));
			used.add(next.activity);
			if (next.future)
			{
				used.add(next.profile);
			}
			planned += points;
		}
		return new Planner.Route(route, pool, teamChecks, blockers, available, prepared, planned);
	}

	private void addVisit(List<Visit> visits, JsonObject trip, List<Integer> ids, int originalCount, boolean future,
		boolean practice, Set<Integer> bankItems, Map<String, Integer> counters)
	{
		if (!ids.isEmpty())
		{
			visits.add(new Visit(trip, ids, catalogue.points(ids), originalCount, future, practice,
				!Collections.disjoint(ids, bankItems), remainingEffort(ids, counters)));
		}
	}

	private Map<String, Integer> observedCounters(Set<Integer> completed)
	{
		Map<String, Integer> counters = new HashMap<>();
		for (int id : completed)
		{
			JsonObject rule = catalogue.rule(id, RewardGoal.ELITE);
			if (rule != null && rule.has("counter"))
			{
				JsonObject counter = rule.getAsJsonObject("counter");
				counters.merge(counter.get("key").getAsString(), counter.get("target").getAsInt(), Math::max);
			}
		}
		return counters;
	}

	private double remainingEffort(List<Integer> ids, Map<String, Integer> counters)
	{
		double worst = 0.25;
		for (int id : ids)
		{
			JsonObject counter = catalogue.rule(id, RewardGoal.ELITE).getAsJsonObject("counter");
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
			attemptMinutes = remainingEffort * (minutes.get(0).getAsInt() + minutes.get(1).getAsInt()) / 2.0;
		}

		private double minutes(Set<String> used, AccountMode mode, int taskCount)
		{
			double cost = Math.max(2, attemptMinutes * Math.max(0.5, (double) taskCount / originalCount));
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
}
