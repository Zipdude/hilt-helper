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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A transparent effort heuristic, not a claim of globally optimal play time. */
public final class Planner
{
	private static final List<String> ITEM_CHECK_FIELDS = List.of("group", "any_of_ids", "spell", "spells_any",
		"predicate", "minimum", "distinct_equipment_slots", "primary_ids", "alternatives", "condition");

	private final GuideCatalog catalogue;
	private final ProgressEngine progress;
	private final ReadinessEngine readiness;
	public Planner(GuideCatalog c, ProgressEngine p, ReadinessEngine r)
	{
		catalogue = c;
		progress = p;
		readiness = r;
	}
	public int currentPoints(GuideState state, LiveSnapshot live)
	{
		return state.rewardGoal() != RewardGoal.MEDIUM && gameProgressVerified(state.rewardGoal(), live) ?
			live.ca.gamePoints : progress.total(state);
	}

	public Set<Integer> completedForGuide(GuideState state, LiveSnapshot live)
	{
		return state.rewardGoal() != RewardGoal.MEDIUM && gameProgressVerified(state.rewardGoal(), live) ?
			live.ca.completed : progress.knownCompleted(state);
	}

	public boolean gameProgressVerified(RewardGoal goal, LiveSnapshot live)
	{
		return live != null && live.mode.supported() && live.ca.verifiedFor(goal) &&
			(goal != RewardGoal.GRANDMASTER ||
				live.context.getOrDefault("caGameBuild", 0) == catalogue.grandmasterGameBuild);
	}

	public GrandmasterLedger grandmaster(GuideState state, LiveSnapshot live)
	{
		boolean verified = gameProgressVerified(RewardGoal.GRANDMASTER, live);
		return new GrandmasterLedger(catalogue, live, verified ? live.ca.completed : progress.knownCompleted(state), verified);
	}

	public boolean hasLiveTarget(RewardGoal goal, LiveSnapshot live)
	{
		if (!gameProgressVerified(goal, live))
		{
			return false;
		}
		if (goal == RewardGoal.GRANDMASTER)
		{
			return new GrandmasterLedger(catalogue, live, live.ca.completed, true).targetVerified;
		}
		if (goal == RewardGoal.MASTER)
		{
			// Reduced Group targets still need in-game confirmation. Use the standard planning target.
			return live.context.getOrDefault("caMasterThreshold", 0) == catalogue.masterThreshold;
		}
		return goal != RewardGoal.ELITE || live.context.getOrDefault("caEliteThreshold", 0) == catalogue.eliteThreshold;
	}

	public boolean hasUnfinishedHespori(GuideState state, LiveSnapshot live)
	{
		if (state.skipped("hespori"))
		{
			return false;
		}
		Set<Integer> complete = completedForGuide(state, live);
		Set<Integer> skipped = state.knownIds("skipTaskIDs", catalogue.tasks.keySet());
		return catalogue.routeIds(catalogue.activity("hespori"), true, state.rewardGoal()).stream()
			.anyMatch(id -> !complete.contains(id) && !skipped.contains(id));
	}

	public Next next(GuideState state, LiveSnapshot live)
	{
		return next(state, live, plan(state, live));
	}
	public Next next(GuideState state, LiveSnapshot live, List<Entry> route)
	{
		RewardGoal goal = state.rewardGoal();
		if (goal == RewardGoal.GRANDMASTER)
		{
			GrandmasterLedger ledger = grandmaster(state, live);
			if (ledger.rewardAvailable())
			{
				return new Next(null, "Review your Grandmaster reward",
					"Your current ledger is complete. Confirm the reward in Combat Achievements → Rewards.", List.of(), 0);
			}
			if (ledger.rewardCollected())
			{
				return new Next(null, "Grandmaster complete", "Your current achievement ledger is complete.", List.of(), 0);
			}
			if (route.isEmpty() || ledger.pointsAtTargetWithOpenTasks())
			{
				String title = ledger.required.isEmpty() || !ledger.targetVerified ?
					"Check Grandmaster rewards" : "Review remaining tasks";
				String detail = ledger.evidence();
				if (detail.isEmpty())
				{
					detail = ledger.required.isEmpty() ? "Check the Rewards tab on a standard members’ world." :
						"Open All guide tasks to review requirements, skipped tasks and party bookings.";
				}
				return new Next(null, title, detail, List.of(), 0);
			}
		}
		int threshold = catalogue.threshold(goal);
		int deficit = Math.max(0, threshold - currentPoints(state, live));
		if (goal != RewardGoal.GRANDMASTER && deficit == 0)
		{
			if (gameProgressVerified(goal, live) && live.ca.gamePoints < threshold)
			{
				return new Next(
					null, "Review the saved point total",
					"Check your game points in Account & backups before claiming your hilt.",
					Collections.emptyList(), 0);
			}
			if (live == null || !live.mode.supported() || !hasLiveTarget(goal, live))
			{
				return new Next(null, "Check " + goal.label + " reward eligibility in game",
					"Refresh or re-log to verify your points and reward target before claiming.",
					Collections.emptyList(), 0);
			}
			if (goal == RewardGoal.MASTER && live.context.getOrDefault("membersWorld", 0) != 1)
			{
				return new Next(null, "Continue on a members’ world",
					"Verify the Master reward there.", Collections.emptyList(), 0);
			}
			if (live.freeWorld())
			{
				return new Next(null, "Claim your hilt on a members’ world",
					"Speak to Ghommal or Laidee Gnonock by the Warriors’ Guild.", Collections.emptyList(), 0);
			}
			return new Next(null, "Claim " + goal.reward,
							"Point target reached. Speak to Ghommal or Laidee Gnonock by the Warriors’ Guild.",
							Collections.emptyList(), 0);
		}
		if (live == null || !live.mode.supported())
		{
			return new Next(null, "Log in on a standard world", "Saved progress stays separate for each account.",
							Collections.emptyList(), 0);
		}
		if (route.isEmpty())
		{
			return new Next(
				null, "Review alternatives and skips",
				deficit +
					(goal != RewardGoal.MEDIUM
						? " points remain. Check available activities for unlocks and kit, or review the reserve tasks."
						: " points remain. Restore a skipped task, confirm an available reserve, or enable Wilderness activities only if you choose that risk."),
				Collections.emptyList(), 0);
		}
		Entry entry = route.get(0);
		String action = entry.kitBlockers.getOrDefault(entry.ids.get(0),
			entry.gates.isEmpty() ? "Review your kit and the first task." : entry.gates.get(0));
		return new Next(entry.key, catalogue.label(entry.key), action, entry.ids, entry.gain);
	}
	public List<Entry> plan(GuideState state, LiveSnapshot live)
	{
		return planRoute(state, live).entries;
	}

