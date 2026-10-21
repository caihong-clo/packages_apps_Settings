/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.widget;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;
import androidx.window.embedding.ActivityEmbeddingController;

import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.core.RoundCornerPreferenceAdapter;
import com.android.settings.flags.Flags;
import com.android.settings.homepage.SettingsHomepageActivity;
import com.android.settingslib.widget.SettingsThemeHelper;

import java.util.WeakHashMap;

/**
 * Adapter for highlighting top level preferences
 */
// FIX: Removed implementation of the non-existent HomepageLoadedListener interface.
public class HighlightableTopLevelPreferenceAdapter extends RoundCornerPreferenceAdapter {

    private static final String TAG = "HighlightableTopLevelAdapter";

    static final long DELAY_HIGHLIGHT_DURATION_MILLIS = 100L;
    private static final int RES_NORMAL_BACKGROUND =
            R.drawable.preference_background;
    private static final int RES_HIGHLIGHTED_BACKGROUND =
            R.drawable.preference_background_highlighted;

    private final int mTitleColorNormal;
    private final int mTitleColorHighlight;
    private final int mSummaryColorNormal;
    private final int mSummaryColorHighlight;
    private final int mIconColorNormal;
    private final int mIconColorHighlight;

    private final SettingsHomepageActivity mHomepageActivity;
    private final RecyclerView mRecyclerView;
    private String mHighlightKey;
    private int mHighlightPosition = RecyclerView.NO_POSITION;
    private int mScrollPosition = RecyclerView.NO_POSITION;
    private boolean mHighlightNeeded;
    private boolean mScrolled;
    private SparseArray<PreferenceViewHolder> mViewHolders;
    // The rows' own backgrounds (the rounded Penguin cards), keyed by the row view. The round
    // corner adapter clears them on bind, so they are captured when the row is created and put
    // back, tinted when highlighted, instead of the square stock backgrounds.
    private final WeakHashMap<View, Drawable.ConstantState> mRowBackgrounds = new WeakHashMap<>();
    private final int mHighlightBackgroundColor;

    public HighlightableTopLevelPreferenceAdapter(SettingsHomepageActivity homepageActivity,
            PreferenceGroup preferenceGroup, RecyclerView recyclerView, String key,
            boolean scrollNeeded) {
        super(preferenceGroup);
        mRecyclerView = recyclerView;
        mHighlightKey = key;
        mScrolled = !scrollNeeded;
        mViewHolders = new SparseArray<>();
        mHomepageActivity = homepageActivity;
        Context context = preferenceGroup.getContext();
        mTitleColorNormal = Utils.getColorAttrDefaultColor(context,
                android.R.attr.textColorPrimary);
        mTitleColorHighlight = context.getColor(
                com.android.internal.R.color.materialColorOnSecondaryContainer);
        mHighlightBackgroundColor = context.getColor(
                com.android.internal.R.color.materialColorSecondaryContainer);
        mSummaryColorNormal = Utils.getColorAttrDefaultColor(context,
                android.R.attr.textColorSecondary);
        mSummaryColorHighlight = mTitleColorHighlight;
        mIconColorNormal = Utils.getHomepageIconColor(context);
        mIconColorHighlight = mTitleColorHighlight;
    }

    @Override
    public PreferenceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        final PreferenceViewHolder holder = super.onCreateViewHolder(parent, viewType);
        final Drawable background = holder.itemView.getBackground();
        if (background != null && background.getConstantState() != null) {
            mRowBackgrounds.put(holder.itemView, background.getConstantState());
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        mViewHolders.put(position, holder);
        updateBackground(holder, position);
    }

    @VisibleForTesting
    void updateBackground(PreferenceViewHolder holder, int position) {
        if (!isHighlightNeeded()) {
            removeHighlightBackground(holder, position);
            return;
        }

        if (position == mHighlightPosition
                && mHighlightKey != null
                && TextUtils.equals(mHighlightKey, getItem(position).getKey())) {
            // This position should be highlighted.
            addHighlightBackground(holder, position);
        } else {
            removeHighlightBackground(holder, position);
        }
    }

