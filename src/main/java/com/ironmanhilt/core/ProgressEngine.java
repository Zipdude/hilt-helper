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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Java port of the existing guide's baseline/overlap reconciliation. */
public final class ProgressEngine
{
	private final GuideCatalog catalogue;
	public ProgressEngine(GuideCatalog catalogue)
	{
		this.catalogue = catalogue;
	}
	public Set<Integer> completed(GuideState state)
	{
		Set<Integer> ids = state.ids("baseIDs");
		ids.addAll(state.ids("newIDs"));
		ids.addAll(state.ids("syncIDs"));
		return ids;
	}
	public Set<Integer> knownCompleted(GuideState state)
	{
		Set<Integer> known = state.knownIds("baseIDs", catalogue.tasks.keySet());
		known.addAll(state.knownIds("newIDs", catalogue.tasks.keySet()));
		known.addAll(state.knownIds("syncIDs", catalogue.tasks.keySet()));
		return known;
	}
	public int total(GuideState state)
	{
		Set<Integer> additions = state.knownIds("newIDs", catalogue.tasks.keySet());
		additions.addAll(state.knownIds("syncIDs", catalogue.tasks.keySet()));
		additions.removeAll(state.knownIds("baseIDs", catalogue.tasks.keySet()));
		additions.removeAll(state.knownIds("syncAnchorIDs", catalogue.tasks.keySet()));
		return Math.max(state.baseline() + catalogue.points(additions), catalogue.points(knownCompleted(state)));
	}
	public Set<Integer> merge(GuideState state, Set<Integer> positives, Set<Integer> observed)
	{
		if (!observed.containsAll(positives) || !catalogue.tasks.keySet().containsAll(observed))
		{
			throw new IllegalArgumentException("Unvalidated achievement evidence");
		}
		Set<Integer> prior = state.ids("syncIDs"), seen = state.ids("syncObservedIDs"), manualNew = state.ids("newIDs"),
						base = state.ids("baseIDs"), anchors = state.ids("syncAnchorIDs");
		Set<Integer> held = new LinkedHashSet<>();
		if (state.baseline() > 0)
		{
			boolean initial = !state.anchorsInitialized();
			Set<Integer> candidates = new LinkedHashSet<>(positives);
			if (initial)
			{
				candidates.addAll(prior);
			}
			for (int id : candidates)
			{
				if ((!manualNew.contains(id) || base.contains(id)) &&
					(initial || (!prior.contains(id) && !seen.contains(id))))
				{
					anchors.add(id);
					if (!initial && !base.contains(id))
					{
						held.add(id);
					}
				}
			}
			state.ids("syncAnchorIDs", anchors);
		}
		prior.addAll(positives);
		state.ids("syncIDs", prior);
		seen.addAll(observed);
		state.ids("syncObservedIDs", seen);
		Set<Integer> overlap = state.ids("syncOverlapIDs");
		overlap.addAll(held);
		state.ids("syncOverlapIDs", overlap);
		return held;
	}
	public Set<Integer> conflicts(GuideState state, Set<Integer> positives, Set<Integer> observed)
	{
		Set<Integer> conflicts = knownCompleted(state);
		conflicts.retainAll(observed);
		conflicts.removeAll(positives);
		return conflicts;
	}
	/** Explicit user correction only. The caller archives the old state before committing this. */
	public GuideState reconcile(GuideState current, CaDecoder.Result evidence)
	{
		if (evidence.gamePoints < 0 || !evidence.observed.equals(catalogue.tasks.keySet()) ||
			catalogue.points(evidence.completed) != evidence.gamePoints)
		{
			throw new IllegalArgumentException("Full current game evidence required");
		}
		GuideState out = current.copy();
		Set<Integer> unknown = completed(current);
		unknown.removeAll(catalogue.tasks.keySet());
		out.data.addProperty("baseTotal", evidence.gamePoints);
		out.ids("baseIDs", evidence.completed);
		out.ids("newIDs", unknown);
		out.ids("syncIDs", evidence.completed);
		out.ids("syncAnchorIDs", evidence.completed);
		out.ids("syncObservedIDs", evidence.observed);
		out.ids("syncOverlapIDs", Collections.emptySet());
		out.data.addProperty("nativeReconciledAt", java.time.Instant.now().toString());
		return out;
	}
}
