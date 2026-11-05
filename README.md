<p align="center">
  <img src="assets/icon.svg" width="120" alt="Ergon icon">
</p>

<h1 align="center">Ergon - For HoYolab Automation</h1>

<p align="center">
  Automatic HoYoLAB check-ins, gift codes and web events for Genshin Impact, Honkai: Star Rail,<br>
  Zenless Zone Zero, Honkai Impact 3rd and Tears of Themis.
</p>

<p align="center">
  <a href="https://github.com/mineygg/ergon/releases/latest"><img src="https://img.shields.io/github/v/release/mineygg/ergon?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0"></a>
</p>

## Features

- **Daily check-in** for all five games, once a day, right after the reset.
- **Gift codes** found and redeemed automatically (Genshin, Star Rail, Zenless).
- **Web events:** the Machine Workshop (Genshin) and Traveling Mimo (Star Rail, Zenless). Ergon collects the
  daily and weekly points for you.
- **Auto exchange:** spends event points on the rewards you want, in your order of priority, and grabs limited
  rewards the moment they come back in stock.
- **Prize draw** (Traveling Mimo): optionally spends spare points on the daily draws.
- **Per-game control:** every game has its own Configure screen, so you choose exactly what Ergon does for it.
- **Runs in the background** and tells you what it claimed, or when a sign-in expired.

### What each game supports

| Game | Check-in | Gift codes | Web event | Auto exchange | Prize draw |
| --- | :---: | :---: | --- | :---: | :---: |
| Genshin Impact | ✔ | ✔ | Machine Workshop | ✔ | — |
| Honkai: Star Rail | ✔ | ✔ | Traveling Mimo | ✔ | ✔ |
| Zenless Zone Zero | ✔ | ✔ | Traveling Mimo | ✔ | ✔ |
| Honkai Impact 3rd | ✔ | — | — | — | — |
| Tears of Themis | ✔ | — | — | — | — |

## Install

1. Download `ergon.apk` from the [latest release](https://github.com/mineygg/ergon/releases/latest) and install it.
   Android will ask you to allow installs from your browser or file manager the first time.
2. Open Ergon, accept the notice, and sign in to HoYoLAB. Google sign-in works. For gift codes, finish the
   second sign-in step on the Home screen.
3. Allow notifications so you hear about claims and expired sign-ins.
4. Open **Settings → Games → Configure** to pick what Ergon does for each game.
5. In Settings, tap **Allow** under *Unrestricted battery usage* so Android doesn't delay the background runs.

It runs on its own from there.

**Requirements:** Android 8.0 or newer, an internet connection, and a HoYoLAB account linked to your games.

---

## More details

**Configure (per game)**
- Settings lists every game with a **Configure** button. For each game you can switch the daily check-in,
  gift codes and the web event on or off. Only what a game supports is shown: Honkai Impact 3rd and Tears of
  Themis only have the check-in.
- When a game's web event is on, **Auto exchange** and **Exchange priority** appear there too.
- **Regions:** Ergon reads your HoYoLAB profile when you sign in and on every check-in, and keeps the game accounts
  it lists. A game where you have accounts in several regions shows a **Regions** card with one switch per region you
  actually have an account in. Switch off a region you don't want automated; at least one stays on. Gift codes are
  only redeemed on the accounts of the regions that are on. (The daily check-in is a single request for your whole
  HoYoLAB account, so it doesn't depend on the region.)

**News (HoYoLAB events)**
- Off by default. The **News** tab is always there; in a game's Configure screen, switch on **HoYoLAB news** to fill
  it. With no game switched on, Ergon doesn't fetch anything. Chips at the top filter the list by game.
- Every 6 hours Ergon reads the newest 15 entries of the public HoYoLAB event list of each game that has News on (no
  sign-in needed). The game of an event is known from the list it comes from.
- An event is important when it gives a premium currency or pass (Primogems, Genesis Crystals, Fates, Welkin Moon,
  Stellar Jade, Oneiric Shards, Special Passes, Polychrome, Monochrome, Master Tapes, S-Chips), or is a web event, a
  raffle, a figure or a hardware prize (phone, laptop, MacBook, iPad, PlayStation, Xbox, Switch). Codes, Stellin and
  gift cards alone don't count: Ergon redeems codes on its own.
- Never important, even with a reward word: top-up and share events, creator contests, Machine Workshop and Travel
  Mimo, Wonderland events, birthdays, fan art, screenshot and submission contests, and the Time Album posts. The words
  are in `NewsWords` (`News.kt`).
- An event stays while it is running and for 30 days after it ended, up to 100 in all games together, and also when it
  drops out of the newest 15. A match shows with its title, a short text and an **Open article** button, and as a
  notification that opens the article. The newest 15 ids of each game are remembered, so nothing comes twice.
- The first check of a game (or when you turn it on again) only fills the News tab: nothing is notified for what is
  already there. An info button in the tab explains all this.

**Discord webhook (gift codes)**
- The small **Webhook** button in the Gift codes card opens a popup with one box for Genshin Impact, Honkai: Star Rail
  and Zenless Zone Zero. Paste a Discord webhook URL (Server Settings, Integrations, Webhooks) for each game you want.
- Saving sends one short test message and keeps the URL only if Discord accepts it. The URL is stored encrypted, is
  never shown again (the box shows dots, with Replace and a bin to remove it) or logged, and only Discord's own
  addresses are accepted.
- Only codes Ergon hasn't seen before are posted. It remembers the codes of every code check, with or without a
  webhook, so a webhook you save starts from that and the codes found after the last check are posted. Only when
  Ergon has seen nothing yet, the first check just notes what is listed. A code is never posted twice. Each code
  is a header with its rewards in small text under it and a bold **Click to claim** link to the game's redeem page. A
  message holds as many codes as Discord's 2000-character limit allows, the rest go into further messages ("1/2",
  "2/2"), a second apart, and Ergon waits as long as Discord asks when it answers "slow down". If Discord can't be
  reached, the rest is sent on a later pass.
- Posting happens during the code check, so it needs the game's auto-redeem switched on and you signed in.

**Schedule**
- Check-in runs once a day, 30 minutes after the daily reset (00:30 UTC+8). Games already claimed that day
  are never requested again.
- Gift codes are checked every 30 minutes (the hoyo-codes list every time, the Game8 page about once an hour) and
  fully re-scanned every 6 hours. A code found by either source is redeemed once, and each source can fail without
  holding back the other.
- The web events run 30 minutes after each reset, 15 minutes before each reset and every 6 hours. Community
  missions reset at 00:00 UTC+8 and game missions at 04:00 server time. One run handles the games one after
  the other.

**No internet**
- Ergon watches the connection. A run that starts offline waits up to 90 seconds for it, and a connection lost
  halfway waits up to 2 minutes. If it doesn't come back, the run is saved as pending (it survives closing the
  app and rebooting) and a catch-up job finishes it as soon as there is internet. The Home screen shows what is waiting.
