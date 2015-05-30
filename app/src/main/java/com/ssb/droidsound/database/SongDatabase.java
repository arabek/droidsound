package com.ssb.droidsound.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.BaseColumns;

import com.ssb.droidsound.FileIdentifier;
import com.ssb.droidsound.Playlist;
import com.ssb.droidsound.SongFile;
import com.ssb.droidsound.file.FileSource;
import com.ssb.droidsound.utils.Archive;
import com.ssb.droidsound.utils.Log;
import com.ssb.droidsound.utils.Unpacker;

/**
 * 
 * SCANNING:
 * 
 * Enter only directories that have changed since last time Check all files and
 * dirs in directory against database entry. Remove missing, add new Enter new
 * directories regardless of modified.
 * 
 */

public class SongDatabase implements Runnable {
	private static final String TAG = SongDatabase.class.getSimpleName();
	
	public static final int DB_VERSION = 4;
	
	private static final String[] FILENAME_array = new String[] { "_id", "FILENAME", "TYPE" };

	private ScanCallback scanCallback;
	
	private Map<String, DataSource> dbsources = new HashMap<String, DataSource>();
	
	private SQLiteDatabase scanDb;
	private SQLiteDatabase rdb;
	
	private String dbName;
	private volatile boolean stopScanning;

	private Context context;

	private volatile Handler mHandler;

	private volatile boolean scanning;

	private volatile boolean isReady;

	//private int indexMode = -1;
	//private int lastIndexMode = -1;

	private File dbFile;
	
	public static final int TYPE_ARCHIVE = 0x100;
	public static final int TYPE_DIR = 0x200;
	public static final int TYPE_PLIST = 0x300;
	public static final int TYPE_FILE = 0x400;
	
	public static final int SORT_TITLE = 0;
	public static final int SORT_AUHTOR = 1;
	public static final int SORT_DATE = 2;
	

	protected static final int MSG_SCAN = 0;
	protected static final int MSG_OPEN = 1;
	protected static final int MSG_SCANDIR = 2;
	protected static final int MSG_QUIT = 4;
	protected static final int MSG_INDEXMODE = 5;
	
	public static final int INDEX_NONE = 0;
	public static final int INDEX_BASIC = 1;
	public static final int INDEX_FULL = 2;

	
	public boolean isReady() {
		return  (mHandler != null);
	}
	
	public SongDatabase(Context ctx) {		
		context = ctx;
		setDefaultCallback();
	}

	public SongDatabase(Context ctx, File db) {		
		context = ctx;		
		dbFile = db;
		setDefaultCallback();
	}

	private void setDefaultCallback() {
		setScanCallback(new ScanCallback() {
			String oldPath;
			@Override
			public void notifyScan(String path, int percent) {
				Intent intent;
				
				if(path == null) {
					path = oldPath;
				} else {
					oldPath = path;
				}
				
				Log.d(TAG, "PATH %s %d\n", path, percent);
				
				if(percent >= 0) {
					intent = new Intent("com.sddb.droidsound.SCAN_UPDATE");
					intent.putExtra("PATH", path);
					intent.putExtra("PERCENT", percent);
				} else {
					intent = new Intent("com.sddb.droidsound.SCAN_DONE");
				}
				context.sendBroadcast(intent);				
			}
		});
	}

