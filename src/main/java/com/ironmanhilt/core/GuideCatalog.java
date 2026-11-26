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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public final class GuideCatalog
{
	public static final String RESOURCE = "/com/ironmanhilt/";
	public final SortedMap<Integer, Task> tasks = new TreeMap<>();
	public final List<JsonObject> activities = new ArrayList<>();
	public final JsonObject guide, items;
	private final JsonObject hardGuide;
	private final Map<RewardGoal, AdvancedGuide> advancedGuides = new EnumMap<>(RewardGoal.class);
	private final List<JsonObject> hardActivities = new ArrayList<>();
	private final Map<String, JsonObject> hardExtensions = new HashMap<>();
	private final Set<Integer> hardTaskIds = new HashSet<>();
	final Set<Integer> hardItemIds = new HashSet<>();
	public final int threshold, hardThreshold, eliteThreshold, masterThreshold;
	private final Map<Integer, JsonObject> rules = new HashMap<>();
	private final Map<Integer, String> owners = new HashMap<>();
	private final Map<String, JsonObject> byKey = new HashMap<>();
	private final Set<Integer> reserves = new HashSet<>();
	private final Map<Integer, String> searchText = new HashMap<>();
	public final String checkedDate;

	public GuideCatalog(Gson gson) throws IOException
	{
		JsonObject catalogue = resource(gson, "combat_achievements.json");
		threshold = catalogue.get("medium_threshold").getAsInt();
		hardThreshold = catalogue.get("hard_threshold").getAsInt();
		eliteThreshold = catalogue.get("elite_threshold").getAsInt();
		masterThreshold = catalogue.get("master_threshold").getAsInt();
		checkedDate = catalogue.get("verified_date").getAsString();
		List<String> tiers = List.of("Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster");
		for (JsonElement value : catalogue.getAsJsonArray("tasks"))
		{
			JsonObject o = value.getAsJsonObject();
			Task task = new Task(o.get("id").getAsInt(), o.get("name").getAsString(), o.get("tier").getAsString(),
									o.get("points").getAsInt());
			if (task.points != tiers.indexOf(task.tier) + 1 || task.points == 0 || tasks.put(task.id, task) != null)
			{
				throw new IOException("Invalid catalogue task ID or points: " + task.id);
			}
		}
		guide = resource(gson, "guide.json");
		items = resource(gson, "item_catalog.json");
		hardGuide = resource(gson, "hard_guide.json");
		for (Map.Entry<String, JsonElement> item : hardGuide.getAsJsonObject("itemEntries").entrySet())
		{
			JsonObject registry = items.getAsJsonObject("items");
			if (registry.has(item.getKey()))
			{
				throw new IOException("Duplicate Hard kit item: " + item.getKey());
			}
			registry.add(item.getKey(), item.getValue());
			hardItemIds.add(Integer.parseInt(item.getKey()));
		}
		for (Map.Entry<String, JsonElement> kit : hardGuide.getAsJsonObject("kitProfiles").entrySet())
		{
			JsonObject profiles = items.getAsJsonObject("boss_profiles");
			JsonObject profile = profiles.getAsJsonObject(kit.getKey());
			if (profile == null)
			{
				profile = new JsonObject();
				profile.add("requirements", new JsonArray());
				profiles.add(kit.getKey(), profile);
			}
			profile.getAsJsonArray("requirements").addAll(kit.getValue().getAsJsonArray());
		}
		for (JsonElement a : guide.getAsJsonArray("activities"))
		{
			activities.add(a.getAsJsonObject());
		}
		if (tasks.size() != 655 || tasks.firstKey() != 0 || tasks.lastKey() != 654 || threshold != 169 || hardThreshold != 436)
		{
			throw new IOException("Bundled catalogue integrity check failed");
		}
		for (int id = 0; id < 655; id++)
		{
			if (!tasks.containsKey(id))
			{
				throw new IOException("Catalogue gap");
			}
		}
		Set<Integer> routed = new HashSet<>();
		for (JsonObject activity : activities)
		{
			String key = activity.get("key").getAsString();
			if (byKey.put(key, activity) != null)
			{
				throw new IOException("Duplicate guide activity: " + key);
			}
			register(activity, routed);
		}
		hardActivities.addAll(activities);
		for (JsonElement value : hardGuide.getAsJsonArray("activities"))
		{
			JsonObject extension = value.getAsJsonObject();
			String key = extension.get("key").getAsString();
			if (byKey.containsKey(key))
			{
				if (hardExtensions.put(key, extension) != null)
				{
					throw new IOException("Duplicate Hard activity: " + key);
				}
			}
			else
			{
				byKey.put(key, extension);
				hardActivities.add(extension);
			}
			register(extension, hardTaskIds);
		}
		if (!java.util.Collections.disjoint(routed, hardTaskIds))
		{
			throw new IOException("Duplicate task between guide resources");
		}
		registerAdvanced(RewardGoal.ELITE, resource(gson, "elite_guide.json"), 4);
		JsonObject master = resource(gson, "master_guide.json");
		registerAdvanced(RewardGoal.MASTER, master, 5);
		mergeMasterRegistry(master, "spellEntries", "spells");
	}

	private void mergeMasterRegistry(JsonObject source, String field, String registryKey) throws IOException
	{
		JsonObject registry = items.getAsJsonObject(registryKey);
		for (Map.Entry<String, JsonElement> row : source.getAsJsonObject(field).entrySet())
		{
			if (registry.has(row.getKey()))
			{
				throw new IOException("Duplicate Master " + registryKey + " entry: " + row.getKey());
			}
			registry.add(row.getKey(), row.getValue());
		}
	}

	private void registerAdvanced(RewardGoal goal, JsonObject definition, int rank) throws IOException
	{
		int cumulative = tasks.values().stream().filter(t -> t.points <= rank).mapToInt(t -> t.points).sum();
		if (definition.get("schema").getAsInt() != 1 || threshold(goal) != cumulative ||
			definition.get("threshold").getAsInt() != cumulative)
		{
			throw new IOException(goal.label + " target does not match the bundled catalogue");
		}
		AdvancedGuide advanced = new AdvancedGuide(definition);
		advancedGuides.put(goal, advanced);
		for (JsonElement value : definition.getAsJsonArray("activities"))
		{
			JsonObject rows = value.getAsJsonObject();
			String key = rows.get("key").getAsString();
			JsonObject activity = goal == RewardGoal.MASTER ? masterActivity(key, rows) : rows;
			if (advanced.activities.put(key, activity) != null)
			{
				throw new IOException("Duplicate " + goal.label + " activity: " + key);
			}
			byKey.putIfAbsent(key, activity);
			List<Integer> listed = routeIds(activity, false);
			listed.addAll(routeIds(activity, true));
			Set<Integer> ids = new HashSet<>(listed);
			for (JsonElement element : activity.getAsJsonArray("tasks"))
			{
				JsonObject rule = element.getAsJsonObject();
				int id = rule.get("id").getAsInt();
				if (!ids.remove(id) || !tasks.containsKey(id) || !tasks.get(id).name.equals(rule.get("name").getAsString()) ||
					advanced.rules.put(id, rule) != null || !owners.getOrDefault(id, key).equals(key) ||
					!List.of("default", "reserve", "group", "catalogue_only").contains(rule.get("role").getAsString()))
				{
					throw new IOException("Invalid advanced rule or shared ownership: " + id);
				}
				owners.putIfAbsent(id, key);
				advanced.search.put(id, searchable(key, rule));
			}
			if (!ids.isEmpty() || new HashSet<>(listed).size() != listed.size())
			{
				throw new IOException("Invalid advanced membership: " + key);
			}
			JsonArray kit = new JsonArray();
			for (JsonElement element : kit(key))
			{
				JsonObject check = eliteKitCheck(key, activity, element.getAsJsonObject());
				kit.add(check);
			}
			kit.addAll(activity.getAsJsonArray("kitChecks"));
			advanced.kits.put(key, kit);
		}
		Set<Integer> tripTasks = new HashSet<>();
		for (JsonElement value : trips(goal))
		{
			JsonObject trip = value.getAsJsonObject();
			String key = trip.get("activity").getAsString();
			List<Integer> ids = ReadinessEngine.ints(trip.getAsJsonArray("ids"));
			if (ids.isEmpty() || !advanced.activities.containsKey(key))
			{
				throw new IOException("Invalid advanced visit: " + key);
			}
			for (int id : ids)
			{
				if (!key.equals(owner(id)) || !advanced.rules.containsKey(id) || reference(id, goal) || !tripTasks.add(id))
				{
					throw new IOException("Invalid or duplicate advanced visit task: " + id);
				}
			}
		}
		for (Task task : tasks.values())
		{
			boolean authored = advanced.rules.containsKey(task.id);
			if ((!authored && (task.points == rank || goal == RewardGoal.MASTER)) ||
				(authored && !reference(task.id, goal) && !tripTasks.contains(task.id)))
			{
				throw new IOException("Missing advanced task or visit: " + task.id);
			}
		}
	}

	private JsonObject masterActivity(String key, JsonObject rows) throws IOException
	{
		JsonObject shared = advancedGuides.get(RewardGoal.ELITE).activities.get(key);
		if (shared == null)
		{
			throw new IOException("Master activity has no canonical owner: " + key);
		}
		JsonObject merged = shared.deepCopy();
		rows.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
		return merged;
	}

	private static JsonObject eliteKitCheck(String key, JsonObject activity, JsonObject original)
	{
		// Keep the original index so saved equipment and access checks still match both guides.
		JsonObject check = original.deepCopy();
		String label = check.get("label").getAsString();
		if (containsLabel(activity, "omitLegacyChecks", label))
		{
			check.add("task_ids", new JsonArray());
		}
		if (activity.getAsJsonObject("legacyTaskScopes").has(label))
		{
			check.add("task_ids", activity.getAsJsonObject("legacyTaskScopes").get(label));
		}
		if (containsLabel(activity, "legacySuggestedChecks", label))
		{
			check.addProperty("role", "recommended");
		}
		if (key.equals("zulrah") && label.equals("Zul-Andra access through Regicide"))
		{
			check.addProperty("access_rule", "zulrah");
		}
		if (key.equals("guardian") && label.equals("Brittle key or previously unlocked rooftop"))
		{
			check.addProperty("access_rule", "guardian");
			JsonArray keys = new JsonArray();
			keys.add(21724);
			check.add("access_item_ids", keys);
		}
		if (containsLabel(activity, "legacyAccessChecks", label))
		{
			check.addProperty("role", "required_access");
			if (key.equals("corrupted-gauntlet"))
			{
				check.addProperty("access_rule", "corrupted-gauntlet");
				JsonArray clears = new JsonArray();
				for (int id = 98; id <= 117; id++)
				{
					clears.add(id);
				}
				check.add("unlock_ids", clears);
			}
		}
		if (containsLabel(activity, "legacyAttemptChecks", label))
		{
			check.addProperty("role", "attempt_access");
			JsonArray assignments = new JsonArray();
			Map<String, List<String>> options = Map.of(
				"shellbane", List.of("shellbanegryphons", "shellbanegryphon"),
				"guardian", List.of("gargoyles", "grotesqueguardians"),
				"kraken", List.of("cavekraken", "kraken"),
				"sire", List.of("abyssaldemons", "abyssalsire"));
			options.getOrDefault(key, List.of()).forEach(assignments::add);
			if (assignments.size() > 0)
			{
				check.add("slayer_assignments", assignments);
			}
		}
		return check;
	}

	private static boolean containsLabel(JsonObject activity, String field, String label)
	{
		for (JsonElement value : activity.getAsJsonArray(field))
		{
			if (label.equals(value.getAsString()))
			{
				return true;
			}
		}
		return false;
	}

	private String searchable(String key, JsonObject rule)
	{
		return (rule.get("id").getAsInt() + " " + label(key) + " " + rule.get("name").getAsString() + " " +
			rule.get("condition").getAsString() + " " + rule.get("tip").getAsString()).toLowerCase(Locale.ROOT);
	}

	private void register(JsonObject activity, Set<Integer> routed) throws IOException
	{
		String key = activity.get("key").getAsString();
		Set<Integer> members = new HashSet<>();
		for (String field : new String[] {"main", "extra"})
		{
			for (int id : ReadinessEngine.ints(activity.getAsJsonArray(field)))
			{
				if (!members.add(id) || !routed.add(id))
				{
					throw new IOException("Duplicate authored route membership: " + id);
				}
				if (field.equals("extra"))
				{
					reserves.add(id);
				}
			}
		}
		Set<Integer> local = new HashSet<>();
		for (JsonElement value : activity.getAsJsonArray("tasks"))
		{
			JsonObject rule = value.getAsJsonObject();
			int id = rule.get("id").getAsInt();
			if (!local.add(id) || !tasks.containsKey(id) || !tasks.get(id).name.equals(rule.get("name").getAsString()) ||
				rules.put(id, rule) != null)
			{
				throw new IOException("Invalid or duplicate authored rule: " + id);
			}
			owners.put(id, key);
			searchText.put(id, searchable(key, rule));
		}
		if (!local.equals(members))
		{
			throw new IOException("Authored task and route membership differ: " + key);
		}
	}

	public int threshold(RewardGoal goal)
	{
		if (goal == RewardGoal.MASTER)
		{
			return masterThreshold;
		}
		if (goal == RewardGoal.ELITE)
		{
			return eliteThreshold;
		}
		return goal == RewardGoal.HARD ? hardThreshold : threshold;
	}

	public List<JsonObject> activities(RewardGoal goal)
	{
		if (goal.advanced())
		{
			return List.copyOf(advancedGuides.get(goal).activities.values());
		}
		return goal == RewardGoal.HARD ? hardActivities : activities;
	}

	public boolean includes(int id, RewardGoal goal)
	{
		if (goal.advanced())
		{
			return advancedGuides.get(goal).rules.containsKey(id);
		}
		return rules.containsKey(id) && (goal == RewardGoal.HARD || !hardTaskIds.contains(id));
	}

	public boolean groupOption(String key, RewardGoal goal)
	{
		return !goal.advanced() && groupOption(key) &&
			(goal == RewardGoal.MEDIUM || !key.equals("chambers") && !key.equals("theatre"));
	}

	public boolean needsGroup(int id, RewardGoal goal)
	{
		String key = owner(id);
		JsonObject r = rule(id, goal);
		return key != null && r != null && (groupOption(key, goal) || r.has("minimumParty"));
	}

	public boolean inRouteStyle(int id, RewardGoal goal, RouteStyle style)
	{
		JsonObject task = rule(id, goal);
		return task != null && (!needsGroup(id, goal) || style == RouteStyle.GROUP) &&
			(style != RouteStyle.RAID_LIGHT || task.has("raidLight") && task.get("raidLight").getAsBoolean());
	}

	public JsonArray preparation(RewardGoal goal)
	{
		if (goal.advanced())
		{
			return advancedGuides.get(goal).data.getAsJsonArray("preparation");
		}
		return (goal == RewardGoal.HARD ? hardGuide : guide).getAsJsonArray("preparation");
	}

	public JsonArray steps(JsonObject activity, RewardGoal goal)
	{
		JsonObject extension = hardExtensions.get(activity.get("key").getAsString());
		return goal == RewardGoal.HARD && extension != null && extension.has("steps") ?
			extension.getAsJsonArray("steps") : activity.getAsJsonArray("steps");
	}

	public List<Integer> routeIds(JsonObject activity, boolean reserve, RewardGoal goal)
	{
		if (goal.advanced())
		{
			JsonObject authored = advancedGuides.get(goal).activities.get(activity.get("key").getAsString());
			return authored == null ? new ArrayList<>() : routeIds(authored, reserve);
		}
		List<Integer> ids = routeIds(activity, reserve);
		JsonObject extension = hardExtensions.get(activity.get("key").getAsString());
		if (goal == RewardGoal.HARD && extension != null)
		{
			ids.addAll(routeIds(extension, reserve));
		}
		return ids;
	}
	private static JsonObject resource(Gson gson, String path) throws IOException
	{
		InputStream stream = GuideCatalog.class.getResourceAsStream(RESOURCE + path);
		if (stream == null)
		{
			throw new IOException("Missing bundled resource: " + path);
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, JsonObject.class);
		}
	}
	public int points(Collection<Integer> ids)
	{
		int total = 0;
		for (int id : ids instanceof Set ? ids : new HashSet<>(ids))
		{
			if (tasks.containsKey(id))
			{
				total += tasks.get(id).points;
			}
		}
		return total;
	}
	public JsonObject activity(String key)
	{
		JsonObject activity = byKey.get(key);
		if (activity == null)
		{
			throw new IllegalArgumentException("Unknown activity");
		}
		return activity;
	}
	public JsonObject activity(String key, RewardGoal goal)
	{
		return goal.advanced() ? advancedGuides.get(goal).activities.getOrDefault(key, activity(key)) : activity(key);
	}
	public String label(String key)
	{
		return ReadinessEngine.str(activity(key), "label", key);
	}
	public JsonArray kit(String key)
	{
		JsonObject profile = items.getAsJsonObject("boss_profiles").getAsJsonObject(key);
		return profile == null ? new JsonArray() : profile.getAsJsonArray("requirements");
	}
	public JsonArray kit(String key, RewardGoal goal)
	{
		return goal.advanced() ? advancedGuides.get(goal).kits.getOrDefault(key, new JsonArray()) : kit(key);
	}
	public JsonObject rule(int id)
	{
		return rules.getOrDefault(id, advancedGuides.get(RewardGoal.ELITE).rules.get(id));
	}
	public JsonObject rule(int id, RewardGoal goal)
	{
		return goal.advanced() ? advancedGuides.get(goal).rules.get(id) : rules.get(id);
	}
	public boolean reference(int id, RewardGoal goal)
	{
		JsonObject rule = rule(id, goal);
		return goal.advanced() && rule != null && rule.get("role").getAsString().equals("catalogue_only");
	}

	JsonArray trips(RewardGoal goal)
	{
		return advancedGuides.get(goal).data.getAsJsonArray("trips");
	}

	JsonObject riskProfiles(RewardGoal goal)
	{
		return advancedGuides.get(goal).data.getAsJsonObject("riskProfiles");
	}
	public String owner(int id)
	{
		return owners.get(id);
	}
	public boolean reserve(int id)
	{
		return reserves.contains(id);
	}
	public boolean reserve(int id, RewardGoal goal)
	{
		JsonObject rule = rule(id, goal);
		return goal.advanced() ? rule != null && rule.get("role").getAsString().equals("reserve") : reserve(id);
	}
	public boolean groupOption(String key)
	{
		return activity(key).has("groupOption");
	}
	public boolean matches(int id, String query)
	{
		return searchText.getOrDefault(id, "").contains(query);
	}
	public boolean matches(int id, String query, RewardGoal goal)
	{
		return goal.advanced() ? advancedGuides.get(goal).search.getOrDefault(id, "").contains(query) : matches(id, query);
	}
	public List<Integer> routeIds(JsonObject activity, boolean reserves)
	{
		return ReadinessEngine.ints(activity.getAsJsonArray(reserves ? "extra" : "main"));
	}
	public JsonObject route()
	{
		return guide.getAsJsonObject("originalRoute");
	}
	private static final class AdvancedGuide
	{
		private final JsonObject data;
		private final Map<String, JsonObject> activities = new LinkedHashMap<>();
		private final Map<Integer, JsonObject> rules = new HashMap<>();
		private final Map<Integer, String> search = new HashMap<>();
		private final Map<String, JsonArray> kits = new HashMap<>();

		private AdvancedGuide(JsonObject data)
		{
			this.data = data;
		}
	}

	public static final class Task
	{
		public final int id, points;
		public final String name, tier;
		public Task(int id, String name, String tier, int points)
		{
			this.id = id;
			this.name = name;
			this.tier = tier;
			this.points = points;
		}
	}
}
