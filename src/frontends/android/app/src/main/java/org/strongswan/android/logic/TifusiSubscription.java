/*
 * Copyright (C) 2026 Tifusi
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.logic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnProfileDataSource;
import org.strongswan.android.data.VpnProfileSource;
import org.strongswan.android.data.VpnType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Tifusi Panel subscription: the link a customer scans (<panel>/sub/<secret>) and the single
 * IKEv2 profile built from its app.json. The panel sends the server, remote ID, the customer's
 * EAP username/password and, for a self-signed server, the CA to trust
 * (backend/app/links/generator.py build_ipsec_configs_for_user).
 */
public class TifusiSubscription
{
	private static final String PREFS = "tifusi";
	private static final String KEY_LINK = "link";
	private static final String KEY_PROFILE = "profile_uuid";
	private static final String KEY_BACKUPS = "backup_domains";
	private static final String KEY_USERNAME = "username";
	private static final String KEY_EXPIRE = "expire";
	private static final String KEY_USED = "used_traffic";
	private static final String KEY_LIMIT = "data_limit";
	private static final int TIMEOUT_MS = 15000;

	private static final Pattern LINK = Pattern.compile(
		"^(https?://[^\\s/?#]+(?:/[^\\s?#]*?)?)/sub/([A-Za-z0-9_-]{16,})/?(?:[?#]\\S*)?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern PEM = Pattern.compile(
		"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", Pattern.DOTALL);

	/** Why an import or refresh failed, for the message the screen shows. */
	public enum Failure
	{
		NOT_A_LINK,
		UNREACHABLE,
		NOT_FOUND,
		NO_IKEV2,
	}

	/** What the card shows about the account, as of the last successful refresh. */
	public static class Account
	{
		public String username;
		/** seconds since the epoch, 0 for no expiry */
		public long expire;
		public long used;
		/** bytes, 0 for unlimited */
		public long limit;
	}

	public static Account savedAccount(Context context)
	{
		SharedPreferences prefs = prefs(context);
		Account account = new Account();
		account.username = prefs.getString(KEY_USERNAME, "");
		account.expire = prefs.getLong(KEY_EXPIRE, 0);
		account.used = prefs.getLong(KEY_USED, 0);
		account.limit = prefs.getLong(KEY_LIMIT, 0);
		return account;
	}

	public static class SubscriptionException extends Exception
	{
		public final Failure failure;

		SubscriptionException(Failure failure)
		{
			super(failure.name());
			this.failure = failure;
		}
	}

	/**
	 * The subscription link in canonical form, or null if this is not one.
	 */
	public static String normalize(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = LINK.matcher(text.trim());
		return m.matches() ? m.group(1) + "/sub/" + m.group(2) : null;
	}

	public static String savedLink(Context context)
	{
		return prefs(context).getString(KEY_LINK, null);
	}

	/**
	 * The stored profile, or null before the first successful import.
	 */
	public static VpnProfile savedProfile(Context context)
	{
		String uuid = prefs(context).getString(KEY_PROFILE, null);
		if (uuid == null)
		{
			return null;
		}
		VpnProfileDataSource source = new VpnProfileSource(context).open();
		try
		{
			return source.getVpnProfile(UUID.fromString(uuid));
		}
		finally
		{
			source.close();
		}
	}

