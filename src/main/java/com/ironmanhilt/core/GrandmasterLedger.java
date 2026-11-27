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
import java.util.Set;

/** The final reward is a ledger of IDs. A route changes their order and availability. */
public final class GrandmasterLedger
{
	public final Set<Integer> required, exempt, unresolved;
	public final int target, reportedTarget, observedPoints, requiredPoints;
	public final boolean gameVerified, targetVerified;
	private final LiveSnapshot live;

	GrandmasterLedger(GuideCatalog catalogue, LiveSnapshot live, Set<Integer> completed, boolean gameVerified)
	{
		this.live = live;
		this.gameVerified = gameVerified;
		reportedTarget = value("caGrandmasterThreshold");
		observedPoints = gameVerified ? live.ca.gamePoints : value("caServerPoints");
		boolean group = live != null && live.mode.groupIronman();
		int roster = value("caGroupSize");
		boolean knownRoster = roster >= 2 && roster <= 5 && value("caGroupMember") == 1;
		Set<Integer> candidates = new LinkedHashSet<>();
		if (group)
		{
			for (GuideCatalog.Task task : catalogue.tasks.values())
			{
				if (task.gimExclusion > 0 && (!knownRoster || roster <= task.gimExclusion))
				{
					candidates.add(task.id);
				}
			}
		}
		int expected = catalogue.grandmasterThreshold - catalogue.points(candidates);
		targetVerified = gameVerified && reportedTarget == expected && (!group ||
			knownRoster && live.ca.groupRulesVerified && value("caRewardsObserved") == 1);
		target = targetVerified ? reportedTarget : catalogue.grandmasterThreshold;
		exempt = group && targetVerified ? Collections.unmodifiableSet(candidates) : Set.of();
		Set<Integer> outstanding = new LinkedHashSet<>(catalogue.tasks.keySet());
		outstanding.removeAll(completed);
		outstanding.removeAll(exempt);
		if (group && !targetVerified)
		{
			candidates.retainAll(outstanding);
			unresolved = Collections.unmodifiableSet(candidates);
			outstanding.removeAll(unresolved);
		}
		else
		{
			unresolved = Set.of();
		}
		required = Collections.unmodifiableSet(outstanding);
		requiredPoints = catalogue.points(required);
	}

	private int value(String key)
	{
		return live == null ? 0 : live.context.getOrDefault(key, 0);
	}

	public String evidence()
	{
		if (!gameVerified)
		{
			if (observedPoints > 0)
			{
				return "Game reports " + observedPoints + " points · verify your task flags. Showing saved progress.";
			}
			return "Saved progress · verify your game tasks on a standard world.";
		}
		if (!targetVerified)
		{
			return reportedTarget > 0 ? "Game target " + reportedTarget + " · verify in Combat Achievements → Rewards." :
				"Open Combat Achievements → Rewards to check your personal target.";
		}
		return pointsAtTargetWithOpenTasks() ?
			"Game points reached the target. Check the unfinished tasks in Combat Achievements → Rewards." : "";
	}

	boolean pointsAtTargetWithOpenTasks()
	{
		return targetVerified && observedPoints >= target && !required.isEmpty();
	}

	boolean canCheckReward()
	{
		return targetVerified && value("membersWorld") == 1 && value("caRewardsObserved") == 1 &&
			required.isEmpty() && unresolved.isEmpty() && live.ca.gamePoints >= target;
	}

	boolean rewardAvailable()
	{
		// This is the state the build-243 Rewards client uses for uncollected tier rewards.
		return canCheckReward() && value("caGrandmasterStatus") == 1;
	}

	boolean rewardCollected()
	{
		return canCheckReward() && value("caGrandmasterStatus") == 2;
	}
}
