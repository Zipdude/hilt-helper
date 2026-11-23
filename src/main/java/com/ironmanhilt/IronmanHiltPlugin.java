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
package com.ironmanhilt;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ironmanhilt.core.GuideCatalog;
import com.ironmanhilt.core.GuideState;
import com.ironmanhilt.core.HesporiPatch;
import com.ironmanhilt.core.LiveSnapshot;
import com.ironmanhilt.core.Migration;
import com.ironmanhilt.core.Planner;
import com.ironmanhilt.core.ProgressEngine;
import com.ironmanhilt.core.ReadinessEngine;
import com.ironmanhilt.core.RiskPolicy;
import com.ironmanhilt.core.RewardGoal;
import com.ironmanhilt.core.RouteStyle;
import com.ironmanhilt.storage.LocalFiles;
import com.ironmanhilt.storage.ProgressStore;
import com.ironmanhilt.storage.SerialExecutor;
import com.ironmanhilt.ui.GuidePanel;
import com.ironmanhilt.ui.TaskOverlay;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(name = "Hilt Helper", internalName = "ironman-hilt-guide",
					description = "Track your OSRS Combat Achievements and plan Medium and Hard routes to Ghommal's hilt 2 and hilt 3.",
					tags = {"ironman", "combat", "achievements", "progression", "guide", "medium", "hard", "hilt",
						"ca", "cas", "tracker", "planner", "pvm", "ghommal", "osrs", "route", "combat-achievements"})
