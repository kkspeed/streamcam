/*
 * Copyright (C) 2011-2015 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.majorkernelpanic.streaming.rtsp;

import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_AAC;
import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_AMRNB;
import static net.majorkernelpanic.streaming.SessionBuilder.AUDIO_NONE;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_H263;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_H264;
import static net.majorkernelpanic.streaming.SessionBuilder.VIDEO_NONE;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.Set;
import net.majorkernelpanic.streaming.MediaStream;
import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.Session2;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.video.VideoQuality;

import android.content.ContentValues;
import android.hardware.Camera.CameraInfo;

/**
 * This class parses URIs received by the RTSP server and configures a Session accordingly.
 */
public class UriParser {

	public final static String TAG = "UriParser";

	/**
	 * Configures a Session according to the given URI.
	 * Here are some examples of URIs that can be used to configure a Session:
	 * <ul><li>rtsp://xxx.xxx.xxx.xxx:8086?h264&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h263&camera=front&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h264=200-20-320-240</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?aac</li></ul>
	 * @param uri The URI
	 * @throws IllegalStateException
	 * @throws IOException
	 * @return A Session configured according to the URI
	 */
	public static Session2 parse(String uri) throws IllegalStateException, IOException {
		Session2 session = new Session2();

		String query = URI.create(uri).getQuery();
		String[] queryParams = query == null ? new String[0] : query.split("&");
		ContentValues params = new ContentValues();
		for(String param:queryParams)
		{
			String[] keyValue = param.split("=");
			String value = "";
			try {
				value = keyValue[1];
			}catch(ArrayIndexOutOfBoundsException e){}

			params.put(
					URLEncoder.encode(keyValue[0], "UTF-8"), // Name
					URLEncoder.encode(value, "UTF-8")  // Value
			);

		}

		if (params.size() > 0) {
			Set<String> paramKeys=params.keySet();
			// Those parameters must be parsed first or else they won't necessarily be taken into account
			for(String paramName: paramKeys) {
				String paramValue = params.getAsString(paramName);
				// UNICAST -> the client can use this to specify where he wants the stream to be sent
				if (paramName.equalsIgnoreCase("unicast")) {
					if (paramValue!=null) {
						session.setDestination(paramValue);
					}
				}
				// H.264
				else if (paramName.equalsIgnoreCase("h264")) {
					VideoQuality quality = VideoQuality.parseQuality(paramValue);
					session.setVideoQuality(quality);
				}
			}
		}

		return session;
	}

}
