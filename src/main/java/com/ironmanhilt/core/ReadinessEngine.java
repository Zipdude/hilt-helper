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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compare owned kit and carried supplies without double-counting moved quantities. */
public final class ReadinessEngine
{
	public static final String READY = "Ready", ALTERNATIVE = "Alternative available", UNKNOWN = "Needs checking",
								MISSING = "Missing", SHORT = "Short", MANUAL = "Manually confirmed",
								PROVIDED = "Collect there", UNUSED = "Not needed for selected tasks";
	private static final String AMMUNITION = "ammo_compatible_with_selected_weapon";
	public static final String FIRST_BANK_CHECK = "Open your bank once to check owned items.";
	private static final String REFRESH_BANK = "Open your bank to confirm the current total with your carried supplies.";
	private static final Map<String, String> SPELLBOOK_QUESTS = Map.of(
		"ancient", "Desert Treasure I", "lunar", "Lunar Diplomacy", "arceuus", "A Kingdom Divided");
	private static final Map<String, Integer> SPELLBOOKS = Map.of(
		"standard", 0, "ancient", 1, "lunar", 2, "arceuus", 3);
	private static final Map<Integer, Set<Integer>> COMBINATION_RUNES = Map.of(
		4694, Set.of(556, 555), 4695, Set.of(555, 557), 4696, Set.of(556, 557),
		4697, Set.of(556, 554), 4698, Set.of(555, 554), 4699, Set.of(557, 554),
		30843, Set.of(564, 566));
	private final GuideCatalog catalogue;
	private final Map<Integer, Set<Integer>> notedIds = new HashMap<>();
	private final Set<Integer> spellRawIds;
	private final Set<Integer> elementalStaffIds;
	private final Set<Integer> guideItemIds;
	private final Set<Integer> hardItemIds;
	private final Set<Integer> rangedWeaponIds;
	public ReadinessEngine(GuideCatalog c)
	{
		catalogue = c;
		for (Map.Entry<String, JsonElement> entry : c.items.getAsJsonObject("note_to_base").entrySet())
		{
			notedIds.computeIfAbsent(entry.getValue().getAsInt(), id -> new HashSet<>())
				.add(Integer.parseInt(entry.getKey()));
		}
		elementalStaffIds = new HashSet<>(ints(c.items.getAsJsonObject("groups")
			.getAsJsonArray("basic_elemental_staff")));
		Set<Integer> spellItems = new HashSet<>(elementalStaffIds);
		for (Map.Entry<String, JsonElement> spell : c.items.getAsJsonObject("spells").entrySet())
		{
			for (String id : spell.getValue().getAsJsonObject().getAsJsonObject("runes_per_cast").keySet())
			{
				spellItems.add(Integer.parseInt(id));
			}
		}
		spellItems.addAll(COMBINATION_RUNES.keySet());
		spellRawIds = withNotes(spellItems);
		Set<Integer> itemIds = new HashSet<>();
		for (String id : c.items.getAsJsonObject("items").keySet())
		{
			itemIds.add(Integer.parseInt(id));
		}
		guideItemIds = withNotes(itemIds);
		guideItemIds.addAll(spellRawIds);
		guideItemIds.add(HesporiPatch.SEED_ID);
		hardItemIds = withNotes(c.hardItemIds);
		Set<Integer> ranged = new HashSet<>();
		for (String id : c.items.getAsJsonObject("ammo_compatibility").keySet())
		{
			ranged.add(Integer.parseInt(id));
		}
		rangedWeaponIds = withNotes(ranged);
	}
	public boolean usesItem(int id, RewardGoal goal)
	{
		return guideItemIds.contains(id) && (goal != RewardGoal.MEDIUM || !hardItemIds.contains(id));
	}
	private Set<Integer> withNotes(Set<Integer> ids)
	{
		Set<Integer> raw = new HashSet<>(ids);
		for (int id : ids)
		{
			raw.addAll(notedIds.getOrDefault(id, Collections.emptySet()));
		}
		return raw;
	}
	public static String checkKey(String activity, int index, JsonObject req)
	{
		return activity + ":" + str(req, "check_key", String.valueOf(index));
	}

