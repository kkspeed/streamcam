package net.majorkernelpanic.streaming;

import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import net.majorkernelpanic.streaming.video.H264VideoStream2;
import net.majorkernelpanic.streaming.video.VideoQuality;

public class Session2 {
  private VideoQuality mVideoQuality = new VideoQuality(1920, 1080, 30,
      10000000);
  private String mDestination;
  private String mOrigin;

  private final long mTimestamp;

  private H264VideoStream2 mVideoStream = new H264VideoStream2();

  public Session2() {
    long uptime = System.currentTimeMillis();

    mTimestamp = (uptime/1000)<<32 & (((uptime-((uptime/1000)*1000))>>32)/1000); // NTP timestamp
    mOrigin = "127.0.0.1";
  }

  /**
   * The destination address for all the streams of the session. <br />
   * Changes will be taken into account the next time you start the session.
   * @param destination The destination address
   */
  public void setDestination(String destination) {
    mDestination = destination;
  }
  public String getDestination() { return mDestination; }

  public void setOrigin(String origin) { mOrigin = origin; }

  public boolean isStreaming() {
    return mVideoStream != null && mVideoStream.isStreaming();
  }

  public void syncConfigure() {}

  public void syncStart(int trackId) {
    try {
      InetAddress destination =  InetAddress.getByName(mDestination);
      mVideoStream.setDestinationAddress(destination);
      mVideoStream.start();
    } catch (IOException e) {
      Log.e("Session2", "Failed to start: ", e);
    }
  }
  public void stop() {}
  public void syncStop() {}

  public void release() {}

  public long getBitrate() {
    return mVideoStream.getBitrate();
  }

  public String getSessionDescription() throws IllegalStateException {
    if (mDestination==null) {
      throw new IllegalStateException("setDestination() has not been called !");
    }
    return "v=0\r\n"
        // TODO: Add IPV6 support
        + "o=- " + mTimestamp + " " + mTimestamp + " IN IP4 " + mOrigin + "\r\n"
        + "s=Unnamed\r\n"
        + "i=N/A\r\n"
        + "c=IN IP4 " + mDestination + "\r\n"
        // t=0 0 means the session is permanent (we don't know when it will stop)
        + "t=0 0\r\n"
        + "a=recvonly\r\n"
        + mVideoStream.getSessionDescription()
        + "a=control:trackID=" + 1 + "\r\n";
  }

  public boolean trackExists(int trackId) {
    // We care about live streaming... so track always exists :).
    return true;
  }

  public Stream getTrack(int trackId) {
    return mVideoStream;
  }

  public void setVideoQuality(final VideoQuality quality) {
    mVideoQuality = quality;
  }
}
