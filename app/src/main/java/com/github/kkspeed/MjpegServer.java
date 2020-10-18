package com.github.kkspeed;

import android.util.Log;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class MjpegServer implements Runnable {
  @Override
  public void run() {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

    ServerSocket server;
    try {
      server = new ServerSocket(8000);
      server.setSoTimeout(5000);
    } catch (IOException e) {
      e.printStackTrace();
      return;
    }

    while (true) {
      try {
        Socket socket = server.accept();
        if (socket.getInetAddress().isSiteLocalAddress()) {
          MjpegSocket mjpegSocket = new MjpegSocket(socket);
          new Thread(mjpegSocket).start();
        } else {
          socket.close();
        }
      } catch (SocketTimeoutException ste) {
        // continue silently
      } catch (IOException ioe) {
        Log.e("MjpegServer", "IOException: ", ioe);
      }
    }
  }
}
