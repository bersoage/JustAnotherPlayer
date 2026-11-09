package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer APK in the "beta" release on GitHub, downloads it and opens the system
 * installer. Never installs silently: Android always asks for confirmation.
 */
final class UpdateChecker {

    private static final String API =
            "https://api.github.com/repos/bersoage/JustAnotherPlayer/releases/tags/beta";
    private static final Pattern NAME = Pattern.compile("JustAnotherPlayer-beta(\\d+)\\.apk");
    private static final String PREFS = "audio_adjust";
    private static final String KEY_LAST = "update_last_check";
    private static final long INTERVAL = 6L * 60 * 60 * 1000;
    private static boolean checkedThisProcess = false;

    private UpdateChecker() {
    }

    /** Automatic check: once per process and at most every 6 hours. Silent when nothing new. */
    static void checkAuto(Activity activity) {
        if (checkedThisProcess) return;
        checkedThisProcess = true;
        final SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (now - p.getLong(KEY_LAST, 0) < INTERVAL) return;
        p.edit().putLong(KEY_LAST, now).apply();
        check(activity, false);
    }

    static void check(Activity activity, boolean manual) {
        new Thread(() -> {
            try {
                final int local = localNumber(activity);
                final HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "JustAnotherPlayer");
                final String body = readAll(c.getInputStream());
                final JSONArray assets = new JSONObject(body).getJSONArray("assets");
                int remote = -1;
                String url = null;
                for (int i = 0; i < assets.length(); i++) {
                    final JSONObject a = assets.getJSONObject(i);
                    final Matcher m = NAME.matcher(a.getString("name"));
                    if (m.matches()) {
                        final int n = Integer.parseInt(m.group(1));
                        if (n > remote) {
                            remote = n;
                            url = a.getString("browser_download_url");
                        }
                    }
                }
                final int r = remote;
                final String u = url;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    if (u != null && r > local) {
                        askToUpdate(activity, r, local, u);
                    } else if (manual) {
                        Toast.makeText(activity, activity.getString(R.string.update_latest, "beta " + local),
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (manual) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.update_error,
                            Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private static int localNumber(Context context) {
        try {
            final String v = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return Integer.parseInt(v.replaceAll("\\D", ""));
        } catch (Exception e) {
            return Integer.MAX_VALUE; // unknown version: never offer
        }
    }

    private static String readAll(InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static void askToUpdate(Activity activity, int remote, int local, String url) {
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.update_available, "beta " + remote, "beta " + local))
                .setPositiveButton(R.string.update_now, (d, w) -> download(activity, url))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void download(Activity activity, String url) {
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            try {
                final File dir = new File(activity.getCacheDir(), "updates");
                dir.mkdirs();
                final File file = new File(dir, "update.apk");
                final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "JustAnotherPlayer");
                final long total = c.getContentLengthLong();
                final InputStream in = c.getInputStream();
                final FileOutputStream out = new FileOutputStream(file);
                final byte[] buf = new byte[65536];
                long done = 0;
                int last = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            activity.runOnUiThread(() -> progress.setMessage(
                                    activity.getString(R.string.update_downloading, pct)));
                        }
                    }
                }
                out.close();
                in.close();
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    install(activity, file);
                });
            } catch (Exception e) {
                e.printStackTrace();
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, R.string.update_error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static void install(Activity activity, File file) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, R.string.update_allow, Toast.LENGTH_LONG).show();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            final Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updates", file);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(activity, R.string.update_error, Toast.LENGTH_LONG).show();
        }
    }
}
