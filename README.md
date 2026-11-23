# Live TV Home — an ad-free launcher for the NVIDIA Shield (2019)

A home screen built around live TV from your **HDHomeRun**, with **Plex Live TV** one press away.
Your streaming apps (YouTube, Prime Video, Disney+, Plex) and your Plex movies sit right on the home screen too.
No ads, no tracking. The app only talks to your HDHomeRun tuner, `api.hdhomerun.com` for guide data,
your Tunarr server if you add one, Open-Meteo (and Zippopotam.us for a ZIP code) if you turn on weather, and, if you link Plex, your Plex server and `plex.tv` for the one-time sign-in.

## The three layouts (switch any time in ⚙ Settings → Home screen layout)

| | Layout | What it is | Best for |
|---|---|---|---|
| **A** | **Guide** (default) | Streaming apps across the top. Below them, your channels on the left showing what's on now with a progress bar, and a preview of the selected channel on the right (show, time left, description, up next). | Channel surfers |
| **B** | **Channel Wall** | Big tiles across the top: Resume, HDHomeRun, Plex Live TV. Below them: your Streaming apps, Plex Continue watching and Recently added movies, a wall of channel cards showing what's on now, then all apps. | Everyday mixed use |
| **C** | **Simple** | A large clock and four huge tiles: Watch TV, TV Guide, Plex Live TV, Apps. | The restricted profile: anyone can get to TV in one press |

## Look and feel

The design is inspired by Apple TV:
- **Background:** soft art from whatever is selected (the current show, or a Plex movie's backdrop) fills the screen behind the menus, dimmed.
- **Tiles:** frosted-glass tiles with no borders.
- **Focus:** the selected item lifts and grows. Text tiles and list rows turn white with dark text; app banners and posters just lift with a shadow.
- **App names:** they appear only under the selected streaming app.

All of the colors are in `Ui.java` if you want to tune them.

## Set up from your phone

Typing addresses with a remote is slow. Instead, go to ⚙ Settings → **Set up from your phone** and scan the QR code with your phone's camera. A settings page opens on your phone, where you can:
- set the **HDHomeRun**, **Tunarr** and **Plex server** addresses
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

## Streaming apps and Plex movies

Every layout now has a **Streaming** row of big app banners. By default it shows YouTube, Prime Video, Disney+ and Plex (and SmartTube, if it's installed). It only shows the apps that are allowed in the current profile.
- **To add or remove apps:** open **Apps** and long-press OK on any app to pin or unpin it.
- **Plex rows:** *Continue watching* and *Recently added movies*, with posters.
  - **Channel Wall** shows them right under Streaming.
  - **Guide** shows them below the channel list.
  - **Simple** shows Continue watching only.

**Connect Plex:** ⚙ Settings → **Plex** → you'll get a 4-letter code. On your phone, go to `plex.tv/link` and enter it. The launcher then finds your server on your home network automatically. Do this inside the restricted profile if that's where you want the rows, because each profile keeps its own settings. Sign in as the Plex Home user that profile should see.

**Opening a movie** (⚙ Settings → Plex → *Open Plex movies in*):
- **Built-in player** (default): plays the file directly from your server, with no transcoding, and resumes where you left off. It reports progress back to Plex, so Continue Watching and watched status stay correct. OK = pause, Left = back 10 s, Right = forward 30 s. If a file can't play here (usually audio the device can't decode), the screen says so and **OK opens it in the Plex app** instead.
- **Plex app**: the launcher opens the title in Plex with a "play" link. Plex releases 2026.18 and later often open without playing, a known Plex bug. So a few seconds later the launcher also asks the Plex app to play it through Plex's own remote control.
  - That second step needs remote control turned on in the Plex app: Settings → Advanced → **Advertise as player** (wording varies by version).
  - If Plex still only opens, the log says why. The built-in player avoids all of this.

**About ads inside apps:** the launcher itself has none. Ads *inside* YouTube, Prime Video, Disney+ or Plex's free channels depend on your subscription with each service, and no launcher can remove them.

## Watching TV

Pick a channel and it plays right away in the built-in player, straight from the tuner.

| Remote | Action |
|---|---|
| Up / Down | Open the channel list (what's on each channel now). Up/Down to move, OK to switch, Back or Left/Right to close. It also closes by itself after 10 s. |
| CH+ / CH− | Next / previous channel directly |
| OK / ⏯ | Pause or resume live TV |
| Left / Right | Back 10 s / forward 10 s |
| ⏪ / ⏩ | Back 30 s / forward 30 s. Skipping past the live point takes you back to live. |
| Hold Left | Back to the channel you were just on |
| Info | Show or hide the info banner |
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
- Its channels are listed after your antenna channels, with guide data from Tunarr's own XMLTV guide.
- They play in the built-in player, with pause and rewind.
- *Favorites only* always keeps Tunarr channels.
- If a Tunarr channel has the same number as an antenna channel, the antenna channel wins.
- Typing a number on the remote picks an exact match first. For example, typing `4` opens Tunarr channel 4; type `41` for 4.1.

**Plex Live TV:** the Plex tile opens the Plex app. Plex doesn't allow other apps to open its Live TV screen or tune a channel directly, so you choose Live TV inside Plex. If your Plex DVR uses this same HDHomeRun, those channels are already in this launcher's guide.

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
  - If the HDHomeRun or Tunarr can't be reached, it tries again after 30 seconds, then 1 minute, then 2, then every 5 minutes until it's back. You don't need to do anything.
  - While a source is down, its last known channels stay in the list.
  - If the saved HDHomeRun address stops answering (for example, your router gave the tuner a new address), the launcher looks for the tuner on your network automatically.
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
  Weather            Current conditions for the clock (Open-Meteo)
  PhoneSetupServer   "Set up from your phone" web page + QR code
  CamerasActivity    RTSP security-camera grid / full screen
  AppLog / App       Daily log files (3 days, 1 MB/day) + crash logging
  PlexClient         plex.tv link sign-in, server discovery, On Deck / Recently Added, progress reporting
  PlexStore          Plex cache + how to open an item (deep link or built-in player)
  ChannelStore       Cache + background refresh (every 15 min)
  Apps               Launching apps, app list, playback routing
  SettingsActivity / AppsActivity / Prefs / Ui / ImageLoader
```
