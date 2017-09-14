package org.appspot.apprtc;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.List;


interface RoomSignalEvents {
    void onJoinRoom(final int error, final int myUserId, final List<RoomUser> other);

    void onSendSdp(final int error);

    void onSendCandidate(final int error);

    void onGetSdp(final int srcUserId, final SessionDescription sdp);

    void onGetCandidate(final int srcUserId, final IceCandidate candidate);

    void onGetCandidateRemoved(final int srcUserId, final IceCandidate candidate);

    void onDisJoinRoom(final int error);
}
