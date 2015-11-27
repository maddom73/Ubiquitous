package com.example.android.sunshine.app;

/**
 * Created by maddom73 on 25/11/15.
 */


import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by Matteo on 19/08/2015.
 */
public class WatchFaceService extends WearableListenerService {

    private static final String TAG = WatchFaceService.class.getSimpleName();

    private static final String WEATHER = "/weather";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                String path = dataEvent.getDataItem().getUri().getPath();
                Log.d(TAG, path);
                if (path.equals(WEATHER)) {
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}