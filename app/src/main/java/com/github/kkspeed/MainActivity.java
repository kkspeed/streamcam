package com.github.kkspeed;

import android.Manifest;
import android.Manifest.permission;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.view.WindowManager.LayoutParams;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.TextureView;

import java.util.Map;
import net.majorkernelpanic.streaming.video.H264VideoStream2;

public class MainActivity extends AppCompatActivity {
  private static final String TAG = "StreamCam";

  private TextureView mTextureView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
    Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
    editor.putString(net.majorkernelpanic.streaming.rtsp.RtspServer.KEY_PORT, String.valueOf(1234));
    editor.apply();

    H264VideoStream2.sContext = this;

    requirePermission(permission.CAMERA, g -> {
        startService(new Intent(this, net.majorkernelpanic.streaming.rtsp.RtspServer.class));
    });
  }

  private void requirePermission(final String permission, ActivityResultCallback<Map<String, Boolean>> next) {
    ActivityResultLauncher<String[]> requestPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), next);
    if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
      next.onActivityResult(null);
    } else {
      requestPermissionLauncher.launch(
          new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE});
    }
  }
}