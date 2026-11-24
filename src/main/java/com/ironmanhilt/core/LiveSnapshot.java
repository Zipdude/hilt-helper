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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

public final class LiveSnapshot
{
	public final String accountKey, name, session;
	public final AccountMode mode;
	public final Instant at;
	public final Map<String, Integer> levels;
	public final Map<String, String> quests;
	public final Map<String, Container> containers;
	public final CaDecoder.Result ca;
	public final Map<String, Integer> context;
	public LiveSnapshot(String accountKey, String name, String session, AccountMode mode, Instant at,
						Map<String, Integer> levels, Map<String, String> quests, Map<String, Container> containers,
						CaDecoder.Result ca)
	{
		this(accountKey, name, session, mode, at, levels, quests, containers, ca, Collections.emptyMap());
	}
	public LiveSnapshot(String accountKey, String name, String session, AccountMode mode, Instant at,
						Map<String, Integer> levels, Map<String, String> quests, Map<String, Container> containers,
						CaDecoder.Result ca, Map<String, Integer> context)
	{
		this.accountKey = accountKey;
		this.name = name;
		this.session = session;
		this.mode = mode;
		this.at = at;
		this.levels = Collections.unmodifiableMap(new HashMap<>(levels));
		this.quests = Collections.unmodifiableMap(new HashMap<>(quests));
		this.containers = Collections.unmodifiableMap(new HashMap<>(containers));
		this.ca = ca;
		this.context = Collections.unmodifiableMap(new HashMap<>(context));
	}
	public boolean freeWorld()
	{
		return context.getOrDefault("membersWorld", 1) == 0;
	}

	public Container observedBank()
	{
		return mode.bankAllowed() ? containers.get("bank") : null;
	}

	public boolean bankObservedThisSession()
	{
		Container bank = observedBank();
		return bank != null && session.equals(bank.session);
	}

	Container observedItems(String location, Instant now)
	{
		if ("bank".equals(location))
		{
			return observedBank();
		}
		Container items = containers.get(location);
		return items != null && items.fresh(session, now) ? items : null;
	}

	boolean completeItemEvidence(Instant now)
	{
		Container inventory = observedItems("inventory", now), equipment = observedItems("equipment", now);
		return (!mode.bankAllowed() || observedBank() != null) && inventory != null && equipment != null &&
			Duration.between(inventory.at, equipment.at).abs().toMillis() <= 120000;
	}

	public LiveSnapshot withBank(Container bank)
	{
		if (!mode.bankAllowed() || bank == null || bank == containers.get("bank"))
		{
			return this;
		}
		Map<String, Container> updated = new HashMap<>(containers);
		updated.put("bank", bank);
		return new LiveSnapshot(accountKey, name, session, mode, at, levels, quests, updated, ca, context);
	}

	LiveSnapshot withoutHistoricalBank()
	{
		if (observedBank() == null || bankObservedThisSession())
		{
			return this;
		}
		Map<String, Container> updated = new HashMap<>(containers);
		updated.remove("bank");
		return new LiveSnapshot(accountKey, name, session, mode, at, levels, quests, updated, ca, context);
	}

	/** Compare the item evidence used by the guide, including carried-item freshness. */
	public boolean sameView(LiveSnapshot other, IntPredicate guideItem)
	{
		if (other == null || !accountKey.equals(other.accountKey) || !name.equals(other.name) ||
			!session.equals(other.session) || mode != other.mode || !levels.equals(other.levels) ||
			!quests.equals(other.quests) || !context.equals(other.context) || ca.valid != other.ca.valid ||
			ca.gamePoints != other.ca.gamePoints || !ca.message.equals(other.ca.message) ||
			!ca.completed.equals(other.ca.completed) || !ca.observed.equals(other.ca.observed) ||
			bankObservedThisSession() != other.bankObservedThisSession())
		{
			return false;
		}
		for (String location : List.of("bank", "inventory", "equipment"))
		{
			Container current = observedItems(location, at), previous = other.observedItems(location, other.at);
			if (current == previous)
			{
				continue;
			}
			if (current == null || previous == null)
			{
				return false;
			}
			if (!current.sameContents(previous) && ("equipment".equals(location) ||
				!current.guideItemQuantities(guideItem).equals(previous.guideItemQuantities(guideItem))))
			{
				return false;
			}
		}
		return completeItemEvidence(at) == other.completeItemEvidence(other.at);
	}
	public static final class Item
	{
		public final int id, quantity, slot;
		public Item(int id, int quantity)
		{
			this(id, quantity, -1);
		}
		public Item(int id, int quantity, int slot)
		{
			this.id = id;
			this.quantity = quantity;
			this.slot = slot;
		}
	}
	public static final class Container
	{
		public final String session;
		public final Instant at;
		public final List<Item> items;
		public Container(String session, Instant at, List<Item> items)
		{
			this.session = session;
			this.at = at;
			this.items = Collections.unmodifiableList(new ArrayList<>(items));
		}
		public boolean fresh(String currentSession, Instant now)
		{
			return session.equals(currentSession) && !at.isAfter(now.plusSeconds(5)) &&
				at.plusSeconds(1800).isAfter(now);
		}
		public boolean sameContents(Container other)
		{
			if (other == null || !session.equals(other.session) || items.size() != other.items.size())
			{
				return false;
			}
			for (int index = 0; index < items.size(); index++)
			{
				Item item = items.get(index), previous = other.items.get(index);
				if (item.id != previous.id || item.quantity != previous.quantity || item.slot != previous.slot)
				{
					return false;
				}
			}
			return true;
		}
		private Map<Integer, Long> guideItemQuantities(IntPredicate guideItem)
		{
			Map<Integer, Long> quantities = new HashMap<>();
			for (Item item : items)
			{
				if (guideItem.test(item.id))
				{
					quantities.merge(item.id, (long)item.quantity, Long::sum);
				}
			}
			return quantities;
		}
	}
}