	public Check check(String activity, int index, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		return check(activity, index, req, state, live, now, null);
	}
	public Check check(String activity, int index, JsonObject req, GuideState state, LiveSnapshot live, Instant now,
						Collection<Integer> selected)
	{
		String label = str(req, "label", "Preparation"), role = str(req, "role", "");
		if (req.has("task_ids") && selected != null &&
			Collections.disjoint(ints(req.getAsJsonArray("task_ids")), selected))
		{
			return new Check(label, UNUSED, "Only needed for other task attempts.", 0);
		}
		if (role.equals("provided_on_site"))
		{
			return new Check(label, PROVIDED, str(req, "notes", "Pick up the tools at the activity."), 0);
		}
		if (taskSetup(req))
		{
			return new Check(label, "Check for this attempt", str(req, "notes", "Check your equipped gear."), 0);
		}
		if (!req.has("spell") && !req.has("spells_any"))
		{
			live = preparationEvidence(state, live);
		}
		Check automatic = req.has("resource_token") ?
			token(req.get("resource_token").getAsString(), state, live, now) : automatic(activity, req, state, live, now);
		if (!automatic.ready() && req.has("on_site"))
		{
			return new Check(label, PROVIDED, req.get("on_site").getAsString(), 0);
		}
		if (req.has("ge_option") && !automatic.ready() && live != null && live.mode == AccountMode.NORMAL)
		{
			JsonObject gear = item(req.get("ge_option").getAsInt());
			if (gear != null && gate(gear, req, state, live) == null)
			{
				return new Check(label, automatic.status,
					"Buy or withdraw " + gear.get("name").getAsString() + " before the attempt.", automatic.amount, false, true);
			}
		}
		if ((req.has("access_rule") || req.has("slayer_assignments")) && !UNKNOWN.equals(automatic.status))
		{
			return automatic;
		}
		JsonObject overrides = state.object("overrides");
		JsonElement entry = overrides.get(checkKey(activity, index, req));
		if (entry == null || !entry.isJsonObject())
		{
			return automatic;
		}
		JsonObject o = entry.getAsJsonObject();
		String status = str(o, "status", ""), date = str(o, "at", "date unavailable");
		if (status.equals("Missing"))
		{
			return new Check(label, MISSING, "Manual correction · " + date, 0);
		}
		if (status.equals("Not required") && !req.has("manualCheck"))
		{
			return new Check(label, MANUAL, "Manually marked not required · " + date, 0);
		}
		if (status.equals("Owned"))
		{
			if (eliteGroup(state, live) && (!wanted(req).isEmpty() || req.has("spell") || req.has("spells_any") ||
				AMMUNITION.equals(str(req, "predicate", ""))))
			{
				return automatic;
			}
			if (automatic.detail.startsWith("Needs ") || automatic.detail.startsWith("Unusable") ||
				automatic.detail.startsWith("Spellbook") || automatic.detail.startsWith("Complete "))
			{
				return new Check(label, UNKNOWN, "Saved manual ownership; " + automatic.detail, automatic.amount);
			}
			// Ownership cannot bypass an explicit, known wear/use gate.
			Set<Integer> wanted = wanted(req);
			boolean allBlocked = !wanted.isEmpty();
			for (int id : wanted)
			{
				JsonObject item = item(id);
				if (item != null && gate(item, req, state, live) == null)
				{
					allBlocked = false;
					break;
				}
			}
			if (allBlocked)
			{
				return new Check(label, UNKNOWN, "Saved manual ownership; usable requirements still need checking", 0);
			}
			String detail = req.has("manualCheck") ? "Manual confirmation · " : "Manual correction · ";
			return new Check(label, MANUAL, detail + date, -1);
		}
		return automatic;
	}

	public static boolean taskSetup(JsonObject req)
	{
		return yes(req, "task_setup");
	}

