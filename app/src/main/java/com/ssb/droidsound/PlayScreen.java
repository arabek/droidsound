package com.ssb.droidsound;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.floreysoft.jmte.Engine;
import com.ssb.droidsound.service.PlayerService;
import com.ssb.droidsound.service.SongMeta;
import com.ssb.droidsound.utils.Log;
import com.ssb.droidsound.utils.Utils;

@SuppressLint("SetJavaScriptEnabled")
public class PlayScreen {
	private static final String TAG = PlayScreen.class.getSimpleName();

	//private static String[] repnames = { "CONT", "HOLD", "REPT", "CS", "RS" };

	private ViewGroup parent;
	private PlayerServiceConnection player;
	private PlayState state;

	private ImageButton playButton;
	private ImageButton pauseButton;
	private ImageButton backButton;
	private ImageButton fwdButton;
	private ImageButton stopButton;

	private TextView songSecondsText;
	private TextView songTotalText;
	private TextView songSubtunesText;
	private SeekBar songSeeker;

	private TextView shuffleText;
	private TextView repeatText;
	private TextView plusText;

	private WebView infoText;


	private Activity activity;

	private int oldSeconds = -1;
	
	private Map<String, Object> variables = new HashMap<String, Object>();

	private Engine engine;

	private String defTemplate;
	private String streamTemplate;

	private String empty = "<html><body style=\"background-color: #%06x;\"></body></body>";

	private File themeDir;
	private File templateDir;
	//private File dataDir;

	private Map<String, String> templates = new HashMap<String, String>();

	private static FileObserver observer = null;

	private String currentPluginName;

	private JSInterface jsInterface;

	private File artworkFile;
	private int aCounter = 0;

	private View controls;

	private TextView controlSeparator;

	private File tempDir;

	private int backgroundColor;



	//private FileObserver observer2;
	
	private static class MyHandler extends Handler {

		private WeakReference<PlayScreen> psRef;

		public MyHandler(PlayScreen ps) {
			psRef = new WeakReference<PlayScreen>(ps);
		}

		@Override
		public void handleMessage(Message msg) {
			PlayScreen ps = psRef.get();
			ps.updateInfo();
		}
	};
	
	/*private static class SongInfo {
		public String title;
		public String composer;
	}; */
	
	@SuppressWarnings("unused")
	private static class JSInterface {
		private Map<String, Object> map;
		public Map<String, String> listenMap;

		public JSInterface(Map<String, Object> map) {
			this.map = map;
			listenMap = new HashMap<String, String>();
		}
		
		public String getString(String what) {
			return (String) map.get(what);
		}
		
		public int listenTo(String what, String function) {
			listenMap.put(what, function);
			return 0;
		}
		
	}
	
//	private SongInfo songInfo = new SongInfo();
	
	public View getView() {
		return parent;
	}

