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
import java.util.HashMap;
import java.util.HashSet;
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
	private final List<JsonObject> hardActivities = new ArrayList<>();
	private final Map<String, JsonObject> hardExtensions = new HashMap<>();
	private final Set<Integer> hardTaskIds = new HashSet<>();
	final Set<Integer> hardItemIds = new HashSet<>();
	public final int threshold, hardThreshold;
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
		checkedDate = catalogue.get("verified_date").getAsString();
		for (JsonElement value : catalogue.getAsJsonArray("tasks"))
		{
			JsonObject o = value.getAsJsonObject();
			Task task = new Task(o.get("id").getAsInt(), o.get("name").getAsString(), o.get("tier").getAsString(),
									o.get("points").getAsInt());
			if (tasks.put(task.id, task) != null)
			{
				throw new IOException("Duplicate catalogue task ID");
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
			searchText.put(id, (id + " " + label(key) + " " + rule.get("name").getAsString() + " " +
				rule.get("condition").getAsString() + " " + rule.get("tip").getAsString()).toLowerCase(Locale.ROOT));
		}
		if (!local.equals(members))
		{
			throw new IOException("Authored task and route membership differ: " + key);
		}
	}

	public int threshold(RewardGoal goal)
	{
		return goal == RewardGoal.HARD ? hardThreshold : threshold;
	}

	public List<JsonObject> activities(RewardGoal goal)
	{
		return goal == RewardGoal.HARD ? hardActivities : activities;
	}

	public boolean includes(int id, RewardGoal goal)
	{
		return rules.containsKey(id) && (goal == RewardGoal.HARD || !hardTaskIds.contains(id));
	}

	public boolean groupOption(String key, RewardGoal goal)
	{
		return groupOption(key) && (goal == RewardGoal.MEDIUM || !key.equals("chambers") && !key.equals("theatre"));
	}

	public boolean needsGroup(int id, RewardGoal goal)
	{
		String key = owner(id);
		JsonObject r = rule(id);
		return key != null && (groupOption(key, goal) || r.has("minimumParty"));
	}

	public JsonArray preparation(RewardGoal goal)
	{
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
		for (int id : ids instanceof java.util.Set ? ids : new HashSet<>(ids))
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
	public String label(String key)
	{
		return ReadinessEngine.str(activity(key), "label", key);
	}
	public JsonArray kit(String key)
	{
		JsonObject profile = items.getAsJsonObject("boss_profiles").getAsJsonObject(key);
		return profile == null ? new JsonArray() : profile.getAsJsonArray("requirements");
	}
	public JsonObject rule(int id)
	{
		return rules.get(id);
	}
	public String owner(int id)
	{
		return owners.get(id);
	}
	public boolean reserve(int id)
	{
		return reserves.contains(id);
	}
	public boolean groupOption(String key)
	{
		return activity(key).has("groupOption");
	}
	public boolean matches(int id, String query)
	{
		return searchText.getOrDefault(id, "").contains(query);
	}
	public List<Integer> routeIds(JsonObject activity, boolean reserves)
	{
		return ReadinessEngine.ints(activity.getAsJsonArray(reserves ? "extra" : "main"));
	}
	public JsonObject route()
	{
		return guide.getAsJsonObject("originalRoute");
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