	public Route planRoute(GuideState state, LiveSnapshot live)
	{
		return planRoute(state, live, state.routeChoice());
	}

	public Route planRoute(GuideState state, LiveSnapshot live, RouteStyle style)
	{
		RewardGoal goal = state.rewardGoal();
		if (goal.advanced())
		{
			return new ElitePlanner(catalogue, this).plan(state, live, style);
		}
		int threshold = catalogue.threshold(goal);
		int deficit = Math.max(0, threshold - currentPoints(state, live));
		if (live == null || !live.mode.supported())
		{
			return new Route(List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0);
		}
		List<Entry> candidates = new ArrayList<>();
		List<Integer> taskPool = new ArrayList<>();
		Map<String, List<String>> groupChecks = new LinkedHashMap<>();
		Map<Integer, String> blockedTasks = new LinkedHashMap<>();
		Set<Integer> done = completedForGuide(state, live);
		Set<Integer> skipped = state.knownIds("skipTaskIDs", catalogue.tasks.keySet());
		Set<Integer> available = new HashSet<>();
		int prepared = 0;
		boolean withGroup = style == RouteStyle.GROUP;
		for (JsonObject activity : catalogue.activities(goal))
		{
			String key = activity.get("key").getAsString();
			boolean group = catalogue.groupOption(key, goal);
			if (state.skipped(key) || RiskPolicy.wilderness(key) && !state.wildernessEnabled() || group && !withGroup)
			{
				continue;
			}
			boolean keyAccess = permanentKeyAccess(key, done);
			List<String> gates = access(key, state, live, keyAccess);
			gates.addAll(strategy(key, state, live));
			if (goal == RewardGoal.HARD && RiskPolicy.groupHardcoreRisk(key, live.mode))
			{
				gates.add("This attempt can cost a group life. Choose a safer activity for the suggested route.");
			}
			for (boolean reserve : new boolean[] {false, true})
			{
				List<Integer> ids = new ArrayList<>();
				List<String> taskChecks = new ArrayList<>();
				for (int id : catalogue.routeIds(activity, reserve, goal))
				{
					if (done.contains(id) || skipped.contains(id) || catalogue.needsGroup(id, goal) && !withGroup)
					{
						continue;
					}
					taskPool.add(id);
					if (!gates.isEmpty())
					{
						blockedTasks.put(id, gates.get(0));
						if (reserve && !group)
						{
							continue;
						}
					}
					List<String> unmet = taskGates(id, state, live);
					if (group && unmet.isEmpty())
					{
						unmet.addAll(groupTaskKitChecks(key, id, state, live));
					}
					if (unmet.isEmpty())
					{
						ids.add(id);
					}
					else
					{
						blockedTasks.putIfAbsent(id, unmet.get(0));
						if (group)
						{
							taskChecks.addAll(unmet);
						}
					}
				}
				if (ids.isEmpty() && taskChecks.isEmpty())
				{
					continue;
				}
				Entry entry = candidate(activity, key, ids, gates, keyAccess, reserve, state, live);
				entry.kitBlockers.forEach(blockedTasks::putIfAbsent);
				if (group)
				{
					List<String> checks = new ArrayList<>(entry.gates);
					checks.addAll(entry.requiredKit);
					checks.addAll(taskChecks);
					groupChecks.put(key, checks);
				}
				boolean ready = entry.gates.isEmpty() && (!group || entry.requiredKit.isEmpty());
				if (ready)
				{
					available.addAll(ids);
					for (int id : ids)
					{
						if (!entry.kitBlockers.containsKey(id))
						{
							prepared += catalogue.tasks.get(id).points;
						}
					}
				}
				// A planned main task can show future unlock work. A reserve needs its access first.
				if (!ids.isEmpty() && (ready || !reserve && !group) &&
					!(goal == RewardGoal.HARD && RiskPolicy.groupHardcoreRisk(key, live.mode)))
				{
					if (goal == RewardGoal.HARD && ready && !entry.kitBlockers.isEmpty())
					{
						List<Integer> readyIds = new ArrayList<>(ids);
						readyIds.removeAll(entry.kitBlockers.keySet());
						if (!readyIds.isEmpty())
						{
							candidates.add(candidate(activity, key, readyIds, gates, keyAccess, reserve, state, live));
						}
						else if (!reserve)
						{
							candidates.add(entry);
						}
					}
					else
					{
						candidates.add(entry);
					}
				}
			}
		}
		String pinned = ReadinessEngine.str(state.data, "pinnedActivity", "");
		candidates.sort(Comparator.comparingInt((Entry e) -> priority(e, pinned, goal))
							.thenComparingInt(e -> catalogue.activity(e.key).has("starter") ? 0 : 1)
							.thenComparing(Comparator.comparingDouble((Entry e) -> e.score).reversed())
							.thenComparing(e -> e.key));
		List<Entry> out = new ArrayList<>();
		int planned = 0;
		for (Entry entry : candidates)
		{
			if (planned >= deficit)
			{
				break;
			}
			List<Integer> ids = new ArrayList<>();
			int points = 0;
			for (int id : entry.ids)
			{
				if (planned + points >= deficit)
				{
					break;
				}
				ids.add(id);
				points += catalogue.tasks.get(id).points;
			}
			if (ids.size() == entry.ids.size())
			{
				out.add(entry);
			}
			else
			{
				out.add(candidate(catalogue.activity(entry.key), entry.key, ids, entry.gates,
					permanentKeyAccess(entry.key, done), entry.reserve, state, live));
			}
			planned += points;
		}
		return new Route(out, taskPool, groupChecks, blockedTasks, catalogue.points(available), prepared, planned);
	}

