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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Point;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** The task and its objective, in a compact note that the player can move. */
public final class TaskOverlay extends Overlay
{
	private static final int MAX_WIDTH = 168;
	private static final int PADDING = 4;
	private static final int TOP = 3;
	private static final Color BACKGROUND = new Color(42, 32, 22, 142);
	private static final Color TITLE = new Color(250, 222, 156);
	private static final Color OBJECTIVE = new Color(250, 246, 229);
	private volatile Note active;
	private Note laidOut;
	private List<String> heading = List.of(), objective = List.of();
	private Dimension size;

	public TaskOverlay(Plugin plugin)
	{
		super(plugin);
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setResizable(false);
	}

	public void show(String title, String objective)
	{
		Note current = active;
		if (current == null || !current.title.equals(title) || !current.objective.equals(objective))
		{
			active = new Note(title, objective);
		}
	}

	public void clear()
	{
		active = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Note note = active;
		if (note == null)
		{
			return null;
		}

		Graphics2D view = (Graphics2D) graphics.create();
		try
		{
			view.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics font = view.getFontMetrics();
			if (note != laidOut)
			{
				layout(note, font);
			}
			// Measure before drawing so the first frame of a changed task uses the correct bounds.
			view.setColor(BACKGROUND);
			view.fillRect(0, 0, size.width, size.height);
			int y = TOP + font.getAscent();
			y = paintLines(view, heading, TITLE, y, font.getHeight());
			paintLines(view, objective, OBJECTIVE, y, font.getHeight());
			return new Dimension(size);
		}
		finally
		{
			view.dispose();
		}
	}

	private void layout(Note note, FontMetrics font)
	{
		heading = wrap(note.title, font, 2);
		objective = wrap(note.objective, font, 4);
		int width = 0;
		for (String line : heading)
		{
			width = Math.max(width, font.stringWidth(line));
		}
		for (String line : objective)
		{
			width = Math.max(width, font.stringWidth(line));
		}
		size = new Dimension(width + 2 * PADDING + 2, (heading.size() + objective.size()) * font.getHeight() + 2 * TOP);
		laidOut = note;
	}

	private static int paintLines(Graphics2D view, List<String> lines, Color color, int y, int height)
	{
		for (String line : lines)
		{
			OverlayUtil.renderTextLocation(view, new Point(PADDING, y), line, color);
			y += height;
		}
		return y;
	}

	private static List<String> wrap(String text, FontMetrics font, int rowLimit)
	{
		int width = MAX_WIDTH - 2 * PADDING - 2;
		List<String> lines = new ArrayList<>();
		String rest = text.strip();
		while (!rest.isEmpty() && lines.size() < rowLimit)
		{
			String line = fit(rest, font, width);
			if (line.length() < rest.length() && !Character.isWhitespace(rest.charAt(line.length())))
			{
				int space = line.lastIndexOf(' ');
				if (space > 0)
				{
					line = line.substring(0, space);
				}
			}
			rest = rest.substring(line.length()).stripLeading();
			if (!rest.isEmpty() && lines.size() + 1 == rowLimit)
			{
				line = fit(line, font, width - font.stringWidth("...")).stripTrailing() + "...";
			}
			lines.add(line);
		}
		return lines;
	}

	private static String fit(String value, FontMetrics font, int width)
	{
		int end = 0;
		while (end < value.length())
		{
			int next = end + Character.charCount(value.codePointAt(end));
			if (end > 0 && font.stringWidth(value.substring(0, next)) > width)
			{
				break;
			}
			end = next;
		}
		return value.substring(0, end);
	}

	private static final class Note
	{
		private final String title, objective;

		private Note(String title, String objective)
		{
			this.title = title;
			this.objective = objective;
		}
	}
}
