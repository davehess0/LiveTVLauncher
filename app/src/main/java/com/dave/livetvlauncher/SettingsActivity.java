package com.dave.livetvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Launcher settings. Click a row to cycle its value. */
public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        int p = Ui.dp(this, 48);
        list.setPadding(p * 3, p / 2, p * 3, p);
        sv.addView(list);
        setContentView(sv);
        render();
    }

    /** Groups currently opened (kept while this screen is open). */
    private final java.util.Set<String> expanded = new java.util.HashSet<>();
    private boolean inGroup;

    /** Rebuilds the list, keeping focus on the same row (rows are found by their label). */
    private void render() {
        View f = getCurrentFocus();
        Object keep = f != null ? f.getTag() : null;
        list.removeAllViews();
        TextView title = Ui.text(this, 28, Ui.TEXT, true);
        title.setText("Launcher settings");
        title.setPadding(0, 0, 0, Ui.dp(this, 20));
        list.addView(title);

        row("Set up from your phone", "Scan a QR code to enter addresses on your phone", v -> showPhoneSetup());
        row("Home screen layout", layoutName(prefs.layout()), v -> {
            String l = prefs.layout();
            prefs.setLayout(Prefs.LAYOUT_GUIDE.equals(l) ? Prefs.LAYOUT_WALL
                    : Prefs.LAYOUT_WALL.equals(l) ? Prefs.LAYOUT_SIMPLE : Prefs.LAYOUT_GUIDE);
            render();
        });
        row("Live preview in the guide", prefs.livePreview() ? "On (uses a tuner while showing)" : "Off", v -> {
            prefs.setLivePreview(!prefs.livePreview());
            render();
        });
        row("When the app opens", prefs.startOnLastChannel() ? "Play the last channel" : "Show the home screen", v -> {
            prefs.setStartOnLastChannel(!prefs.startOnLastChannel());
            render();
        });
        row("Play channels with", playerName(prefs.player()), v -> {
            String pl = prefs.player();
            prefs.setPlayer(Prefs.PLAYER_BUILTIN.equals(pl) ? Prefs.PLAYER_EXTERNAL
                    : Prefs.PLAYER_EXTERNAL.equals(pl) ? Prefs.PLAYER_HDHR_APP : Prefs.PLAYER_BUILTIN);
            render();
        });

        group("HDHomeRun", (prefs.tunerIp().isEmpty() ? "Automatic" : prefs.tunerIp())
                + (prefs.favoritesOnly() ? " · Favorites only" : " · All channels"), () -> {
            row("Find HDHomeRun on the network", "Search and pick your tuner", v -> findTuners());
            row("HDHomeRun address", prefs.tunerIp().isEmpty() ? "Automatic" : prefs.tunerIp(), v -> askTunerIp());
            row("Channels shown", prefs.favoritesOnly() ? "Favorites only (set in the HDHomeRun app)" : "All channels", v -> {
                prefs.setFavoritesOnly(!prefs.favoritesOnly());
                render();
            });
        });
        group("Tunarr", prefs.tunarrUrl().isEmpty() ? "Off" : prefs.tunarrUrl(), () ->
                row("Tunarr server", prefs.tunarrUrl().isEmpty() ? "Off · click to add" : prefs.tunarrUrl(), v -> askTunarrUrl()));
        group("Plex", prefs.plexToken().isEmpty() ? "Not connected" : "Connected", () -> {
            row("Plex account", prefs.plexToken().isEmpty() ? "Not connected · click to link"
                    : "Connected · click to re-link", v -> linkPlex());
            row("Open Plex movies in", Prefs.PLEX_BUILTIN.equals(prefs.plexPlayer())
                    ? "Built-in player (direct play, reports progress)" : "Plex app", v -> {
                prefs.setPlexPlayer(Prefs.PLEX_BUILTIN.equals(prefs.plexPlayer()) ? Prefs.PLEX_APP : Prefs.PLEX_BUILTIN);
                render();
            });
            row("Plex server address", prefs.plexUrl().isEmpty() ? "Automatic after linking" : prefs.plexUrl(),
                    v -> askPlexUrl());
        });

        java.util.List<String[]> cams = prefs.cameras();
        group("Cameras", cams.isEmpty() ? "None · click to add" : cams.size() + (cams.size() == 1 ? " camera" : " cameras"), () -> {
            for (int i = 0; i < cams.size(); i++) {
                final int idx = i;
                row(cams.get(i)[0], AppLog.mask(cams.get(i)[1]), v -> askCamera(idx));
            }
            row("Add camera", "RTSP address from the Eufy app (or a go2rtc relay)", v -> askCamera(-1));
        });

        row("Streaming row", "Open Apps and long-press OK on any app to pin / unpin it",
                v -> startActivity(new android.content.Intent(this, AppsActivity.class)));
        row("Apps row on Channel Wall", prefs.showAppRow() ? "Shown" : "Hidden", v -> {
            prefs.setShowAppRow(!prefs.showAppRow());
            render();
        });
        row("Clock", prefs.clock24h() ? "24-hour" : "12-hour", v -> {
            prefs.setClock24h(!prefs.clock24h());
            render();
        });
        row("Weather", prefs.weatherLat().isEmpty() ? "Off · click to set your ZIP code or city"
                : prefs.weatherPlace() + (prefs.weatherFahrenheit() ? " · °F" : " · °C"), v -> askWeather());
        row("Settings PIN", prefs.pin().isEmpty() ? "Off" : "On", v -> askPin());
        row("Refresh channels & guide", ChannelStore.get().status(), v -> {
            ChannelStore.get().refresh(this);
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show();
        });
        row("Log", "Today's events and errors · kept " + AppLog.KEEP_DAYS + " days", v -> showLog());
        row("Android settings", "Wi-Fi, display, sound, restricted profile", v -> Apps.openSystemSettings(this));

        TextView about = Ui.text(this, 14, Ui.SUB, false);
        about.setSingleLine(false);
        about.setPadding(0, Ui.dp(this, 24), 0, 0);
        about.setText("Live TV Home 1.0 — no ads, no tracking, no accounts.\n"
                + "Only talks to your HDHomeRun, api.hdhomerun.com (guide data), your Tunarr server if you add one, "
                + "Open-Meteo if you turn on weather, and — if you link it — your Plex server and plex.tv (sign-in).");
        list.addView(about);
        View target = keep != null ? list.findViewWithTag(keep) : null;
        if (target == null) target = list.getChildAt(1); // first row
        if (target != null) target.post(target::requestFocus);
    }

    /** A heading row that opens / closes the rows built by {@code body}. */
    private void group(String name, String summary, Runnable body) {
        boolean open = expanded.contains(name);
        row((open ? "▾  " : "▸  ") + name, open ? "" : summary, v -> {
            if (!expanded.remove(name)) expanded.add(name);
            render();
        });
        // Tag by name only, so focus stays on the heading when its arrow flips.
        list.getChildAt(list.getChildCount() - 1).setTag("group:" + name);
        if (open) {
            inGroup = true;
            body.run();
            inGroup = false;
        }
    }

    private void row(String label, String value, View.OnClickListener click) {
        LinearLayout r = Ui.card(this, LinearLayout.HORIZONTAL, Ui.ACCENT);
        r.setTag(label);
        Ui.styleFocusable(r, 1.0f); // white-on-focus, no zoom in a settings list
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 18);
        r.setPadding(p, p, p, p);
        TextView l = Ui.text(this, 18, Ui.TEXT, true);
        l.setText(label);
        r.addView(l, Ui.lpWeight(0, -2, 1));
        TextView v = Ui.text(this, 16, Ui.ACCENT, false);
        v.setText(value);
        v.setGravity(Gravity.END);
        r.addView(v, Ui.lpWeight(0, -2, 1));
        r.setOnClickListener(click);
        // Rows inside an open group are indented under their heading.
        list.addView(r, Ui.margins(Ui.lp(-1, -2), inGroup ? Ui.dp(this, 40) : 0, 0, 0, Ui.dp(this, 10)));
    }

    /** Search the network for HDHomeRun tuners and let the user pick one (or Automatic). */
    private void findTuners() {
        AlertDialog searching = new AlertDialog.Builder(this)
                .setTitle("Looking for HDHomeRun tuners…")
                .setMessage("Searching your home network. This takes a couple of seconds.")
                .setNegativeButton("Cancel", null)
                .show();
        net.execute(() -> {
            java.util.List<HdhrClient.Found> found = HdhrClient.findTuners(prefs.tunerIp());
            ui.post(() -> {
                if (!searching.isShowing()) return; // cancelled
                searching.dismiss();
                showTunerChoices(found);
            });
        });
    }

    private void showTunerChoices(java.util.List<HdhrClient.Found> found) {
        String current = prefs.tunerIp();
        java.util.List<CharSequence> items = new java.util.ArrayList<>();
        for (HdhrClient.Found f : found) {
            boolean selected = f.ip.equals(current);
            items.add((selected ? "✓  " : "") + f.label() + "\n" + f.detail());
        }
        items.add((current.isEmpty() ? "✓  " : "") + "Automatic\nFind the tuner by itself each time");
        items.add("Enter an address…");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(found.isEmpty() ? "No HDHomeRun found" : found.size() == 1 ? "Found 1 HDHomeRun" : "Found " + found.size() + " HDHomeRun tuners")
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which < found.size()) {
                        HdhrClient.Found f = found.get(which);
                        prefs.setTunerIp(f.ip);
                        AppLog.i("Channels", "HDHomeRun chosen: " + f.label() + " at " + f.ip);
                        Toast.makeText(this, "Using " + f.label() + " at " + f.ip, Toast.LENGTH_SHORT).show();
                        ChannelStore.get().refresh(this);
                        render();
                    } else if (which == found.size()) {
                        prefs.setTunerIp("");
                        ChannelStore.get().refresh(this);
                        render();
                    } else {
                        askTunerIp();
                    }
                })
                .setNegativeButton("Cancel", null);
        if (found.isEmpty()) b.setMessage(null);
        b.show();
        if (found.isEmpty())
            Toast.makeText(this, "Check the tuner is powered on and on the same network, or enter its address.", Toast.LENGTH_LONG).show();
    }

    private void askTunerIp() {
        EditText in = new EditText(this);
        in.setHint("e.g. 192.168.1.50 (leave blank for automatic)");
        in.setText(prefs.tunerIp());
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("HDHomeRun address")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setTunerIp(in.getText().toString());
                    ChannelStore.get().refresh(this);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private PhoneSetupServer phoneServer;

    /** QR code + address of a small settings web page; the page works only while this is showing. */
    private void showPhoneSetup() {
        phoneServer = new PhoneSetupServer(this, this::render);
        String url;
        try {
            url = phoneServer.start();
        } catch (java.io.IOException e) {
            url = null;
        }
        if (url == null) {
            Toast.makeText(this, "This device isn't on a home network (Wi-Fi or Ethernet) right now.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 24);
        box.setPadding(p, p, p, p);
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setImageBitmap(PhoneSetupServer.qr(url, Ui.dp(this, 260)));
        box.addView(qr, new LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 260)));
        TextView t = Ui.text(this, 16, Ui.TEXT, false);
        t.setSingleLine(false);
        t.setPadding(Ui.dp(this, 28), 0, 0, 0);
        t.setText("Scan with your phone's camera to set the HDHomeRun, Tunarr and Plex addresses, cameras and weather.\n\n"
                + "Your phone must be on the same Wi-Fi.\n\nOr type this into its browser:\n" + url
                + "\n\nThe page stops working when you close this.");
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Set up from your phone")
                .setView(box)
                .setPositiveButton("Done", null)
                .setOnDismissListener(x -> { if (phoneServer != null) { phoneServer.stop(); phoneServer = null; } })
                .show();
        if (d.getWindow() != null) d.getWindow().setLayout(Ui.dp(this, 900), -2);
        // Close by itself after 15 minutes so the page doesn't stay open forever.
        ui.postDelayed(() -> { if (d.isShowing()) d.dismiss(); }, 15 * 60_000L);
    }

    @Override protected void onStop() {
        if (phoneServer != null) { phoneServer.stop(); phoneServer = null; }
        super.onStop();
    }

    /** Add (index -1) or edit / remove a camera. */
    private void askCamera(int index) {
        java.util.List<String[]> cams = prefs.cameras();
        boolean editing = index >= 0 && index < cams.size();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, 0, p, 0);
        EditText name = new EditText(this);
        name.setHint("Name, e.g. Front door");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText url = new EditText(this);
        url.setHint("rtsp://user:password@192.168.1.50/live0");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (editing) { name.setText(cams.get(index)[0]); url.setText(cams.get(index)[1]); }
        box.addView(name);
        box.addView(url);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(editing ? "Edit camera" : "Add camera")
                .setMessage("In the Eufy app: camera → Settings → Storage (or NAS) → RTSP stream. "
                        + "Turn it on, set a user name and password, and copy the address it shows.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim(), u = url.getText().toString().trim();
                    if (u.isEmpty()) { Toast.makeText(this, "Enter the camera's stream address", Toast.LENGTH_LONG).show(); return; }
                    if (!u.matches("(?i)^(rtsps?|https?)://.+")) u = "rtsp://" + u;
                    // The video library splits the address at the first "@", so an "@" inside the
                    // user name or password sends it to the wrong address. Warn rather than fail later.
                    int s = u.indexOf("://") + 3, at = u.lastIndexOf('@');
                    if (at > s && u.substring(s, at).contains("@"))
                        Toast.makeText(this, "This camera's user name or password contains \"@\", which the player can't use. "
                                + "Change the camera's RTSP password in the Eufy app to one without \"@\".", Toast.LENGTH_LONG).show();
                    if (n.isEmpty()) n = "Camera " + (editing ? index + 1 : cams.size() + 1);
                    java.util.List<String[]> list = prefs.cameras();
                    if (editing) list.set(index, new String[]{n, u}); else list.add(new String[]{n, u});
                    prefs.setCameras(list);
                    AppLog.i("Cameras", (editing ? "Edited " : "Added ") + n + " · " + u);
                    render();
                })
                .setNegativeButton("Cancel", null);
        if (editing) b.setNeutralButton("Remove", (d, w) -> {
            java.util.List<String[]> list = prefs.cameras();
            AppLog.i("Cameras", "Removed " + list.get(index)[0]);
            list.remove(index);
            prefs.setCameras(list);
            render();
        });
        b.show();
    }

    /** Today's log, newest at the bottom (scroll with Up / Down). */
    private void showLog() {
        String all = AppLog.today();
        String[] lines = all.isEmpty() ? new String[0] : all.split("\n");
        int from = Math.max(0, lines.length - 400); // the end of the day is what matters; keep the dialog light
        StringBuilder sb = new StringBuilder();
        if (from > 0) sb.append("… ").append(from).append(" earlier lines not shown …\n");
        for (int k = from; k < lines.length; k++) sb.append(lines[k]).append('\n');
        if (lines.length == 0) sb.append("Nothing logged yet today.");
        sb.append("\nFiles: ").append(AppLog.folder());

        TextView t = Ui.text(this, 13, Ui.TEXT, false);
        t.setSingleLine(false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setText(sb);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(true); // so the remote's Up / Down scroll it
        sv.addView(t);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Log · today")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
        sv.post(() -> { sv.fullScroll(View.FOCUS_DOWN); sv.requestFocus(); });
        if (d.getWindow() != null) d.getWindow().setLayout(Ui.dp(this, 900), Ui.dp(this, 480));
    }

    private void askWeather() {
        EditText in = new EditText(this);
        in.setHint("ZIP code or city (leave blank to turn off)");
        in.setText(prefs.weatherPlace());
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Weather")
                .setMessage("Shown next to the clock. Uses Open-Meteo (free, no account).")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (q.isEmpty()) {
                        prefs.setWeather("", "", "", prefs.weatherFahrenheit());
                        render();
                        return;
                    }
                    Toast.makeText(this, "Looking up " + q + "…", Toast.LENGTH_SHORT).show();
                    net.execute(() -> {
                        Weather.Place p = null;
                        try { p = Weather.geocode(q); } catch (Exception ignored) { }
                        final Weather.Place place = p;
                        ui.post(() -> {
                            if (place == null) {
                                Toast.makeText(this, "Couldn't find \"" + q + "\". Try a ZIP code or \"City, State\".",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            prefs.setWeather(place.name, place.lat, place.lon, place.fahrenheit);
                            Weather.get().refreshIfStale(this, null);
                            render();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askTunarrUrl() {
        EditText in = new EditText(this);
        in.setHint("e.g. http://192.168.1.50:8000 (leave blank to turn off)");
        in.setText(prefs.tunarrUrl());
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Tunarr server")
                .setMessage("Its channels are added after your antenna channels, with Tunarr's own guide.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setTunarrUrl(in.getText().toString());
                    ChannelStore.get().refresh(this);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final android.os.Handler ui =new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** plex.tv/link sign-in: show a code, poll until it's entered, then find the server on the LAN. */
    private void linkPlex() {
        final PlexClient pc = new PlexClient(prefs.clientId());
        final TextView msg = Ui.text(this, 18, Ui.TEXT, false);
        msg.setSingleLine(false);
        msg.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        msg.setText("Getting a code from Plex…");
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Link Plex")
                .setView(msg)
                .setNegativeButton("Cancel", null)
                .show();
        net.execute(() -> {
            try {
                PlexClient.Pin pin = pc.createPin();
                ui.post(() -> msg.setText("On your phone or computer, go to\n\nplex.tv/link\n\nand enter:   "
                        + pin.code + "\n\nSign in as the Plex user you want this profile to use."));
                for (int i = 0; i < 150 && dlg.isShowing(); i++) { // ~5 minutes
                    Thread.sleep(2000);
                    String token = pc.checkPin(pin.id);
                    if (token == null) continue;
                    ui.post(() -> msg.setText("Linked. Finding your Plex server…"));
                    String[] server = pc.findServer(token);
                    prefs.setPlexUrl(server[0]);
                    prefs.setPlexToken(server[1]);
                    ui.post(() -> {
                        dlg.dismiss();
                        PlexStore.get().refresh(this);
                        Toast.makeText(this, "Plex connected", Toast.LENGTH_SHORT).show();
                        render();
                    });
                    return;
                }
            } catch (Exception e) {
                ui.post(() -> msg.setText("Couldn't link Plex: " + e.getMessage()));
            }
        });
    }

    private void askPlexUrl() {
        EditText in = new EditText(this);
        in.setHint("e.g. http://192.168.1.20:32400");
        in.setText(prefs.plexUrl());
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Plex server address")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setPlexUrl(in.getText().toString());
                    PlexStore.get().refresh(this);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askPin() {
        EditText in = new EditText(this);
        in.setHint("4+ digits, blank to turn off");
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Launcher settings PIN")
                .setMessage("Guards this screen only. Your restricted-profile PIN is separate and unchanged.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String s = in.getText().toString().trim();
                    if (!s.isEmpty() && s.length() < 4) {
                        Toast.makeText(this, "Use at least 4 digits", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.setPin(s);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String layoutName(String l) {
        switch (l) {
            case Prefs.LAYOUT_WALL: return "B · Channel Wall";
            case Prefs.LAYOUT_SIMPLE: return "C · Simple";
            default: return "A · Guide";
        }
    }

    private static String playerName(String p) {
        switch (p) {
            case Prefs.PLAYER_EXTERNAL: return "Another video app (VLC, Kodi…)";
            case Prefs.PLAYER_HDHR_APP: return "HDHomeRun app (opens its guide)";
            default: return "Built-in player (fastest)";
        }
    }
}
