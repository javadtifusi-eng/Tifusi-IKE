# Tifusi

A one-button IKEv2 client for [Tifusi Panel](https://github.com/javadtifusi-eng/Tifusi-Panel), built on the
[strongSwan](https://www.strongswan.org) Android app (GPLv2+; this fork keeps that license).

- Scan the subscription QR code from the panel once (or paste the link).
- One button: on / off.

The app fetches `<panel>/sub/<secret>/app.json`, takes the first IKEv2 server (server, remote ID, EAP
username/password, and the CA only when the server's certificate is self-signed) and keeps it updated
every time the app opens. All of IKEv2 runs inside the app (strongSwan's charon via `VpnService`), not
in Android's built-in IKEv2, so it behaves the same on every phone.

Tifusi's own code: `TifusiActivity.java`, `TifusiSubscription.java`, `res/layout/tifusi_activity.xml`,
`res/values/tifusi.xml`. The APK is built by GitHub Actions (`.github/workflows/android.yml`).

---

کلاینت IKEv2 تک‌دکمه برای پنل تیفوسی، بر پایه‌ی اپ اندروید strongSwan. یک بار بارکد اشتراک را
اسکن کنید، بعد فقط خاموش/روشن.

Version 1.0.7 keeps multiple subscriptions on the device. The embedded APK link still imports
on first launch. Scan QR, Add subscription (typed or pasted link), and Subscriptions stay visible
after setup. Select an account to connect; disconnect before switching or deleting accounts.
Deleting removes only the local account and its native VPN profile, never the seller account.
The original single account migrates with its VPN UUID intact. Existing APKs are not force-updated.
