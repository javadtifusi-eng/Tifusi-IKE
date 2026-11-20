package org.strongswan.android.logic;

/** A power tap can skip a background fetch until the worker starts saving the profile. */
public final class QuietRefresh
{
	private boolean saving;
	private boolean cancelled;

	public synchronized boolean cancel()
	{
		if (saving) { return false; }
		cancelled = true;
		return true;
	}

	public synchronized boolean beginSave()
	{
		if (cancelled) { return false; }
		saving = true;
		return true;
	}

	public synchronized boolean isCancelled()
	{
		return cancelled;
	}
}