    /**
     * A function can highlight a specific setting in recycler view.
     */
    public void requestHighlight() {
        if (mRecyclerView == null) {
            return;
        }

        final int previousPosition = mHighlightPosition;
        if (TextUtils.isEmpty(mHighlightKey)) {
            // De-highlight previous preference.
            mHighlightPosition = RecyclerView.NO_POSITION;
            mScrolled = true;
            if (previousPosition >= 0) {
                notifyItemChanged(previousPosition);
            }
            return;
        }

        final int position = getPreferenceAdapterPosition(mHighlightKey);
        if (position < 0) {
            return;
        }

        // Scroll before highlight if needed.
        final boolean highlightNeeded = isHighlightNeeded();
        if (highlightNeeded) {
            mScrollPosition = position;
            scroll();
        }

        // Turn on/off highlight when screen split mode is changed.
        if (highlightNeeded != mHighlightNeeded) {
            Log.d(TAG, "Highlight needed change: " + highlightNeeded);
            mHighlightNeeded = highlightNeeded;
            mHighlightPosition = position;
            notifyItemChanged(position);
            if (!highlightNeeded) {
                // De-highlight to prevent a flicker
                removeHighlightAt(previousPosition);
            }
            return;
        }

        if (position == mHighlightPosition) {
            return;
        }

        mHighlightPosition = position;
        Log.d(TAG, "Request highlight position " + position);
        Log.d(TAG, "Is highlight needed: " + highlightNeeded);
        if (!highlightNeeded) {
            return;
        }

        // Highlight preference.
        notifyItemChanged(position);

        // De-highlight previous preference.
        if (previousPosition >= 0) {
            notifyItemChanged(previousPosition);
        }
    }

    /**
     * A function that highlights a setting by specifying a preference key. Usually used whenever a
     * preference is clicked.
     */
    public void highlightPreference(String key, boolean scrollNeeded) {
        mHighlightKey = key;
        mScrolled = !scrollNeeded;
        requestHighlight();
    }

    // FIX: Removed onHomepageLoaded() as the listener interface no longer exists.
    /*
    @Override
    public void onHomepageLoaded() {
        scroll();
    }
    */

    private void scroll() {
        if (mScrolled || mScrollPosition < 0) {
            return;
        }

        // FIX: Removed call to addHomepageLoadedListener as it no longer exists.
        /*
        if (mHomepageActivity.addHomepageLoadedListener(this)) {
            return;
        }
        */

        // Only when the recyclerView is loaded, it can be scrolled
        final View view = mRecyclerView.getChildAt(mScrollPosition);
        if (view == null) {
            mRecyclerView.postDelayed(() -> scroll(), DELAY_HIGHLIGHT_DURATION_MILLIS);
            return;
        }

        mScrolled = true;
        Log.d(TAG, "Scroll to position " + mScrollPosition);
        // Scroll to the top to reset the position.
        mRecyclerView.nestedScrollBy(0, -mRecyclerView.getHeight());

        // get the visible area of the recycler view
        Rect rvRect = new Rect();
        mRecyclerView.getGlobalVisibleRect(rvRect);

        final int scrollY = view.getTop();
        if (scrollY > 0) {
            mRecyclerView.nestedScrollBy(0, scrollY);
        }
    }

    private void removeHighlightAt(int position) {
        if (position >= 0) {
            // De-highlight the existing preference view holder at an early stage
            final PreferenceViewHolder holder = mViewHolders.get(position);
            if (holder != null) {
                removeHighlightBackground(holder, position);
            }
            notifyItemChanged(position);
        }
    }

    private void addHighlightBackground(PreferenceViewHolder holder, int position) {
        final View v = holder.itemView;
        final Drawable.ConstantState rowBackground = mRowBackgrounds.get(v);
        if (rowBackground != null) {
            final Drawable highlighted = rowBackground.newDrawable(v.getResources()).mutate();
            highlighted.setTint(mHighlightBackgroundColor);
            v.setBackground(highlighted);
        } else {
            v.setBackgroundResource(RES_HIGHLIGHTED_BACKGROUND);
        }
        ((TextView) v.findViewById(android.R.id.title)).setTextColor(mTitleColorHighlight);
        ((TextView) v.findViewById(android.R.id.summary)).setTextColor(mSummaryColorHighlight);
        final Drawable drawable = ((ImageView) v.findViewById(android.R.id.icon)).getDrawable();
        if (drawable != null) {
            drawable.setTint(mIconColorHighlight);
        }
    }

    private void removeHighlightBackground(PreferenceViewHolder holder, int position) {
        final View v = holder.itemView;
        final Drawable.ConstantState rowBackground = mRowBackgrounds.get(v);
        if (rowBackground != null) {
            v.setBackground(rowBackground.newDrawable(v.getResources()));
        } else {
            v.setBackgroundResource(RES_NORMAL_BACKGROUND);
        }
        ((TextView) v.findViewById(android.R.id.title)).setTextColor(mTitleColorNormal);
        ((TextView) v.findViewById(android.R.id.summary)).setTextColor(mSummaryColorNormal);
        final Drawable drawable = ((ImageView) v.findViewById(android.R.id.icon)).getDrawable();
        if (drawable != null) {
           drawable.setTint(mIconColorNormal);
        }
    }

    private boolean isHighlightNeeded() {
        return ActivityEmbeddingController.getInstance(mHomepageActivity)
                .isActivityEmbedded(mHomepageActivity);
    }
}
