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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static String TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER = "/weather";
        private static final String WEATHER_INFO = "/weather-info";

        private static final String mUUID = "uuid";
        private static final String HIGH = "high";
        private static final String LOW = "low";
        private static final String WEATHER_ID = "weatherId";

        Paint mBackgroundPaint;
        Paint textPaint;
        Paint textDatePaint;
        Paint textDateAmbientPaint;
        Paint textTempHighPaint;
        Paint textTempLowPaint;
        Paint textTempLowAmbientPaint;
        boolean mAmbient;

        String weatherHigh;
        String weatherLow;
        Bitmap weatherIcon;

        float timeXOffset;
        float dateXOffset;
        float lineHeight;
        float timeYOffset;
        float dateYOffset;
        float dividerYOffset;
        float weatherYOffset;
        float timeXOffsetAmbient;

        private Calendar calendar;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                calendar.setTimeInMillis(now);
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            timeYOffset = resources.getDimension(R.dimen.time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.date_y_offset);
            dividerYOffset = resources.getDimension(R.dimen.divider_y_offset);
            weatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            lineHeight = resources.getDimension(R.dimen.line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            textPaint = new Paint();
            textPaint = setTextPaint(resources.getColor(R.color.digital_text));

            textDatePaint = new Paint();
            textDatePaint = setTextPaint(resources.getColor(R.color.primary_light));

            textDateAmbientPaint = new Paint();
            textDateAmbientPaint = setTextPaint(Color.WHITE);

            textTempHighPaint = setBoldTextPaint(Color.WHITE);
            textTempLowPaint = setTextPaint(resources.getColor(R.color.primary_light));
            textTempLowAmbientPaint = setTextPaint(Color.WHITE);

            calendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }
        private Paint setTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint setBoldTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            timeXOffsetAmbient = resources.getDimension(isRound
                    ? R.dimen.time_x_offset_round : R.dimen.time_x_offset);
            dateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            timeXOffsetAmbient = resources.getDimension(isRound
                    ? R.dimen.time_x_offset_round_ambient : R.dimen.time_x_offset_ambient);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_round : R.dimen.digital_temp);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_round : R.dimen.digital_date);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_round : R.dimen.digital_temp);

            textPaint.setTextSize(timeTextSize);
            textDatePaint.setTextSize(dateTextSize);
            textDateAmbientPaint.setTextSize(dateTextSize);
            textTempHighPaint.setTextSize(tempTextSize);
            textTempLowAmbientPaint.setTextSize(tempTextSize);
            textTempLowPaint.setTextSize(tempTextSize);
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
                    textPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            long now = System.currentTimeMillis();
            calendar.setTimeInMillis(now);

            boolean is24Hours = DateFormat.is24HourFormat(SunshineWatchFace.this);

            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            int time  = calendar.get(Calendar.AM_PM);

            String timeText;
            if (is24Hours) {
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                timeText = mAmbient
                        ? String.format("%02d:%02d", hour, minute)
                        : String.format("%02d:%02d:%02d", hour, minute, second);
            } else {
                int hour = calendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }

                String time12Text = Utility.getString(getResources(), time);

                timeText = mAmbient
                        ? String.format("%d:%02d %s", hour, minute, time12Text)
                        : String.format("%d:%02d:%02d %s", hour, minute, second, time12Text);
            }

            float xOffsetTime = textPaint.measureText(timeText) / 2;
            canvas.drawText(timeText, bounds.centerX() - xOffsetTime, timeYOffset, textPaint);

            // Decide which paint to user for the next bits dependent on ambient mode.
            Paint datePaint = mAmbient ? textDateAmbientPaint : textDatePaint;

            // Draw the date
            String dayOfWeek = getDayOfWeek(calendar.get(Calendar.DAY_OF_WEEK));
            String monthOfYearString = getMonthOfYear(calendar.get(Calendar.MONTH));

            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            int year = calendar.get(Calendar.YEAR);

            String dateText = String.format("%s, %s %d %d", dayOfWeek, monthOfYearString, dayOfMonth, year);
            float xOffsetDate = datePaint.measureText(dateText) / 2;
            canvas.drawText(dateText, bounds.centerX() - xOffsetDate, dateYOffset, datePaint);

            // Draw a line to separate date and time from weather elements
            canvas.drawLine(bounds.centerX() - 20, dividerYOffset, bounds.centerX() + 20, dividerYOffset, datePaint);

            // Draw high and low temp if we have it
            if (weatherHigh != null && weatherLow != null) {
                // Draw a line to separate date and time from weather elements
                canvas.drawLine(bounds.centerX() - 20, dividerYOffset, bounds.centerX() + 20, dividerYOffset, datePaint);

                float highTextLen = textTempHighPaint.measureText(weatherHigh);

                if (mAmbient) {
                    float lowTextLen = textTempLowAmbientPaint.measureText(weatherLow);
                    float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
                    canvas.drawText(weatherHigh, xOffset, weatherYOffset, textTempHighPaint);
                    canvas.drawText(weatherLow, xOffset + highTextLen + 20, weatherYOffset, textTempLowAmbientPaint);
                } else {
                    float xOffset = bounds.centerX() - (highTextLen / 2);
                    canvas.drawText(weatherHigh, xOffset, weatherYOffset, textTempHighPaint);
                    canvas.drawText(weatherLow, bounds.centerX() + (highTextLen / 2) + 20, weatherYOffset, textTempLowPaint);
                    float iconXOffset = bounds.centerX() - ((highTextLen / 2) + weatherIcon.getWidth() + 30);
                    canvas.drawBitmap(weatherIcon, iconXOffset, weatherYOffset - weatherIcon.getHeight(), null);
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();

                registerReceiver();

                calendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                calendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();

                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */

        @NonNull
        private String getMonthOfYear(int monthOfYear) {
            String monthOfYearString;
            switch(monthOfYear) {
                case Calendar.JANUARY:
                    monthOfYearString = getResources().getString(R.string.january);
                    break;
                case Calendar.FEBRUARY:
                    monthOfYearString = getResources().getString(R.string.february);
                    break;
                case Calendar.MARCH:
                    monthOfYearString = getResources().getString(R.string.march);
                    break;
                case Calendar.APRIL:
                    monthOfYearString = getResources().getString(R.string.april);
                    break;
                case Calendar.MAY:
                    monthOfYearString = getResources().getString(R.string.may);
                    break;
                case Calendar.JUNE:
                    monthOfYearString = getResources().getString(R.string.june);
                    break;
                case Calendar.JULY:
                    monthOfYearString = getResources().getString(R.string.july);
                    break;
                case Calendar.AUGUST:
                    monthOfYearString = getResources().getString(R.string.august);
                    break;
                case Calendar.SEPTEMBER:
                    monthOfYearString = getResources().getString(R.string.september);
                    break;
                case Calendar.OCTOBER:
                    monthOfYearString = getResources().getString(R.string.october);
                    break;
                case Calendar.NOVEMBER:
                    monthOfYearString = getResources().getString(R.string.november);
                    break;
                case Calendar.DECEMBER:
                    monthOfYearString = getResources().getString(R.string.december);
                    break;
                default:
                    monthOfYearString = "";
            }
            return monthOfYearString;
        }

        @NonNull
        private String getDayOfWeek(int day) {
            String dayOfWeekString;
            switch (day) {
                case Calendar.SUNDAY:
                    dayOfWeekString = getResources().getString(R.string.sunday);
                    break;
                case Calendar.MONDAY:
                    dayOfWeekString = getResources().getString(R.string.monday);
                    break;
                case Calendar.TUESDAY:
                    dayOfWeekString = getResources().getString(R.string.tuesday);
                    break;
                case Calendar.WEDNESDAY:
                    dayOfWeekString = getResources().getString(R.string.wednesday);
                    break;
                case Calendar.THURSDAY:
                    dayOfWeekString = getResources().getString(R.string.thursday);
                    break;
                case Calendar.FRIDAY:
                    dayOfWeekString = getResources().getString(R.string.friday);
                    break;
                case Calendar.SATURDAY:
                    dayOfWeekString = getResources().getString(R.string.saturday);
                    break;
                default:
                    dayOfWeekString = "";
            }
            return dayOfWeekString;
        }

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

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);

            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER);
            putDataMapRequest.getDataMap().putString(mUUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(googleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Failed asking phone for weather data");
                            } else {
                                Log.d(TAG, "Successfully asked for weather data");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, path);
                    if (path.equals(WEATHER_INFO)) {
                        if (dataMap.containsKey(HIGH)) {
                            weatherHigh = dataMap.getString(HIGH);
                            Log.d(TAG, "High = " + weatherHigh);
                        } else {
                            Log.d(TAG, "What? No high?");
                        }

                        if (dataMap.containsKey(LOW)) {
                            weatherLow = dataMap.getString(LOW);
                            Log.d(TAG, "Low = " + weatherLow);
                        } else {
                            Log.d(TAG, "What? No low?");
                        }

                        if (dataMap.containsKey(WEATHER_ID)) {
                            int weatherId = dataMap.getInt(WEATHER_ID);
                            Drawable b = getResources().getDrawable(Utility.getIconResourceForWeatherCondition(weatherId));
                            Bitmap icon = ((BitmapDrawable) b).getBitmap();
                            float scaledWidth = (textTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                            weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) textTempHighPaint.getTextSize(), true);

                        } else {
                            Log.d(TAG, "What? no weatherId?");
                        }

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }

    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
