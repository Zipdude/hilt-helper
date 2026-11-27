/*
	* Copyright (c) 2026, OSRS Ironman Combat Achievement Progression Guide contributors
	* Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
	* Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
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
package com.ironmanhilt;

import com.ironmanhilt.core.AccountMode;
import com.ironmanhilt.core.CaDecoder;
import com.ironmanhilt.core.GuideCatalog;
import com.ironmanhilt.core.LiveSnapshot;
import com.ironmanhilt.core.RewardGoal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WidgetNode;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.config.RuneScapeProfileType;

/** Called on the client thread. Does not export files, use HTTP or operate game inputs. */
public final class RuneLiteCollector
{
	// RuneLite Time Tracking's FarmingWorld maps the Hespori cave to this region and transmit varbit.
	private static final int HESPORI_REGION = 5021;
	private boolean wasInHesporiCave;
	// Audited against CA Exporter and RuneLite 1.13.6 gameval constants.
	static final int[] CA_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0,  VarPlayerID.CA_TASK_COMPLETED_1,	VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3,  VarPlayerID.CA_TASK_COMPLETED_4,	VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6,  VarPlayerID.CA_TASK_COMPLETED_7,	VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9,  VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20};
	private static final int[] RELEVANT_VARBITS = {VarbitID.IRONMAN,
													VarbitID.CA_THRESHOLD_MEDIUM,
													VarbitID.CA_THRESHOLD_HARD,
													VarbitID.CA_THRESHOLD_ELITE,
													VarbitID.CA_THRESHOLD_MASTER,
													VarbitID.CA_POINTS,
													VarbitID.SPELLBOOK,
													VarbitID.RUNE_POUCH_TYPE_1,
													VarbitID.RUNE_POUCH_TYPE_2,
													VarbitID.RUNE_POUCH_TYPE_3,
													VarbitID.RUNE_POUCH_TYPE_4,
													VarbitID.RUNE_POUCH_TYPE_5,
													VarbitID.RUNE_POUCH_TYPE_6,
													VarbitID.RUNE_POUCH_QUANTITY_1,
													VarbitID.RUNE_POUCH_QUANTITY_2,
													VarbitID.RUNE_POUCH_QUANTITY_3,
													VarbitID.RUNE_POUCH_QUANTITY_4,
													VarbitID.RUNE_POUCH_QUANTITY_5,
													VarbitID.RUNE_POUCH_QUANTITY_6};
	// Local game counters and flags used for readiness. They never award achievement points.
	private static final Map<Integer, String> ACCESS_VARPS = Map.ofEntries(
		Map.entry(VarPlayerID.TOTAL_ARMADYL_KILLS, "kc:kree-arra"),
		Map.entry(VarPlayerID.TOTAL_BANDOS_KILLS, "kc:general-graardor"),
		Map.entry(VarPlayerID.TOTAL_SARADOMIN_KILLS, "kc:commander-zilyana"),
		Map.entry(VarPlayerID.TOTAL_ZAMORAK_KILLS, "kc:k-ril-tsutsaroth"),
		Map.entry(VarPlayerID.TOTAL_NEX_KILLS, "kc:nex"),
		Map.entry(VarPlayerID.TOTAL_YAMA_KILLS, "kc:yama"),
		Map.entry(VarPlayerID.TOTAL_VORKATH_KILLS, "kc:vorkath"),
		Map.entry(VarPlayerID.TOTAL_HYDRABOSS_KILLS, "kc:alchemical-hydra"),
		Map.entry(VarPlayerID.TOTAL_HESPORI_KILLS, "kc:hespori"),
		Map.entry(VarPlayerID.TOTAL_NIGHTMARE_KILLS, "kc:nightmare"),
		Map.entry(VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS, "kc:phosani"),
		Map.entry(VarPlayerID.TOTAL_MUSPAH_KILLS, "kc:muspah"),
		Map.entry(VarPlayerID.TOTAL_ARAXXOR_KILLS, "kc:araxxor"),
		Map.entry(VarPlayerID.TOTAL_MAGGOT_KING_KILLS, "kc:maggot-king"),
		Map.entry(VarPlayerID.TOTAL_SNAKEBOSS_KILLS, "kc:zulrah"),
		Map.entry(VarPlayerID.TOTAL_GARGBOSS_KILLS, "kc:guardian"),
		Map.entry(VarPlayerID.TOTAL_ZUK_KILLS, "kc:tzkal-zuk"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_GAUNTLET, "kc:gauntlet"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM, "kc:corrupted-gauntlet"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD, "kc:theatre-normal"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD, "kc:theatre-hard"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS, "kc:chambers"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE, "kc:chambers-cm"),
		Map.entry(VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT, "kc:toa-expert"),
		Map.entry(VarPlayerID.TOTAL_SOL_KILLS, "kc:sol-heredit"),
		Map.entry(VarPlayerID.TOTAL_GRYPHON_BOSS_KILLS, "kc:shellbane"),
		Map.entry(VarPlayerID.TOTAL_MAD_ANGEL_KILLS, "kc:mad-angel"));
	private static final Map<Integer, String> ACCESS_VARBITS = Map.ofEntries(
		Map.entry(VarbitID.GODWARS_ENTRANCE_ROPE, "gwdRope"),
		Map.entry(VarbitID.TROLL_ENTERED_STRONGHOLD, "trollAccess"),
		Map.entry(VarbitID.INFERNO_SACRIFICED_FIRECAPE, "infernoAccess"),
		Map.entry(VarbitID.GARGBOSS_UNLOCKED_ROOF, "guardianRoof"),
		Map.entry(VarbitID.CA_THRESHOLD_EASY, "caEasyThreshold"),
		Map.entry(VarbitID.CA_THRESHOLD_GRANDMASTER, "caGrandmasterThreshold"),
		Map.entry(VarbitID.CA_TIER_STATUS_GRANDMASTER, "caGrandmasterStatus"),
		Map.entry(VarbitID.GIM_GROUPSIZE, "caGroupSize"),
		Map.entry(VarbitID.GIM_I_AM_MEMBER, "caGroupMember"),
		Map.entry(VarbitID.GIM_IS_PRESTIGED, "caGroupPrestige"),
		Map.entry(VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, "wildMedium"),
		Map.entry(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, "wildHard"),
		Map.entry(VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, "wildElite"),
		Map.entry(VarbitID.WESTERN_DIARY_HARD_COMPLETE, "westHard"),
		Map.entry(VarbitID.WESTERN_DIARY_ELITE_COMPLETE, "westElite"));
	// Game Slayer tables used by RuneLite's built-in Slayer plugin in the pinned client.
	private static final int SLAYER_TASK_TABLE = 113;
	private static final int SLAYER_BOSS_TABLE = 116;
	private static final int BOSS_ASSIGNMENT = 98;
	private static final Map<Skill, String> TRACKED_BOOSTS =
		Map.of(Skill.MAGIC, "boostedMagic", Skill.FARMING, "boostedFarming", Skill.SLAYER, "boostedSlayer",
			Skill.AGILITY, "boostedAgility", Skill.STRENGTH, "boostedStrength");
	private static final Set<String> TRACKED_ASSIGNMENTS = Set.of(
		"callisto", "venenatis", "vetion", "gargoyles", "grotesqueguardians", "hellhounds", "cerberus",
		"hydra", "hydras", "alchemicalhydra", "smokedevils", "thermonuclearsmokedevil", "cavekraken", "kraken",
		"abyssaldemons", "abyssalsire", "spiders", "araxytes", "araxxor", "shellbanegryphons", "shellbanegryphon");
	private final Client client;
	private final GuideCatalog catalogue;
	private final CaDecoder decoder;
	private String session = UUID.randomUUID().toString();
	private LiveSnapshot.Container bank;
	private String bankAccountKey = "";
	private String observedAccountKey = "";
	private List<GuideCatalog.Task> cacheTasks;
	private int[] lastWords;
	private CaDecoder.Result lastCa;
	private int lastMediumThreshold, lastHardThreshold;
	private int cacheRevision;
	private List<Integer> rewardsObserved = List.of();
	private AccountMode lastCaMode;
	private final Set<Integer> relevantVarps = new HashSet<>();
	private Map<String, String> questCache = new HashMap<>();
	private Map<String, Integer> levelCache = new HashMap<>();
	private final Map<Skill, Integer> lastBoosts = new EnumMap<>(Skill.class);
	public RuneLiteCollector(Client client, GuideCatalog catalogue)
	{
		this.client = client;
		this.catalogue = catalogue;
		decoder = new CaDecoder(catalogue);
	}
	public void reset()
	{
		session = UUID.randomUUID().toString();
		bank = null;
		bankAccountKey = "";
		observedAccountKey = "";
		cacheTasks = null;
		lastCa = null;
		lastWords = null;
		rewardsObserved = List.of();
		questCache.clear();
		levelCache.clear();
		lastBoosts.clear();
		wasInHesporiCave = false;
	}
	public String session()
	{
		return session;
	}
	public LiveSnapshot.Container lastBank()
	{
		return bankAccountKey.equals(Long.toUnsignedString(client.getAccountHash()) + "-standard") ? bank : null;
	}
	public void historicalBank(LiveSnapshot.Container old, String owner)
	{
		if (bank == null || !bankAccountKey.equals(owner))
		{
			bank = old;
			bankAccountKey = owner;
		}
	}
	/** Capture real bank updates immediately, including a short visit between refresh ticks. */
	public boolean observeContainer(int containerId, ItemContainer changed)
	{
		if (containerId != InventoryID.BANK || changed == null || client.getGameState() != GameState.LOGGED_IN ||
			client.getAccountHash() == 0 || client.getAccountHash() == -1 ||
			!standardWorld() ||
			!AccountMode.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN)).bankAllowed())
		{
			return false;
		}
		bank = copyContainer(changed, Instant.now());
		bankAccountKey = Long.toUnsignedString(client.getAccountHash()) + "-standard";
		return true;
	}
	boolean standardWorld()
	{
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD &&
			!client.getWorldType().contains(WorldType.HARDCORE_MAIN);
	}

	public LiveSnapshot collect(boolean refreshRequirements)
	{
		return collect(refreshRequirements, refreshRequirements);
	}

	/** Read the region-local patch only after two samples in the cave, with the game view unobstructed. */
	public int hesporiPatch()
	{
		Player player = client.getLocalPlayer();
		WorldPoint location = player == null ? null : player.getWorldLocation();
		boolean inCave = location != null && location.getRegionID() == HESPORI_REGION;
		boolean settled = wasInHesporiCave && inCave;
		wasInHesporiCave = inCave;
		if (!settled || client.getComponentTable() == null)
		{
			return -1;
		}
		for (WidgetNode widget : client.getComponentTable())
		{
			if (widget.getModalMode() != WidgetModalMode.NON_MODAL)
			{
				wasInHesporiCave = false;
				return -1;
			}
		}
		return client.getVarbitValue(VarbitID.FARMING_TRANSMIT_J);
	}
	public LiveSnapshot collect(boolean refreshLevels, boolean refreshQuests)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null ||
			client.getAccountHash() == 0 || client.getAccountHash() == -1)
		{
			return null;
		}
		if (!standardWorld())
		{
			return null;
		}
		String name = client.getLocalPlayer().getName();
		if (name == null || name.trim().isEmpty())
		{
			return null;
		}
		String key = Long.toUnsignedString(client.getAccountHash()) + "-standard";
		resolveRelevantVariables();
		if (!key.equals(observedAccountKey))
		{
			if (!observedAccountKey.isEmpty())
			{
				session = UUID.randomUUID().toString();
				bank = null;
				cacheTasks = null;
				lastCa = null;
				lastWords = null;
				rewardsObserved = List.of();
				questCache.clear();
				levelCache.clear();
				lastBoosts.clear();
			}
			observedAccountKey = key;
			refreshLevels = true;
			refreshQuests = true;
		}
		if (!key.equals(bankAccountKey))
		{
			bank = null;
			bankAccountKey = key;
		}
		AccountMode mode = AccountMode.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN));
		Instant now = Instant.now();
		if (refreshLevels || levelCache.isEmpty())
		{
			Map<String, Integer> levels = new HashMap<>();
			for (Skill skill : Skill.values())
			{
				if (skill.getName().equals("Overall"))
				{
					continue;
				}
				int n = client.getRealSkillLevel(skill);
				if (n > 0 && n <= 126)
				{
					levels.put(skill.getName().toLowerCase(Locale.ROOT).replace(" ", ""), n);
				}
			}
			levelCache = levels;
		}
		if (refreshQuests || questCache.isEmpty())
		{
			Map<String, String> quests = new HashMap<>();
			if (mode.supported())
			{
				for (Quest quest : Quest.values())
				{
					try
					{
						quests.put(quest.getName(), quest.getState(client).name());
					}
					catch (RuntimeException ex)
					{ /* Missing quest data stays absent/unknown. */
					}
				}
			}
			questCache = quests;
		}
		Map<String, LiveSnapshot.Container> containers = new HashMap<>();
		LiveSnapshot.Container inventory = container(InventoryID.INV, now), equipment =
																				container(InventoryID.WORN, now);
		Map<String, Integer> context = new HashMap<>();
		context.put("membersWorld", client.getWorldType().contains(WorldType.MEMBERS) ? 1 : 0);
		try
		{
			context.put("spellbook", client.getVarbitValue(VarbitID.SPELLBOOK));
		}
		catch (RuntimeException ex)
		{ /* Unknown remains absent. */
		}
		if (inventory != null)
		{
			containers.put("inventory", withPouch(inventory, context));
		}
		if (equipment != null)
		{
			containers.put("equipment", equipment);
		}
		Widget bankWidget = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (mode.bankAllowed() && bankWidget != null && !bankWidget.isHidden())
		{
			LiveSnapshot.Container observed = container(InventoryID.BANK, now);
			if (observed != null)
			{
				bank = observed;
			}
		}
		if (mode.bankAllowed() && bank != null)
		{
			containers.put("bank", bank);
		}
		CaDecoder.Result ca = CaDecoder.Result.unavailable("Waiting for the account type");
		if (mode.supported())
		{
			observeAccess(context);
			observeGrandmasterContext(context, mode);
			observeRewardThreshold(context, "caEliteThreshold", VarbitID.CA_THRESHOLD_ELITE);
			observeRewardThreshold(context, "caMasterThreshold", VarbitID.CA_THRESHOLD_MASTER);
			try
			{
				int revision = context.get("caGameBuild");
				if (cacheTasks == null || cacheRevision != revision)
				{
					cacheTasks = readCacheTasks();
					cacheRevision = revision;
					lastCa = null;
				}
				int[] words = new int[CA_VARPS.length];
				for (int i = 0; i < words.length; i++)
				{
					words[i] = client.getServerVarpValue(CA_VARPS[i]);
				}
				int threshold = client.getServerVarbitValue(VarbitID.CA_THRESHOLD_MEDIUM),
					hardThreshold = client.getServerVarbitValue(VarbitID.CA_THRESHOLD_HARD),
					points = client.getServerVarbitValue(VarbitID.CA_POINTS);
				context.put("caMediumThreshold", threshold);
				context.put("caHardThreshold", hardThreshold);
				context.put("caServerPoints", points);
				context.put("caLiveTaskCount", cacheTasks.size());
				if (lastCa != null && lastCa.verifiedFor(RewardGoal.MASTER) && mode == lastCaMode &&
					threshold == lastMediumThreshold && hardThreshold == lastHardThreshold && points == lastCa.gamePoints &&
					Arrays.equals(words, lastWords))
				{
					ca = lastCa;
				}
				else
				{
					ca = decoder.decode(cacheTasks, words, threshold, hardThreshold, points, mode.groupIronman());
				}
				lastCa = ca;
				lastWords = words;
				lastMediumThreshold = threshold;
				lastHardThreshold = hardThreshold;
				lastCaMode = mode;
				if (!ca.verifiedFor(RewardGoal.MASTER))
				{
					cacheTasks = null;
				}
			}
			catch (RuntimeException ex)
			{
				cacheTasks = null;
				ca = CaDecoder.Result.unavailable(
					"Achievement data is not ready or has changed; saved progress retained.");
			}
		}
		return new LiveSnapshot(key, name, session, mode, now, levelCache, questCache, containers, ca, context);
	}

	private void observeGrandmasterContext(Map<String, Integer> context, AccountMode mode)
	{
		int revision = client.getRevision();
		context.put("caGameBuild", revision);
		int target = context.getOrDefault("caGrandmasterThreshold", 0);
		List<Integer> scope = List.of(revision, mode.ordinal(), target, context.getOrDefault("caGroupSize", 0),
			context.getOrDefault("caGroupMember", 0), context.getOrDefault("caGroupPrestige", 0),
			context.getOrDefault("membersWorld", 0));
		Widget rewards = client.getWidget(InterfaceID.CaRewards.FRAME);
		if (target > 0 && rewards != null && !rewards.isHidden())
		{
			rewardsObserved = scope;
		}
		else if (!scope.equals(rewardsObserved))
		{
			rewardsObserved = List.of();
		}
		context.put("caRewardsObserved", scope.equals(rewardsObserved) ? 1 : 0);
		// The lower bound remains safe on clients whose first counter already includes the variant.
		context.put("kc:chambers-combined", Math.max(context.getOrDefault("kc:chambers", 0),
			context.getOrDefault("kc:chambers-cm", 0)));
		context.put("kc:theatre-combined", Math.max(context.getOrDefault("kc:theatre-normal", 0),
			context.getOrDefault("kc:theatre-hard", 0)));
	}
	private void observeRewardThreshold(Map<String, Integer> context, String key, int id)
	{
		try
		{
			context.put(key, client.getServerVarbitValue(id));
		}
		catch (RuntimeException ex)
		{
			// The individual target can be unavailable even when the task flags match.
		}
	}

	private void observeAccess(Map<String, Integer> context)
	{
		ACCESS_VARPS.forEach((id, key) ->
		{
			try
			{
				int value = client.getServerVarpValue(id);
				if (value >= 0)
				{
					context.put(key, value);
				}
			}
			catch (RuntimeException ex)
			{
				// This check can use other current account evidence.
			}
		});
		ACCESS_VARBITS.forEach((id, key) ->
		{
			try
			{
				int value = client.getServerVarbitValue(id);
				if (value >= 0)
				{
					context.put(key, value);
				}
			}
			catch (RuntimeException ex)
			{
				// Leave an unavailable flag unknown.
			}
		});
		TRACKED_BOOSTS.forEach((skill, key) -> observeBoost(skill, key, context));
		observeSlayer(context);
	}

	private void observeBoost(Skill skill, String key, Map<String, Integer> context)
	{
		try
		{
			int level = client.getBoostedSkillLevel(skill);
			lastBoosts.put(skill, level);
			if (level > 0)
			{
				context.put(key, level);
			}
		}
		catch (RuntimeException ex)
		{
			// The real level remains available.
		}
	}

	private void observeSlayer(Map<String, Integer> context)
	{
		try
		{
			int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
			context.put("slayerRemaining", remaining);
			if (remaining <= 0)
			{
				context.put("slayerObserved", 1);
				return;
			}
			int type = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
			boolean boss = type == BOSS_ASSIGNMENT;
			List<Integer> rows = boss ? client.getDBRowsByValue(SLAYER_BOSS_TABLE, 1, 0,
				client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID)) : client.getDBRowsByValue(SLAYER_TASK_TABLE, 0, 0, type);
			if (rows == null || rows.size() != 1)
			{
				return;
			}
			int row = rows.get(0);
			if (boss)
			{
				row = (Integer) client.getDBTableField(row, 4, 0)[0];
			}
			String name = ((String) client.getDBTableField(row, 10, 0)[0]).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
			if (TRACKED_ASSIGNMENTS.contains(name))
			{
				context.put((boss ? "bossSlayer:" : "slayer:") + name, 1);
			}
			context.put("slayerObserved", 1);
		}
		catch (RuntimeException ex)
		{
			// A changed or unavailable Slayer row leaves the current assignment unknown.
		}
	}

	private LiveSnapshot.Container container(int id, Instant now)
	{
		ItemContainer c = client.getItemContainer(id);
		if (c == null)
		{
			return null;
		}
		return copyContainer(c, now);
	}
	private LiveSnapshot.Container copyContainer(ItemContainer c, Instant now)
	{
		List<LiveSnapshot.Item> rows = new ArrayList<>();
		Item[] items = c.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				rows.add(new LiveSnapshot.Item(item.getId(), item.getQuantity(), slot));
			}
		}
		return new LiveSnapshot.Container(session, now, rows);
	}
	private LiveSnapshot.Container withPouch(LiveSnapshot.Container inventory, Map<String, Integer> context)
	{
		boolean held = false;
		for (LiveSnapshot.Item row : inventory.items)
		{
			if (row.id == ItemID.BH_RUNE_POUCH || row.id == ItemID.BH_RUNE_POUCH_TROUVER ||
				row.id == ItemID.DIVINE_RUNE_POUCH || row.id == ItemID.DIVINE_RUNE_POUCH_TROUVER)
			{
				held = true;
			}
		}
		if (!held)
		{
			return inventory;
		}
		int[] types = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
						VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
		int[] quantities = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
							VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4,
							VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};
		List<LiveSnapshot.Item> rows = new ArrayList<>(inventory.items);
		try
		{
			EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
			if (runes == null)
			{
				throw new IllegalStateException("Pouch rune table unavailable");
			}
			for (int slot = 0; slot < types.length; slot++)
			{
				int type = client.getVarbitValue(types[slot]), quantity = client.getVarbitValue(quantities[slot]);
				if (type == 0 || quantity <= 0)
				{
					continue;
				}
				int id = runes.getIntValue(type);
				if (id <= 0)
				{
					throw new IllegalStateException("Pouch rune mapping unavailable");
				}
				rows.add(new LiveSnapshot.Item(id, quantity, -2));
			}
			return new LiveSnapshot.Container(inventory.session, inventory.at, rows);
		}
		catch (RuntimeException ex)
		{
			context.put("pouchUnknown", 1);
			return inventory;
		}
	}
	private List<GuideCatalog.Task> readCacheTasks()
	{
		List<GuideCatalog.Task> tasks = new ArrayList<>();
		String[] tiers = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};
		for (int tier = 0; tier < tiers.length; tier++)
		{
			EnumComposition table = client.getEnum(3981 + tier);
			if (table == null || table.getIntVals() == null)
			{
				throw new IllegalStateException("Task table unavailable");
			}
			for (int structID : table.getIntVals())
			{
				StructComposition struct = client.getStructComposition(structID);
				tasks.add(new GuideCatalog.Task(struct.getIntValue(1306), struct.getStringValue(1308), tiers[tier],
					tier + 1, struct.getIntValue(1425)));
			}
		}
		return tasks;
	}
	private void resolveRelevantVariables()
	{
		if (!relevantVarps.isEmpty())
		{
			return;
		}
		for (int id : RELEVANT_VARBITS)
		{
			VarbitComposition definition = client.getVarbit(id);
			if (definition == null)
			{
				if (id == VarbitID.CA_THRESHOLD_ELITE || id == VarbitID.CA_THRESHOLD_MASTER)
				{
					continue;
				}
				relevantVarps.clear();
				return;
			}
			relevantVarps.add(definition.getIndex());
		}
		for (int id : ACCESS_VARBITS.keySet())
		{
			VarbitComposition definition = client.getVarbit(id);
			if (definition != null)
			{
				relevantVarps.add(definition.getIndex());
			}
		}
		relevantVarps.addAll(ACCESS_VARPS.keySet());
		relevantVarps.add(VarPlayerID.SLAYER_COUNT);
		relevantVarps.add(VarPlayerID.SLAYER_TARGET);
		VarbitComposition bossAssignment = client.getVarbit(VarbitID.SLAYER_TARGET_BOSSID);
		if (bossAssignment != null)
		{
			relevantVarps.add(bossAssignment.getIndex());
		}
	}
	public boolean relevantVariable(int varp, int varbit)
	{
		if (achievementVarp(varp) || relevantVarps.contains(varp) || ACCESS_VARPS.containsKey(varp) ||
			ACCESS_VARBITS.containsKey(varbit) || varp == VarPlayerID.SLAYER_COUNT ||
			varp == VarPlayerID.SLAYER_TARGET || varbit == VarbitID.SLAYER_TARGET_BOSSID)
		{
			return true;
		}
		for (int id : RELEVANT_VARBITS)
		{
			if (varbit == id)
			{
				return true;
			}
		}
		return false;
	}
	public boolean levelChanged(Skill skill, int level, int boostedLevel)
	{
		String key = skill.getName().toLowerCase(Locale.ROOT).replace(" ", "");
		return !Objects.equals(levelCache.get(key), level) ||
			TRACKED_BOOSTS.containsKey(skill) && !Objects.equals(lastBoosts.get(skill), boostedLevel);
	}
	public static boolean relevantContainer(int id)
	{
		return id == InventoryID.BANK || id == InventoryID.INV || id == InventoryID.WORN;
	}
	public static boolean achievementVarp(int id)
	{
		for (int varp : CA_VARPS)
		{
			if (varp == id)
			{
				return true;
			}
		}
		return false;
	}
}