	/**
	 * Fetch app.json for this link and save its first IKEv2 server as the one profile. Blocking,
	 * call it off the main thread. Tries the backup domains the panel handed out last time when the
	 * link's own host does not answer.
	 */
	public static VpnProfile refresh(Context context, String link) throws SubscriptionException
	{
		migrate(context);
		String canonical = normalize(link);
		if (canonical == null)
		{
			throw new SubscriptionException(Failure.NOT_A_LINK);
		}
		JSONObject config = fetch(context, canonical);
		JSONArray ikev2 = config.optJSONArray("ikev2");
		JSONObject server = ikev2 != null ? ikev2.optJSONObject(0) : null;
		if (server == null || server.optString("server").isEmpty() || server.optString("password").isEmpty())
		{
			throw new SubscriptionException(Failure.NO_IKEV2);
		}

		SharedPreferences prefs = accountPrefs(context, canonical);
		SharedPreferences.Editor edit = prefs.edit();
		/* the panel can move a customer to a new address, follow it */
		String moved = normalize(config.optString("subscription_url"));
		edit.putString(KEY_LINK, moved != null ? moved : canonical);
		JSONArray backups = config.optJSONArray("backup_domains");
		edit.putString(KEY_BACKUPS, backups != null ? backups.toString() : "[]");
		edit.putString(KEY_USERNAME, config.optString("username", server.optString("username")));
		edit.putLong(KEY_EXPIRE, config.optLong("expire", 0));
		edit.putLong(KEY_USED, config.optLong("used_traffic", 0));
		edit.putLong(KEY_LIMIT, config.optLong("data_limit", 0));

		VpnProfileDataSource source = new VpnProfileSource(context).open();
		try
		{
			String uuid = prefs.getString(KEY_PROFILE, null);
			VpnProfile existing = uuid != null ? source.getVpnProfile(UUID.fromString(uuid)) : null;
			VpnProfile profile = existing != null ? existing : new VpnProfile();
			profile.setName("Tifusi");
			profile.setVpnType(VpnType.IKEV2_EAP);
			profile.setGateway(server.optString("server"));
			String remoteId = server.optString("remote_id");
			profile.setRemoteId(remoteId.isEmpty() || "null".equals(remoteId) ? null : remoteId);
			profile.setUsername(server.optString("username"));
			profile.setPassword(server.optString("password"));
			profile.setCertificateAlias(storeSelfSignedCa(server.optString("certificate", null)));
			if (existing != null)
			{
				source.updateVpnProfile(profile);
			}
			else
			{
				source.insertProfile(profile);
			}
			edit.putString(KEY_PROFILE, profile.getUUID().toString());
			edit.commit();
			register(context, canonical);
			return profile;
		}
		finally
		{
			source.close();
		}
	}

	private static JSONObject fetch(Context context, String canonical) throws SubscriptionException
	{
		List<String> candidates = new ArrayList<>();
		candidates.add(canonical);
		try
		{
			URL url = new URL(canonical);
			String path = url.getPath();
			JSONArray backups = new JSONArray(accountPrefs(context, canonical).getString(KEY_BACKUPS, "[]"));
			for (int i = 0; i < backups.length(); i++)
			{
				candidates.add("https://" + backups.getString(i) + path);
			}
		}
		catch (Exception ignored)
		{
		}

		String query = "/app.json?hwid=" + encode(hwid(context));
		boolean reached = false;
		for (String base : candidates)
		{
			HttpURLConnection conn = null;
			try
			{
				conn = (HttpURLConnection)new URL(base + query).openConnection();
				conn.setConnectTimeout(TIMEOUT_MS);
				conn.setReadTimeout(TIMEOUT_MS);
				conn.setRequestProperty("Accept", "application/json");
				int code = conn.getResponseCode();
				reached = true;
				if (code == 200)
				{
					return new JSONObject(read(conn.getInputStream()));
				}
			}
			catch (Exception ignored)
			{
				/* try the next address */
			}
			finally
			{
				if (conn != null)
				{
					conn.disconnect();
				}
			}
		}
		throw new SubscriptionException(reached ? Failure.NOT_FOUND : Failure.UNREACHABLE);
	}

	/**
	 * The panel sends its certificate with the issuing CA last. A publicly trusted certificate
	 * needs nothing (the system's CAs are used, alias null); a self-signed CA is stored and pinned,
	 * the same rule backend/app/subscription/ikev2_profile.py follows for iOS.
	 */
	private static String storeSelfSignedCa(String pem)
	{
		if (pem == null || pem.isEmpty() || "null".equals(pem))
		{
			return null;
		}
		String last = null;
		Matcher m = PEM.matcher(pem);
		while (m.find())
		{
			last = m.group();
		}
		if (last == null)
		{
			return null;
		}
		try
		{
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			X509Certificate ca = (X509Certificate)factory.generateCertificate(
				new ByteArrayInputStream(last.getBytes(StandardCharsets.US_ASCII)));
			if (!ca.getIssuerX500Principal().equals(ca.getSubjectX500Principal()))
			{
				return null;
			}
			KeyStore store = KeyStore.getInstance("LocalCertificateStore");
			store.load(null, null);
			store.setCertificateEntry(null, ca);
			TrustedCertificateManager.getInstance().reset();
			return store.getCertificateAlias(ca);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@SuppressLint("HardwareIds")
	private static String hwid(Context context)
	{
		String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
		return id != null ? id : "";
	}

	private static String encode(String value)
	{
		try
		{
			return URLEncoder.encode(value, "UTF-8");
		}
		catch (IOException e)
		{
			return "";
		}
	}

	private static String read(InputStream in) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1)
		{
			out.write(buf, 0, n);
		}
		return out.toString("UTF-8");
	}

