package org.strongswan.android.logic;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuietRefreshTest
{
	@Test
	public void tapDuringFetchDiscardsLateResponse()
	{
		QuietRefresh refresh = new QuietRefresh();
		assertTrue(refresh.cancel());
		assertTrue(refresh.isCancelled());
		assertFalse(refresh.beginSave());
		assertTrue(refresh.cancel());
	}

	@Test
	public void tapDuringLocalSaveWaitsForCompletion()
	{
		QuietRefresh refresh = new QuietRefresh();
		assertTrue(refresh.beginSave());
		assertFalse(refresh.cancel());
		assertFalse(refresh.isCancelled());
	}
}
