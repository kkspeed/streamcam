package com.github.kkspeed;

import android.util.Log;
import java.io.DataOutputStream;
import java.net.Socket;

public class MjpegSocket implements Runnable {
  private final Socket socket;

  public MjpegSocket(Socket socket) {
    this.socket = socket;
  }

  @Override
  public void run() {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

    try {
      DataOutputStream stream = new DataOutputStream(socket.getOutputStream());

      String boundary = "CameraServeDataBoundary";
      stream.write(("HTTP/1.0 200 OK\r\n" +
          "Server: CameraServe\r\n" +
          "Connection: close\r\n" +
          "Max-Age: 0\r\n" +
          "Expires: 0\r\n" +
          "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
          "Pragma: no-cache\r\n" +
          "Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n" +
          "\r\n" +
          "--" + boundary + "\r\n").getBytes());
      stream.flush();

      while (true) {
        byte[] frame = MainActivity.getJpegFrame();

        stream.write(("Content-type: image/jpeg\r\n" +
            "Content-Length: " + frame.length + "\r\n" +
            "\r\n").getBytes());
        stream.write(frame);
        stream.write(("\r\n--" + boundary + "\r\n").getBytes());

        Thread.sleep(12);
        Log.e("MjpegSocket", "Writing frame: " + System.currentTimeMillis());
      }
    } catch (Exception e) {
      Log.e("MjpegSocket", "Exception: ", e);
    }
  }
}
