/*
 * Copyright (C) 2019 The LineageOS Project
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

package com.android.settings.deviceinfo.penguin;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;
import com.android.settingslib.utils.ThreadUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Shows the device maintainer, and calls them the official maintainer only when the device is on
 * the official list and the system is signed with one of the keys listed for it.
 *
 * <p>The list maps each official codename to the SHA-256 fingerprints of its maintainer's platform
 * certificates, for example {@code {"caihong": ["232F57...BDA8"]}}.
 */
public class PenguinMaintainerPreferenceController extends BasePreferenceController {

    private static final String TAG = "PenguinMaintainerPreferenceController";

    private static final String OFFICIAL_DEVICES_URL =
            "https://raw.githubusercontent.com/Project-PenguinOS/OTA/main/official_devices.json";
    private static final String PROP_DEVICE = "ro.aospa.device";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_LIST_BYTES = 64 * 1024;

    // The last answer is kept per build, so the title stays right offline.
    private static final String PREFS = "penguin_official_build";
    private static final String KEY_BUILD = "build_fingerprint";
    private static final String KEY_STATE = "state";

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_OFFICIAL = 1;
    private static final int STATE_UNOFFICIAL = 2;

    private boolean mChecked;

    public PenguinMaintainerPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        String maintainer = mContext.getString(R.string.penguin_maintainer);
        return TextUtils.isEmpty(maintainer)
                ? mContext.getString(R.string.maintainer_unknown) : maintainer;
    }

    @Override
    public void updateState(Preference preference) {
        bind(preference, getCachedState());
        if (mChecked) {
            return;
        }
        mChecked = true;
        ThreadUtils.postOnBackgroundThread(() -> {
            int state = checkOfficial();
            if (state == STATE_UNKNOWN) {
                return;
            }
            putCachedState(state);
            ThreadUtils.postOnMainThread(() -> bind(preference, state));
        });
    }

    private void bind(Preference preference, int state) {
        String maintainer = mContext.getString(R.string.penguin_maintainer);
        if (state == STATE_OFFICIAL) {
            preference.setTitle(R.string.maintainer_official);
            preference.setSummary(getSummary());
        } else if (state == STATE_UNOFFICIAL) {
            preference.setTitle(R.string.maintainer);
            preference.setSummary(TextUtils.isEmpty(maintainer)
                    ? mContext.getString(R.string.maintainer_unofficial_build)
                    : mContext.getString(R.string.maintainer_unofficial, maintainer));
        } else {
            preference.setTitle(R.string.maintainer);
            preference.setSummary(getSummary());
        }
    }

    private int checkOfficial() {
        String device = SystemProperties.get(PROP_DEVICE);
        if (TextUtils.isEmpty(device)) {
            return STATE_UNOFFICIAL;
        }
        JSONObject officialDevices;
        try {
            officialDevices = new JSONObject(downloadOfficialDevices());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read the official device list", e);
            return STATE_UNKNOWN;
        }
        JSONArray certificates = officialDevices.optJSONArray(device);
        if (certificates == null) {
            return STATE_UNOFFICIAL;
        }
        PackageManager pm = mContext.getPackageManager();
        for (int i = 0; i < certificates.length(); i++) {
            byte[] digest;
            try {
                digest = HexFormat.of().parseHex(certificates.optString(i).replace(":", ""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (digest.length == 32 && pm.hasSigningCertificate(
                    "android", digest, PackageManager.CERT_INPUT_SHA256)) {
                return STATE_OFFICIAL;
            }
        }
        return STATE_UNOFFICIAL;
    }

    private static String downloadOfficialDevices() throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(OFFICIAL_DEVICES_URL).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > MAX_LIST_BYTES) {
                        throw new IOException("Official device list is too large");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private int getCachedState() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!Build.FINGERPRINT.equals(prefs.getString(KEY_BUILD, null))) {
            return STATE_UNKNOWN;
        }
        return prefs.getInt(KEY_STATE, STATE_UNKNOWN);
    }

    private void putCachedState(int state) {
        mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BUILD, Build.FINGERPRINT)
                .putInt(KEY_STATE, state)
                .apply();
    }
}