	private Check automatic(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = str(req, "label", "Preparation");
		if (live == null || !live.mode.supported())
		{
			return new Check(label, UNKNOWN, "Log in on a standard-world account", 0);
		}
		if (req.has("unlock_ids") && live.ca.valid &&
			!Collections.disjoint(ints(req.getAsJsonArray("unlock_ids")), live.ca.completed))
		{
			return new Check(label, READY, "A verified game completion establishes access.", 1);
		}
		if (req.has("access_rule"))
		{
			Check access = UnlockChecks.check(req.get("access_rule").getAsString(), activity, label, state, live);
			if (!access.ready() && req.has("access_item_ids"))
			{
				JsonObject key = new JsonObject();
				key.addProperty("label", label);
				key.add("any_of_ids", req.getAsJsonArray("access_item_ids"));
				Check owned = automatic(activity, key, state, live, now);
				if (owned.ready())
				{
					return new Check(label, ALTERNATIVE, "Bring your brittle key to unlock the rooftop.", 1, owned.fromBank);
				}
				if (MISSING.equals(owned.status))
				{
					return new Check(label, MISSING, access.detail, 0);
				}
			}
			return access;
		}
		if (req.has("slayer_assignments"))
		{
			Set<String> names = new HashSet<>();
			req.getAsJsonArray("slayer_assignments").forEach(name -> names.add(name.getAsString()));
			return UnlockChecks.assignment(label, names, live);
		}
		if ("muspah-drain".equals(str(req, "predicate", "")))
		{
			return prayerDrainSources(activity, req, state, live, now);
		}
		if (req.has("base_level"))
		{
			JsonArray base = req.getAsJsonArray("base_level");
			Integer level = live.levels.get(base.get(0).getAsString());
			if (level != null && level >= base.get(1).getAsInt())
			{
				return new Check(label, READY, "Base level meets this requirement.", level);
			}
		}
		if (req.has("effective_level"))
		{
			JsonArray gate = req.getAsJsonArray("effective_level");
			String skill = gate.get(0).getAsString();
			int required = gate.get(1).getAsInt();
			int lowestPossible = gate.get(2).getAsInt();
			int level = effectiveLevel(skill.toLowerCase(Locale.ROOT), "boosted" + skill, state, live);
			String detail = "Reach " + required + " " + skill + " or bring a boost that reaches it.";
			return new Check(label, level >= required ? READY : level > 0 && level < lowestPossible ? MISSING : UNKNOWN,
				level >= required ? "The effective level needed is available." : detail, level);
		}
		if (req.has("quest") && Boolean.TRUE.equals(quest(req.get("quest").getAsString(), state, live)))
		{
			return new Check(label, READY, "Quest completion establishes access.", 1);
		}
		if (req.has("spell") || req.has("spells_any"))
		{
			if (req.has("alt_item_ids"))
			{
				JsonObject other = new JsonObject();
				other.addProperty("label", label);
				other.add("any_of_ids", req.getAsJsonArray("alt_item_ids"));
				Check item = automatic(activity, other, state, live, now);
				if (item.ready())
				{
					return new Check(label, ALTERNATIVE, "Use the eligible weapon special to immobilise the boss.", 1, item.fromBank);
				}
			}
			return spells(activity, req, state, live, now);
		}
		if (str(req, "predicate", "").equals("teleport_usable_for_location") && RiskPolicy.wilderness(activity))
		{
			return new Check(label, UNKNOWN, "Confirm Wilderness level, teleblock and teleport restrictions in game.",
								0);
		}
		if (str(req, "predicate", "").equals(AMMUNITION))
		{
			return ammunition(activity, req, state, live, now);
		}
		Set<Integer> ids = wanted(req);
		if (ids.isEmpty())
		{
			if (str(req, "role", "").equals("required_access"))
			{
				return new Check(label, UNKNOWN, "Check this unlock in game.", 0);
			}
			if (str(req, "role", "").equals("attempt_access"))
			{
				return new Check(label, UNKNOWN, "Check your current attempt setup.", 0);
			}
			return new Check(label, UNKNOWN, "Confirm this strategy condition in game.", 0);
		}
		LiveSnapshot.Container bank = live.observedBank();
		Set<Integer> rawIds = bank != null && bank.items.size() > 64 ? withNotes(ids) : null;
		boolean distinct = yes(req, "distinct_equipment_slots");
		String unit = str(req, "unit", "items");
		boolean doses = unit.equals("doses");
		GearSlots ownedSlots = distinct ? new GearSlots() : null;
		GearSlots carriedSlots = distinct ? new GearSlots() : null;
		GearSlots wornSlots = distinct ? new GearSlots() : null;
		Map<Integer, Integer> carriedStock = new HashMap<>(), bankStock = new HashMap<>();
		boolean primary = false, blocked = false;
		String reason = "";
		for (String location : locations(live))
		{
			LiveSnapshot.Container c = live.observedItems(location, now);
			if (c == null)
			{
				continue;
			}
			boolean banked = location.equals("bank");
			Map<Integer, Integer> stock = banked ? bankStock : carriedStock;
			for (LiveSnapshot.Item row : c.items)
			{
				if (rawIds != null && !rawIds.contains(row.id))
				{
					continue;
				}
				int id = base(row.id);
				if (!ids.contains(id) || row.quantity <= 0)
				{
					continue;
				}
				JsonObject item = item(id);
				if (item == null)
				{
					blocked = true;
					continue;
				}
				if (id != row.id && !banked)
				{
					blocked = true;
					reason = "Noted inventory items are not usable supplies";
					continue;
				}
				String gate = gate(item, req, state, live);
				if (gate != null)
				{
					blocked = true;
					reason = gate;
					continue;
				}
				int multiplier = doses ? integer(item, "doses_per_item", 0) : 1;
				if (distinct)
				{
					ownedSlots.add(item);
					if (!banked)
					{
						carriedSlots.add(item);
					}
					if (location.equals("equipment"))
					{
						wornSlots.add(item);
					}
				}
				else
				{
					int amount = (int)Math.min(Integer.MAX_VALUE, (long)row.quantity * multiplier);
					stock.merge(id, amount, ReadinessEngine::addQuantity);
				}
				if (!req.has("primary_ids") || ints(req.getAsJsonArray("primary_ids")).contains(id))
				{
					primary = true;
				}
			}
		}
		int needed = integer(req, "minimum", 1);
		boolean currentBank = live.bankObservedThisSession();
		int best, carried;
		String quantity;
		if (distinct)
		{
			best = ownedSlots.count();
			carried = carriedSlots.count();
			quantity = best + " / " + needed + " equipment slots";
			if (best >= needed)
			{
				boolean fromBank = carried < needed;
				String action = wornSlots.count() >= needed ? "Required pieces are equipped."
					: fromBank ? "Withdraw the remaining pieces and equip the outfit for this task."
						: "Equip the matching pieces for this task.";
				return new Check(label, READY, quantity + " available. " + action, best, fromBank);
			}
		}
		else
		{
			carried = total(carriedStock);
			// A potion from a saved bank scan may now be carried as a different dose variant.
			best = doses && !currentBank ? Math.max(carried, total(bankStock)) :
				total(combinedStock(carriedStock, bankStock, currentBank));
			quantity = best + " / " + needed + " " + unit;
		}
		if (best >= needed)
		{
			boolean fromBank = carried < needed;
			String detail = fromBank ? quantity + (carried == 0 ? " in your last bank scan." : " across your bank and carried supplies.")
				+ " Withdraw before attempting."
				: carried + " / " + needed + " " + unit + " on hand.";
			return new Check(label, primary ? READY : ALTERNATIVE, detail, best, fromBank);
		}
		if (req.has("alternatives"))
		{
			for (JsonElement value : req.getAsJsonArray("alternatives"))
			{
				JsonObject alt = value.getAsJsonObject().deepCopy();
				alt.addProperty("label", label);
				alt.addProperty("minimum", 1);
				Check result = automatic(activity, alt, state, live, now);
				boolean usable = result.ready();
				if (alt.has("and_ids"))
				{
					for (int id : ints(alt.getAsJsonArray("and_ids")))
					{
						JsonObject extra = new JsonObject();
						extra.addProperty("label", label);
						JsonArray a = new JsonArray();
						a.add(id);
						extra.add("any_of_ids", a);
						usable &= automatic(activity, extra, state, live, now).ready();
					}
				}
				if (usable)
				{
					return new Check(
						label, ALTERNATIVE,
						"Unlit source and tinderbox available. Light it before entering; apply its Firemaking requirement.",
						1);
				}
			}
		}
		if (blocked)
		{
			return new Check(label, UNKNOWN, reason.isEmpty() ? "Usable form needs checking" : reason, best);
		}
		if (!live.completeItemEvidence(now))
		{
			return new Check(label, UNKNOWN, missingEvidenceReason(live), best);
		}
		if (!distinct && !currentBank && addQuantity(carried, total(bankStock)) >= needed)
		{
			return new Check(label, UNKNOWN, REFRESH_BANK, best);
		}
		return new Check(label, best > 0 ? SHORT : MISSING, quantity, best);
	}

