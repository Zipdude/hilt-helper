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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Keeps the original state object, including unknown extension fields. */
public final class GuideState
{
	public final JsonObject data;
	public GuideState(JsonObject data)
	{
		validate(data);
		this.data = data.deepCopy();
		for (String k : new String[] {"syncIDs", "syncObservedIDs", "syncOverlapIDs", "skip"})
		{
			if (!this.data.has(k) || this.data.get(k).isJsonNull())
			{
				this.data.add(k, new JsonArray());
			}
		}
		for (String k : new String[] {"levels", "quests", "items", "kc", "overrides"})
		{
			if (!this.data.has(k))
			{
				this.data.add(k, new JsonObject());
			}
		}
		if (!this.data.has("skipTaskIDs"))
		{
			this.data.add("skipTaskIDs", new JsonArray());
		}
	}
	public static GuideState empty()
	{
		JsonObject o = new JsonObject();
		o.addProperty("version", 2);
		o.addProperty("baseTotal", 0);
		for (String k : new String[] {"baseIDs", "newIDs", "syncIDs", "syncObservedIDs", "syncOverlapIDs", "skip"})
		{
			o.add(k, new JsonArray());
		}
		o.add("syncAnchorIDs", JsonNull.INSTANCE);
		for (String k : new String[] {"levels", "quests", "items", "kc", "overrides"})
		{
			o.add(k, new JsonObject());
		}
		o.addProperty("wild", false);
		o.addProperty("username", "");
		o.addProperty("mode", "");
		return new GuideState(o);
	}
	public GuideState copy()
	{
		return new GuideState(data);
	}
	public RouteStyle routeChoice()
	{
		return RouteStyle.from(ReadinessEngine.str(data, "routeStyle", ""));
	}
	public int screenTask()
	{
		return data.has("screenTask") ? data.get("screenTask").getAsInt() : -1;
	}
	/** Diagnostic timestamps do not change a route or a saved correction. */
	public boolean sameProgress(GuideState other)
	{
		if (other == null)
		{
			return false;
		}
		for (Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			if (!entry.getKey().equals("nativeLastObservation") &&
				!entry.getValue().equals(other.data.get(entry.getKey())))
			{
				return false;
			}
		}
		for (String key : other.data.keySet())
		{
			if (!key.equals("nativeLastObservation") && !data.has(key))
			{
				return false;
			}
		}
		return true;
	}
	public static void validate(JsonObject o)
	{
		if (o == null || !o.has("version") || integer(o.get("version"), 1, 2) < 1)
		{
			throw new IllegalArgumentException("Unsupported progress version");
		}
		if (!o.has("baseTotal"))
		{
			throw new IllegalArgumentException("Missing starting point total");
		}
		integer(o.get("baseTotal"), 0, 100000);
		for (String k : new String[] {"baseIDs", "newIDs", "syncIDs", "syncObservedIDs", "syncOverlapIDs",
										"syncAnchorIDs", "skipTaskIDs"})
		{
			JsonElement v = o.get(k);
			if (v == null || v.isJsonNull())
			{
				if (k.equals("baseIDs") || k.equals("newIDs"))
				{
					throw new IllegalArgumentException("Missing " + k);
				}
				continue;
			}
			if (!v.isJsonArray() || v.getAsJsonArray().size() > 100000)
			{
				throw new IllegalArgumentException("Invalid " + k);
			}
			for (JsonElement id : v.getAsJsonArray())
			{
				integer(id, 0, 100000);
			}
		}
		for (String k : new String[] {"levels", "quests", "items", "kc", "overrides"})
		{
			if (o.has(k) && !o.get(k).isJsonObject())
			{
				throw new IllegalArgumentException("Invalid " + k);
			}
		}
		if (o.has("username") && (!isString(o.get("username")) || o.get("username").getAsString().length() > 12))
		{
			throw new IllegalArgumentException("Invalid character name");
		}
		if (o.has("levels"))
		{
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("levels").entrySet())
			{
				integer(e.getValue(), 1, 126);
			}
		}
		for (String k : new String[] {"quests", "items"})
		{
			if (o.has(k))
			{
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(k).entrySet())
				{
					if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isBoolean())
					{
						throw new IllegalArgumentException("Invalid manual " + k);
					}
				}
			}
		}
		if (o.has("skip"))
		{
			if (!o.get("skip").isJsonArray())
			{
				throw new IllegalArgumentException("Invalid skipped activities");
			}
			for (JsonElement e : o.getAsJsonArray("skip"))
			{
				if (!isString(e) || e.getAsString().length() > 90)
				{
					throw new IllegalArgumentException("Invalid activity key");
				}
			}
		}
		if (o.has("wild") && (!o.get("wild").isJsonPrimitive() || !o.getAsJsonPrimitive("wild").isBoolean()))
		{
			throw new IllegalArgumentException("Invalid Wilderness preference");
		}
		if (o.has("rewardGoal") &&
			(!isString(o.get("rewardGoal")) || o.get("rewardGoal").getAsString().length() > 32))
		{
			throw new IllegalArgumentException("Invalid reward goal");
		}
		if (o.has("pinnedActivity") &&
			(!isString(o.get("pinnedActivity")) || o.get("pinnedActivity").getAsString().length() > 90))
		{
			throw new IllegalArgumentException("Invalid followed activity");
		}
		if (o.has("routeStyle") && (!isString(o.get("routeStyle")) || o.get("routeStyle").getAsString().length() > 32))
		{
			throw new IllegalArgumentException("Invalid route choice");
		}
		if (o.has("screenTask"))
		{
			integer(o.get("screenTask"), 0, 100000);
		}
		if (o.has("hesporiReminder") &&
			(!o.get("hesporiReminder").isJsonPrimitive() || !o.getAsJsonPrimitive("hesporiReminder").isBoolean()))
		{
			throw new IllegalArgumentException("Invalid Hespori reminder preference");
		}
		if (o.has("hesporiObservation"))
		{
			if (!o.get("hesporiObservation").isJsonObject())
			{
				throw new IllegalArgumentException("Invalid Hespori observation");
			}
			JsonObject observation = o.getAsJsonObject("hesporiObservation");
			integer(observation.get("value"), 0, 9);
			if (!isString(observation.get("at")) || observation.get("at").getAsString().length() > 40)
			{
				throw new IllegalArgumentException("Invalid Hespori observation time");
			}
			try
			{
				Instant seen = Instant.parse(observation.get("at").getAsString());
				if (seen.isBefore(Instant.EPOCH) || seen.getEpochSecond() > 253402300799L)
				{
					throw new IllegalArgumentException("Invalid Hespori observation time");
				}
			}
			catch (java.time.format.DateTimeParseException failure)
			{
				throw new IllegalArgumentException("Invalid Hespori observation time", failure);
			}
		}
		if (o.has("hesporiNotice") && (!isString(o.get("hesporiNotice")) || o.get("hesporiNotice").getAsString().length() > 24))
		{
			throw new IllegalArgumentException("Invalid Hespori notice");
		}
		if (o.has("overrides"))
		{
			for (Map.Entry<String, JsonElement> entry : o.getAsJsonObject("overrides").entrySet())
			{
				if (entry.getValue().isJsonObject())
				{
					for (String field : new String[] {"status", "at"})
					{
						JsonElement value = entry.getValue().getAsJsonObject().get(field);
						if (value != null && !isString(value))
						{
							throw new IllegalArgumentException("Invalid preparation correction");
						}
					}
				}
			}
		}
		if (o.has("correctionDates"))
		{
			if (!o.get("correctionDates").isJsonObject())
			{
				throw new IllegalArgumentException("Invalid correction dates");
			}
			for (Map.Entry<String, JsonElement> entry : o.getAsJsonObject("correctionDates").entrySet())
			{
				if (!isString(entry.getValue()))
				{
					throw new IllegalArgumentException("Invalid correction date");
				}
			}
		}
	}
	public static boolean isString(JsonElement e)
	{
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}
	public static int integer(JsonElement e, int min, int max)
	{
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			throw new IllegalArgumentException("Expected an integer");
		}
		try
		{
			int n = new BigDecimal(e.getAsString()).intValueExact();
			if (n < min || n > max)
			{
				throw new ArithmeticException();
			}
			return n;
		}
		catch (ArithmeticException ex)
		{
			throw new IllegalArgumentException("Number outside supported range");
		}
	}
	public LinkedHashSet<Integer> ids(String key)
	{
		LinkedHashSet<Integer> out = new LinkedHashSet<>();
		JsonElement e = data.get(key);
		if (e != null && e.isJsonArray())
		{
			for (JsonElement id : e.getAsJsonArray())
			{
				out.add(id.getAsInt());
			}
		}
		return out;
	}
	/** Project known IDs for display/calculation while keeping every raw extension ID in data. */
	public LinkedHashSet<Integer> knownIds(String key, Set<Integer> catalogue)
	{
		LinkedHashSet<Integer> known = new LinkedHashSet<>();
		JsonElement value = data.get(key);
		if (value != null && value.isJsonArray())
		{
			for (JsonElement id : value.getAsJsonArray())
			{
				int number = id.getAsInt();
				if (catalogue.contains(number))
				{
					known.add(number);
				}
			}
		}
		return known;
	}
	public void ids(String key, Collection<Integer> ids)
	{
		JsonArray a = new JsonArray();
		for (int id : new LinkedHashSet<>(ids))
		{
			a.add(id);
		}
		data.add(key, a);
	}
	public JsonObject object(String key)
	{
		if (!data.has(key) || !data.get(key).isJsonObject())
		{
			data.add(key, new JsonObject());
		}
		return data.getAsJsonObject(key);
	}
	public boolean anchorsInitialized()
	{
		return data.has("syncAnchorIDs") && !data.get("syncAnchorIDs").isJsonNull();
	}
	public int baseline()
	{
		return data.get("baseTotal").getAsInt();
	}
	public String name()
	{
		return data.has("username") ? data.get("username").getAsString() : "";
	}
	public boolean skipped(String activity)
	{
		if (data.has("skip"))
		{
			for (JsonElement e : data.getAsJsonArray("skip"))
			{
				if (activity.equals(e.getAsString()))
				{
					return true;
				}
			}
		}
		return false;
	}
	public void skip(String key, boolean value)
	{
		LinkedHashSet<String> all = new LinkedHashSet<>();
		for (JsonElement e : data.getAsJsonArray("skip"))
		{
			all.add(e.getAsString());
		}
		if (value)
		{
			all.add(key);
		}
		else
		{
			all.remove(key);
		}
		JsonArray a = new JsonArray();
		all.forEach(a::add);
		data.add("skip", a);
	}
	public void manualTask(int id, boolean value, Instant now)
	{
		if (ids("syncIDs").contains(id) || ids("baseIDs").contains(id))
		{
			return;
		}
		Set<Integer> added = ids("newIDs");
		if (value)
		{
			added.add(id);
			object("manualCompletedAt").addProperty(String.valueOf(id), now.toString());
		}
		else
		{
			added.remove(id);
			object("manualCompletedAt").remove(String.valueOf(id));
		}
		ids("newIDs", added);
	}
	public void skipTask(int id, boolean skip)
	{
		Set<Integer> ids = ids("skipTaskIDs");
		if (skip)
		{
			ids.add(id);
		}
		else
		{
			ids.remove(id);
		}
		ids("skipTaskIDs", ids);
	}
	public boolean wildernessEnabled()
	{
		return data.has("wild") && data.get("wild").isJsonPrimitive() && data.get("wild").getAsBoolean();
	}
}
