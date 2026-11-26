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

import java.util.Map;
import java.util.Set;

final class UnlockChecks
{
	private static final Map<String, String> GWD_SKILLS = Map.of(
		"kree-arra", "Ranged", "general-graardor", "Strength",
		"commander-zilyana", "Agility", "k-ril-tsutsaroth", "Hitpoints");
	private static final Map<String, Integer> GWD_CA = Map.of(
		"kree-arra", 9, "general-graardor", 16, "commander-zilyana", 211, "k-ril-tsutsaroth", 332);
	private static final Map<String, String[]> WILDERNESS_BOSSES = Map.of(
		"wild-callisto", new String[] {"Callisto", "Artio", "callisto"},
		"wild-venenatis", new String[] {"Venenatis", "Spindel", "venenatis"},
		"wild-vetion", new String[] {"Vet’ion", "Calvar’ion", "vetion"});

	private UnlockChecks()
	{
	}

	static ReadinessEngine.Check check(String rule, String activity, String label, GuideState state, LiveSnapshot live)
	{
		switch (rule)
		{
			case "gwd":
				return godWars(activity, label, state, live);
			case "nex":
				return frozenDoor(state, live) ? ready(label, "The Ancient Prison is unlocked.") :
					nexAccess(label, state, live);
			case "wilderness-diary":
				return wilderness(activity, label, live);
			case "yama":
				if (killed(live, "yama") || ca(state.rewardGoal(), live, 599, 600, 601, 602, 604, 605, 606, 607))
				{
					return ready(label, "A Yama kill establishes access.");
				}
				Boolean kingdom = ReadinessEngine.quest("A Kingdom Divided", state, live);
				return Boolean.TRUE.equals(kingdom) ?
					ready(label, "First visit: speak to the Voice of Yama and complete the introductory contract.") :
					evidenceStatus(label, kingdom != null, "Complete A Kingdom Divided to reach Yama.");
			case "corrupted-gauntlet":
				return killUnlock(label, live, "Clear the normal Gauntlet once to open Corrupted Gauntlet.",
					"gauntlet", "corrupted-gauntlet");
			case "theatre-hard":
				return killUnlock(label, live, "Clear Normal Theatre of Blood once to open Hard Mode.",
					"theatre-normal", "theatre-hard");
			case "inferno":
				return positive(live, "infernoAccess") || killed(live, "tzkal-zuk") ?
					ready(label, "The Inferno entrance is unlocked.") :
					evidenceStatus(label, live.context.containsKey("infernoAccess"), "Give TzHaar-Ket-Keh a fire cape once.");
			case "jad-challenge":
				return killUnlock(label, live, "Clear the Inferno once to open this Jad challenge.", "tzkal-zuk");
			case "zulrah":
				if (killed(live, "zulrah"))
				{
					return ready(label, "A Zulrah kill establishes access.");
				}
				Boolean regicide = ReadinessEngine.quest("Regicide", state, live);
				return Boolean.TRUE.equals(regicide) ? ready(label, "Regicide is complete. Meet the priestess on your first trip.") :
					evidenceStatus(label, "NOT_STARTED".equals(live.quests.get("Regicide")),
						"Reach Port Tyras during Regicide and speak to Zul-Harcinqa.");
			case "guardian":
				if (positive(live, "guardianRoof") || killed(live, "guardian"))
				{
					return ready(label, "The Slayer Tower rooftop has been unlocked.");
				}
				return unknown(label, "Bring a brittle key to unlock the rooftop.");
			default:
				throw new IllegalArgumentException("Unknown access rule: " + rule);
		}
	}

	private static ReadinessEngine.Check godWars(String activity, String label, GuideState state, LiveSnapshot live)
	{
		if (frozenDoor(state, live) || killed(live, activity) || ca(state.rewardGoal(), live, GWD_CA.get(activity)))
		{
			return ready(label, "The boss stronghold is accessible from your account progress.");
		}
		String skill = GWD_SKILLS.get(activity);
		Integer level = ReadinessEngine.level(skill, state, live);
		if (level == null || level < 70)
		{
			return evidenceStatus(label, level != null, "Reach 70 " + skill + " for this stronghold.");
		}
		if (positive(live, "gwdRope") ||
			GWD_SKILLS.keySet().stream().anyMatch(boss -> killed(live, boss) || ca(state.rewardGoal(), live, GWD_CA.get(boss))))
		{
			return ready(label, "The God Wars approach is open and your stronghold level is met.");
		}
		int agility = ReadinessEngine.effectiveLevel("agility", "boostedAgility", state, live);
		int strength = ReadinessEngine.effectiveLevel("strength", "boostedStrength", state, live);
		if (Math.max(agility, strength) < 60)
		{
			return evidenceStatus(label, agility > 0 && strength > 0 && Math.max(agility, strength) < 55,
				"Reach 60 Strength or Agility for the entrance boulder. A boost works here.");
		}
		if (positive(live, "trollAccess") || Boolean.TRUE.equals(ReadinessEngine.quest("Troll Stronghold", state, live)))
		{
			return ready(label, "The Troll Stronghold approach and your stronghold level are met. Bring a rope on the first trip.");
		}
		int easy = live.context.getOrDefault("caEasyThreshold", 0);
		if (live.ca.verifiedFor(state.rewardGoal()) && easy > 0 && live.ca.gamePoints >= easy)
		{
			return ready(label, "Ghommal’s hilt can take you to the entrance. Claim it first if needed.");
		}
		String route = "Defeat Dad during Troll Stronghold or use Ghommal’s hilt to reach the entrance.";
		boolean unsupportedApproach = "NOT_STARTED".equals(live.quests.get("Troll Stronghold")) &&
			live.ca.verifiedFor(state.rewardGoal()) && easy > 0 && live.ca.gamePoints < easy;
		return evidenceStatus(label, unsupportedApproach, route);
	}

