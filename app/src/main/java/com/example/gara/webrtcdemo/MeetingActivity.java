package com.example.gara.webrtcdemo;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.appspot.apprtc.PeerConnectionClient;
import org.appspot.apprtc.PercentFrameLayout;
import org.appspot.apprtc.RoomSignalClient;
import org.appspot.apprtc.RoomSignalEvents;
import org.appspot.apprtc.RoomUser;
import org.appspot.apprtc.UnhandledExceptionHandler;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by gara on 2017/9/16.
 */

public class MeetingActivity extends Activity implements PeerConnectionClient.PeerConnectionEvents, RoomSignalEvents {private static final String TAG = "CallRTCClient";

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
    private int videoCount = 2;
    private ArrayList<PercentFrameLayout> percentFrameLayouts = new ArrayList<PercentFrameLayout>();
    private RoomSignalClient roomSignalClient;
    private EglBase rootEglBase;
    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"};
    private Toast logToast;
    private PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;

    private String roomId;

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
    private ArrayList<ProxyRenderer> proxyRenderers = new ArrayList<ProxyRenderer>();
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
    }

    private void createView() {
        RelativeLayout relativeLayout = (RelativeLayout)findViewById(R.id.video_container);
        for(int i = 0; i < videoCount; i++) {
            PercentFrameLayout percentFrameLayout = new PercentFrameLayout(this);
            ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            percentFrameLayout.setLayoutParams(layoutParams);
            relativeLayout.addView(percentFrameLayout);
            percentFrameLayouts.add(percentFrameLayout);
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
            proxyRenderers.add(proxyRenderer);
        }
        if (videoCount == 2) {
            PercentFrameLayout percentFrameLayout = percentFrameLayouts.get(0);
            percentFrameLayout.setPosition(0, 0, 100, 100);
            percentFrameLayout = percentFrameLayouts.get(1);
            percentFrameLayout.setPosition(52, 52, 48, 48);
        } else if(videoCount == 3 || videoCount == 4) {
            for(int i = 0 ; i < percentFrameLayouts.size(); i++) {
                PercentFrameLayout percentFrameLayout = percentFrameLayouts.get(i);
                percentFrameLayout.setPosition(50 * (i % 2) + 1, 50 * ((int)(i / 2)) + 1, 48, 48);
            }
        } else if(videoCount == 5 || videoCount == 6) {
            for(int i = 0 ; i < percentFrameLayouts.size(); i++) {
                PercentFrameLayout percentFrameLayout = percentFrameLayouts.get(i);
                percentFrameLayout.setPosition(33 * (i % 3) + 1, 50 * ((int)(i / 3)) + 1, 32, 48);
            }
        }
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

    @Override
    public void onLocalDescription(SessionDescription sdp, PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onIceCandidate(IceCandidate candidate, PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] candidates, PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onIceConnected(PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onIceDisconnected(PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onPeerConnectionClosed(PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onPeerConnectionStatsReady(StatsReport[] reports, PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onPeerConnectionError(String description, PeerConnectionClient.PeerConnectionNode peerConnectionNode) {

    }

    @Override
    public void onJoinRoom(int error, int myUserId, List<RoomUser> other) {

    }

    @Override
    public void onSendSdp(int error) {

    }

    @Override
    public void onSendCandidate(int error) {

    }

    @Override
    public void onGetSdp(int srcUserId, SessionDescription sdp) {

    }

    @Override
    public void onGetCandidate(int srcUserId, IceCandidate candidate) {

    }

    @Override
    public void onGetCandidateRemoved(int srcUserId, IceCandidate candidate) {

    }

    @Override
    public void onDisJoinRoom(int error) {

    }
}
