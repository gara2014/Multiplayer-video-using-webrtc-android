package com.example.gara.webrtcdemo;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Created by gara on 2017/9/16.
 */

public class VideoFragment extends Fragment {
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View controlView = inflater.inflate(R.layout.fragment_video, container, false);

        // Create UI controls.
        //contactView = (TextView) controlView.findViewById(R.id.contact_name_call);

        return controlView;
    }
}