	private static ReadinessEngine.Check wilderness(String activity, String label, LiveSnapshot live)
	{
		String[] bosses = WILDERNESS_BOSSES.get(activity);
		if (positive(live, "wildHard") || positive(live, "wildElite") || positive(live, "bossSlayer:" + bosses[2]))
		{
			return ready(label, "You can use " + bosses[0] + " or " + bosses[1] + ".");
		}
		if (positive(live, "wildMedium"))
		{
			return ready(label, "Use " + bosses[0] + ". " + bosses[1] + " needs the Hard diary or a matching boss assignment.");
		}
		boolean observed = live.context.containsKey("wildMedium") && live.context.containsKey("wildHard") &&
			positive(live, "slayerObserved");
		// Old kill count predates diary rules, and a Slayer exception can expire.
		return evidenceStatus(label, observed,
			"Complete the Medium Wilderness Diary for " + bosses[0] + " or get its boss Slayer assignment.");
	}

	static ReadinessEngine.Check assignment(String label, Set<String> options, LiveSnapshot live)
	{
		for (String name : options)
		{
			if (positive(live, "slayer:" + name) || positive(live, "bossSlayer:" + name))
			{
				return ready(label, "An eligible current Slayer assignment was observed.");
			}
		}
		return evidenceStatus(label, positive(live, "slayerObserved"), "Get an eligible Slayer assignment before the attempt.");
	}

	private static boolean frozenDoor(GuideState state, LiveSnapshot live)
	{
		return Boolean.TRUE.equals(ReadinessEngine.quest("The Frozen Door", state, live)) ||
			killed(live, "nex") || ca(state.rewardGoal(), live, 410, 411);
	}

	private static ReadinessEngine.Check killUnlock(String label, LiveSnapshot live, String detail, String... bosses)
	{
		for (String boss : bosses)
		{
			if (killed(live, boss))
			{
				return ready(label, "Your game kill count confirms the unlock.");
			}
		}
		return unknown(label, detail);
	}

	private static ReadinessEngine.Check nexAccess(String label, GuideState state, LiveSnapshot live)
	{
		for (String skill : GWD_SKILLS.values())
		{
			Integer level = ReadinessEngine.level(skill, state, live);
			if (level != null && level < 70)
			{
				return evidenceStatus(label, true, "Reach 70 " + skill + " to collect the four Frozen Door key pieces.");
			}
		}
		return observedQuest("The Frozen Door", state, live, label, "Complete The Frozen Door to access the Ancient Prison.");
	}

	private static ReadinessEngine.Check observedQuest(String name, GuideState state, LiveSnapshot live, String label, String detail)
	{
		return evidenceStatus(label, ReadinessEngine.quest(name, state, live) != null, detail);
	}

	private static boolean ca(RewardGoal goal, LiveSnapshot live, int... ids)
	{
		if (live.ca.verifiedFor(goal))
		{
			for (int id : ids)
			{
				if (live.ca.completed.contains(id))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean killed(LiveSnapshot live, String key)
	{
		return positive(live, "kc:" + key);
	}

	private static boolean positive(LiveSnapshot live, String key)
	{
		return live.context.getOrDefault(key, 0) > 0;
	}

	private static ReadinessEngine.Check ready(String label, String detail)
	{
		return new ReadinessEngine.Check(label, ReadinessEngine.READY, detail, 1);
	}

	private static ReadinessEngine.Check unknown(String label, String detail)
	{
		return new ReadinessEngine.Check(label, ReadinessEngine.UNKNOWN, detail, 0);
	}

	private static ReadinessEngine.Check evidenceStatus(String label, boolean observed, String detail)
	{
		return new ReadinessEngine.Check(label, observed ? ReadinessEngine.MISSING : ReadinessEngine.UNKNOWN, detail, 0);
	}
}
