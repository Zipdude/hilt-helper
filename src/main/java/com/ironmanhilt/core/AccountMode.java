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

public enum AccountMode
{
	IRONMAN("ironman", "Ironman"),
	HARDCORE("hardcore", "Hardcore Ironman"),
	ULTIMATE("ultimate", "Ultimate Ironman"),
	GROUP("group", "Group Ironman"),
	HARDCORE_GROUP("hardcore_group", "Hardcore Group Ironman"),
	UNRANKED_GROUP("group", "Unranked Group Ironman"),
	NORMAL("normal", "Main account"),
	UNKNOWN("", "Account type needs checking");

	public final String key, label;
	AccountMode(String key, String label)
	{
		this.key = key;
		this.label = label;
	}
	public boolean supported()
	{
		return this != UNKNOWN;
	}
	public boolean hardcore()
	{
		return this == HARDCORE || this == HARDCORE_GROUP;
	}
	public boolean bankAllowed()
	{
		return supported() && this != ULTIMATE;
	}
	public static AccountMode fromVarbit(int value)
	{
		switch (value)
		{
		case 0:
			return NORMAL;
		case 1:
			return IRONMAN;
		case 2:
			return ULTIMATE;
		case 3:
			return HARDCORE;
		case 4:
			return GROUP;
		case 5:
			return HARDCORE_GROUP;
		case 6:
			return UNRANKED_GROUP;
		default:
			return UNKNOWN;
		}
	}
}
