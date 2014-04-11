/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2014 Benoit 'BoD' Lubek (BoD@JRAF.org)
 * Copyright (C) 2014 Carmen Alvarez (c@rmen.ca)
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
package ca.rmen.android.networkmonitor.app.service;

import android.app.Notification;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;

import ca.rmen.android.networkmonitor.Constants;
import ca.rmen.android.networkmonitor.app.email.ReportEmailer;
import ca.rmen.android.networkmonitor.app.prefs.NetMonPreferences;
import ca.rmen.android.networkmonitor.app.service.datasources.NetMonDataSources;
import ca.rmen.android.networkmonitor.app.service.scheduler.Scheduler;
import ca.rmen.android.networkmonitor.provider.NetMonColumns;
import ca.rmen.android.networkmonitor.util.Log;

/**
 * This service periodically retrieves network state information and writes it to the database.
 */
public class NetMonService extends Service {
    private static final String TAG = Constants.TAG + NetMonService.class.getSimpleName();


    private PowerManager mPowerManager;
    private long mLastWakeUp = 0;
    private NetMonDataSources mDataSources;
    private ReportEmailer mReportEmailer;
    private Scheduler mScheduler;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate Service is enabled: starting monitor loop");

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        // Show our ongoing notification
        Notification notification = NetMonNotification.createNotification(this);
        startForeground(NetMonNotification.NOTIFICATION_ID, notification);

        // Prepare our data sources
        mDataSources = new NetMonDataSources();
        mDataSources.onCreate(this);

        mReportEmailer = new ReportEmailer(this);

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(mSharedPreferenceListener);

        scheduleTests();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy");
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(mSharedPreferenceListener);
        mDataSources.onDestroy();
        NetMonNotification.dismissNotification(this);
        mScheduler.onDestroy();
        super.onDestroy();
    }

    /**
     * Start scheduling tests, using the scheduler class chosen by the user in the advanced settings.
     */
    private void scheduleTests() {
        Log.v(TAG, "scheduleTests");
        if (mScheduler != null) {
            mScheduler.onDestroy();
        }
        Class<?> schedulerClass = NetMonPreferences.getInstance(this).getSchedulerClass();
        Log.v(TAG, "Will use scheduler " + schedulerClass);
        try {
            mScheduler = (Scheduler) schedulerClass.newInstance();
            mScheduler.onCreate(this);
            mScheduler.schedule(mTask, NetMonPreferences.getInstance(this).getUpdateInterval());
        } catch (InstantiationException e) {
            Log.e(TAG, "setScheduler Could not create scheduler " + schedulerClass + ": " + e.getMessage(), e);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "setScheduler Could not create scheduler " + schedulerClass + ": " + e.getMessage(), e);
        }
    }

    private Runnable mTask = new Runnable() {

        @Override
        public void run() {
            Log.v(TAG, "running task");

            // Retrieve the log
            WakeLock wakeLock = null;
            try {
                // Periodically wake up the device to prevent the data connection from being cut.
                long wakeInterval = NetMonPreferences.getInstance(NetMonService.this).getWakeInterval();
                long now = System.currentTimeMillis();
                long timeSinceLastWake = now - mLastWakeUp;
                Log.d(TAG, "wakeInterval = " + wakeInterval + ", lastWakeUp = " + mLastWakeUp + ", timeSinceLastWake = " + timeSinceLastWake);
                if (wakeInterval > 0 && timeSinceLastWake > wakeInterval) {
                    Log.d(TAG, "acquiring lock");
                    wakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
                    wakeLock.acquire();
                    mLastWakeUp = now;
                }

                // Insert this ContentValues into the DB.
                Log.v(TAG, "Inserting data into DB");
                // Put all the data we want to log, into a ContentValues.
                ContentValues values = new ContentValues();
                values.put(NetMonColumns.TIMESTAMP, System.currentTimeMillis());
                values.putAll(mDataSources.getContentValues());
                getContentResolver().insert(NetMonColumns.CONTENT_URI, values);

                // Send mail
                mReportEmailer.send();

            } catch (Throwable t) {
                Log.v(TAG, "Error in monitorLoop: " + t.getMessage(), t);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            }

        }
    };

    private OnSharedPreferenceChangeListener mSharedPreferenceListener = new OnSharedPreferenceChangeListener() {

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            Log.v(TAG, "onSharedPreferenceChanged: " + key);
            // Listen for the user disabling the service
            if (NetMonPreferences.PREF_SERVICE_ENABLED.equals(key)) {
                if (!sharedPreferences.getBoolean(key, NetMonPreferences.PREF_SERVICE_ENABLED_DEFAULT)) {
                    Log.v(TAG, "Preference to enable service was turned off");
                    stopSelf();
                }
            }
            // Reschedule our task if the user changed the interval
            else if (NetMonPreferences.PREF_UPDATE_INTERVAL.equals(key)) {
                int interval = NetMonPreferences.getInstance(NetMonService.this).getUpdateInterval();
                mScheduler.setInterval(interval);
            } else if (NetMonPreferences.PREF_SCHEDULER.equals(key)) {
                scheduleTests();
            }
        }
    };


}
