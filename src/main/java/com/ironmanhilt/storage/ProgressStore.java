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
package com.ironmanhilt.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ironmanhilt.core.GuideState;
import com.ironmanhilt.core.LiveSnapshot;
import com.ironmanhilt.core.Migration;
import java.io.IOException;

/** Checksummed local envelopes; a bad primary never silently becomes a new profile. */
public final class ProgressStore
{
	public interface Files
	{
		String read(String name) throws IOException;
		void write(String name, String text, boolean keepPrevious) throws IOException;
	}
	private final Gson gson;
	private final Files files;
	public ProgressStore(Gson gson, Files files)
	{
		this.gson = gson;
		this.files = files;
	}
	public Loaded load(String key) throws IOException
	{
		requireKey(key);
		String primary = files.read(key + ".json");
		if (primary == null)
		{
			String previous = files.read(key + ".json.previous");
			if (previous == null)
			{
				return new Loaded(GuideState.empty(), null, "", false);
			}
			try
			{
				return decode(previous, key, "Recovered the previous save after an interrupted write.", true);
			}
			catch (RuntimeException ex)
			{
				throw new IOException("A previous save exists but needs recovery. No blank profile was written.", ex);
			}
		}
		try
		{
			return decode(primary, key, "", false);
		}
		catch (RuntimeException ex)
		{
			String previous = files.read(key + ".json.previous");
			if (previous == null)
			{
				throw new IOException(
					"Saved progress could not be read. Import a backup; the original file was retained.", ex);
			}
			try
			{
				return decode(previous, key,
								"Recovered the previous valid save. The unreadable primary will be retained separately.",
								true);
			}
			catch (RuntimeException second)
			{
				throw new IOException("Both local saves need recovery. No progress was overwritten.", second);
			}
		}
	}
	public void save(String key, String name, GuideState state, LiveSnapshot.Container bank, boolean recovered)
		throws IOException
	{
		requireKey(key);
		String existing = files.read(key + ".json");
		boolean validPrevious = existing != null;
		if (existing != null)
		{
			try
			{
				decode(existing, key, "", false);
			}
			catch (RuntimeException ex)
			{
				validPrevious = false;
				files.write(key + ".unreadable-" + Migration.digest(existing).substring(0, 12) + ".json", existing,
							false);
			}
		}
		files.write(key + ".json", envelope(key, name, state, bank), validPrevious);
	}
	public void archiveImport(String key, String name, GuideState before, LiveSnapshot.Container bank,
								Migration.Preview preview) throws IOException
	{
		requireKey(key);
		files.write(key + ".before-import.json", envelope(key, name, before, bank), false);
		String source = key + ".import-" + preview.hash + ".json";
		if (files.read(source) == null)
		{
			files.write(source, preview.original, false);
		}
	}
	public Loaded beforeImport(String key) throws IOException
	{
		String text = files.read(key + ".before-import.json");
		if (text == null)
		{
			throw new IOException("No import rollback exists for this account");
		}
		try
		{
			return decode(text, key, "Restored the saved state from before the last import", false);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Import rollback could not be read; current progress retained", e);
		}
	}
	public void archiveReconciliation(String key, GuideState state) throws IOException
	{
		requireKey(key);
		String text = export(key, state);
		files.write(key + ".before-reconcile-" + Migration.digest(gson.toJson(state.data)).substring(0, 12) + ".json",
					text, false);
	}
	public String export(String key, GuideState state)
	{
		JsonObject root = new JsonObject();
		root.addProperty("guide", "osrs-hilt2-field-guide");
		root.addProperty("version", 2);
		root.addProperty("nativeAccountKey", key);
		root.addProperty("exportedAt", java.time.Instant.now().toString());
		root.add("state", state.data.deepCopy());
		return gson.toJson(root);
	}
	private String envelope(String key, String name, GuideState state, LiveSnapshot.Container bank)
	{
		JsonObject body = new JsonObject();
		body.addProperty("accountKey", key);
		body.addProperty("name", name);
		body.add("state", state.data.deepCopy());
		if (bank != null)
		{
			JsonObject saved = new JsonObject();
			saved.addProperty("session", bank.session);
			saved.addProperty("at", bank.at.toString());
			JsonArray rows = new JsonArray();
			for (LiveSnapshot.Item item : bank.items)
			{
				JsonObject row = new JsonObject();
				row.addProperty("id", item.id);
				row.addProperty("quantity", item.quantity);
				rows.add(row);
			}
			saved.add("items", rows);
			body.add("lastBank", saved);
		}
		String payload = gson.toJson(body);
		JsonObject wrapper = new JsonObject();
		wrapper.addProperty("schema", 1);
		wrapper.addProperty("sha256", Migration.digest(payload));
		wrapper.add("payload", body);
		return gson.toJson(wrapper);
	}
	private Loaded decode(String text, String key, String warning, boolean recovered)
	{
		if (text.length() > 10_000_000)
		{
			throw new IllegalArgumentException("Save exceeds supported size");
		}
		JsonObject root = gson.fromJson(text, JsonObject.class);
		if (root == null || GuideState.integer(root.get("schema"), 1, 1) != 1 || !root.has("payload"))
		{
			throw new IllegalArgumentException("Invalid save envelope");
		}
		JsonObject body = root.getAsJsonObject("payload");
		if (!key.equals(body.get("accountKey").getAsString()) ||
			!Migration.digest(gson.toJson(body)).equals(root.get("sha256").getAsString()))
		{
			throw new IllegalArgumentException("Save identity or checksum mismatch");
		}
		GuideState state = new GuideState(body.getAsJsonObject("state"));
		LiveSnapshot.Container bank = null;
		if (body.has("lastBank"))
		{
			JsonObject saved = body.getAsJsonObject("lastBank");
			java.util.List<LiveSnapshot.Item> items = new java.util.ArrayList<>();
			for (JsonElement e : saved.getAsJsonArray("items"))
			{
				JsonObject row = e.getAsJsonObject();
				items.add(new LiveSnapshot.Item(GuideState.integer(row.get("id"), 0, 1000000),
												GuideState.integer(row.get("quantity"), 0, Integer.MAX_VALUE)));
			}
			bank = new LiveSnapshot.Container(saved.get("session").getAsString(),
												java.time.Instant.parse(saved.get("at").getAsString()), items);
		}
		return new Loaded(state, bank, warning, recovered);
	}
	public static void requireKey(String key)
	{
		if (key == null || !key.matches("[0-9]{1,20}-standard"))
		{
			throw new IllegalArgumentException("Invalid account identity");
		}
	}
	public static final class Loaded
	{
		public final GuideState state;
		public final LiveSnapshot.Container bank;
		public final String warning;
		public final boolean recovered;
		Loaded(GuideState state, LiveSnapshot.Container bank, String warning, boolean recovered)
		{
			this.state = state;
			this.bank = bank;
			this.warning = warning;
			this.recovered = recovered;
		}
	}
}
