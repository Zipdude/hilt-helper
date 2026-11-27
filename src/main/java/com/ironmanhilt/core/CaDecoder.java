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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Cache IDs come from struct parameter 1306, never from display or list order. */
public final class CaDecoder
{
	public static final int COMPLETION_WORDS = 21;
	private final GuideCatalog catalogue;
	private final int[] knownMasks = new int[COMPLETION_WORDS];
	public CaDecoder(GuideCatalog catalogue)
	{
		this.catalogue = catalogue;
		for (int id : catalogue.tasks.keySet())
		{
			knownMasks[id / Integer.SIZE] |= 1 << (id % Integer.SIZE);
		}
	}
	public Result decode(List<GuideCatalog.Task> liveTasks, int[] words, int mediumThreshold, int hardThreshold, int gamePoints)
	{
		return decode(liveTasks, words, mediumThreshold, hardThreshold, gamePoints, false);
	}

	public Result decode(List<GuideCatalog.Task> liveTasks, int[] words, int mediumThreshold, int hardThreshold,
		int gamePoints, boolean groupIronman)
	{
		if (mediumThreshold <= 0 || hardThreshold <= 0)
		{
			return Result.unavailable("Waiting for server achievement variables");
		}
		boolean standard = mediumThreshold == catalogue.threshold && hardThreshold == catalogue.hardThreshold;
		boolean possibleGroupTarget = groupIronman && mediumThreshold <= catalogue.threshold &&
			hardThreshold <= catalogue.hardThreshold && hardThreshold > mediumThreshold;
		if (!standard && !possibleGroupTarget)
		{
			return Result.unavailable(
				"The game's reward threshold changed. Catalogue update required; saved progress retained.");
		}
		if (words == null || words.length != COMPLETION_WORDS)
		{
			return Result.unavailable("Achievement bit blocks are unavailable");
		}
		if (liveTasks == null || liveTasks.size() != catalogue.tasks.size())
		{
			return Result.unavailable("Live task catalogue changed or is unavailable; saved progress retained.");
		}
		for (int i = 0; i < words.length; i++)
		{
			if ((words[i] & ~knownMasks[i]) != 0)
			{
				return Result.unavailable("A task flag is outside the known catalogue; saved progress retained.");
			}
		}
		Set<Integer> observed = new LinkedHashSet<>(), positive = new LinkedHashSet<>();
		boolean groupRulesVerified = true;
		for (GuideCatalog.Task task : liveTasks)
		{
			if (task == null || task.name == null || task.tier == null)
			{
				return Result.unavailable("Invalid task metadata; saved progress retained.");
			}
			GuideCatalog.Task expected = catalogue.tasks.get(task.id);
			if (expected == null || !observed.add(task.id) || !expected.tier.equals(task.tier) ||
				!normal(expected.name).equals(normal(task.name)) || expected.points != task.points)
			{
				return Result.unavailable("Live task ID/name/tier validation failed; saved progress retained.");
			}
			groupRulesVerified &= expected.gimExclusion == task.gimExclusion;
			if ((words[task.id / Integer.SIZE] & (1 << (task.id % Integer.SIZE))) != 0)
			{
				positive.add(task.id);
			}
		}
		if (!observed.equals(catalogue.tasks.keySet()))
		{
			return Result.unavailable("Incomplete task-ID coverage");
		}
		int decodedPoints = catalogue.points(positive);
		if (gamePoints < 0 || gamePoints != decodedPoints)
		{
			return Result.unavailable(
				"Waiting for matching server points and completion flags; saved progress retained.");
		}
		return new Result(standard, groupRulesVerified, positive, observed, decodedPoints,
			observed.size() + " / " + catalogue.tasks.size() + " task IDs validated");
	}
	private static String normal(String s)
	{
		return s.replace('\u2019', '\'').replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
	}
	public static final class Result
	{
		public final boolean valid, groupRulesVerified;
		public final Set<Integer> completed, observed;
		public final int gamePoints;
		public final String message;
		private Result(boolean valid, boolean groupRulesVerified, Set<Integer> completed, Set<Integer> observed,
			int points, String message)
		{
			this.valid = valid;
			this.groupRulesVerified = groupRulesVerified;
			this.completed = Collections.unmodifiableSet(new LinkedHashSet<>(completed));
			this.observed = Collections.unmodifiableSet(new LinkedHashSet<>(observed));
			this.gamePoints = points;
			this.message = message;
		}
		public boolean verifiedFor(RewardGoal goal)
		{
			// A plausible group threshold does not alter the legacy guide guards.
			return valid || goal.supportsRaidLight() && gamePoints >= 0;
		}

		public static Result unavailable(String reason)
		{
			return new Result(false, false, Collections.emptySet(), Collections.emptySet(), -1, reason);
		}
	}
}
