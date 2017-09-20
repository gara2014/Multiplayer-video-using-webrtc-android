package com.example.gara.webrtcdemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.appspot.apprtc.AppRTCAudioManager;
import org.appspot.apprtc.AppRTCAudioManager.AudioDevice;
import org.appspot.apprtc.AppRTCAudioManager.AudioManagerEvents;
import org.appspot.apprtc.PeerConnectionClient;
import org.appspot.apprtc.PercentFrameLayout;
import org.appspot.apprtc.RoomSignalClient;
import org.appspot.apprtc.RoomSignalEvents;
import org.appspot.apprtc.RoomUser;
import org.appspot.apprtc.UnhandledExceptionHandler;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Created by gara on 2017/9/16.
 */

public class MeetingActivity extends Activity implements PeerConnectionClient.PeerConnectionEvents, RoomSignalEvents {private static final String TAG = "CallRTCClient";

    private class VideoRendererItem {
        public PercentFrameLayout percentFrameLayout = null;
        public ProxyRenderer proxyRenderer = null;
        public RoomUser roomUser = null;
        public SurfaceViewRenderer surfaceViewRenderer = null;
    }

    // Fix for devices running old Android versions not finding the libraries.
    // https://bugs.chromium.org/p/webrtc/issues/detail?id=6751
    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("boringssl.cr");
            System.loadLibrary("protobuf_lite.cr");
        } catch (UnsatisfiedLinkError e) {
            Logging.w(TAG, "Failed to load native dependencies: ", e);
        }
    }
    private ArrayList<RoomUser> roomUsers = new ArrayList<RoomUser>();
    //最后一个是自己
    private ArrayList<VideoRendererItem> videoRendererItems = new ArrayList<VideoRendererItem>();
    private RoomSignalClient roomSignalClient;
    private EglBase rootEglBase;
    private AppRTCAudioManager audioManager = null;
    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"};
    private Toast logToast;
    private PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;
    private String roomId;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private SurfaceViewRenderer localRender;
    private boolean isError;
    private long callStartedTimeMs = 0;

    private class ProxyRenderer implements VideoRenderer.Callbacks {
        private VideoRenderer.Callbacks target;

        synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            target.renderFrame(frame);
        }

        synchronized public void setTarget(VideoRenderer.Callbacks target) {
            this.target = target;
        }
    }
    private PeerConnectionClient peerConnectionClient = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(this));
        setContentView(R.layout.activity_meeting);

        // Check for mandatory permissions.
        for (String permission : MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission " + permission + " is not granted");
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }

        roomId = getIntent().getStringExtra("roomId");

        peerConnectionParameters =
                new PeerConnectionClient.PeerConnectionParameters(true, false/*loopback*/,
                        false/*tracing*/, 0/*videoWidth*/, 0/*videoHeight*/, 0/*videofps*/,
                        0/*video bitrate*/, null/*intent.getStringExtra(EXTRA_VIDEOCODEC)*/,
                        true/*HWCODEC_ENABLED*/,
                        false/*FLEXFEC_ENABLED*/,
                        0/*audio bitrate*/, null/*audiocodec*/,
                        false/*intent.getBooleanExtra(EXTRA_NOAUDIOPROCESSING_ENABLED, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_AECDUMP_ENABLED, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_OPENSLES_ENABLED, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AEC, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AGC, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_NS, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_ENABLE_LEVEL_CONTROL, false)*/,
                        false/*intent.getBooleanExtra(EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, false)*/, null/*dataChannelParameters*/);

        roomSignalClient = new RoomSignalClient(this);
        rootEglBase = EglBase.create();
        createView();

        peerConnectionClient = new PeerConnectionClient(executor);
        VideoCapturer videoCapturer = null;
        if (peerConnectionParameters.videoCallEnabled) {
            videoCapturer = createVideoCapturer();
        }
        peerConnectionClient.createPeerConnectionFactory(
                getApplicationContext(), peerConnectionParameters, MeetingActivity.this,
                rootEglBase.getEglBaseContext(), localRender, videoCapturer);
        startCall();
    }

    // Activity interfaces
    @Override
    public void onStop() {
        super.onStop();
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        if (peerConnectionClient != null) {
            peerConnectionClient.stopVideoSource();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Video is not paused for screencapture. See onPause.
        if (peerConnectionClient != null) {
            peerConnectionClient.startVideoSource();
        }
    }

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        disconnect();
        if (logToast != null) {
            logToast.cancel();
        }
        rootEglBase.release();
        super.onDestroy();
    }

    private void createView() {
        //gara test only
        roomUsers.add(new RoomUser());
        roomUsers.add(new RoomUser());
        roomUsers.add(new RoomUser());

        RelativeLayout relativeLayout = (RelativeLayout)findViewById(R.id.video_container);
        for(int i = 0; i < roomUsers.size(); i++) {
            PercentFrameLayout percentFrameLayout = new PercentFrameLayout(this);
            ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            percentFrameLayout.setLayoutParams(layoutParams);
            relativeLayout.addView(percentFrameLayout);
            //create render
            SurfaceViewRenderer surfaceViewRenderer = new SurfaceViewRenderer(this);
            surfaceViewRenderer.init(rootEglBase.getEglBaseContext(), null);
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            surfaceViewRenderer.setEnableHardwareScaler(true /* enabled */);

            layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            surfaceViewRenderer.setLayoutParams(layoutParams);
            percentFrameLayout.addView(surfaceViewRenderer);
            ProxyRenderer proxyRenderer = new ProxyRenderer();
            proxyRenderer.setTarget(surfaceViewRenderer);
            if (i == roomUsers.size() - 1) {
                localRender = surfaceViewRenderer;
            }
            VideoRendererItem videoRendererItem = new VideoRendererItem();
            videoRendererItem.roomUser = roomUsers.get(i);
            videoRendererItem.proxyRenderer = proxyRenderer;
            videoRendererItem.percentFrameLayout = percentFrameLayout;
            videoRendererItem.surfaceViewRenderer = surfaceViewRenderer;
            videoRendererItems.add(videoRendererItem);
        }
        if (roomUsers.size() == 2) {
            PercentFrameLayout percentFrameLayout = videoRendererItems.get(0).percentFrameLayout;
            percentFrameLayout.setPosition(0, 0, 100, 100);
            percentFrameLayout = videoRendererItems.get(1).percentFrameLayout;
            percentFrameLayout.setPosition(52, 52, 48, 48);
        } else if(roomUsers.size() == 3 || roomUsers.size() == 4) {
            for(int i = 0 ; i < videoRendererItems.size(); i++) {
                PercentFrameLayout percentFrameLayout = videoRendererItems.get(i).percentFrameLayout;
                percentFrameLayout.setPosition(50 * (i % 2) + 1, 50 * ((int)(i / 2)) + 1, 48, 48);
            }
        } else if(roomUsers.size() == 5 || roomUsers.size() == 6) {
            for(int i = 0 ; i < videoRendererItems.size(); i++) {
                PercentFrameLayout percentFrameLayout = videoRendererItems.get(i).percentFrameLayout;
                percentFrameLayout.setPosition(33 * (i % 3) + 1, 50 * ((int)(i / 3)) + 1, 32, 48);
            }
        }
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer = null;
        if (useCamera2()) {
            if (!captureToTexture()) {
                reportError(getString(R.string.camera2_texture_only_error));
                return null;
            }

            Logging.d(TAG, "Creating capturer using camera2 API.");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(captureToTexture()));
        }
        if (videoCapturer == null) {
            reportError("Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

    private boolean captureToTexture() {
        return true;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private void startCall() {
        if (roomSignalClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.");
            return;
        }
        callStartedTimeMs = System.currentTimeMillis();

        // Start room connection.
        logAndToast(getString(R.string.connecting_to, ""));
        //appRtcClient.connectToRoom(roomConnectionParameters);
        roomSignalClient.joinRoom(roomId);

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        audioManager = AppRTCAudioManager.create(getApplicationContext());
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Log.d(TAG, "Starting the audio manager...");
        audioManager.start(new AudioManagerEvents() {
            // This method will be called each time the number of available audio
            // devices has changed.
            @Override
            public void onAudioDeviceChanged(
                    AudioDevice audioDevice, Set<AudioDevice> availableAudioDevices) {
                onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            }
        });
    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg) {
        Log.d(TAG, msg);
        if (logToast != null) {
            logToast.cancel();
        }
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        logToast.show();
    }

    private void reportError(final String description) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isError) {
                    isError = true;
                    disconnectWithErrorMessage(description);
                }
            }
        });
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private void onAudioManagerDevicesChanged(
            final AudioDevice device, final Set<AudioDevice> availableDevices) {
        Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", "
                + "selected: " + device);
        // TODO(henrika): add callback handler.
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        if(videoRendererItems != null) {
            for(int i = 0; i < videoRendererItems.size(); i++) {
                VideoRendererItem videoRendererItem = videoRendererItems.get(i);
                if(videoRendererItem.proxyRenderer != null) {
                    videoRendererItem.proxyRenderer.setTarget(null);
                }
                if(videoRendererItem.surfaceViewRenderer != null) {
                    videoRendererItem.surfaceViewRenderer.release();
                    videoRendererItem.surfaceViewRenderer = null;
                }
            }
        }
        videoRendererItems.clear();
        if (roomSignalClient != null) {
            roomSignalClient.stopGetSdpCandidate();
            roomSignalClient.leaveRoom(roomId);
            roomSignalClient = null;
        }
        if (peerConnectionClient != null) {
            peerConnectionClient.close();
            peerConnectionClient = null;
        }
        localRender = null;
        if (audioManager != null) {
            audioManager.stop();
            audioManager = null;
        }
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        new AlertDialog.Builder(this)
                .setTitle(getText(R.string.channel_error_title))
                .setMessage(errorMessage)
                .setCancelable(false)
                .setNeutralButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        })
                .create()
                .show();
    }

    private SurfaceViewRenderer getRemoteRender(int userId) {
        for(int i = 0; i < videoRendererItems.size(); i++) {
            VideoRendererItem videoRendererItem = videoRendererItems.get(i);
            if (videoRendererItem.roomUser == null || videoRendererItem.roomUser.userId == 0
                    || videoRendererItem.roomUser.userId == userId) {
                return videoRendererItem.surfaceViewRenderer;
            }
        }
        return null;
    }

    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    @Override
    public void onLocalDescription(final SessionDescription sdp, final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (roomSignalClient != null) {
                    logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
                    roomSignalClient.sendSdp(sdp, peerConnectionNode.isInitiator, peerConnectionNode.remoteUserId);
                }

                if (peerConnectionParameters.videoMaxBitrate > 0) {
                    Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters.videoMaxBitrate);
                    peerConnectionClient.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate, peerConnectionNode);
                }
            }
        });
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate, final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (roomSignalClient != null) {
                    IceCandidate candidates[] = new IceCandidate[1];
                    candidates[0] = candidate;
                    roomSignalClient.sendCandidate(candidates, false, peerConnectionNode.isInitiator, peerConnectionNode.remoteUserId);
                }
            }
        });
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates, final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (roomSignalClient != null) {
                    roomSignalClient.sendCandidate(candidates, true, peerConnectionNode.isInitiator, peerConnectionNode.remoteUserId);
                }
            }
        });
    }

    @Override
    public void onIceConnected(final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE connected, delay=" + delta + "ms");
                //iceConnected = true;
                //callConnected(peerConnectionNode);
            }
        });
    }

    @Override
    public void onIceDisconnected(final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE disconnected");
                if (peerConnectionClient != null) {
                    peerConnectionClient.closeNode(peerConnectionNode);
                }
            }
        });
    }

    @Override
    public void onPeerConnectionClosed(final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (peerConnectionNode != null) {
                    for(int i = 0; i < peerConnectionNode.remoteRenders.size(); i++) {
                        VideoRenderer.Callbacks cb = peerConnectionNode.remoteRenders.get(i);
                        if (cb != null && cb instanceof SurfaceViewRenderer) {
                            ((SurfaceViewRenderer) cb).clearImage();
                        }
                    }
                    peerConnectionNode.remoteRenders = null;
                }
                if (peerConnectionClient == null || peerConnectionClient.peerConnectionNodes == null
                        || peerConnectionClient.peerConnectionNodes.size() == 0) {
                    //iceConnected = false;
                    //disconnect();
                }
            }
        });
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports, final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
    }

    @Override
    public void onPeerConnectionError(final String description, final PeerConnectionClient.PeerConnectionNode peerConnectionNode) {
        reportError(description);
    }

    @Override
    public void onJoinRoom(final int error, final int myUserId, final List<RoomUser> other) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (error == 0) {
                    //isInitiator = (other != null && other.size() > 0);
                    if (other != null && other.size() > 0) {
                        //remoteUserId = ru.userId;
                    }
                    if (other != null) {
                        for (int i = 0; i < other.size(); i++) {
                            RoomUser ru = other.get(i);
                            final long delta = System.currentTimeMillis() - callStartedTimeMs;

                            logAndToast("Creating peer connection, delay=" + delta + "ms");
                            SurfaceViewRenderer remoteRender = getRemoteRender(ru.userId);
                            if (remoteRender != null) {
                                peerConnectionClient.createPeerConnection(remoteRender, true, ru.userId);
                                logAndToast("Creating OFFER...");
                                // Create offer. Offer SDP will be sent to answering client in
                                // PeerConnectionEvents.onLocalDescription event.
                                peerConnectionClient.createOffer(ru.userId);
                            }
                        }
                    }

                    if (roomSignalClient != null) {
                        roomSignalClient.startGetSdpCandidate();
                    }
                } else {
                    logAndToast("room is full");
                }
            }
        });
    }

    @Override
    public void onSendSdp(final int error) {

    }

    @Override
    public void onSendCandidate(final int error) {

    }

    @Override
    public void onGetSdp(final int srcUserId, final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                    return;
                }
                logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
                //remoteUserId = srcUserId;
                PeerConnectionClient.PeerConnectionNode peerConnectionNode = peerConnectionClient.getPeerConnectionNode(srcUserId);

                boolean isInitiator = true;
                if (peerConnectionNode == null) {

                    final long delta = System.currentTimeMillis() - callStartedTimeMs;

                    logAndToast("Creating peer connection, delay=" + delta + "ms");

                    SurfaceViewRenderer remoteRender = getRemoteRender(srcUserId);
                    if (remoteRender != null) {
                        peerConnectionClient.createPeerConnection(remoteRender, false, srcUserId);
                    }
                    isInitiator = false;
                }
                peerConnectionClient.setRemoteDescription(sdp, srcUserId);

                logAndToast("Creating ANSWER...");
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                if (!isInitiator) {
                    peerConnectionClient.createAnswer(srcUserId);
                }
            }
        });
    }

    @Override
    public void onGetCandidate(final int srcUserId, final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                    return;
                }
                //remoteUserId = srcUserId;
                PeerConnectionClient.PeerConnectionNode peerConnectionNode = peerConnectionClient.getPeerConnectionNode(srcUserId);
                if (peerConnectionNode == null) {

                    final long delta = System.currentTimeMillis() - callStartedTimeMs;

                    logAndToast("Creating peer connection, delay=" + delta + "ms");

                    SurfaceViewRenderer remoteRender = getRemoteRender(srcUserId);
                    if (remoteRender != null) {
                        peerConnectionClient.createPeerConnection(remoteRender, false, srcUserId);
                    }
                }
                peerConnectionClient.addRemoteIceCandidate(candidate, srcUserId);
            }
        });
    }

    @Override
    public void onGetCandidateRemoved(final int srcUserId, final IceCandidate candidate) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                    return;
                }
                //remoteUserId = srcUserId;
                PeerConnectionClient.PeerConnectionNode peerConnectionNode = peerConnectionClient.getPeerConnectionNode(srcUserId);
                if (peerConnectionNode != null) {
                    IceCandidate candidates[] = new IceCandidate[1];
                    candidates[0] = candidate;
                    peerConnectionClient.removeRemoteIceCandidates(candidates, srcUserId);
                }
            }
        });
    }

    @Override
    public void onDisJoinRoom(final int error) {

    }
}
