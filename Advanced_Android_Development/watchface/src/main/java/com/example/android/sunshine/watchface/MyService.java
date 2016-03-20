package com.example.android.sunshine.watchface;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.List;

public class MyService extends WearableListenerService {

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        SharedPreferences sharedpreferences = getSharedPreferences("MyPREFERENCES", Context.MODE_PRIVATE);

        Log.d("myTag", "dataChanged!");
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        for(DataEvent event : events) {
            DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
            String path = event.getDataItem().getUri().getPath();
            if(path.equals("/CONFIG")) {
                double high,low;

                high = map.getDouble("high");
                low = map.getDouble("low");
                String stringExample = "deneme";

                SharedPreferences.Editor editor = sharedpreferences.edit();
                editor.putString("high", Integer.toString((int)high));
                editor.putString("low", Integer.toString((int)low));
                editor.commit();

                Log.e("myTag", "Data changed!: "+Double.toString(high)+" "+Double.toString(low)+" "+stringExample);
            }


        }
    }

}