- Nothing is lost or claimed twice: check-ins, codes and event tasks are saved as they are done, so a resumed run only
  does what is left. Requests that failed because of the connection are repeated, never counted as a refusal.
- The 30-minute run also catches a check-in that never got done today (at most 4 tries a day, 2 hours apart).
- Runs that start together at 00:30 UTC+8 no longer drop each other: the one that finds the other busy tries again.

**Sign-in and privacy**
- Sign-in happens in an in-app browser. Ergon keeps only the HoYoLAB cookies it needs, stored encrypted on
  the device, and sends them only to HoYoverse servers.
- Gift codes need the second sign-in step, which provides a separate cookie for the redeem page.
- A redeem request is sent at least once a day, even with no new codes, so that cookie doesn't expire.
- If a session expires you get a notification, and you sign in again from the app.

**Machine Workshop and Traveling Mimo**
- Genshin has the Machine Workshop, Star Rail and Zenless have Traveling Mimo. All three work the same way and
  each has its own card on the Home screen, its own on/off switch and its own auto exchange settings.
- Tasks that need you in the game (known per game) are never completed through the API: they are only watched,
  and claimed once their progress is complete.
- Every other task gets one attempt to be completed through the API per day or week. If the server refuses,
  the task is only watched, and claimed once its progress is complete.
- Tasks already claimed are skipped.
- **Auto exchange** (off by default, per game): after the tasks, the exchange shop is read once per run.
  Rewards that cost 0 are always claimed. Then the rewards in that game's **Exchange priority** list are claimed
  top to bottom. If the top reward costs more than you have, the mode decides: *Wait for points* keeps
  saving, *Claim what I can afford* moves on to the next affordable one.
- **Prize draw** (Star Rail and Zenless): in the exchange priority screen, *Prize draw only* claims nothing and
  spends the points on draws. *Draw when nothing is left to claim* (with either of the other two modes) draws once
  no reward in the list is left to claim, never while you are saving for one. The server allows 5 draws per game
  per day, and Ergon never goes past that. Genshin's prize draw is left to you.
- If a reward or prize is delivered as a redeem code, Ergon redeems it for you through the gift-code API (needs the second
  sign-in step). Without it, the code is shown in Activity and in the notification.
- Claimed rewards are hidden. Timed rewards (on a refresh timer) are listed but not claimable until they refresh.
  Their stock is first come, first served, so Ergon sets an exact alarm a few seconds before the refresh and
  claims the moment the reward is back, following your priority list and points as usual. The alarm only exists
  while auto exchange is waiting for such a reward, and it is set again after a reboot.
- Every claim request is sent at least 5 seconds after the previous one, across all games.

**Other**
- Background runs use Android's WorkManager, so they happen even when the app is closed. Nothing runs in
  between, and there is no always-on service. The one extra is the exact alarm for a timed reward described
  above. Phones with strict battery saving may delay the runs, which is
  what the *Unrestricted battery usage* button in Settings is for.
- Ergon checks GitHub for new releases and offers an **Update** button. The check can be turned off in
  Settings. Updates only install over a build signed with the same key.
- Runs can be started or stopped manually from each card on the Home screen.
- Each screen keeps its scroll position when you switch tabs or open a Configure screen.

**Disclaimer**
Unofficial fan project, not affiliated with or endorsed by HoYoverse. Automating accounts is your own call
and at your own risk.

**Development**
See [BUILDING.md](BUILDING.md).

**Name, icon and identifiers**
The name "Ergon", the app icon (`assets/icon.svg` and the launcher icon files in the app), the name "mineygg" and
the application ID and package names `com.mineygg.ergon` (`applicationId` and `namespace` in
`app/build.gradle.kts`, and the Kotlin source packages) are **not** covered by the GPL v3, and no rights to use
them are granted. If you fork or modify Ergon, remove them and use your own name, icon, application ID and
package names, and don't suggest your version comes from or is endorsed by the original author.
The copyright notice and the link to this project must stay, as `NOTICE` requires. The source code itself stays
under the GPL v3.

**License**
Copyright (C) 2026 mineygg. GNU General Public License v3.0 (see `LICENSE`), with the attribution terms in `NOTICE`.
The name, icon and identifiers are excluded, as described above.