	private int priority(Entry entry, String pinned, RewardGoal goal)
	{
		boolean group = catalogue.groupOption(entry.key, goal);
		if (goal == RewardGoal.HARD)
		{
			if (!entry.gates.isEmpty() || !entry.requiredKit.isEmpty())
			{
				return 4;
			}
			if (RiskPolicy.wilderness(entry.key))
			{
				return 3;
			}
			return entry.key.equals(pinned) ? -1 : 0;
		}
		if (!group && entry.key.equals(pinned))
		{
			return -1;
		}
		if (!entry.gates.isEmpty())
		{
			return 4;
		}
		if (group)
		{
			return 1;
		}
		if (RiskPolicy.wilderness(entry.key))
		{
			return 3;
		}
		return entry.reserve ? 2 : 0;
	}

	private List<String> groupTaskKitChecks(String key, int id, GuideState state, LiveSnapshot live)
	{
		List<String> checks = new ArrayList<>();
		JsonArray kit = catalogue.kit(key, state.rewardGoal());
		for (int i = 0; i < kit.size(); i++)
		{
			JsonObject req = kit.get(i).getAsJsonObject();
			if (ReadinessEngine.str(req, "role", "").equals("required_task"))
			{
				ReadinessEngine.Check check = readiness.check(key, i, req, state, live, live.at, List.of(id));
				if (check.blocksPlanning(live))
				{
					checks.add(catalogue.tasks.get(id).name + " needs " + check.label + ". " + check.detail);
				}
			}
		}
		return checks;
	}

	private Entry candidate(JsonObject activity, String key, List<Integer> ids, List<String> gates, boolean keyAccess,
							boolean reserve, GuideState state, LiveSnapshot live)
	{
		double minutes = 10 + RiskPolicy.penalty(key, live.mode) + gates.size() * 90;
		JsonObject effort = catalogue.route().getAsJsonObject("effort");
		for (int id : ids)
		{
			JsonObject rule = catalogue.rule(id);
			minutes += rule.has("effort") ? rule.get("effort").getAsDouble() :
				effort.has(String.valueOf(id)) ? effort.get(String.valueOf(id)).getAsDouble() : 10;
		}
		List<String> kitChecks = new ArrayList<>(), requiredKit = new ArrayList<>();
		Map<Integer, String> kitBlockers = new LinkedHashMap<>();
		JsonArray kit = catalogue.kit(key, state.rewardGoal());
		for (int i = 0; i < kit.size(); i++)
		{
			JsonObject req = kit.get(i).getAsJsonObject();
			String role = ReadinessEngine.str(req, "role", "");
			if (role.equals("optional_upgrade") || role.equals("convenience") || role.equals("attempt_note") ||
				ReadinessEngine.taskSetup(req) || coveredByKeyUnlock(req, keyAccess))
			{
				continue;
			}
			ReadinessEngine.Check check = readiness.check(key, i, req, state, live, live.at, ids);
			if (check.blocksPlanning(live))
			{
				String line = kitStatus(check);
				kitChecks.add(line);
				if (requiredKitRole(role) || role.equals("required_access"))
				{
					requiredKit.add(line);
					List<Integer> affected = req.has("task_ids") ? ReadinessEngine.ints(req.getAsJsonArray("task_ids")) : ids;
					for (int id : affected)
					{
						if (ids.contains(id))
						{
							kitBlockers.putIfAbsent(id, line);
						}
					}
				}
				minutes += check.status.equals(ReadinessEngine.UNKNOWN) ? 4 : 12;
			}
		}
		for (Map.Entry<String, JsonElement> stat : activity.getAsJsonObject("recommended").entrySet())
		{
			Integer current = ReadinessEngine.level(stat.getKey(), state, live);
			if (current == null || current < stat.getValue().getAsInt())
			{
				minutes += 8;
			}
		}
		int points = catalogue.points(ids);
		return new Entry(key, ids, points, gates, kitChecks, requiredKit, kitBlockers, minutes,
			points / Math.max(1, minutes), reserve);
	}

	public String partyBlocker(int id, GuideState state, RouteStyle style)
	{
		return partyBlocker(id, state, style, null);
	}