public final class IronmanHiltPlugin extends Plugin implements GuidePanel.Actions
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ClientToolbar toolbar;
	@Inject private OverlayManager overlayManager;
	@Inject private Notifier notifier;
	@Inject private ScheduledExecutorService executor;
	@Inject private Gson gson;

	private GuideCatalog catalogue;
	private ProgressEngine progress;
	private ReadinessEngine readiness;
	private Planner planner;
	private Migration migration;
	private ProgressStore store;
	private SerialExecutor io;
	private RuneLiteCollector collector;
	private GuideState state = GuideState.empty();
	private LiveSnapshot live;
	private String activeKey = "", status = GuidePanel.SIGN_IN, message = "";
	private boolean loading, blockedSave, recovered, saveFailed, dirty = true, levelsDirty = true, transaction;
	private boolean bankRefreshPending;
	private int ticks, sessionTicks, lastCollect = -100, lastRequirements = -100;
	private long lastSaveMillis;
	private GuideState lastQueuedState;
	private LiveSnapshot.Container lastQueuedBank;
	private volatile boolean enabled;
	private volatile long generation;
	private volatile GuidePanel panel;
	private NavigationButton navigation;
	private volatile TaskOverlay taskOverlay;
	// Only this small mailbox crosses client-thread / EDT boundaries. One pending render maximum.
	private ViewUpdate pendingView;
	private GuideState publishedState;
	private boolean viewQueued;

	@Override protected void startUp() throws Exception
	{
		BufferedImage icon = ImageUtil.loadImageResource(IronmanHiltPlugin.class, "hilt-icon.png");
		catalogue = new GuideCatalog(gson);
		progress = new ProgressEngine(catalogue);
		readiness = new ReadinessEngine(catalogue);
		planner = new Planner(catalogue, progress, readiness);
		migration = new Migration(gson, progress);
		// Retain the same serial queue over toggles, so an old account write precedes its reload.
		if (io == null)
		{
			io = new SerialExecutor(executor);
		}
		enabled = true;
		long started = ++generation;
		panel = new GuidePanel(catalogue, progress, readiness, planner, this);
		panel.onDeactivate();
		taskOverlay = new TaskOverlay(this);
		overlayManager.add(taskOverlay);
		navigation =
			NavigationButton.builder().tooltip("Hilt Helper").icon(icon).priority(7).panel(panel).build();
		toolbar.addNavigation(navigation);
		clientThread.invokeLater(() ->
		{
			if (!enabled || generation != started)
			{
				return;
			}
			collector = new RuneLiteCollector(client, catalogue);
			// Plugin directory creation and all storage operations occur on the supplied worker.
			ProgressStore.Files files = new ProgressStore.Files()
			{
				private LocalFiles local;
				private LocalFiles local() throws IOException
				{
					if (local == null)
					{
						local = new LocalFiles(getPluginDirectory());
					}
					return local;
				}
				public String read(String name) throws IOException
				{
					return local().read(name);
				}
				public void write(String name, String text, boolean previous) throws IOException
				{
					local().write(name, text, previous);
				}
			};
			store = new ProgressStore(gson, files);
			resetSession();
		});
	}

	@Override protected void shutDown()
	{
		enabled = false;
		long stopped = ++generation;
		if (panel != null)
		{
			panel.close();
		}
		if (navigation != null)
		{
			toolbar.removeNavigation(navigation);
		}
		TaskOverlay overlay = taskOverlay;
		if (overlay != null)
		{
			overlay.clear();
			overlayManager.remove(overlay);
			taskOverlay = null;
		}
		panel = null;
		navigation = null;
		synchronized (this)
		{
			pendingView = null;
		}
		clientThread.invokeLater(() ->
		{
			if (generation != stopped)
			{
				return;
			}
			if (!loading && !transaction)
			{
				save(true);
			}
			collector = null;
			live = null;
			activeKey = "";
		});
	}

	@Subscribe public void onGameStateChanged(GameStateChanged event)
	{
		if (!enabled)
		{
			return;
		}
		switch (event.getGameState())
		{
		case LOGIN_SCREEN:
		case LOGIN_SCREEN_AUTHENTICATOR:
		case HOPPING:
		case LOGGING_IN:
		case CONNECTION_LOST:
			resetSession();
			break;
		case LOGGED_IN:
			dirty = true;
			break;
		default:
			break;
		}
	}

	@Subscribe public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (enabled)
		{
			resetSession();
		}
	}
	@Subscribe public void onAccountHashChanged(AccountHashChanged event)
	{
		if (enabled)
		{
			resetSession();
		}
	}

	private void resetSession()
	{
		if (collector == null)
		{
			return;
		}
		collector.reset();
		live = null;
		sessionTicks = 0;
		lastCollect = lastRequirements = -100;
		dirty = levelsDirty = true;
		activeKey = "";
		state = GuideState.empty();
		loading = blockedSave = recovered = saveFailed = transaction = false;
		bankRefreshPending = false;
		lastQueuedState = null;
		lastQueuedBank = null;
		publishedState = null;
		status = client.getGameState() == GameState.LOGIN_SCREEN ||
			client.getGameState() == GameState.LOGIN_SCREEN_AUTHENTICATOR ? GuidePanel.SIGN_IN : "";
		message = "";
		publish();
	}

	@Subscribe public void onStatChanged(StatChanged event)
	{
		if (enabled && collector != null && collector.levelChanged(event.getSkill(), event.getLevel()))
		{
			dirty = levelsDirty = true;
		}
	}

	@Subscribe public void onVarbitChanged(VarbitChanged event)
	{
		if (enabled && collector != null && collector.relevantVariable(event.getVarpId(), event.getVarbitId()))
		{
			dirty = true;
		}
	}

	@Subscribe public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!enabled || collector == null || !RuneLiteCollector.relevantContainer(event.getContainerId()))
		{
			return;
		}
		// Capture bank updates immediately, even if the bank closes before the next refresh.
		bankRefreshPending |= collector.observeContainer(event.getContainerId(), event.getItemContainer());
		dirty = true;
	}

	@Subscribe public void onWidgetLoaded(WidgetLoaded event)
	{
		if (enabled && event.getGroupId() == InterfaceID.BANKMAIN && live != null && live.mode.bankAllowed())
		{
			dirty = bankRefreshPending = true;
		}
	}

	@Subscribe public void onGameTick(GameTick event)
	{
		if (!enabled || collector == null)
		{
			return;
		}
		ticks++;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		sessionTicks++;
		if (ticks % 5 == 0)
		{
			updateHesporiPatch();
		}
		if (sessionTicks < 3 || loading || transaction || ticks - lastCollect < 2)
		{
			return;
		}
		boolean fallback = ticks - lastRequirements >= 50;
		if (!dirty && !fallback)
		{
			return;
		}
		if (dirty && !bankRefreshPending && ticks - lastCollect < 10 && live != null)
		{
			return;
		}
		lastCollect = ticks;
		try
		{
			// Quest states use a client script without a supported variable-to-quest map.
			// Refresh them at login/manual refresh and every 30s, independently of event floods.
			LiveSnapshot next = collector.collect(levelsDirty || fallback, fallback);
			if (fallback)
			{
				lastRequirements = ticks;
			}
			bankRefreshPending = levelsDirty = dirty = false;
			if (next == null)
			{
				if (!activeKey.isEmpty())
				{
					resetSession();
				}
				live = null;
				dirty = RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
				status = dirty ? "" : "Switch to a standard world to use the guide.";
				publish();
				return;
			}
			live = next;
			if (!next.mode.supported())
			{
				status = "Account type unavailable. Try refreshing game data.";
				dirty = true;
				publish();
				return;
			}
			if (!activeKey.equals(next.accountKey))
			{
				load(next);
				return;
			}
			if (!blockedSave)
			{
				applyLive();
			}
			publish();
		}
		catch (RuntimeException failure)
		{
			dirty = true;
			status = "Game data couldn’t be read. Try refreshing.";
			publish();
		}
	}

	private void load(LiveSnapshot snapshot)
	{
		loading = true;
		status = "";
		publish();
		String key = snapshot.accountKey, session = snapshot.session;
		long lifetime = generation;
		ProgressStore storage = store;
		io.execute(() ->
		{
			try
			{
				ProgressStore.Loaded loaded = storage.load(key);
				clientThread.invokeLater(() ->
				{
					if (!current(lifetime, session, key))
					{
						return;
					}
					activeKey = key;
					state = loaded.state;
					recovered = loaded.recovered;
					blockedSave = loading = false;
					message = loaded.recovered ? "Previous save recovered. Review your points and route." : "";
					collector.historicalBank(loaded.bank, key);
					live = live.withBank(collector.lastBank());
					dirty = true;
					lastQueuedState = null;
					applyLive();
					publish();
				});
			}
			catch (IOException | RuntimeException failure)
			{
				loadFailed(lifetime, session, key);
			}
		}, failure -> loadFailed(lifetime, session, key));
	}

	private void updateHesporiPatch()
	{
		if (!canChange())
		{
			return;
		}
		int observed = collector.hesporiPatch();
		boolean reminders = HesporiPatch.enabled(state);
		if ((observed < 0 && !reminders) || !planner.hasUnfinishedHespori(state, live))
		{
			return;
		}
		Instant now = Instant.now();
		boolean changed = HesporiPatch.observe(state, observed, now);
		HesporiPatch.Status patch = HesporiPatch.status(state, now);
		if (reminders && patch.canNotify() && !patch.name().equals(ReadinessEngine.str(state.data, "hesporiNotice", "")))
		{
			state.data.addProperty("hesporiNotice", patch.name());
			changed = true;
			notifier.notify("Hilt Helper · Hespori: " + patch.detail);
		}
		if (changed)
		{
			save(false);
			publish();
		}
	}

	private void loadFailed(long lifetime, String session, String key)
	{
		clientThread.invokeLater(() ->
		{
			if (!current(lifetime, session, key))
			{
				return;
			}
			activeKey = key;
			state = GuideState.empty();
			loading = false;
			blockedSave = recovered = true;
		status = "Your save couldn’t be opened. Import a backup from Account & backups.";
		message = "";
			publish();
		});
	}

	private void applyLive()
	{
		if (live == null || !live.accountKey.equals(activeKey) || !live.mode.supported() || blockedSave)
		{
			return;
		}
		state.data.addProperty("username", live.name);
		state.data.addProperty("mode", live.mode.key);
		if (live.ca.valid)
		{
			progress.merge(state, live.ca.completed, live.ca.observed);
			if (live.ca.completed.contains(state.screenTask()))
			{
				state.data.remove("screenTask");
			}
		}
		status = live.ca.valid ? "" : "Achievements couldn’t refresh. Try again or log back in.";
		JsonObject observed = new JsonObject();
		observed.addProperty("at", live.at.toString());
		observed.addProperty("session", live.session);
		observed.addProperty("validAchievements", live.ca.valid);
		observed.add("levels", gson.toJsonTree(live.levels));
		observed.add("quests", gson.toJsonTree(live.quests));
		state.data.add("nativeLastObservation", observed);
		save(false);
	}

	private void save(boolean force)
	{
		if (activeKey.isEmpty() || blockedSave || loading || live == null)
		{
			return;
		}
		LiveSnapshot.Container bank = collector == null ? null : collector.lastBank();
		long now = System.currentTimeMillis();
		boolean sameBank = bank == null ? lastQueuedBank == null : bank.sameContents(lastQueuedBank);
		if (!force && state.sameProgress(lastQueuedState) && sameBank && now - lastSaveMillis < 30000)
		{
			return;
		}
		String key = activeKey, name = live.name, session = live.session;
		long lifetime = generation;
		GuideState snapshot = state.copy();
		boolean wasRecovered = recovered;
		ProgressStore storage = store;
		lastQueuedState = snapshot;
		lastQueuedBank = bank;
		lastSaveMillis = now;
		recovered = false;
		Runnable failed = () -> clientThread.invokeLater(() ->
		{
			if (!current(lifetime, session, key))
			{
				return;
			}
			lastQueuedState = null;
			recovered |= wasRecovered;
			saveFailed = true;
			publish();
		});
		io.executeLatest(key, () ->
		{
			try
			{
				storage.save(key, name, snapshot, bank, wasRecovered);
				clientThread.invokeLater(() ->
				{
					if (saveFailed && current(lifetime, session, key))
					{
						saveFailed = false;
						publish();
					}
				});
			}
			catch (IOException | RuntimeException failure)
			{
				failed.run();
			}
		}, failure -> failed.run());
	}

	private void publish()
	{
		if (!enabled || panel == null)
		{
			return;
		}
		boolean available = live != null && live.mode.supported() && live.accountKey.equals(activeKey) &&
			!loading && !blockedSave;
		if (publishedState == null || !state.sameProgress(publishedState))
		{
			publishedState = state.copy();
		}
		publishTaskNote(available);
		String notice = saveFailed ? "Couldn’t save progress. Export a backup while the guide retries." : message;
		ViewUpdate view = new ViewUpdate(generation, available ? publishedState : GuideState.empty(), live, status,
			notice, available);
		synchronized (this)
		{
			pendingView = view;
			if (viewQueued)
			{
				return;
			}
			viewQueued = true;
		}
		SwingUtilities.invokeLater(this::renderPending);
	}

	private void publishTaskNote(boolean available)
	{
		TaskOverlay overlay = taskOverlay;
		if (overlay == null)
		{
			return;
		}
		int id = state.screenTask();
		String activity = catalogue.owner(id);
		if (!available || !catalogue.includes(id, state.rewardGoal()) || state.routeChoice() == null || state.skipped(activity) ||
			state.knownIds("skipTaskIDs", catalogue.tasks.keySet()).contains(id) ||
			progress.knownCompleted(state).contains(id) || (RiskPolicy.wilderness(activity) && !state.wildernessEnabled()) ||
			(catalogue.needsGroup(id, state.rewardGoal()) && state.routeChoice() != RouteStyle.GROUP))
		{
			overlay.clear();
			return;
		}
		JsonObject task = catalogue.rule(id);
		String kit = "";
		for (JsonElement value : catalogue.kit(activity))
		{
			JsonObject requirement = value.getAsJsonObject();
			String role = ReadinessEngine.str(requirement, "role", "");
			if ((role.equals("required_strategy") || role.equals("required_task")) &&
				(!requirement.has("task_ids") || ReadinessEngine.ints(requirement.getAsJsonArray("task_ids")).contains(id)))
			{
				kit = "Check kit: " + requirement.get("label").getAsString();
				break;
			}
		}
		overlay.show(task.get("name").getAsString(), task.get("condition").getAsString(), kit);
	}

	private void renderPending()
	{
		ViewUpdate view;
		synchronized (this)
		{
			view = pendingView;
			pendingView = null;
			viewQueued = false;
		}
		if (view != null && enabled && view.generation == generation && panel != null)
		{
			panel.render(view.state, view.live, view.status, view.message, view.available);
		}
	}

	private boolean current(long lifetime, String session, String key)
	{
		return enabled && generation == lifetime && collector != null && collector.session().equals(session) &&
			live != null && live.accountKey.equals(key) && live.session.equals(session) &&
			client.getGameState() == GameState.LOGGED_IN &&
			key.equals(Long.toUnsignedString(client.getAccountHash()) + "-standard") &&
			RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	private boolean canChange()
	{
		return live != null && live.mode.supported() && activeKey.equals(live.accountKey) && !loading && !blockedSave &&
			!transaction && current(generation, live.session, activeKey);
	}

	private String intendedSession()
	{
		GuidePanel current = panel;
		return current == null ? "" : current.session();
	}

	private void mutate(Consumer<GuideState> action)
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!canChange() || generation != lifetime || !live.session.equals(intended))
			{
				return;
			}
			action.accept(state);
			save(true);
			publish();
		});
	}

	@Override public void manual(int id, boolean complete)
	{
		mutate(s ->
		{
			if (catalogue.tasks.containsKey(id))
			{
				s.manualTask(id, complete, Instant.now());
				if (complete && s.screenTask() == id)
				{
					s.data.remove("screenTask");
				}
			}
		});
	}
	@Override public void selectGuide(RewardGoal goal)
	{
		mutate(s ->
		{
			s.data.addProperty("guideGoal", goal.key);
			if (!catalogue.includes(s.screenTask(), goal) ||
				(catalogue.needsGroup(s.screenTask(), goal) && s.routeChoice() != RouteStyle.GROUP))
			{
				s.data.remove("screenTask");
			}
		});
	}

	@Override public void routeStyle(RouteStyle choice)
	{
		mutate(s ->
		{
			s.data.addProperty("routeStyle", choice.key);
			if (catalogue.needsGroup(s.screenTask(), s.rewardGoal()) && choice == RouteStyle.SOLO)
			{
				s.data.remove("screenTask");
			}
		});
	}

	@Override public void hesporiReminder(boolean enable)
	{
		mutate(s -> s.data.addProperty("hesporiReminder", enable));
	}
	@Override public void screenTask(int id)
	{
		mutate(s ->
		{
			if (id == -1)
			{
				s.data.remove("screenTask");
			}
			else if (catalogue.includes(id, s.rewardGoal()) && !progress.knownCompleted(s).contains(id))
			{
				s.data.addProperty("screenTask", id);
			}
		});
	}
	@Override public void skip(String activity, boolean skip)
	{
		mutate(s ->
		{
			catalogue.activity(activity);
			s.skip(activity, skip);
			if (skip && activity.equals(catalogue.owner(s.screenTask())))
			{
				s.data.remove("screenTask");
			}
		});
	}
	@Override public void skipTask(int id, boolean skip)
	{
		mutate(s ->
		{
			if (catalogue.rule(id) != null)
			{
				s.skipTask(id, skip);
				if (skip && s.screenTask() == id)
				{
					s.data.remove("screenTask");
				}
			}
		});
	}
	@Override public void follow(String activity)
	{
		mutate(s ->
		{
			if (activity.isEmpty())
			{
				s.data.remove("pinnedActivity");
			}
			else
			{
				catalogue.activity(activity);
				s.data.addProperty("pinnedActivity", activity);
			}
		});
	}

	@Override public void wilderness(boolean value)
	{
		mutate(s ->
		{
			s.data.addProperty("wild", value);
			String active = catalogue.owner(s.screenTask());
			if (!value && active != null && RiskPolicy.wilderness(active))
			{
				s.data.remove("screenTask");
			}
		});
	}

	@Override public void correction(String category, String key, JsonElement value)
	{
		if (!Arrays.asList("levels", "quests", "items", "overrides").contains(category))
		{
			return;
		}
		mutate(s ->
		{
			GuideState checked = s.copy();
			if (value == null)
			{
				checked.object(category).remove(key);
			}
			else
			{
				checked.object(category).add(key, value.deepCopy());
			}
			GuideState.validate(checked.data);
			if (value == null)
			{
				s.object(category).remove(key);
			}
			else
			{
				s.object(category).add(key, value.deepCopy());
			}
			if (value == null)
			{
				s.object("correctionDates").remove(category + ":" + key);
			}
			else
			{
				s.object("correctionDates").addProperty(category + ":" + key, Instant.now().toString());
			}
		});
	}

	@Override public void reconcile()
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!canChange() || generation != lifetime || !live.session.equals(intended) || !live.ca.valid)
			{
				return;
			}
			GuideState before = state.copy(), next = progress.reconcile(state, live.ca);
			String key = activeKey, name = live.name, session = live.session;
			LiveSnapshot.Container bank = collector.lastBank();
			ProgressStore storage = store;
			transaction = true;
			publish();
			io.execute(
				()
					->
					{
					try
					{
						storage.archiveReconciliation(key, before);
						storage.save(key, name, next, bank, false);
						finishTransaction(lifetime, session, key, next,
											"Progress updated to match the game.",
											false);
					}
					catch (IOException | RuntimeException failure)
					{
						transactionFailed(lifetime, session, key,
											"Couldn’t update saved progress. Try again.");
					}
				},
				failure
				-> transactionFailed(lifetime, session, key,
										"Couldn’t update saved progress. Try again."));
		});
	}

	@Override public void refresh()
	{
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!enabled || generation != lifetime)
			{
				return;
			}
			dirty = levelsDirty = true;
			lastCollect = lastRequirements = -100;
		});
	}

	@Override public void importFile(Filepath file)
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!enabled || generation != lifetime || live == null || !live.session.equals(intended) ||
				!live.mode.supported() || loading || transaction)
			{
				return;
			}
			String key = live.accountKey, name = live.name, session = live.session;
			Migration importer = migration;
			io.execute(
				()
					->
					{
					try
					{
						if (file.size() > 5_000_000)
						{
							throw new IOException("Progress backup exceeds 5 MB");
						}
						StringBuilder text = new StringBuilder();
						try (Reader reader = file.openBufferedReader())
						{
							char[] buffer = new char[8192];
							int length;
							while ((length = reader.read(buffer)) != -1)
							{
								if (text.length() + length > 5_000_000)
								{
									throw new IOException("Progress backup exceeds 5 MB");
								}
								text.append(buffer, 0, length);
							}
						}
						Migration.Preview preview = importer.preview(text.toString(), key, name);
						clientThread.invokeLater(() ->
						{
							if (!current(lifetime, session, key))
							{
								return;
							}
							SwingUtilities.invokeLater(() ->
							{
								GuidePanel current = panel;
								if (enabled && generation == lifetime && current != null &&
									current.session().equals(session))
								{
									current.preview(preview);
								}
							});
						});
					}
					catch (IOException | RuntimeException failure)
					{
						report(lifetime, session, key, "Couldn’t read this backup. Check that it belongs to this account.");
					}
				},
				failure
				-> report(lifetime, session, key, "Import could not start. Try again when storage is available."));
		});
	}

	@Override public void applyImport(Migration.Preview preview, boolean associated)
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!enabled || generation != lifetime || live == null || !live.session.equals(intended) ||
				!live.mode.supported() || loading || transaction)
			{
				return;
			}
			String key = activeKey, name = live.name, session = live.session;
			if (!preview.targetKey.equals(live.accountKey) || !preview.targetKey.equals(key))
			{
				return;
			}
			try
			{
				if (state.object("importReceipts").has(preview.hash))
				{
					report(lifetime, session, key, "This backup was already imported.");
					return;
				}
				GuideState next = migration.merge(preview, state, key, associated, Instant.now()), before =
																										state.copy();
				LiveSnapshot.Container bank = collector.lastBank();
				boolean wasRecovered = recovered;
				ProgressStore storage = store;
				transaction = true;
				publish();
				io.execute(
					()
						->
						{
						try
						{
							storage.archiveImport(key, name, before, bank, preview);
							storage.save(key, name, next, bank, wasRecovered);
							finishTransaction(
								lifetime, session, key, next,
								"Progress imported. You can undo it from Account & backups.", true);
						}
						catch (IOException | RuntimeException failure)
						{
							transactionFailed(lifetime, session, key,
												"Couldn’t save the import. Check your backup and try again.");
						}
					},
					failure
					-> transactionFailed(lifetime, session, key, "Import could not start; earlier progress retained."));
			}
			catch (RuntimeException failure)
			{
				report(lifetime, session, key, "Couldn’t read this backup. Check that it belongs to this account.");
			}
		});
	}

	private void finishTransaction(long lifetime, String session, String key, GuideState next, String text,
									boolean imported)
	{
		clientThread.invokeLater(() ->
		{
			if (!current(lifetime, session, key))
			{
				return;
			}
			transaction = blockedSave = recovered = saveFailed = false;
			state = next;
			message = text;
			dirty = true;
			lastQueuedState = null;
			applyLive();
			publish();
			if (imported)
			{
				SwingUtilities.invokeLater(() ->
				{
					GuidePanel current = panel;
					if (enabled && generation == lifetime && current != null && current.session().equals(session))
					{
						current.importFinished();
					}
				});
			}
		});
	}

	private void transactionFailed(long lifetime, String session, String key, String text)
	{
		clientThread.invokeLater(() ->
		{
			if (!current(lifetime, session, key))
			{
				return;
			}
			transaction = false;
			message = text;
			publish();
		});
	}

	@Override public void exportFile(Filepath file)
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!canChange() || generation != lifetime || !live.session.equals(intended))
			{
				return;
			}
			String key = activeKey, session = live.session;
			GuideState snapshot = state.copy();
			ProgressStore storage = store;
			io.execute(() ->
			{
				try
				{
					file.write(storage.export(key, snapshot));
					report(lifetime, session, key, "Progress backup exported.");
				}
				catch (IOException | RuntimeException failure)
				{
					report(lifetime, session, key, "Couldn’t export the backup. Try another folder.");
				}
			}, failure -> report(lifetime, session, key, "Couldn’t export the backup. Try again."));
		});
	}

	@Override public void undoImport()
	{
		String intended = intendedSession();
		long lifetime = generation;
		clientThread.invokeLater(() ->
		{
			if (!canChange() || generation != lifetime || !live.session.equals(intended))
			{
				return;
			}
			String key = activeKey, name = live.name, session = live.session;
			LiveSnapshot.Container bank = collector.lastBank();
			ProgressStore storage = store;
			transaction = true;
			publish();
			io.execute(
				()
					->
					{
					try
					{
						ProgressStore.Loaded previous = storage.beforeImport(key);
						storage.save(key, name, previous.state, bank, false);
						finishTransaction(lifetime, session, key, previous.state, previous.warning, false);
					}
					catch (IOException | RuntimeException failure)
					{
						transactionFailed(lifetime, session, key, "Couldn’t undo the import. Check that this account has an earlier import.");
					}
				},
				failure
				-> transactionFailed(lifetime, session, key, "Rollback could not start; saved progress was retained."));
		});
	}

	@Override public void clearCorrections()
	{
		mutate(s ->
		{
			JsonObject old = new JsonObject();
			old.addProperty("at", Instant.now().toString());
			for (String key : new String[] {"levels", "quests", "items", "overrides", "correctionDates"})
			{
				old.add(key, s.object(key).deepCopy());
				s.data.add(key, new JsonObject());
			}
			s.data.add("clearedCorrectionsBackup", old);
			message = "Preparation corrections cleared.";
		});
	}

	private void report(long lifetime, String session, String key, String text)
	{
		clientThread.invokeLater(() ->
		{
			if (!current(lifetime, session, key))
			{
				return;
			}
			message = text;
			publish();
		});
	}

	private static final class ViewUpdate
	{
		private final long generation;
		private final GuideState state;
		private final LiveSnapshot live;
		private final String status, message;
		private final boolean available;

		private ViewUpdate(long generation, GuideState state, LiveSnapshot live, String status, String message,
							boolean available)
		{
			this.generation = generation;
			this.state = state;
			this.live = live;
			this.status = status;
			this.message = message;
			this.available = available;
		}
	}
}