	private static String accountId(String link)
	{
		return UUID.nameUUIDFromBytes(link.substring(link.lastIndexOf("/sub/") + 5)
			.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private static SharedPreferences rootPrefs(Context context)
	{
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static SharedPreferences accountPrefs(Context context, String link)
	{
		return context.getSharedPreferences(PREFS + "_account_" + accountId(link), Context.MODE_PRIVATE);
	}

	/** Migrate the original single account once, preserving its native VPN UUID. */
	private static synchronized void migrate(Context context)
	{
		SharedPreferences root = rootPrefs(context);
		if (root.getBoolean("multi_migrated", false)) { return; }
		String link = root.getString(KEY_LINK, null);
		if (normalize(link) != null)
		{
			SharedPreferences.Editor e = accountPrefs(context, link).edit();
			for (String key : new String[]{KEY_LINK, KEY_PROFILE, KEY_BACKUPS, KEY_USERNAME})
			{ e.putString(key, root.getString(key, null)); }
			for (String key : new String[]{KEY_EXPIRE, KEY_USED, KEY_LIMIT})
			{ e.putLong(key, root.getLong(key, 0)); }
			e.commit();
			register(context, link);
		}
		root.edit().putBoolean("multi_migrated", true).commit();
	}

	private static synchronized void register(Context context, String link)
	{
		SharedPreferences root = rootPrefs(context);
		java.util.Set<String> ids = new java.util.HashSet<>(root.getStringSet("accounts", java.util.Collections.emptySet()));
		String id = accountId(link);
		ids.add(id);
		root.edit().putStringSet("accounts", ids).putString("active_account", id).commit();
	}

	public static synchronized List<AccountEntry> accounts(Context context)
	{
		migrate(context);
		List<AccountEntry> entries = new ArrayList<>();
		for (String id : rootPrefs(context).getStringSet("accounts", java.util.Collections.emptySet()))
		{
			SharedPreferences p = context.getSharedPreferences(PREFS + "_account_" + id, Context.MODE_PRIVATE);
			String link = p.getString(KEY_LINK, null);
			if (link != null) { entries.add(new AccountEntry(link, p.getString(KEY_USERNAME, "Tifusi"))); }
		}
		java.util.Collections.sort(entries, (a, b) -> a.username.compareToIgnoreCase(b.username));
		return entries;
	}

	public static class AccountEntry
	{
		public final String link;
		public final String username;
		AccountEntry(String link, String username) { this.link = link; this.username = username; }
	}

	public static synchronized boolean contains(Context context, String link)
	{
		migrate(context);
		return rootPrefs(context).getStringSet("accounts", java.util.Collections.emptySet()).contains(accountId(link));
	}

	public static synchronized void select(Context context, String link)
	{
		if (contains(context, link))
		{ rootPrefs(context).edit().putString("active_account", accountId(link)).commit(); }
	}

	/** Delete only this local subscription; the seller's account and shared CAs stay intact. */
	public static synchronized void remove(Context context, String link)
	{
		migrate(context);
		SharedPreferences p = accountPrefs(context, link);
		String uuid = p.getString(KEY_PROFILE, null);
		if (uuid != null)
		{
			VpnProfileDataSource source = new VpnProfileSource(context).open();
			try
			{
				VpnProfile profile = source.getVpnProfile(UUID.fromString(uuid));
				if (profile != null) { source.deleteVpnProfile(profile); }
			}
			finally { source.close(); }
		}
		SharedPreferences root = rootPrefs(context);
		java.util.Set<String> ids = new java.util.HashSet<>(root.getStringSet("accounts", java.util.Collections.emptySet()));
		String id = accountId(link);
		ids.remove(id);
		SharedPreferences.Editor e = root.edit().putStringSet("accounts", ids);
		if (id.equals(root.getString("active_account", null)))
		{ e.putString("active_account", ids.isEmpty() ? null : ids.iterator().next()); }
		e.commit();
		p.edit().clear().commit();
	}

	private static SharedPreferences prefs(Context context)
	{
		migrate(context);
		String id = rootPrefs(context).getString("active_account", null);
		return context.getSharedPreferences(PREFS + "_account_" + (id != null ? id : "empty"), Context.MODE_PRIVATE);
	}
}