	public String partyBlocker(int id, GuideState state, RouteStyle style, LiveSnapshot live)
	{
		RewardGoal goal = state.rewardGoal();
		if (!goal.advanced() || !catalogue.needsGroup(id, goal))
		{
			return "";
		}
		JsonObject rule = catalogue.rule(id, goal);
		JsonObject party = rule.getAsJsonObject("party");
		int minimum = rule.get("minimumParty").getAsInt();
		int maximum = party.get("max").isJsonNull() ? 100 : party.get("max").getAsInt();
		int size = state.elitePartySize(catalogue.owner(id));
		String scale = minimum == maximum ? "exactly " + minimum : maximum == 100 ?
			"at least " + minimum : minimum + "–" + maximum;
		if (goal == RewardGoal.GRANDMASTER && live != null && live.mode == AccountMode.HARDCORE_GROUP &&
			party.get("prestigeRestrictedToOwnGroup").getAsBoolean() &&
			live.context.getOrDefault("caGroupMember", 0) == 1)
		{
			int roster = live.context.getOrDefault("caGroupSize", 0);
			if (roster >= 2 && roster <= 5 && (size > roster || minimum > roster))
			{
				return "Confirm " + scale + " eligible players in your Hardcore Group.";
			}
		}
		if (style != RouteStyle.GROUP && !(goal == RewardGoal.GRANDMASTER && style == RouteStyle.RAID_LIGHT &&
			catalogue.inRouteStyle(id, goal, style)))
		{
			return "Choose the Group route for this task. Party of " + scale + ".";
		}
		return size >= minimum && size <= maximum ? "" : "Confirm a party of " + scale + " eligible players.";
	}

	/** Account access and temporary encounter availability for the advanced routes. */
	public List<ItemRequirement> advancedAccessChecks(String key, List<Integer> ids, GuideState state, LiveSnapshot live)
	{
		List<ItemRequirement> checks = new ArrayList<>();
		JsonArray kit = catalogue.kit(key, state.rewardGoal());
		for (int index = 0; index < kit.size(); index++)
		{
			JsonObject req = kit.get(index).getAsJsonObject();
			String role = ReadinessEngine.str(req, "role", "");
			if (!role.equals("required_access") && !role.equals("attempt_access"))
			{
				continue;
			}
			List<Integer> affected = affectedTasks(req, ids);
			if (!affected.isEmpty())
			{
				ReadinessEngine.Check check = readiness.check(key, index, req, state, live, live == null ? Instant.now() : live.at, affected);
				checks.add(new ItemRequirement(check, List.copyOf(affected), role.equals("required_access"), req,
					req.has("manualCheck") ? "overrides" : "", ReadinessEngine.checkKey(key, index, req)));
			}
		}
		return checks;
	}

	/** Required gear and activity access. */
	public List<String> requiredTaskKit(int id, GuideState state, LiveSnapshot live)
	{
		String key = catalogue.owner(id);
		List<String> checks = new ArrayList<>();
		boolean keyAccess = permanentKeyAccess(key, state);
		JsonArray kit = catalogue.kit(key, state.rewardGoal());
		List<Integer> selected = List.of(id);
		Instant at = live == null ? Instant.now() : live.at;
		for (int i = 0; i < kit.size(); i++)
		{
			JsonObject req = kit.get(i).getAsJsonObject();
			String role = ReadinessEngine.str(req, "role", "");
			if ((requiredKitRole(role) || role.equals("required_access")) && !coveredByKeyUnlock(req, keyAccess) &&
				(!state.rewardGoal().advanced() || !ReadinessEngine.taskSetup(req)))
			{
				ReadinessEngine.Check check = readiness.check(key, i, req, state, live, at, selected);
				if (check.blocksPlanning(live) && !ReadinessEngine.UNUSED.equals(check.status))
				{
					checks.add(kitStatus(check));
				}
			}
		}
		return checks;
	}

	private static boolean requiredKitRole(String role)
	{
		return role.equals("required_task") || role.equals("required_strategy");
	}

	private static boolean coveredByKeyUnlock(JsonObject req, boolean keyAccess)
	{
		return keyAccess && ReadinessEngine.str(req, "label", "").matches("Giant key|Mossy key");
	}

	static String kitStatus(ReadinessEngine.Check check)
	{
		return check.label + " · " + check.detail;
	}

	/** Uncompleted points with access and route strategy checks met. */
	public int availablePoints(GuideState state, LiveSnapshot live)
	{
		return planRoute(state, live).availablePoints;
	}

	public List<String> access(String key, GuideState state, LiveSnapshot live)
	{
		return access(key, state, live, permanentKeyAccess(key, state));
	}