	private static int addQuantity(int a, int b)
	{
		return (int)Math.min(Integer.MAX_VALUE, (long)a + b);
	}

	private static int total(Map<Integer, Integer> stock)
	{
		return stock.values().stream().reduce(0, ReadinessEngine::addQuantity);
	}

	private static Map<Integer, Integer> combinedStock(Map<Integer, Integer> carried, Map<Integer, Integer> bank,
		boolean currentBank)
	{
		Map<Integer, Integer> stock = new HashMap<>(carried);
		// A previous session's bank might still list copies since withdrawn to the inventory.
		bank.forEach((id, amount) -> stock.merge(id, amount, currentBank ? ReadinessEngine::addQuantity : Math::max));
		return stock;
	}

	private Check ammunition(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = str(req, "label", "Ammunition");
		JsonObject compatibility = catalogue.items.getAsJsonObject("ammo_compatibility");
		Set<Integer> seen = new HashSet<>();
		Check best = null;
		for (String location : List.of("equipment", "inventory", "bank"))
		{
			LiveSnapshot.Container container = live.observedItems(location, now);
			if (container == null)
			{
				continue;
			}
			for (LiveSnapshot.Item row : container.items)
			{
				if (row.quantity <= 0 || !rangedWeaponIds.contains(row.id))
				{
					continue;
				}
				int id = base(row.id);
				JsonObject pairing = compatibility.getAsJsonObject(String.valueOf(id));
				JsonObject weapon = pairing == null ? null : item(id);
				if (weapon == null || id != row.id && !location.equals("bank") ||
					!seen.add(id) || gate(weapon, req, state, live) != null)
				{
					continue;
				}
				JsonObject ammo = req.deepCopy();
				ammo.remove("predicate");
				List<Integer> allowed = ints(pairing.getAsJsonArray("allowed_base_ids"));
				if (req.has("any_of_ids"))
				{
					allowed.retainAll(ints(req.getAsJsonArray("any_of_ids")));
					if (allowed.isEmpty())
					{
						continue;
					}
				}
				JsonArray ids = new JsonArray();
				allowed.forEach(ids::add);
				ammo.add("any_of_ids", ids);
				ammo.addProperty("unit", "shots");
				Check supplies = automatic(activity, ammo, state, live, now);
				String weaponName = weapon.get("name").getAsString();
				String detail = weaponName + " · " + supplies.detail;
				if (supplies.ready() && location.equals("bank") && !supplies.fromBank)
				{
					detail += " Withdraw your " + weaponName.toLowerCase(Locale.ROOT) + " before attempting.";
				}
				Check match = new Check(label, supplies.status, detail,
					supplies.amount, supplies.ready() && (supplies.fromBank || location.equals("bank")));
				if (match.ready())
				{
					return match;
				}
				if (best == null || match.amount > best.amount)
				{
					best = match;
				}
			}
		}
		return best == null ?
			new Check(label, UNKNOWN, "Check ammunition for the ranged weapon you plan to bring.", 0) : best;
	}

	private Check prayerDrainSources(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		Integer prayer = level("prayer", state, live);
		int count = prayer != null && prayer >= 52 ? 1 : 0;
		JsonObject mace = new JsonObject();
		JsonArray maces = new JsonArray();
		maces.add(11061);
		mace.add("any_of_ids", maces);
		if (automatic(activity, mace, state, live, now).ready())
		{
			count++;
		}

		JsonObject sapphire = new JsonObject();
		JsonArray bolts = new JsonArray();
		bolts.add(9240);
		bolts.add(21940);
		sapphire.add("any_of_ids", bolts);
		if (ammunition(activity, sapphire, state, live, now).ready())
		{
			count++;
		}

		JsonObject corruption = new JsonObject();
		JsonArray spells = new JsonArray();
		spells.add("Lesser Corruption");
		spells.add("Greater Corruption");
		corruption.add("spells_any", spells);
		if (spells(activity, corruption, state, live, now).ready())
		{
			count++;
		}
		String label = req.get("label").getAsString();
		return count >= 3 ? new Check(label, READY, "Three qualifying sources are available. Bring them on the same kill.", count) :
			new Check(label, UNKNOWN, count + " of 3 sources verified. Use three of Smite, sapphire bolts (e), Ancient mace and Corruption.", count);
	}

	private static final class GearSlots
	{
		private final Set<Integer> occupied = new HashSet<>();
		private boolean oneHandedWeapon;

		void add(JsonObject item)
		{
			int slot = integer(item, "equipment_slot", -1);
			if (slot >= 0)
			{
				occupied.add(slot);
				oneHandedWeapon |= slot == 3 && !yes(item, "two_handed");
			}
		}

