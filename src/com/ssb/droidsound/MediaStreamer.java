package com.ssb.droidsound;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import com.ssb.droidsound.plugins.DroidSoundPlugin;
import com.ssb.droidsound.utils.ID3Tag;
import com.ssb.droidsound.utils.StreamingHttpConnection;

import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.util.Log;

public class MediaStreamer implements Runnable {
	private static final String TAG = MediaStreamer.class.getSimpleName();
	
	private static class MetaString {
		public MetaString(int m, String t) {
			msec = m;
			text = t;
		}
		public int msec;
		public String text;
	};
	
	
	//public volatile int socketPort;
	
	private volatile boolean started;
	private volatile boolean prepared;
	private volatile boolean loaded;
	
	private volatile int mplayerPos;

	
	//private String httpName;
	private List<MetaString> metaStrings = new ArrayList<MetaString>();
	
	private List<String> httpNames = new ArrayList<String>();

	private MediaPlayer mediaPlayer;
	
	//private boolean newMeta;


	//private int frameSize;

	private int bitRate;
	private int freq;

	private long usec;

	private long nextFramePos;
	//private String streamTitle;

	private volatile boolean hasQuit;
	private volatile boolean doQuit;

	private long last_usec;

	private int lastPos = 0;

	private String icyDesc;
	private String icyGenre;
	private String icyName;
	private String icyUrl;
	private String icyBitrate;

	//private ServerSocket serverSocket;
	//private Socket socket;

	private LocalMPConnection localMPConnection;
	private byte[] metaArray;
	private int metaPos;
	private int metaSize;
	private int metaCounter;
	private int metaInterval;
	private byte[] frameHeader;
	private long totalBytes;
	private int frameHeaderBits;
	
	private byte[] tagBuffer;
	private int tagFilled;
	private int tagSize;
	
	private ID3Tag id3 = new ID3Tag();
	private String songComposer;
	private String songTitle;
	private boolean gotID3;
	private int songLength = -1;
	private long contentLength;
	private int avgBitrate;
	private int extraSize;
	private boolean fileMode;
	private boolean bufferEnded;
	private String songAlbum;
	private String songTrack;
	private String songGenre;
	private String songComment;
	private String songCopyright;
	private long totalFrameBytes;
	
	private static final int bitRateTab[] = new int [] {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0};
	
	public MediaStreamer(String http, MediaPlayer mp, boolean fileMode) {
		//httpName = http;
		this.fileMode = fileMode;
		mediaPlayer = mp;
		httpNames.add(http);			
		//socketPort = -1;
	}
	
	public MediaStreamer(List<String> https, MediaPlayer mp, boolean fileMode) {
		
		this.fileMode = fileMode;
		mediaPlayer = mp;
		for(String s : https)
			httpNames.add(s);			
		//socketPort = -1;
	}
	
/*
V/MediaStreamer(12369): content-type: audio/mpeg
V/MediaStreamer(12369): icy-br: 128
V/MediaStreamer(12369): ice-audio-info: ice-samplerate=44100;ice-bitrate=128;ice-channels=2
V/MediaStreamer(12369): icy-br: 128
V/MediaStreamer(12369): icy-description: Watching the scene...
V/MediaStreamer(12369): icy-genre: Demoscene Amiga C64 Various Misc
V/MediaStreamer(12369): icy-name: SceneSat Radio
V/MediaStreamer(12369): icy-pub: 1
V/MediaStreamer(12369): icy-url: http://SceneSat.com/
V/MediaStreamer(12369): server: Icecast 2.3.2
V/MediaStreamer(12369): cache-control: no-cache
V/MediaStreamer(12369): icy-metaint: 16000
*/
	
	public void addSource(String hname) {
		httpNames.add(hname);
	}
	
