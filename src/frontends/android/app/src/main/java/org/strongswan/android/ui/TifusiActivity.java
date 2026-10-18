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

package org.strongswan.android.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.logic.TifusiApkLink;
import org.strongswan.android.logic.TifusiSubscription;
import org.strongswan.android.logic.TifusiSubscription.Account;
import org.strongswan.android.logic.TifusiSubscription.SubscriptionException;
import org.strongswan.android.logic.VpnStateService;
import org.strongswan.android.logic.VpnStateService.ErrorState;
import org.strongswan.android.logic.VpnStateService.State;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The whole app: scan the panel's subscription QR code once, then one button to turn the VPN on
 * and off. Everything else strongSwan offers stays out of sight.
 */
public class TifusiActivity extends AppCompatActivity implements VpnStateService.VpnStateListener
{
	private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private VpnStateService mService;
	private View mPower;
	private View mCard;
	private TextView mUser;
	private TextView mDays;
	private TextView mData;
	private TextView mStatus;
	private View mSetup;
	private boolean mBusy;
	/* a link opened while a quiet refresh was running, imported right after it */
	private String mPending;

	private final ServiceConnection mServiceConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			mService = ((VpnStateService.LocalBinder)service).getService();
			mService.registerListener(TifusiActivity.this);
			render();
		}

		@Override
		public void onServiceDisconnected(ComponentName name)
		{
			mService = null;
		}
	};

	private final ActivityResultLauncher<ScanOptions> mScan = registerForActivityResult(new ScanContract(), result -> {
		if (result.getContents() != null)
		{
			importLink(result.getContents());
		}
	});

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.tifusi_activity);
		mPower = findViewById(R.id.tifusi_power);
		mStatus = findViewById(R.id.tifusi_status);
		mSetup = findViewById(R.id.tifusi_setup);
		mCard = findViewById(R.id.tifusi_card);
		mUser = findViewById(R.id.tifusi_user);
		mDays = findViewById(R.id.tifusi_days);
		mData = findViewById(R.id.tifusi_data);
		/* brushed silver, like the horses */
		TextView title = findViewById(R.id.tifusi_title);
		float size = title.getTextSize();
		title.getPaint().setShader(new LinearGradient(0, 0, 0, size, new int[]{0xFFFFFFFF, 0xFFB8C9E8, 0xFFFFFFFF, 0xFF7E9CCB},
													  new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP));

		mPower.setOnClickListener(v -> toggle());
		findViewById(R.id.tifusi_scan).setOnClickListener(v -> scan());
		findViewById(R.id.tifusi_paste).setOnClickListener(v -> paste());
		/* a new subscription replaces the old one: long-press the status line */
		mStatus.setOnLongClickListener(v -> {
			scan();
			return true;
		});

		bindService(new Intent(this, VpnStateService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
		if (savedInstanceState == null)
		{
			openLink(getIntent());
		}
	}

	/**
	 * A per-user download from the panel carries its subscription inside the APK, so a fresh install
	 * comes up already set up. Tried on every start until a subscription is saved (a failed first
	 * fetch is retried), and again when a newer APK installed over the app carries another account.
	 */
	private void importFromApk()
	{
		mExecutor.execute(() -> {
			String link = TifusiSubscription.normalize(TifusiApkLink.read(getApplicationContext()));
			if (link != null)
			{
				mHandler.post(() -> offerApkLink(link));
			}
		});
	}

	private void offerApkLink(String link)
	{
		final String key = "apk_link_handled";
		android.content.SharedPreferences prefs = getSharedPreferences("tifusi_apk", Context.MODE_PRIVATE);
		String saved = TifusiSubscription.savedLink(this);
		if (saved == null)
		{
			/* an import already running (a tifusi:// link) wins; the next start tries again */
			if (!mBusy)
			{
				runImport(link, false);
			}
			return;
		}
		/* already this account, or the user already answered for this APK */
		if (secretOf(saved).equals(secretOf(link)) || link.equals(prefs.getString(key, null)))
		{
			return;
		}
		prefs.edit().putString(key, link).apply();
		new AlertDialog.Builder(this)
			.setTitle(R.string.tifusi_replace_title)
			.setMessage(R.string.tifusi_replace_text)
			.setPositiveButton(R.string.tifusi_replace_ok, (d, w) -> runImport(link, false))
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		super.onNewIntent(intent);
		setIntent(intent);
		openLink(intent);
	}

	/**
	 * tifusi://import?u=<subscription link>, from the "connect" button on the panel's guide page.
	 * Imported like a scanned QR code; replacing a different subscription asks first, so no random
	 * web page can quietly move someone onto another server.
	 */
	private void openLink(Intent intent)
	{
		Uri data = intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) ? intent.getData() : null;
		if (data == null || !"tifusi".equals(data.getScheme()))
		{
			return;
		}
		String link = TifusiSubscription.normalize(data.getQueryParameter("u"));
		if (link == null)
		{
			mStatus.setText(R.string.tifusi_err_link);
			return;
		}
		String saved = TifusiSubscription.savedLink(this);
		/* the same account, maybe on another of the panel's domains */
		if (saved == null || secretOf(saved).equals(secretOf(link)))
		{
			runImport(link, false);
			return;
		}
		new AlertDialog.Builder(this)
			.setTitle(R.string.tifusi_replace_title)
			.setMessage(R.string.tifusi_replace_text)
			.setPositiveButton(R.string.tifusi_replace_ok, (d, w) -> runImport(link, false))
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		render();
		/* pick up a changed password or server quietly, but never mid-connection */
		String link = TifusiSubscription.savedLink(this);
		if (link != null && (mService == null || mService.getState() == State.DISABLED))
		{
			runImport(link, true);
		}
		importFromApk();
	}

	@Override
	protected void onDestroy()
	{
		super.onDestroy();
		if (mService != null)
		{
			mService.unregisterListener(this);
		}
		unbindService(mServiceConnection);
		mExecutor.shutdown();
	}

	@Override
	public void stateChanged()
	{
		render();
	}

	private void scan()
	{
		ScanOptions options = new ScanOptions();
		options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
		options.setPrompt(getString(R.string.tifusi_scan_prompt));
		options.setBeepEnabled(false);
		options.setOrientationLocked(false);
		mScan.launch(options);
	}

	private void paste()
	{
		ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
		ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
		CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
		importLink(text != null ? text.toString() : "");
	}

	private void importLink(String text)
	{
		if (TifusiSubscription.normalize(text) == null)
		{
			mStatus.setText(R.string.tifusi_err_link);
			return;
		}
		runImport(text, false);
	}

	private void runImport(String link, boolean quiet)
	{
		if (mBusy)
		{
			if (!quiet)
			{
				mPending = link;
			}
			return;
		}
		mBusy = true;
		if (!quiet)
		{
			mStatus.setText(R.string.tifusi_importing);
		}
		mExecutor.execute(() -> {
			int error = 0;
			try
			{
				TifusiSubscription.refresh(getApplicationContext(), link);
			}
			catch (SubscriptionException e)
			{
				switch (e.failure)
				{
					case NOT_A_LINK:
						error = R.string.tifusi_err_link;
						break;
					case NOT_FOUND:
						error = R.string.tifusi_err_not_found;
						break;
					case NO_IKEV2:
						error = R.string.tifusi_err_no_ikev2;
						break;
					default:
						error = R.string.tifusi_err_unreachable;
				}
			}
			final int shown = quiet ? 0 : error;
			mHandler.post(() -> {
				mBusy = false;
				render();
				if (shown != 0)
				{
					mStatus.setText(shown);
				}
				if (mPending != null)
				{
					String next = mPending;
					mPending = null;
					runImport(next, false);
				}
			});
		});
	}

	private static String secretOf(String link)
	{
		return link.substring(link.lastIndexOf("/sub/") + 5);
	}

	private void toggle()
	{
		VpnProfile profile = TifusiSubscription.savedProfile(this);
		if (profile == null)
		{
			scan();
			return;
		}
		if (mService != null && mService.getState() != State.DISABLED)
		{
			mService.disconnect();
			return;
		}
		Intent intent = new Intent(this, VpnProfileControlActivity.class);
		intent.setAction(VpnProfileControlActivity.START_PROFILE);
		intent.putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_UUID, profile.getUUID().toString());
		startActivity(intent);
	}

	private void render()
	{
		boolean ready = TifusiSubscription.savedLink(this) != null;
		mSetup.setVisibility(ready ? View.GONE : View.VISIBLE);
		mCard.setVisibility(ready ? View.VISIBLE : View.GONE);
		if (!ready)
		{
			mStatus.setText(R.string.tifusi_first_scan);
			return;
		}
		renderAccount();
		State state = mService != null ? mService.getState() : State.DISABLED;
		ErrorState error = mService != null ? mService.getErrorState() : ErrorState.NO_ERROR;
		mPower.setActivated(state == State.CONNECTED);
		mPower.setSelected(state == State.CONNECTING);
		switch (state)
		{
			case CONNECTED:
				mStatus.setText(R.string.tifusi_connected);
				break;
			case CONNECTING:
				mStatus.setText(error != ErrorState.NO_ERROR ? R.string.tifusi_retrying : R.string.tifusi_connecting);
				break;
			case DISCONNECTING:
				mStatus.setText(R.string.tifusi_disconnecting);
				break;
			default:
				mStatus.setText(error == ErrorState.AUTH_FAILED ? R.string.tifusi_err_auth :
								error != ErrorState.NO_ERROR ? R.string.tifusi_err_connect : R.string.tifusi_disconnected);
		}
	}

	private void renderAccount()
	{
		Account account = TifusiSubscription.savedAccount(this);
		mUser.setText(account.username);
		if (account.expire <= 0)
		{
			mDays.setText(R.string.tifusi_no_expiry);
		}
		else
		{
			long left = account.expire - System.currentTimeMillis() / 1000;
			long days = (left + 86399) / 86400;
			if (left <= 0)
			{
				mDays.setText(R.string.tifusi_expired);
			}
			else if (left < 86400)
			{
				mDays.setText(R.string.tifusi_last_day);
			}
			else if (days == 1)
			{
				mDays.setText(R.string.tifusi_one_day_left);
			}
			else
			{
				mDays.setText(getString(R.string.tifusi_days_left, (int)days));
			}
		}
		if (account.limit <= 0)
		{
			mData.setText(R.string.tifusi_unlimited);
		}
		else
		{
			/* the panel counts a GB as 1024^3 bytes */
			double left = Math.max(0, account.limit - account.used) / (1024.0 * 1024 * 1024);
			mData.setText(getString(R.string.tifusi_gb_left, String.format(Locale.US, "%.2f", left)));
		}
	}
}
