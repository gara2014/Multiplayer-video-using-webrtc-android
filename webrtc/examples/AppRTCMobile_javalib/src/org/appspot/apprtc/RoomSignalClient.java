package org.appspot.apprtc;

import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.webrtc.SessionDescription.Type.ANSWER;
import static org.webrtc.SessionDescription.Type.OFFER;


public class RoomSignalClient {
    private RoomSignalEvents events;
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final String SignalUrl = "http://192.168.1.104:8080/";
    private static final String TAG = "RoomSignalClient";
    private final ScheduledExecutorService executor;
    private int myUserId;
    private int remoteUserId;
    private String roomName;
    private Handler handler;
    private Runnable runnable;
    public RoomSignalClient(RoomSignalEvents events) {
        this.events = events;
        executor = Executors.newSingleThreadScheduledExecutor();
        handler = new Handler();
        runnable = new Runnable(){
            public void run() {getSdpCandidate();
            }
        };
    }

    public void joinRoom(final String roomName) {
        this.roomName = roomName;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject postJson = new JSONObject();
                try {
                    postJson.put("room_name", roomName);
                    JSONObject responseJson = postRequest("join", postJson);
                    if (responseJson != null) {
                        int error = responseJson.getInt("error");
                        if (error == 0) {
                            int myUid = responseJson.getInt("my_user_id");
                            JSONArray otherUserArray = responseJson.getJSONArray("other_user_id");
                            List<RoomUser> roomUserList = new LinkedList<>();
                            for (int i = 0; i < otherUserArray.length(); i++) {
                                JSONObject obj = (JSONObject) otherUserArray.get(i);
                                int uid = obj.getInt("room_user_id");
                                RoomUser ru = new RoomUser();
                                ru.userId = uid;
                                roomUserList.add(ru);
                            }
                            myUserId = myUid;
                            events.onJoinRoom(error, myUid, roomUserList);
                        } else {
                            events.onJoinRoom(error, 0, null);
                        }
                    } else {
                        events.onJoinRoom(-1, 0, null);
                    }
                } catch (JSONException e) {
                    Log.d(TAG, "joinRoom:" + e.toString());
                }
            }
        });
    }

    public void leaveRoom(final String roomname) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject postJson = new JSONObject();
                try {
                    postJson.put("room_name", roomname);
                    postJson.put("src_user_id", myUserId);
                    JSONObject responseJson = postRequest("leave", postJson);
                    int error = responseJson.getInt("error");
                    roomName = null;
                    myUserId = 0;

                    events.onDisJoinRoom(error);
                } catch (JSONException e) {
                    Log.d(TAG, "disjoinRoom:" + e.toString());
                }
            }
        });
    }

    public void sendSdp(final SessionDescription sdp,
                         final boolean isInitiator, final int dstUserId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject postJson = new JSONObject();
                try {
                    postJson.put("src_user_id", myUserId);
                    postJson.put("dst_user_id", dstUserId);
                    postJson.put("room_name", roomName);
                    postJson.put("is_initiator", isInitiator ? 1 : 0);
                    postJson.put("sdp_type", sdp.type == OFFER ? "offer" : "answer");
                    postJson.put("sdp_description", sdp.description);
                    JSONObject responseJson = postRequest("sdp", postJson);
                    int error = responseJson.getInt("error");
                    events.onSendSdp(error);
                } catch (JSONException e) {
                    Log.d(TAG, "sendSdp:" + e.toString());
                }
            }
        });
    }

    public void sendCandidate(final IceCandidate[] candidates, final boolean isRemoved,
                               final boolean isInitiator, final int dstUserId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject postJson = new JSONObject();
                JSONArray candidateJsonArray = new JSONArray();
                try {
                    for (int i = 0; i < candidates.length; i++) {
                        IceCandidate candidate = candidates[i];
                        JSONObject candidateJson = new JSONObject();
                        candidateJson.put("added_or_removed", isRemoved ? 1 : 0);
                        candidateJson.put("src_user_id", myUserId);
                        candidateJson.put("dst_user_id", dstUserId);
                        candidateJson.put("room_name", roomName);
                        candidateJson.put("is_initiator", isInitiator ? 1 : 0);
                        candidateJson.put("ice_sdp_mid", candidate.sdpMid);
                        candidateJson.put("ice_sdp", candidate.sdp);
                        candidateJson.put("ice_sdp_m_line_index", candidate.sdpMLineIndex);
                        candidateJsonArray.put(candidateJson);
                    }
                    postJson.put("candidates", candidateJsonArray);
                    JSONObject responseJson = postRequest("candidate", postJson);
                    int error = responseJson.getInt("error");
                    events.onSendCandidate(error);
                } catch (JSONException e) {
                    Log.d(TAG, "sendCandidate:" + e.toString());
                }
            }
        });
    }

    public void startGetSdpCandidate() {
        getSdpCandidate();
    }

    public void stopGetSdpCandidate() {
        handler.removeCallbacks(runnable);
    }

    private void getSdpCandidate() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject postJson = new JSONObject();
                try {
                    postJson.put("dst_user_id", myUserId);
                    postJson.put("room_name", roomName);
                    JSONObject responseJson = postRequest("get_sdp_candidate", postJson);
                    int error = responseJson.getInt("error");
                    if (error == 0) {
                        JSONArray jsonArray = responseJson.getJSONArray("sdp_list");
                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject obj = (JSONObject)jsonArray.get(i);
                            int srcUserId = obj.getInt("src_user_id");
                            String sdpType = obj.getString("sdp_type");
                            String sdpDescription = obj.getString("sdp_description");
                            SessionDescription sdp = new SessionDescription((sdpType.equals("offer") ? OFFER : ANSWER), sdpDescription);
                            events.onGetSdp(srcUserId, sdp);
                        }
                        jsonArray = responseJson.getJSONArray("ice_list_json");
                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject obj = (JSONObject)jsonArray.get(i);
                            int srcUserId = obj.getInt("src_user_id");
                            String iceSdpMid = obj.getString("ice_sdp_mid");
                            String iceSdp = obj.getString("ice_sdp");
                            int mLineIndex = obj.getInt("ice_sdp_m_line_index");
                            IceCandidate candidate = new IceCandidate(iceSdpMid, mLineIndex, iceSdp);
                            events.onGetCandidate(srcUserId, candidate);
                        }
                        jsonArray = responseJson.getJSONArray("ice_removed_list_json");
                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject obj = (JSONObject)jsonArray.get(i);
                            int srcUserId = obj.getInt("src_user_id");
                            String iceSdpMid = obj.getString("ice_sdp_mid");
                            String iceSdp = obj.getString("ice_sdp");
                            int mLineIndex = obj.getInt("ice_sdp_m_line_index");
                            IceCandidate candidate = new IceCandidate(iceSdpMid, mLineIndex, iceSdp);
                            events.onGetCandidateRemoved(srcUserId, candidate);
                        }
                    }
                    handler.postDelayed(runnable, 3000);
                } catch (JSONException e) {
                    Log.d(TAG, "getSdpCandidate:" + e.toString());
                }
            }
        });
    }

    private JSONObject postRequest(String url, JSONObject jsonObject) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(SignalUrl + url).openConnection();
            byte[] postData = jsonObject.toString().getBytes();
            String method = "POST";
            connection.setRequestMethod(method);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            boolean doOutput = false;
            if (method.equals("POST")) {
                doOutput = true;
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(postData.length);
            }

            // Send POST request.
            if (doOutput && postData.length > 0) {
                OutputStream outStream = connection.getOutputStream();
                outStream.write(postData);
                outStream.close();
            }

            // Get response.
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                connection.disconnect();
                return null;
            }
            InputStream responseStream = connection.getInputStream();
            String response = drainStream(responseStream);
            responseStream.close();
            connection.disconnect();
            JSONObject responseJson = new JSONObject(response);
            return responseJson;
        } catch (SocketTimeoutException e) {
        } catch (IOException e) {
        } catch (JSONException ex) {
        }
        return null;
    }
    // Return the contents of an InputStream as a String.
    private static String drainStream(InputStream in) {
        Scanner s = new Scanner(in).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
