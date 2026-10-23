/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.custom.taskbar;

import android.content.Context;

import com.android.settings.core.BasePreferenceController;

/** Shows the Taskbar page only on large screens, where the launcher has a taskbar. */
public class TaskbarSettingsPreferenceController extends BasePreferenceController {

    // The launcher shows a taskbar from this smallest width up (Launcher3's large screen).
    private static final int LARGE_SCREEN_MIN_WIDTH_DP = 600;

    public TaskbarSettingsPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public int getAvailabilityStatus() {
        return mContext.getResources().getConfiguration().smallestScreenWidthDp
                >= LARGE_SCREEN_MIN_WIDTH_DP ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }
}