		int count()
		{
			return occupied.size() - (!oneHandedWeapon && occupied.contains(3) && occupied.contains(5) ? 1 : 0);
		}
	}
	private Check spells(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = str(req, "label", "Spell supplies");
		List<String> options = new ArrayList<>();
		if (req.has("spell"))
		{
			options.add(req.get("spell").getAsString());
		}
		else
		{
			for (JsonElement s : req.getAsJsonArray("spells_any"))
			{
				options.add(s.getAsString());
			}
		}
		Map<Integer, Integer> carried = new HashMap<>(), bank = new HashMap<>();
		Map<Set<Integer>, Boolean> staffs = new LinkedHashMap<>();
		staffs.put(Collections.emptySet(), true);
		boolean uncertainStaff = false;
		for (String location : locations(live))
		{
			LiveSnapshot.Container container = live.observedItems(location, now);
			if (container == null)
			{
				continue;
			}
			boolean banked = location.equals("bank");
			for (LiveSnapshot.Item row : container.items)
			{
				if (row.quantity <= 0 || !spellRawIds.contains(row.id))
				{
					continue;
				}
				int id = base(row.id);
				if (id != row.id && !banked)
				{
					continue;
				}
				if (elementalStaffIds.contains(id))
				{
					JsonObject staff = item(id);
					if (staff != null && gate(staff, new JsonObject(), state, live) == null)
					{
						staffs.merge(staffRunes(staff), !banked, Boolean::logicalOr);
					}
					else
					{
						uncertainStaff = true;
					}
				}
				else
				{
					(banked ? bank : carried).merge(id, row.quantity, ReadinessEngine::addQuantity);
				}
			}
		}

		boolean currentBank = live.bankObservedThisSession();
		Map<Integer, Integer> owned = combinedStock(carried, bank, currentBank);
		Map<Integer, Integer> upper = currentBank ? owned : combinedStock(carried, bank, true);
		SpellSupply best = new SpellSupply("", "", 0, false), onHand = best;
		int possible = 0;
		String missingQuest = "";
		int requiredLevel = Integer.MAX_VALUE;
		boolean usableSpell = false, questUnknown = false;
		int magic = effectiveLevel("magic", "boostedMagic", state, live);
		for (String spellName : options)
		{
			JsonObject spell = catalogue.items.getAsJsonObject("spells").getAsJsonObject(spellName);
			if (spell == null)
			{
				continue;
			}
			String book = str(spell, "spellbook", "standard");
			String questName = SPELLBOOK_QUESTS.get(book);
			Boolean unlocked = questName == null ? Boolean.TRUE : quest(questName, state, live);
			if (!Boolean.TRUE.equals(unlocked))
			{
				missingQuest = questName;
				questUnknown |= unlocked == null;
				continue;
			}
			int requiredMagic = spell.get("magic").getAsInt();
			requiredLevel = Math.min(requiredLevel, requiredMagic);
			if (magic < requiredMagic)
			{
				continue;
			}
			usableSpell = true;
			Map<Integer, Integer> costs = spellCosts(activity, spellName, spell);
			for (Map.Entry<Set<Integer>, Boolean> staff : staffs.entrySet())
			{
				Set<Integer> elements = staff.getKey();
				int available = casts(owned, costs, elements);
				if (available > best.amount)
				{
					best = new SpellSupply(spellName, book, available, !elements.isEmpty());
				}
				if (staff.getValue())
				{
					int carriedCasts = casts(carried, costs, elements);
					if (carriedCasts > onHand.amount)
					{
						onHand = new SpellSupply(spellName, book, carriedCasts, !elements.isEmpty());
					}
				}
				if (!currentBank)
				{
					possible = Math.max(possible, casts(upper, costs, elements));
				}
			}
		}
		int minimum = integer(req, "minimum", 1);
		String note = activity.equals("titans") ? "Both elemental budgets counted together. " : "";
		if (best.amount >= minimum)
		{
			boolean fromBank = onHand.amount < minimum;
			SpellSupply supply = fromBank ? best : onHand;
			String action = fromBank ? " Withdraw the remaining supplies from your bank." : "";
			Integer activeBook = live.context.get("spellbook");
			if (activeBook != null && !activeBook.equals(SPELLBOOKS.get(supply.book)))
			{
				action += " Switch to the " + supply.book + " spellbook before the attempt.";
			}
			if (supply.staff)
			{
				action += " Equip the elemental staff for this setup.";
			}
			return new Check(label, supply.staff ? ALTERNATIVE : READY,
				note + supply.spell + " · " + supply.amount + " / " + minimum + " casts available." + action,
				supply.amount, fromBank);
		}
		if (!usableSpell)
		{
			if (requiredLevel < Integer.MAX_VALUE)
			{
				return new Check(label, magic > 0 ? MISSING : UNKNOWN, "Needs Magic " + requiredLevel + " for this spell.", 0);
			}
			if (!missingQuest.isEmpty())
			{
				return new Check(label, questUnknown ? UNKNOWN : MISSING, "Complete " + missingQuest + " to use this spellbook.", 0);
			}
			return new Check(label, UNKNOWN, "Check the spell recipe in game.", 0);
		}
		if (possible >= minimum)
		{
			return new Check(label, UNKNOWN, REFRESH_BANK, best.amount, false, true);
		}
		if (uncertainStaff || !live.completeItemEvidence(now) || live.context.getOrDefault("pouchUnknown", 0) == 1)
		{
			return new Check(
				label, UNKNOWN,
				uncertainStaff ? "An elemental staff was observed, but its usable wear requirements need checking."
				: live.context.getOrDefault("pouchUnknown", 0) == 1 ? "Held rune pouch contents could not be verified."
					: missingEvidenceReason(live),
				best.amount, false, true);
		}
		String supplies = live.mode == AccountMode.NORMAL ? " Restock tradeable runes at the Grand Exchange or a rune shop." :
			" Restock from your own supplies or a rune shop.";
		return new Check(label, best.amount > 0 ? SHORT : MISSING,
			note + best.amount + " / " + minimum + " casts from observed supplies." + supplies, best.amount, false, true);
	}

