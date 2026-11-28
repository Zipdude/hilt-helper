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
package com.ironmanhilt.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bundled artwork is decoded once and shared by the checklist's cell renderers. */
final class ChecklistPortraits
{
	static final int SIZE = 28;
	private static final Color BACKDROP = new Color(54, 53, 51);
	private static final String RESOURCE = "/com/ironmanhilt/checklist-portraits";
	private static final Logger LOG = LoggerFactory.getLogger(ChecklistPortraits.class);
	static final ChecklistPortraits INSTANCE = load();

	private final Map<String, ImageIcon> activities;
	private final Map<String, ImageIcon> tasks;

	private ChecklistPortraits(Map<String, ImageIcon> activities, Map<String, ImageIcon> tasks)
	{
		this.activities = activities;
		this.tasks = tasks;
	}

	ImageIcon icon(String activity, int task)
	{
		return tasks.getOrDefault(Integer.toString(task), activities.get(activity));
	}

	private static ChecklistPortraits load()
	{
		try (InputStream index = ChecklistPortraits.class.getResourceAsStream(RESOURCE + ".json");
			InputStream art = ChecklistPortraits.class.getResourceAsStream(RESOURCE + ".png"))
		{
			if (index == null || art == null)
			{
				throw new IOException("Missing checklist portrait resources");
			}
			JsonObject data = new Gson().fromJson(new InputStreamReader(index, StandardCharsets.UTF_8), JsonObject.class);
			BufferedImage sheet = ImageIO.read(art);
			int columns = data.get("columns").getAsInt();
			JsonArray portraits = data.getAsJsonArray("portraits");
			Map<String, ImageIcon> icons = new HashMap<>();
			for (int i = 0; i < portraits.size(); i++)
			{
				JsonObject portrait = portraits.get(i).getAsJsonObject();
				BufferedImage tile = sheet.getSubimage(i % columns * SIZE, i / columns * SIZE, SIZE, SIZE);
				BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = image.createGraphics();
				graphics.setColor(BACKDROP);
				graphics.fillRoundRect(0, 0, SIZE, SIZE, 5, 5);
				graphics.drawImage(tile, 0, 0, null);
				graphics.dispose();
				icons.put(portrait.get("key").getAsString(), new ImageIcon(image, portrait.get("name").getAsString()));
			}
			return new ChecklistPortraits(map(data, "activities", icons), map(data, "taskOverrides", icons));
		}
		catch (IOException | RuntimeException ex)
		{
			LOG.warn("Could not load Hilt Helper checklist portraits", ex);
			return new ChecklistPortraits(Map.of(), Map.of());
		}
	}

	private static Map<String, ImageIcon> map(JsonObject data, String section, Map<String, ImageIcon> icons)
	{
		Map<String, ImageIcon> byKey = new HashMap<>();
		for (Map.Entry<String, JsonElement> row : data.getAsJsonObject(section).entrySet())
		{
			byKey.put(row.getKey(), icons.get(row.getValue().getAsString()));
		}
		return Map.copyOf(byKey);
	}
}