	private List<String> access(String key, GuideState state, LiveSnapshot live, boolean keyAccess)
	{
		List<String> gates = new ArrayList<>();
		JsonObject req = catalogue.activity(key, state.rewardGoal()).getAsJsonObject("requirements");
		if (!state.rewardGoal().advanced() && RiskPolicy.wilderness(key) && !state.wildernessEnabled())
		{
			gates.add("Wilderness is excluded. Opt in only after reviewing the risk.");
		}

		if (req.has("quests"))
		{
			for (JsonElement q : req.getAsJsonArray("quests"))
			{
				String name = q.getAsString();
				Boolean done = ReadinessEngine.quest(name, state, live);
				if (!Boolean.TRUE.equals(done))
				{
					gates.add((done == null ? "Confirm " : "Complete ") + name + " before this activity.");
				}
			}
		}
		if (req.has("levels"))
		{
			for (Map.Entry<String, JsonElement> s : req.getAsJsonObject("levels").entrySet())
			{
				Integer n = ReadinessEngine.level(s.getKey(), state, live);
				if (n == null || n < s.getValue().getAsInt())
				{
					gates.add((n == null ? "Confirm " : "Train ") + s.getKey() + " to " + s.getValue().getAsInt() +
								" for this route.");
				}
			}
		}
		if (req.has("items"))
		{
			for (JsonElement item : req.getAsJsonArray("items"))
			{
				String token = item.getAsString();
				if ((token.equals("giant-key") || token.equals("mossy-key")) && keyAccess)
				{
					continue;
				}
				ReadinessEngine.Check c =
					readiness.token(token, state, live, live == null ? java.time.Instant.now() : live.at);
				if (!c.ready())
				{
					gates.add((state.rewardGoal().supportsRaidLight() && ReadinessEngine.UNKNOWN.equals(c.status)
						? "Confirm " : "Prepare ") + c.label + ". " + c.detail);
				}
			}
		}
		return gates;
	}
	/** Requirements of the authored route, distinct from entry/unlock requirements. */
	public List<String> strategy(String key, GuideState state, LiveSnapshot live)
	{
		List<String> checks = new ArrayList<>();
		JsonObject activity = catalogue.activity(key, state.rewardGoal());
		JsonArray quests = strategyQuests(key, state);
		if (quests != null)
		{
			for (JsonElement value : quests)
			{
				String quest = value.getAsString();
				Boolean observed = ReadinessEngine.observedQuest(quest, live);
				if (!Boolean.TRUE.equals(observed))
				{
					checks.add((observed == null ? "Confirm completion of " : "Finish ") + quest + " for this route.");
				}
			}
		}
		JsonArray items = activity.getAsJsonArray("strategyItems");
		if (items != null)
		{
			for (JsonElement value : items)
			{
				String item = value.getAsString();
				ReadinessEngine.Check check = readiness.token(item, state, live,
					live == null ? java.time.Instant.now() : live.at);
				if (!check.ready())
				{
					checks.add("Prepare " + check.label + " for this route. " + check.detail);
				}
			}
		}
		JsonObject levels = activity.getAsJsonObject("strategyLevels");
		if (levels == null)
		{
			return checks;
		}
		for (Map.Entry<String, JsonElement> skill : levels.entrySet())
		{
			Integer observed = ReadinessEngine.level(skill.getKey(), state, live);
			if (observed == null || observed < skill.getValue().getAsInt())
			{
				checks.add((observed == null ? "Confirm " : "Train ") + skill.getKey() + " to " +
							skill.getValue().getAsInt() +
							" for this guide’s strategy; this is not an entry requirement.");
			}
		}
		return checks;
	}
	private JsonArray strategyQuests(String key, GuideState state)
	{
		return state.rewardGoal() == RewardGoal.HARD && key.equals("theatre") ?
			null : catalogue.activity(key, state.rewardGoal()).getAsJsonArray("strategyQuests");
	}

	private JsonObject taskRequirements(int id, GuideState state)
	{
		JsonObject rule = catalogue.rule(id, state.rewardGoal());
		if (rule != null && rule.has("requirements"))
		{
			return rule.getAsJsonObject("requirements");
		}
		return state.rewardGoal().supportsRaidLight() ? null :
			catalogue.route().getAsJsonObject("extraReq").getAsJsonObject(String.valueOf(id));
	}

	public List<String> requirementSummary(int id, GuideState state)
	{
		String key = catalogue.owner(id);
		JsonObject req = catalogue.activity(key, state.rewardGoal()).getAsJsonObject("requirements");
		JsonObject task = taskRequirements(id, state);
		Set<String> quests = new LinkedHashSet<>();
		for (JsonArray source : new JsonArray[] {req.getAsJsonArray("quests"), strategyQuests(key, state),
			task == null ? null : task.getAsJsonArray("quests")})
		{
			if (source != null)
			{
				source.forEach(q -> quests.add(q.getAsString()));
			}
		}
		List<String> lines = new ArrayList<>();
		if (!quests.isEmpty())
		{
			lines.add("Quests · " + String.join(", ", quests));
		}
		JsonObject levels = req.getAsJsonObject("levels");
		if (levels != null)
		{
			levels.entrySet().forEach(e -> lines.add(e.getValue().getAsInt() + " " + e.getKey() + " for access"));
		}
		JsonArray level = task == null ? null : task.getAsJsonArray("level");
		if (level != null)
		{
			lines.add(level.get(1).getAsInt() + " " + level.get(0).getAsString() + " for this task");
		}
		if (task != null && task.has("levels"))
		{
			task.getAsJsonObject("levels").entrySet().forEach(e ->
				lines.add(e.getValue().getAsInt() + " " + e.getKey() + " for this task"));
		}
		return lines;
	}