	void httpStream() throws IOException {

		localMPConnection = null;
		
		for(int httpNo=0; httpNo < httpNames.size(); httpNo++) {

			boolean parseMp3 = false;
			boolean doRetry = false;

			String httpName = httpNames.get(httpNo);
			URL url = new URL(httpName);
	
			Log.v(TAG, "Opening URL " + httpName);
				
			StreamingHttpConnection httpConn = new StreamingHttpConnection(url);
			/*URLConnection conn = url.openConnection();
			if (!(conn instanceof HttpURLConnection))
				throw new IOException("Not a HTTP connection");
			HttpURLConnection httpConn = (HttpURLConnection) conn; */
			
			httpConn.setAllowUserInteraction(false);
			httpConn.setInstanceFollowRedirects(true);
			httpConn.setRequestMethod("GET");
			httpConn.addRequestProperty("Icy-MetaData", "1");
	
			Log.v(TAG, "Connecting");	
			httpConn.connect();
	
			int response = httpConn.getResponseCode();			
			//Log.v(TAG, String.format("RESPONSE %d %s", response, httpConn.getResponseMessage()));
			
			if (response == HttpURLConnection.HTTP_OK) {
				Log.v(TAG, "HTTP connected");
				
				metaInterval = -1;
								
				icyDesc = httpConn.getHeaderField("icy-description");
				icyGenre = httpConn.getHeaderField("icy-genre");
				icyName = httpConn.getHeaderField("icy-name");
				icyUrl = httpConn.getHeaderField("icy-url");
				icyBitrate = httpConn.getHeaderField("icy-br");
				
				String contentType = httpConn.getHeaderField("content-type");
				String cl = httpConn.getHeaderField("content-length");
				contentLength = -1;
				try {
					contentLength = Integer.parseInt(cl);
				} catch (NumberFormatException e) {
				}
				
				if(contentType.trim().startsWith("audio/mp"))
					parseMp3 = true;
				
				String icy = httpConn.getHeaderField("icy-metaint");
				if(icy != null) {
					metaInterval = Integer.parseInt(icy);
				}

				Log.v(TAG, String.format("META INTERVAL %d", metaInterval));
				
				if(localMPConnection == null) {
					localMPConnection = new LocalMPConnection();
					localMPConnection.setContentType(contentType);
					localMPConnection.accept();
				}

				int size;
				frameHeader = new byte[4];
				byte[] buffer = new byte[128*1024];
				
				//tagBuffer = new byte[32768];
				tagFilled = 0;
				tagSize = 0;

				metaArray = new byte[4092];
				metaPos = 0;
				metaSize = -1;				
				metaCounter = 0;
				
				totalBytes = 0L;
				totalFrameBytes = 0;
				frameHeaderBits = 0;
				boolean firstRead = true;

				last_usec = usec = 0L;
				nextFramePos = -1;
				hasQuit = false;
				extraSize = 0;
				
				mplayerPos = 0;
				bufferEnded = false;
				
				InputStream in = httpConn.getInputStream();

				if(!parseMp3)
					usec = -1000;

				while (!doQuit) {
					int rem = buffer.length;
					rem = updateMeta(in, rem);

					if(rem > 0) {
						try {
							size = in.read(buffer, 0, rem);
						} catch (IOException e) {
							Log.v(TAG, "####### LOST CONNECTION");
							if(!fileMode)
								httpNo--;
							doRetry = true;
							break;
						}	
						
						if(size == -1) {
							Log.v(TAG, "####### End of buffer");
							bufferEnded = true;
							if(!fileMode) {
								httpNo--;
								doRetry = true;
								break;
							} else {
								if(httpNo+1 < httpNames.size()) {
									doRetry = true;
									break;
								}
								try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
									doQuit = true;
								}
								continue;
							}							
						}
						
						if(firstRead) {
							Log.v(TAG, String.format("### READ: %02x %02x %02x %02x", buffer[0], buffer[1], buffer[2], buffer[3]));
							firstRead = false;
						}
						
						if(parseMp3) {
							parseMP3Frames(buffer, size);
						}
						
						//Log.v(TAG, String.format("####### %d", totalBytes));
						
						metaCounter += size;						
						totalBytes += size;

						if(tagSize == 0 || tagFilled >= tagSize) {						
							localMPConnection.write(buffer, 0, size);
						}
						
						if(metaCounter == metaInterval)
							Log.v(TAG, "META TIME");
						
					} else
						Log.v(TAG, "IN META!");
					
					if(parseMp3) {
						if(usec - last_usec > 1000000) {
							Log.v(TAG, String.format("%%%%%%%%%% QUEUE POS %d msec", (int)(usec / 1000)));
							last_usec = usec;														
							int sl = (int) ((usec/1000) * (contentLength-extraSize) /  (totalFrameBytes));
							Log.v(TAG, String.format("%d seconds in %dKB out of %dKB = %d seconds total", usec/1000000, totalBytes/1024, contentLength/1024, sl/1000));
							//if(sl/1000 != songLength/1000) {
								songLength = sl;
								gotID3 = true;
							//}
						}
						

					}
					
				}
				
				if(!doRetry) {
					localMPConnection.close();
					localMPConnection = null;
				}
				httpConn.disconnect();
	
			} else {
				Log.v(TAG, String.format("Connection failed: %d", response));
				continue;
			}
			
			if(doRetry)
				continue;
			
			break;
		}
		hasQuit = true;		
		Log.v(TAG, "THREAD ENDING");
		doQuit = false;			
	}
	
	private void parseMP3Frames(byte [] buffer, int size) {

		if(tagSize > 0) {
			int l = size;
			Log.v(TAG, String.format("%d %d %d", size, tagSize, tagFilled));
			if(l > (tagSize - tagFilled))
				l = tagSize - tagFilled;
			if(l > 0) {
				System.arraycopy(buffer, 0, tagBuffer, tagFilled, l);
				tagFilled += l;
			}
			if(tagFilled >= tagSize) {
				extraSize += tagFilled;
				Log.v(TAG, String.format(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Tag is %d", tagFilled));
				id3.parseTag(tagBuffer, 0, tagFilled);
				songComposer = id3.getStringInfo(DroidSoundPlugin.INFO_AUTHOR);
				songTitle = id3.getStringInfo(DroidSoundPlugin.INFO_TITLE);

				songAlbum = id3.getStringInfo(ID3Tag.ID3INFO_ALBUM);
				songTrack = id3.getStringInfo(ID3Tag.ID3INFO_TRACK);
				songGenre = id3.getStringInfo(ID3Tag.ID3INFO_GENRE);
				songComment = id3.getStringInfo(ID3Tag.ID3INFO_COMMENT);
				songCopyright = id3.getStringInfo(DroidSoundPlugin.INFO_COPYRIGHT);

				
				
				//songLength  = id3.getIntInfo(DroidSoundPlugin.INFO_LENGTH);
				Log.v(TAG, String.format(">>>>>>>>>>>>>>>>> ID3:  %s by %s", songComposer, songComposer));
				tagSize = tagFilled = 0;
				tagBuffer = null;
				gotID3 = true;
			} else
				return;
		}
		
		
		if(nextFramePos < 0) {
			for(int i=0; i<size-3; i++) {
				if(buffer[i] == -1 && (buffer[i+1] & 0xfe) == 0xfa  && (buffer[i+2] & 0xf0) != 0xf0) {							
					Log.v(TAG, String.format("Synced at %d", i));							
					nextFramePos = i+totalBytes;
					break;									
				} else if(buffer[i] == 'I' && buffer[i+1] == 'D'  && buffer[i+2] == '3') {
					Log.v(TAG, "Found ID3-header");
					int len = id3.checkForTag(buffer, i, size-i);
					Log.v(TAG, String.format("Check for tag says %d", len));
					if(len > 0) {
						tagSize = len;
						
						tagBuffer = new byte [tagSize];
						
						len = size-i;
						if(tagSize < len) len = tagSize;
						System.arraycopy(buffer, i, tagBuffer, 0, len);
						tagFilled = len;
						return;
					}					
				}
			}
		}

		while(nextFramePos >= 0) {
			int o = (int) (nextFramePos - totalBytes);
			//Log.v(TAG, String.format("NFP %d, TOTAL %d, HF %x", nextFramePos, total, hf));								
			//if(o >=0 && o < size-3) {
			//	Log.v(TAG, String.format("%d: Expect frame: %02x %02x %02x %02x", nextFramePos, buffer[o], buffer[o+1], buffer[o+2], buffer[o+3]));
			//}

			if(o >=0 && o < size) {
				frameHeader[0] = buffer[o];
				frameHeaderBits |= 1;
			}
			o++;
			if(o >=0 && o < size) {
				frameHeader[1] = buffer[o];
				frameHeaderBits |= 2;
			}
			o++;
			if(o >=0 && o < size) {
				frameHeader[2] = buffer[o];
				frameHeaderBits |= 4;
			}
			o++;
			if(o >=0 && o < size) {
				frameHeader[3] = buffer[o];
				frameHeaderBits |= 8;
			}
			
			if(frameHeaderBits == 0xf) {
				//Log.v(TAG, String.format("Got frame: %02x %02x %02x %02x", frameHeader[0], frameHeader[1], frameHeader[2], frameHeader[3]));
				
				bitRate = bitRateTab[(frameHeader[2]>>4) & 0xf] * 1000;
				
				if(bitRate != 0) { 							
					freq = 44100;
					int ff = frameHeader[2] & 0xc;
					
					if(ff != 0xc) {
						
						if(ff == 4) freq = 48000;
						else if(ff == 8) freq = 32000;
						
						int frameSize = 144 * bitRate / freq;

						if((frameHeader[2] & 0x02) == 2)
							frameSize++;

						usec += frameSize * 1000 / (bitRate/8000);
						
						
						totalFrameBytes += frameSize;
						

						nextFramePos += frameSize;
						
						if(avgBitrate == 0)
							avgBitrate = bitRate;
						else
							avgBitrate = (avgBitrate * 15 + bitRate) / 16;
						
						//extraSize += 4;
						
						//Log.v(TAG, String.format("BITRATE %d, FREQ %d -> FRAMESIZE %d, nextFramePos = %d", bitRate, freq, frameSize, nextFramePos));
					} else nextFramePos = -1;
				} else nextFramePos = -1;
				frameHeaderBits = 0;
				if(nextFramePos == -1) {
					//Log.v(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
					//Log.v(TAG, "!!!!!!!!!!!!!!!!!!!!!!!! LOST SYNC !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
					Log.v(TAG, "!!!!!!!!!!!!!!!!!!!!!!!! LOST SYNC !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
					//Log.v(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
				}
			} else
				break;
		}
		
		//if(contentLength > 0 && avgBitrate > 0) {
		//	//Log.v(TAG, String.format("BITRATE %d, FREQ %d -> FRAMESIZE %d, nextFramePos = %d", bitRate, freq, frameSize, nextFramePos));
		//	songLength = contentLength / (avgBitrate/8000);
		//	Log.v(TAG, String.format("BITRATE %d SONG LENGTH: %d", avgBitrate, songLength));
		//	gotID3 = true;
		//}

	}

	private int updateMeta(InputStream in, int remaining) throws IOException {
		if(metaCounter == metaInterval) {
			
			int rem;
			if(metaSize == -1)
				rem = 1;
			else
				rem = metaSize - metaPos;
			int size = in.read(metaArray, metaPos, rem);
			metaPos += size;

			Log.v(TAG, String.format("Read %d META bytes", size));

			
			if(metaPos == metaSize) {
				String meta =  new String(metaArray, 1, metaSize-1);
				Log.v(TAG, "META DONE: " + meta);
				
				String split [] = meta.split(";");
				
				for(String data : split) {							
					int startPos = data.indexOf("StreamTitle='");
					if(startPos >= 0) {
						startPos += 13;
						int endPos = data.lastIndexOf('\'');
						if(endPos > startPos) {
							//streamTitle = data.substring(startPos, endPos);
							metaStrings.add(new MetaString((int) (usec/1000), data.substring(startPos, endPos)));
							//newMeta = true;
						}
					}
				}
				
				metaCounter = 0;
				metaPos = 0;
				metaSize = -1;
				return 0;
			}
			
			if(metaPos > 0) {
				metaSize = (metaArray[0] * 16) + 1;
				Log.v(TAG, String.format("META SIZE %d", metaSize-1));
				if(metaSize == 1) {
					metaCounter = 0;
					metaPos = 0;
					metaSize = -1;
				}
				return 0;
			}						
		} else {
			if(metaInterval > 0) {
				int toNextMeta = metaInterval - metaCounter;
				if(toNextMeta < remaining) remaining = toNextMeta;
			}						
			//Log.v(TAG, String.format("TO NEXT META %d", toNextMeta)); 
		}
		return remaining;
			
	}

	public int getLatency() {
		if(usec < 0) return -1;
		return (int) (usec/1000 - (mediaPlayer == null ? 0 : mediaPlayer.getCurrentPosition()));
	}
	
	public int update() {
		
		if(hasQuit)
			return -1;
		
		if(localMPConnection == null || !localMPConnection.isListening())
			return 0;
				
		if(!loaded) {
			try {
				localMPConnection.connect(mediaPlayer);
				//mediaPlayer.setDataSource(String.format("http://127.0.0.1:%d/", socketPort));
				loaded = true;
				Log.v(TAG, ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> PREPARING ");
				mediaPlayer.setOnPreparedListener(new OnPreparedListener() {
					@Override
					public void onPrepared(MediaPlayer mp) {
						prepared = true;
					}
				});
				mediaPlayer.prepareAsync();
			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalStateException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if(!prepared) {
			Log.v(TAG, "-------- Not prepared yet");
			return 0;
		}
		
		if(!started) {			
			Log.v(TAG, ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> STARTING ");
			mediaPlayer.start();
			started = true;
		}
		
		int rc = mediaPlayer.getCurrentPosition();

		if(rc > 1000 && rc == lastPos) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
			rc = mediaPlayer.getCurrentPosition();
			if(rc == lastPos)
				return -1;
		}
		
		lastPos = rc;
		
		//if(rc - lastPos > 1000) {
		//	Log.v(TAG, String.format("########## READ POS %d msec",rc));
		//	lastPos  = rc;
		//}
		return rc;
	}	

	@Override
	public void run() {
		try {
			httpStream();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void quit() {
		doQuit = true;
	}
	
	public boolean hasQuit() {
		return hasQuit;
	}

	public boolean checkNewMeta() {
		
		if(metaStrings.size() > 0 && mediaPlayer != null) {
			MetaString ms = metaStrings.get(0);
			if(ms.msec <= mediaPlayer.getCurrentPosition()) {
				
				songTitle = ms.text;
				int split = songTitle.indexOf(" - ");
				if(split > 0) {
					songComposer = songTitle.substring(0, split);
					songTitle = songTitle.substring(split + 3);
				}
				metaStrings.remove(0);
				return true;
			}
		} else if(gotID3) {
			gotID3 = false;
			return true;
		}
		return false;
		
	}
	

	public String getStringInfo(int what) {
		switch(what) {
		case DroidSoundPlugin.INFO_AUTHOR:
			return songComposer;
		case DroidSoundPlugin.INFO_TITLE:
			return songTitle;
		}
		return null;
	}

	public int getIntInfo(int what) {
		switch(what) {
		case DroidSoundPlugin.INFO_LENGTH:
			return songLength;
		}
		return 0;
	}

	public String[] getDetailedInfo() {
		
		List<String> info = new ArrayList<String>();
		if(songAlbum != null && songAlbum.length() > 0) {
			info.add("Album");
			info.add(songAlbum);
		}
		if(songTrack != null && songTrack.length() > 0) {
			info.add("Track");
			info.add(songTrack);
		}
		if(songGenre != null && songGenre.length() > 0) {
			info.add("Genre");
			info.add(songGenre);
		}
		if(songComment != null && songComment.length() > 0) {
			info.add("Comment");
			info.add(songComment);
		}
		if(icyName != null) {
			info.add("Name");
			info.add(icyName);
		}
		if(icyDesc != null) {
			info.add("Description");
			info.add(icyDesc);
		}
		if(icyUrl != null) {
			info.add("URL");
			info.add(icyUrl);
		}
		if(icyGenre != null) {
			info.add("Genre");
			info.add(icyGenre);
		}
		if(icyBitrate != null) {
			info.add("Bitrate");
			info.add(icyBitrate);
		}
		String[] strArray = new String[info.size()];
		info.toArray(strArray);
		return strArray;
	}
	
	public boolean isBufferDone() {
		return bufferEnded;
	}

}


