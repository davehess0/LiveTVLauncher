# Live TV Home — an ad-free launcher for the NVIDIA Shield (2019)

A home screen built around live TV from your **HDHomeRun**, with **Plex** one press away in the Streaming row.
Your streaming apps (YouTube, Prime Video, Disney+, Plex) and your Plex movies sit right on the home screen too.
No ads, no tracking. The app only talks to your HDHomeRun tuner, `api.hdhomerun.com` for guide data,
your Tunarr server and IPTV playlist / guide if you add them, Open-Meteo (and Zippopotam.us for a ZIP code) if you turn on weather, RainViewer and Esri map tiles when you open the radar, the National Weather Service for alerts, your Plex server and `plex.tv` for the one-time sign-in if you link Plex, your Jellyfin or Emby server if you add one, and GitHub to look for new releases (when the app opens, every few hours while it's open, and when you press **Check for updates**).

## The three layouts (switch any time in ⚙ Settings → Home screen layout)

| | Layout | What it is | Best for |
|---|---|---|---|
| **A** | **Guide** (default) | Streaming apps across the top. Below them, your channels on the left showing what's on now with a progress bar, and a preview of the selected channel on the right (show, time left, description, up next). | Channel surfers |
| **B** | **Channel Wall** | Big tiles across the top: Resume, HDHomeRun, Settings (plus Cameras if you've added any). Below them: your Streaming apps, Plex Continue watching and Recently added movies, a wall of channel cards showing what's on now, then all apps. | Everyday mixed use |
| **C** | **Simple** | A large clock and huge tiles: a double-width Watch TV, then TV Guide and Apps (plus Cameras if you've added any). Plex is still in the Streaming row. | The restricted profile: anyone can get to TV in one press |

## Look and feel

The design is inspired by Apple TV:
- **Background:** art from whatever is selected (the current show, or a Plex movie's backdrop) fills the screen behind the menus, dimmed. Full-size art is shown sharp; pictures too small for that become a soft blur of their colors.
- **Tiles:** frosted-glass tiles with no borders. Scrolling rows run to the screen edges.
- **Focus:** the selected item lifts, grows and gets a soft white glow that fades out around it. Text keeps its colors.
- **Streaming apps:** shown as their banners only, with no names underneath.

**Colors:** pick a look in ⚙ Settings → Home screen → **Colors**. It changes every screen at once.
- **Classic black:** near-black with grey glass (the original look).
- **Midnight blue:** deep blue-black with a soft blue glow and blue-tinted glass, like Google TV and Plex.
- **Colors from the show:** the background takes its color from the art of whatever is selected, so the screen changes mood as you move around.
- **Deep teal:** a dark blue-green slate with teal glass and accents.
- **Liquid Glass:** modeled on Apple TV's tvOS 26: clear glass tiles with a bright rim, and more of the selected show's art showing through, tinted from it.

Each profile keeps its own choice. The colors for each look are in `Ui.applyTheme()` if you want to tune them.

## Set up from your phone

Typing addresses with a remote is slow. Instead, go to ⚙ Settings → **Set up from your phone** and scan the QR code with your phone's camera. A settings page opens on your phone, where you can:
- find your **HDHomeRun** on the network and pick it, or type its address
- set the **Tunarr**, **IPTV playlist / guide** and **Plex server** addresses
- set the **weather** location
- **add or remove cameras** (passwords are masked)

Press **Save**, and the TV reloads its channels right away.

- Your phone must be on the same Wi-Fi as the TV. The page is served by the TV itself, on port 8765, and nothing goes over the internet.
- The page only works while the QR code is showing. It stops when you press Done, and on its own after 15 minutes.
- The QR code holds a random key that changes every time, so other devices on your network can't change settings. Settings PIN, if set, still guards the Settings screen where the code appears.
- If scanning doesn't work, type the address shown next to the code into the phone's browser.

## Security cameras (Eufy and other RTSP cameras)

Add cameras in ⚙ Settings → **Cameras** → **Add camera**: give each one a name and its stream address. Once you have at least one, a **Cameras** button appears on every layout.
- **Grid:** up to four cameras show live at once, muted. Press OK on one to open it full screen with sound. Left and Right switch cameras, and Back returns to the grid. With only one camera, it opens straight to full screen.
- **Reconnecting:** a dropped stream reconnects by itself every few seconds. The screen says why it dropped, for example "Can't reach this camera" or a wrong password.
- **Getting the address from Eufy:** in the Eufy Security app, open the camera → Settings → Storage (or NAS) → **RTSP stream**. Turn it on, set a user name and password, and copy the address it shows. It usually looks like `rtsp://user:password@192.168.1.50/live0`, but use exactly what the app shows.
- **Which Eufy cameras work:** RTSP is offered on many plugged-in models, such as the Indoor Cams, some SoloCams and floodlights, and cameras on a HomeBase 2 or 3, depending on firmware. Battery cameras usually don't stream around the clock, to save battery.
- **Battery cameras:** run [go2rtc](https://github.com/AlexxIT/go2rtc) with the eufy-security-ws add-on (for example in Home Assistant) and add go2rtc's RTSP address here, such as `rtsp://192.168.1.10:8554/front_door`.
- **Privacy:** camera addresses, including passwords, are stored only on this device. Passwords are masked in Settings and in the log.
- **Connection type:** the app tries RTP over TCP first and switches to UDP if the camera won't accept TCP. The log shows which one worked.
- **Eufy keyframe repair:** Eufy cameras send each keyframe in a form most players can't recognize, so the picture never starts and the spinner keeps going. Over TCP, the app sends the stream through a small built-in relay that rebuilds those keyframes in the standard form. Everything else passes through unchanged, and the log notes how many keyframes it repaired.
- **"Stream Not Found (404)" after it worked:** battery cameras behind a HomeBase stop serving RTSP after a while to save battery. Open the camera's live view in the Eufy app, or set it to continuous recording if your model supports it, and the stream comes back.
- **Passwords:** a camera password can't contain `@`, because the video player misreads the address. Settings warns you if it does. Other symbols are fine.
- **"The camera turned down the user name / password (401)":** the camera itself is rejecting the login, so it's not an app problem.
  - In the Eufy app, turn the camera's RTSP setting off and back on, re-enter the user name and password, save, and restart the camera.
  - To check outside the app, open the same address in VLC on a computer (Media → Open Network Stream).

## Updating the app

Updates install from inside the app, with no computer or `adb` needed. It works from the main profile or the restricted profile, and the update applies to every profile. On the Shield the restricted profile is allowed to install apps; if Android blocks installs in a profile, the app says so and you update from the main profile instead.

- **Automatically:** when the home screen opens (and again every 6 hours while it stays open), the app quietly asks GitHub for the newest release. If it's newer than what's installed, it downloads and installs it without asking. Android 11 (the Shield) still shows its own one-tap **Update** confirmation; newer Android versions install it silently. If you're watching TV when it finds one, it waits until you're back on the home screen. Nothing shows when you're up to date or offline.
- **From the TV:** ⚙ Settings → System → **Check for updates**. It asks this repo's GitHub Releases for the newest version, shows its notes, and on **Install** downloads the APK and hands it to Android, which asks you to confirm. The launcher then restarts on the new version with all settings kept.
- **From your phone:** download the APK to your phone, open ⚙ Settings → **Set up from your phone**, scan the code and use **Install an update** at the bottom of the page. Confirm on the TV. This needs no token.
- **Private repo:** give the app a GitHub token so it can read the Releases. Make a *fine-grained* token at github.com → Settings → Developer settings → Personal access tokens, with access to **only this repository** and **Contents: Read-only**. Then either:
  - paste it into **GitHub token** on the phone setup page. It's stored only on the TV and never shown again, and ⚙ Settings → System → GitHub token can remove it. Or:
  - add `githubToken=github_pat_…` to `local.properties` (never committed). Every APK you build then carries it, and a token set on the TV takes priority. Anyone holding that APK file could pull the token out of it, which is why it should be read-only and limited to this one repo.
  The token is only sent to `api.github.com`, and the log masks it.
- **The first time,** Android asks you to let Live TV Home install apps. Settings walks you there (on the Shield: Device Preferences → Security & restrictions → Unknown sources → Live TV Home). This is a separate switch in each profile, so turn it on in every profile you'll update from.
- **Restricted profile:** anyone using it can install updates, but only genuine Live TV Home updates signed with your key. Set a Settings PIN if you want to control who can.
- **Safety checks:** before installing, the app checks that the APK is Live TV Home, isn't older than what's installed, and is signed with the same key. If any check fails, it explains why and installs nothing.

**Publishing an update:**
1. Raise `versionCode` and `versionName` in `app/build.gradle`.
2. Build the APK on the **same computer** as the installed copy. Release builds are signed with that computer's debug key, and Android only accepts an update signed with the same key. APKs built by GitHub Actions use a different key every run, so they can't update an existing install.
3. Create a GitHub Release tagged `v<versionName>` (for example `v1.2`) and attach the `.apk`.

## Log

The launcher keeps a small log to help track down problems: channel loading, tuning and how long it took, playback errors, Tunarr, Plex and weather failures, cameras, and crashes.
- One file is kept per day, and only the last 3 days are kept. Each day's file stops at 1 MB, so it can't grow large.
- To read it on the TV, go to ⚙ Settings → **Log**.
- To copy it to a computer: `adb pull /sdcard/Android/data/com.dave.livetvlauncher/files/logs`
- Plex tokens, HDHomeRun keys and camera passwords are masked before anything is written.

## Weather

Every layout can show the current weather next to the clock, for example ☁️ 57°. To turn it on, go to ⚙ Settings → **Weather** and enter a ZIP code or city.
- Data comes from [Open-Meteo](https://open-meteo.com), which is free and needs no account or key.
- It updates every 30 minutes.
- US locations use °F and most other places use °C.
- Weather is off until you set a location. What you type is looked up once: US ZIP codes at [Zippopotam.us](https://zippopotam.us) and place names at Open-Meteo. After that, only the location's coordinates are sent to Open-Meteo.
- Clear the field to turn weather off again.
- **Forecast and radar:** select the temperature next to the clock (OK on the remote, or tap it) to open the weather screen.
  - **10-day forecast:** highs, lows, conditions and chance of rain for each day.
  - **Radar:** an animated loop of the last 2 hours of rain and snow on a dark map centered on your location.
  - Left / Right switch tabs; Back closes.
  - Radar comes from [RainViewer](https://www.rainviewer.com) and the map from Esri. Both are downloaded only while the Radar tab is open.
- **Weather alerts (US):** the launcher checks the [National Weather Service](https://www.weather.gov) every 5 minutes for watches, warnings and advisories at your location.
  - **On the home screen:** a pop-up shows the alert, how serious it is, how long it lasts and the areas covered. **Dismiss** hides it for good. **Details** shows the full NWS text and what to do. **Later** (or Back) hides it until the app restarts.
  - **While watching TV:** a colored banner appears across the top for 15 seconds, without interrupting the show.
  - **Minor advisories** don't pop up; they only appear in the weather screen.
  - **⚠ next to the temperature** means an alert is active. Select it to see every alert at the top of the weather screen.
  - To turn alerts off, go to ⚙ Settings → **Weather alerts** → Off. Outside the US there are no alerts.

## Streaming apps and Plex movies

Every layout now has a **Streaming** row of big app banners. By default it shows YouTube, Prime Video, Disney+ and Plex (and SmartTube, if it's installed). It only shows the apps that are allowed in the current profile.
- **To add an app:** open **Apps**, hold OK on the app and choose **Add to Streaming row**.
- **To move or remove an app:** hold OK on it in the Streaming row and choose **Move** (then ◀ ▶, and OK when done) or **Remove**.
- **Plex rows:** *Continue watching* and *Recently added movies*, with posters. OK on something you've partly watched asks **Resume from 1:07:57** or **Start from the beginning** (Plex, Jellyfin and Emby).
  - **Channel Wall** shows them right under Streaming.
  - **Guide** shows them below the channel list.
  - **Simple** shows Continue watching only.

**Connect Plex:** ⚙ Settings → **Plex** → you'll get a 4-letter code. On your phone, go to `plex.tv/link` and enter it. The launcher then finds your server on your home network automatically. Do this inside the restricted profile if that's where you want the rows, because each profile keeps its own settings. Sign in as the Plex Home user that profile should see.

**Opening a movie** (⚙ Settings → Plex → *Open Plex movies in*):
- **Built-in player** (default): plays the file directly from your server, with no transcoding, and resumes where you left off. It reports progress back to Plex, so Continue Watching and watched status stay correct. OK = pause, Left = back 10 s, Right = forward 30 s. If a file can't play here (usually audio the device can't decode), the screen says so and **OK opens it in the Plex app** instead.
  - **Frame rate:** movies and shows switch the TV to the video's own rate (23.976, 24, 25 … Hz) at the same resolution while they play, so film doesn't judder or shimmer from 3:2 pulldown at 60 Hz. The TV goes black for a moment while it switches. Turn it off in ⚙ Settings → Playback → **Match movie frame rate**. The rate comes from Plex / Jellyfin / Emby, and the TV switches before the video starts, so playback and the receiver's audio aren't interrupted. Only if the server doesn't know it does the player measure it from the first few seconds of video and switch then. A brief audio dropout while the TV switches doesn't hand the title to VLC. The log shows the video's frame rate and what the TV switched to.
  - **Dolby Vision profile 7** (UHD Blu-ray rips, `dvhe.07`) plays on the Shield's Dolby Vision decoder, which doesn't officially support it. If such a title flickers or looks off, ⚙ Settings → Playback → **Dolby Vision profile 7** → **Play as HDR10** plays its HDR10 base layer instead.
- **Plex app**: the launcher opens the title in Plex with a "play" link. Plex releases 2026.18 and later often open without playing, a known Plex bug. So a few seconds later the launcher also asks the Plex app to play it through Plex's own remote control.
  - That second step needs remote control turned on in the Plex app: Settings → Advanced → **Advertise as player** (wording varies by version).
  - If Plex still only opens, the log says why. The built-in player avoids all of this.

**Jellyfin and Emby:** ⚙ Settings → Sources → **Jellyfin** or **Emby** → enter the server's address (the one you open in a browser, usually port 8096), then sign in:
- **Jellyfin Quick Connect** (if your server has it on): the TV shows a 6-digit code; in the Jellyfin app on your phone, open Settings → Quick Connect and enter it.
- **Username and password**, on the TV or from your phone (⚙ Settings → Sources → *Set up from your phone*). The password isn't stored, only the session the server gives back.

Their *Continue watching* (including the next episode of shows you're watching) and *Recently added movies* join the Plex rows on the home screen. Titles play in the built-in player, straight from the file (no transcoding), resume where you left off, and report progress back to the server.

**About ads inside apps:** the launcher itself has none. Ads *inside* YouTube, Prime Video, Disney+ or Plex's free channels depend on your subscription with each service, and no launcher can remove them.

## Watching TV

Pick a channel and it plays right away in the built-in player, straight from the tuner.

**Recent Channels:** every layout has a row of the last 10 channels you watched, newest first, each showing what's on now. In the Guide it sits above the channel list; on Channel Wall and Simple it's above Streaming.

**The Guide's preview box:** press Right from the channel list to select it. Up / Down scroll through the full show description, OK opens the channel, and Left goes back to it in the list.

**Live preview in the Guide:** when you rest on a channel in the Guide layout for about a second, its preview panel plays that channel live, without sound. The logo shows until the video starts. Channels the device can't decode use VLC, as in the full player. The preview uses one tuner while it plays and gives it back as soon as you open a channel, move to another row or leave the Guide. To turn it off, for example if your tuners are often all busy, go to ⚙ Settings → **Live preview in the guide**.

**Subtitles and closed captions:** turn them on in ⚙ Settings → Playback → **Subtitles / captions**, or while watching by **holding OK** (or a remote's CC button, or the **CC** touch button). The choice is remembered.
- Antenna channels show their broadcast closed captions.
- Plex movies show subtitles stored in the file, and separate subtitle files (.srt, .ass, .vtt) that sit next to the movie on your Plex server. The player picks your device's language first, then English.
- Text size and style follow Android's caption settings (Settings → Accessibility → Captions).

**Starting up:** when the app starts, for example after the Shield reboots or when you open it on a tablet, it goes straight to the last channel you were watching, like a cable box. Press Back for the home screen. It happens once per start, never on later Home presses. To change it, go to ⚙ Settings → **When the app opens** → *Show the home screen*.

| Remote | Action |
|---|---|
| Up / Down | Open the channel list (what's on each channel now). Up/Down to move, OK to switch, Back or Left/Right to close. It also closes by itself after 10 s. |
| CH+ / CH− | Next / previous channel directly |
| OK / ⏯ | Pause or resume live TV |
| Left / Right | Back 10 s / forward 10 s |
| ⏪ / ⏩ | Back 30 s / forward 30 s. Skipping past the live point takes you back to live. |
| Hold Left | Back to the channel you were just on |
| Info | Show or hide the info banner |
| Hold OK | Subtitles / closed captions on or off |
| 0–9 | Type a channel number (type `51` for 5.1) |
| Back | Return home. This releases the tuner and clears the pause buffer. |

**On a tablet or phone (touch):**
- **Tap** the video to show or hide the controls: ← back, −30 / −10 / pause / +10 / +30, ☰ Channels, CH ▲▼, and **● LIVE** when you're behind live. The controls fade after 4 seconds unless paused.
- **Swipe up or down** to open the channel list. Tap a channel to switch, or tap outside the list to close it.
- **Swipe left or right** to go back or forward 30 s. **Double-tap** the left or right half to go back or forward 10 s.
- Plex movies get the same tap controls (−10 / pause / +30).
- None of this affects the TV remote.

**Any video or audio format:** the built-in player uses the device's own decoders when it can. If a channel or Plex file uses a format the device can't decode, the app switches to VLC's engine automatically, which has its own decoders. For example, phones and tablets like the OnePlus Pad usually can't decode the MPEG-2 video and AC-3 audio of antenna channels. Pause, rewind and the channel list work the same in VLC mode. Once a source has needed VLC, its other channels go straight to VLC. The log shows when VLC is used.

**Pause and rewind:** while you watch a channel, it's recorded to a buffer on the Shield's storage. The buffer holds up to 2 GB, about 15–20 minutes of an HD channel, and never more than a third of the free space. You can pause and rewind within it. The banner shows how far behind live you are. While paused, the channel keeps recording and holds its tuner. Changing channels or pressing Back clears the buffer.

You can change this in Settings → **Play channels with**:
- **Built-in player** (default)
- **Another video app** (VLC, Kodi, etc.)
- **HDHomeRun app.** That app has no "tune to channel" link, so it opens on its own guide.

**Favorites:** if you've marked favorite channels in the HDHomeRun app, set Settings → **Channels shown** → *Favorites only*.

**Tunarr channels:** if you run a [Tunarr](https://github.com/chrisbenincasa/tunarr) server, enter its address in ⚙ Settings → **Tunarr server**, for example `http://192.168.1.50:8000`.
- **Find Tunarr on the network** (in the Tunarr group, or on the phone page) searches for it and lets you pick it. It asks over SSDP first, then tries port 8000 on your network. If the saved address stops answering (say the router gave the server a new address), the launcher searches by itself, at most every 10 minutes, and switches to the server if it finds exactly one.
- Its channels are listed after your antenna channels, with guide data from Tunarr's own XMLTV guide.
- They play in the built-in player, with pause and rewind.
- *Favorites only* always keeps Tunarr channels.
- If a Tunarr channel has the same number as an antenna channel, the antenna channel wins.
- Typing a number on the remote picks an exact match first. For example, typing `4` opens Tunarr channel 4; type `41` for 4.1.

**IPTV channels:** in ⚙ Settings → **IPTV**, enter your provider's M3U / M3U8 playlist link (easiest from your phone, see above). An Xtream-style `get.php?username=…&password=…&type=m3u_plus` link works too.
- Channels are listed after the antenna and Tunarr channels. They keep the playlist's `tvg-chno` number when it's free; otherwise they're numbered from 1000 up, in playlist order.
- **Favorites:** hold OK on an IPTV channel (in the channel list or the Full Guide) and choose **Add to favorites**. Favorites get a ★ and move to the top of the IPTV channels. The list stays put while you're starring (so you can work down it), and re-sorts the next time you come back to it. Hold OK again to remove one.
- **Reminders:** the same hold-OK menu has **Remind me** for the channel's next show (in the channel list) or the show you're on (in the Full Guide), when the IPTV guide lists it. You get a pop-up a minute before it starts, as with antenna channels. Channels without a guide can't have reminders. They're remembered by channel name, so they survive playlist reloads.
- Guide: add an XMLTV link (plain or `.gz`) under **Guide (XMLTV)**, or leave it blank to use the guide the playlist names (`url-tvg`). Channels are matched by `tvg-id`, then by name. The guide is re-downloaded every 3 hours.
- **Channel groups** lets you hide playlist groups (`group-title`) you don't want. **Hide all** clears every box so you can tick just the few you want (it turns into **Show all** to undo). At most 3,000 channels are loaded, so hide groups to trim a big provider list.
- MPEG-TS streams get full pause and rewind. HLS (`.m3u8`), DASH, RTMP and RTSP streams play directly, and pause / rewind work within the stream's own live window. Formats the built-in player can't decode switch to VLC as usual.
- Playlist and guide links often contain your username and password. The TV shows only the host name, and the log masks them.

**Plex Live TV:** open Plex from the Streaming row. Plex doesn't allow other apps to open its Live TV screen or tune a channel directly, so you choose Live TV inside Plex. If your Plex DVR uses this same HDHomeRun, those channels are already in this launcher's guide.

## Build the APK

Pick one:
- **Android Studio:** File → Open → this folder → Build → Build APK(s). The APK lands in `app/build/outputs/apk/`.
- **GitHub Actions:** push this folder to a repo. `.github/workflows/build.yml` builds `LiveTVLauncher-apk` as a downloadable workflow artifact.
- **Command line** (with the Android SDK installed): `./gradlew assembleRelease`

Because VLC's engine is large, a build makes only one APK, for 64-bit ARM: `app-arm64-v8a-release.apk` (about 59 MB). It's for the **NVIDIA Shield, OnePlus Pad** and most current devices. It also runs in the Android emulator, which translates ARM apps. To build for other processors too, add `armeabi-v7a` or `x86_64` to the `splits` list in `app/build.gradle`.

Ready-made APKs are attached to this repo's GitHub Releases.

**Building inside OneDrive (or Dropbox, etc.):** syncing locks Gradle's files mid-build. To avoid that, add a `buildRoot` line to `local.properties`, a per-computer file that isn't shared:
```
buildRoot=C\:/Users/<you>/AppData/Local/LiveTVLauncher/build
```
Builds then go there instead of `app/build/`, and the APK lands in `<buildRoot>/app/outputs/apk/release/`. Android Studio follows the same setting.

The release build is signed with the local debug key so you can sideload it without extra setup.

## Setting it as your home screen

The first time you open Live TV Home (on any device where it isn't already the home screen), it asks **"Make Live TV Home your home screen?"** Choose **Set as home** and Android shows its own "Home app" prompt; pick Live TV Home. Then the remote's Home button opens it and it starts when the device turns on. **Not now** asks again next launch; **Don't ask again** stops the prompt. You can always change it later in Android's Settings → Apps → Default apps → Home app.

On a phone or tablet (OnePlus Pad, etc.) that's all you need. On the Shield's **restricted profile**, Android blocks changing the home app, so use the adb steps below instead (run from the main profile).

## Install on the Shield without loosening the restricted profile

Do these steps from the **main (owner) profile**. The restricted profile's PIN and restrictions stay as they are. The only change is allowing one more app in it.

1. **Turn on network debugging.** Settings → Device Preferences → About → click **Build** 7 times. Then Settings → Device Preferences → Developer options → turn on **Network debugging**. Note the IP address it shows.
2. **Install the app** from your computer:
   ```
   adb connect <shield-ip>:5555
   adb install -r app-release.apk
   ```
3. **Allow it in the restricted profile.** Settings → Device Preferences → Security & restrictions → Restricted profile → **Allowed apps** → turn on **Live TV Home**. Also check that **HDHomeRun** and **Plex** are allowed.
4. **Find the restricted profile's user ID:**
   ```
   adb shell pm list users
   ```
   Look for something like `UserInfo{10:Restricted profile:...}`. The `10` is the ID; yours may differ.
5. **Test it before making it the home screen.** Enter the restricted profile and open *Live TV Home* from the app list. Confirm your channels show up.
6. **Make it the home screen for the restricted profile only.** On current Shield software, `set-home-activity` reports success but does nothing. Disabling the stock launcher for that one user is what actually works:
   ```
   adb shell pm disable-user --user 10 com.google.android.tvlauncher
   ```
   Press Home. Live TV Home is now that profile's home screen, and the stock launcher's sponsored rows are gone there. Your main profile is untouched.
   (Optional: to get rid of the stock home screen's ads in your main profile too, run the same command with `--user 0`.)
7. **Check it:**
   ```
   adb shell cmd package resolve-activity --user 10 -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
   You should see `com.dave.livetvlauncher`.

### Getting out / undoing
- **Exit the restricted profile:** inside the profile, open ⚙ Settings → *Android settings* → Device Preferences → Security & restrictions → **Exit restricted profile** (it asks for your PIN). Confirm this menu is there **before** step 6. It used to be reachable from the stock home screen.
- **Emergency exit from a computer:** `adb shell am switch-user 0`
- **Bring back the stock home screen:** `adb shell pm enable --user 10 com.google.android.tvlauncher`
- **After a Shield system update**, the stock launcher can come back. Re-run step 6 if it does.

### Optional: lock the launcher's own settings
⚙ Settings → **Settings PIN** keeps people in the restricted profile from changing the layout or player. It's separate from your restricted-profile PIN.

## Troubleshooting
- **Automatic rechecks:** while the home screen is showing, the launcher reloads channels and the guide every 15 minutes.
  - If the HDHomeRun, Tunarr or IPTV playlist can't be reached, it tries again after 30 seconds, then 1 minute, then 2, then every 5 minutes until it's back. You don't need to do anything.
  - While a source is down, its last known channels stay in the list.
  - If the saved HDHomeRun address stops answering (for example, your router gave the tuner a new address), the launcher looks for the tuner on your network automatically.
- **Finding your tuner:** go to ⚙ Settings → HDHomeRun → **Find HDHomeRun on the network**. It searches your network, the same way HDHomeRun's own apps do, and lists every tuner it finds with its name, model, address and tuner count. Pick one to use it, or choose *Automatic*. The phone setup page has the same **Find HDHomeRun** button.
- **"Can't reach HDHomeRun"**: if it doesn't clear up on its own, set the tuner's IP in ⚙ Settings → HDHomeRun → HDHomeRun address. You can find it at `my.hdhomerun.com` or in your router. Reserving that address for the tuner in your router keeps it from changing.
- **"no guide data"**: the guide needs *Sync with guide server* enabled on the tuner, which it is by default. Channels still play without a guide.
- **A channel won't play**: all tuners may be busy with other TVs or recordings. Copy-protected cable channels are hidden automatically.

## Project layout
```
app/src/main/java/com/dave/livetvlauncher/
  LauncherActivity   HOME screen host; swaps layouts
  GuideLayout        Layout A
  WallLayout         Layout B
  SimpleLayout       Layout C
  PlayerActivity     Built-in live TV player (Media3/ExoPlayer) + channel list overlay
  VlcEngine          VLC fallback for formats the device cannot decode
  Timeshift          On-disk pause/rewind buffer for live TV
  HdhrClient         Tuner discovery, lineup.json, guide API
  TunarrClient       Tunarr lineup.json + XMLTV guide
  IptvClient         IPTV M3U playlist + XMLTV guide (streamed, .gz ok)
  Weather            Current conditions for the clock (Open-Meteo)
  WeatherActivity    10-day forecast + radar loop (RainViewer, Esri map)
  PhoneSetupServer   "Set up from your phone" web page + QR code
  CamerasActivity    RTSP security-camera grid / full screen
  AppLog / App       Daily log files (3 days, 1 MB/day) + crash logging
  PlexClient         plex.tv link sign-in, server discovery, On Deck / Recently Added, progress reporting
  PlexStore          Plex cache + how to open an item (deep link or built-in player)
  ChannelStore       Cache + background refresh (every 15 min)
  Apps               Launching apps, app list, playback routing
  Updater / UpdateReceiver  In-app updates: GitHub Releases check, APK checks, Android installer
  SettingsActivity / AppsActivity / Prefs / Ui / ImageLoader
```
