package cn.qiuxiang.react.recording;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;

class RecordingModule extends ReactContextBaseJavaModule {
    private static AudioRecord audioRecord;
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;
    private boolean running;
    private int bufferSize;
    private Thread recordingThread;
    private Thread recordingDownsampleThread;
    private Thread recordingDemodulateThread;
    private Thread recordingCountEventsThread;

    RecordingModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "Recording";
    }

    @ReactMethod
    public void init(ReadableMap options) {
        if (eventEmitter == null) {
            eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
        }

        if (running || (recordingThread != null && recordingThread.isAlive())) {
            return;
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }

        // for parameter description, see
        // https://developer.android.com/reference/android/media/AudioRecord.html

        int sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channelsPerFrame")) {
            int channelsPerFrame = options.getInt("channelsPerFrame");

            // every other case --> CHANNEL_IN_MONO
            if (channelsPerFrame == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        // we support only 8-bit and 16-bit PCM
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerChannel")) {
            int bitsPerChannel = options.getInt("bitsPerChannel");

            if (bitsPerChannel == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        if (options.hasKey("bufferSize")) {
            this.bufferSize = options.getInt("bufferSize");
        } else {
            this.bufferSize = 8192;
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                this.bufferSize * 2);

        recordingThread = new Thread(new Runnable() {
            public void run() {
                recording();
            }
        }, "RecordingThread");

        recordingDownsampleThread = new Thread(new Runnable() {
            public void run() {
                recordingDownsample();
            }
        }, "RecordingDownsampleThread");

        recordingDemodulateThread = new Thread(new Runnable() {
            public void run() {
                recordingDemodulate();
            }
        }, "RecordingdemodulatedThread");

        recordingCountEventsThread = new Thread(new Runnable() {
            public void run() {
                recordingCountEvents();
            }
        }, "RecordingCountEventsThread");
    }

    @ReactMethod
    public void startDemodulate() { //this is original start
        if (!running && audioRecord != null && recordingThread != null) {
            running = true;
            audioRecord.startRecording();
            recordingThread.start();
        }
    }

    @ReactMethod
    public void startDownsample() { 
        if (!running && audioRecord != null && recordingThread != null) {
            running = true;
            audioRecord.startRecording();
            recordingDownsampleThread.start();
        }
    }

    @ReactMethod
    public void start() { //this is start Demodulate
        if (!running && audioRecord != null && recordingThread != null) {
            running = true;
            audioRecord.startRecording();
            recordingDemodulateThread.start();
        }
    }

    @ReactMethod
    public void startCountEvents() {
        if (!running && audioRecord != null && recordingThread != null) {
            running = true;
            audioRecord.startRecording();
            recordingCountEventsThread.start();
        }
    }

    @ReactMethod
    public void stop() {
        if (audioRecord != null) {
            running = false;
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void recording() {
        short buffer[] = new short[bufferSize];
        while (running && !reactContext.getCatalystInstance().isDestroyed()) {
            WritableArray data = Arguments.createArray();
            audioRecord.read(buffer, 0, bufferSize);
            for (float value : buffer) {
                data.pushInt((int) value);
            }
            eventEmitter.emit("recording", data);
        }
    }

    private void recordingDownsample() {
        int oneSecondBufferSize = audioRecord.getChannelCount() * audioRecord.getSampleRate();
        short buffer[] = new short[oneSecondBufferSize];
        while (running && !reactContext.getCatalystInstance().isDestroyed()) {
            audioRecord.read(buffer, 0, oneSecondBufferSize);
            downsample(buffer, true);
        }
    }

    private void recordingDemodulate() {
        int oneSecondBufferSize = audioRecord.getChannelCount() * audioRecord.getSampleRate();
        short buffer[] = new short[oneSecondBufferSize];
        while (running && !reactContext.getCatalystInstance().isDestroyed()) {
            audioRecord.read(buffer, 0, oneSecondBufferSize);
            short downsampleData[] = downsample(buffer, false);
            demodulate(downsampleData, true);

        }
    }

    private void recordingCountEvents() {
        int oneSecondBufferSize = audioRecord.getChannelCount() * audioRecord.getSampleRate();
        short buffer[] = new short[oneSecondBufferSize];
        short oneMinDemodulateData[] = new short[oneSecondBufferSize / 32 / 138 * 60];
        int oneMinArrayIndex = 0;
        int secondsCount = 0;
        while (running && !reactContext.getCatalystInstance().isDestroyed()) {
            audioRecord.read(buffer, 0, oneSecondBufferSize);

            short downsampleData[] = downsample(buffer, false);
            short demodulateData[] = demodulate(downsampleData, false);
            for (int i = 0; i < demodulateData.length; i++) {
                oneMinDemodulateData[oneMinArrayIndex] = downsampleData[i];
                oneMinArrayIndex++;
            }
            secondsCount++;
            if(secondsCount == 60) {
                secondsCount = 0;
                oneMinArrayIndex = 0;
                countEvents(oneMinDemodulateData);
            }

        }
    }

    private short[] downsample(short data[], boolean shouldEmitEvent) {
        final int factor = 32;
        int every = data.length / factor;
        short downsampleData[] = new short[every];
            WritableArray arr = Arguments.createArray();
            for (int i = 0; i < every; i++) {
                if (shouldEmitEvent) {
                    arr.pushInt(data[i * factor]);
                }
                downsampleData[i] = data[i * factor];
            }
        if (shouldEmitEvent) {
            eventEmitter.emit("downsampled", arr);
        }

        return downsampleData;
    }

    private short[] demodulate(short data[], boolean shouldEmitEvent) {
        WritableArray demodulatedData = Arguments.createArray();
        int index = 0;
        short max = 0;
        short demodulData[] = new short[data.length/138];

        for (int i = 0; i < data.length; i++) {
            if (index == 138) {
                if (shouldEmitEvent) {
                    demodulatedData.pushInt(max);
                }
                demodulData[i/138 - 1] = max;
                max = 0;
                index = 0;
            }
            max = data[i] > max ? data[i] : max;
            index++;
        }
        if (shouldEmitEvent) {
            eventEmitter.emit("demodulated", demodulatedData);
        }
        return demodulData;
    }

    private enum EventState {
        FIND_FIRST(0),
        WAIT(1),
        FIND_EVENT_START(2),
        EVENT_TOP(3);

        EventState(int i) {
            this.type = i;
        }

        private int type;

        public int getNumericType() {
            return type;
        }
    }

    private void countEvents(short demodulatedData[]) {

        final double thresholdDecayRate = 0.985;  //threshold decay rate per sample
        final int holdCnt = 40;      //number of samples processed in hold-off State
        final int stateTwoLimit = 80;  //after STATE2LIMIT samples have occured reset the signal max value
        final int eventSize = 400;
        final int initialThreshold = 200;

        EventState currentState = EventState.FIND_FIRST;
        int threshold = initialThreshold;
        int eventCount = 0;
        int maxSig = 0, minSig = 0, hold = 0, stateTwoCnt = 0;

        for(int i = 0; i < demodulatedData.length; i++) {
            int value = demodulatedData[i];
            switch (currentState) {
                case FIND_FIRST:
                    if (value > threshold) {
                        threshold = value;
                        eventCount++;
                        currentState = EventState.WAIT;
                        maxSig = 0;
                        minSig = 10000;
                        hold = 0;
                    }
                    break;
                case WAIT:
                    if ((hold > holdCnt - 3) && (value > maxSig)) { maxSig = value; }
                    if (value < minSig) { minSig = value; }

                    hold++;

                    if (hold > holdCnt) { currentState = EventState.FIND_EVENT_START; }

                    stateTwoCnt = 0;

                    if (value > threshold) { threshold = value; }
                    threshold = threshold < initialThreshold ? initialThreshold : (int)(threshold * thresholdDecayRate);
                    break;
                case FIND_EVENT_START:
                    stateTwoCnt++;
                    if (stateTwoCnt > stateTwoLimit) {
                        maxSig = 0;
                        currentState = EventState.FIND_FIRST;
                    } else {
                        if (value > maxSig) { maxSig = value; }
                        if (value < minSig) { minSig = value; }
                        if (value < threshold) {
                            threshold = threshold < initialThreshold ? initialThreshold : (int)(threshold * thresholdDecayRate);
                        } else {
                            threshold = value;
                            currentState = EventState.EVENT_TOP;
                        }
                    }
                    break;
                case EVENT_TOP:
                    if (value > maxSig) { maxSig = value; }
                    if (value < minSig) { minSig = value; }
                    if (value > threshold) {
                        threshold = value;
                    } else {
                        if (maxSig - minSig >= eventSize) { eventCount++; }

                        currentState = EventState.WAIT;
                        maxSig = 0;
                        minSig = 10000;
                        hold = 0;
                    }
                    break;
                default:
                    break;
            }
        }
        eventEmitter.emit("countEvents", eventCount);
    }

}
