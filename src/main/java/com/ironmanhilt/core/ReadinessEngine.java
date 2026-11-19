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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative item evidence. Independent container snapshots are never added together. */
public final class ReadinessEngine
{
	public static final String READY = "Ready", ALTERNATIVE = "Alternative available", UNKNOWN = "Needs checking",
								MISSING = "Missing", SHORT = "Short", MANUAL = "Manually confirmed",
								PROVIDED = "Collect there", UNUSED = "Not needed for selected tasks";
	public static final String FIRST_BANK_CHECK = "Open your bank once to check owned items.";
	private final GuideCatalog catalogue;
	private final Map<Integer, Set<Integer>> notedIds = new HashMap<>();
	private final Set<Integer> spellRawIds;
	private final Set<Integer> guideItemIds;
	public ReadinessEngine(GuideCatalog c)
	{
		catalogue = c;
		for (Map.Entry<String, JsonElement> entry : c.items.getAsJsonObject("note_to_base").entrySet())
		{
			notedIds.computeIfAbsent(entry.getValue().getAsInt(), id -> new HashSet<>())
				.add(Integer.parseInt(entry.getKey()));
		}
		Set<Integer> spellItems = new HashSet<>(ints(c.items.getAsJsonObject("groups")
			.getAsJsonArray("basic_elemental_staff")));
		for (Map.Entry<String, JsonElement> spell : c.items.getAsJsonObject("spells").entrySet())
		{
			for (String id : spell.getValue().getAsJsonObject().getAsJsonObject("runes_per_cast").keySet())
			{
				spellItems.add(Integer.parseInt(id));
			}
		}
		for (int id = 4694; id <= 4699; id++)
		{
			spellItems.add(id);
		}
		spellRawIds = withNotes(spellItems);
		Set<Integer> itemIds = new HashSet<>();
		for (String id : c.items.getAsJsonObject("items").keySet())
		{
			itemIds.add(Integer.parseInt(id));
		}
		guideItemIds = withNotes(itemIds);
		guideItemIds.addAll(spellRawIds);
	}
	public boolean usesItem(int id)
	{
		return guideItemIds.contains(id);
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
		Check automatic = automatic(activity, req, state, live, now);
		JsonObject overrides = state.object("overrides");
		JsonElement entry = overrides.get(activity + ":" + index);
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
		if (status.equals("Not required"))
		{
			return new Check(label, MANUAL, "Manually marked not required · " + date, 0);
		}
		if (status.equals("Owned"))
		{
			if (automatic.detail.startsWith("Needs ") || automatic.detail.startsWith("Unusable") ||
				automatic.detail.startsWith("Spellbook"))
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
			return new Check(label, MANUAL, "Manual correction · " + date, -1);
		}
		return automatic;
	}
	private Check automatic(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = str(req, "label", "Preparation");
		if (live == null || !live.mode.supported())
		{
			return new Check(label, UNKNOWN, "Log in on a standard-world account", 0);
		}
		if (req.has("spell") || req.has("spells_any"))
		{
			return spells(activity, req, state, live, now);
		}
		if (str(req, "predicate", "").equals("teleport_usable_for_location") && RiskPolicy.wilderness(activity))
		{
			return new Check(label, UNKNOWN, "Confirm Wilderness level, teleblock and teleport restrictions in game.",
								0);
		}
		Set<Integer> ids = wanted(req);
		if (str(req, "predicate", "").equals("ammo_compatible_with_selected_weapon"))
		{
			LiveSnapshot.Container equipment = live.observedItems("equipment", now);
			Integer weapon = null;
			if (equipment != null)
			{
				for (LiveSnapshot.Item row : equipment.items)
				{
					if (row.slot == 3)
					{
						weapon = row.id;
					}
				}
			}
			JsonObject ammo =
				weapon == null
					? null
					: catalogue.items.getAsJsonObject("ammo_compatibility").getAsJsonObject(String.valueOf(weapon));
			if (ammo == null)
			{
				return new Check(
					label, UNKNOWN,
					"Equip the intended ranged weapon; its ammunition pairing must be in the verified catalogue.", 0);
			}
			ids.addAll(ints(ammo.getAsJsonArray("allowed_base_ids")));
		}
		if (ids.isEmpty())
		{
			return new Check(label, UNKNOWN, "Confirm this strategy condition in game.", 0);
		}
		LiveSnapshot.Container bank = live.observedBank();
		Set<Integer> rawIds = bank != null && bank.items.size() > 64 ? withNotes(ids) : null;
		int best = 0;
		boolean primary = false, blocked = false;
		String reason = "";
		for (String location : locations(live))
		{
			LiveSnapshot.Container c = live.observedItems(location, now);
			if (c == null)
			{
				continue;
			}
			int count = 0;
			Set<Integer> slots = new HashSet<>();
			boolean locationPrimary = false, twoHanded = false;
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
				if (id != row.id && !location.equals("bank"))
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
				int multiplier = str(req, "unit", "").equals("doses") ? integer(item, "doses_per_item", 0) : 1;
				if (yes(req, "distinct_equipment_slots"))
				{
					if (item.has("equipment_slot") && !item.get("equipment_slot").isJsonNull())
					{
						slots.add(item.get("equipment_slot").getAsInt());
					}
					twoHanded |= yes(item, "two_handed");
				}
				else
				{
					count = (int)Math.min(Integer.MAX_VALUE, (long)count + (long)row.quantity * multiplier);
				}
				if (!req.has("primary_ids") || ints(req.getAsJsonArray("primary_ids")).contains(id))
				{
					locationPrimary = true;
				}
			}
			if (yes(req, "distinct_equipment_slots"))
			{
				if (twoHanded && slots.contains(3))
				{
					slots.remove(5);
				}
				count = slots.size();
			}
			if (count > best || count == best && locationPrimary)
			{
				best = count;
				primary = locationPrimary;
			}
		}
		int needed = integer(req, "minimum", 1);
		if (best >= needed)
		{
			return new Check(label, primary ? READY : ALTERNATIVE,
								best + " / " + needed + " " + str(req, "unit", "items") + " in one observed location. " +
									(yes(req, "distinct_equipment_slots") ? "Wear the distinct slots together."
																		: "Withdraw banked supplies before attempting."),
								best);
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
		return new Check(label, best > 0 ? SHORT : MISSING, best + " / " + needed + " " + str(req, "unit", "items"),
							best);
	}
	private Check spells(String activity, JsonObject req, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = str(req, "label", "Spell supplies");
		Integer book = live.context.get("spellbook");
		if (book == null)
		{
			return new Check(label, UNKNOWN, "Spellbook has not been observed. Confirm the standard spellbook.", 0);
		}
		if (book != 0)
		{
			return new Check(label, UNKNOWN, "Spellbook: switch to the standard spellbook for this recipe.", 0);
		}
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
		int best = 0;
		String name = "", failure = "";
		boolean alternative = false, combo = false, uncertainStaff = false;
		for (String spellName : options)
		{
			JsonObject spell = catalogue.items.getAsJsonObject("spells").getAsJsonObject(spellName);
			if (spell == null)
			{
				continue;
			}
			Integer magic = level("magic", state, live);
			if (magic == null || magic < spell.get("magic").getAsInt())
			{
				failure = "Needs Magic " + spell.get("magic").getAsInt() + " for " + spellName;
				continue;
			}
			Map<Integer, Integer> costs = new HashMap<>();
			for (Map.Entry<String, JsonElement> cost : spell.getAsJsonObject("runes_per_cast").entrySet())
			{
				costs.put(Integer.parseInt(cost.getKey()), cost.getValue().getAsInt());
			}
			// Titans needs both rune budgets at once, including shared death/air runes.
			if (activity.equals("titans"))
			{
				for (JsonElement other : catalogue.kit(activity))
				{
					JsonObject o = other.getAsJsonObject();
					if (!o.has("spell") || o.get("spell").getAsString().equals(spellName))
					{
						continue;
					}
					JsonObject sp =
						catalogue.items.getAsJsonObject("spells").getAsJsonObject(o.get("spell").getAsString());
					if (sp != null)
					{
						for (Map.Entry<String, JsonElement> cost : sp.getAsJsonObject("runes_per_cast").entrySet())
						{
							costs.merge(Integer.parseInt(cost.getKey()), cost.getValue().getAsInt(), Integer::sum);
						}
					}
				}
			}
			for (String location : locations(live))
			{
				LiveSnapshot.Container container = live.observedItems(location, now);
				if (container == null)
				{
					continue;
				}
				Map<Integer, Integer> quantities = new HashMap<>();
				List<Set<Integer>> staffs = new ArrayList<>();
				staffs.add(Collections.emptySet());
				for (LiveSnapshot.Item row : container.items)
				{
					if (!spellRawIds.contains(row.id))
					{
						continue;
					}
					int baseId = base(row.id);
					if (baseId != row.id && !location.equals("bank"))
					{
						continue;
					}
					quantities.merge(baseId, row.quantity,
										(a, b) -> (int)Math.min(Integer.MAX_VALUE, (long)a + b));
					if (row.id >= 4694 && row.id <= 4699)
					{
						combo = true;
					}
					JsonObject item = item(baseId);
					Set<Integer> elements = staffRunes(item);
					if (item != null && !elements.isEmpty())
					{
						if (gate(item, new JsonObject(), state, live) == null)
						{
							staffs.add(elements);
						}
						else
						{
							uncertainStaff = true;
						}
					}
				}
				// A currently equipped staff can be used with carried or banked runes. Never combine two staffs.
				LiveSnapshot.Container equip = live.observedItems("equipment", now);
				if (equip != null)
				{
					for (LiveSnapshot.Item row : equip.items)
					{
						JsonObject item = item(row.id);
						if (row.slot == 3 && item != null && gate(item, new JsonObject(), state, live) == null &&
							!staffRunes(item).isEmpty())
						{
							staffs.add(staffRunes(item));
						}
					}
				}
				for (Set<Integer> staff : staffs)
				{
					int casts = Integer.MAX_VALUE;
					for (Map.Entry<Integer, Integer> cost : costs.entrySet())
					{
						if (!staff.contains(cost.getKey()))
						{
							casts = Math.min(casts, quantities.getOrDefault(cost.getKey(), 0) / cost.getValue());
						}
					}
					if (casts > best)
					{
						best = casts;
						name = spellName;
						alternative = !staff.isEmpty();
					}
				}
			}
		}
		int minimum = integer(req, "minimum", 1);
		String note = activity.equals("titans") ? "Both elemental budgets counted together. " : "";
		if (best >= minimum)
		{
			return new Check(label, alternative ? ALTERNATIVE : READY,
								note + name + ": " + best + " / " + minimum +
									" casts with one usable staff setup. Equip that staff before casting.",
								best);
		}
		if (!failure.isEmpty() && best == 0)
		{
			return new Check(label, UNKNOWN, failure, 0);
		}
		if (combo || uncertainStaff || !live.completeItemEvidence(now) || live.context.getOrDefault("pouchUnknown", 0) == 1)
		{
			return new Check(
				label, UNKNOWN,
				combo			 ? "Combination-rune allocation needs checking."
				: uncertainStaff ? "An elemental staff was observed, but its usable wear requirements need checking."
				: live.context.getOrDefault("pouchUnknown", 0) == 1 ? "Held rune pouch contents could not be verified."
																					: missingEvidenceReason(live),
				best);
		}
		return new Check(label, best > 0 ? SHORT : MISSING,
							note + best + " / " + minimum + " casts from observed supplies.", best);
	}
	private Set<Integer> staffRunes(JsonObject item)
	{
		Set<Integer> ids = new HashSet<>();
		if (item == null)
		{
			return ids;
		}
		String name = str(item, "name", "").toLowerCase(Locale.ROOT);
		// Only the explicitly catalogued ordinary elemental staffs. No charged spell weapon inferred.
		if (!ints(catalogue.items.getAsJsonObject("groups").getAsJsonArray("basic_elemental_staff"))
					.contains(item.get("id").getAsInt()))
		{
			return ids;
		}
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
		if (!use && item.has("wear_quests") && item.get("wear_quests").isJsonArray())
		{
			for (JsonElement q : item.getAsJsonArray("wear_quests"))
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
		return item.has("use_requirements") && (group.contains("axes") || group.contains("pickaxe") ||
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
			for (JsonElement choice : choices)
			{
				JsonObject spell = catalogue.items.getAsJsonObject("spells").getAsJsonObject(choice.getAsString());
				if (spell == null)
				{
					return result;
				}
				minimum = Math.min(minimum, spell.get("magic").getAsInt());
			}
			if (minimum < Integer.MAX_VALUE)
			{
				result.levels.put("magic", minimum);
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
	public Check token(String token, GuideState state, LiveSnapshot live, Instant now)
	{
		String label = resourceLabel(token);
		if (state.object("items").has(token))
		{
			return new Check(label, state.object("items").get(token).getAsBoolean() ? MANUAL : MISSING,
								"Manual resource confirmation", 0);
		}
		JsonObject req = tokenRequirement(token);
		if (req == null)
		{
			return new Check(label, UNKNOWN, "Check this in game, then use Account & backups → Progress corrections.", 0);
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
		case "huey-earth-supplies":
			req.addProperty("spell", "Earth Blast");
			req.addProperty("minimum", 60);
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
		public Check(String l, String s, String d, int a)
		{
			label = l;
			status = s;
			detail = d;
			amount = a;
		}
		public boolean ready()
		{
			return status.equals(READY) || status.equals(ALTERNATIVE) || status.equals(MANUAL) ||
				status.equals(PROVIDED) || status.equals(UNUSED);
		}
	}
}
