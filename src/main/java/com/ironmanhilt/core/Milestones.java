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
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** The live point targets and reward states for one account observation. */
public final class Milestones
{
	public enum Status
	{
		UPCOMING, CHECK, EARNED, READY, COLLECTED
	}

	public final RewardGoal next;
	public final int points, savedPoints;
	public final boolean progressVerified, members;
	public final GrandmasterLedger grandmaster;
	public final Map<RewardGoal, Reward> rewards;

	Milestones(GuideCatalog catalogue, GuideState saved, LiveSnapshot live, int savedPoints)
	{
		this.savedPoints = savedPoints;
		progressVerified = verified(live);
		members = live != null && live.context.getOrDefault("membersWorld", 0) == 1;
		Set<Integer> observed = progressVerified ? live.ca.completed : saved.knownIds("syncIDs", catalogue.tasks.keySet());
		points = progressVerified ? live.ca.gamePoints : catalogue.points(observed);
		boolean grandmasterVerified = progressVerified &&
			live.context.getOrDefault("caGameBuild", 0) == catalogue.grandmasterGameBuild;
		grandmaster = new GrandmasterLedger(catalogue, live, observed, grandmasterVerified);
		RewardGoal active = RewardGoal.GRANDMASTER;
		Map<RewardGoal, Reward> entries = new EnumMap<>(RewardGoal.class);
		for (RewardGoal goal : RewardGoal.values())
		{
			Reward reward = goal == RewardGoal.GRANDMASTER ? lastReward() : pointReward(catalogue, goal, live);
			entries.put(goal, reward);
			if (active == RewardGoal.GRANDMASTER && goal != active && points < reward.target)
			{
				active = goal;
			}
		}
		next = active;
		rewards = Collections.unmodifiableMap(entries);
	}

	public Reward target()
	{
		return rewards.get(next);
	}

	static boolean verified(LiveSnapshot live)
	{
		return live != null && live.mode.supported() && live.ca.gamePoints >= 0;
	}

	private Reward pointReward(GuideCatalog catalogue, RewardGoal goal, LiveSnapshot live)
	{
		int standard = catalogue.threshold(goal);
		String prefix = "ca" + goal.label;
		int reported = live == null ? 0 : live.context.getOrDefault(prefix + "Threshold", 0);
		boolean checked = progressVerified && live.context.getOrDefault(prefix + "RewardsObserved", 0) == 1;
		boolean reduced = progressVerified && live.mode.groupIronman() && checked && live.ca.groupRulesVerified &&
			live.context.getOrDefault("caGroupMember", 0) == 1 && live.context.getOrDefault("caGameBuild", 0) ==
			catalogue.grandmasterGameBuild && consistentGroupTarget(catalogue, goal, live, standard, reported);
		boolean exact = progressVerified && (reported == standard || reduced);
		int target = reduced ? reported : reported <= catalogue.grandmasterThreshold ?
			Math.max(standard, reported) : standard;
		Status status = !progressVerified ? Status.CHECK : points < target ? Status.UPCOMING : Status.EARNED;
		if (progressVerified && reported > 0 && !exact)
		{
			status = Status.CHECK;
		}
		int rewardState = live == null ? 0 : live.context.getOrDefault(prefix + "Status", 0);
		if (exact && checked && members && points >= target)
		{
			if (rewardState == 1)
			{
				status = Status.READY;
			}
			else if (rewardState == 2)
			{
				status = Status.COLLECTED;
			}
		}
		return new Reward(target, exact, status);
	}

	private static boolean consistentGroupTarget(GuideCatalog catalogue, RewardGoal goal, LiveSnapshot live,
		int standard, int reported)
	{
		int roster = live.context.getOrDefault("caGroupSize", 0);
		if (roster < 2 || roster > 5 || reported <= 0 || reported >= standard)
		{
			return false;
		}
		int excluded = catalogue.tasks.values().stream()
			.filter(t -> t.points <= goal.ordinal() + 1 && t.gimExclusion >= roster)
			.mapToInt(t -> t.points).sum();
		return standard - excluded == reported;
	}

	private Reward lastReward()
	{
		Status status = Status.CHECK;
		if (grandmaster.targetVerified)
		{
			status = grandmaster.rewardCollected() ? Status.COLLECTED : grandmaster.rewardAvailable() ? Status.READY :
				grandmaster.required.isEmpty() && grandmaster.unresolved.isEmpty() && points >= grandmaster.target ?
					Status.EARNED : Status.UPCOMING;
		}
		return new Reward(grandmaster.target, grandmaster.targetVerified, status);
	}

	public static final class Reward
	{
		public final int target;
		public final boolean verified;
		public final Status status;

		private Reward(int target, boolean verified, Status status)
		{
			this.target = target;
			this.verified = verified;
			this.status = status;
		}
	}
}
