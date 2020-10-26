package com.github.kkspeed;

import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspServer;

public class AlternativeActivity extends AppCompatActivity {
  private final static String TAG = "AlternativeActivity";

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.alternative_layout);
    Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
    editor.putString(RtspServer.KEY_PORT, String.valueOf(1234));
    editor.apply();
    SurfaceView surfaceView = findViewById(R.id.alternative_surface_view);
    SessionBuilder.getInstance()
        .setSurfaceView(surfaceView)
        .setContext(getApplicationContext());
  }

  @Override
  protected void onStart() {
    super.onStart();
    startService(new Intent(this, RtspServer.class));
  }

  @Override
  protected void onStop() {
    super.onStop();
    stopService(new Intent(this, RtspServer.class));
  }
}
