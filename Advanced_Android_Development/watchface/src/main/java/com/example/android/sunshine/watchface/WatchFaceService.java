/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mDateAmbientPaint;
        Paint mbatteryPaint;

        boolean mAmbient;
        Time mTime;
        String mdateString;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffsetAmbient;
        float mYOffset;
        String high="30";
        String low="10";
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            mGoogleApiClient = new GoogleApiClient.Builder(getApplication())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mYOffsetAmbient = resources.getDimension(R.dimen.digital_y_offset_ambient);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            //mTextPaint.setStrokeWidth(5.0f);
            mDatePaint = new Paint();
            mDateAmbientPaint = new Paint();
            mbatteryPaint = new Paint();

            mDateAmbientPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.watchface_date));
            mbatteryPaint = createTextPaint(resources.getColor(R.color.watchface_date));

            mTime = new Time();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
            for(DataEvent event : events) {
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                String path = event.getDataItem().getUri().getPath();
                if(path.equals("/CONFIG")) {
                    high = Integer.toString((int)map.getDouble("high"));
                    low = Integer.toString((int)map.getDouble("low"));
                    Asset asset = map.getAsset("weatherImage");

                    //Bitmap bitmap = loadBitmapFromAsset(asset);

                    //String encoded=BitmapToString(bitmap);

                    Log.e("myTag", "Data changed!: "+high+" "+low);
                    invalidate();
                }


            }
        }
        /*
        public Bitmap loadBitmapFromAsset(Asset asset) {

        mGoogleApiClient.connect();
        if (asset == null) {
            throw new IllegalArgumentException("Asset must be non-null");
        }
        long TIMEOUT_MS=10000;
        ConnectionResult result =
                mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!result.isSuccess()) {
            return null;
        }
        // convert asset into a file descriptor and block until it's ready
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                mGoogleApiClient, asset).await().getInputStream();
        mGoogleApiClient.disconnect();

        if (assetInputStream == null) {
            Log.w("myTag", "Requested an unknown Asset.");
            return null;
        }
        // decode the stream into a bitmap
        return BitmapFactory.decodeStream(assetInputStream);
    }
/*
    public String BitmapToString(Bitmap bitmap){
        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] b=baos.toByteArray();
        String encoded= Base64.encodeToString(b, Base64.DEFAULT);
        return encoded;
    }
*/
        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.d("myTag", "onConnectionFailed: " + result);
        }
        @Override
        public void onConnectionSuspended(int cause) {
            Log.d("myTag", "onConnectionSuspended: " + cause);
        }
        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        public String getDayWord(int dayNumber){
            String day="";
            switch (dayNumber){
                case 0:
                    day=getString(R.string.sunday);
                    break;
                case 1:
                    day=getString(R.string.monday);
                    break;
                case 2:
                    day=getString(R.string.tuesday);
                    break;
                case 3:
                    day=getString(R.string.wednesday);
                    break;
                case 4:
                    day=getString(R.string.thursday);
                    break;
                case 5:
                    day=getString(R.string.friday);
                    break;
                case 6:
                    day=getString(R.string.saturday);
                    break;
                default:
                    break;
            }
            return day;
        }
        public String getMonthWord(int monthNumber){
            String month="";
            switch (monthNumber){
                case 1:
                    month=getString(R.string.january);
                    break;
                case 2:
                    month=getString(R.string.february);
                    break;
                case 3:
                    month=getString(R.string.march);
                    break;
                case 4:
                    month=getString(R.string.april);
                    break;
                case 5:
                    month=getString(R.string.may);
                    break;
                case 6:
                    month=getString(R.string.june);
                    break;
                case 7:
                    month=getString(R.string.july);
                    break;
                case 8:
                    month=getString(R.string.august);
                    break;
                case 9:
                    month=getString(R.string.september);
                    break;
                case 10:
                    month=getString(R.string.october);
                    break;
                case 11:
                    month=getString(R.string.november);
                    break;
                case 12:
                    month=getString(R.string.december);
                    break;
                default:
                    break;
            }
            return month;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                mGoogleApiClient.connect();

            } else {
                unregisterReceiver();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textSize2 = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_2 : R.dimen.digital_text_size_2);
            float textSize3 = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_3 : R.dimen.digital_text_size_3);
            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(textSize2);
            mDateAmbientPaint.setTextSize(textSize2);
            mbatteryPaint.setTextSize(textSize3);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        public Bitmap StringToBitmap(String encoded){
            byte[] imageAsBytes = Base64.decode(encoded.getBytes(), Base64.DEFAULT);
            Bitmap bitmap= BitmapFactory.decodeByteArray(imageAsBytes, 0, imageAsBytes.length);
            return bitmap;
        }
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {


            IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus =  getApplicationContext().registerReceiver(null, iFilter);
            int batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String day=getDayWord(mTime.weekDay);
            String month=getMonthWord(mTime.month);
            mdateString=day+", "+month+" "+mTime.monthDay+" "+mTime.year;
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            String textDate = !mAmbient
                    ? String.format("%d.%02d.%02d", mTime.monthDay, mTime.month + 1, mTime.year % 100)
                    : String.format("%d.%02d", mTime.monthDay, mTime.month + 1);
            String highString=high+(char) 0x00B0 ;
            String lowString=low+(char) 0x00B0 ;
            String battery=Integer.toString(batteryLevel)+"%";
            if (!mAmbient) {
                canvas.drawText(text, bounds.centerX() - (mTextPaint.measureText(text)) / 2, mYOffset, mTextPaint);
                canvas.drawText(mdateString, bounds.centerX() - (mDatePaint.measureText(mdateString)) / 2, mYOffset + 50, mDatePaint);
                canvas.drawLine(bounds.centerX() - (mTextPaint.measureText(text)) / 4, mYOffset + 90,
                        mTextPaint.measureText(text) / 2 + bounds.centerX() - (mTextPaint.measureText(text)) / 4, mYOffset + 90, mDatePaint);
                canvas.drawText(highString, bounds.centerX(), mYOffset + 150, mDatePaint);
                canvas.drawText(lowString,bounds.centerX() + mDatePaint.measureText(highString)+15, mYOffset + 150, mDatePaint);
                canvas.drawText(battery, bounds.centerX() - mbatteryPaint.measureText(battery) / 2, mYOffset / 3, mbatteryPaint);
                //canvas.drawBitmap(weatherImage, bounds.centerX() - (mTextPaint.measureText(text)) / 2, mYOffset + 150, mDatePaint);
            }
            else{
                canvas.drawText(text, bounds.centerX() - (mTextPaint.measureText(text)) / 2, mYOffsetAmbient, mTextPaint);
                canvas.drawText(textDate, bounds.centerX() - (mDatePaint.measureText(textDate)) / 2, mYOffsetAmbient + 70, mDateAmbientPaint);
            }




        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }



    }
}