	public PlayScreen(PlayState st, PlayerServiceConnection plr, Activity act) {
		player = plr;
		state = st;
		activity = act;
		
		LayoutInflater inflater = (LayoutInflater)activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		parent = (ViewGroup) inflater.inflate(R.layout.info, null);
	
		controls = parent.findViewById(R.id.controls);
		
		stopButton = (ImageButton) parent.findViewById(R.id.stop_button);
		playButton = (ImageButton) parent.findViewById(R.id.play_button);
		pauseButton = (ImageButton) parent.findViewById(R.id.pause_button);
		backButton = (ImageButton) parent.findViewById(R.id.back_button);
		fwdButton = (ImageButton) parent.findViewById(R.id.fwd_button);

		songSecondsText = (TextView) parent.findViewById(R.id.seconds_text);
		songTotalText = (TextView) parent.findViewById(R.id.total_text);
		songSubtunesText = (TextView) parent.findViewById(R.id.songs_text);
		songSeeker = (SeekBar) parent.findViewById(R.id.song_seek);

		shuffleText = (TextView) parent.findViewById(R.id.shuffle_text);
		repeatText = (TextView) parent.findViewById(R.id.repeat_text);
		//repeatText.setText("CONT");
		plusText = (TextView) parent.findViewById(R.id.plus_text);
		
		controlSeparator = (TextView) parent.findViewById(R.id.low_text);
		
		infoText = (WebView) parent.findViewById(R.id.web_view);		
		infoText.getSettings().setJavaScriptEnabled(true);
		jsInterface = new JSInterface(variables);		
		infoText.addJavascriptInterface(jsInterface, "info");
		//infoText.addJavascriptInterface(songInfo, "info");
		
		ThemeManager tm = ThemeManager.getInstance();
		
		if(controlSeparator != null)
			tm.manageView("control-separator", controlSeparator);
		
		tm.manageView("controls", controls);
		tm.manageView("controls.seconds", songSecondsText);
		tm.manageView("controls.total", songTotalText);
		tm.manageView("controls.subtunes", songSubtunesText);
		tm.manageView("controls.shuffle", shuffleText);
		tm.manageView("controls.repeat", repeatText);
		tm.manageView("controls.plus", plusText);
		tm.manageView("controls.stop", stopButton);
		tm.manageView("controls.play", playButton);
		tm.manageView("controls.pause", pauseButton);
		tm.manageView("controls.forward", fwdButton);
		tm.manageView("controls.back", backButton);
		tm.manageView("controls.seekbar", songSeeker);

	
		//infoText.loadData(String.format(empty, backgroundColor & 0xffffff), "text/html", "utf-8");
		//infoText.getSettings();
		//infoText.setBackgroundColor(backgroundColor);
		
		defTemplate = Utils.readAsset(activity, "templates/def.html");
		streamTemplate = Utils.readAsset(activity, "templates/stream.html");
		
		themeDir = new File(Environment.getExternalStorageDirectory(), "droidsound/theme");
		templateDir = new File(themeDir, "templates");
		tempDir = new File(Environment.getExternalStorageDirectory(), "droidsound/tmp");
		tempDir.mkdir();
		//dataDir = new File(htmlDir, "data");
		/*if(!htmlDir.exists())
			htmlDir.mkdir();
		if(!templateDir.exists()) {
			templateDir.mkdir();
			File f = new File(templateDir, "sample.html");
			try {
				FileOutputStream os = new FileOutputStream(f);
				os.write(defTemplate.getBytes());
				os.close();
			} catch (IOException e) {
				e.printStackTrace();
			}		
		}
		if(!dataDir.exists())
			dataDir.mkdir(); */
		

		final Handler handler = new MyHandler(this);
		
		if(observer != null) {
			observer.stopWatching();
			observer = null;
		}
		
		observer = new FileObserver(templateDir.getPath(), FileObserver.MODIFY | FileObserver.MOVED_TO | FileObserver.CREATE) {			
			@Override
			public void onEvent(int event, String path) {
				if(updateHtml()) {
					Message msg = handler.obtainMessage(0);
					handler.sendMessage(msg);
				}

			}
		};
		observer.startWatching();
		updateHtml();
		
		ThemeManager.getInstance().onChange(new ThemeManager.ChangeListener() {
			@Override
			public void themeChanged() {
				Message msg = handler.obtainMessage(0);
				handler.sendMessage(msg);
			}
		});
				
        WebViewClient client = new WebViewClient() {
        	/*@Override
        	public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        		Log.d(TAG, "WANT TO LOAD " + url);
        		return super.shouldInterceptRequest(view, url);
        	}*/
        	@Override
        	public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        		super.onReceivedError(view, errorCode, description, failingUrl);
        		Log.d(TAG, "Error: %d (%s)", errorCode, description);
        	}
        	
        	@Override
        	public boolean shouldOverrideUrlLoading(WebView view, String url) {
        		
        		 Uri uri = Uri.parse(url);
        		 Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        		 activity.startActivity(intent);
        		 return true;
        		
        	}
        	
        };
        infoText.setWebViewClient(client);
        
        WebChromeClient chrome = new WebChromeClient() {
        	
        	@Override
        	public boolean onJsAlert(WebView view, String url, String message, JsResult result) {        		
        		Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        		result.confirm();
        		return true;
        		
        	}        	
        };
        infoText.setWebChromeClient(chrome);
		
		shuffleText.setText(state.shuffleSongs ? "RND" : "SEQ");
		
		engine = new Engine();

		stopButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				player.stop();
			}
		});
		playButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				player.playPause(true);
			}
		});

		pauseButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				player.playPause(false);
			}
		});

		backButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if(state.subTune == 0 || state.subTuneCount == 0) {
					player.playPrev();
				} else {
					state.subTune -= 1;
					player.setSubSong(state.subTune);
				}
			}
		});

		backButton.setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				player.playPrev();
				return true;
			}
		});

		fwdButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d(TAG, "NEXT %d %d", state.subTune, state.subTuneCount);
				if((state.subTune + 1) < state.subTuneCount) {
					state.subTune += 1;
					player.setSubSong(state.subTune);
				} else {
					player.playNext();
				}
			}
		});

		fwdButton.setOnLongClickListener(new OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				player.playNext();
				return true;
			}
		});
		
		songSeeker.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				Log.d(TAG, "Stop tracking");
				player.seekTo(state.songLength * seekBar.getProgress() * 10);
				state.seekingSong = 5;
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				Log.d(TAG, "Start tracking");
				state.seekingSong = 5;
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if(fromUser) {
					// Log.d(TAG, "Changed %d", progress);
					int pos = state.songLength * seekBar.getProgress() / 100;
					songSecondsText.setText(String.format("%02d:%02d", pos / 60, pos % 60));
					state.seekingSong = 5;
				}
			}
		});
		
		shuffleText.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				state.shuffleSongs = !state.shuffleSongs;
				player.setOption(PlayerService.OPTION_PLAYBACK_ORDER, state.shuffleSongs ? "R" : "S");
				shuffleText.setText(state.shuffleSongs ? "RND" : "SEQ");
			}
		});

		repeatText.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// shuffleSongs = !shuffleSongs;
				state.songRepeat = !state.songRepeat;
				player.setOption(PlayerService.OPTION_REPEATSONG, Boolean.toString(state.songRepeat));
				repeatText.setText(state.songRepeat ? "HOLD" : "CONT");
			}
		});

		plusText.setOnClickListener(new OnClickListener() {
			@SuppressWarnings("deprecation")
			@Override
			public void onClick(View v) {

				if(state.songFile != null) {
					//operationFile = songFile.getFile();
					state.operationSong = new SongFile(state.songFile);
					//operationSong.setSubTune(subTune);
					
					//operationTune = subTune;
					state.operationTitle = null;
					state.operationTuneCount = state.subTuneCount;
					Log.d(TAG, "%s - %s ADD", state.songTitle != null ? state.songTitle : "null", state.subtuneTitle != null ? state.subtuneTitle : "null");
					if(state.songTitle != null && state.subtuneTitle != null) {
						// operationTitle = songTitle + " - " + subtuneTitle;
						state.operationTitle = state.subtuneTitle + " (" + state.songTitle + ")";
					}
					//operationSong.setTitle(operationTitle);
					activity.showDialog(R.string.add_to_plist);
				}
			}
		});

	}
	
	private String secToString(int sec) {
		StringBuilder sb = new StringBuilder();
		int min = sec / 60;
		sec = sec % 60;
		if(min < 10)
			sb.append('0');
		sb.append(min);
		if(sec < 10)
			sb.append(":0");
		else
			sb.append(':');
		sb.append(sec);
		return sb.toString();
	}
	
	public void update(Map<String, Object> data, boolean newsong) {
		
		//Log.d(TAG, "UPDATE:%s %d", newsong ? " NEW" : "", data.size());
		
		if(newsong || state.songDetails == null)
			state.songDetails = data;
		else
			state.songDetails.putAll(data);
		
		if(newsong) {
			state.buffering = 0;
			songSubtunesText.setText(secToString(state.buffering / 1000));	
		}
		
		if(data.containsKey(SongMeta.STATE)) {
			state.songState = (Integer)data.get(SongMeta.STATE);
			
			
			if(state.songState == 0) {
				infoText.loadData(String.format(empty, backgroundColor & 0xffffff), "text/html", "utf-8");
				state.songLength = 0;
				songTotalText.setText(secToString(state.songLength));
			}
			Log.d(TAG, "State %d", state.songState);
			
			if(state.songState == 1) {
				playButton.setVisibility(View.GONE);
				pauseButton.setVisibility(View.VISIBLE);
			} else {
				playButton.setVisibility(View.VISIBLE);
				pauseButton.setVisibility(View.GONE);
			}
		}
		if(data.containsKey(SongMeta.LENGTH)) {
			state.songLength = (Integer)data.get(SongMeta.LENGTH) / 1000;
			if(state.songLength < 0) {
				// TODO: Hide length
				state.songLength = 0;
				songTotalText.setVisibility(View.GONE);
			} else {
				songTotalText.setVisibility(View.VISIBLE);
			}
			String secs = secToString(state.songLength);
			Log.d(TAG, "Songlength " + secs);
			songTotalText.setText(secs);
		}
		
		if(data.containsKey(SongMeta.REPEAT)) {
			state.songRepeat = (Boolean)data.get(SongMeta.REPEAT);
			repeatText.setText(state.songRepeat ? "HOLD" : "CONT");
		}
		
		if(data.containsKey(SongMeta.SHUFFLE)) {
			state.shuffleSongs = (Boolean)data.get(SongMeta.SHUFFLE);
			shuffleText.setText(state.shuffleSongs ? "RND" : "SEQ");
		}
		
		if(data.containsKey(SongMeta.POSITION)) {
			int value = (Integer)data.get(SongMeta.POSITION) / 1000;
			
			if(state.seekingSong == 0) {
				if(value != oldSeconds) {
					oldSeconds = value;
					state.songPos = value;
					String t = secToString(state.songPos);					
					songSecondsText.setText(t);
					if(state.songLength > 0) {
						int percent = 100 * state.songPos / state.songLength;
						if(percent > 100) percent = 100;
						songSeeker.setProgress(percent);
					} else {
						songSeeker.setProgress(0);
					}
				}
			} else
				state.seekingSong--;
		}
		
		if(data.containsKey(SongMeta.BUFFERING)) {
			state.buffering = (Integer)data.get(SongMeta.BUFFERING);
			songSubtunesText.setText(secToString(state.buffering / 1000 ));
		}
		
		if(data.containsKey("track")) {
			String track = (String) data.get("track");
			songSubtunesText.setText("#" + track);
		}
		
		if(data.containsKey(SongMeta.TOTAL_SUBTUNES)) {
			state.subTuneCount = (Integer) data.get(SongMeta.TOTAL_SUBTUNES);
		}
	
		if(data.containsKey(SongMeta.SUBTUNE)) {
			state.subTune = (Integer) data.get(SongMeta.SUBTUNE);
			if(state.subTuneCount > 0)
				songSubtunesText.setText(String.format("[%02d/%02d]", state.subTune + 1, state.subTuneCount));
		}
	
		if(data.containsKey(SongMeta.CAN_SEEK)) {
			songSeeker.setEnabled((Boolean) data.get(SongMeta.CAN_SEEK));
		}
		
		if(data.containsKey(SongMeta.TITLE)) {
			state.songTitle = getString(data, SongMeta.TITLE);
		}
		if(data.containsKey(SongMeta.SUBTUNE_TITLE)) {
			state.subtuneTitle = getString(data, SongMeta.SUBTUNE_TITLE);
		}
		if(data.containsKey(SongMeta.SUBTUNE_COMPOSER)) {
			state.subtuneAuthor = getString(data, SongMeta.SUBTUNE_COMPOSER);
		}
		if(data.containsKey(SongMeta.COMPOSER)) {
			state.songComposer = getString(data, SongMeta.COMPOSER);
		}
		if(data.containsKey(SongMeta.TOTAL_SUBTUNES)) {
			state.subTuneCount = (Integer) data.get(SongMeta.TOTAL_SUBTUNES);
		}
		if(data.containsKey(SongMeta.SOURCE)) {
			state.songSource = (String) data.get(SongMeta.SOURCE);
		}
		if(data.containsKey("binary")) {
			int what = (Integer) data.get("binary");
			if(what >= 0) {
				byte [] bindata = (byte[]) data.get("binary_" + what);
				String ext = "";
				if(bindata[0] == -1 && bindata[1] == -40)
					ext = ".jpg";
				else if(bindata[1] == 0x50 && bindata[2] == 0x4e && bindata[2] == 0x47)
					ext = ".png";				
				//state.artWork = BitmapFactory.decodeByteArray(bindata,  0,  bindata.length);
				if(artworkFile != null)
					artworkFile.delete();
				artworkFile = new File(tempDir,  "artwork" + String.valueOf(aCounter) + ext);
				aCounter++;
				Utils.dumpFile(artworkFile, bindata);
				data.put("artwork", "file:" + artworkFile.getPath());
			}
		}
		if(newsong)
			infoText.scrollTo(0, 0);
		
		boolean doUpdate = newsong;
		for(Entry<String, Object> e : data.entrySet()) {
			String k = e.getKey();
			
			String func = jsInterface.listenMap.get(k);
			if(func != null) {
				String url = "javascript:" + func + "('" + e.getValue() +"')";
				infoText.loadUrl(url);
			}

			
			if(k == SongMeta.POSITION || k == SongMeta.BUFFERING || k == SongMeta.STATE || k == SongMeta.CAN_SEEK || k == SongMeta.LENGTH)
				continue;
			doUpdate = true;
			break;
		}
		
		if(state.songState == 0)
			return;
		
		if(doUpdate)
			updateInfo();
	}
	
	private String getString(Map<String, Object> data, String what) {
		String s = (String) data.get(what);
		if(s.equals(""))
			return null;
		return s;
	}
	
	public boolean updateHtml() {
		
		//templateDir.mkdirs();
		boolean changed = false;
		templates.clear();
		templates.put("STREAM", streamTemplate);
		templates.put("DEF", defTemplate);

		if(!templateDir.exists())
			return false;

		for(File f : templateDir.listFiles()) {
			String parts[] = f.getName().split("\\.");
			if(parts.length == 2 && parts[1].toLowerCase(Locale.ENGLISH).equals("html")) {
				String html = Utils.readFile(f);
				String what = parts[0].toUpperCase(Locale.ENGLISH);
				String oldHtml = templates.get(what);
				if(oldHtml == null || !oldHtml.equals(html)) {
					templates.put(what, html);
					changed = true;
				}
			}
		}
		return changed;
	}
	
	private String getVar(String name) {
		if(variables.containsKey(name)) {
			String rc = (String) variables.get(name);
			if(rc.equals(""))
				return null;
			return rc;
		}
		return null;
	}
		
	@SuppressWarnings("unused")
	private void updateInfo() {
		
		variables.clear();
		variables.put("THEMEDIR", "file://" + themeDir.getPath() + "/");
		if(state.songDetails != null) {
			
			StringBuilder all = new StringBuilder();
			
			for(Entry<String, Object> e : state.songDetails.entrySet()) {
				
				Object val = e.getValue();
				String key = e.getKey();
				all.append(key).append(":");
				all.append(String.valueOf(val)).append("\n");
				
				if(val instanceof String[]) {
					// Transform String array into object array with extra fields
					Object[] newArray = new Object [ ((String[])val).length ];
					
					int counter = 0;
					for(final String s : (String[])val) {						
						counter++;
						final int idx = counter;
						newArray[counter-1] = new Object() {									
							public String text = s;
							public int index = idx;
							public String index2 = String.format("%02d", idx);;
						};							
					}
					
					variables.put(e.getKey(), newArray);
				} else					
					variables.put(e.getKey(), e.getValue());
			}
			variables.put("all", all.toString());
		}
		
		String title = getVar(SongMeta.TITLE);
		String subTitle = getVar(SongMeta.SUBTUNE_TITLE);
		String subComposer = getVar(SongMeta.SUBTUNE_COMPOSER);
		String fullTitle = title;
		
		if(subTitle != null) {
			if(title != null && title.length() > 0)
				fullTitle = subTitle + " (" + title + ")";
			else
				fullTitle = subTitle;
		}
		variables.put(SongMeta.TITLE, fullTitle);
		
		if(subComposer != null) {
			variables.put(SongMeta.COMPOSER, subComposer);
		}
		
		if(variables.containsKey(SongMeta.SIZE)) {
			variables.put("sizeText", makeSize((Integer)variables.get(SongMeta.SIZE)));
		}

		if(variables.containsKey("plugin"))
			currentPluginName = ((String)variables.get("plugin")).toUpperCase(Locale.ENGLISH);
		else
			currentPluginName = "DEF";
		
		variables.put("width", infoText.getWidth());
		variables.put("height", infoText.getHeight());
		
		DisplayMetrics metrics =  activity.getResources().getDisplayMetrics();
		variables.put("dpi", metrics.xdpi);
		
		variables.put("themeCSS", ThemeManager.getInstance().getCSS());
		
		if(variables.containsKey("webpage")) {
			//jsInterface.listenMap.clear();
			String page = (String) variables.get("webpage");
			infoText.loadUrl(page);
		} else {
			
			String html = templates.get(currentPluginName);
			if(html == null)
				html = templates.get("DEF");
			//if(html == null)
			//	html = defTemplate;
			
			String output = engine.transform(html, variables);
			infoText.loadDataWithBaseURL("", output, "text/html", "utf-8", null);
		}
	}
	
	@SuppressLint("DefaultLocale")
	public static String makeSize(long fileSize) {
		String s;
		if(fileSize < 10 * 1024) {
			s = String.format("%1.1fKB", (float) fileSize / 1024F);
		} else if(fileSize < 1024 * 1024) {
			s = String.format("%dKB", fileSize / 1024);
		} else if(fileSize < 10 * 1024 * 1024) {
			s = String.format("%1.1fMB", (float) fileSize / (1024F * 1024F));
		} else {
			s = String.format("%dMB", fileSize / (1024 * 1024));
		}
		return s;
	}

	public void setBackgroundColor(int color) {
		backgroundColor = color;
		if(infoText != null) {
			String html = String.format(empty, backgroundColor & 0xffffff);
			infoText.loadData(html, "text/html", "utf-8");
			infoText.getSettings();
			infoText.setBackgroundColor(backgroundColor);
		}
	}

}
