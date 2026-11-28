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
import java.util.LinkedHashSet;
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
		RewardGoal guide = state.contentGoal();
		boolean automatic = state.automatic();
		Map<Integer, GuideCatalog.RouteOrder> milestoneOrder = automatic ? catalogue.milestoneOrder(goal) : Map.of();
		GrandmasterLedger finalLedger = goal == RewardGoal.GRANDMASTER ? planner.grandmaster(state, live) : null;
		if (live == null || !live.mode.supported() || automatic && !state.milestones().progressVerified)
		{
			if (finalLedger != null)
			{
				return new Planner.Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0,
					Set.of(), Set.of(), Set.of(), finalLedger, Map.of());
			}
			return new Planner.Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0);
		}
		boolean finishesAll = finalLedger != null;
		boolean detailedPreparation = guide.detailedPreparation();
		int target = automatic ? state.milestones().target().target : catalogue.threshold(goal);
		int gap = Math.max(0, target - planner.currentPoints(state, live));
		Set<Integer> done = planner.completedForGuide(state, live);
		Map<String, Integer> counters = observedCounters(guide, live);
		Set<String> experienced = new HashSet<>();
		if (automatic)
		{
			done.forEach(id -> experienced.add(catalogue.owner(id)));
		}
		Set<Integer> skipped = state.knownIds("skipTaskIDs", catalogue.tasks.keySet());
		List<Integer> pool = new ArrayList<>();
		Set<Integer> unchecked = new HashSet<>(), missing = new HashSet<>();
		Set<Integer> partyWaiting = new HashSet<>(), attemptWaiting = new HashSet<>();
		Map<Integer, String> blockers = new LinkedHashMap<>();
		Map<Integer, String> deferred = new LinkedHashMap<>();
		Map<String, List<String>> teamChecks = new LinkedHashMap<>();
		Map<String, List<String>> access = new HashMap<>();
		List<Visit> candidates = new ArrayList<>();
		Set<Integer> restocking = new HashSet<>();
		int prepared = 0, available = 0;
		for (JsonElement value : automatic ? catalogue.automaticTrips() : catalogue.trips(guide))
		{
			JsonObject trip = value.getAsJsonObject();
			String activity = trip.get("activity").getAsString();
			boolean familiar = experienced.contains(activity) || live.context.getOrDefault("kc:" + activity, 0) > 0;
			boolean activitySkipped = state.skipped(activity);
			boolean wildernessDeferred = RiskPolicy.wilderness(activity) && !state.wildernessEnabled();
			if (!automatic && !finishesAll && (activitySkipped || wildernessDeferred))
			{
				continue;
			}
			List<Integer> ids = ReadinessEngine.ints(trip.getAsJsonArray("ids"));
			List<Integer> eligible = new ArrayList<>();
			Set<Integer> waiting = new HashSet<>();
			for (int id : ids)
			{
				JsonObject rule = catalogue.rule(id, guide);
				if (done.contains(id) || finishesAll && !finalLedger.required.contains(id))
				{
					continue;
				}
				boolean skippedTask = activitySkipped || skipped.contains(id);
				boolean inStyle = planner.eligibleRouteStyle(id, state, style);
				String risk = RiskPolicy.eliteBlocker(rule, live.mode);
				if (finishesAll && live.mode.hardcore() &&
					rule.getAsJsonObject("risk").get("deliberateDamageOrUnprovenMargin").getAsBoolean())
				{
					risk = "Hardcore risk · review the task before attempting it.";
				}
				if (skippedTask || wildernessDeferred || !inStyle || !risk.isEmpty())
				{
					if (finishesAll || automatic)
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
						else if (style == RouteStyle.SOLO && catalogue.needsGroup(id, guide))
						{
							partyWaiting.add(id);
							blockers.put(id, planner.partyBlocker(id, state, style, live));
						}
						else
						{
							deferred.put(id, automatic ? "Full raid deferred · allow it in Route preferences." :
								"Full raid deferred · use Solo or With a group to schedule it.");
						}
					}
					continue;
				}
				if (!automatic || milestoneOrder.containsKey(id))
				{
					pool.add(id);
				}
				eligible.add(id);
				String party = planner.partyBlocker(id, state, style, live);
				String world = planner.worldBlocker(id, state, live);
				if (!world.isEmpty())
				{
					waiting.add(id);
					attemptWaiting.add(id);
					blockers.put(id, world);
				}
				if (!party.isEmpty())
				{
					waiting.add(id);
					if (detailedPreparation)
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
					if (detailedPreparation)
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
						if (detailedPreparation)
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
						if (detailedPreparation)
						{
							attemptWaiting.addAll(accessCheck.taskIds);
							accessCheck.taskIds.forEach(id -> blockers.putIfAbsent(id, Planner.kitStatus(accessCheck.check)));
						}
					}
				}
			}
			for (int id : eligible)
			{
				if (!blockers.containsKey(id) && (!automatic || milestoneOrder.containsKey(id)))
				{
					available += catalogue.tasks.get(id).points;
				}
			}
			Set<Integer> bankItems = new HashSet<>();
			for (Planner.ItemRequirement item : planner.itemRequirements(activity, eligible, state, live))
			{
				if (!item.required)
				{
					if (automatic && item.restockNeeded())
					{
						restocking.addAll(item.taskIds);
					}
					continue;
				}
				if (item.check.fromBank)
				{
					bankItems.addAll(item.taskIds);
				}
				if (!item.check.ready())
				{
					String status = Planner.kitStatus(item.check);
					if (detailedPreparation)
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
				GuideCatalog.RouteOrder order = milestoneOrder.get(id);
				if (automatic && order == null)
				{
					continue;
				}
				if (waiting.contains(id))
				{
					if (!automatic && goal != RewardGoal.EASY && detailedPreparation && !partyWaiting.contains(id) &&
						(!reserve || !missing.contains(id) && !unchecked.contains(id)))
					{
						future.add(id);
					}
					continue;
				}
				if (blockers.containsKey(id))
				{
					if (!(automatic ? order.reserve : reserve) || finishesAll)
					{
						future.add(id);
					}
				}
				else
				{
					JsonObject rule = catalogue.rule(id, guide);
					boolean needsPractice = automatic ? !familiar &&
						(rule.has("practiceRecommended") || catalogue.tasks.get(id).points >= 4) :
						goal != RewardGoal.EASY && rule.has("practiceRecommended");
					(needsPractice ? practice : ready).add(id);
					if (!restocking.contains(id))
					{
						prepared += catalogue.tasks.get(id).points;
					}
				}
			}
			addVisits(candidates, trip, guide, ready, ids.size(), false, false, bankItems, counters, milestoneOrder);
			addVisits(candidates, trip, guide, practice, ids.size(), false, true, bankItems, counters, milestoneOrder);
			addVisits(candidates, trip, guide, future, ids.size(), true, false, bankItems, counters, milestoneOrder);
		}
		unchecked.removeAll(missing);
		List<Planner.Entry> route = new ArrayList<>();
		Map<String, SupplyUse> suppliesUsed = new HashMap<>();
		int planned = 0;
		Set<String> used = new HashSet<>();
		String pinned = ReadinessEngine.str(state.data, "pinnedActivity", "");
		while ((finishesAll || planned < gap) && !candidates.isEmpty() && (!automatic || route.size() < 12))
		{
			int plannedSoFar = planned;
			int region = planned == 0 ? live.context.getOrDefault("region", -1) : -1;
			// Re-evaluate marginal cost after each visit so an already-planned setup only counts once.
			Visit next = automatic ? automaticVisit(candidates, live, pinned, used, counters, restocking,
				finishesAll ? 0 : gap - plannedSoFar, region) : candidates.stream().min(Comparator
				.comparingInt((Visit v) -> priority(v, pinned))
				.thenComparingInt(v -> goal == RewardGoal.EASY ? easyOrder(v, counters, gap - plannedSoFar, live.mode, region) : 0)
				.thenComparingDouble(v -> estimatedMinutes(v, used, live.mode, v.ids, counters, goal) /
					(goal == RewardGoal.MASTER || goal == RewardGoal.EASY ? Math.min(v.points, gap - plannedSoFar) : v.points))
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
			double minutes = automatic ? automaticMinutes(next, ids, used, live.mode, counters, region, restocking) :
				estimatedMinutes(next, used, live.mode, ids, counters, guide);
			String reason = automatic ? automaticReason(next, live, region, counters) : "";
			List<String> supplyChecks = automatic ? visitSupplies(next.activity, ids, state, live, suppliesUsed) : List.of();
			if (detailedPreparation)
			{
				// The cost model can reuse credited kill progress. Only the game can set an earned task flag.
				for (int id : ids)
				{
					mergeCounter(catalogue.rule(id, guide), counters);
				}
			}
			route.add(new Planner.Entry(next.activity, ids, points, List.copyOf(visitBlockers.values()),
				supplyChecks, List.of(), visitBlockers, minutes, points / minutes, next.reserve, reason));
			used.add(next.activity);
			if (next.future)
			{
				used.add(next.profile);
			}
			planned += points;
		}
		return new Planner.Route(route, pool, teamChecks, blockers, available, prepared, planned,
			unchecked, partyWaiting, attemptWaiting, finalLedger, deferred, restocking);
	}

	private List<String> visitSupplies(String activity, List<Integer> ids, GuideState state, LiveSnapshot live,
		Map<String, SupplyUse> used)
	{
		Set<String> checks = new LinkedHashSet<>();
		Map<String, SupplyUse> visit = new LinkedHashMap<>();
		for (Planner.ItemRequirement item : planner.itemRequirements(activity, ids, state, live))
		{
			if (item.restockNeeded())
			{
				checks.add(item.check.label + " · " + item.check.detail);
			}
			String pool = item.supplyPool();
			if (!pool.isEmpty() && item.check.ready() && item.check.amount > 0)
			{
				SupplyUse use = new SupplyUse(item.supplyRecipe(activity, pool), item.minimum(), item.check.amount);
				// Objectives that share a visit may use the same attempts to meet a cumulative minimum.
				visit.merge(pool, use, (a, b) -> new SupplyUse(a.recipe.equals(b.recipe) ? a.recipe : "",
					Math.max(a.minimum, b.minimum), Math.min(a.stock, b.stock)));
			}
		}
		visit.forEach((pool, use) ->
		{
			SupplyUse earlier = used.get(pool);
			if (earlier == null)
			{
				used.put(pool, use);
			}
			else if (use.recipe.isEmpty() || !earlier.recipe.equals(use.recipe))
			{
				checks.add("Recheck " + pool.toLowerCase(java.util.Locale.ROOT) + " after the earlier visit.");
				used.put(pool, new SupplyUse("", 0, 0));
			}
			else
			{
				int minimum = earlier.minimum + use.minimum;
				int stock = Math.min(earlier.stock, use.stock);
				if (minimum > stock)
				{
					checks.add(pool + " · restock before this visit (" + minimum + " across visits, " + stock + " observed).");
				}
				used.put(pool, new SupplyUse(use.recipe, minimum, stock));
			}
		});
		return List.copyOf(checks);
	}

	private static final class SupplyUse
	{
		private final String recipe;
		private final int minimum, stock;

		private SupplyUse(String recipe, int minimum, int stock)
		{
			this.recipe = recipe;
			this.minimum = minimum;
			this.stock = stock;
		}
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

	private void addVisits(List<Visit> visits, JsonObject trip, RewardGoal goal, List<Integer> ids, int originalCount,
		boolean future, boolean practice, Set<Integer> bankItems, Map<String, Integer> counters,
		Map<Integer, GuideCatalog.RouteOrder> milestoneOrder)
	{
		if (milestoneOrder.isEmpty())
		{
			addVisit(visits, trip, goal, ids, originalCount, future, practice, bankItems, counters, null);
			return;
		}
		Map<Integer, List<Integer>> byPhase = new LinkedHashMap<>();
		for (int id : ids)
		{
			byPhase.computeIfAbsent(milestoneOrder.get(id).priority(), ignored -> new ArrayList<>()).add(id);
		}
		for (List<Integer> group : byPhase.values())
		{
			addVisit(visits, trip, goal, group, originalCount, future, practice, bankItems, counters,
				milestoneOrder.get(group.get(0)));
		}
	}

	private void addVisit(List<Visit> visits, JsonObject trip, RewardGoal goal, List<Integer> ids, int originalCount,
		boolean future, boolean practice, Set<Integer> bankItems, Map<String, Integer> counters, GuideCatalog.RouteOrder order)
	{
		if (!ids.isEmpty())
		{
			double oldEstimate = goal == RewardGoal.ELITE ? remainingEffort(ids, counters, goal) : 0;
			Visit visit = new Visit(trip, ids, catalogue.points(ids), originalCount, future, practice,
				!Collections.disjoint(ids, bankItems), oldEstimate, order);
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
		if (goal.detailedPreparation())
		{
			for (int id : catalogue.tasks.keySet())
			{
				JsonObject rule = catalogue.rule(id, goal);
				JsonObject counter = rule == null ? null : rule.getAsJsonObject("counter");
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
		return estimatedMinutes(visit, used, mode, ids, counters, goal, false);
	}

	private double estimatedMinutes(Visit visit, Set<String> used, AccountMode mode, List<Integer> ids,
		Map<String, Integer> counters, RewardGoal goal, boolean usePrefix)
	{
		if (goal == RewardGoal.ELITE)
		{
			return visit.minutes(used, mode, ids.size());
		}
		if (goal == RewardGoal.GRANDMASTER && !usePrefix)
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

	private Visit automaticVisit(List<Visit> candidates, LiveSnapshot live, String pinned, Set<String> used,
		Map<String, Integer> counters, Set<Integer> restocking, int gap, int region)
	{
		Comparator<Visit> readiness = Comparator
			.comparingInt((Visit v) -> live.freeWorld() && !v.future && v.ids.stream().anyMatch(i -> i == 120 || i == 121) ?
				3 : v.practice ? 2 : priority(v, pinned))
			.thenComparingInt(v -> gap > 0 && gap <= 8 && v.regions.contains(region) && nearCounter(v.ids.get(0), counters) ?
				0 : 1)
			.thenComparingInt(v -> v.phase)
			.thenComparing(v -> v.reserve);
		Comparator<Visit> order = readiness
			.thenComparingDouble(v -> automaticMinutes(v, visitPrefix(v, gap), used, live.mode, counters, region, restocking) /
				(gap == 0 ? v.points : Math.min(v.points, gap)))
			.thenComparingInt(v -> v.phase)
			.thenComparingInt(v -> v.ids.get(0));
		Visit preferred = candidates.stream().min(order).orElseThrow();
		if (gap == 0 || gap > 12 || preferred.future || preferred.activity.equals(pinned) ||
			(preferred.regions.contains(region) && nearCounter(preferred.ids.get(0), counters)))
		{
			return preferred;
		}

		List<Visit> shortlist = candidates.stream().filter(v -> readiness.compare(v, preferred) == 0)
			.sorted(order).limit(8).collect(java.util.stream.Collectors.toList());
		Visit bestFinish = preferred;
		double bestTime = Double.POSITIVE_INFINITY;
		for (Visit first : shortlist)
		{
			double minutes = automaticMinutes(first, visitPrefix(first, gap), used, live.mode, counters, region, restocking);
			int missing = gap - first.points;
			if (missing > 0)
			{
				Set<String> nextSetup = new HashSet<>(used);
				nextSetup.add(first.activity);
				Map<String, Integer> nextCounters = new HashMap<>(counters);
				first.ids.forEach(id -> mergeCounter(catalogue.rule(id, RewardGoal.GRANDMASTER), nextCounters));
				double second = shortlist.stream().filter(v -> v != first && v.points >= missing)
					.mapToDouble(v -> automaticMinutes(v, visitPrefix(v, missing), nextSetup, live.mode, nextCounters, -1, restocking))
					.min().orElse(Double.POSITIVE_INFINITY);
				minutes += second;
			}
			if (minutes < bestTime)
			{
				bestFinish = first;
				bestTime = minutes;
			}
		}
		return bestFinish;
	}

	private List<Integer> visitPrefix(Visit visit, int gap)
	{
		if (gap == 0 || visit.points <= gap)
		{
			return visit.ids;
		}
		int points = 0;
		int length = 0;
		while (points < gap)
		{
			points += catalogue.tasks.get(visit.ids.get(length++)).points;
		}
		return visit.ids.subList(0, length);
	}

	private double automaticMinutes(Visit visit, List<Integer> ids, Set<String> used, AccountMode mode,
		Map<String, Integer> counters, int region, Set<Integer> restocking)
	{
		// The point planner can use a prefix, so a longer counter at the end of the visit is not charged early.
		double minutes = estimatedMinutes(visit, used, mode, ids, counters, RewardGoal.GRANDMASTER, true);
		if (!used.contains(visit.activity) && visit.regions.contains(region))
		{
			minutes -= 3;
		}
		if (!Collections.disjoint(ids, restocking))
		{
			minutes += 4;
		}
		return Math.max(1, minutes);
	}

	private boolean nearCounter(int id, Map<String, Integer> counters)
	{
		JsonObject counter = catalogue.rule(id, RewardGoal.GRANDMASTER).getAsJsonObject("counter");
		return counter != null && counter.get("target").getAsInt() > 1 &&
			counter.get("target").getAsInt() - counters.getOrDefault(counter.get("key").getAsString(), 0) == 1;
	}

	private String automaticReason(Visit visit, LiveSnapshot live, int region, Map<String, Integer> counters)
	{
		if (visit.future)
		{
			return "Review the preparation needed for this visit.";
		}
		if (visit.practice)
		{
			return "Start with a practice attempt here. Open the walkthrough for the objective’s setup.";
		}
		if (live.freeWorld() && visit.ids.stream().anyMatch(i -> i == 120 || i == 121))
		{
			return "Use the free-world Demonic Ruins method. This visit enters the Wilderness.";
		}
		if (nearCounter(visit.ids.get(0), counters))
		{
			return "Your observed counter is one attempt from this objective.";
		}
		if (visit.regions.contains(region))
		{
			return "This objective is nearby and its preparation checks are met.";
		}
		if (visit.bank)
		{
			return "Your bank has supplies for this visit. Check what to bring.";
		}
		return visit.ids.size() > 1 ? "These objectives can share a visit with your current setup." :
			"This objective fits your current unlocks and supplies.";
	}

	private int easyOrder(Visit visit, Map<String, Integer> counters, int remaining, AccountMode mode, int region)
	{
		if (mode.hardcore() && visit.activity.equals("tempoross"))
		{
			return -1;
		}
		if (remaining <= 8)
		{
			boolean nearby = region >= 0 && visit.regions.contains(region);
			JsonObject counter = catalogue.rule(visit.ids.get(0), RewardGoal.EASY).getAsJsonObject("counter");
			int units = counter == null ? 1 : counter.get("target").getAsInt() -
				counters.getOrDefault(counter.get("key").getAsString(), 0);
			if (counter != null && counter.get("target").getAsInt() > 1 && units == 1)
			{
				return nearby ? 0 : 1;
			}
			if (remaining <= 2 && units <= 1 && nearby)
			{
				return 2;
			}
			if (remaining <= 2 && units > 1 && visit.reserve)
			{
				return visit.phase + 200;
			}
		}
		return visit.phase + (visit.reserve ? 100 : 0);
	}

	private static final class Visit
	{
		private final String activity, profile;
		private final int phase, points, originalCount, unlockMinutes;
		private final double attemptMinutes;
		private final boolean reserve, future, practice, bank;
		private final List<Integer> ids, regions;
		private final List<CounterEffort> counterEffort = new ArrayList<>();
		private double fixedEffort = 2;
		private boolean groupEffort;

		private Visit(JsonObject value, List<Integer> ids, int points, int originalCount, boolean future, boolean practice,
			boolean bank, double remainingEffort, GuideCatalog.RouteOrder order)
		{
			this.ids = ids;
			this.points = points;
			this.originalCount = originalCount;
			this.future = future;
			this.practice = practice;
			this.bank = bank;
			activity = value.get("activity").getAsString();
			profile = value.get("profile").getAsString();
			phase = order == null ? value.get("phase").getAsInt() : order.phase;
			regions = ReadinessEngine.ints(value.getAsJsonArray("regions"));
			unlockMinutes = order == null ? value.get("unlockMinutes").getAsInt() : order.unlockMinutes;
			reserve = order == null ? value.get("role").getAsString().equals("reserve") : order.reserve;
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
