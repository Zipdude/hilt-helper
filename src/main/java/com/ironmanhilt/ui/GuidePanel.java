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
package com.ironmanhilt.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

public final class GuidePanel extends PluginPanel
{
	public interface Actions
	{
		void manual(int id, boolean complete);
		void skip(String activity, boolean skip);
		void refresh();
		void importFile(Filepath file);
		void applyImport(Migration.Preview preview, boolean associated);
		void exportFile(Filepath file);
		void undoImport();
		void clearCorrections();
		default void skipTask(int id, boolean skip)
		{
		}
		default void follow(String activity)
		{
		}
		default void selectGuide(RewardGoal goal)
		{
		}
		default void routeStyle(RouteStyle choice)
		{
		}
		default void screenTask(int id)
		{
		}
		default void hesporiReminder(boolean enabled)
		{
		}
		default void wilderness(boolean enabled)
		{
		}
		default void correction(String category, String key, JsonElement value)
		{
		}
		default void reconcile()
		{
		}
	}
	public static final Color BACKGROUND = new Color(28, 30, 34), CARD = new Color(40, 43, 49),
								TEXT = new Color(234, 236, 240), MUTED = new Color(176, 186, 200),
								GOLD = new Color(244, 203, 111), GREEN = new Color(130, 216, 157);
	public static final String SIGN_IN = "Log in on a standard world to start.";
	private final GuideCatalog catalogue;
	private final ProgressEngine progress;
	private final ReadinessEngine readiness;
	private final Planner planner;
	private final Actions actions;
	private final JPanel footerNavigation;
	private final JButton accountButton, checklistButton;
	private static final Color BORDER = new Color(79, 87, 99);
	private static final Color SELECTED_CARD = new Color(55, 51, 43);
	private static final FocusAdapter KEEP_FOCUS_VISIBLE = new FocusAdapter()
	{
		@Override public void focusGained(FocusEvent event)
		{
			scrollFocusedControl((JComponent)event.getComponent());
		}
	};
	private Set<Integer> completedIDs = Collections.emptySet(), syncedIDs = Collections.emptySet(),
							baseIDs = Collections.emptySet(), skippedIDs = Collections.emptySet();
	private Planner.Route shownRoute;
	private Planner.Route alternateRoute;
	private Planner.Unlocks missingUnlocks;
	private Planner.Next shownNext;
	private static final int SKIP_PAGE_SIZE = 12;
	private static final int COMPACT_LIST_HEIGHT = 263;
	private static final int TASK_CARD_TEXT_WIDTH = 148;
	private static final int UPCOMING_TASKS = 4;
	private static final String SUGGESTED = "Suggested tasks", SELECTED = "Selected tasks", RESERVES = "Reserve tasks",
								ALL = "All guide tasks";
	private static final String ALL_MISSING_ITEMS = "Show all missing items";
	private static final String REPOSITORY_URL = "https://github.com/Zipdude/hilt-helper";
	private static final String README_URL = REPOSITORY_URL + "#readme";
	private static final String BUG_REPORT_URL = REPOSITORY_URL + "/issues/new?template=bug-report.md";
	private int shownTotal, skippedOffset, focusedIndex;
	private List<Integer> focusedTasks = List.of();
	private TaskOrigin returnOrigin;
	private TaskSection taskSection;
	private ChecklistPosition beforeHespori;
	private ScrollAnchor returnAnchor;
	private ScrollAnchor taskReturnAnchor, settingsReturnAnchor;
	private TaskListArea checklistArea, blockedArea;
	private ListPosition checklistPosition = ListPosition.TOP, blockedPosition = ListPosition.TOP;
	private JTextField searchField;
	private Component restoredFocus;
	private String searchDraft = "";
	private boolean forceRender;
	private volatile boolean closed, active = true;
	private Runnable deferredRender;
	private long renderNumber;
	private volatile String shownSession = "";
	private boolean settingsOpen, associationConfirmed, hideCompleted, choosingRoute, choosingGuide, showingSkipped;
	private boolean routeExpanded, filtersExpanded, activityOptionsExpanded, blockedExpanded, correctionsExpanded;
	private boolean routeItemsExpanded, routeNeedsCheckingExpanded;
	private boolean taskListExpanded = true;
	private volatile String shownAccount = "";
	private String selectedActivity = "", scope = SUGGESTED, query = "", revealAnchor = "";
	private String routeItemActivity = "";
	private String focusTarget = "";
	private Migration.Preview preview;
	private GuideState shownState = GuideState.empty();
	private HesporiPatch.Status shownHesporiPatch = HesporiPatch.Status.UNKNOWN;
	private LiveSnapshot shownLive;
	private String status = SIGN_IN, message = "";
	private boolean available;

	private enum TaskSection
	{
		KIT, WALKTHROUGH, OPTIONS
	}

	private enum TaskOrigin
	{
		PRIMARY, CHECKLIST, BLOCKED
	}

