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

public enum RewardGoal
{
	MEDIUM("medium", "Medium", "Ghommal’s hilt 2", "https://oldschool.runescape.wiki/w/Ghommal%27s_hilt_2"),
	HARD("hard", "Hard", "Ghommal’s hilt 3", "https://oldschool.runescape.wiki/w/Ghommal%27s_hilt_3"),
	ELITE("elite", "Elite", "Ghommal’s hilt 4", "https://oldschool.runescape.wiki/w/Ghommal%27s_hilt_4");

	public final String key, label, reward, wiki;

	RewardGoal(String key, String label, String reward, String wiki)
	{
		this.key = key;
		this.label = label;
		this.reward = reward;
		this.wiki = wiki;
	}

	public static RewardGoal from(String key)
	{
		for (RewardGoal value : values())
		{
			if (value.key.equals(key))
			{
				return value;
			}
		}
		return null;
	}
}