	private Map<Integer, Integer> spellCosts(String activity, String spellName, JsonObject spell)
	{
		Map<Integer, Integer> costs = new HashMap<>();
		addSpellCosts(costs, spell);
		// Both Titans spells draw from a single supply of air and death runes.
		if (activity.equals("titans"))
		{
			for (JsonElement other : catalogue.kit(activity))
			{
				String otherName = str(other.getAsJsonObject(), "spell", "");
				JsonObject otherSpell = catalogue.items.getAsJsonObject("spells").getAsJsonObject(otherName);
				if (!otherName.equals(spellName) && otherSpell != null)
				{
					addSpellCosts(costs, otherSpell);
				}
			}
		}
		return costs;
	}

	private static void addSpellCosts(Map<Integer, Integer> costs, JsonObject spell)
	{
		spell.getAsJsonObject("runes_per_cast").entrySet().forEach(cost ->
			costs.merge(Integer.parseInt(cost.getKey()), cost.getValue().getAsInt(), Integer::sum));
	}

	private static int casts(Map<Integer, Integer> stock, Map<Integer, Integer> costs, Set<Integer> staff)
	{
		int casts = Integer.MAX_VALUE;
		for (Map.Entry<Integer, Integer> cost : costs.entrySet())
		{
			int rune = cost.getKey();
			if (staff.contains(rune))
			{
				continue;
			}
			int available = stock.getOrDefault(rune, 0);
			for (Map.Entry<Integer, Set<Integer>> combo : COMBINATION_RUNES.entrySet())
			{
				if (combo.getValue().contains(rune))
				{
					available = addQuantity(available, stock.getOrDefault(combo.getKey(), 0));
				}
			}
			casts = Math.min(casts, available / cost.getValue());
		}
		return casts;
	}

	private static final class SpellSupply
	{
		private final String spell, book;
		private final int amount;
		private final boolean staff;

		private SpellSupply(String spell, String book, int amount, boolean staff)
		{
			this.spell = spell;
			this.book = book;
			this.amount = amount;
			this.staff = staff;
		}
	}