	public GuidePanel(GuideCatalog c, ProgressEngine p, ReadinessEngine r, Planner planner, Actions actions)
	{
		super();
		catalogue = c;
		progress = p;
		readiness = r;
		this.planner = planner;
		this.actions = actions;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(BACKGROUND);
		setBorder(new EmptyBorder(10, 8, 10, 8));
		getScrollPane().getViewport().getView().setBackground(BACKGROUND);
		getScrollPane().getVerticalScrollBar().setFocusable(false);
		JPanel footer = new JPanel(new BorderLayout(0, 4));
		footer.setBackground(BACKGROUND);
		footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
			new EmptyBorder(5, 7, 5, 7)));
		footerNavigation = new JPanel(new BorderLayout());
		footerNavigation.setOpaque(false);
		accountButton = button("Account & backups", this::toggleSettings);
		accountButton.setName("account");
		checklistButton = button("Checklist", () ->
		{
			showingSkipped = false;
			choosingRoute = false;
			choosingGuide = false;
			revealAnchor = "checklist";
			focusTarget = "search";
			redraw();
		});
		footerNavigation.add(checklistButton, BorderLayout.WEST);
		footerNavigation.add(accountButton, BorderLayout.EAST);
		footer.add(footerNavigation, BorderLayout.NORTH);
		JPanel helpLinks = new JPanel(new GridLayout(1, 2, 5, 0));
		helpLinks.setOpaque(false);
		helpLinks.add(link("Read Me", README_URL));
		helpLinks.add(link("Report a Bug", BUG_REPORT_URL));
		footer.add(helpLinks, BorderLayout.CENTER);
		getWrappedPanel().add(footer, BorderLayout.SOUTH);
		getScrollPane().getViewport().addComponentListener(new ComponentAdapter()
		{
			@Override public void componentResized(ComponentEvent event)
			{
				fitTaskLists();
			}
		});
		forceRender = true;
		render(shownState, null, status, "", false);
	}

	private void toggleSettings()
	{
		settingsOpen = !settingsOpen;
		if (settingsOpen)
		{
			settingsReturnAnchor = captureAnchor(getScrollPane().getViewport().getViewPosition().y);
			revealAnchor = "account-settings";
			focusTarget = "refresh-game-data";
		}
		else
		{
			returnAnchor = settingsReturnAnchor;
			revealAnchor = returnAnchor == null ? "start" : "";
		}
		redraw();
	}

	private Component windowFocusOwner()
	{
		Window window = SwingUtilities.getWindowAncestor(getWrappedPanel());
		return window == null ? null : window.getFocusOwner();
	}

	public void render(GuideState state, LiveSnapshot live, String status, String message, boolean available)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			throw new IllegalStateException("Panel updates require the Swing thread");
		}
		if (closed)
		{
			return;
		}

		if (!active)
		{
			deferredRender = () -> render(state, live, status, message, available);
			return;
		}
		RewardGoal goal = state.rewardGoal();
		HesporiPatch.Status patch = HesporiPatch.status(state, live == null ? Instant.now() : live.at);
		boolean evidenceChanged = live == null ? shownLive != null :
			!live.sameView(shownLive, id -> readiness.usesItem(id, goal));
		boolean modelChanged = evidenceChanged || patch != shownHesporiPatch || !state.sameProgress(shownState);
		boolean viewChanged =
			modelChanged || !status.equals(this.status) || !message.equals(this.message) || available != this.available;
		if (!forceRender && !viewChanged)
		{
			shownLive = live;
			return;
		}
		forceRender = false;
		restoredFocus = null;
		long rendering = ++renderNumber;
		Point scroll = getScrollPane().getViewport().getViewPosition();
		ScrollAnchor anchor = returnAnchor == null ? captureAnchor(scroll.y) : returnAnchor;
		returnAnchor = null;
		Component focusOwner = windowFocusOwner();
		boolean sidebarFocused = focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, getWrappedPanel());
		String previousFocus = sidebarFocused ? focusKey(focusOwner) : "";
		int caret = focusOwner != null && focusOwner == searchField ? searchField.getCaretPosition() : -1;
		rememberListPositions();
		String account = live == null ? "" : live.accountKey, session = live == null ? "" : live.session;
		if (!account.equals(shownAccount) || !session.equals(shownSession))
		{
			resetChecklist();
			choosingRoute = false;
			choosingGuide = false;
			showingSkipped = false;
			preview = null;
			associationConfirmed = false;
			settingsOpen = false;
			correctionsExpanded = false;
			routeExpanded = false;
			searchField = null;
			scroll = new Point();
			anchor = null;
		}
		shownAccount = account;
		shownSession = session;
		shownHesporiPatch = patch;
		if (modelChanged || shownNext == null || available != this.available)
		{
			if (shownState.routeChoice() != state.routeChoice() || shownState.selectedGuide() != state.selectedGuide())
			{
				resetChecklist();
				choosingGuide = false;
				if (state.routeChoice() != null)
				{
					focusTarget = "next-action";
				}
				anchor = null;
				scroll = new Point();
			}
			shownState = state.copy();
			shownRoute = planner.planRoute(shownState, available && state.routeChoice() != null ? live : null);
			alternateRoute = null;
			missingUnlocks = null;
			shownNext = planner.next(shownState, live, shownRoute.entries);
			Set<Integer> priorCompleted = completedIDs;
			completedIDs = planner.completedForGuide(shownState, live);
			syncedIDs = shownState.knownIds("syncIDs", catalogue.tasks.keySet());
			baseIDs = shownState.knownIds("baseIDs", catalogue.tasks.keySet());
			skippedIDs = shownState.knownIds("skipTaskIDs", catalogue.tasks.keySet());
			skippedIDs.removeIf(id -> !catalogue.includes(id, state.rewardGoal()));
			int previousTotal = shownTotal;
			shownTotal = planner.currentPoints(shownState, live);
			if (available && !focusedTasks.isEmpty())
			{
				int current = focusedTasks.get(focusedIndex);
				extendWithSuggestedTasks();
				int target = catalogue.threshold(state.rewardGoal());
				if (this.available && (!priorCompleted.contains(current) && completedIDs.contains(current) ||
					state.rewardGoal() == RewardGoal.HARD && previousTotal < target && shownTotal >= target))
				{
					advanceCompletedTask();
				}
			}
		}
		shownLive = live;
		this.status = status;
		this.message = message;
		this.available = available;
		searchField = null;
		removeAll();
		footerNavigation.setVisible(!available || focusedTasks.isEmpty());
		checklistButton.setVisible(available && state.selectedGuide() != null &&
			state.routeChoice() != null && !choosingGuide && !settingsOpen);
		accountButton.setText(settingsOpen ? "Back to guide" : "Account & backups");
		if (available && !focusedTasks.isEmpty())
		{
			int next = nextTaskIndex(focusedIndex + 1);
			getScrollPane().setColumnHeaderView(taskNavigation(next));
			focusedTask(shownState, live);
			upcomingTasks(next);
		}
		else
		{
			getScrollPane().setColumnHeader(null);
			if (settingsOpen)
			{
				settings(shownState, live);
			}
			else
			{
				overview(shownState, live);
			}
		}
		align(this);
		revalidate();
		repaint();
		Point savedScroll = scroll;
		ScrollAnchor savedAnchor = anchor;
		String target = revealAnchor;
		revealAnchor = "";
		boolean restoreRequestedFocus = !focusTarget.isEmpty();
		String focus = restoreRequestedFocus ? focusTarget : previousFocus;
		focusTarget = "";
		SwingUtilities.invokeLater(() ->
		{
			if (closed || renderNumber != rendering)
			{
				return;
			}
			int max = Math.max(0, getScrollPane().getViewport().getViewSize().height -
				getScrollPane().getViewport().getExtentSize().height);
			Component requested = target.isEmpty() ? null : findAnchor(this, target);
			Component anchored = savedAnchor == null ? null : findAnchor(this, savedAnchor.key);
			int position = requested != null ? anchorTop(requested)
				: anchored == null ? savedScroll.y : anchorTop(anchored) + savedAnchor.offset;
			getScrollPane().getViewport().setViewPosition(new Point(0, Math.max(0, Math.min(position, max))));
			if (checklistArea != null)
			{
				checklistPosition.restore(checklistArea.list);
			}
			if (blockedArea != null)
			{
				blockedPosition.restore(blockedArea.list);
			}
			Component currentFocus = windowFocusOwner();
			boolean focusStillInGuide = currentFocus == null || currentFocus == focusOwner ||
				(restoreRequestedFocus && SwingUtilities.isDescendingFrom(currentFocus, getWrappedPanel()));
			if (sidebarFocused && focusStillInGuide && !focus.isEmpty())
			{
				Component replacement = findFocus(getWrappedPanel(), focus);
				if (replacement != null && replacement != currentFocus)
				{
					if (!restoreRequestedFocus)
					{
						restoredFocus = replacement;
					}
					if (!replacement.requestFocusInWindow())
					{
						restoredFocus = null;
					}
					if (caret >= 0 && replacement == searchField)
					{
						searchField.setCaretPosition(Math.min(caret, searchField.getDocument().getLength()));
					}
				}
			}
		});
	}

	private static String focusKey(Component control)
	{
		if (control.getName() != null)
		{
			return control.getName();
		}
		return control instanceof AbstractButton ? ((AbstractButton)control).getActionCommand() : "";
	}

	private static Component findFocus(Container parent, String key)
	{
		for (Component child : parent.getComponents())
		{
			if (!child.isVisible() || !child.isEnabled())
			{
				continue;
			}
			if (child.isFocusable() && key.equals(focusKey(child)))
			{
				return child;
			}
			if (child instanceof Container)
			{
				Component found = findFocus((Container)child, key);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private void resetChecklist()
	{
		selectedActivity = "";
		scope = SUGGESTED;
		query = "";
		searchDraft = "";
		resetListPositions();
		skippedOffset = 0;
		beforeHespori = null;
		filtersExpanded = false;
		activityOptionsExpanded = false;
		blockedExpanded = false;
		routeItemsExpanded = false;
		routeNeedsCheckingExpanded = false;
		routeItemActivity = "";
		taskListExpanded = true;
		focusedTasks = List.of();
		taskSection = null;
		taskReturnAnchor = returnAnchor = settingsReturnAnchor = null;
		focusTarget = "";
		revealAnchor = "";
	}

	private void overview(GuideState state, LiveSnapshot live)
	{
		RewardGoal goal = state.rewardGoal();
		boolean choosing = choosingGuide || state.selectedGuide() == null;
		JTextArea title = text(choosing ? "Hilt Helper" : goal.reward, GOLD, 19, true);
		title.putClientProperty("guideAnchor", "start");
		add(title);
		int target = catalogue.threshold(goal);
		int remaining = Math.max(0, target - shownTotal);
		add(text(choosing ? "Combat Achievement guides" : goal.label + " Combat Achievements", MUTED, 12, false));
		if (live != null && live.mode.supported())
		{
			add(text(live.name + " · " + live.mode.label, MUTED, 12, false));
		}
		if (available)
		{
			add(gap(3));
			if (choosing)
			{
				add(text(shownTotal + " Combat Achievement points", TEXT, 13, true));
			}
			else
			{
				String points = shownTotal + " / " + target + " points";
				add(text(remaining > 0 ? points + " · " + remaining + " to go" : points, TEXT, 13, true));
				JProgressBar bar = new JProgressBar(0, target);
				bar.setValue(Math.min(shownTotal, target));
				bar.getAccessibleContext().setAccessibleName(goal.label + " progress");
				bar.setForeground(GOLD);
				bar.setBackground(CARD);
				bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
				add(bar);
			}
			if (live != null && live.mode.bankAllowed() && live.observedBank() == null)
			{
				add(text(ReadinessEngine.FIRST_BANK_CHECK, GOLD, 12, true));
			}
			if (live != null && live.freeWorld() && remaining > 0)
			{
				add(text("The full route and hilt reward require a members’ world.", MUTED, 12, false));
			}
		}
		addNotices(this);
		if (hasProgressDifference())
		{
			add(text("The game shows " + live.ca.gamePoints + " points. Review differences in Account & backups.",
				GOLD, 12, false));
		}
		if (available)
		{
			if (choosing)
			{
				guidePicker();
				return;
			}
			JButton change = button("Change guide", () ->
			{
				choosingGuide = true;
				revealAnchor = "guide-picker";
				focusTarget = "guide-medium";
				redraw();
			});
			change.setName("change-guide");
			add(change);
			if (shownState.routeChoice() == null || choosingRoute)
			{
				routePicker();
			}
			else if (showingSkipped)
			{
				skippedItems(shownState);
			}
			else
			{
				guide(shownState, live, remaining);
			}
		}
	}

	private boolean hasProgressDifference()
	{
		return available && shownLive != null && shownLive.ca.valid &&
			(shownTotal != shownLive.ca.gamePoints || !shownLive.ca.completed.containsAll(completedIDs));
	}

	private void addNotices(JPanel panel)
	{
		if (!status.isEmpty())
		{
			panel.add(text(status, available ? GOLD : TEXT, 12, false));
		}
		if (!message.isEmpty())
		{
			panel.add(text(message, GOLD, 12, false));
		}
	}

	private void guidePicker()
	{
		JPanel box = card();
		box.putClientProperty("guideAnchor", "guide-picker");
		box.add(text("Choose a guide", TEXT, 16, true));
		for (RewardGoal goal : RewardGoal.values())
		{
			JButton choose = button(goal.label + " · " + catalogue.threshold(goal) + " points",
				savedAction(() -> chooseGuide(goal)));
			choose.setName("guide-" + goal.key);
			box.add(choose);
			box.add(text(goal == RewardGoal.MEDIUM ? "Build toward Ghommal’s hilt 2." :
				"Use your current points to build toward Ghommal’s hilt 3.", MUTED, 12, false));
			box.add(gap(4));
		}
		if (choosingGuide)
		{
			box.add(button("Back to guide", () ->
			{
				choosingGuide = false;
				redraw();
			}));
		}
		add(gap(6));
		add(box);
	}

	private void chooseGuide(RewardGoal goal)
	{
		choosingGuide = false;
		showingSkipped = false;
		if (shownState.selectedGuide() == goal)
		{
			redraw();
		}
		else
		{
			actions.selectGuide(goal);
		}
	}

	private void routePicker()
	{
		JPanel box = card();
		box.putClientProperty("guideAnchor", "route-picker");
		box.add(text("Choose a route", TEXT, 16, true));
		box.add(text("Both routes work for every account type. Switch any time.", MUTED, 12, false));
		box.add(gap(5));
		box.add(primaryButton(RouteStyle.SOLO.label + " · recommended",
			savedAction(() -> chooseRoute(RouteStyle.SOLO))));
		box.add(text(shownState.rewardGoal() == RewardGoal.MEDIUM ? "Start with skilling bosses and solo combat." :
			"Prioritize ready tasks you can complete on your own.", TEXT, 12, false));
		box.add(gap(4));
		box.add(button(RouteStyle.GROUP.label, savedAction(() -> chooseRoute(RouteStyle.GROUP))));
		box.add(text("Include eligible party tasks when you’re ready.", TEXT, 12, false));
		if (shownState.routeChoice() != null)
		{
			box.add(button("Back to guide", () ->
			{
				choosingRoute = false;
				redraw();
			}));
		}
		add(gap(8));
		add(box);
	}

	private void chooseRoute(RouteStyle style)
	{
		choosingRoute = false;
		if (shownState.routeChoice() == style)
		{
			redraw();
		}
		else
		{
			actions.routeStyle(style);
		}
	}

	private void guide(GuideState state, LiveSnapshot live, int remaining)
	{
		add(gap(8));
		nextAction(state, remaining);
		routeRequirements(remaining);
		hesporiStatus(state, live);
		checklist(state);
	}

	private void nextAction(GuideState state, int remaining)
	{
		Planner.Next next = shownNext;
		JPanel box = card();
		box.add(text("Next up · " + state.routeChoice().label, GOLD, 12, true));
		if (!next.ids.isEmpty())
		{
			int id = next.ids.get(0);
			box.add(text(catalogue.tasks.get(id).name, TEXT, 15, true));
			box.add(text(next.title + " · " + taskPoints(id), MUTED, 12, false));
			String preparation = shownRoute.blockedTasks.get(id);
			boolean needsPreparation = preparation != null;
			if (needsPreparation)
			{
				box.add(text(shortKitCheck(preparation), GOLD, 12, false));
			}
			box.add(gap(4));
			JButton open = primaryButton(needsPreparation ? "Review preparation" : "Open next task",
				() -> openTask(id, plannedTasks(""), TaskOrigin.PRIMARY));
			open.setName("next-action");
			box.add(open);
		}
		else
		{
			box.add(text(next.title, TEXT, 14, true));
			box.add(text(next.detail, MUTED, 12, false));
			if (remaining == 0)
			{
				box.add(link("Hilt rewards on the Wiki", state.rewardGoal().wiki));
			}
		}
		int tracked = state.screenTask();
		if (catalogue.includes(tracked, state.rewardGoal()) && !completedIDs.contains(tracked))
		{
			box.add(gap(4));
			box.add(text("Pinned · " + catalogue.tasks.get(tracked).name, MUTED, 12, false));
			box.add(button("Clear on-screen task", savedAction(() -> actions.screenTask(-1))));
		}
		add(box);
	}

	private static String shortKitCheck(String check)
	{
		int detail = check.indexOf(". ");
		return detail < 0 ? check : check.substring(0, detail);
	}

	private void routeRequirements(int remaining)
	{
		JPanel box = card();
		if (!routeExpanded)
		{
			box.setBorder(new EmptyBorder(0, 0, 0, 0));
			box.setOpaque(false);
		}
		box.putClientProperty("guideAnchor", "requirements");
		box.add(disclosure("Route & requirements", routeExpanded, () ->
		{
			routeExpanded = !routeExpanded;
			redraw();
		}));
		if (remaining > 0)
		{
			int ready = Math.min(remaining, shownRoute.preparedPoints);
			String timing = shownLive != null && shownLive.freeWorld() ? " on a members’ world" : " now";
			String capacity = ready + " of " + remaining + " remaining points ready" + timing;
			box.add(text(capacity, ready == remaining ? GREEN : TEXT, 12, true));
			if (ready < remaining)
			{
				box.add(text((remaining - ready) + " more need preparation or other tasks.", MUTED, 12, false));
			}
		}
		if (routeExpanded)
		{
			box.add(gap(4));
			box.add(taskTypePicker("route-task-type"));
			box.add(text("Unearned points in this filter", MUTED, 12, false));
			RouteStyle current = shownState.routeChoice();
			if (alternateRoute == null)
			{
				alternateRoute = planner.planRoute(shownState, shownLive,
					current == RouteStyle.SOLO ? RouteStyle.GROUP : RouteStyle.SOLO);
			}
			for (RouteStyle style : RouteStyle.values())
			{
				PointTally points = pointTally(style == current ? shownRoute : alternateRoute);
				box.add(text(style.label, style == current ? GOLD : TEXT, 12, true));
				box.add(text(points.unlocked + " unlocked pts · " + points.locked + " locked pts", TEXT, 12, false));
			}
			box.add(button("Change route", () ->
			{
				choosingRoute = true;
				revealAnchor = "route-picker";
				focusTarget = RouteStyle.SOLO.label + " · recommended";
				redraw();
			}));
			box.add(gap(6));
			routeItems(box);
			box.add(gap(6));
			box.add(text("Quests & levels to work on", GOLD, 13, true));
			box.add(text("Across unfinished tasks on your selected route.", MUTED, 12, false));
			if (missingUnlocks == null)
			{
				missingUnlocks = planner.missingUnlocks(shownRoute, shownState, shownLive);
			}
			if (missingUnlocks.quests.isEmpty() && missingUnlocks.levels.isEmpty())
			{
				box.add(text("Quest and skill checks met.", GREEN, 12, false));
			}
			appendUnlocks(box, "Quests to complete or confirm", missingUnlocks.quests);
			appendUnlocks(box, "Skill levels to reach or confirm", missingUnlocks.levels);
			box.add(button("General preparation notes…", this::showPreparation));
		}
		else if (remaining > 0 && shownRoute.groupChecks.containsKey("theatre"))
		{
			shownRoute.groupChecks.get("theatre").stream()
				.filter(check -> check.contains("A Night at the Theatre"))
				.findFirst()
				.ifPresent(check -> box.add(text(shortKitCheck(check), GOLD, 12, false)));
		}
		add(gap(6));
		add(box);
	}

	private void routeItems(JPanel parent)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setOpaque(false);
		box.putClientProperty("guideAnchor", "route-items");
		JButton toggle = disclosure("Required & missing items", routeItemsExpanded, () ->
		{
			routeItemsExpanded = !routeItemsExpanded;
			revealAnchor = "route-items";
			focusTarget = "route-items-toggle";
			redraw();
		});
		toggle.setName("route-items-toggle");
		box.add(toggle);
		parent.add(box);
		if (!routeItemsExpanded)
		{
			return;
		}

		Map<String, List<Integer>> byActivity = tasksByActivity(scopedRouteTasks(shownRoute));
		if (!ALL_MISSING_ITEMS.equals(routeItemActivity) && !byActivity.containsKey(routeItemActivity))
		{
			routeItemActivity = byActivity.keySet().stream().findFirst().orElse(ALL_MISSING_ITEMS);
		}
		box.add(routeItemPicker(byActivity.keySet()));
		if (ALL_MISSING_ITEMS.equals(routeItemActivity))
		{
			allMissingRouteItems(box);
		}
		else
		{
			activityItemChecks(box, byActivity.get(routeItemActivity));
		}
	}

	private Map<String, List<Integer>> tasksByActivity(List<Integer> tasks)
	{
		Map<String, List<Integer>> byActivity = new LinkedHashMap<>();
		for (int id : tasks)
		{
			byActivity.computeIfAbsent(catalogue.owner(id), ignored -> new ArrayList<>()).add(id);
		}
		return byActivity;
	}

	private JComboBox<String> routeItemPicker(Set<String> activities)
	{
		List<String> options = new ArrayList<>();
		options.add(ALL_MISSING_ITEMS);
		options.addAll(activities);
		String[] labels = options.stream().map(key -> ALL_MISSING_ITEMS.equals(key) ? key : catalogue.label(key))
			.toArray(String[]::new);
		JComboBox<String> chooser = new JComboBox<>(labels);
		chooser.setName("route-item-activity");
		chooser.getAccessibleContext().setAccessibleName("Item requirements to show");
		chooser.setSelectedIndex(options.indexOf(routeItemActivity));
		chooser.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		chooser.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		chooser.addFocusListener(KEEP_FOCUS_VISIBLE);
		chooser.addActionListener(e ->
		{
			routeItemActivity = options.get(chooser.getSelectedIndex());
			focusTarget = "route-item-activity";
			redraw();
		});
		return chooser;
	}

	private void allMissingRouteItems(JPanel box)
	{
		box.add(text("Required items for unfinished tasks. Open a task to see what to bring or wear.", MUTED, 12, false));
		Map<String, List<Integer>> byActivity = tasksByActivity(shownRoute.taskPool);
		if (byActivity.isEmpty())
		{
			box.add(text("There are no unfinished tasks on this route.", MUTED, 12, false));
			return;
		}

		JPanel shortages = new JPanel();
		shortages.setLayout(new BoxLayout(shortages, BoxLayout.Y_AXIS));
		shortages.setOpaque(false);
		shortages.putClientProperty("guideAnchor", "route-missing-short");
		shortages.add(text("Missing or short", TEXT, 12, true));
		Map<String, List<Planner.ItemRequirement>> unchecked = new LinkedHashMap<>();
		int unconfirmed = 0;
		for (Map.Entry<String, List<Integer>> activity : byActivity.entrySet())
		{
			String key = activity.getKey();
			List<Integer> tasks = activity.getValue();
			List<Planner.ItemRequirement> missing = new ArrayList<>();
			for (Planner.ItemRequirement item : planner.itemRequirements(key, tasks, shownState, shownLive))
			{
				if (!item.required || item.check.ready())
				{
					continue;
				}
				if (ReadinessEngine.MISSING.equals(item.check.status) || ReadinessEngine.SHORT.equals(item.check.status))
				{
					missing.add(item);
				}
				else
				{
					unchecked.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
					unconfirmed++;
				}
			}
			appendRouteActivityItems(shortages, key, missing, tasks.size(), "missing-items-");
		}
		if (shortages.getComponentCount() == 1)
		{
			shortages.add(text("No confirmed missing or short items.", MUTED, 12, false));
		}
		if (unconfirmed > 0)
		{
			JPanel review = new JPanel();
			review.setLayout(new BoxLayout(review, BoxLayout.Y_AXIS));
			review.setOpaque(false);
			review.putClientProperty("guideAnchor", "route-item-review");
			JButton toggle = disclosure("Needs checking (" + unconfirmed + ")", routeNeedsCheckingExpanded, () ->
			{
				routeNeedsCheckingExpanded = !routeNeedsCheckingExpanded;
				revealAnchor = "route-needs-checking";
				focusTarget = "route-needs-checking";
				redraw();
			});
			toggle.setName("route-needs-checking");
			toggle.putClientProperty("guideAnchor", "route-needs-checking");
			review.add(toggle);
			if (routeNeedsCheckingExpanded)
			{
				unchecked.forEach((key, items) ->
					appendRouteActivityItems(review, key, items, byActivity.get(key).size(), "unchecked-items-"));
			}
			box.add(review);
			shortages.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER), new EmptyBorder(7, 0, 0, 0)));
		}
		box.add(gap(8));
		box.add(shortages);
	}

	private void appendRouteActivityItems(JPanel box, String key, List<Planner.ItemRequirement> items, int taskCount,
		String anchorPrefix)
	{
		if (items.isEmpty())
		{
			return;
		}
		JPanel activity = new JPanel();
		activity.setLayout(new BoxLayout(activity, BoxLayout.Y_AXIS));
		activity.setOpaque(false);
		activity.putClientProperty("guideAnchor", anchorPrefix + key);
		appendItemChecks(activity, catalogue.label(key), items, taskCount, new HashSet<>());
		box.add(activity);
	}

	private void activityItemChecks(JPanel box, List<Integer> tasks)
	{
		box.add(text("Items for unfinished tasks in this filter", MUTED, 12, false));
		if (tasks.size() == 1)
		{
			box.add(text(catalogue.tasks.get(tasks.get(0)).name, TEXT, 12, true));
		}
		List<Planner.ItemRequirement> required = new ArrayList<>(), suggested = new ArrayList<>(), provided = new ArrayList<>();
		for (Planner.ItemRequirement item : planner.itemRequirements(routeItemActivity, tasks, shownState, shownLive))
		{
			if (item.required)
			{
				required.add(item);
			}
			else if (ReadinessEngine.PROVIDED.equals(item.check.status))
			{
				provided.add(item);
			}
			else
			{
				suggested.add(item);
			}
		}
		Set<String> displayedHints = new HashSet<>();
		appendItemChecks(box, "Required by this guide’s method", required, tasks.size(), displayedHints);
		appendItemChecks(box, "Suggested kit", suggested, tasks.size(), displayedHints);
		appendItemChecks(box, "Collect at the activity", provided, tasks.size(), displayedHints);
		if (required.isEmpty() && suggested.isEmpty() && provided.isEmpty())
		{
			box.add(text("See the task walkthrough for setup details.", MUTED, 12, false));
		}
	}

	private void appendItemChecks(JPanel box, String heading, List<Planner.ItemRequirement> items, int taskCount,
		Set<String> displayedHints)
	{
		if (items.isEmpty())
		{
			return;
		}
		box.add(gap(4));
		box.add(text(heading, TEXT, 12, true));
		items.sort(Comparator.comparing(item -> item.check.ready()));
		for (Planner.ItemRequirement item : items)
		{
			ReadinessEngine.Check check = item.check;
			boolean suggested = !item.required && !check.ready();
			box.add(text(check.label + " — " + (suggested ? "Suggested" : check.displayStatus()),
				suggested ? MUTED : check.ready() ? GREEN : GOLD, 12, true));
			String quantity = item.quantity();
			if (!quantity.isEmpty())
			{
				String action = ReadinessEngine.PROVIDED.equals(check.status) ? "Collect " : "Bring ";
				box.add(text(action + quantity, MUTED, 12, false));
			}
			if (item.taskIds.size() < taskCount)
			{
				List<String> names = new ArrayList<>();
				for (int id : item.taskIds)
				{
					names.add(catalogue.tasks.get(id).name);
				}
				box.add(text("For " + String.join(", ", names), MUTED, 12, false));
			}
			boolean hasHint = !check.ready() && !ReadinessEngine.MISSING.equals(check.status) ||
				check.fromBank || ReadinessEngine.ALTERNATIVE.equals(check.status) || ReadinessEngine.PROVIDED.equals(check.status);
			if (!suggested && (ReadinessEngine.SHORT.equals(check.status) || hasHint && displayedHints.add(check.detail)))
			{
				box.add(text(check.detail, MUTED, 12, false));
			}
			boolean confirmed = ReadinessEngine.MANUAL.equals(check.status);
			if (item.required && !item.correctionCategory.isEmpty() &&
				(confirmed || ReadinessEngine.UNKNOWN.equals(check.status)))
			{
				box.add(itemConfirmation(item, confirmed));
			}
		}
	}

	private JCheckBox itemConfirmation(Planner.ItemRequirement item, boolean confirmed)
	{
		JCheckBox checkbox = checkbox("Confirmed available", confirmed);
		checkbox.setName("confirm-" + item.correctionCategory + "-" + item.correctionKey);
		checkbox.getAccessibleContext().setAccessibleName("Confirm " + item.check.label);
		checkbox.setToolTipText("Verify this in game. Checking it saves a preparation correction for this account.");
		checkbox.setEnabled(available);
		Runnable save = savedAction(() ->
		{
			JsonElement value = null;
			if (checkbox.isSelected())
			{
				value = "items".equals(item.correctionCategory) ? new JsonPrimitive(true) : readinessOverride("Owned");
			}
			actions.correction(item.correctionCategory, item.correctionKey, value);
		});
		checkbox.addActionListener(event -> save.run());
		return checkbox;
	}

	private static void appendUnlocks(JPanel box, String heading, Map<String, Set<String>> unlocks)
	{
		if (unlocks.isEmpty())
		{
			return;
		}
		box.add(gap(4));
		box.add(text(heading, TEXT, 12, true));
		StringBuilder details = new StringBuilder();
		unlocks.forEach((name, activities) -> details.append("• ").append(name).append(" · ")
			.append(String.join(", ", activities)).append('\n'));
		box.add(text(details.toString().stripTrailing(), TEXT, 12, false));
	}

	private PointTally pointTally(Planner.Route route)
	{
		int unlocked = 0, locked = 0;
		for (int id : scopedRouteTasks(route))
		{
			int points = catalogue.tasks.get(id).points;
			if (route.blockedTasks.containsKey(id))
			{
				locked += points;
			}
			else
			{
				unlocked += points;
			}
		}
		return new PointTally(unlocked, locked);
	}

	private static final class PointTally
	{
		private final int unlocked, locked;
		private PointTally(int unlocked, int locked)
		{
			this.unlocked = unlocked;
			this.locked = locked;
		}
	}

	private void hesporiStatus(GuideState state, LiveSnapshot live)
	{
		boolean open = selectedActivity.equals("hespori");
		boolean watching = HesporiPatch.enabled(state);
		Integer farming = ReadinessEngine.level("farming", state, live);
		if (!open && !watching && (state.skipped("hespori") || farming == null || farming < 65))
		{
			return;
		}
		JPanel box = card();
		if (!watching)
		{
			box.setBorder(new EmptyBorder(0, 0, 0, 0));
			box.setOpaque(false);
		}
		box.putClientProperty("guideAnchor", "hespori-shortcut");
		box.add(disclosure(open ? "Close Hespori checklist" : "Open Hespori checklist", open, this::toggleHespori));
		if (open && planner.hasUnfinishedHespori(state, live))
		{
			box.add(text(HesporiPatch.checklist(state, live, live == null ? Instant.now() : live.at), TEXT, 11, false));
		}
		add(gap(6));
		add(box);
	}

	private void toggleHespori()
	{
		rememberListPositions();
		if (selectedActivity.equals("hespori"))
		{
			if (beforeHespori == null)
			{
				selectedActivity = "";
				query = "";
				searchDraft = "";
				resetListPositions();
				activityOptionsExpanded = false;
			}
			else
			{
				selectedActivity = beforeHespori.activity;
				scope = beforeHespori.scope;
				query = beforeHespori.query;
				searchDraft = beforeHespori.draft;
				checklistPosition = beforeHespori.checklist;
				blockedPosition = beforeHespori.blocked;
				blockedExpanded = beforeHespori.showBlocked;
				filtersExpanded = beforeHespori.filters;
				activityOptionsExpanded = beforeHespori.activityOptions;
				taskListExpanded = beforeHespori.showTaskList;
			}
			beforeHespori = null;
		}
		else
		{
			beforeHespori = new ChecklistPosition(selectedActivity, scope, query, searchDraft, checklistPosition,
				blockedPosition, filtersExpanded, activityOptionsExpanded, blockedExpanded, taskListExpanded);
			selectedActivity = "hespori";
			scope = RESERVES;
			query = "";
			searchDraft = "";
			resetListPositions();
			filtersExpanded = false;
			activityOptionsExpanded = false;
			blockedExpanded = false;
			taskListExpanded = true;
		}
		revealAnchor = "hespori-shortcut";
		redraw();
	}

	private static final class ChecklistPosition
	{
		private final String activity, scope, query, draft;
		private final ListPosition checklist, blocked;
		private final boolean filters, activityOptions, showBlocked, showTaskList;

		private ChecklistPosition(String activity, String scope, String query, String draft, ListPosition checklist,
			ListPosition blocked, boolean filters, boolean activityOptions, boolean showBlocked, boolean showTaskList)
		{
			this.activity = activity;
			this.scope = scope;
			this.query = query;
			this.draft = draft;
			this.checklist = checklist;
			this.blocked = blocked;
			this.filters = filters;
			this.activityOptions = activityOptions;
			this.showBlocked = showBlocked;
			this.showTaskList = showTaskList;
		}
	}

	private void checklist(GuideState state)
	{
		JPanel header = card();
		header.putClientProperty("guideAnchor", "checklist");
		header.add(text("Task checklist", TEXT, 15, true));
		header.add(searchBox());
		header.add(gap(5));
		header.add(disclosure("Filters · " + scope, filtersExpanded, () ->
		{
			filtersExpanded = !filtersExpanded;
			revealAnchor = "checklist";
			redraw();
		}));
		if (filtersExpanded)
		{
			checklistFilters(header, state);
		}
		if (!selectedActivity.isEmpty())
		{
			header.add(gap(5));
			header.add(text(catalogue.label(selectedActivity), TEXT, 14, true));
			if (selectedActivity.equals("hespori"))
			{
				boolean watching = HesporiPatch.enabled(state);
				header.add(button(watching ? "Turn off Hespori reminders" : "Enable Hespori reminders",
					savedAction(() -> actions.hesporiReminder(!watching))));
			}
			header.add(disclosure("Activity prep & options", activityOptionsExpanded, () ->
			{
				activityOptionsExpanded = !activityOptionsExpanded;
				redraw();
			}));
			if (activityOptionsExpanded)
			{
				activityOptions(header, selectedActivity, state);
			}
		}
		int skippedCount = skippedIDs.size();
		for (JsonObject activity : catalogue.activities(shownState.rewardGoal()))
		{
			if (state.skipped(activity.get("key").getAsString()))
			{
				skippedCount++;
			}
		}
		if (skippedCount > 0)
		{
			header.add(gap(4));
			header.add(button("Skipped tasks & activities (" + skippedCount + ")", () ->
			{
				showingSkipped = true;
				skippedOffset = 0;
				revealAnchor = "skipped";
				focusTarget = "back-from-skips";
				redraw();
			}));
		}
		add(gap(8));
		add(header);
		blockedTasks();
		checklistResults();
	}

	private JPanel searchBox()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		JTextField input = new JTextField(searchDraft);
		searchField = input;
		input.setName("search");
		input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		input.setForeground(TEXT);
		input.setCaretColor(TEXT);
		input.setBackground(BACKGROUND);
		Border searchBorder = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
			new EmptyBorder(4, 6, 4, 6));
		input.setBorder(searchBorder);
		input.addFocusListener(new FocusAdapter()
		{
			@Override public void focusGained(FocusEvent event)
			{
				input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GOLD),
					new EmptyBorder(4, 6, 4, 6)));
				scrollFocusedControl(input);
			}

			@Override public void focusLost(FocusEvent event)
			{
				input.setBorder(searchBorder);
			}
		});
		String hint = "Find tasks by name, activity, rule or ID";
		input.putClientProperty("JTextField.placeholderText", "Find a task");
		input.setToolTipText(hint);
		input.getAccessibleContext().setAccessibleName(hint);
		input.setMinimumSize(new Dimension(32, 28));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 29));
		input.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent event)
			{
				updateDraft();
			}

			@Override public void removeUpdate(DocumentEvent event)
			{
				updateDraft();
			}

			@Override public void changedUpdate(DocumentEvent event)
			{
				updateDraft();
			}

			private void updateDraft()
			{
				if (searchField == input)
				{
					searchDraft = input.getText();
				}
			}
		});
		Runnable submit = () -> search(input.getText());
		input.addActionListener(event -> submit.run());
		row.add(input, BorderLayout.CENTER);
		JPanel controls = new JPanel(new BorderLayout(3, 0));
		controls.setOpaque(false);
		controls.add(button("Search", submit), BorderLayout.CENTER);
		if (!query.isEmpty())
		{
			controls.add(button("Clear", () ->
			{
				focusTarget = "search";
				search("");
			}), BorderLayout.EAST);
		}
		row.add(controls, BorderLayout.EAST);
		return row;
	}

	private void search(String input)
	{
		searchDraft = input.trim();
		String term = searchDraft.toLowerCase(Locale.ROOT);
		if (!term.isEmpty() && query.isEmpty() && selectedActivity.isEmpty() && scope.equals(SUGGESTED))
		{
			scope = ALL;
		}
		query = term;
		if (!term.isEmpty())
		{
			taskListExpanded = true;
		}
		resetListPositions();
		revealAnchor = "checklist";
		redraw();
	}

	private void checklistFilters(JPanel header, GuideState state)
	{
		JComboBox<String> activities = new JComboBox<>();
		activities.setName("activity-filter");
		activities.addItem("All activities");
		int selected = 0;
		List<JsonObject> choices = catalogue.activities(state.rewardGoal());
		for (int i = 0; i < choices.size(); i++)
		{
			JsonObject activity = choices.get(i);
			String key = activity.get("key").getAsString();
			activities.addItem(catalogue.label(key) +
				(catalogue.routeIds(activity, false, state.rewardGoal()).isEmpty() ? " · reserve" : "") +
				(state.skipped(key) ? " · skipped" : ""));
			if (key.equals(selectedActivity))
			{
				selected = i + 1;
			}
		}
		activities.setSelectedIndex(selected);
		activities.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		activities.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		activities.getAccessibleContext().setAccessibleName("Activity");
		activities.addFocusListener(KEEP_FOCUS_VISIBLE);
		activities.addActionListener(e ->
		{
			int i = activities.getSelectedIndex();
			selectedActivity = i <= 0 ? "" : choices.get(i - 1).get("key").getAsString();
			beforeHespori = null;
			activityOptionsExpanded = false;
			resetListPositions();
			redraw();
		});
		header.add(activities);
		header.add(taskTypePicker("checklist-task-type"));
		header.add(text("Suggested follows your plan. Selected shows the guide’s core tasks.", MUTED, 12, false));
		JCheckBox hide = checkbox("Hide completed tasks", hideCompleted);
		hide.addActionListener(e ->
		{
			hideCompleted = hide.isSelected();
			resetListPositions();
			redraw();
		});
		header.add(hide);
	}

	private JComboBox<String> taskTypePicker(String name)
	{
		JComboBox<String> scopes = new JComboBox<>(new String[] {SUGGESTED, SELECTED, RESERVES, ALL});
		scopes.setName(name);
		scopes.setSelectedItem(scope);
		scopes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		scopes.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		scopes.getAccessibleContext().setAccessibleName("Task type");
		scopes.setToolTipText("Selected tasks are the guide’s core tasks. Suggestions can also use reserves.");
		scopes.addFocusListener(KEEP_FOCUS_VISIBLE);
		scopes.addActionListener(e ->
		{
			scope = (String)scopes.getSelectedItem();
			resetListPositions();
			redraw();
		});
		return scopes;
	}

	private List<Integer> plannedTasks(String activity)
	{
		return plannedTasks(shownRoute, activity);
	}

	private static List<Integer> plannedTasks(Planner.Route route, String activity)
	{
		List<Integer> ids = new ArrayList<>();
		for (Planner.Entry entry : route.entries)
		{
			if (activity.isEmpty() || activity.equals(entry.key))
			{
				ids.addAll(entry.ids);
			}
		}
		return ids;
	}

	private List<Integer> scopedRouteTasks(Planner.Route route)
	{
		List<Integer> ids = new ArrayList<>();
		List<Integer> pool = scope.equals(SUGGESTED) ? plannedTasks(route, selectedActivity) : route.taskPool;
		for (int id : pool)
		{
			if ((!selectedActivity.isEmpty() && !selectedActivity.equals(catalogue.owner(id))) ||
				(!query.isEmpty() && !catalogue.matches(id, query)) ||
				(scope.equals(SELECTED) && catalogue.reserve(id)) || (scope.equals(RESERVES) && !catalogue.reserve(id)))
			{
				continue;
			}
			ids.add(id);
		}
		return ids;
	}

	private List<Integer> checklistTasks()
	{
		List<Integer> ids = scope.equals(SUGGESTED) ? plannedTasks(selectedActivity) : new ArrayList<>();
		if (!scope.equals(SUGGESTED))
		{
			for (JsonObject activity : catalogue.activities(shownState.rewardGoal()))
			{
				if (!selectedActivity.isEmpty() && !selectedActivity.equals(activity.get("key").getAsString()))
				{
					continue;
				}
				if (!scope.equals(RESERVES))
				{
					ids.addAll(catalogue.routeIds(activity, false, shownState.rewardGoal()));
				}
				if (!scope.equals(SELECTED))
				{
					ids.addAll(catalogue.routeIds(activity, true, shownState.rewardGoal()));
				}
			}
		}
		ids.removeIf(id -> hideCompleted && completedIDs.contains(id) ||
			!query.isEmpty() && !catalogue.matches(id, query) ||
			scope.equals(SUGGESTED) && shownRoute.blockedTasks.containsKey(id));
		return ids;
	}

	private void blockedTasks()
	{
		List<Integer> ids = scopedRouteTasks(shownRoute);
		ids.removeIf(id -> !shownRoute.blockedTasks.containsKey(id));
		if (ids.isEmpty())
		{
			return;
		}
		JPanel section = card();
		section.putClientProperty("guideAnchor", "blocked-tasks");
		section.add(disclosure("Blocked tasks · " + ids.size(), blockedExpanded, () ->
		{
			blockedExpanded = !blockedExpanded;
			revealAnchor = "blocked-tasks";
			redraw();
		}));
		if (blockedExpanded)
		{
			section.add(text("Open a task to see what to unlock or bring.", MUTED, 12, false));
			section.add(taskList(ids, TaskOrigin.BLOCKED));
		}
		add(gap(5));
		add(section);
	}

	private void checklistResults()
	{
		List<Integer> ids = checklistTasks();
		JPanel results = new JPanel();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setOpaque(false);
		results.putClientProperty("guideAnchor", "task-results");
		if (ids.isEmpty())
		{
			String empty = scope.equals(SUGGESTED) && query.isEmpty() ?
				"No suggested tasks ready here. Review blocked tasks or change filters." :
				"No matching tasks.";
			results.add(text(empty, MUTED, 12, false));
		}
		else
		{
			results.add(disclosure("Task list · " + ids.size() + (query.isEmpty() ? " tasks" : " matches"),
				taskListExpanded, () ->
				{
					taskListExpanded = !taskListExpanded;
					revealAnchor = "task-results";
					redraw();
				}));
			if (taskListExpanded)
			{
				results.add(gap(4));
				results.add(taskList(ids, TaskOrigin.CHECKLIST));
			}
		}
		add(gap(4));
		add(results);
	}

	private JPanel taskList(List<Integer> ids, TaskOrigin origin)
	{
		boolean blocked = origin == TaskOrigin.BLOCKED;
		JList<Integer> list = new JList<>(ids.toArray(new Integer[0]));
		list.setName(blocked ? "blocked-tasks" : "checklist-tasks");
		list.getAccessibleContext().setAccessibleName(blocked ? "Blocked tasks" : "Checklist tasks");
		list.setBackground(BACKGROUND);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(blocked ? 108 : 88);
		list.setFixedCellWidth(176);
		list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		list.setCellRenderer(new TaskCell(blocked));
		list.addFocusListener(new FocusAdapter()
		{
			@Override public void focusGained(FocusEvent event)
			{
				if (list.isSelectionEmpty())
				{
					list.setSelectedIndex(Math.max(0, list.getFirstVisibleIndex()));
				}
				scrollFocusedControl(list);
			}
		});
		list.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent event)
			{
				int index = list.locationToIndex(event.getPoint());
				Rectangle row = index < 0 ? null : list.getCellBounds(index, index);
				if (SwingUtilities.isLeftMouseButton(event) && row != null && row.contains(event.getPoint()))
				{
					list.setSelectedIndex(index);
					openTask(ids.get(index), ids, origin);
				}
			}
		});
		list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-task");
		list.getActionMap().put("open-task", new AbstractAction()
		{
			@Override public void actionPerformed(ActionEvent event)
			{
				Integer id = list.getSelectedValue();
				if (id != null)
				{
					openTask(id, ids, origin);
				}
			}
		});
		TaskListArea section = new TaskListArea(list);
		section.fit(getScrollPane().getViewport().getExtentSize().height);
		if (blocked)
		{
			blockedArea = section;
		}
		else
		{
			checklistArea = section;
		}
		return section;
	}

	private void fitTaskLists()
	{
		if (closed)
		{
			return;
		}
		int height = getScrollPane().getViewport().getExtentSize().height;
		boolean checklistChanged = checklistArea != null && checklistArea.fit(height);
		boolean blockedChanged = blockedArea != null && blockedArea.fit(height);
		if (checklistChanged || blockedChanged)
		{
			revalidate();
			repaint();
		}
	}

	private static final class TaskListArea extends JPanel
	{
		private final JList<Integer> list;
		private final JScrollPane scroller;
		private final JPanel jump = new JPanel(new GridLayout(1, 2, 5, 0));
		private int fittedHeight;

		TaskListArea(JList<Integer> list)
		{
			this.list = list;
			setOpaque(false);
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			scroller = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			scroller.setBorder(BorderFactory.createLineBorder(BORDER));
			scroller.setViewportBorder(null);
			scroller.setWheelScrollingEnabled(false);
			scroller.getVerticalScrollBar().setFocusable(false);
			scroller.addMouseWheelListener(event -> scrollTasks(scroller.getVerticalScrollBar(), event));
			jump.setOpaque(false);
			jump.setBorder(new EmptyBorder(0, 0, 4, 0));
			JScrollBar bar = scroller.getVerticalScrollBar();
			JButton top = button("Top of list", () -> bar.setValue(bar.getMinimum()));
			JButton bottom = button("Bottom of list", () -> bar.setValue(bar.getMaximum()));
			top.setMargin(new Insets(0, 3, 0, 3));
			bottom.setMargin(new Insets(0, 3, 0, 3));
			top.setFocusable(true);
			bottom.setFocusable(true);
			jump.add(top);
			jump.add(bottom);
			top.setName(list.getName() + "-top");
			bottom.setName(list.getName() + "-bottom");
			add(jump);
			add(scroller);
		}

		boolean fit(int viewportHeight)
		{
			int content = list.getModel().getSize() * list.getFixedCellHeight() + 2;
			int height = COMPACT_LIST_HEIGHT;
			if (viewportHeight > 0)
			{
				// Keep room for the list disclosure and jump buttons at small window sizes.
				height = Math.min(Math.max(list.getFixedCellHeight(), viewportHeight - 70),
					Math.max(COMPACT_LIST_HEIGHT, viewportHeight - 210));
			}
			height = Math.min(height, content);
			if (height == fittedHeight)
			{
				return false;
			}
			fittedHeight = height;
			scroller.setPreferredSize(new Dimension(180, height));
			scroller.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			jump.setVisible(height < content);
			// The enclosing BoxLayouts cache child sizes across viewport resizes.
			for (Container parent = this; parent != null && !(parent instanceof JViewport); parent = parent.getParent())
			{
				parent.invalidate();
			}
			return true;
		}
	}

	private static void scrollTasks(JScrollBar bar, MouseWheelEvent event)
	{
		int direction = event.getWheelRotation() < 0 ? -1 : 1;
		int step = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
			? bar.getBlockIncrement(direction)
			: 16 * event.getScrollAmount();
		bar.setValue(bar.getValue() + (int)Math.round(event.getPreciseWheelRotation() * step));
		event.consume();
	}

	private final class TaskCell extends JPanel implements ListCellRenderer<Integer>
	{
		private final boolean blocked;
		private final JPanel card = new JPanel(new BorderLayout(4, 0));
		private final JPanel labels = new JPanel();
		private final JTextArea title = text("", TEXT, 13, true, TASK_CARD_TEXT_WIDTH);
		private final JTextArea caption = text("", MUTED, 12, false, TASK_CARD_TEXT_WIDTH);
		private final Border standard = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
			new EmptyBorder(5, 8, 4, 6));
		private final Border highlighted = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GOLD),
			new EmptyBorder(5, 8, 4, 6));

		TaskCell(boolean blocked)
		{
			super(new BorderLayout());
			this.blocked = blocked;
			setBackground(BACKGROUND);
			setBorder(new EmptyBorder(3, 3, 2, 3));
			card.setBackground(CARD);
			labels.setLayout(null);
			labels.setOpaque(false);
			labels.add(title);
			labels.add(caption);
			card.add(labels, BorderLayout.CENTER);
			JLabel arrow = new JLabel("›", JLabel.CENTER);
			arrow.setForeground(TEXT);
			arrow.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
			arrow.setPreferredSize(new Dimension(12, 26));
			card.add(arrow, BorderLayout.EAST);
			add(card, BorderLayout.CENTER);
		}

		@Override public void validate()
		{
			// The renderer is reused by the list and can be painted before it has a native peer.
			doLayout();
			card.doLayout();
			int width = labels.getWidth(), height = labels.getHeight();
			title.setSize(width, height);
			int titleHeight = Math.min(height, title.getPreferredSize().height + 2);
			title.setBounds(0, 0, width, titleHeight);
			caption.setBounds(0, titleHeight, width, height - titleHeight);
		}

		@Override public Component getListCellRendererComponent(JList<? extends Integer> list, Integer id, int index,
			boolean selected, boolean cellHasFocus)
		{
			title.setText(catalogue.tasks.get(id).name);
			String owner = catalogue.owner(id);
			String blocker = shownRoute.blockedTasks.get(id);
			if (blocked)
			{
				String reason = blocker == null ? "Review preparation" : shortKitCheck(blocker);
				caption.setText(reason.length() > 95 ? reason.substring(0, 92).stripTrailing() + "…" : reason);
				caption.setForeground(GOLD);
			}
			else
			{
				String detail = taskPoints(id) + " · " + taskType(id);
				if (completedIDs.contains(id))
				{
					detail += " · Complete";
				}
				else if (skippedIDs.contains(id) || shownState.skipped(owner))
				{
					detail += " · Skipped";
				}
				else if (blocker != null)
				{
					detail += " · Needs prep";
				}
				String activityName = selectedActivity.isEmpty() ? catalogue.label(owner) + " · " : "";
				caption.setText(activityName + detail);
			}
			boolean firstSuggestion = !blocked && index == 0 && scope.equals(SUGGESTED) && query.isEmpty();
			card.setBorder(selected || cellHasFocus || firstSuggestion ? highlighted : standard);
			card.setBackground(selected ? SELECTED_CARD : CARD);
			getAccessibleContext().setAccessibleName(title.getText() + ". " + caption.getText());
			return this;
		}
	}

	private void rememberListPositions()
	{
		if (checklistArea != null)
		{
			checklistPosition = ListPosition.capture(checklistArea.list);
			checklistArea = null;
		}
		if (blockedArea != null)
		{
			blockedPosition = ListPosition.capture(blockedArea.list);
			blockedArea = null;
		}
	}

	private void resetListPositions()
	{
		checklistArea = null;
		blockedArea = null;
		checklistPosition = ListPosition.TOP;
		blockedPosition = ListPosition.TOP;
	}

	private static final class ListPosition
	{
		private static final ListPosition TOP = new ListPosition(-1, 0, 0, null);
		private final int id, offset, y;
		private final Integer selected;

		private ListPosition(int id, int offset, int y, Integer selected)
		{
			this.id = id;
			this.offset = offset;
			this.y = y;
			this.selected = selected;
		}

		private static ListPosition capture(JList<Integer> list)
		{
			JViewport viewport = (JViewport)list.getParent();
			int y = viewport.getViewPosition().y;
			int row = Math.min(list.getModel().getSize() - 1, y / list.getFixedCellHeight());
			return row < 0 ? TOP : new ListPosition(list.getModel().getElementAt(row),
				y - row * list.getFixedCellHeight(), y, list.getSelectedValue());
		}

		private void restore(JList<Integer> list)
		{
			if (list == null)
			{
				return;
			}
			JViewport viewport = (JViewport)list.getParent();
			int position = y;
			for (int i = 0; i < list.getModel().getSize(); i++)
			{
				if (list.getModel().getElementAt(i) == id)
				{
					position = i * list.getFixedCellHeight() + offset;
					break;
				}
			}
			int max = Math.max(0,
				list.getModel().getSize() * list.getFixedCellHeight() - viewport.getExtentSize().height);
			list.setSelectedValue(selected, false);
			viewport.setViewPosition(new Point(0, Math.max(0, Math.min(position, max))));
		}
	}

	private String taskPoints(int id)
	{
		int points = catalogue.tasks.get(id).points;
		return "+" + points + (points == 1 ? " pt" : " pts");
	}

	private String taskType(int id)
	{
		return catalogue.reserve(id) ? "Reserve" : "Selected";
	}

	private void activityOptions(JPanel box, String key, GuideState state)
	{
		String pinned = ReadinessEngine.str(state.data, "pinnedActivity", "");
		if (catalogue.groupOption(key, state.rewardGoal()))
		{
			box.add(text(ReadinessEngine.str(catalogue.activity(key), "groupOption", ""), MUTED, 11, false));
		}
		addRequirements(box, activityRequirements(key, state, shownLive));
		box.add(button(key.equals(pinned) ? "Stop following activity" : "Follow this activity",
			savedAction(() -> actions.follow(key.equals(pinned) ? "" : key))));
		boolean skipped = state.skipped(key);
		box.add(button(skipped ? "Restore activity" : "Skip this activity",
			savedAction(() -> actions.skip(key, !skipped))));
	}

	private List<String> activityRequirements(String key, GuideState state, LiveSnapshot live)
	{
		List<String> requirements = planner.access(key, state, live);
		requirements.addAll(planner.strategy(key, state, live));
		return requirements;
	}

	private void openTask(int id, List<Integer> ids, TaskOrigin origin)
	{
		int index = ids.indexOf(id);
		if (index < 0)
		{
			return;
		}
		focusedTasks = List.copyOf(ids);
		extendWithSuggestedTasks();
		returnOrigin = origin;
		taskReturnAnchor = captureAnchor(getScrollPane().getViewport().getViewPosition().y);
		selectFocusedTask(index);
		focusTarget = "back-from-task";
		redraw();
	}

	private void extendWithSuggestedTasks()
	{
		Set<Integer> sequence = new LinkedHashSet<>(focusedTasks);
		if (sequence.addAll(plannedTasks("")))
		{
			focusedTasks = List.copyOf(sequence);
		}
	}

	private void advanceCompletedTask()
	{
		int next = nextTaskIndex(focusedIndex + 1);
		if (next == focusedTasks.size() && !shownNext.ids.isEmpty())
		{
			next = focusedTasks.indexOf(shownNext.ids.get(0));
		}
		if (shownTotal < catalogue.threshold(shownState.rewardGoal()) && next >= 0 && next < focusedTasks.size())
		{
			selectFocusedTask(next);
			focusTarget = "back-from-task";
			return;
		}
		focusedTasks = List.of();
		taskSection = null;
		taskReturnAnchor = null;
		revealAnchor = "start";
		focusTarget = "Checklist";
	}

	private JPanel taskNavigation(int next)
	{
		JPanel navigation = card();
		navigation.setBorder(new EmptyBorder(6, 8, 6, 8));
		JButton back = button("‹ Back to checklist", this::backToChecklist);
		back.setName("back-from-task");
		navigation.add(back);
		navigation.add(gap(5));
		JPanel step = new JPanel(new GridLayout(1, 2, 5, 0));
		step.setOpaque(false);
		JButton previous = button("‹ Previous", () -> moveTask(focusedIndex - 1));
		previous.setEnabled(focusedIndex > 0);
		JButton forward = primaryButton("Next task ›", () -> moveTask(next));
		forward.setEnabled(next < focusedTasks.size());
		step.add(previous);
		step.add(forward);
		navigation.add(step);
		align(navigation);
		return navigation;
	}

	private int nextTaskIndex(int next)
	{
		while (next < focusedTasks.size() && !canTrackTask(focusedTasks.get(next)))
		{
			next++;
		}
		return next;
	}

	private boolean canTrackTask(int id)
	{
		String key = catalogue.owner(id);
		return catalogue.includes(id, shownState.rewardGoal()) && !completedIDs.contains(id) &&
			!skippedIDs.contains(id) && !shownState.skipped(key) &&
			(!RiskPolicy.wilderness(key) || shownState.wildernessEnabled()) &&
			(!catalogue.needsGroup(id, shownState.rewardGoal()) || shownState.routeChoice() == RouteStyle.GROUP);
	}

	private void selectFocusedTask(int index)
	{
		focusedIndex = index;
		taskSection = shownRoute.blockedTasks.containsKey(focusedTasks.get(index)) ? TaskSection.KIT : null;
		revealAnchor = "focused-task";
	}

	private void moveTask(int index)
	{
		if (index >= 0 && index < focusedTasks.size())
		{
			selectFocusedTask(index);
			if (nextTaskIndex(index + 1) == focusedTasks.size())
			{
				focusTarget = "back-from-task";
			}
			redraw();
		}
	}

	private void upcomingTasks(int next)
	{
		if (next == focusedTasks.size())
		{
			return;
		}
		add(gap(8));
		add(text("Upcoming tasks", GOLD, 13, true));
		for (int count = 0; count < UPCOMING_TASKS && next < focusedTasks.size(); count++)
		{
			int index = next, id = focusedTasks.get(index);
			String name = catalogue.tasks.get(id).name;
			JPanel box = card();
			box.putClientProperty("guideAnchor", "upcoming-task-" + id);
			String preparation = shownRoute.blockedTasks.containsKey(id) ? " · Needs prep" : "";
			box.add(text(catalogue.label(catalogue.owner(id)) + " · " + taskPoints(id) + preparation, MUTED, 11, false));
			box.add(text(name, TEXT, 14, true));
			box.add(text(catalogue.rule(id).get("condition").getAsString(), TEXT, 12, false));
			box.add(gap(3));
			JButton open = button("Open task", () ->
			{
				focusTarget = "back-from-task";
				moveTask(index);
			});
			open.setName("open-upcoming-task-" + id);
			open.getAccessibleContext().setAccessibleName("Open task " + name);
			box.add(open);
			add(gap(4));
			add(box);
			next = nextTaskIndex(index + 1);
		}
	}

	private void backToChecklist()
	{
		focusedTasks = List.of();
		taskSection = null;
		if (returnOrigin == TaskOrigin.PRIMARY)
		{
			revealAnchor = "checklist";
			focusTarget = "search";
		}
		else
		{
			returnAnchor = taskReturnAnchor;
			if (returnAnchor == null)
			{
				revealAnchor = returnOrigin == TaskOrigin.BLOCKED ? "blocked-tasks" : "task-results";
			}
			focusTarget = returnOrigin == TaskOrigin.BLOCKED ? "blocked-tasks" : "checklist-tasks";
		}
		taskReturnAnchor = null;
		redraw();
	}

	private void focusedTask(GuideState state, LiveSnapshot live)
	{
		int id = focusedTasks.get(focusedIndex);
		String key = catalogue.owner(id);
		JsonObject activity = catalogue.activity(key), rule = catalogue.rule(id);
		GuideCatalog.Task task = catalogue.tasks.get(id);
		JPanel box = card();
		box.putClientProperty("guideAnchor", "focused-task");
		box.add(text(catalogue.label(key) + " · " + taskType(id) + " · " + taskPoints(id), MUTED, 11, false));
		box.add(text(task.name, TEXT, 16, true));
		boolean complete = completedIDs.contains(id), skipped = skippedIDs.contains(id);
		if (complete)
		{
			String evidence = syncedIDs.contains(id) ? (live != null && live.ca.valid && live.ca.completed.contains(id)
				? "Complete" : "Previously completed") : "Completion saved";
			box.add(text(evidence, GREEN, 12, true));
		}
		else if (skipped || state.skipped(key))
		{
			box.add(text(skipped ? "Task skipped" : "Activity skipped", GOLD, 12, true));
		}
		addNotices(box);
		if (id == 147)
		{
			ReadinessEngine.Check cape = readiness.token("fire-cape", state, live, Instant.now());
			if (cape.ready())
			{
				String found = cape.fromBank ? "Fire cape found in your bank." : "Fire cape on hand.";
				box.add(text(found + " Achievement credit comes from the game’s record.", MUTED, 12, false));
			}
		}
		List<String> requirements = activityRequirements(key, state, live);
		requirements.addAll(planner.taskGates(id, state, live));
		if (!complete)
		{
			List<String> kitChecks = requirements.isEmpty() ? planner.requiredTaskKit(id, state, live) : List.of();
			if (!requirements.isEmpty() || !kitChecks.isEmpty())
			{
				String first = shortKitCheck(requirements.isEmpty() ? kitChecks.get(0) : requirements.get(0));
				box.add(text("Prepare before attempting", GOLD, 12, true));
				box.add(text(first, TEXT, 12, false));
			}
			List<String> fromBank = new ArrayList<>();
			for (Planner.ItemRequirement item : planner.itemRequirements(key, List.of(id), state, live))
			{
				if (item.required && item.check.fromBank)
				{
					fromBank.add(item.check.label);
				}
			}
			if (!fromBank.isEmpty())
			{
				box.add(text("Bring from your bank", GOLD, 12, true));
				box.add(text(String.join(", ", fromBank), TEXT, 12, false));
			}
		}
		box.add(gap(5));
		box.add(text(rule.get("condition").getAsString(), TEXT, 13, false));
		box.add(text(rule.get("tip").getAsString(), MUTED, 12, false));
		String risk = live == null ? "" : RiskPolicy.advice(key, live.mode);
		boolean showRisk = live != null && (RiskPolicy.wilderness(key) || live.mode.hardcore());
		if (showRisk)
		{
			box.add(text(risk, GOLD, 12, false));
		}
		add(box);
		if (canTrackTask(id))
		{
			boolean showing = state.screenTask() == id;
			add(gap(6));
			add(button(showing ? "Clear on-screen task" : "Show task on screen",
				savedAction(() -> actions.screenTask(showing ? -1 : id))));
		}
		addTaskSection("Requirements & kit", TaskSection.KIT, content ->
		{
			if (state.rewardGoal() == RewardGoal.HARD)
			{
				for (String line : planner.requirementSummary(id, state))
				{
					content.add(text(line, TEXT, 12, false));
				}
				if (rule.has("minimumParty"))
				{
					content.add(text("Party of " + rule.get("minimumParty").getAsInt() + " or more", TEXT, 12, false));
				}
			}
			addRequirements(content, requirements);
			if (!showRisk && !risk.isEmpty())
			{
				content.add(text(risk, MUTED, 11, false));
			}
			if (catalogue.groupOption(key, state.rewardGoal()))
			{
				content.add(text(ReadinessEngine.str(activity, "groupOption", ""), MUTED, 11, false));
			}
			kit(content, activity, state, live, List.of(id));
		});
		addTaskSection("Walkthrough & sources", TaskSection.WALKTHROUGH, content ->
		{
			if (rule.getAsJsonArray("sources").size() > 0)
			{
				content.add(link("Exact task rule", rule.getAsJsonArray("sources").get(0).getAsJsonObject()
					.get("url").getAsString()));
			}
			if (rule.has("plan"))
			{
				content.add(text(rule.get("plan").getAsString(), MUTED, 12, false));
			}
			walkthrough(content, activity);
		});
		addTaskSection("Task options", TaskSection.OPTIONS, content ->
		{
			content.add(text(task.tier + " · Task " + id, MUTED, 12, false));
			if (!complete)
			{
				content.add(button(skipped ? "Restore task to route" : "Skip this task",
					savedAction(() -> actions.skipTask(id, !skipped))));
			}
			JCheckBox check = checkbox("Confirm completion manually", complete);
			check.setEnabled(!syncedIDs.contains(id) && !baseIDs.contains(id));
			check.setToolTipText("Confirm only after the game awards this task");
			Runnable change = savedAction(() -> actions.manual(id, check.isSelected()));
			check.addActionListener(event -> change.run());
			content.add(check);
		});
	}

	private void addTaskSection(String label, TaskSection section, Consumer<JPanel> populate)
	{
		JPanel panel = card();
		boolean expanded = taskSection == section;
		panel.add(disclosure(label, expanded, () ->
		{
			taskSection = expanded ? null : section;
			redraw();
		}));
		if (expanded)
		{
			panel.add(gap(6));
			populate.accept(panel);
		}
		add(gap(6));
		add(panel);
	}

	private static void addRequirements(JPanel panel, List<String> requirements)
	{
		for (String requirement : requirements)
		{
			panel.add(text(requirement, GOLD, 12, false));
		}
	}

	private void kit(JPanel box, JsonObject activity, GuideState state, LiveSnapshot live, List<Integer> ids)
	{
		String key = activity.get("key").getAsString();
		box.add(text("Kit checklist", GOLD, 13, true));
		JsonArray kit = catalogue.kit(key);
		if (activity.has("recommended"))
		{
			List<String> stats = new ArrayList<>();
			for (Map.Entry<String, JsonElement> skill : activity.getAsJsonObject("recommended").entrySet())
			{
				stats.add(skill.getValue().getAsInt() + " " + skill.getKey());
			}
			if (!stats.isEmpty())
			{
				box.add(text("Suggested stats\n" + String.join(", ", stats), MUTED, 12, false));
			}
		}
		if (kit.size() == 0)
		{
			box.add(text("See the walkthrough for task-specific setup.", MUTED, 12, false));
		}
		for (int i = 0; i < kit.size(); i++)
		{
			JsonObject requirement = kit.get(i).getAsJsonObject();
			ReadinessEngine.Check result = readiness.check(key, i, requirement, state, live, Instant.now(), ids);
			if (ReadinessEngine.UNUSED.equals(result.status))
			{
				continue;
			}
			if (planner.permanentKeyAccess(key, state) && result.label.matches("Giant key|Mossy key"))
			{
				result = new ReadinessEngine.Check(
					result.label, ReadinessEngine.READY,
					"A recorded boss task establishes the permanent first-key unlock. Four-key reserves still need four keys.",
					1);
			}
			String role = ReadinessEngine.str(requirement, "role", "");
			boolean suggested = !result.ready() && !role.equals("required_task") && !role.equals("required_strategy");
			box.add(text(result.label + " — " + (suggested ? "Suggested" : result.displayStatus()),
				suggested ? MUTED : result.ready() ? GREEN : GOLD, 12, true));
			if ("required_task".equals(role))
			{
				box.add(text("Required for this task", TEXT, 12, false));
			}
			else if ("required_strategy".equals(role))
			{
				box.add(text("Required for this guide’s method", TEXT, 12, false));
			}
			if (!suggested)
			{
				box.add(text(result.detail, MUTED, 12, false));
			}
			boolean setupCheck = ReadinessEngine.taskSetup(requirement);
			if (requirement.has("notes") && !setupCheck && !ReadinessEngine.PROVIDED.equals(result.status))
			{
				box.add(text(requirement.get("notes").getAsString(), MUTED, 12, false));
			}
			final int index = i;
			if (!setupCheck && !ReadinessEngine.PROVIDED.equals(result.status))
			{
				boolean manual = requirement.has("manualCheck");
				JButton correct = button(manual ? "Confirm requirement…" : "Correct item check…",
					() -> correctReadiness(key, index, manual));
				correct.setName("kit-check-" + i);
				correct.getAccessibleContext().setAccessibleName("Correct check for " + result.label);
				correct.setEnabled(available);
				box.add(correct);
			}
			box.add(gap(4));
		}
	}
	private void walkthrough(JPanel box, JsonObject activity)
	{
		for (JsonElement intro : activity.getAsJsonArray("intro"))
		{
			box.add(text(intro.getAsString(), MUTED, 12, false));
		}

		box.add(text("How to do it", GOLD, 13, true));
		int number = 1;
		for (JsonElement step : catalogue.steps(activity, shownState.rewardGoal()))
		{
			box.add(text((number++) + ". " + step.getAsString(), TEXT, 12, false));
		}

		for (JsonElement video : activity.getAsJsonArray("videos"))
		{
			JsonObject source = video.getAsJsonObject();
			box.add(link(ReadinessEngine.str(source, "label", "Watch source demonstration"),
							source.get("url").getAsString()));
		}
		box.add(link("Boss guide on the Wiki", activity.get("bossSource").getAsString()));
		if (catalogue.guide.getAsJsonArray("referenceFigures").size() > 0)
		{
			box.add(button("Open reference image source…", this::showFigures));
		}
	}
	private void skippedItems(GuideState state)
	{
		JPanel box = card();
		String anchor = "skipped";
		box.putClientProperty("guideAnchor", anchor);
		box.add(text("Skipped tasks & activities", TEXT, 15, true));
		JButton back = button("Back to checklist", () ->
		{
			showingSkipped = false;
			revealAnchor = "checklist";
			focusTarget = "search";
			redraw();
		});
		back.setName("back-from-skips");
		box.add(back);
		List<String> activities = new ArrayList<>();
		for (JsonObject activity : catalogue.activities(shownState.rewardGoal()))
		{
			String key = activity.get("key").getAsString();
			if (state.skipped(key))
			{
				activities.add(key);
			}
		}
		List<Integer> tasks = new ArrayList<>(skippedIDs);
		int total = activities.size() + tasks.size();
		int end = pageEnd(total);
		box.add(text(total == 0 ? "Nothing is skipped." : range(end, total) + " skipped items", MUTED, 11, false));
		for (int i = skippedOffset; i < end; i++)
		{
			if (i < activities.size())
			{
				String activity = activities.get(i);
				box.add(text(catalogue.label(activity), TEXT, 12, true));
				JButton restore = button("Restore activity", savedAction(() -> actions.skip(activity, false)));
				restore.setName("restore-" + activity);
				restore.getAccessibleContext().setAccessibleName("Restore " + catalogue.label(activity));
				box.add(restore);
			}
			else
			{
				int id = tasks.get(i - activities.size());
				String activity = catalogue.owner(id);
				box.add(text(catalogue.tasks.get(id).name + " · " + catalogue.label(activity), TEXT, 12, true));
				if (state.skipped(activity))
				{
					box.add(text("Restore the activity too to include it in suggestions.", MUTED, 11, false));
				}
				JButton restore = button("Restore task", savedAction(() -> actions.skipTask(id, false)));
				restore.setName("restore-" + id);
				restore.getAccessibleContext().setAccessibleName("Restore " + catalogue.tasks.get(id).name);
				box.add(restore);
			}
		}
		pageButtons(box, end, total, anchor, "skips");
		add(gap(8));
		add(box);
	}

	private int pageEnd(int count)
	{
		int last = Math.max(0, (count - 1) / SKIP_PAGE_SIZE * SKIP_PAGE_SIZE);
		skippedOffset = Math.min(skippedOffset, last);
		return Math.min(count, skippedOffset + SKIP_PAGE_SIZE);
	}

	private String range(int end, int count)
	{
		return (skippedOffset + 1) + "–" + end + " of " + count;
	}

	private void pageButtons(JPanel box, int end, int total, String anchor, String noun)
	{
		if (skippedOffset > 0)
		{
			box.add(button("Previous " + noun, () -> page(-SKIP_PAGE_SIZE, anchor)));
		}
		if (end < total)
		{
			box.add(button("Next " + noun, () -> page(SKIP_PAGE_SIZE, anchor)));
		}
	}

	private void page(int change, String anchor)
	{
		skippedOffset = Math.max(0, skippedOffset + change);
		revealAnchor = anchor;
		redraw();
	}

	private void correctReadiness(String activity, int index, boolean manual)
	{
		String intended = session();
		String[] options = manual ? new String[] {"Automatic", "Confirmed", "Still needed"} :
			new String[] {"Automatic", "Owned", "Missing", "Not required"};
		Object choice = JOptionPane.showInputDialog(
			this, manual ? "Inspect this requirement in game before confirming it." :
				"Choose how to check this item for your account.",
			"Preparation correction", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		if (choice == null || !stillCurrent(intended))
		{
			return;
		}
		String status = choice.toString();
		if (manual && !status.equals("Automatic"))
		{
			status = status.equals("Confirmed") ? "Owned" : "Missing";
		}
		actions.correction("overrides", activity + ":" + index, status.equals("Automatic") ? null : readinessOverride(status));
	}

	private static JsonObject readinessOverride(String status)
	{
		JsonObject value = new JsonObject();
		value.addProperty("status", status);
		value.addProperty("at", Instant.now().toString());
		return value;
	}
	private void editPreparation()
	{
		String intended = session();
		String[] categories = {"levels", "quests", "items"};
		Object category =
			JOptionPane.showInputDialog(this, "Choose what to update.", "Account preparation",
										JOptionPane.PLAIN_MESSAGE, null, categories, categories[0]);
		if (category == null)
		{
			return;
		}
		JsonArray keys = catalogue.route().getAsJsonArray(category.equals("levels") ? "skills" : category.toString());
		List<String> names = new ArrayList<>();
		for (JsonElement e : keys)
		{
			names.add(e.getAsString());
		}
		Object key = JOptionPane.showInputDialog(this, "Choose a field. Use Automatic to return to observed data.",
													"Preparation field", JOptionPane.PLAIN_MESSAGE, null, names.toArray(),
													names.get(0));
		if (key == null)
		{
			return;
		}
		if (category.equals("levels"))
		{
			String input =
				JOptionPane.showInputDialog(this, "Level 1–126, or blank for Automatic:",
											shownState.object("levels").has(key.toString())
												? shownState.object("levels").get(key.toString()).getAsString()
												: "");
			if (input == null)
			{
				return;
			}
			try
			{
				Integer n = input.trim().isEmpty() ? null : Integer.valueOf(input.trim());
				if (n != null && (n < 1 || n > 126))
				{
					throw new NumberFormatException();
				}
				if (stillCurrent(intended))
				{
					actions.correction("levels", key.toString(), n == null ? null : new JsonPrimitive(n));
				}
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(this, "Enter a whole level from 1 to 126.");
			}
		}
		else
		{
			String[] options = {"Automatic", "Confirmed yes", "Confirmed no"};
			Object choice = JOptionPane.showInputDialog(
				this, "Confirm only what is available to this character:", "Saved correction",
				JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
			if (choice != null && stillCurrent(intended))
			{
				actions.correction(category.toString(), key.toString(),
									choice.equals("Automatic") ? null
																: new JsonPrimitive(choice.equals("Confirmed yes")));
			}
		}
	}
	private void reviewReconciliation()
	{
		if (shownLive == null || !shownLive.ca.valid)
		{
			return;
		}
		String intended = session();
		Set<Integer> conflicts = progress.conflicts(shownState, shownLive.ca.completed, shownLive.ca.observed);
		String body = "Saved points   " + progress.total(shownState) + "\nGame points   " + shownLive.ca.gamePoints +
			"\nTasks that differ   " + conflicts.size() +
			"\n\nUse the game’s current points and completions? The guide saves a copy before changing your progress.";
		if (JOptionPane.showConfirmDialog(this, body, "Review game progress",
											JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION &&
			stillCurrent(intended))
		{
			actions.reconcile();
		}
	}
	private void showPreparation()
	{
		StringBuilder b = new StringBuilder();
		for (JsonElement e : catalogue.preparation(shownState.rewardGoal()))
		{
			JsonObject o = e.getAsJsonObject();
			b.append(o.get("text").getAsString()).append("\n\n");
			for (JsonElement s : o.getAsJsonArray("sources"))
			{
				b.append(s.getAsJsonObject().get("url").getAsString()).append('\n');
			}
		}
		showText("General preparation", b.toString());
	}
	private void showFigures()
	{
		String intended = session();
		List<String> captions = new ArrayList<>();
		for (JsonElement e : catalogue.guide.getAsJsonArray("referenceFigures"))
		{
			captions.add(e.getAsJsonObject().get("caption").getAsString());
		}
		Object chosen = JOptionPane.showInputDialog(this, "Choose an original source image:", "Reference images",
			JOptionPane.PLAIN_MESSAGE, null, captions.toArray(), captions.get(0));
		if (chosen == null || !stillCurrent(intended))
		{
			return;
		}
		int index = captions.indexOf(chosen.toString());
		JsonObject figure = catalogue.guide.getAsJsonArray("referenceFigures").get(index).getAsJsonObject();
		String url = figure.getAsJsonArray("sources").get(0).getAsJsonObject().get("url").getAsString();
		LinkBrowser.browse(url);
	}
	private void showText(String title, String value)
	{
		JTextArea body = new JTextArea(value);
		body.setEditable(false);
		body.setLineWrap(true);
		body.setWrapStyleWord(true);
		body.setForeground(TEXT);
		body.setBackground(BACKGROUND);
		body.setCaretPosition(0);
		body.setColumns(52);
		body.setRows(23);
		JScrollPane scroll = new JScrollPane(body);
		scroll.setPreferredSize(new Dimension(580, 470));
		JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
	}
	private void settings(GuideState state, LiveSnapshot live)
	{
		JPanel box = card();
		box.putClientProperty("guideAnchor", "account-settings");
		box.add(text("Account & backups", TEXT, 16, true));
		box.add(text(live == null ? "No active account" : live.name + " · " + live.mode.label, MUTED, 12, false));
		addNotices(box);
		if (live == null)
		{
			if (!status.isEmpty() && !SIGN_IN.equals(status))
			{
				box.add(button("Refresh game data", actions::refresh));
			}
			add(box);
			return;
		}

		JButton refresh = button("Refresh game data", actions::refresh);
		refresh.setName("refresh-game-data");
		box.add(gap(4));
		box.add(refresh);
		if (live.mode.supported())
		{
			box.add(text(bankSummary(live), MUTED, 12, false));
		}

		box.add(gap(8));
		box.add(text("Progress backups", GOLD, 13, true));
		JButton importButton = button("Import progress backup…", () -> choose(true));
		importButton.setEnabled(live.mode.supported());
		box.add(importButton);
		JButton exportButton = button("Export progress backup…", () -> choose(false));
		exportButton.setEnabled(available);
		box.add(exportButton);
		if (preview != null)
		{
			importPreview(box);
		}
		JButton rollback = button("Undo last import…", () ->
		{
			String intended = session();
			if (JOptionPane.showConfirmDialog(this,
				"Restore this account’s progress from before its last import?",
				"Undo progress import", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION &&
				stillCurrent(intended))
			{
				actions.undoImport();
			}
		});
		rollback.setEnabled(available);
		box.add(rollback);

		if (available)
		{
			box.add(gap(8));
			JCheckBox wild = checkbox("Include Wilderness reserves", state.wildernessEnabled());
			Runnable toggleWilderness = savedAction(() -> actions.wilderness(wild.isSelected()));
			wild.addActionListener(e -> toggleWilderness.run());
			box.add(wild);
			if (state.wildernessEnabled())
			{
				box.add(text("Wilderness tasks carry PvP and item-loss risk.", GOLD, 12, false));
			}
			box.add(gap(4));
			box.add(disclosure("Progress corrections", correctionsExpanded, () ->
			{
				correctionsExpanded = !correctionsExpanded;
				redraw();
			}));
			if (correctionsExpanded || hasProgressDifference())
			{
				JButton reconcile = button("Review saved vs game points…", this::reviewReconciliation);
				reconcile.setEnabled(live.ca.valid);
				box.add(reconcile);
			}
			if (correctionsExpanded)
			{
				box.add(button("Correct stats, quests or kit…", this::editPreparation));
				box.add(button("Clear prep corrections…", this::clearPreparation));
			}
		}
		add(box);
	}

	private static String bankSummary(LiveSnapshot live)
	{
		if (!live.mode.bankAllowed())
		{
			return "Kit checks use your inventory and equipment.";
		}
		return live.observedBank() == null ? ReadinessEngine.FIRST_BANK_CHECK : "Using your last bank scan.";
	}

	private void importPreview(JPanel box)
	{
		JPanel details = card();
		details.putClientProperty("guideAnchor", "import-preview");
		details.add(text("Review backup", GOLD, 13, true));
		String incomingName = preview.incoming.name();
		details.add(text("Backup · " + (incomingName.isEmpty() ? "Unassigned" : incomingName) +
			"\nAccount · " + preview.targetName + "\nSaved points · " + preview.points, TEXT, 12, false));
		if (preview.associationNeeded)
		{
			JCheckBox confirm = checkbox("Use for this account", associationConfirmed);
			confirm.addActionListener(e ->
			{
				associationConfirmed = confirm.isSelected();
				redraw();
			});
			details.add(confirm);
		}
		JButton apply = primaryButton("Import this backup", () -> actions.applyImport(preview, associationConfirmed));
		apply.setEnabled(!preview.associationNeeded || associationConfirmed);
		details.add(apply);
		details.add(button("Cancel import", () ->
		{
			preview = null;
			associationConfirmed = false;
			focusTarget = "Import progress backup…";
			redraw();
		}));
		box.add(details);
	}

	private void clearPreparation()
	{
		String intended = session();
		if (JOptionPane.showConfirmDialog(this,
			"Clear saved level, quest and item corrections for this account?",
			"Clear preparation corrections", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION &&
			stillCurrent(intended))
		{
			actions.clearCorrections();
		}
	}

	private Runnable savedAction(Runnable action)
	{
		String intended = session();
		return () ->
		{
			if (!intended.isEmpty() && intended.equals(session()) && available && !closed)
			{
				action.run();
			}
		};
	}

	private boolean stillCurrent(String intended)
	{
		if (!closed && active && intended.equals(shownSession) && shownLive != null && shownLive.mode.supported())
		{
			return true;
		}
		JOptionPane.showMessageDialog(
			this, "Account changed or became unavailable. Review the action again on the intended account.");
		return false;
	}
	private void choose(boolean importing)
	{
		String intended = session();
		Filepath.Chooser chooser =
			new Filepath.Chooser()
				.setAcceptsFiles()
				.addExtensionFilter("Progress JSON", "json")
				.setDefaultExtension("json")
				.setDialogTitle(importing ? "Choose your existing guide progress backup" : "Save progress backup");
		if (importing)
		{
			chooser.setIsOpen();
		}
		else
		{
			chooser.setIsSave().setFileName("OSRS_Hilt2_Progress.json");
		}
		List<Filepath> files = chooser.showDialog(this);
		if (!files.isEmpty() && stillCurrent(intended))
		{
			if (importing)
			{
				actions.importFile(files.get(0));
			}
			else
			{
				actions.exportFile(files.get(0));
			}
		}
	}
	public void preview(Migration.Preview preview)
	{
		if (closed || shownLive == null || !preview.targetKey.equals(shownAccount))
		{
			return;
		}
		this.preview = preview;
		focusedTasks = List.of();
		settingsOpen = true;
		associationConfirmed = false;
		revealAnchor = "import-preview";
		redraw();
	}
	public void importFinished()
	{
		preview = null;
		associationConfirmed = false;
		focusTarget = "Import progress backup…";
		redraw();
	}
	public String accountKey()
	{
		return shownAccount;
	}
	public String session()
	{
		return active ? shownSession : "";
	}
	@Override public void onDeactivate()
	{
		active = false;
	}
	@Override public void onActivate()
	{
		if (closed)
		{
			return;
		}
		active = true;
		Runnable latest = deferredRender;
		deferredRender = null;
		if (latest != null)
		{
			latest.run();
		}
	}
	public void close()
	{
		closed = true;
		deferredRender = null;
		shownSession = "";
		shownAccount = "";
		preview = null;
		shownLive = null;
		available = false;
		renderNumber++;
	}
	private void redraw()
	{
		forceRender = true;
		render(shownState, shownLive, status, message, available);
	}
	private ScrollAnchor captureAnchor(int scroll)
	{
		List<Component> anchors = new ArrayList<>();
		collectAnchors(this, anchors);
		Component chosen = null;
		for (Component component : anchors)
		{
			if (anchorTop(component) <= scroll && (chosen == null || anchorTop(component) >= anchorTop(chosen)))
			{
				chosen = component;
			}
		}
		return chosen == null ? null
								: new ScrollAnchor(((JComponent)chosen).getClientProperty("guideAnchor").toString(),
													scroll - anchorTop(chosen));
	}
	private int anchorTop(Component component)
	{
		return SwingUtilities.convertPoint(component, 0, 0, getScrollPane().getViewport().getView()).y;
	}
	private static void collectAnchors(Container parent, List<Component> anchors)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JComponent && ((JComponent)child).getClientProperty("guideAnchor") != null)
			{
				anchors.add(child);
			}
			if (child instanceof Container)
			{
				collectAnchors((Container)child, anchors);
			}
		}
	}
	private static Component findAnchor(Container parent, String key)
	{
		List<Component> anchors = new ArrayList<>();
		collectAnchors(parent, anchors);
		for (Component component : anchors)
		{
			if (key.equals(((JComponent)component).getClientProperty("guideAnchor")))
			{
				return component;
			}
		}
		return null;
	}
	private static final class ScrollAnchor
	{
		private final String key;
		private final int offset;
		private ScrollAnchor(String key, int offset)
		{
			this.key = key;
			this.offset = offset;
		}
	}
	private static void align(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JComponent && parent.getLayout() instanceof BoxLayout)
			{
				((JComponent)child).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
			if (child instanceof JComboBox)
			{
				((JComboBox<?>)child).setMinimumSize(new Dimension(0, 28));
			}
			if (child instanceof Container)
			{
				align((Container)child);
			}
		}
	}
	private static JPanel card()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(CARD);
		p.setBorder(new EmptyBorder(8, 8, 8, 8));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}
	private static Component gap(int height)
	{
		return Box.createRigidArea(new Dimension(0, height));
	}
	private static JTextArea text(String value, Color color, int size, boolean bold)
	{
		return text(value, color, size, bold, 190);
	}

	private static JTextArea text(String value, Color color, int size, boolean bold, int initialWidth)
	{
		JTextArea text = new WrappedText(value, initialWidth);
		text.setEditable(false);
		text.setFocusable(false);
		text.setOpaque(false);
		text.setForeground(color);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size));
		text.setColumns(20);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.setBorder(new EmptyBorder(2, 0, 3, 0));
		return text;
	}
	/** Measures wrapped height at the real sidebar width, including nested task cards. */
	private static final class WrappedText extends JTextArea
	{
		private final int initialWidth;

		WrappedText(String text, int initialWidth)
		{
			super(text);
			this.initialWidth = initialWidth;
		}

		@Override public void scrollRectToVisible(Rectangle bounds)
		{
			// The caret of a read-only label must never scroll the sidebar.
		}

		@Override public Dimension getPreferredSize()
		{
			int width = initialWidth, margins = 0;
			// Measure new cards at the width of their nearest laid-out ancestor.
			for (Container parent = getParent(); parent != null; parent = parent.getParent())
			{
				Insets insets = parent.getInsets();
				margins += insets.left + insets.right;
				if (parent.getLayout() instanceof BorderLayout)
				{
					BorderLayout layout = (BorderLayout)parent.getLayout();
					for (String side : new String[] {BorderLayout.WEST, BorderLayout.EAST})
					{
						Component other = layout.getLayoutComponent(side);
						if (other != null)
						{
							margins += other.getPreferredSize().width + layout.getHgap();
						}
					}
				}
				if (parent.getWidth() > 0)
				{
					width = parent.getWidth() - margins;
					break;
				}
			}
			width = Math.max(60, width);
			Insets insets = getInsets();
			javax.swing.text.View view = getUI().getRootView(this);
			view.setSize(Math.max(1, width - insets.left - insets.right), Float.MAX_VALUE);
			int height =
				(int)Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom;
			return new Dimension(width, height);
		}
		@Override public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
		@Override public Dimension getMinimumSize()
		{
			return new Dimension(0, getPreferredSize().height);
		}
	}
	private static JButton button(String label, Runnable action)
	{
		JButton b = new JButton(label);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		b.setToolTipText(label);
		b.setForeground(TEXT);
		b.setBackground(CARD);
		b.setMargin(new Insets(3, 7, 3, 7));
		b.addFocusListener(KEEP_FOCUS_VISIBLE);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		b.addActionListener(e -> action.run());
		return b;
	}

	private static JButton disclosure(String label, boolean expanded, Runnable action)
	{
		JButton toggle = button((expanded ? "▾ " : "▸ ") + label, action);
		toggle.setHorizontalAlignment(JButton.LEFT);
		toggle.setActionCommand(label);
		toggle.getAccessibleContext().setAccessibleName(label + (expanded ? ", expanded" : ", collapsed"));
		return toggle;
	}

	private static JButton primaryButton(String label, Runnable action)
	{
		JButton button = button(label, action);
		button.setBackground(GOLD);
		button.setForeground(BACKGROUND);
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		return button;
	}

	private static JCheckBox checkbox(String label, boolean selected)
	{
		JCheckBox check = new JCheckBox(label, selected);
		check.setOpaque(false);
		check.setForeground(TEXT);
		check.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		check.addFocusListener(KEEP_FOCUS_VISIBLE);
		return check;
	}

	private static void scrollFocusedControl(JComponent control)
	{
		GuidePanel panel = (GuidePanel)SwingUtilities.getAncestorOfClass(GuidePanel.class, control);
		if (panel == null)
		{
			return;
		}
		if (control == panel.restoredFocus)
		{
			panel.restoredFocus = null;
			return;
		}
		Rectangle bounds = new Rectangle(0, 0, control.getWidth(), control.getHeight());
		if (control instanceof JList)
		{
			JList<?> tasks = (JList<?>)control;
			int row = tasks.getSelectedIndex();
			tasks.ensureIndexIsVisible(row);
			Rectangle selected = tasks.getCellBounds(row, row);
			if (selected != null)
			{
				bounds = selected;
			}
		}
		JComponent view = (JComponent)panel.getScrollPane().getViewport().getView();
		view.scrollRectToVisible(SwingUtilities.convertRectangle(control, bounds, view));
	}
	private static JButton link(String label, String url)
	{
		JButton b = button(label + " ↗", () -> LinkBrowser.browse(url));
		b.setForeground(GOLD);
		return b;
	}
}
