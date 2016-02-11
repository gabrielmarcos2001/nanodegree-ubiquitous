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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "WATCH";
    private static final String DATA_PATH = "/sunshine_weather";
    private static final String WEATHER_HIGH_PATH = "sunshine_weather_high";
    private static final String WEATHER_LOW_PATH = "sunshine_weather_low";
    private static final String WEATHER_ICON_PATH = "sunshine_icon";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

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

        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
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
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        // Paint objects
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mColonPaint;
        Paint mDatePaint;
        Paint mDivisionLinePaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        // Calendar related objects
        Calendar mCalendar;
        Date mDate;
        java.text.DateFormat mDateFormat;

        boolean mAmbient;
        Time mTime;

        // Receiver to update the time Zone
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        int mTapCount;

        // Drawing Offset variables
        float mYOffset;
        float mLineHeight;

        private String mHighTemperature = "";
        private String mLowTemperature = "";
        private Bitmap mWeatherIconBitmap = null;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            initPaintObjects(resources);
            initCalendarObjects();

        }

        private void initPaintObjects(Resources resources) {

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);

            mMinutePaint = new Paint();
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mColonPaint = new Paint();
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mDatePaint.setAlpha(175);

            mDivisionLinePaint = new Paint();
            mDivisionLinePaint.setColor(getResources().getColor(R.color.digital_text));
            mDivisionLinePaint.setAlpha(75);

            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);

            mLowTempPaint = new Paint();
            mLowTempPaint.setColor(getResources().getColor(R.color.digital_text));
            mLowTempPaint.setAlpha(175);

        }

        private void initCalendarObjects() {

            mTime = new Time();
            mCalendar = Calendar.getInstance();
            mDate = new Date();

            initDateFormat();
        }

        private void initDateFormat() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);

            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
            mLowTempPaint.setTextSize(tempTextSize);
            mHighTempPaint.setTextSize(tempTextSize);
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
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
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
            Resources resources = SunshineWatchFaceService.this.getResources();
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

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            long now = System.currentTimeMillis();

            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime.setToNow();

            String hourText =  String.format("%d", mTime.hour);
            String colon = ":";
            String minuteText = String.format("%02d", mTime.minute);

            // Draws the colon text horizontally centered on the screen
            canvas.drawText(colon,bounds.centerX() - mColonPaint.measureText(colon)/2, mYOffset,mColonPaint);

            // Calculates the X position for drawing the minutes and hours
            float minutesPosX = bounds.centerX() + mColonPaint.measureText(colon);
            float hoursPosX   = bounds.centerX() - mColonPaint.measureText(colon) - mHourPaint.measureText(hourText);

            // Draws the hours and minutes on the screen
            canvas.drawText(hourText, hoursPosX, mYOffset, mHourPaint);
            canvas.drawText(minuteText, minutesPosX, mYOffset, mMinutePaint);

            // Gets the Current Date Text
            String dateText = mDateFormat.format(mDate).toUpperCase(Locale.US);

            float datePosX = bounds.centerX() - mDatePaint.measureText(dateText) / 2; // This will center the text on the screen
            float datePosY = mYOffset + mLineHeight;

            // Draws the current date
            canvas.drawText(
                    dateText,
                    datePosX, datePosY, mDatePaint);

            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {

                if (!mAmbient) {

                    // Calculates the divider position
                    float dividerStartX = bounds.centerX() - getResources().getDimension(R.dimen.division_line_width) / 2;
                    float dividerStopX = dividerStartX + getResources().getDimension(R.dimen.division_line_width);
                    float dividerPosY = bounds.centerY() + mLineHeight/2;

                    // Draws a very cool Divider Line
                    canvas.drawLine(dividerStartX, dividerPosY, dividerStopX, dividerPosY, mDivisionLinePaint);

                    // Weather Image representation
                    float bitmapX = bounds.centerX() - getResources().getDimension(R.dimen.weather_icon_x_offset);
                    float bitmapY = bounds.centerY() + getResources().getDimension(R.dimen.weather_icon_y_offset);

                    if (mWeatherIconBitmap != null) {

                        // Draws the weather icon
                        canvas.drawBitmap(mWeatherIconBitmap, bitmapX, bitmapY, null);

                        // Calculates positions for drawing the temp Max and temp Min in relation to the Bitmap Icon
                        int tempMaxPosX = (int)bitmapX + mWeatherIconBitmap.getWidth() + (int)getResources().getDimension(R.dimen.temp_min_x_offset);
                        int tempPosY = (int)bitmapY + mWeatherIconBitmap.getHeight() / 2;
                        int tempMinPosX = tempMaxPosX + (int) mHighTempPaint.measureText(mHighTemperature) + (int)getResources().getDimension(R.dimen.temp_min_x_offset);

                        // Draws High and low temperatures
                        canvas.drawText(mHighTemperature, tempMaxPosX, tempPosY, mHighTempPaint);
                        canvas.drawText(mLowTemperature, tempMinPosX, tempPosY, mLowTempPaint);
                    }
                }
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

            int a= 0;
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d(TAG, "onConnected Called");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended Called");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            Log.d(TAG, "onDataChanged Called");
            for (DataEvent event : dataEventBuffer) {

                DataItem item = event.getDataItem();

                // We only consider Data Changed events
                if (DATA_PATH.equals(item.getUri().getPath()) && event.getType() == DataEvent.TYPE_CHANGED) {

                    DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                    mHighTemperature = map.getString(WEATHER_HIGH_PATH);
                    mLowTemperature = map.getString(WEATHER_LOW_PATH);
                    Asset iconAsset = map.getAsset(WEATHER_ICON_PATH);
                    Utility.loadBitmapFromAsset(mGoogleApiClient, iconAsset, new Utility.LoadBitmapListener() {
                        @Override
                        public void onError(String error) {

                        }

                        @Override
                        public void onBitmapLoaded(Bitmap bitmap) {
                            mWeatherIconBitmap = bitmap;
                            invalidate();
                        }
                    });

                    invalidate();

                }
            }

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: ");
        }
    }
}
