/*
  The goal of this fork is to allow recording from a bluetooth microphone on Android. Note that
  we're force to use SCO which limits the sample-rate to an abysmal 8 kHz. Things will change
  when BLE Audio becomes available on more devices.

  changes in the forked (record_bt) version:
    - added permissions in AndroidManifest.xml
      - legacy (SDK<30): BLUETOOTH, BLUETOOTH_ADMIN
      - BLUETOOTH_CONNECT, MODIFY_AUDIO_SETTINGS
      - from SDK>30, BLUETOOTH_CONNECT has to be requested explicitly


 */

package com.llfbandit.record;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class MethodCallHandlerImpl implements
    MethodCallHandler,
    EventChannel.StreamHandler,
    PluginRegistry.RequestPermissionsResultListener {

  private static final int RECORD_AUDIO_REQUEST_CODE = 1001;
  private static final int BLUETOOTH_CONNECT_REQUEST_CODE = 1002;

  private static final int RECORD_STATE_PAUSE = 0;
  private static final int RECORD_STATE_RECORD = 1;
  private static final int RECORD_STATE_STOP = 2;

  private final Activity activity;
  private RecorderBase recorder;
  private Result pendingPermResult;
  // Event producer
  private EventChannel.EventSink eventSink;

  MethodCallHandlerImpl(Activity activity) {
    this.activity = activity;
  }

  void close() {
    if (recorder != null) {
      recorder.close();
    }
    pendingPermResult = null;
  }

  @Override
  @SuppressWarnings("ConstantConditions")
  public void onMethodCall(MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "start":
        String path = call.argument("path");

        if (path == null) {
          path = genTempFileName(result);
          if (path == null) return;
        }

        String encoder = call.argument("encoder");
        int bitRate = call.argument("bitRate");
        int samplingRate = call.argument("samplingRate");
        int numChannels = call.argument("numChannels");
        Map<String, Object> device = call.argument("device");

        recorder = selectRecorder(encoder);

        try {
          recorder.start(path, encoder, bitRate, samplingRate, numChannels, device);
          result.success(null);
          sendStateEvent(RECORD_STATE_RECORD);
        } catch (Exception e) {
          result.error("-1", e.getMessage(), e.getCause());
        }
        break;
      case "stop":
        if (recorder != null) {
          try {
            result.success(recorder.stop());
            sendStateEvent(RECORD_STATE_STOP);
          } catch (Exception e) {
            result.error("-2", e.getMessage(), e.getCause());
          }
        } else {
          result.success(null);
        }
        break;
      case "pause":
        if (recorder != null) {
          try {
            recorder.pause();
            result.success(null);
            sendStateEvent(RECORD_STATE_PAUSE);
          } catch (Exception e) {
            result.error("-3", e.getMessage(), e.getCause());
          }
        } else {
          result.success(null);
        }
        break;
      case "resume":
        if (recorder != null) {
          try {
            recorder.resume();
            result.success(null);
            sendStateEvent(RECORD_STATE_RECORD);
          } catch (Exception e) {
            result.error("-4", e.getMessage(), e.getCause());
          }
        } else {
          result.success(null);
        }
        break;
      case "isPaused":
        if (recorder != null) {
          result.success(recorder.isPaused());
        } else {
          result.success(false);
        }
        break;
      case "isRecording":
        if (recorder != null) {
          result.success(recorder.isRecording());
        } else {
          result.success(false);
        }
        break;
      case "hasPermission":
        hasPermission(result);
        break;
      case "getAmplitude":
        if (recorder != null) {
          result.success(recorder.getAmplitude());
        } else {
          result.success(null);
        }
        break;
      case "listInputDevices":
        result.success(null);
        break;
      case "dispose":
        close();
        result.success(null);
        break;
      case "isEncoderSupported":
        String codec = call.argument("encoder");
        RecorderBase rec = selectRecorder(codec);

        boolean isSupported = rec.isEncoderSupported(codec);
        result.success(isSupported);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  /// EventChannel.StreamHandler
  ///
  @Override
  public void onListen(Object o, EventChannel.EventSink eventSink) {
    this.eventSink = eventSink;
  }

  @Override
  public void onCancel(Object o) {
    eventSink = null;
  }
  ///
  /// END EventChannel.StreamHandler
  /////////////////////////////////////////////////////////////////////////////

  @Override
  public boolean onRequestPermissionsResult(
      int requestCode,
      @NonNull String[] permissions,
      @NonNull int[] grantResults
  ) {
    if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
      if (pendingPermResult != null) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          pendingPermResult.success(true);
        } else {
          pendingPermResult.success(false);
        }
        pendingPermResult = null;
        return true;
      }
    }

    return false;
  }

  private void hasPermission(@NonNull Result result) {
    if (!isPermissionGranted()) {
      pendingPermResult = result;
      askForPermission();
    } else {
      result.success(true);
    }
  }

  private boolean isPermissionGranted() {
    int result = ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO);
    return result == PackageManager.PERMISSION_GRANTED;
  }

  private void askForPermission() {
    ActivityCompat.requestPermissions(
        activity,
        new String[]{Manifest.permission.RECORD_AUDIO},
        MethodCallHandlerImpl.RECORD_AUDIO_REQUEST_CODE
    );
  }

  private RecorderBase selectRecorder(String encoder) {
    RecorderBase r = new AudioRecorder();
    if (r.isEncoderSupported(encoder)) {
      return r;
    }

    r = new MediaRecorder(activity);
    if (r.isEncoderSupported(encoder)) {
      return r;
    }

    return null;
  }

  private String genTempFileName(@NonNull Result result) {
    File outputDir = activity.getCacheDir();
    File outputFile;

    try {
      outputFile = File.createTempFile("audio", ".m4a", outputDir);
      return outputFile.getPath();
    } catch (IOException e) {
      result.error("record", "Cannot create temp file.", e.getMessage());
      e.printStackTrace();
    }

    return null;
  }

  private void sendStateEvent(int state) {
    if (eventSink != null) eventSink.success(state);
  }

  /* map int received from EXTRA_CONNECTION_STATE to string*/
  private Map<Integer, String> strConnectionState = new HashMap<Integer, String>() {{
    put(0, "disconnected");
    put(1, "connecting");
    put(2, "connected");
    put(3, "disconnecting");
    put(10, "turned off");
    put(12, "turned on");
    put(13, "turning off");
    put(11, "turning on");
  }};

  private BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
    /*
        handle all events connected to Bluetooth devices.
     */
    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, @NonNull Intent intent) {
      Bundle extras = intent.getExtras();
      switch (intent.getAction()) {
        case BluetoothAdapter.ACTION_STATE_CHANGED:
          if (extras == null) {
            return;
          }
          // print some debug info
          final int state = extras.getInt(BluetoothAdapter.EXTRA_STATE);
          Log.d(LOG_TAG, "Bluetooth connection changed to state: "
                  + strConnectionState.get(state));
          // if BT was turned off, revoke permissions
          if (strConnectionState.get(state) == "turned off") {
            bluetoothConnectionPermitted = false;
            // stop current recording
            if (isRecording) {
              isIncompleteRecording = true;
              recordButton.performClick();
            }
          }
          break;

        case BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED:
          if (extras == null)
            return;
          final BluetoothDevice device = extras.getParcelable(BluetoothDevice.EXTRA_DEVICE);
          final int connectionState = extras.getInt(BluetoothAdapter.EXTRA_CONNECTION_STATE);
          // print some debug info
          System.out.printf("DEVICE: %s", device);
          Log.d(LOG_TAG, "Bluetooth connection: " + device.getName() +
                  " [" + device.getAddress() + "] changed to state: "
                  + strConnectionState.get(connectionState));

          // if last device is disconnected, revoke permission
          if (Objects.equals(strConnectionState.get(connectionState),
                  "disconnected")) {
            // check if there's still another headset connected
            if (!isBluetoothDeviceConnected()) {
              bluetoothConnectionPermitted = false;
              // stop current recording
              if (isRecording) {
                isIncompleteRecording = true;
                recordButton.performClick();
              }
            }
            // if a new headset is connected, grant permission
            // NOTE: using bluetoothConnectionPermitted here is a bit odd,
            //       but it should be fine since we would never get here
            //       if we didn't have the BLUETOOTH_CONNECT permission
          } else if (Objects.equals(strConnectionState.get(connectionState),
                  "connected")) {
            if (isBluetoothDeviceConnected()) {
              bluetoothConnectionPermitted = true;
            }
          }

          break;

        default:
          String actUnhandled = String.format("Received unhandled action: %",
                  intent.getAction());
          Log.w(LOG_TAG, actUnhandled);
      }
    }
  };
}
