# Ergon

Automatic daily HoYoLAB check-ins and gift-code redemption for Genshin Impact, Honkai: Star Rail,
Zenless Zone Zero, Honkai Impact 3rd and Tears of Themis.

## Install

1. Download `ergon.apk` from the [latest release](https://github.com/mineygg/ergon/releases/latest) and install it.
   Android will ask you to allow installs from your browser or file manager the first time.
2. Open Ergon, accept the notice, and sign in to HoYoLAB. Google sign-in works. For gift codes, finish the
   second sign-in step on the Home screen.
3. Allow notifications so you hear about claims and expired sign-ins.

It runs on its own from there.

**Requirements:** Android 8.0 or newer, an internet connection, and a HoYoLAB account linked to your games.

---

## More details

**Schedule**
- Check-in runs once a day, 30 minutes after the daily reset (00:30 UTC+8). Games already claimed that day
  are never requested again.
- Gift codes are checked every hour and fully re-scanned every 6 hours. Gift codes are supported for Genshin,
  Star Rail and Zenless.
- Machine Workshop (the Genshin web event) runs 30 minutes after each reset, 15 minutes before each reset
  and every 6 hours. Community missions reset at 00:00 UTC+8 and game missions at 04:00 server time.

**Sign-in and privacy**
- Sign-in happens in an in-app browser. Ergon keeps only the HoYoLAB cookies it needs, stored encrypted on
  the device, and sends them only to HoYoverse servers.
- Gift codes need the second sign-in step, which provides a separate cookie for the redeem page.
- A redeem request is sent at least once a day, even with no new codes, so that cookie doesn't expire.
- If a session expires you get a notification, and you sign in again from the app.

**Machine Workshop**
- Each task gets one attempt to be completed through the API per day or week. If the server refuses
  (for example it needs in-game play), the task is only watched, and claimed once its progress is complete.
- Tasks already claimed are skipped. Spending points on the prize draw is left to you.
- **Auto exchange** (Settings, off by default): after the tasks, the exchange shop is read once per run.
  Rewards that cost 0 are always claimed. Then the rewards in your **Exchange priority** list are claimed
  top to bottom. If the top reward costs more than you have, the mode decides: *Wait for points* keeps
  saving, *Claim what I can afford* moves on to the next affordable one.
- Claimed rewards are hidden. Timed rewards (on a refresh timer) are listed but not claimable until they
  refresh; they have no timer of their own and are only checked on the normal Machine Workshop runs.
- Every Machine Workshop claim request is sent at least 5 seconds after the previous one.

**Other**
- Background runs use Android's WorkManager, so they happen even when the app is closed. Phones with strict
  battery saving may delay them; setting Ergon's battery usage to Unrestricted helps.
- Ergon checks GitHub for new releases and offers an **Update** button. The check can be turned off in
  Settings. Updates only install over a build signed with the same key.
- Runs can be started or stopped manually from each card on the Home screen.

**Disclaimer**
Unofficial fan project, not affiliated with or endorsed by HoYoverse. Automating accounts is your own call
and at your own risk.

**Development**
See [BUILDING.md](BUILDING.md).

**License**
Copyright (C) 2026 mineygg. GNU General Public License v3.0 (see `LICENSE`), with the attribution terms in `NOTICE`.
