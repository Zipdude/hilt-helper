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
package com.ironmanhilt.storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One worker drains ordered transactions; pending ordinary saves coalesce per account. */
public final class SerialExecutor implements Executor
{
	private final Executor executor;
	private final Deque<Job> queue = new ArrayDeque<>();
	private boolean scheduled;

	public SerialExecutor(Executor executor)
	{
		this.executor = executor;
	}

	@Override public void execute(Runnable command)
	{
		execute(command, failure ->
		{ throw failure; });
	}

	public void execute(Runnable command, Consumer<RuntimeException> rejected)
	{
		submit(new Job(null, command, rejected));
	}

	public void executeLatest(String account, Runnable command, Consumer<RuntimeException> rejected)
	{
		submit(new Job(account, command, rejected));
	}

	private void submit(Job job)
	{
		synchronized (this)
		{
			if (job.account != null)
			{
				// A transaction is an ordering barrier: never replace a save across it.
				Iterator<Job> pending = queue.descendingIterator();
				while (pending.hasNext())
				{
					Job previous = pending.next();
					if (previous.account == null)
					{
						break;
					}
					if (job.account.equals(previous.account))
					{
						pending.remove();
						break;
					}
				}
			}
			queue.addLast(job);
			if (scheduled)
			{
				return;
			}
			scheduled = true;
		}
		try
		{
			executor.execute(this::drain);
		}
		catch (RuntimeException failure)
		{
			List<Job> rejected;
			synchronized (this)
			{
				rejected = new ArrayList<>(queue);
				queue.clear();
				scheduled = false;
			}
			RuntimeException first = null;
			for (Job pending : rejected)
			{
				try
				{
					pending.rejected.accept(failure);
				}
				catch (RuntimeException callbackFailure)
				{
					if (first == null)
					{
						first = callbackFailure;
					}
				}
			}
			if (first != null)
			{
				throw first;
			}
		}
	}

	private void drain()
	{
		RuntimeException first = null;
		while (true)
		{
			Job job;
			synchronized (this)
			{
				job = queue.pollFirst();
				if (job == null)
				{
					scheduled = false;
					break;
				}
			}
			try
			{
				job.command.run();
			}
			catch (RuntimeException failure)
			{
				try
				{
					job.rejected.accept(failure);
				}
				catch (RuntimeException callbackFailure)
				{
					if (first == null)
					{
						first = callbackFailure;
					}
				}
			}
		}
		if (first != null)
		{
			throw first;
		}
	}

	private static final class Job
	{
		private final String account;
		private final Runnable command;
		private final Consumer<RuntimeException> rejected;

		private Job(String account, Runnable command, Consumer<RuntimeException> rejected)
		{
			this.account = account;
			this.command = command;
			this.rejected = rejected;
		}
	}
}
