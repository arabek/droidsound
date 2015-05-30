package com.ssb.droidsound.service;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;

import com.ssb.droidsound.utils.Log;

public class AudioPlayer {	
	private static final String TAG = AudioPlayer.class.getSimpleName();

	private MediaPlayer mediaPlayer;
	private AudioTrack audioTrack;
	private boolean reinitAudio;
	private int bufSize = 0x40000;
	private int FREQ = 44100;
	private int startPlaybackHead;
	private int playPosOffset;

	private boolean doStart;
	
	public AudioPlayer(int bs) {
		bufSize = bs;
		audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, FREQ, AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);

	}

	public void update(short [] samples, int len) {
		
		if(doStart) {
			audioTrack.play();
			//Log.d(TAG, "STATE " + audioTrack.getState());
			//try {
			//	Thread.sleep(10);
			//} catch (InterruptedException e) {
				// TODO Auto-generated catch block
			//	e.printStackTrace();
			//}
			startPlaybackHead = audioTrack.getPlaybackHeadPosition();
			playPosOffset = 0;
			//Log.d(TAG, "START PLAYBACK " + startPlaybackHead);
			//Log.d(TAG, "STATE " + audioTrack.getState());
			doStart = false;
		}
		
		audioTrack.write(samples, 0, len);
		

		//Log.d(TAG, "STATE " + audioTrack.getState());
		//Log.d(TAG, "PLAYBACK NOW " + audioTrack.getPlaybackHeadPosition());

	}
	
	// Get number of seconds played since start
	public int getPlaybackPosition() {
		
		if(mediaPlayer != null) {
			return  mediaPlayer.getCurrentPosition();
		}
		int pos = audioTrack.getPlaybackHeadPosition() - startPlaybackHead;
		int playPos = pos * 10 / (FREQ / 100);
		//Log.d(TAG, "POS %d => %d (%d)", pos, playPos, playPosOffset);

		return playPos + playPosOffset;
	}
	
	// Inform player that playback has seeked to a new position. Affects playback position
	public void seekTo(int msec) {
		if(mediaPlayer != null) {
			mediaPlayer.seekTo(msec);
			return;
		}
		
		audioTrack.flush();
		
		int playPos = (audioTrack.getPlaybackHeadPosition()  - startPlaybackHead) * 10 / (FREQ / 100);
		Log.d(TAG, "Offset %d/1000 - %d", msec, playPos);
		playPosOffset = msec - playPos;
	}

	// Stop playback
	public void stop() {
		if(mediaPlayer != null) {
			mediaPlayer.stop();
		} else {

			audioTrack.stop();
			audioTrack.flush();

			if(reinitAudio) {
				audioTrack.release();
				audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, FREQ,
						AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
						AudioTrack.MODE_STREAM);
				reinitAudio = false;
			}
			
			//startPlaybackHead = 0;//audioTrack.getPlaybackHeadPosition();
			//playPosOffset = 0;
		}
	}


	public void start() {
		//MediaPlayer mp = currentPlugin.getMediaPlayer();
		if(mediaPlayer != null) {
			mediaPlayer.start();
			return;
		}

		if(reinitAudio) {
			audioTrack.release();
			audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, FREQ, AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
			reinitAudio = false;
		}

		doStart = true;
		//Log.d(TAG, "STATE " + audioTrack.getState());

		//audioTrack.play();
		//startPlaybackHead = audioTrack.getPlaybackHeadPosition();
		//Log.d(TAG, "START PLAYBACK " + startPlaybackHead);
		//playPosOffset = 0;		
	}

	public void pause() {
		audioTrack.pause();
		
	}

	public void flush() {
		audioTrack.flush();
		startPlaybackHead = audioTrack.getPlaybackHeadPosition();
		playPosOffset = 0;
		Log.d(TAG, "PLAYBACK AFTER FLUSH " + startPlaybackHead);
		
	}

	public void release() {
		audioTrack.release();
		
	}

	public void play() {
		audioTrack.play();
		// TODO Auto-generated method stub
		
	}

	public void setBufferSize(int bs) {
		bufSize = bs;
		reinitAudio = true;
	}

}
