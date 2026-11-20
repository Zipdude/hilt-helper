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

public final class RiskPolicy
{
	private RiskPolicy()
	{
	}
	public static boolean wilderness(String activity)
	{
		return activity.startsWith("wild-");
	}
	public static boolean groupHardcoreRisk(String activity, AccountMode mode)
	{
		return mode == AccountMode.HARDCORE_GROUP &&
				(activity.equals("chambers") || activity.equals("fightcaves") ||
					activity.equals("theatre") || activity.equals("toa"));
	}

	public static String advice(String activity, AccountMode mode)
	{
		if (wilderness(activity))
		{
			return "Wilderness PvP and item-loss risk. Enter only after explicit opt-in; verify teleport restrictions and your escape.";
		}
		if ("chambers".equals(activity))
		{
			if (mode == AccountMode.HARDCORE_GROUP)
			{
				return "Raid with your own group. A death here costs a group life.";
			}
			if (mode == AccountMode.HARDCORE)
			{
				return "Chambers deaths do not remove solo Hardcore status. Prioritize individual rooms while learning.";
			}
			if (mode == AccountMode.GROUP)
			{
				return "Raiding outside your group can remove group prestige. Check whether your objective needs a full clear.";
			}
			return "Room objectives can award before a full clear. Check your task and any dropped items before leaving.";
		}
		if ("theatre".equals(activity))
		{
			if (mode == AccountMode.HARDCORE_GROUP)
			{
				return "Raid with your own group. An Entry Mode death can cost a group life.";
			}
			if (mode == AccountMode.HARDCORE)
			{
				return "An Entry Mode death can end your Hardcore status.";
			}
			return "Choose Entry Mode. Check each task for its room or raid completion requirement.";
		}
		if ("fightcaves".equals(activity) && mode.hardcore())
		{
			return mode == AccountMode.HARDCORE_GROUP
				? "A Fight Cave death costs a Hardcore Group life."
				: "Inside the Fight Cave, a death does not end solo Hardcore status.";
		}
		if ("toa".equals(activity) && mode.hardcore())
		{
			return "Tombs of Amascut can cost Hardcore status or a group life. Review your mode and risk before entering.";
		}
		if ("wintertodt".equals(activity))
		{
			return "Warmth reaching zero causes death. Keep it restored; warmth replacing Hitpoints does not make this a safe death for Hardcore accounts.";
		}
		if ("tempoross".equals(activity))
		{
			return "Tempoross does not normally damage Hitpoints. Poison, venom and self-inflicted damage can still cause an unsafe death.";
		}
		if (mode == AccountMode.HARDCORE_GROUP)
		{
			return "An unsafe death costs a group life. Solo Hardcore safe-death exceptions cannot be assumed for this mode.";
		}
		if (mode == AccountMode.HARDCORE)
		{
			return "Unsafe death removes Hardcore status. Review the approach, supplies and escape before entering.";
		}
		if (mode == AccountMode.ULTIMATE)
		{
			return "Plan around your carried items. Check any stored supplies in game before relying on them.";
		}
		if (mode == AccountMode.GROUP || mode == AccountMode.UNRANKED_GROUP)
		{
			return "Withdraw shared supplies to check them against this task.";
		}
		return "Follow the exact task rule and bank or escape when needed. Supplies do not guarantee a safe kill.";
	}
	public static double penalty(String activity, AccountMode mode)
	{
		if (!mode.hardcore())
		{
			return 0;
		}
		if ("chambers".equals(activity))
		{
			return mode == AccountMode.HARDCORE_GROUP ? 180 : 0;
		}
		if ("theatre".equals(activity))
		{
			return 240;
		}
		if (wilderness(activity))
		{
			return 480;
		}
		if ("tempoross".equals(activity))
		{
			return 0;
		}
		return 15;
	}
}