	private Set<Integer> staffRunes(JsonObject item)
	{
		Set<Integer> ids = new HashSet<>();
		String name = str(item, "name", "").toLowerCase(Locale.ROOT);
		if (name.contains("air"))
		{
			ids.add(556);
		}
		if (name.contains("water"))
		{
			ids.add(555);
		}
		if (name.contains("earth"))
		{
			ids.add(557);
		}
		if (name.contains("fire"))
		{
			ids.add(554);
		}
		return ids;
	}
	private Set<Integer> wanted(JsonObject req)
	{
		Set<Integer> ids = new HashSet<>();
		if (req.has("any_of_ids"))
		{
			ids.addAll(ints(req.getAsJsonArray("any_of_ids")));
		}
		if (req.has("group"))
		{
			JsonArray group = catalogue.items.getAsJsonObject("groups").getAsJsonArray(req.get("group").getAsString());
			if (group != null)
			{
				ids.addAll(ints(group));
			}
		}
		if (str(req, "predicate", "").equals("teleport_usable_for_location"))
		{
			for (Map.Entry<String, JsonElement> e : catalogue.items.getAsJsonObject("items").entrySet())
			{
				JsonObject item = e.getValue().getAsJsonObject();
				if (item.has("tags") && contains(item.getAsJsonArray("tags"), "teleport") &&
					(integer(item, "teleport_charges", 0) > 0 ||
						yes(item, "stackable") && str(item, "name", "").toLowerCase(Locale.ROOT).contains("teleport")))
				{
					ids.add(Integer.parseInt(e.getKey()));
				}
			}
		}
		return ids;
	}
	private String gate(JsonObject item, JsonObject req, GuideState state, LiveSnapshot live)
	{
		boolean use = useRequirements(item, req);
		String charge = str(item, "charge_state", "");
		boolean ring = req.has("group") && req.get("group").getAsString().equals("brutus_cowbell");
		if (yes(item, "broken") || charge.equals("broken") || charge.equals("uncharged") && !ring ||
			item.has("charged") && !item.get("charged").getAsBoolean())
		{
			return "Unusable broken or uncharged form";
		}
		if (charge.equals("hidden_charge_count"))
		{
			return "Usable charge count needs checking";
		}
		if (yes(item, "equipable") && !use && !yes(item, "requirements_verified"))
		{
			return "Wear requirements need checking";
		}
		JsonElement requirements = item.get(use ? "use_requirements" : "wear_requirements");
		if (requirements != null && requirements.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : requirements.getAsJsonObject().entrySet())
			{
				Integer n = level(e.getKey(), state, live);
				if (n == null || n < e.getValue().getAsInt())
				{
					return "Needs " + e.getKey() + " " + e.getValue().getAsInt();
				}
			}
		}
		if (!use && "westHard".equals(str(item, "wear_diary", "")) &&
			(live == null || live.context.getOrDefault("westHard", 0) == 0 && live.context.getOrDefault("westElite", 0) == 0))
		{
			return "Complete the Hard Western Provinces Diary to equip this halberd.";
		}
		String quests = use ? "use_quests" : "wear_quests";
		if (item.has(quests) && item.get(quests).isJsonArray())
		{
			for (JsonElement q : item.getAsJsonArray(quests))
			{
				if (!Boolean.TRUE.equals(quest(q.getAsString(), state, live)))
				{
					return q.getAsString() + " completion needs checking";
				}
			}
		}
		return null;
	}

	private static boolean useRequirements(JsonObject item, JsonObject req)
	{
		String group = str(req, "group", "");
		return !yes(req, "must_equip") && item.has("use_requirements") && (group.contains("axes") || group.contains("pickaxe") ||
			!yes(item, "equipable"));
	}

	/** Unlocks shared by every tracked option for a required kit row. */
	KitUnlocks kitUnlocks(JsonObject req)
	{
		KitUnlocks result = new KitUnlocks();
		if (req.has("spell") || req.has("spells_any"))
		{
			JsonArray choices = req.getAsJsonArray("spells_any");
			if (req.has("spell"))
			{
				choices = new JsonArray();
				choices.add(req.get("spell"));
			}
			int minimum = Integer.MAX_VALUE;
			Set<String> books = new HashSet<>();
			for (JsonElement choice : choices)
			{
				JsonObject spell = catalogue.items.getAsJsonObject("spells").getAsJsonObject(choice.getAsString());
				if (spell == null)
				{
					return result;
				}
				minimum = Math.min(minimum, spell.get("magic").getAsInt());
				books.add(str(spell, "spellbook", "standard"));
			}
			if (!req.has("alt_item_ids") && minimum < Integer.MAX_VALUE)
			{
				result.levels.put("magic", minimum);
				if (books.size() == 1)
				{
					String quest = SPELLBOOK_QUESTS.get(books.iterator().next());
					if (quest != null)
					{
						result.quests.add(quest);
					}
				}
			}
			return result;
		}
		if (req.has("alternatives"))
		{
			return result;
		}
		boolean first = true;
		for (int id : wanted(req))
		{
			JsonObject option = item(id);
			if (option == null)
			{
				return new KitUnlocks();
			}
			boolean use = useRequirements(option, req);
			JsonElement levelData = option.get(use ? "use_requirements" : "wear_requirements");
			JsonObject levels = levelData != null && levelData.isJsonObject() ? levelData.getAsJsonObject() : null;
			JsonElement questData = use ? null : option.get("wear_quests");
			JsonArray quests = questData != null && questData.isJsonArray() ? questData.getAsJsonArray() : null;
			Set<String> names = new HashSet<>();
			if (quests != null)
			{
				for (JsonElement quest : quests)
				{
					names.add(quest.getAsString());
				}
			}
			if (first)
			{
				if (levels != null)
				{
					levels.entrySet().forEach(entry -> result.levels.put(entry.getKey(), entry.getValue().getAsInt()));
				}
				result.quests.addAll(names);
				first = false;
			}
			else
			{
				result.levels.entrySet().removeIf(entry -> levels == null || !levels.has(entry.getKey()));
				result.levels.replaceAll((skill, minimum) -> Math.min(minimum, levels.get(skill).getAsInt()));
				result.quests.retainAll(names);
			}
		}
		return result;
	}

	static final class KitUnlocks
	{
		final Map<String, Integer> levels = new HashMap<>();
		final Set<String> quests = new HashSet<>();
	}
	public static Integer level(String key, GuideState state, LiveSnapshot live)
	{
		String k = key.toLowerCase(Locale.ROOT).replace(" ", "");
		if (state.object("levels").has(k))
		{
			return Integer.valueOf(state.object("levels").get(k).getAsInt());
		}
		return live == null ? null : live.levels.get(k);
	}
	static int effectiveLevel(String skill, String boostKey, GuideState state, LiveSnapshot live)
	{
		Integer base = level(skill, state, live);
		int boosted = live == null ? 0 : live.context.getOrDefault(boostKey, 0);
		return Math.max(base == null ? 0 : base, boosted);
	}
	public static Boolean quest(String key, GuideState state, LiveSnapshot live)
	{
		if (state.object("quests").has(key))
		{
			return state.object("quests").get(key).getAsBoolean();
		}
		return observedQuest(key, live);
	}

	static Boolean observedQuest(String key, LiveSnapshot live)
	{
		String value = live == null ? null : live.quests.get(key);
		return value == null ? null : value.equals("FINISHED");
	}
	private static LiveSnapshot preparationEvidence(GuideState state, LiveSnapshot live)
	{
		return live != null && state.rewardGoal() == RewardGoal.ELITE ? live.withoutHistoricalBank() : live;
	}

	private static boolean eliteGroup(GuideState state, LiveSnapshot live)
	{
		return state.rewardGoal() == RewardGoal.ELITE && live != null &&
			(live.mode == AccountMode.GROUP || live.mode == AccountMode.UNRANKED_GROUP ||
				live.mode == AccountMode.HARDCORE_GROUP);
	}

	public Check token(String token, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = resourceLabel(token);
		if ("hespori-level".equals(token))
		{
			int farming = effectiveLevel("farming", "boostedFarming", state, live);
			return farming >= 65 ? new Check("Hespori Farming access", READY, "At least 65 Farming observed.", farming) :
				new Check("Hespori Farming access", farming > 0 && farming < 60 ? MISSING : UNKNOWN,
					"Reach 65 Farming or bring a boost that reaches it.", farming);
		}
		if ("hespori".equals(token))
		{
			HesporiPatch.Status patch = HesporiPatch.status(state, now);
			if (patch != HesporiPatch.Status.UNKNOWN)
			{
				return new Check(label, patch == HesporiPatch.Status.GROWN ? READY : UNKNOWN, patch.detail, 0);
			}
		}
		JsonObject req = tokenRequirement(token);
		if (state.object("items").has(token) && (!eliteGroup(state, live) || req == null ||
			!state.object("items").get(token).getAsBoolean()))
		{
			return new Check(label, state.object("items").get(token).getAsBoolean() ? MANUAL : MISSING,
								"Manual resource confirmation", 0);
		}
		if (req != null && !req.has("spell") && !req.has("spells_any"))
		{
			live = preparationEvidence(state, live);
		}
		if (req == null)
		{
			if ("hespori".equals(token))
			{
				return new Check(label, UNKNOWN, HesporiPatch.Status.UNKNOWN.detail, 0);
			}
			return new Check(label, UNKNOWN, "Check this in game before confirming availability.", 0);
		}
		return automatic("", req, state, live, now);
	}

	JsonObject tokenRequirement(String token)
	{
		JsonObject req = new JsonObject();
		req.addProperty("label", resourceLabel(token));
		req.addProperty("minimum", 1);
		JsonArray ids = new JsonArray();
		String group = null;
		switch (token)
		{
		case "giant-key":
			ids.add(20754);
			break;
		case "mossy-key":
			ids.add(22374);
			break;
		case "four-giant-keys":
			ids.add(20754);
			req.addProperty("minimum", 4);
			break;
		case "four-mossy-keys":
			ids.add(22374);
			req.addProperty("minimum", 4);
			break;
		case "spine":
			group = "ratbane";
			break;
		case "salve":
			group = "salve_amulet";
			break;
		case "temotli":
			group = "exact_glacial_temotli";
			break;
		case "dragon-scimitar":
			group = "exact_dragon_scimitar";
			break;
		case "dragon-dagger":
			group = "dragon_dagger";
			break;
		case "shayzien-armour":
			group = "shayzien_tier_five";
			req.addProperty("minimum", 5);
			req.addProperty("distinct_equipment_slots", true);
			break;
		case "angler":
			group = "angler_outfit";
			req.addProperty("minimum", 4);
			req.addProperty("distinct_equipment_slots", true);
			break;
		case "fire-cape":
			ids.add(6570);
			ids.add(24223);
			break;
		case "huey-earth-supplies":
			JsonArray earthSpells = new JsonArray();
			List.of("Earth Strike", "Earth Bolt", "Earth Blast", "Earth Wave", "Earth Surge").forEach(earthSpells::add);
			req.add("spells_any", earthSpells);
			return req;
		case "totem":
			ids.add(19685);
			break;
		default:
			return null;
		}
		if (group != null)
		{
			req.addProperty("group", group);
		}
		else
		{
			req.add("any_of_ids", ids);
		}
		return req;
	}
	public String resourceLabel(String token)
	{
		JsonObject labels = catalogue.route().getAsJsonObject("resourceLabels");
		return labels != null && labels.has(token) ? labels.get(token).getAsString() : token.replace('-', ' ');
	}
	private JsonObject item(int id)
	{
		return catalogue.items.getAsJsonObject("items").getAsJsonObject(String.valueOf(id));
	}
	private int base(int id)
	{
		JsonObject aliases = catalogue.items.getAsJsonObject("note_to_base");
		return aliases.has(String.valueOf(id)) ? aliases.get(String.valueOf(id)).getAsInt() : id;
	}
	private static List<String> locations(LiveSnapshot l)
	{
		return l.mode.bankAllowed() ? Arrays.asList("bank", "inventory", "equipment")
									: Arrays.asList("inventory", "equipment");
	}
	public static List<Integer> ints(JsonArray a)
	{
		List<Integer> out = new ArrayList<>();
		if (a != null)
		{
			for (JsonElement e : a)
			{
				out.add(e.getAsInt());
			}
		}
		return out;
	}
	public static String str(JsonObject o, String k, String d)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
	}
	private static int integer(JsonObject o, String k, int d)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
	}
	private static boolean yes(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
	}
	private static boolean contains(JsonArray a, String s)
	{
		for (JsonElement e : a)
		{
			if (s.equals(e.getAsString()))
			{
				return true;
			}
		}
		return false;
	}
	private static String missingEvidenceReason(LiveSnapshot live)
	{
		return live.mode.bankAllowed() && live.observedBank() == null ? FIRST_BANK_CHECK
			: "Check your carried supplies in game.";
	}
	public static final class Check
	{
		public final String label, status, detail;
		public final int amount;
		public final boolean fromBank, restock;
		public Check(String l, String s, String d, int a)
		{
			this(l, s, d, a, false);
		}
		public Check(String l, String s, String d, int a, boolean fromBank)
		{
			this(l, s, d, a, fromBank, false);
		}

		private Check(String l, String s, String d, int a, boolean fromBank, boolean restock)
		{
			label = l;
			status = s;
			detail = d;
			amount = a;
			this.fromBank = fromBank;
			this.restock = restock;
		}
		public boolean blocksPlanning(LiveSnapshot live)
		{
			return !ready() && !(restock && live != null && live.mode == AccountMode.NORMAL);
		}
		Check withLabel(String name)
		{
			return new Check(name, status, detail, amount, fromBank, restock);
		}
		public String displayStatus()
		{
			return fromBank ? "In bank" : status;
		}
		public boolean ready()
		{
			return status.equals(READY) || status.equals(ALTERNATIVE) || status.equals(MANUAL) ||
				status.equals(PROVIDED) || status.equals(UNUSED);
		}
	}
}