	public List<String> taskGates(int id, GuideState state, LiveSnapshot live)
	{
		List<String> gates = new ArrayList<>();
		JsonObject req = taskRequirements(id, state);
		if (req == null)
		{
			return gates;
		}
		if (req.has("level"))
		{
			JsonArray level = req.getAsJsonArray("level");
			Integer n = ReadinessEngine.level(level.get(0).getAsString(), state, live);
			if (n == null || n < level.get(1).getAsInt())
			{
				gates.add((n == null ? "Confirm " : "Train ") + level.get(0).getAsString() + " to " +
					level.get(1).getAsInt() + " for this task.");
			}
		}

		if (req.has("levels"))
		{
			for (Map.Entry<String, JsonElement> requirement : req.getAsJsonObject("levels").entrySet())
			{
				String skill = requirement.getKey();
				int minimum = requirement.getValue().getAsInt();
				Integer observed = ReadinessEngine.level(skill, state, live);
				if (observed == null || observed < minimum)
				{
					gates.add((observed == null ? "Confirm " : "Train ") + skill + " to " + minimum + " for this task.");
				}
			}
		}
		if (req.has("quests"))
		{
			for (JsonElement value : req.getAsJsonArray("quests"))
			{
				String quest = value.getAsString();
				Boolean complete = ReadinessEngine.quest(quest, state, live);
				if (!Boolean.TRUE.equals(complete))
				{
					String action = state.rewardGoal().supportsRaidLight() ?
						(complete == null ? "Confirm " : "Complete ") : "Complete or confirm ";
					gates.add(action + quest + " for this task.");
				}
			}
		}

		if (req.has("item"))
		{
			ReadinessEngine.Check c = readiness.token(req.get("item").getAsString(), state, live,
														live == null ? java.time.Instant.now() : live.at);
			if (c.blocksPlanning(live))
			{
				gates.add("Prepare " + c.label + ". " + c.detail);
			}
		}
		return gates;
	}

	/** Item checks for the unfinished tasks shown for one activity. */
	public List<ItemRequirement> itemRequirements(String key, List<Integer> ids, GuideState state, LiveSnapshot live)
	{
		if (ids.isEmpty())
		{
			return List.of();
		}
		Instant at = live == null ? Instant.now() : live.at;
		boolean keyAccess = permanentKeyAccess(key, state);
		JsonObject activity = catalogue.activity(key, state.rewardGoal());
		Map<String, Set<Integer>> resourceTasks = new LinkedHashMap<>();
		for (String section : new String[] {"items", "strategyItems"})
		{
			JsonArray resources = "items".equals(section) ? activity.getAsJsonObject("requirements").getAsJsonArray(section)
				: activity.getAsJsonArray(section);
			if (resources != null)
			{
				for (JsonElement element : resources)
				{
					String token = element.getAsString();
					if (!"hespori".equals(token) && (!keyAccess || !("giant-key".equals(token) || "mossy-key".equals(token))))
					{
						resourceTasks.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).addAll(ids);
					}
				}
			}
		}
		JsonObject extras = catalogue.route().getAsJsonObject("extraReq");
		for (int id : ids)
		{
			JsonObject extra = state.rewardGoal().advanced() ? taskRequirements(id, state) :
				extras.getAsJsonObject(String.valueOf(id));
			if (extra != null && extra.has("item"))
			{
				String token = extra.get("item").getAsString();
				resourceTasks.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(id);
			}
		}

