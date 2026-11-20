package de.daubli.feedwatch.uvc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Plays the audio that a UVC capture card exposes as a USB audio input and reports peak levels
 * for the audio meter.
 *
 * <p>The card's audio interfaces stay with the kernel driver (only the video interface is
 * claimed for UVC), so the audio arrives through Android's normal recording path. The USB input
 * is selected explicitly when the system lists one.</p>
 */
public class UvcAudioPlayer {
    private static final String TAG = "UvcAudioPlayer";

    private static final int[] SAMPLE_RATES = {48_000, 44_100};
    private static final int[] CHANNEL_CONFIGS = {
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};

    /** About 20 ms of audio per read at 48 kHz. */
    private static final int FRAMES_PER_READ = 1024;

    /** A stopped session ends after its current read, this is only a safety net. */
    private static final long PREVIOUS_SESSION_TIMEOUT_MS = 1000;

    public interface LevelListener {
        void onLevels(float left, float right);
    }

    private final Context context;

    private final LevelListener listener;

    // the running session, null while stopped; guarded by this
    private Session session;

    // thread of the last session, which may still be closing after stop; guarded by this
    private Thread lastThread;

    public UvcAudioPlayer(Context context, LevelListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized boolean start() {
        if (session != null) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO is not granted, no audio");
            return false;
        }

        Session started = new Session(lastThread);
        Thread thread = new Thread(started::run, "UVC-Audio");
        session = started;
        lastThread = thread;
        thread.start();
        return true;
    }

    /** Returns at once, the session releases the USB input after its current read. */
    public synchronized void stop() {
        if (session != null) {
            session.running = false;
            session = null;
        }
    }

    /**
     * One start to stop. Each session has its own recorder and player, so a session that is still
     * finishing never touches the ones of the next.
     */
    private final class Session {

        private final Thread previous;

        private volatile boolean running = true;

        private AudioRecord record;

        private AudioTrack track;

        private int channelCount;

        Session(Thread previous) {
            this.previous = previous;
        }

        void run() {
            try {
                // the USB input can be opened only once, wait until the previous session let go of it
                if (previous != null) {
                    previous.join(PREVIOUS_SESSION_TIMEOUT_MS);
                }
                if (!running || !open()) {
                    return;
                }

                short[] buffer = new short[FRAMES_PER_READ * channelCount];

                while (running) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read <= 0) {
                        if (read < 0) {
                            Log.w(TAG, "AudioRecord.read returned " + read);
                            break;
                        }
                        continue;
                    }

                    reportLevels(buffer, read);

                    if (track != null) {
                        track.write(buffer, 0, read);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Audio loop failed", e);
            } finally {
                close();
            }
        }

        private boolean open() {
            AudioDeviceInfo usbInput = findUsbInput();
            if (usbInput == null) {
                Log.w(TAG, "No USB audio input found, no audio");
                return false;
            }
            Log.i(TAG, "Using USB audio input: " + usbInput.getProductName());

            for (int rate : SAMPLE_RATES) {
                for (int channelConfig : CHANNEL_CONFIGS) {
                    int channels = channelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
                    int minBuffer = AudioRecord.getMinBufferSize(rate, channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuffer <= 0) {
                        continue;
                    }

                    AudioRecord candidate = null;
                    try {
                        candidate = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, channelConfig,
                                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2);
                        if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                            candidate.release();
                            continue;
                        }
                        candidate.setPreferredDevice(usbInput);
                        candidate.startRecording();
                        if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            candidate.release();
                            continue;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not open AudioRecord " + rate + "/" + channels, e);
                        if (candidate != null) {
                            candidate.release();
                        }
                        continue;
                    }

                    record = candidate;
                    channelCount = channels;
                    track = openTrack(rate, channels);
                    Log.i(TAG, "Audio opened at " + rate + " Hz, " + channels + " channel(s)");
                    return true;
                }
            }

            Log.w(TAG, "No usable audio configuration");
            return false;
        }

        private void reportLevels(short[] samples, int count) {
            if (listener == null) {
                return;
            }

            float peakLeft = 0f;
            float peakRight = 0f;

            if (channelCount == 2) {
                for (int i = 0; i + 1 < count; i += 2) {
                    float left = Math.abs(samples[i]) / 32768f;
                    float right = Math.abs(samples[i + 1]) / 32768f;
                    if (left > peakLeft) {
                        peakLeft = left;
                    }
                    if (right > peakRight) {
                        peakRight = right;
                    }
                }
            } else {
                for (int i = 0; i < count; i++) {
                    float value = Math.abs(samples[i]) / 32768f;
                    if (value > peakLeft) {
                        peakLeft = value;
                    }
                }
                peakRight = peakLeft;
            }

            listener.onLevels(peakLeft, peakRight);
        }

        private void close() {
            if (record != null) {
                try {
                    record.stop();
                } catch (Exception ignored) {
                }
                record.release();
                record = null;
            }

            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                track.release();
                track = null;
            }

            Log.i(TAG, "Audio closed");
        }
    }

    private static AudioTrack openTrack(int rate, int channels) {
        int channelConfig = channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int minBuffer = AudioTrack.getMinBufferSize(rate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            return null;
        }

        try {
            AudioTrack player = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            player.play();
            return player;
        } catch (Exception e) {
            Log.w(TAG, "Could not open AudioTrack", e);
            return null;
        }
    }

    private AudioDeviceInfo findUsbInput() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    return device;
                default:
                    break;
            }
        }
        return null;
    }
}
