/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar;

import java.util.ArrayList;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

import android.provider.Settings;

public abstract class StatusBar extends SystemUI implements CommandQueue.Callbacks {
    static final String TAG = "StatusBar";
    private static final boolean SPEW = false;

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // Up-call methods
    protected abstract View makeStatusBarView();
    protected abstract int getStatusBarGravity();
    public abstract int getStatusBarHeight();
    public abstract void animateCollapse();
    public abstract boolean isTablet();

    private boolean mShowNotificationCounts;
    private DoNotDisturb mDoNotDisturb;

    public void start() {
        // First set up our views and stuff.
        View sb = makeStatusBarView();

        mStatusBarContainer.addView(sb);

        mShowNotificationCounts = Settings.System.getInt(mContext.getContentResolver(),
	                Settings.System.STATUS_BAR_NOTIF_COUNT, 0) == 1;

        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        ArrayList<IBinder> notificationKeys = new ArrayList<IBinder>();
        ArrayList<StatusBarNotification> notifications = new ArrayList<StatusBarNotification>();
        mCommandQueue = new CommandQueue(this, iconList);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        int[] switches = new int[7];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, notificationKeys, notifications,
                    switches, binders);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        disable(switches[0]);
        setSystemUiVisibility(switches[1]);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4]);
        setHardKeyboardStatus(switches[5] != 0, switches[6] != 0);

        // Set up the initial icon state
        int N = iconList.size();
        int viewIndex = 0;
        for (int i=0; i<N; i++) {
            StatusBarIcon icon = iconList.getIcon(i);
            if (icon != null) {
                addIcon(iconList.getSlot(i), i, viewIndex, icon);
                viewIndex++;
            }
        }

        // Set up the initial notification state
        N = notificationKeys.size();
        if (N == notifications.size()) {
            for (int i=0; i<N; i++) {
                addNotification(notificationKeys.get(i), notifications.get(i));
            }
        } else {
            Log.wtf(TAG, "Notification list length mismatch: keys=" + N
                    + " notifications=" + notifications.size());
        }

        // Put up the view
        final int height = getStatusBarHeight();

        final int opacity = Settings.System.getInt(
                                        sb.getContext().getContentResolver(),
                                        Settings.System.STATUS_BAR_TRANSPARENCY, 100);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                // We use a pixel format of RGB565 for the status bar to save memory bandwidth and
                // to ensure that the layer can be handled by HWComposer.  On some devices the
                // HWComposer is unable to handle SW-rendered RGBX_8888 layers.

                (opacity != 100 ? PixelFormat.TRANSPARENT : PixelFormat.RGB_565)

                );

        if (opacity != 100) {
            sb.setBackgroundColor(
                (int) (((float)opacity / 100.0F) * 255) * 0x1000000
            );
        }
        
        // the status bar should be in an overlay if possible
        final Display defaultDisplay 
            = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();

        // We explicitly leave FLAG_HARDWARE_ACCELERATED out of the flags.  The status bar occupies
        // very little screen real-estate and is updated fairly frequently.  By using CPU rendering
        // for the status bar, we prevent the GPU from having to wake up just to do these small
        // updates, which should help keep power consumption down.

        lp.gravity = getStatusBarGravity();
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();
        lp.windowAnimations = R.style.Animation_StatusBar;
        WindowManagerImpl.getDefault().addView(mStatusBarContainer, lp);

        if (SPEW) {
            Slog.d(TAG, "Added status bar view: gravity=0x" + Integer.toHexString(lp.gravity) 
                   + " icons=" + iconList.size()
                   + " disabled=0x" + Integer.toHexString(switches[0])
                   + " lights=" + switches[1]
                   + " menu=" + switches[2]
                   + " imeButton=" + switches[3]
                   );
        }

        mDoNotDisturb = new DoNotDisturb(mContext);

        // refresh weather here (after 10s) in case systemui was restarted or somehow crashed
        Handler h = new Handler();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshWeather();
            }
        }, 10000);
    }

    private void refreshWeather() {
        Intent weatherintent = new Intent("com.aokp.romcontrol.INTENT_WEATHER_REQUEST");
        weatherintent.putExtra("com.aokp.romcontrol.INTENT_EXTRA_TYPE", "updateweather");
        weatherintent.putExtra("com.aokp.romcontrol.INTENT_EXTRA_ISMANUAL", false);
        mContext.sendBroadcast(weatherintent);
    }

    public static void resetColors(Context c) {
        Settings.System.putInt(c.getContentResolver(), Settings.System.STATUSBAR_CLOCK_COLOR,
                Integer.MIN_VALUE);
        Settings.System.putInt(c.getContentResolver(), Settings.System.STATUSBAR_BATTERY_BAR_COLOR,
                Integer.MIN_VALUE);
        Settings.System.putInt(c.getContentResolver(), Settings.System.STATUSBAR_SIGNAL_TEXT_COLOR,
                Integer.MIN_VALUE);
        Settings.System.putInt(c.getContentResolver(), Settings.System.STATUSBAR_WIFI_SIGNAL_TEXT_COLOR,
                Integer.MIN_VALUE);
        Settings.System.putInt(c.getContentResolver(), Settings.System.NAVIGATION_BAR_GLOW_TINT,
                Integer.MIN_VALUE);
        Settings.System.putInt(c.getContentResolver(), Settings.System.NAVIGATION_BAR_TINT,
                Integer.MIN_VALUE);
    }

    protected View updateNotificationVetoButton(View row, StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        if (n.isClearable()) {
            final String _pkg = n.pkg;
            final String _tag = n.tag;
            final int _id = n.id;
            vetoButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            mBarService.onNotificationClear(_pkg, _tag, _id);
                        } catch (RemoteException ex) {
                            // system process is dead if we're here.
                        }
                    }
                });
            vetoButton.setVisibility(View.VISIBLE);
        } else {
            vetoButton.setVisibility(View.GONE);
        }
        return vetoButton;
    }
}
