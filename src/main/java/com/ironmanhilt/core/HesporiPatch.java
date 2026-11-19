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

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Local patch observations and a conservative estimate between visits. */
public final class HesporiPatch
{
	public enum Status
	{
		UNKNOWN("Visit the Hespori cave to read your patch."),
		EMPTY("Last seen empty. Plant a Hespori seed to start another attempt."),
		GROWING("Last seen growing. Return after the next growth cycles."),
		CHECK("Hespori may be grown. Check the cave before planning a kill."),
		GROWN("Full growth observed. Check the cave to fight Hespori or clear and replant a defeated crop.");

		public final String detail;

		Status(String detail)
		{
			this.detail = detail;
		}

		public boolean canNotify()
		{
			return this == EMPTY || this == CHECK || this == GROWN;
		}
	}

	private static final int GROWTH_MINUTES = 640;

	private HesporiPatch()
	{
	}

	public static boolean enabled(GuideState state)
	{
		return state.data.has("hesporiReminder") && state.data.get("hesporiReminder").getAsBoolean();
	}

	public static Status status(GuideState state, Instant now)
	{
		JsonObject observation = state.data.getAsJsonObject("hesporiObservation");
		if (observation == null)
		{
			return Status.UNKNOWN;
		}
		int value = observation.get("value").getAsInt();
		Status stage = stage(value);
		if (stage == Status.GROWING)
		{
			Instant seen = Instant.parse(observation.get("at").getAsString());
			// An observation can be made at any point within the current growth stage.
			if (!now.isBefore(seen.plus((7L - value) * GROWTH_MINUTES, ChronoUnit.MINUTES)))
			{
				return Status.CHECK;
			}
		}
		return stage;
	}

	public static boolean observe(GuideState state, int value, Instant now)
	{
		Status next = stage(value);
		if (next == Status.UNKNOWN)
		{
			return false;
		}
		JsonObject prior = state.data.getAsJsonObject("hesporiObservation");
		if (prior != null)
		{
			int old = prior.get("value").getAsInt();
			if (old == value && status(state, now) != Status.CHECK)
			{
				return false;
			}
			if (stage(old) != next || next == Status.GROWING && value < old)
			{
				state.data.remove("hesporiNotice");
			}
		}
		JsonObject observed = new JsonObject();
		observed.addProperty("value", value);
		observed.addProperty("at", now.toString());
		state.data.add("hesporiObservation", observed);
		return true;
	}

	private static Status stage(int value)
	{
		if (value >= 0 && value <= 3 || value == 9)
		{
			return Status.EMPTY;
		}
		if (value >= 4 && value <= 6)
		{
			return Status.GROWING;
		}
		if (value == 7 || value == 8)
		{
			return Status.GROWN;
		}
		return Status.UNKNOWN;
	}
}