		List<ItemRequirement> resources = new ArrayList<>();
		resourceTasks.forEach((token, tasks) ->
		{
			JsonObject definition = readiness.tokenRequirement(token);
			ReadinessEngine.Check check = readiness.token(token, state, live, at);
			resources.add(new ItemRequirement(check, List.copyOf(tasks), check.ready() || check.blocksPlanning(live),
				definition, definition == null ? "items" : "", token));
		});
		List<ItemRequirement> kitRows = new ArrayList<>();
		JsonArray kit = catalogue.kit(key, state.rewardGoal());
		for (int index = 0; index < kit.size(); index++)
		{
			JsonObject req = kit.get(index).getAsJsonObject();
			String role = ReadinessEngine.str(req, "role", "");
			boolean required = requiredKitRole(role);
			if ((!required && !role.equals("recommended") && !role.equals("provided_on_site")) ||
				ReadinessEngine.taskSetup(req) ||
				coveredByKeyUnlock(req, keyAccess) || dismissedKitCheck(state, key, index, req))
			{
				continue;
			}
			List<Integer> affected = affectedTasks(req, ids);
			if (affected.isEmpty())
			{
				continue;
			}
			ReadinessEngine.Check check = readiness.check(key, index, req, state, live, at, affected);
			for (int i = 0; i < resources.size(); i++)
			{
				ItemRequirement gate = resources.get(i);
				if (gate.taskIds.equals(affected) && sameItemCheck(gate.definition, req))
				{
					resources.remove(i);
					ReadinessEngine.Check result = required && gate.check.ready() ? check : gate.check;
					check = result.withLabel(check.label);
					required = true;
					break;
				}
			}
			required &= check.ready() || check.blocksPlanning(live);
			kitRows.add(new ItemRequirement(check, List.copyOf(affected), required, req,
				req.has("manualCheck") ? "overrides" : "", ReadinessEngine.checkKey(key, index, req)));
		}
		resources.addAll(kitRows);
		return resources;
	}

	private static List<Integer> affectedTasks(JsonObject req, List<Integer> ids)
	{
		if (!req.has("task_ids"))
		{
			return ids;
		}
		List<Integer> affected = new ArrayList<>(ids);
		affected.retainAll(ReadinessEngine.ints(req.getAsJsonArray("task_ids")));
		return affected;
	}

	private static boolean sameItemCheck(JsonObject token, JsonObject kit)
	{
		if (token == null || "doses".equals(ReadinessEngine.str(kit, "unit", "")))
		{
			return false;
		}
		for (String field : ITEM_CHECK_FIELDS)
		{
			if (!Objects.equals(token.get(field), kit.get(field)))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean dismissedKitCheck(GuideState state, String activity, int index, JsonObject req)
	{
		JsonObject override = state.object("overrides").getAsJsonObject(ReadinessEngine.checkKey(activity, index, req));
		return !req.has("manualCheck") && override != null && "Not required".equals(ReadinessEngine.str(override, "status", ""));
	}

	public static final class ItemRequirement
	{
		public final ReadinessEngine.Check check;
		public final List<Integer> taskIds;
		public final boolean required;
		public final String correctionCategory, correctionKey;
		private final JsonObject definition;

		private ItemRequirement(ReadinessEngine.Check check, List<Integer> taskIds, boolean required,
			JsonObject definition, String correctionCategory, String correctionKey)
		{
			this.check = check;
			this.taskIds = taskIds;
			this.required = required;
			this.definition = definition;
			this.correctionCategory = correctionCategory;
			this.correctionKey = correctionKey;
		}

		public String quantity()
		{
			if (definition == null || !definition.has("minimum") || definition.get("minimum").getAsInt() <= 1)
			{
				return "";
			}
			String unit = ReadinessEngine.str(definition, "unit", "");
			if (unit.isEmpty())
			{
				if (definition.has("spell") || definition.has("spells_any"))
				{
					unit = "casts";
				}
				else if ("ammo_compatible_with_selected_weapon".equals(ReadinessEngine.str(definition, "predicate", "")))
				{
					unit = "shots";
				}
				else if (definition.has("distinct_equipment_slots") && definition.get("distinct_equipment_slots").getAsBoolean())
				{
					unit = "equipment slots";
				}
				else
				{
					unit = "items";
				}
			}
			return definition.get("minimum").getAsInt() + " " + unit;
		}
	}

	public Unlocks missingUnlocks(Route route, GuideState state, LiveSnapshot live)
	{
		Unlocks result = new Unlocks();
		Map<String, Set<Integer>> byActivity = new LinkedHashMap<>();
		List<Integer> pool = route.taskPool;
		if (state.rewardGoal().advanced())
		{
			pool = new ArrayList<>();
			for (Entry entry : route.entries)
			{
				pool.addAll(entry.ids);
			}
		}
		for (int id : pool)
		{
			if (route.blockedTasks.containsKey(id))
			{
				byActivity.computeIfAbsent(catalogue.owner(id), ignored -> new HashSet<>()).add(id);
			}
		}
		for (Map.Entry<String, Set<Integer>> entry : byActivity.entrySet())
		{
			String key = entry.getKey(), label = catalogue.label(key);
			JsonObject activity = catalogue.activity(key, state.rewardGoal());
			JsonObject access = activity.getAsJsonObject("requirements");
			result.quests(access.getAsJsonArray("quests"), label, state, live, false);
			result.quests(strategyQuests(key, state), label, state, live, true);
			result.levels(access.getAsJsonObject("levels"), label, state, live);
			result.levels(activity.getAsJsonObject("strategyLevels"), label, state, live);
			for (int id : entry.getValue())
			{
				JsonObject task = taskRequirements(id, state);
				if (task == null)
				{
					continue;
				}
				result.quests(task.getAsJsonArray("quests"), label, state, live, false);
				result.levels(task.getAsJsonObject("levels"), label, state, live);
				JsonArray level = task.getAsJsonArray("level");
				if (level != null)
				{
					result.level(level.get(0).getAsString(), level.get(1).getAsInt(), label, state, live);
				}
			}
			JsonArray kit = catalogue.kit(key, state.rewardGoal());
			for (int i = 0; i < kit.size(); i++)
			{
				JsonObject req = kit.get(i).getAsJsonObject();
				if (!requiredKitRole(ReadinessEngine.str(req, "role", "")) ||
					dismissedKitCheck(state, key, i, req) ||
					(req.has("task_ids") && Collections.disjoint(entry.getValue(),
						ReadinessEngine.ints(req.getAsJsonArray("task_ids")))))
				{
					continue;
				}
				ReadinessEngine.KitUnlocks unlocks = readiness.kitUnlocks(req);
				unlocks.levels.forEach((skill, minimum) -> result.level(skill, minimum, label, state, live));
				for (String quest : unlocks.quests)
				{
					result.quest(quest, label, ReadinessEngine.quest(quest, state, live));
				}
			}
		}
		return result;
	}

	public static final class Unlocks
	{
		public final Map<String, Set<String>> quests = new LinkedHashMap<>();
		public final Map<String, Set<String>> levels = new LinkedHashMap<>();

		private void quests(JsonArray values, String activity, GuideState state, LiveSnapshot live, boolean observed)
		{
			if (values != null)
			{
				for (JsonElement value : values)
				{
					String name = value.getAsString();
					quest(name, activity, observed ? ReadinessEngine.observedQuest(name, live) :
						ReadinessEngine.quest(name, state, live));
				}
			}
		}

		private void quest(String name, String activity, Boolean completed)
		{
			if (!Boolean.TRUE.equals(completed))
			{
				quests.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(activity);
			}
		}

		private void levels(JsonObject requirements, String activity, GuideState state, LiveSnapshot live)
		{
			if (requirements != null)
			{
				requirements.entrySet().forEach(e -> level(e.getKey(), e.getValue().getAsInt(), activity, state, live));
			}
		}

		private void level(String skill, int minimum, String activity, GuideState state, LiveSnapshot live)
		{
			Integer observed = ReadinessEngine.level(skill, state, live);
			if (observed == null || observed < minimum)
			{
				String label = minimum + " " + skill.substring(0, 1).toUpperCase(Locale.ROOT) + skill.substring(1);
				levels.computeIfAbsent(label, ignored -> new LinkedHashSet<>()).add(activity);
			}
		}
	}
	public boolean permanentKeyAccess(String key, GuideState state)
	{
		return (key.equals("obor") || key.equals("bryophyta")) &&
			permanentKeyAccess(key, progress.knownCompleted(state));
	}

	private boolean permanentKeyAccess(String key, Set<Integer> completed)
	{
		if (!key.equals("obor") && !key.equals("bryophyta"))
		{
			return false;
		}
		List<Integer> bossTasks = ReadinessEngine.ints(catalogue.activity(key).getAsJsonArray("main"));
		bossTasks.addAll(ReadinessEngine.ints(catalogue.activity(key).getAsJsonArray("extra")));
		for (int id : completed)
		{
			if (bossTasks.contains(id))
			{
				return true;
			}
		}
		return false;
	}
	public static String risk(AccountMode mode)
	{
		switch (mode)
		{
		case HARDCORE:
			return "Hardcore: unsafe deaths remove status. Review each activity’s risk.";
		case HARDCORE_GROUP:
			return "Hardcore Group: group life rules differ from solo Hardcore, including raids.";
		case ULTIMATE:
			return "Ultimate: carried inventory/equipment only. Conventional bank and hidden storage are excluded.";
		case GROUP:
			return "Group: only your observed resources count. Shared storage and teammate supplies require confirmation.";
		default:
			return "Ironman: use your own available resources and the exact task conditions.";
		}
	}
	public static final class Route
	{
		public final List<Entry> entries;
		public final List<Integer> taskPool;
		public final Map<String, List<String>> groupChecks;
		public final Map<Integer, String> blockedTasks;
		public final Map<Integer, String> deferredTasks;
		public final GrandmasterLedger grandmaster;
		public final Set<Integer> uncheckedTasks, partyWaiting, attemptWaiting;
		public final int availablePoints, preparedPoints, plannedPoints;

		Route(List<Entry> entries, List<Integer> taskPool, Map<String, List<String>> groupChecks, Map<Integer, String> blockedTasks,
			int availablePoints, int preparedPoints, int plannedPoints)
		{
			this(entries, taskPool, groupChecks, blockedTasks, availablePoints, preparedPoints, plannedPoints,
				Set.of(), Set.of(), Set.of());
		}

		Route(List<Entry> entries, List<Integer> taskPool, Map<String, List<String>> groupChecks,
			Map<Integer, String> blockedTasks, int availablePoints, int preparedPoints, int plannedPoints,
			Set<Integer> uncheckedTasks, Set<Integer> partyWaiting, Set<Integer> attemptWaiting)
		{
			this(entries, taskPool, groupChecks, blockedTasks, availablePoints, preparedPoints, plannedPoints,
				uncheckedTasks, partyWaiting, attemptWaiting, null, Map.of());
		}

		Route(List<Entry> entries, List<Integer> taskPool, Map<String, List<String>> groupChecks,
			Map<Integer, String> blockedTasks, int availablePoints, int preparedPoints, int plannedPoints,
			Set<Integer> uncheckedTasks, Set<Integer> partyWaiting, Set<Integer> attemptWaiting,
			GrandmasterLedger grandmaster, Map<Integer, String> deferredTasks)
		{
			this.uncheckedTasks = Set.copyOf(uncheckedTasks);
			this.partyWaiting = Set.copyOf(partyWaiting);
			this.attemptWaiting = Set.copyOf(attemptWaiting);
			this.entries = List.copyOf(entries);
			this.taskPool = List.copyOf(taskPool);
			Map<String, List<String>> checks = new LinkedHashMap<>();
			groupChecks.forEach((key, lines) -> checks.put(key, List.copyOf(lines)));
			this.groupChecks = Collections.unmodifiableMap(checks);
			this.blockedTasks = Collections.unmodifiableMap(new LinkedHashMap<>(blockedTasks));
			this.deferredTasks = Map.copyOf(deferredTasks);
			this.grandmaster = grandmaster;
			this.availablePoints = availablePoints;
			this.preparedPoints = preparedPoints;
			this.plannedPoints = plannedPoints;
		}
	}

	public static final class Entry
	{
		public final String key;
		public final List<Integer> ids;
		public final List<String> gates, kitChecks, requiredKit;
		private final Map<Integer, String> kitBlockers;
		public final int gain;
		public final double minutes, score;
		public final boolean reserve;

		Entry(String key, List<Integer> ids, int gain, List<String> gates, List<String> kitChecks,
			List<String> requiredKit, Map<Integer, String> kitBlockers, double minutes, double score, boolean reserve)
		{
			this.key = key;
			this.ids = List.copyOf(ids);
			this.gain = gain;
			this.gates = List.copyOf(gates);
			this.kitChecks = List.copyOf(kitChecks);
			this.requiredKit = List.copyOf(requiredKit);
			this.kitBlockers = Map.copyOf(kitBlockers);
			this.minutes = minutes;
			this.score = score;
			this.reserve = reserve;
		}
	}
	public static final class Next
	{
		public final String activity, title, detail;
		public final List<Integer> ids;
		public final int gain;
		Next(String a, String t, String d, List<Integer> i, int g)
		{
			activity = a;
			title = t;
			detail = d;
			ids = Collections.unmodifiableList(new ArrayList<>(i));
			gain = g;
		}
	}
}