	private SQLiteDatabase getReadableDatabase() {
		if(dbName == null) {
			return null;
		}
		try {
			return SQLiteDatabase.openDatabase(dbName, null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		} catch (SQLException e) {
			e.printStackTrace();
			return null;
		}
	}

	private SQLiteDatabase getWritableDatabase() {
		SQLiteDatabase dbrc = null;
		if(dbName == null) {
			return null;
		}
		try {
			dbrc =  SQLiteDatabase.openDatabase(dbName, null, SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return dbrc;
	}

	public void registerDataSource(String dumpname, DataSource ds) {
		String s = dumpname.toUpperCase(Locale.ENGLISH);
		dbsources.put(s, ds);
	}
	
	private static class MyHandler extends Handler {
		
		private WeakReference<SongDatabase> sdbRef;

		public MyHandler(SongDatabase sdb) {
			sdbRef = new WeakReference<SongDatabase>(sdb);
		}
		
		@Override
        public void handleMessage(Message msg) {
        	Log.d(TAG, "Got msg %d with arg %d", msg.what, msg.arg1);
        	SongDatabase sdb = sdbRef.get();
            switch (msg.what) {
            case MSG_SCAN:
            	if(msg.arg1 == 2) {
            		sdb.doOpen(true);
            		sdb.doScan((String)msg.obj, false);
            	} else {
            		sdb.doScan((String)msg.obj, msg.arg1 != 0);
            	}
            	break;
            case MSG_SCANDIR:
            	sdb.doScanDir((String)msg.obj);
            	break;
            case MSG_QUIT:
            	Log.d(TAG, "Telling looper to quit");
            	Looper.myLooper().quit();
            	break;
            } 
		}
	};
	

	@Override
	public void run() {
				
		Looper.prepare();
		
		mHandler = new MyHandler(this);
		
		doOpen(false);

		Intent intent = new Intent("com.sddb.droidsound.OPEN_DONE");
		context.sendBroadcast(intent);
		
		//UADEPlugin.extractFiles();

		Looper.loop();
		
		Log.d(TAG, "Exiting songdatabase");
	}

	private void doOpen(boolean drop) {	

		if(dbFile == null) {
			File droidDir = new File(Environment.getExternalStorageDirectory(), "droidsound");
	
			droidDir.mkdir();
			
			if(!droidDir.exists()) {
				throw new RuntimeException("Droidsound directory could not be created");
			}
			
			dbFile  = new File(droidDir, "songs.db");
		}		
		
		isReady = false;
		
		if(rdb != null) {
			rdb.close();
			rdb = null;
		}

		

		dbName = dbFile.getAbsolutePath();
		Log.d(TAG, "Database path %s", dbName);		

		SQLiteDatabase db = getWritableDatabase();
		
		if(db == null) {
			scanning = false;
			return;
		}
		

		if(db.needUpgrade(DB_VERSION)) {
			drop = true;
		}
		
		try {
			if(drop) {
				scanCallback.notifyScan("Clearing tables", 0);
				Log.d(TAG, "Deleting file tables!");
				
				db.execSQL("DROP TABLE IF EXISTS FILES ;");
				db.execSQL("DROP TABLE IF EXISTS VARIABLES ;");
				db.execSQL("DROP TABLE IF EXISTS LINKS ;");
				//db.execSQL("DROP TABLE IF EXISTS SONGINFO");
				db.setVersion(DB_VERSION);
				scanCallback.notifyScan("Creating tables", 0);
			}
	
			db.execSQL("CREATE TABLE IF NOT EXISTS " + "FILES" + " (" + BaseColumns._ID + " INTEGER PRIMARY KEY," +
					"PATH" + " TEXT," +
					"FILENAME" + " TEXT," +
					"TYPE" + " INTEGER," +
	
					"TITLE" + " TEXT," +
					"COMPOSER" + " TEXT," +
					"DATE" + " INTEGER," +
					"FORMAT" + " TEXT" + ");");
					// "LENGTH" + " INTEGER" + 
			
			db.execSQL("CREATE TABLE IF NOT EXISTS " + "VARIABLES" + " (" + BaseColumns._ID + " INTEGER PRIMARY KEY," +
					"VAR" + " TEXT," +
					"VALUE" + " TEXT" + ");");
			
			db.execSQL("CREATE INDEX IF NOT EXISTS fileindex ON FILES (PATH) ;");		
			db.execSQL("DROP INDEX IF EXISTS titleindex ;");
			db.execSQL("DROP INDEX IF EXISTS composerindex ;");
			db.execSQL("DROP INDEX IF EXISTS filenameindex ;");

		} finally {		
			db.close();
		}
		isReady = true;
	}
	
	/*
	private void createIndex(SQLiteDatabase db) {
		
			
			scanCallback.notifyScan("Updating indexes", 0);
			
			Log.d(TAG, "Updating indexes to mode " + indexMode);
			
			switch(indexMode) {
			case INDEX_NONE:
				db.execSQL("DROP INDEX IF EXISTS fileindex ;");		
				db.execSQL("DROP INDEX IF EXISTS titleindex ;");
				db.execSQL("DROP INDEX IF EXISTS filenameindex ;");
				db.execSQL("DROP INDEX IF EXISTS composerindex ;");
				break;
			case INDEX_BASIC:
				db.execSQL("CREATE INDEX IF NOT EXISTS fileindex ON FILES (PATH) ;");		
				db.execSQL("DROP INDEX IF EXISTS titleindex ;");
				db.execSQL("DROP INDEX IF EXISTS composerindex ;");
				db.execSQL("DROP INDEX IF EXISTS filenameindex ;");
				break;
			case INDEX_FULL:
				db.execSQL("CREATE INDEX IF NOT EXISTS fileindex ON FILES (PATH) ;");		
				db.execSQL("CREATE INDEX IF NOT EXISTS titleindex ON FILES (TITLE) ;");
				db.execSQL("CREATE INDEX IF NOT EXISTS composerindex ON FILES (COMPOSER) ;");;
				db.execSQL("DROP INDEX IF EXISTS filenameindex ;");
				break;
			}
			
			for(Entry<String, DataSource> ds : dbsources.entrySet()) {			
				ds.getValue().createIndex(indexMode, db);			
			}
			
			//scanCallback.notifyScan(null, -1);
			
			//db.close();
			
			//scanning = false;
			
			//indexMode = mode; 
		//}
	} */
	
	
	private boolean scanZip(File zipFile) throws IOException {
		
		Log.d(TAG, "Scanning %s", zipFile.getPath());
		
		// Erase any previous entries
		scanDb.delete("FILES", "PATH=? AND FILENAME=?", new String [] { zipFile.getParent(), zipFile.getName() } );
		scanDb.delete("FILES", "PATH=?", new String [] { zipFile.getPath() });		
		scanDb.delete("FILES", "PATH LIKE ?", new String [] { zipFile.getPath() + "/%" });		
		
		Log.d(TAG, "OPEN");
		//ZipFile zfile = new ZipFile(zipFile);
		
		Archive archive = Unpacker.openArchive(zipFile);
		if(archive == null)
			return false;
		
		//NativeZipFile zfile = new NativeZipFile(zipFile);
		Log.d(TAG, "ENTRY");
		
		String baseNameNoSlash = zipFile.getPath();
		String baseName = zipFile.getPath() + "/";
		// Basename = /sdcard/MODS/C64Music.zip/

		//ZipEntry infoe = zfile.getEntry("C64Music/DOCUMENTS/Songlengths.txt");
		//if(infoe != null) {
		//	HVSCParser.parseSongLengths(zfile.getInputStream(infoe), scanDb);
		//}		
		
		Log.d(TAG, "ENUM");
		//Enumeration<? extends ZipEntry> entries = zfile.entries();
		Iterator<Archive.Entry> entries = archive.getIterator();
		
		Set<String> pathSet = new HashSet<String>();
		
		//Log.d(TAG, "Scanning %d zip entries", zfile.size());
		int count = 0;
		int total = archive.getFileCount();
		// total == -1 means archive does not support getting number of files
		int reportPeriod = total / 100;
		if(reportPeriod < 100) {
			reportPeriod = 100;
		}

		ContentValues values = new ContentValues();
		//values.put("LENGTH", 0);
		values.put("TYPE", TYPE_FILE);
		
		boolean pathIncluded = false;
			
		while(entries.hasNext()) {
		//while(entries.hasMoreElements()) {
			
			if(stopScanning) {
				//zfile.close();
				archive.close();
				return false;
			}
			
			//ZipEntry ze = entries.nextElement();
			Archive.Entry ze = entries.next();
			
			String n = ze.getPath(); // -> MUSIC/song.mod		
			int slash = n.lastIndexOf('/');				
			String fileName = n.substring(slash+1);
			String path;
			if(slash >= 0) {
				path = baseName + n.substring(0, slash); // -> '/sdcard/MODS/Stuff.zip/' + 'MUSIC'
			} else {
				path = baseNameNoSlash; // Silly optimization
			}

			if(fileName.equals("")) {
				pathIncluded = true;
				pathSet.add(path);
			} else {
				
				if(!pathIncluded && !path.equals(baseNameNoSlash))
					pathSet.add(path);
									
				//InputStream is = zfile.getInputStream(ze);
				
				//FileSource fs = new FileSource(zfile, ze);
				FileSource fs = archive.getFileSource(ze);
				
				FileIdentifier.MusicInfo info = FileIdentifier.identify(n, fs);
				fs.close();
	
				if(info != null) {
					
					// NOTE: Fix for strange zip archives that does not contain directories
					//if(!pathSet.contains(path))
					//	pathSet.add(path);

					values.put("TITLE", info.title);
					values.put("COMPOSER", info.composer);
					//values.put("COPYRIGHT", info.copyright);
					values.put("DATE", info.date);
					values.put("FORMAT", info.format);
					values.put("PATH", path);
					values.put("FILENAME", fileName);
					scanDb.insert("FILES", "PATH", values);
				} else {
					Log.d(TAG, "Could not identify '%s'", n);
				}

				count++;
				if((count % reportPeriod) == 0) {
					isReady = false;
					scanCallback.notifyScan(null, total >= 0 ? count * 100 / total : count);
				}
					
				//if(count == 4000)
				//	break;
			}			
		}
		
		//zfile.close();
		archive.close();

		Log.d(TAG, "Adding %d paths", pathSet.size());
		
		values.clear();
		values.put("TYPE", TYPE_DIR);
		if(!pathIncluded) {
			
			// Add all parent paths to the paths already in pathSet
			
			Set<String> pathSet2 = new HashSet<String>();
			for(String s : pathSet) {
				//if(s.equals(baseNameNoSlash))
				//	break;

				int slash = 1;
				while(slash > 0) {					
					slash = s.lastIndexOf('/');
					if(slash > 0) {
						s = s.substring(0,slash);
						if(s.equals(baseNameNoSlash))
							break;
						if(pathSet.contains(s))
							break;
						pathSet2.add(s);					
					}
				}
			}
			pathSet.addAll(pathSet2);
		}
		for(String s : pathSet) {
			//ContentValues values = new ContentValues();
			
			if(!pathIncluded) {
				Log.d(TAG, "Adding: %s", s);
			}
			
			int slash = s.lastIndexOf('/');
			String fileName = s.substring(slash+1);
			String path = s.substring(0, slash);
			
			values.put("PATH", path);
			values.put("FILENAME", fileName);
			scanDb.insert("FILES", "PATH", values);
		}
		
		return true;
		
	}
	
	private void scanFiles(File parentDir, boolean alwaysScan, long lastScan) {
		
		String parent = parentDir.getPath();
		String[] parentArray = new String[] { parent };
		
		if(stopScanning) {
			return;
		}
		
		boolean hasChanged = (lastScan < parentDir.lastModified());
		if(alwaysScan)
			hasChanged = true;
		
		Log.d(TAG, "Entering '%s', lastScan %d", parent, lastScan);
		if(hasChanged) {
			Log.d(TAG, ">> Doing FULL scan");
		}

		Cursor fileCursor = scanDb.query("FILES", FILENAME_array, "PATH=?", parentArray, null, null, null);
		int index = fileCursor.getColumnIndex("FILENAME");
		int flindex = fileCursor.getColumnIndex("TYPE");
		int idindex = fileCursor.getColumnIndex("_id");

		if(hasChanged) {
			
			scanCallback.notifyScan(parentDir.getPath(), 0);
			
			// All files and directories
			Set<String> files = new HashSet<String>();
			
			// Directories to scan later
			Set<String> foundDirs = new HashSet<String>();
			Set<String> foundDirsNew = new HashSet<String>();
			Set<File> zipFiles = new HashSet<File>();
			
			File [] fileList = parentDir.listFiles();
			
			if(fileList != null) {
				// Add all existing files to a hash set
				for(File f : fileList) {
					if(f.getName().charAt(0) != '.')
						files.add(f.getName());
				}
			}

			Log.d(TAG, "Datbase has %d entries, found %d files/dirs", fileCursor.getCount(), files.size());

			Set<String> delDirs = new HashSet<String>();
			Set<Long> delFiles = new HashSet<Long>();
			

			Set<String> removes = new HashSet<String>();

			Log.d(TAG, "Comparing DB to FS");
			
			// Iterate over database result and compare to hash set
			while(fileCursor.moveToNext()) {
				String fileName = fileCursor.getString(index);
				int type = fileCursor.getInt(flindex);
				long id = fileCursor.getLong(idindex);
				
				if(removes.contains(fileName)) {
					// Found duplicate in database
					Log.d(TAG, "!! Found duplicate in database '%s', REMOVING !!", fileName);
					delFiles.add(id);
				}
				
				if(files.contains(fileName)) {
					// File/dir is in both
					
					File f = new File(parentDir, fileName);
					
					// Add directories to scanlist
					if(f.isDirectory()) {
						foundDirs.add(fileName);
						/// files.remove(fileName);
						removes.add(fileName);
					} else {
						//Log.d(TAG, "!! Lastscan %d, file %s modified %d", lastScan, fileName, f.lastModified());
						if(lastScan < f.lastModified()) {
							// File has been modified - del and readd
							Log.d(TAG, "!! FILE %s was modified", fileName);
							delFiles.add(id);
						} else {
							/// files.remove(fileName);
							removes.add(fileName);
						}
					}
				} else {
					Log.d(TAG, "!! '%s' found in DB but not on disk, DELETING", fileName);
					// File has been removed on disk, schedule for DELETE
					if(type == TYPE_FILE) {
						delFiles.add(id);
					} else {
						delDirs.add(fileName);
					}
				}
			}
			
			files.removeAll(removes);
			
			for(String s : files) {
				Log.d(TAG, "!! '%s' not in database, ADDING", s);
			}
				
			// Close cursor (important since we call ourselves recursively below)
			fileCursor.close();
			fileCursor = null;
			//File csdb = null;
			List<File> foundDumps = new ArrayList<File>();
	
			if(files.size() > 0 || delDirs.size() > 0 || delFiles.size() > 0) {

				// We have database operations to perform
				scanDb.beginTransaction();
				try {
					
					for(String d : delDirs) {
						String path = new File(parent, d).getPath();
						Log.d(TAG, "Deleting PATH %s and subdirs", path);
						scanDb.delete("FILES", "PATH=? AND FILENAME=?", new String [] { parent, d} );
						scanDb.delete("FILES", "PATH LIKE ?", new String [] { path + "/%" } );
						scanDb.delete("FILES", "PATH=?", new String [] { path } );
					}
	
					for(long id : delFiles) {
						Log.d(TAG, "Deleting FILE %d in %s", id, parent);
						scanDb.delete("FILES", "_id=?", new String [] { Long.toString(id) } );
					}

					// Iterate over added files					
					int count = 0;
					int total = files.size();
					int reportPeriod = total / 100;
					if(reportPeriod < 10) {
						reportPeriod = 10;
					}
					for(String s : files) {
						File f = new File(parentDir, s);
						ContentValues values = new ContentValues();
						values.put("PATH", f.getParentFile().getPath());
						values.put("FILENAME", f.getName());
						FileSource fs = FileSource.fromFile(f);
						
						Log.d(TAG, "%s isfile %s", f.getPath(), String.valueOf(f.isFile()));
	
						if(f.isFile()) {
							
							String fn = f.getName();
							int end = fn.length();
														
							if(dbsources.containsKey(fn.toUpperCase(Locale.ENGLISH))) {								
								foundDumps.add(f);
								values = null;
							}							
							else
							if(fn.toUpperCase(Locale.ENGLISH).endsWith(".ZIP")) {
								Log.d(TAG, "Found zipfile (%s)", f.getPath());
								zipFiles.add(f);
								
								values = null;
							}
							else 
							if(fn.toUpperCase(Locale.ENGLISH).endsWith(".PLIST")) {
								Log.d(TAG, "Found playlist (%s)", fn);
								values.put("TYPE", TYPE_PLIST);
								values.put("TITLE", fn.substring(0, end - 6));								
							}
							else 
							if(fn.toUpperCase(Locale.ENGLISH).endsWith(".LNK")) {
								Log.d(TAG, "Found link (%s)", fn);
								values.put("TYPE", TYPE_DIR);
								values.put("TITLE", fn.substring(0, end - 4));								
							} else {
								values.put("TYPE", TYPE_FILE);
								Log.d(TAG, "Checking %s", f.getPath());
																	
								//InputStream is = new BufferedInputStream(new FileInputStream(f), 256);
								//FileIdentifier.MusicInfo info = FileIdentifier.identify(f.getName(), is);
								//is.close();
								FileIdentifier.MusicInfo info = FileIdentifier.identify(fs);
								boolean ok = false;
								if(info != null) {
									values.put("TITLE", info.title);
									values.put("COMPOSER", info.composer);
									//values.put("COPYRIGHT", info.copyright);
									values.put("DATE", info.date);
									values.put("FORMAT", info.format);
									//values.put("LENGTH", 0);
									ok = true;				
								}/* else {
									if(checkModule(f, values)) {
										ok = true;
									}
								} */
								
								if(!ok) {
									values = null;
								}
							}
						} else {
							foundDirsNew.add(s);
							values.put("TYPE", TYPE_DIR);
						}
						if(values != null) {
							Log.d(TAG, "Inserting FILE... (%s)", s);
							scanDb.insert("FILES", "PATH", values);
						}
						
						fs.close();
						fs = null;
						
						count++;
						if(count % reportPeriod == 0) {
							isReady = false;
							scanCallback.notifyScan(null, count * 100 / total);
						}
					}
					Log.d(TAG, "TRANSACTION SUCCESSFUL");
					scanDb.setTransactionSuccessful();
					
				} finally {
					scanDb.endTransaction();
				}
			}
			
			Log.d(TAG, "zipfiles (%d)", zipFiles.size());
			if(zipFiles.size() > 0) {				
				for(File f : zipFiles) {
					try {
						isReady = false;
						scanCallback.notifyScan(f.getPath(), 0);
						scanDb.beginTransaction();
						if(scanZip(f)) {
							ContentValues values = new ContentValues();
							values.put("PATH", f.getParentFile().getPath());
							values.put("FILENAME", f.getName());
							values.put("TYPE", TYPE_ARCHIVE);
							int end = f.getName().length();
							values.put("TITLE", f.getName().substring(0, end - 4));				
							Log.d(TAG, "Inserting FILE... (%s)", f.getName());
							scanDb.insert("FILES", "PATH", values);
							scanDb.setTransactionSuccessful();
							Log.d(TAG, "ZIP TRANSATION SUCCESSFUL");
						}
					} /*catch (ZipException e) {
						Log.d(TAG, "Broken zip");
					} */ catch (IOException e) {
						Log.d(TAG, "IO Error");
					}
					scanDb.endTransaction();
				}
			}
			
			for(File dump : foundDumps) {
				DataSource ds = dbsources.get(dump.getName().toUpperCase(Locale.ENGLISH));
				isReady = false;
				scanCallback.notifyScan(dump.getPath(), 0);
				
				InputStream is = null;
				int size = -1;
				ZipFile zf = null;
				if(dump.getName().toUpperCase(Locale.ENGLISH).endsWith(".ZIP")) {
					try {
						zf = new ZipFile(dump);
						Enumeration<? extends ZipEntry> entries = zf.entries();
						while(entries.hasMoreElements()) {
							ZipEntry ze = entries.nextElement();
							if(!ze.isDirectory()) {
								is = zf.getInputStream(ze);
								size = (int) ze.getSize();
								break;
							}
						}	
					} catch (ZipException e) {
					} catch (IOException e) {
					}
				} else {
					try {
						is = new FileInputStream(dump);
						size = (int) dump.length();					
					} catch (FileNotFoundException e) {
					}
				}
				
				try {
					if(is != null) {
						if(ds.parseDump(is, size, scanDb, scanCallback)) {
							ContentValues values = new ContentValues();
							values.put("PATH", dump.getParentFile().getPath());
							values.put("FILENAME", dump.getName());
							values.put("TYPE", TYPE_ARCHIVE);
							values.put("TITLE", ds.getTitle());
							Log.d(TAG, "Inserting %s from dump (%s)", ds.getTitle(), dump.getPath());
							scanDb.insert("FILES", "PATH", values);
							//db.setTransactionSuccessful();
							//Log.d(TAG, "ZIP TRANSATION SUCCESSFUL");
						}
						is.close();
					}
					if(zf != null) {
						zf.close();
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			
			if(stopScanning) {
				return;
			}

			// Time to recursively scan sub-directories
			// scanDirs contains found dirs that exists in database

			for(String s : foundDirs) {
				File f = new File(parentDir, s);
				isReady = false;
				scanCallback.notifyScan(f.getPath(), 0);
				scanFiles(f, alwaysScan, lastScan);
			}			
			
			for(String s : foundDirsNew) {
				File f = new File(parentDir, s);
				isReady = false;
				scanCallback.notifyScan(f.getPath(), 0);
				scanFiles(f, true, lastScan);
			}
	
		} else {
			
			// This directory is not modified - we can rely on the database entries
			
			Set<File> files = new HashSet<File>();
			while(fileCursor.moveToNext()) {
				String fileName = fileCursor.getString(index);
				int type = fileCursor.getInt(flindex);
				if(type == TYPE_DIR) {
					files.add(new File(parentDir, fileName));
				}
			}
			
			Log.d(TAG, "No change, scanning %d Datbase entries with %d dirs", fileCursor.getCount(), files.size());

			fileCursor.close();
			fileCursor = null;

			for(File f : files) {
				scanFiles(f, alwaysScan, lastScan);
			}
		}
	}

	public void scan(boolean full, String mdir) {
		Message msg = mHandler.obtainMessage(MSG_SCAN, full ? 1 : 0, 0, mdir);
		mHandler.sendMessage(msg);
	}

	public void scanDir(String dir) {
		Message msg = mHandler.obtainMessage(MSG_SCANDIR, dir);
		mHandler.sendMessage(msg);
	}

	public void rescan(String mdir) {
		Message msg = mHandler.obtainMessage(MSG_SCAN, 2, 0, mdir);
		mHandler.sendMessage(msg);
	}
	
	public void open() {
		Message msg = mHandler.obtainMessage(MSG_OPEN);
		mHandler.sendMessage(msg);
	}

	/*
	public void setIndexMode(int mode) {
		
		 Log.d(TAG, "INDEX MODE " + mode);
		 indexMode = mode;
		
		//if(indexMode != mode) {
		//	Message msg = mHandler.obtainMessage(MSG_INDEXMODE, mode, 0);
		//	mHandler.sendMessage(msg);
		//}
		
	} */
	
	private void doScanDir(String dir) {
		
		scanDb = getWritableDatabase();
		
		if(scanDb == null) {
			return;
		}

		stopScanning = false;
		scanning = true;
		
		//long startTime = System.currentTimeMillis();
		//long lastScan = -1;
		
		File f = new File(dir);
		
		if(f.isDirectory()) {
			scanFiles(f, true, 0);
		} else if(f.getName().toUpperCase(Locale.ENGLISH).endsWith(".ZIP")) {			
			try {
				isReady = false;
				scanCallback.notifyScan(f.getPath(), 0);
				scanDb.beginTransaction();
				if(scanZip(f)) {
					ContentValues values = new ContentValues();
					values.put("PATH", f.getParentFile().getPath());
					values.put("FILENAME", f.getName());
					values.put("TYPE", TYPE_ARCHIVE);
					int end = f.getName().length();
					values.put("TITLE", f.getName().substring(0, end - 4));				
					Log.d(TAG, "Inserting FILE... (%s)", f.getName());
					scanDb.insert("FILES", "PATH", values);
					scanDb.setTransactionSuccessful();
					Log.d(TAG, "ZIP TRANSATION SUCCESSFUL");
				}
			} /*catch (ZipException e) {
				Log.d(TAG, "Broken zip");
			} */ catch (IOException e) {
				Log.d(TAG, "IO Error");
			}
			scanDb.endTransaction();
		}

		stopScanning = false;
		isReady = true;
		
		scanDb.close();
		scanDb = null;
		scanning = false;

		scanCallback.notifyScan(null, -1);

	}
	
	private void doScan(String modsDir, boolean full) {

		scanDb = getWritableDatabase();
		
		if(scanDb == null) {
			return;
		}

		stopScanning = false;
		scanning = true;

				
		// FileIdentifier.setPlugins(plugins);


		//rdb = getReadableDatabase();
		

		long startTime = System.currentTimeMillis();
		long lastScan = -1;
		Cursor cursor = scanDb.query("VARIABLES", new String[] { "VAR", "VALUE" }, "VAR='lastscan'", null, null, null, null);
		if(cursor.getCount() == 0) {
			ContentValues values = new ContentValues();
			values.put("VAR", "lastscan");
			values.put("VALUE", "0");
			scanDb.insert("VARIABLES", "VAR", values);
		} else {		
			cursor.moveToFirst();
			lastScan = Long.parseLong(cursor.getString(1));
		}
		
		cursor.close();
		
		Log.d(TAG, "Last scan %d\n", lastScan);

		File parentDir = new File(modsDir);
		
		if(full) {
			
			Set<File> deletes = new HashSet<File>();
			int limit, offset;
			limit = 5000;
			offset = 1;
						
			scanCallback.notifyScan("Checking orphans", 0);
			
			while(true) {
				
				// Remove orphaned directories
				Cursor oc = scanDb.query("FILES", new String[] { "PATH", "FILENAME" }, "PATH NOT LIKE '%.zip%'", null, null, null, null, String.format("%d,%d", offset, limit));

				Log.d(TAG, "Orphan check on %d files\n", oc.getCount());

				if(oc.getCount() <= 0) {
					oc.close();
					break;
				}
				
				offset += limit;
				
				int pindex = oc.getColumnIndex("PATH");
				int findex = oc.getColumnIndex("FILENAME");
		

				deletes.clear();
				while(oc.moveToNext()) {
					if(stopScanning) {
						break;
					}
					String pathName = oc.getString(pindex);
					if(!pathName.toUpperCase(Locale.ENGLISH).contains(".ZIP")) {
						String fileName = oc.getString(findex);
						File f = new File(pathName, fileName);
						if(!f.exists()) {
							deletes.add(f);
						}
					}
				}
				
				oc.close();
				
				if(stopScanning) {
					break;
				}
				
				if(deletes.size() > 0) {
					isReady = false;
					scanDb.beginTransaction();
					for(File f : deletes) {
						Log.d(TAG, "Removing %s from DB\n", f.getPath());
						scanDb.delete("FILES", "PATH=? AND FILENAME=?", new String[] { f.getParent(), f.getName() });
						if(f.getName().toUpperCase(Locale.ENGLISH).endsWith(".ZIP")) {
							Log.d(TAG, "Removing zip contents");
							scanDb.delete("FILES", "PATH LIKE ?", new String[] { f.getPath() + "/%" });
						}
					}
					scanDb.setTransactionSuccessful();
					scanDb.endTransaction();
				}
			}
		}

		
		
		scanFiles(parentDir, full, lastScan);
		
		if(!stopScanning) {
			ContentValues values = new ContentValues();
			values.put("VAR", "lastscan");
			values.put("VALUE", Long.toString(startTime));
			scanDb.update("VARIABLES", values, "VAR='lastscan'", null);
		}
			
		//if(indexMode != lastIndexMode) {
		//	createIndex(scanDb);
		//	lastIndexMode = indexMode;
		//}
		
		stopScanning = false;
		isReady = true;
		
		scanDb.close();
		scanDb = null;
		scanning = false;

		scanCallback.notifyScan(null, -1);

		//rdb.close();
	}

	private static String searchOrder [] = new String[] { "TITLE COLLATE NOCASE", "DATE", "COMPOSER COLLATE NOCASE" };

	public Cursor search(String query, String fromPath, int sorting) {
		
		if(!isReady || query == null) {
			return null;
		}
		
		if(rdb == null) {
			rdb = getReadableDatabase();
		}
		
		
		for(Entry<String, DataSource> ds : dbsources.entrySet()) {
			if(fromPath.toUpperCase(Locale.ENGLISH).contains("/" + ds.getKey())) {
				Cursor cursor = ds.getValue().search(query, fromPath, rdb);
				if(cursor != null) {
					return cursor;
				}
			}
		}
		Cursor c = null;
		
		String [] columns = new String[] { "_id", "TITLE", "COMPOSER", "PATH", "FILENAME", "TYPE", "DATE" };
		if(query.charAt(0) == '.') {
			char x = query.toUpperCase().charAt(1);
			String q = "%" + query.substring(2).trim() + "%" ;			
			switch(x) {
			case 'C':
				c = rdb.query("FILES", columns, "COMPOSER LIKE ?", new String[] { q }, null, null, "TITLE", "500");
				break;
			case 'T':
				c = rdb.query("FILES", columns, "TITLE LIKE ?", new String[] { q }, null, null, "TITLE", "500");
				break;
			case 'Q':
				c = rdb.query("FILES", columns, query.substring(2).trim(), null, null, null, "TITLE", "500");
				break;
			default:
				break;
			}
		}
		else {
			String q = "%" + query + "%" ;		
			c = rdb.query("FILES", columns, "TITLE LIKE ? OR COMPOSER LIKE ?", new String[] { q, q }, null, null, searchOrder[sorting], "500");
		}
		if(c != null) {
			Log.d(TAG, "Got %d hits", c.getCount());
		}
		return c;
	}
	
	public void closeDB() {
		if(rdb != null) {
			rdb.close();
			rdb = null;
		}
	}

	private Playlist currentPlaylist;
	private Playlist activePlaylist;

	private String pathTitle;

	//private boolean doQuit;
	//private String currentLink;

	public Playlist getCurrentPlaylist() {
		return currentPlaylist;
	}

	public Playlist getActivePlaylist() {
		return activePlaylist;
	}
	
	public void setActivePlaylist(File file) {		
		activePlaylist = Playlist.getPlaylist(file);
	}
	
	public String getPathTitle() {
		return pathTitle;
	}
	
	private static String sortOrder [] = new String[] { "TYPE, TITLE COLLATE NOCASE, FILENAME ", "TYPE, DATE, FILENAME", "TYPE, COMPOSER COLLATE NOCASE, FILENAME" };

	private Map<String, String> linkMap = new HashMap<String, String>();
	/*
	public String translatePath(String path) {
		String upath = path.toUpperCase();
		int lIndex = upath.indexOf(".LNK");
		if(lIndex > 0) {
			String linkPath = path.substring(0, lIndex+4);
			Log.d(TAG, "linkPath '%s'", linkPath);
			String linkTarget = linkMap.get(linkPath);
			if(linkTarget == null) {
				try {
					File f = new File(linkPath);
					BufferedReader reader = new BufferedReader(new FileReader(f));
					String p = reader.readLine();
					reader.close();
					if(p != null && p.length() > 0) {
						linkTarget = p;
						linkMap.put(linkPath, linkTarget);
					}
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			path = linkTarget + path.substring(lIndex+4);			
			Log.d(TAG, "Translated to '%s'", path);
			
		}
		return path;
	} */

	
	public Cursor getFilesInPath(String pathName, int sorting) {
		
		if(pathName == null || !isReady) {
			return null;
		}

		pathTitle = null;
		
		String upath = pathName.toUpperCase(Locale.ENGLISH);
		int dot = pathName.lastIndexOf('.');
		String ext = "";
		if(dot > 0) {
			ext = upath.substring(dot);
		}

		Log.d(TAG, "files in path '%s'", pathName);
		//String name = new File(pathName).getName().toUpperCase();
		
		int lIndex = upath.indexOf(".LNK");
		if(lIndex > 0) {
			String linkPath = pathName.substring(0, lIndex+4);
			Log.d(TAG, "linkPath '%s'", linkPath);
			String linkTarget = linkMap.get(linkPath);
			if(linkTarget == null) {
				try {
					File f = new File(linkPath);
					BufferedReader reader = new BufferedReader(new FileReader(f));
					String p = reader.readLine();
					reader.close();
					if(p != null && p.length() > 0) {
						linkTarget = p;
						linkMap.put(linkPath, linkTarget);
					}
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			pathName = linkTarget + pathName.substring(lIndex+4);
			
			Log.d(TAG, "Translated to '%s'", pathName);
		}
		
		/*
		if(ext.equals(".LNK")) {
			try {
				File f = new File(pathName);
				BufferedReader reader = new BufferedReader(new FileReader(f));
				String p = reader.readLine();
				reader.close();
				if(p != null && p.length() > 0) {
					currentLink = pathName;
					pathName = p;
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		} */
		currentPlaylist = null;

		if(pathName.startsWith("http://")) {
			String s;
			try {
				s = URLDecoder.decode(pathName, "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}

			pathTitle = new File(s).getName();
			return HttpSongSource.getFilesInPath(context, pathName, sorting);
		}
		
		File file = new File(pathName);
		
		if(ext.equals(".PLIST")) {
			
			String name = file.getName();
			dot = name.lastIndexOf('.');
			if(dot > 0) {
				pathTitle = name.substring(0, dot);
			} else {
				pathTitle = name;
			}

			currentPlaylist = Playlist.getPlaylist(file);
			if(activePlaylist == null) {
				activePlaylist = currentPlaylist;
			}
			return currentPlaylist.getCursor();
		}

		if(rdb == null) {
			rdb = getReadableDatabase();
		}
		
		for(Entry<String, DataSource> db : dbsources.entrySet()) {
			if(upath.contains("/" + db.getKey())) {
				Cursor cursor = db.getValue().getCursorFromPath(file, rdb, sorting);
				if(cursor != null) {
					 pathTitle = db.getValue().getPathTitle(file);
					return cursor;
				}
			}
		}

		Log.d(TAG, "Path now '%s'", pathName);

		String path = file.getParent();
		String fname = file.getName();
		
		if(path == null || fname == null) {
			return null;
		}
	
		Log.d(TAG, "BEGIN");
		Cursor c = rdb.query("FILES", new String[] { "TITLE", "TYPE" }, "PATH=? AND FILENAME=?", new String[] { path, fname }, null, null, sortOrder[sorting], "5000");
		if(c != null) {
			if(c.moveToFirst()) {
				pathTitle = c.getString(0);
			}
			c.close();
		}
		c = rdb.query("FILES", new String[] { "_id", "TITLE", "COMPOSER", "FILENAME", "TYPE", "DATE" }, "PATH=?", new String[] { pathName }, null, null, sortOrder[sorting], "5000");	
		Log.d(TAG, "END");
		return c;
	}
	
	public void setScanCallback(ScanCallback cb) {
		scanCallback = cb;
	}

	public void stopScan() {
		stopScanning = true;
		
	}

	public boolean isScanning() {
		// TODO Auto-generated method stub
		return scanning;
	}
	
	public void addToPlaylist(Playlist pl, SongFile songFile) {
		
		
		Log.d(TAG, "Adding %s / %s to playlist %s", songFile.getPath(), songFile.getName(), pl.getFile().getName());
		
		if(pl.contains(songFile)) {
			Log.d(TAG, "Song exists, ignoring");
			return;
		}
		
		if(songFile.getName().toUpperCase(Locale.ENGLISH).endsWith(".ZIP")) {
			Log.d(TAG, "WONT add zip files");
			return;
		}

		//String realPath = translatePath(songFile.getPath());
		
		if(songFile.getPath().startsWith("http://")) {
			pl.add(songFile);
			return;
		}
		
		
		if(songFile.exists()) {
			if(songFile.getName().toUpperCase(Locale.ENGLISH).endsWith(".PLIST")) {
				Playlist newpl = Playlist.getPlaylist(songFile.getFile());
				List<SongFile> files = newpl.getSongs();
				Log.d(TAG, "Adding %d files from playlist", files.size());
				for(SongFile f2 : files) {
					addToPlaylist(pl, f2);
				}
				
			} else {
				pl.add(songFile);
			}
		} else {
			if(rdb == null) {
				rdb = getReadableDatabase();
			}
			Cursor cursor = rdb.query("FILES", new String[] { "_id", "TITLE", "COMPOSER", "FILENAME", "PATH", "TYPE" }, "PATH=? AND FILENAME=?", new String[] { songFile.getParent(), songFile.getName() }, null, null, null);
			
			
			//Log.d(TAG, "Got %d results from query", cursor.getCount());
			
			if(cursor != null && cursor.moveToFirst()) {
				int type = cursor.getInt(cursor.getColumnIndex("TYPE"));
				if(type == SongDatabase.TYPE_DIR) {
					cursor.close();
					cursor = rdb.query("FILES", new String[] { "_id", "TITLE", "COMPOSER", "FILENAME", "PATH", "TYPE" }, "PATH=?", new String[] { songFile.getPath() }, null, null, "TITLE");
					if(cursor.moveToFirst()) {
						pl.add(cursor, -1, null);
					}
					
				} else if(type == SongDatabase.TYPE_FILE) {
					pl.add(cursor, songFile.getSubtune(), songFile.getTitle());
				}
			}
			cursor.close();
		}
	}
	
	
	public boolean deleteFile(SongFile song) {
		return deleteFile(song.getFile());
	}

	public boolean deleteFile(File f) {
		SQLiteDatabase db = getWritableDatabase();
		if(db == null) {
			return false;
		}

		db.delete("FILES", "PATH=? AND FILENAME=?", new String [] { f.getParent(), f.getName() });		
		db.close();
		return true;

	}

	public boolean deleteDir(File f) {
		SQLiteDatabase db = getWritableDatabase();
		if(db == null) {
			return false;
		}

		// /sdcard/MODS/Dummy
		// /sdcard/MODS/Dummy  Dummy2
		// /sdcard/MODS/Dummy/Dummy2 
		
		String path = f.getPath();
		db.delete("FILES", "PATH=? AND FILENAME=?", new String [] { f.getParent(), f.getName()} );
		db.delete("FILES", "PATH LIKE ?", new String [] { path + "/%" } );
		db.delete("FILES", "PATH=?", new String [] { path } );
		db.close();
		return true;

	}

	public void createPlaylist(File file) {
		
		FileWriter writer;
		String n = file.getName();
		try {			
			writer = new FileWriter(file);
			writer.close();
			ContentValues values = new ContentValues();
			values.put("PATH", file.getParent());
			values.put("FILENAME", n);
			values.put("TYPE", TYPE_PLIST);
			int dot = n.indexOf('.');
			if(dot > 0) {
				values.put("TITLE", n.substring(0, dot));
			} else {
				values.put("TITLE", n);
			}
			
			SQLiteDatabase db = getWritableDatabase();
			db.insert("FILES", "PATH", values);
			db.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}					
	}

	public void createLink(File file, String contents) {
		
		FileWriter writer;
		String n = file.getName();
		try {			
			writer = new FileWriter(file);
			writer.append(contents);
			writer.close();
			ContentValues values = new ContentValues();
			values.put("PATH", file.getParent());
			values.put("FILENAME", n);
			values.put("TYPE", TYPE_DIR);
			int dot = n.indexOf('.');
			if(dot > 0) {
				values.put("TITLE", n.substring(0, dot));
			} else {
				values.put("TITLE", n);
			}
			
			SQLiteDatabase db = getWritableDatabase();
			db.insert("FILES", "PATH", values);
			db.close();
			
		} catch (IOException e) {
			e.printStackTrace();
		}					
	}

	public void createFolder(File file) {
		
		if(file.mkdir()) {
			String n = file.getName();
			ContentValues values = new ContentValues();
			values.put("PATH", file.getParent());
			values.put("FILENAME", n);
			values.put("TYPE", TYPE_DIR);			
			SQLiteDatabase db = getWritableDatabase();
			if(db != null) {
				db.insert("FILES", "PATH", values);
				db.close();
			} else {
				file.delete();
			}
		}
	}

	public void quit() {
		stopScanning = true;
		Message msg = mHandler.obtainMessage(MSG_QUIT);
		mHandler.sendMessage(msg);	
	}



}
