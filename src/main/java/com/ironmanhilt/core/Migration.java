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
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Migration
{
	private final Gson gson;
	private final ProgressEngine progress;
	public Migration(Gson gson, ProgressEngine progress)
	{
		this.gson = gson;
		this.progress = progress;
	}
	public Preview preview(String json, String targetKey, String targetName)
	{
		if (json == null || json.length() > 5_000_000)
		{
			throw new IllegalArgumentException("Progress backup exceeds 5 MB");
		}
		JsonObject root;
		try
		{
			root = gson.fromJson(json, JsonObject.class);
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			throw new IllegalArgumentException("The backup is not a valid progress document", ex);
		}
		if (root == null || !root.has("guide") || !"osrs-hilt2-field-guide".equals(root.get("guide").getAsString()) ||
			!root.has("state") || !root.get("state").isJsonObject())
		{
			throw new IllegalArgumentException("Choose a progress JSON exported by the existing guide or this plugin");
		}
		if (!root.has("version") || GuideState.integer(root.get("version"), 1, 2) < 1)
		{
			throw new IllegalArgumentException("Unsupported backup version");
		}
		if (root.has("nativeAccountKey") && !targetKey.equals(root.get("nativeAccountKey").getAsString()))
		{
			throw new IllegalArgumentException(
				"This native backup is bound to a different account. It was not imported.");
		}
		GuideState state = new GuideState(root.getAsJsonObject("state"));
		boolean associationNeeded = !root.has("nativeAccountKey") && !state.name().equalsIgnoreCase(targetName);
		return new Preview(targetKey, targetName, state, digest(json), json, progress.total(state), associationNeeded);
	}
	public GuideState merge(Preview preview, GuideState current, String currentKey, boolean associationConfirmed,
							Instant now)
	{
		if (!preview.targetKey.equals(currentKey))
		{
			throw new IllegalArgumentException(
				"Account changed after the preview. Preview again on the intended account.");
		}
		if (preview.associationNeeded && !associationConfirmed)
		{
			throw new IllegalArgumentException("Confirm that this legacy backup belongs to the current account");
		}
		if (current.object("importReceipts").has(preview.hash))
		{
			return current.copy();
		}
		GuideState next = preview.incoming.copy();
		Set<Integer> completed = progress.completed(current);
		completed.addAll(progress.completed(next));
		int floor = Math.max(progress.total(current), progress.total(next));
		// All pre-import completions are included once in the new starting floor.
		next.data.addProperty("baseTotal", floor);
		next.ids("baseIDs", completed);
		next.ids("newIDs", Collections.emptySet());
		Set<Integer> verified = current.ids("syncIDs");
		verified.addAll(next.ids("syncIDs"));
		next.ids("syncIDs", verified);
		Set<Integer> anchors = current.ids("syncAnchorIDs");
		anchors.addAll(next.ids("syncAnchorIDs"));
		anchors.addAll(completed);
		next.ids("syncAnchorIDs", anchors);
		Set<Integer> observed = current.ids("syncObservedIDs");
		observed.addAll(next.ids("syncObservedIDs"));
		next.ids("syncObservedIDs", observed);
		Set<Integer> overlap = current.ids("syncOverlapIDs");
		overlap.addAll(next.ids("syncOverlapIDs"));
		next.ids("syncOverlapIDs", overlap);
		for (String field : new String[] {"levels", "quests", "items", "kc", "manualCompletedAt"})
		{
			JsonObject merged = current.object(field).deepCopy();
			for (Map.Entry<String, JsonElement> e : next.object(field).entrySet())
			{
				String stamp = field + ":" + e.getKey();
				String oldAt = ReadinessEngine.str(current.object("correctionDates"), stamp, ""),
						newAt = ReadinessEngine.str(next.object("correctionDates"), stamp, "");
				if (!merged.has(e.getKey()) || !later(oldAt, newAt))
				{
					merged.add(e.getKey(), e.getValue().deepCopy());
				}
			}
			next.data.add(field, merged);
		}
		JsonObject corrections = current.object("overrides").deepCopy();
		for (Map.Entry<String, JsonElement> e : next.object("overrides").entrySet())
		{
			JsonElement old = corrections.get(e.getKey()), incoming = e.getValue();
			String oldAt =
				old != null && old.isJsonObject() ? ReadinessEngine.str(old.getAsJsonObject(), "at", "") : "";
			String newAt = incoming.isJsonObject() ? ReadinessEngine.str(incoming.getAsJsonObject(), "at", "") : "";
			if (old == null || !later(oldAt, newAt))
			{
				corrections.add(e.getKey(), incoming.deepCopy());
			}
		}
		next.data.add("overrides", corrections);
		Set<String> skipped = new LinkedHashSet<>();
		for (GuideState s : Arrays.asList(current, next))
		{
			if (s.data.has("skip"))
			{
				for (JsonElement e : s.data.getAsJsonArray("skip"))
				{
					skipped.add(e.getAsString());
				}
			}
		}
		JsonArray skips = new JsonArray();
		skipped.forEach(skips::add);
		next.data.add("skip", skips);
		Set<Integer> taskSkips = current.ids("skipTaskIDs");
		taskSkips.addAll(next.ids("skipTaskIDs"));
		next.ids("skipTaskIDs", taskSkips);
		JsonObject dates = current.object("correctionDates").deepCopy();
		for (Map.Entry<String, JsonElement> entry : next.object("correctionDates").entrySet())
		{
			if (!later(ReadinessEngine.str(dates, entry.getKey(), ""), entry.getValue().getAsString()))
			{
				dates.add(entry.getKey(), entry.getValue().deepCopy());
			}
		}
		next.data.add("correctionDates", dates);
		// Retain extension fields and every receipt; originals are also archived unchanged.
		for (Map.Entry<String, JsonElement> e : current.data.entrySet())
		{
			if (!next.data.has(e.getKey()))
			{
				next.data.add(e.getKey(), e.getValue().deepCopy());
			}
		}
		JsonObject receipts = current.object("importReceipts").deepCopy();
		for (Map.Entry<String, JsonElement> e : next.object("importReceipts").entrySet())
		{
			receipts.add(e.getKey(), e.getValue().deepCopy());
		}
		receipts.addProperty(preview.hash, now.toString());
		next.data.add("importReceipts", receipts);
		next.data.addProperty("username", preview.targetName);
		next.data.addProperty("importedAt", now.toString());
		return next;
	}
	public static String digest(String text)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte b : hash)
			{
				out.append(String.format(Locale.ROOT, "%02x", b & 255));
			}
			return out.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}
	private static boolean later(String oldAt, String newAt)
	{
		if (oldAt.isEmpty())
		{
			return false;
		}
		if (newAt.isEmpty())
		{
			return true;
		}
		try
		{
			return Instant.parse(oldAt).isAfter(Instant.parse(newAt));
		}
		catch (java.time.format.DateTimeParseException ex)
		{
			return oldAt.compareTo(newAt) > 0;
		}
	}
	public static final class Preview
	{
		public final String targetKey, targetName, hash, original;
		public final GuideState incoming;
		public final int points;
		public final boolean associationNeeded;
		Preview(String key, String name, GuideState incoming, String hash, String original, int points, boolean needed)
		{
			targetKey = key;
			targetName = name;
			this.incoming = incoming;
			this.hash = hash;
			this.original = original;
			this.points = points;
			associationNeeded = needed;
		}
	}
}
