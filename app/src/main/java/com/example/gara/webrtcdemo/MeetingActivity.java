package com.example.gara.webrtcdemo;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.os.Bundle;

/**
 * Created by gara on 2017/9/16.
 */

public class MeetingActivity extends Activity {

    private VideoFragment videoFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meeting);

        videoFragment = new VideoFragment();
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(R.id.video_fragment_container, videoFragment);
        ft.commit();
    }
}
