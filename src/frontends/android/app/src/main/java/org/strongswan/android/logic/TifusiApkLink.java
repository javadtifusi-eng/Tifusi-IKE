package org.strongswan.android.logic;

import android.content.Context;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * The subscription link the panel wrote into this copy of the APK.
 *
 * The panel's per-user download (/sub/<secret>/tifusi.apk) adds one ID-value pair to the APK
 * Signing Block. That block is not covered by the v2/v3 signature, so the APK still installs as
 * the same signed app, and the first launch finds its own subscription without any pairing.
 */
public class TifusiApkLink
{
	/* "TIFU", little-endian; must match backend/app/subscription/ike_app.py */
	private static final int LINK_ID = 0x55464954;
	private static final byte[] MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

	/**
	 * @return the embedded link, or null for a plain copy of the app (or anything unexpected)
	 */
	public static String read(Context context)
	{
		try (RandomAccessFile apk = new RandomAccessFile(context.getApplicationInfo().sourceDir, "r"))
		{
			return find(apk);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	private static String find(RandomAccessFile apk) throws IOException
	{
		long cd = centralDirectoryOffset(apk);
		if (cd < 32)
		{
			return null;
		}
		ByteBuffer footer = readAt(apk, cd - 24, 24);
		long size = footer.getLong();
		byte[] magic = new byte[16];
		footer.get(magic);
		if (!java.util.Arrays.equals(magic, MAGIC) || size < 24 || size > cd - 8)
		{
			return null;
		}
		/* the pairs sit between the leading size field and the footer */
		ByteBuffer pairs = readAt(apk, cd - size, (int)(size - 24));
		while (pairs.remaining() >= 12)
		{
			long len = pairs.getLong();
			if (len < 4 || len > pairs.remaining())
			{
				return null;
			}
			int id = pairs.getInt();
			if (id == LINK_ID)
			{
				byte[] value = new byte[(int)(len - 4)];
				pairs.get(value);
				return new String(value, StandardCharsets.UTF_8).trim();
			}
			pairs.position(pairs.position() + (int)(len - 4));
		}
		return null;
	}

	private static long centralDirectoryOffset(RandomAccessFile apk) throws IOException
	{
		long length = apk.length();
		int tail = (int)Math.min(length, 22 + 65535);
		ByteBuffer buf = readAt(apk, length - tail, tail);
		for (int i = tail - 22; i >= 0; i--)
		{
			if (buf.getInt(i) == 0x06054b50)
			{
				return buf.getInt(i + 16) & 0xFFFFFFFFL;
			}
		}
		return -1;
	}

	private static ByteBuffer readAt(RandomAccessFile apk, long offset, int len) throws IOException
	{
		byte[] data = new byte[len];
		apk.seek(offset);
		apk.readFully(data);
		return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
	}
}
