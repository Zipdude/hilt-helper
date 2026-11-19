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
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Objects;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/** A player-selected note from the guide. Rendering depends only on the note and font. */
public final class TaskOverlay extends OverlayPanel
{
	private static final int WIDTH = 224;
	private static final int PADDING = 7;
	private volatile Note active;
	private Note laidOut;
	private FontMetrics metrics;

	public TaskOverlay(Plugin plugin)
	{
		super(plugin);
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setResizable(false);
		setClearChildren(false);
		panelComponent.setBackgroundColor(new Color(26, 28, 32, 230));
		panelComponent.setBorder(new Rectangle(PADDING, 5, PADDING, 5));
		panelComponent.setGap(new Point(0, 1));
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
	}

	public void show(String title, String objective, String kit)
	{
		Note current = active;
		if (current == null || !current.title.equals(title) || !current.objective.equals(objective) ||
			!current.kit.equals(kit))
		{
			active = new Note(title, objective, kit);
		}
	}

	public void clear()
	{
		active = null;
	}

	@Override public Dimension render(Graphics2D graphics)
	{
		Note note = active;
		if (note == null)
		{
			if (laidOut != null)
			{
				panelComponent.getChildren().clear();
				laidOut = null;
			}
			return null;
		}
		FontMetrics font = graphics.getFontMetrics();
		if (note != laidOut || metrics == null || !Objects.equals(metrics.getFont(), font.getFont()))
		{
			metrics = font;
			laidOut = note;
			panelComponent.getChildren().clear();
			addLines(note.title, GuidePanel.GOLD, font);
			addLines(note.objective, GuidePanel.TEXT, font);
			addLines(note.kit, GuidePanel.MUTED, font);
		}
		return super.render(graphics);
	}

	private void addLines(String value, Color color, FontMetrics font)
	{
		StringBuilder line = new StringBuilder();
		for (String word : value.split("\\s+"))
		{
			if (line.length() > 0 && font.stringWidth(line + " " + word) > WIDTH - 2 * PADDING)
			{
				addLine(line.toString(), color);
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			addLine(line.toString(), color);
		}
	}

	private void addLine(String value, Color color)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(value).leftColor(color).build());
	}

	private static final class Note
	{
		private final String title, objective, kit;

		private Note(String title, String objective, String kit)
		{
			this.title = title;
			this.objective = objective;
			this.kit = kit;
		}
	}
}
