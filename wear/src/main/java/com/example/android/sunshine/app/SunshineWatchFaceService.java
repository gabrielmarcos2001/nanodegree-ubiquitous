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
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.sunshine.common.WearConnector;

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

    private class Engine extends CanvasWatchFaceService.Engine implements WearConnector.ConnectionInterface, WearConnector.SunshineDataInterface {

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
        float mYOffsetBitmap;

        String mHighTemperature = "";
        String mLowTemperature = "";
        Bitmap mWeatherIconBitmap = null;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        // Creates the Connector object for receiving data from the mobile app
        WearConnector mWearConnector = new WearConnector(SunshineWatchFaceService.this);

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

            initPaintObjects(resources);
            initCalendarObjects();
        }

        /**
         * Initialize the different Paint objects used for drawing text on the Canvas
         * @param resources
         */
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

        /**
         * Initialize the Calendar objects with the date format
         */
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

                // Connects the weareable connector
                mWearConnector.connect(this);

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

            } else {
                unregisterReceiver();

                // Disconnects the wereable connector
                mWearConnector.disconnect();
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

            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            mLineHeight = resources.getDimension(isRound
                    ? R.dimen.digital_line_height_round : R.dimen.digital_line_height);

            mYOffsetBitmap = resources.getDimension(isRound
                    ? R.dimen.weather_icon_y_offset_round : R.dimen.weather_icon_y_offset);

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
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
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

            // Gets the total Width of the time so we can center the text on the screen
            float timeTotalWidth = mHourPaint.measureText(hourText) + mColonPaint.measureText(colon) + mMinutePaint.measureText(minuteText);

            // Calculates the start X position for each text
            float hoursStartX = bounds.centerX() - timeTotalWidth / 2;
            float colonStartX = hoursStartX + mHourPaint.measureText(hourText);
            float minutesStartX = colonStartX + mColonPaint.measureText(colon);

            // Draws all the time components on the screen
            canvas.drawText(hourText, hoursStartX,mYOffset,mHourPaint);
            canvas.drawText(colon, colonStartX,mYOffset,mColonPaint);
            canvas.drawText(minuteText, minutesStartX, mYOffset, mMinutePaint);

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
                    float bitmapY = bounds.centerY() + mYOffsetBitmap;

                    if (mWeatherIconBitmap != null) {

                        // Gets the total Width of the temperature section so we can center the text on the screen
                        float temperatureTotalWidth = mWeatherIconBitmap.getWidth() + (int)getResources().getDimension(R.dimen.temp_max_x_offset) + mHighTempPaint.measureText(mHighTemperature) + (int)getResources().getDimension(R.dimen.temp_min_x_offset) + mLowTempPaint.measureText(mLowTemperature);

                        float bitmapStartX = bounds.centerX() - temperatureTotalWidth / 2;
                        float tempHighStartX = bitmapStartX + mWeatherIconBitmap.getWidth() + (int)getResources().getDimension(R.dimen.temp_max_x_offset);
                        float tempLowStartX = tempHighStartX + mHighTempPaint.measureText(mHighTemperature) + (int)getResources().getDimension(R.dimen.temp_min_x_offset);

                        int tempPosY = (int)bitmapY + mWeatherIconBitmap.getHeight() / 2 + (int)mLineHeight / 2;
                        canvas.drawBitmap(mWeatherIconBitmap, bitmapStartX, bitmapY, null);
                        canvas.drawText(mHighTemperature, tempHighStartX, tempPosY, mHighTempPaint);
                        canvas.drawText(mLowTemperature, tempLowStartX, tempPosY, mLowTempPaint);

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
        }

        @Override
        public void onConnected() {
            mWearConnector.startListeningForData(this);
        }

        @Override
        public void onError(String error) {

        }

        /**
         * This method is called whenever the data is changed from the mobile app
         * @param highTemp
         * @param lowTemp
         * @param bitmap
         */
        @Override
        public void onDataChanged(String highTemp, String lowTemp, Bitmap bitmap) {

            mHighTemperature = highTemp;
            mLowTemperature = lowTemp;
            mWeatherIconBitmap = Bitmap.createScaledBitmap(bitmap,(int)getResources().getDimension(R.dimen.bitmap_size),(int)getResources().getDimension(R.dimen.bitmap_size),true);

            invalidate();
        }
    }
}
