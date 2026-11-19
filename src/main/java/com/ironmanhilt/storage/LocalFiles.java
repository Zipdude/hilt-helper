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

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.runelite.client.util.Filepath;

/** All filesystem access is scoped through RuneLite's Filepath API. */
public final class LocalFiles implements ProgressStore.Files
{
	private final Filepath directory;
	public LocalFiles(Filepath directory) throws IOException
	{
		this.directory = directory;
		directory.createDirectories();
	}
	private Filepath file(String name)
	{
		if (!name.matches("[a-zA-Z0-9.\\-]+"))
		{
			throw new IllegalArgumentException("Invalid local filename");
		}
		return directory.joinSegment(name);
	}
	@Override public String read(String name) throws IOException
	{
		Filepath path = file(name);
		if (!path.exists())
		{
			return null;
		}
		if (path.size() > 10_000_000)
		{
			throw new IOException("Progress file is too large");
		}
		StringBuilder out = new StringBuilder();
		try (Reader reader = path.openBufferedReader())
		{
			char[] buffer = new char[8192];
			int count;
			while ((count = reader.read(buffer)) != -1)
			{
				if (out.length() + count > 10_000_000)
				{
					throw new IOException("Progress file is too large");
				}
				out.append(buffer, 0, count);
			}
		}
		return out.toString();
	}
	@Override public void write(String name, String text, boolean keepPrevious) throws IOException
	{
		Filepath target = file(name), temporary = file(name + ".writing-" + UUID.randomUUID());
		try
		{
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			try (FileChannel channel =
						temporary.openFileChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
			{
				ByteBuffer data = ByteBuffer.wrap(bytes);
				while (data.hasRemaining())
				{
					channel.write(data);
				}
				channel.force(true);
			}
			if (keepPrevious && target.exists())
			{
				Filepath previousTemp = file(name + ".previous-writing");
				target.copyTo(previousTemp, StandardCopyOption.REPLACE_EXISTING);
				move(previousTemp, file(name + ".previous"));
			}
			move(temporary, target);
		}
		finally
		{
			temporary.deleteIfExists();
		}
	}
	private static void move(Filepath source, Filepath target) throws IOException
	{
		try
		{
			source.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			source.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
