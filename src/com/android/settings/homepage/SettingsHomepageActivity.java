/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settings.homepage;

import static android.provider.Settings.ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY;
import static android.provider.Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY;
import static android.provider.Settings.EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI;

import static com.android.settings.SettingsActivity.EXTRA_IS_DEEPLINK_HOME_STARTED_FROM_SEARCH;
import static com.android.settings.SettingsActivity.EXTRA_USER_HANDLE;

import android.animation.LayoutTransition;
import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.settings.flags.Flags;
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.BlurTarget;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.ViewOutlineProvider;

import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.Insets;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.window.embedding.SplitController;
import androidx.window.embedding.SplitInfo;
import androidx.window.embedding.SplitRule;
import androidx.window.java.embedding.SplitControllerCallbackAdapter;

import com.android.settings.R;
import com.android.settings.Settings;
import com.android.settings.SettingsActivity;
import com.android.settings.SettingsApplication;
import com.android.settings.accounts.AvatarViewMixin;
import com.android.settings.activityembedding.ActivityEmbeddingRulesController;
import com.android.settings.activityembedding.ActivityEmbeddingUtils;
import com.android.settings.activityembedding.EmbeddedDeepLinkUtils;
import com.android.settings.core.CategoryMixin;
import com.android.settings.core.FeatureFlags;
import com.android.settings.homepage.contextualcards.ContextualCardsFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.safetycenter.SafetyCenterManagerWrapper;
import com.android.settingslib.Utils;
import com.android.settingslib.core.lifecycle.HideNonSystemOverlayMixin;

import com.google.android.setupcompat.util.WizardManagerHelper;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

/** Settings homepage activity */
public class SettingsHomepageActivity extends FragmentActivity implements
        CategoryMixin.CategoryHandler {

    private static final String TAG = "SettingsHomepageActivity";
    private Drawable mCachedNoiseDrawable;

    // Additional extra of Settings#ACTION_SETTINGS_LARGE_SCREEN_DEEP_LINK.
    // Put true value to the intent when startActivity for a deep link intent from this Activity.
    public static final String EXTRA_IS_FROM_SETTINGS_HOMEPAGE = "is_from_settings_homepage";

    // Additional extra of Settings#ACTION_SETTINGS_LARGE_SCREEN_DEEP_LINK.
    // Set & get Uri of the Intent separately to prevent failure of Intent#ParseUri.
    public static final String EXTRA_SETTINGS_LARGE_SCREEN_DEEP_LINK_INTENT_DATA =
            "settings_large_screen_deep_link_intent_data";

    // The referrer who fires the initial intent to start the homepage
    @VisibleForTesting
    static final String EXTRA_INITIAL_REFERRER = "initial_referrer";

    static final int DEFAULT_HIGHLIGHT_MENU_KEY = R.string.menu_key_network;

    private TopLevelSettings mMainFragment;
    private CategoryMixin mCategoryMixin;
    private boolean mIsEmbeddingActivityEnabled;
    private boolean mIsTwoPane;
    // A regular layout shows icons on homepage, whereas a simplified layout doesn't.
    private boolean mIsRegularLayout = true;

    private SplitControllerCallbackAdapter mSplitControllerAdapter;
    private SplitInfoCallback mCallback;

    /** A listener receiving homepage loaded events. */
    // CRASH-FIX: Removed suggestion-related interface
    /*
    public interface HomepageLoadedListener {
        /** Called when the homepage is loaded. *
        void onHomepageLoaded();
    }
    */

    private interface FragmentCreator<T extends Fragment> {
        T create();

        /** To initialize after {@link #create} */
        default void init(Fragment fragment) {}
    }

    /**
     * Try to add a {@link HomepageLoadedListener}. If homepage is already loaded, the listener
     * will not be notified.
     *
     * @return Whether the listener is added.
     */
    // CRASH-FIX: Removed suggestion-related method
    /*
    public boolean addHomepageLoadedListener(HomepageLoadedListener listener) {
        if (mHomepageView == null) {
            return false;
        } else {
            if (!mLoadedListeners.contains(listener)) {
                mLoadedListeners.add(listener);
            }
            return true;
        }
    }
    */

    /**
     * Shows the homepage and shows/hides the suggestion together. Only allows to be executed once
     * to avoid the flicker caused by the suggestion suddenly appearing/disappearing.
     */
    // CRASH-FIX: Removed suggestion-related method
    /*
    public void showHomepageWithSuggestion(boolean showSuggestion) {
        if (mAllowUpdateSuggestion) {
            Log.i(TAG, "showHomepageWithSuggestion: " + showSuggestion);
            mAllowUpdateSuggestion = false;
            mSuggestionView.setVisibility(showSuggestion ? View.VISIBLE : View.GONE);
            mTwoPaneSuggestionView.setVisibility(showSuggestion ? View.VISIBLE : View.GONE);
        }

        if (mHomepageView == null) {
            return;
        }
        final View homepageView = mHomepageView;
        mHomepageView = null;
        mLoadedListeners.forEach(listener -> listener.onHomepageLoaded());
        mLoadedListeners.clear();
        homepageView.setVisibility(View.VISIBLE);
    }
    */

    /** Returns the main content fragment */
    public TopLevelSettings getMainFragment() {
        return mMainFragment;
    }

    @Override
    public CategoryMixin getCategoryMixin() {
        return mCategoryMixin;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure device is provisioned in order to access Settings home
        // TODO(b/331254029): This should later be replaced in favor of an allowlist
        boolean unprovisioned = android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        if (unprovisioned) {
            Log.e(TAG, "Device is not provisioned, exiting Settings");
            finish();
            return;
        }

        // Settings homepage should be the task root, otherwise there will be UI issues.
        boolean isTaskRoot = isTaskRoot();

        mIsEmbeddingActivityEnabled = ActivityEmbeddingUtils.isEmbeddingActivityEnabled(this);
        if (mIsEmbeddingActivityEnabled) {
            final UserManager um = getSystemService(UserManager.class);
            final UserInfo userInfo = um.getUserInfo(getUserId());
            if (EmbeddedDeepLinkUtils.isSubProfile(userInfo)) {
                final Intent intent = new Intent(getIntent())
                        .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                        .putExtra(EXTRA_USER_HANDLE, getUser())
                        .putExtra(EXTRA_INITIAL_REFERRER, getCurrentReferrer());
                if (TextUtils.equals(intent.getAction(), ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)
                        && this instanceof DeepLinkHomepageActivity) {
                    intent.setClass(this, DeepLinkHomepageActivityInternal.class);
                } else {
                    intent.setPackage(getPackageName());
                }
                if (!isTaskRoot) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                } else {
                    intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                startActivityAsUser(intent, um.getProfileParent(userInfo.id).getUserHandle());
                finish();
                return;
            }
        }

        final boolean isDeepLinkStartedFromSearch = getIntent().getBooleanExtra(
                EXTRA_IS_DEEPLINK_HOME_STARTED_FROM_SEARCH, false /* defaultValue */);
        if (!isTaskRoot && !isDeepLinkStartedFromSearch) {
            if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
                Log.i(TAG, "Activity has been started, finishing");
            } else {
                Log.i(TAG, "Homepage should be started with FLAG_ACTIVITY_NEW_TASK, restarting");
                Intent intent = new Intent(getIntent())
                        .setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                        .putExtra(EXTRA_USER_HANDLE, getUser())
                        .putExtra(EXTRA_INITIAL_REFERRER, getCurrentReferrer());
                startActivity(intent);
            }
            finish();
            return;
        }

        setupEdgeToEdge();
        setContentView(R.layout.settings_homepage_container);

        mIsTwoPane = ActivityEmbeddingUtils.isAlreadyEmbedded(this);

        updateAppBarMinHeight();
        initHomepageContainer();
        updateHomepageAppBar();
        updateHomepageBackground();

        initSearchBarView();

        getLifecycle().addObserver(new HideNonSystemOverlayMixin(this));
        mCategoryMixin = new CategoryMixin(this);
        getLifecycle().addObserver(mCategoryMixin);
        
        // Block GMS Core backup/restore entry from injecting into Settings
        mCategoryMixin.addToDenylist(new ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.backup.component.BackupOrRestoreSettingsActivity"));

        final String highlightMenuKey = getHighlightMenuKey();

        // CRASH-FIX: Removed suggestion and contextual card loading to prevent crash
        /*
        // Only allow features on high ram devices.
        if (!getSystemService(ActivityManager.class).isLowRamDevice()) {
            final boolean scrollNeeded = mIsEmbeddingActivityEnabled
                    && !TextUtils.equals(getString(DEFAULT_HIGHLIGHT_MENU_KEY), highlightMenuKey);
            showSuggestionFragment(scrollNeeded);
            if (!Flags.updatedSuggestionCardAosp()
                    && FeatureFlagUtils.isEnabled(this, FeatureFlags.CONTEXTUAL_HOME)) {
                showFragment(() -> new ContextualCardsFragment(), R.id.contextual_cards_content);
                ((FrameLayout) findViewById(R.id.main_content))
                        .getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);
            }
        }
        */

        mMainFragment = showFragment(() -> {
            final TopLevelSettings fragment = new TopLevelSettings();
            fragment.getArguments().putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY,
                    highlightMenuKey);
            return fragment;
        }, R.id.main_content);

        if (shouldLaunchDeepLinkIntentToRight()) {
            launchDeepLinkIntentToRight();
        }

        // Settings app may be launched on an existing task. Reset SplitPairRule of SubSettings here
        // to prevent SplitPairRule of an existing task applied on a new started Settings app.
        if (mIsEmbeddingActivityEnabled
                && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
            initSplitPairRules();
        }

        updateHomepagePaddings();
        updateSplitLayout();

        enableTaskLocaleOverride();
    }

    @VisibleForTesting
    void initSplitPairRules() {
        new ActivityEmbeddingRulesController(getApplicationContext()).initRules();
    }

    @Override
    protected void onStart() {
        ((SettingsApplication) getApplication()).setHomeActivity(this);
        super.onStart();
        if (mIsEmbeddingActivityEnabled) {
            final SplitController splitController = SplitController.getInstance(this);
            mSplitControllerAdapter = new SplitControllerCallbackAdapter(splitController);
            mCallback = new SplitInfoCallback(this);
            mSplitControllerAdapter.addSplitListener(this, Runnable::run, mCallback);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mSplitControllerAdapter != null && mCallback != null) {
            mSplitControllerAdapter.removeSplitListener(mCallback);
            mCallback = null;
            mSplitControllerAdapter = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // When it's large screen 2-pane and Settings app is in the background, receiving an Intent
        // will not recreate this activity. Update the intent for this case.
        setIntent(intent);
        reloadHighlightMenuKey();
        if (isFinishing()) {
            return;
        }
        if (shouldLaunchDeepLinkIntentToRight()) {
            launchDeepLinkIntentToRight();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateHomepageUI();
    }

    private void updateSplitLayout() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }
        if (mIsTwoPane) {
            if (mIsRegularLayout == ActivityEmbeddingUtils.isRegularHomepageLayout(this)) {
                // Layout unchanged
                return;
            }
        } else if (mIsRegularLayout) {
            // One pane mode with the regular layout, not needed to change
            return;
        }
        mIsRegularLayout = !mIsRegularLayout;

        // Update search title padding
        View searchTitle = findViewById(R.id.search_bar_title);
        if (searchTitle != null && mIsTwoPane) {
            int paddingStart = getResources().getDimensionPixelSize(
                    mIsRegularLayout
                            ? R.dimen.search_bar_title_padding_start_regular_two_pane
                            : R.dimen.search_bar_title_padding_start);
            searchTitle.setPaddingRelative(paddingStart, 0, 0, 0);
        }
        // Notify fragments
        getSupportFragmentManager().getFragments().forEach(fragment -> {
            if (fragment instanceof SplitLayoutListener) {
                ((SplitLayoutListener) fragment).onSplitLayoutChanged(mIsRegularLayout);
            }
        });
    }

    private void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(insets.left, 0, insets.right, insets.bottom);

                    View appBar = findViewById(R.id.app_bar);
                    if (appBar != null) {
                        appBar.setPadding(appBar.getPaddingLeft(), insets.top,
                                appBar.getPaddingRight(), appBar.getPaddingBottom());
                    }

                    return WindowInsetsCompat.CONSUMED;
                });
    }

    private void initSearchBarView() {
        View toolbar = findViewById(R.id.search_action_bar);
        FeatureFactory.getFeatureFactory().getSearchFeatureProvider()
                .initSearchToolbar(this /* activity */, toolbar, SettingsEnums.SETTINGS_HOMEPAGE);

        if (mIsEmbeddingActivityEnabled) {
            final View toolbarTwoPaneVersion = findViewById(R.id.search_action_bar_two_pane);
            FeatureFactory.getFeatureFactory().getSearchFeatureProvider()
                    .initSearchToolbar(this /* activity */, toolbarTwoPaneVersion,
                            SettingsEnums.SETTINGS_HOMEPAGE);
        }

        setupSearchBarBlur();
    }

    private void setupSearchBarBlur() {
        final BlurView blurView = findViewById(R.id.bottom_search_bar_container);
        final BlurTarget target = findViewById(R.id.main_content_blur_target);
        if (blurView == null || target == null) {
            return;
        }

        float blurRadius = 14f;
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        if (wm != null && !wm.isCrossWindowBlurEnabled()) {
            blurRadius = 0f;
        }

        Drawable windowBackground = getWindow().getDecorView().getBackground();
        blurView.setupWith(target)
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(blurRadius);

        blurView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                int radius = (int) (24 * view.getResources().getDisplayMetrics().density);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        blurView.setClipToOutline(true);

        final View cardView = findViewById(R.id.homepage_app_bar_regular_phone_view);
        if (cardView instanceof com.google.android.material.card.MaterialCardView) {
            com.google.android.material.card.MaterialCardView materialCardView =
                    (com.google.android.material.card.MaterialCardView) cardView;
            materialCardView.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
            materialCardView.setStrokeColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        }
        if (cardView != null) {
            View searchActionBar = cardView.findViewById(R.id.search_action_bar);
            if (searchActionBar != null) {
                searchActionBar.setBackground(null);
            }
            
            cardView.setScaleX(0.98f);
            cardView.setScaleY(0.98f);
            
            int onSurfaceVariantColor = 0;
            int onSurfaceVariantAttrId = getResources().getIdentifier("colorOnSurfaceVariant", "attr", getPackageName());
            if (onSurfaceVariantAttrId != 0) {
                onSurfaceVariantColor = Utils.getColorAttrDefaultColor(this, onSurfaceVariantAttrId, 0);
            }
            if (onSurfaceVariantColor == 0) {
                onSurfaceVariantColor = Utils.getColorAttrDefaultColor(this, android.R.attr.textColorSecondary, 0);
            }
            if (onSurfaceVariantColor == 0) {
                onSurfaceVariantColor = Utils.getColorAttrDefaultColor(this, android.R.attr.textColorPrimary, 0);
            }
            if (onSurfaceVariantColor == 0) {
                onSurfaceVariantColor = 0xFF888888;
            }
            
            onSurfaceVariantColor = androidx.core.graphics.ColorUtils.setAlphaComponent(onSurfaceVariantColor, 255);
            
            android.widget.ImageView searchIcon = cardView.findViewById(R.id.imageView);
            if (searchIcon != null) {
                searchIcon.setImageTintList(android.content.res.ColorStateList.valueOf(onSurfaceVariantColor));
            }
            
            android.widget.TextView searchTitle = cardView.findViewById(R.id.search_bar_title);
            if (searchTitle != null) {
                searchTitle.setTextColor(onSurfaceVariantColor);
            }

            cardView.setFocusable(true);
            cardView.setOnFocusChangeListener((v, hasFocus) -> {
                animateSearchFocus(blurView, cardView, hasFocus);
            });
            
            cardView.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    animateSearchFocus(blurView, cardView, true);
                } else if (event.getAction() == android.view.MotionEvent.ACTION_UP 
                        || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    animateSearchFocus(blurView, cardView, cardView.hasFocus());
                }
                return false;
            });
        }

        boolean isBlurEnabled = wm != null && wm.isCrossWindowBlurEnabled();
        refreshGlassBackground(blurView, isBlurEnabled ? 0.45f : 1.0f);
    }

    private void refreshGlassBackground(BlurView blurView, float alpha) {
        float density = getResources().getDisplayMetrics().density;
        
        GradientDrawable glassDrawable = new GradientDrawable();
        glassDrawable.setShape(GradientDrawable.RECTANGLE);
        glassDrawable.setCornerRadius(28f * density);
        
        int attrId = getResources().getIdentifier("colorSurfaceContainerHigh", "attr", getPackageName());
        if (attrId == 0) {
            attrId = android.R.attr.colorBackgroundFloating;
        }
        int surfaceColor = Utils.getColorAttrDefaultColor(this, attrId);
        int tintColor = androidx.core.graphics.ColorUtils.setAlphaComponent(surfaceColor, (int)(255 * alpha));
        glassDrawable.setColor(tintColor);
        
        int outlineAttrId = getResources().getIdentifier("colorOutlineVariant", "attr", getPackageName());
        if (outlineAttrId == 0) {
            outlineAttrId = android.R.attr.colorControlActivated;
        }
        int outlineColor = Utils.getColorAttrDefaultColor(this, outlineAttrId);
        int softOutlineColor = androidx.core.graphics.ColorUtils.setAlphaComponent(outlineColor, (int)(255 * 0.30f));
        glassDrawable.setStroke((int)(0.5f * density), softOutlineColor);
        
        Drawable noiseDrawable = getNoiseDrawable();
        Drawable[] layers = {glassDrawable, noiseDrawable};
        LayerDrawable layerDrawable = new LayerDrawable(layers);
        
        blurView.setBackground(layerDrawable);
        blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        blurView.setClipToOutline(true);
    }

    private Drawable getNoiseDrawable() {
        if (mCachedNoiseDrawable == null) {
            int size = 128;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            java.util.Random random = new java.util.Random();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int alpha = 1 + random.nextInt(3);
                    int val = random.nextBoolean() ? 255 : 0;
                    bitmap.setPixel(x, y, Color.argb(alpha, val, val, val));
                }
            }
            BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
            drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mCachedNoiseDrawable = drawable;
        }
        return mCachedNoiseDrawable;
    }

    private void animateSearchFocus(BlurView blurView, View cardView, boolean focused) {
        float targetScale = focused ? 1.0f : 0.98f;
        float targetBlur = focused ? 20f : 14f;
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        boolean isBlurEnabled = wm != null && wm.isCrossWindowBlurEnabled();
        float targetAlpha = isBlurEnabled ? (focused ? 0.60f : 0.45f) : 1.0f;
        
        cardView.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(220)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
                
        refreshGlassBackground(blurView, targetAlpha);
        
        float finalRadius = targetBlur;
        if (wm != null && !wm.isCrossWindowBlurEnabled()) {
            finalRadius = 0f;
        }
        blurView.setBlurRadius(finalRadius);
        blurView.invalidate();
    }

    private void updateHomepageUI() {
        final boolean newTwoPaneState = ActivityEmbeddingUtils.isAlreadyEmbedded(this);
        if (mIsTwoPane != newTwoPaneState) {
            mIsTwoPane = newTwoPaneState;
            updateHomepageAppBar();
            updateHomepageBackground();
            updateHomepagePaddings();
        }
        updateSplitLayout();
    }

    private void updateHomepageBackground() {

        final Window window = getWindow();
        final int color = mIsTwoPane
                ? getColor(R.color.settings_two_pane_background_color)
                : Utils.getColorAttrDefaultColor(this, android.R.attr.colorBackground);

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Update content background.
        findViewById(android.R.id.content).setBackgroundColor(color);
        findViewById(R.id.app_bar_container).setBackgroundColor(color);
    }

    // CRASH-FIX: Removed suggestion-related method
    /*
    private void showSuggestionFragment(boolean scrollNeeded) {
        final Class<? extends Fragment> fragmentClass = FeatureFactory.getFeatureFactory()
                .getSuggestionFeatureProvider().getSuggestionFragment();
        if (fragmentClass == null) {
            return;
        }

        mSuggestionView = findViewById(R.id.suggestion_content);
        mTwoPaneSuggestionView = findViewById(R.id.two_pane_suggestion_content);
        mHomepageView = findViewById(R.id.settings_homepage_container);
        // Hide the homepage for preparing the suggestion. If scrolling is needed, the list views
        // should be initialized in the invisible homepage view to prevent a scroll flicker.
        mHomepageView.setVisibility(scrollNeeded ? View.INVISIBLE : View.GONE);
        // Schedule a timer to show the homepage and hide the suggestion on timeout.
        mHomepageView.postDelayed(() -> showHomepageWithSuggestion(false),
                HOMEPAGE_LOADING_TIMEOUT_MS);
            showFragment(new SuggestionFragCreator(fragmentClass, /* isTwoPaneLayout= * / false),
                    R.id.suggestion_content);
            if (mIsEmbeddingActivityEnabled) {
                showFragment(new SuggestionFragCreator(fragmentClass, /* isTwoPaneLayout= * / true),
                        R.id.two_pane_suggestion_content);
        }
    }
    */

    private <T extends Fragment> T showFragment(FragmentCreator<T> fragmentCreator, int id) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        T showFragment = (T) fragmentManager.findFragmentById(id);

        if (showFragment == null) {
            showFragment = fragmentCreator.create();
            fragmentCreator.init(showFragment);
            fragmentTransaction.add(id, showFragment);
        } else {
            fragmentCreator.init(showFragment);
            fragmentTransaction.show(showFragment);
        }
        fragmentTransaction.commit();
        return showFragment;
    }

    private boolean shouldLaunchDeepLinkIntentToRight() {
        if (!ActivityEmbeddingUtils.isSettingsSplitEnabled(this)
                || !FeatureFlagUtils.isEnabled(this,
                        FeatureFlagUtils.SETTINGS_SUPPORT_LARGE_SCREEN)) {
            return false;
        }

        Intent intent = getIntent();
        return intent != null && TextUtils.equals(intent.getAction(),
                ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY);
    }

    private void launchDeepLinkIntentToRight() {
        if (!(this instanceof DeepLinkHomepageActivity
                || this instanceof DeepLinkHomepageActivityInternal)) {
            Log.e(TAG, "Not a deep link component");
            finish();
            return;
        }

        if (!WizardManagerHelper.isUserSetupComplete(this)) {
            Log.e(TAG, "Cancel deep link before SUW completed");
            finish();
            return;
        }

        final Intent intent = getIntent();
        final String intentUriString = intent.getStringExtra(
                EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI);
        if (TextUtils.isEmpty(intentUriString)) {
            Log.e(TAG, "No EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_INTENT_URI to deep link");
            finish();
            return;
        }

        final Intent targetIntent;
        try {
            targetIntent = Intent.parseUri(intentUriString, Intent.URI_INTENT_SCHEME);
            fixPermissionControllerIntent(targetIntent);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Failed to parse deep link intent: " + e);
            finish();
            return;
        }

        targetIntent.setData(intent.getParcelableExtra(
                SettingsHomepageActivity.EXTRA_SETTINGS_LARGE_SCREEN_DEEP_LINK_INTENT_DATA));
        final ComponentName targetComponentName = targetIntent.resolveActivity(getPackageManager());
        if (targetComponentName == null) {
            Log.e(TAG, "No valid target for the deep link intent: " + targetIntent);
            finish();
            return;
        }

        ActivityInfo targetActivityInfo;
        try {
            targetActivityInfo = getPackageManager().getActivityInfo(targetComponentName,
                    /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get target ActivityInfo: " + e);
            finish();
            return;
        }

        UserHandle user = intent.getParcelableExtra(EXTRA_USER_HANDLE, UserHandle.class);
        String caller = getInitialReferrer();
        int callerUid = -1;
        if (caller != null) {
            try {
                callerUid = getPackageManager().getApplicationInfoAsUser(caller,
                        ApplicationInfoFlags.of(/* flags= */ 0),
                        user != null ? user.getIdentifier() : getUserId()).uid;
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Not able to get callerUid: " + e);
                finish();
                return;
            }
        }

        if (!hasPrivilegedAccess(caller, callerUid, targetActivityInfo.packageName)) {
            if (!targetActivityInfo.exported) {
                Log.e(TAG, "Target Activity is not exported");
                finish();
                return;
            }

            if (!isCallingAppPermitted(targetActivityInfo.permission, callerUid)) {
                Log.e(TAG, "Calling app must have the permission of deep link Activity");
                finish();
                return;
            }
        }

        // Only allow FLAG_GRANT_READ/WRITE_URI_PERMISSION if calling app has the permission to
        // access specified Uri.
        int uriPermissionFlags = targetIntent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (targetIntent.getData() != null
                && uriPermissionFlags != 0
                && checkUriPermission(targetIntent.getData(), /* pid= */ -1, callerUid,
                        uriPermissionFlags) == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Calling app must have the permission to access Uri and grant permission");
            finish();
            return;
        }

        targetIntent.setComponent(targetComponentName);

        // To prevent launchDeepLinkIntentToRight again for configuration change.
        intent.setAction(null);

        targetIntent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        // Sender of intent may want to send intent extra data to the destination of targetIntent.
        targetIntent.replaceExtras(intent);

        targetIntent.putExtra(EXTRA_IS_FROM_SETTINGS_HOMEPAGE, true);
        targetIntent.putExtra(SettingsActivity.EXTRA_IS_FROM_SLICE, false);

        // Set 2-pane pair rule for the deep link page.
        ActivityEmbeddingRulesController.registerTwoPanePairRule(this,
                new ComponentName(getApplicationContext(), getClass()),
                targetComponentName,
                targetIntent.getAction(),
                SplitRule.FinishBehavior.ALWAYS,
                SplitRule.FinishBehavior.ALWAYS,
                true /* clearTop */);
        ActivityEmbeddingRulesController.registerTwoPanePairRule(this,
                new ComponentName(getApplicationContext(), Settings.class),
                targetComponentName,
                targetIntent.getAction(),
                SplitRule.FinishBehavior.ALWAYS,
                SplitRule.FinishBehavior.ALWAYS,
                true /* clearTop */);

        if (user != null) {
            startActivityAsUser(targetIntent, user);
        } else {
            startActivity(targetIntent);
        }
    }

    // Check if the caller has privileged access to launch the target page.
    private boolean hasPrivilegedAccess(String callerPkg, int callerUid, String targetPackage) {
        if (TextUtils.equals(callerPkg, getPackageName())) {
            return true;
        }

        int targetUid = -1;
        try {
            targetUid = getPackageManager().getApplicationInfo(targetPackage,
                    ApplicationInfoFlags.of(/* flags= */ 0)).uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Not able to get targetUid: " + e);
            return false;
        }

        // When activityInfo.exported is false, Activity still can be launched if applications have
        // the same user ID.
        if (UserHandle.isSameApp(callerUid, targetUid)) {
            return true;
        }

        // When activityInfo.exported is false, Activity still can be launched if calling app has
        // root or system privilege.
        int callingAppId = UserHandle.getAppId(callerUid);
        if (callingAppId == Process.ROOT_UID || callingAppId == Process.SYSTEM_UID) {
            return true;
        }

        return false;
    }

    @VisibleForTesting
    String getInitialReferrer() {
        String referrer = getCurrentReferrer();
        if (!TextUtils.equals(referrer, getPackageName())) {
            return referrer;
        }

        String initialReferrer = getIntent().getStringExtra(EXTRA_INITIAL_REFERRER);
        return TextUtils.isEmpty(initialReferrer) ? referrer : initialReferrer;
    }

    @VisibleForTesting
    String getCurrentReferrer() {
        Intent intent = getIntent();
        // Clear extras to get the real referrer
        intent.removeExtra(Intent.EXTRA_REFERRER);
        intent.removeExtra(Intent.EXTRA_REFERRER_NAME);
        Uri referrer = getReferrer();
        return referrer != null ? referrer.getHost() : null;
    }

    @VisibleForTesting
    boolean isCallingAppPermitted(String permission, int callerUid) {
        return TextUtils.isEmpty(permission)
                || checkPermission(permission, /* pid= */ -1, callerUid)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private String getHighlightMenuKey() {
        final Intent intent = getIntent();
        if (intent != null && TextUtils.equals(intent.getAction(),
                ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)) {
            final String menuKey = intent.getStringExtra(
                    EXTRA_SETTINGS_EMBEDDED_DEEP_LINK_HIGHLIGHT_MENU_KEY);
            if (!TextUtils.isEmpty(menuKey)) {
                return maybeRemapMenuKey(menuKey);
            }
        }
        return getString(DEFAULT_HIGHLIGHT_MENU_KEY);
    }

    private String maybeRemapMenuKey(String menuKey) {
        boolean isPrivacyOrSecurityMenuKey =
                getString(R.string.menu_key_privacy).equals(menuKey)
                        || getString(R.string.menu_key_security).equals(menuKey);
        boolean isSafetyCenterMenuKey = getString(R.string.menu_key_safety_center).equals(menuKey);

        if (isPrivacyOrSecurityMenuKey && SafetyCenterManagerWrapper.get().isEnabled(this)) {
            return getString(R.string.menu_key_safety_center);
        }
        if (isSafetyCenterMenuKey && !SafetyCenterManagerWrapper.get().isEnabled(this)) {
            // We don't know if security or privacy, default to security as it is above.
            return getString(R.string.menu_key_security);
        }
        return menuKey;
    }

    private void reloadHighlightMenuKey() {
        mMainFragment.getArguments().putString(SettingsActivity.EXTRA_FRAGMENT_ARG_KEY,
                getHighlightMenuKey());
        mMainFragment.reloadHighlightMenuKey();
    }

    private void initHomepageContainer() {
        final View view = findViewById(R.id.homepage_container);
        view.setFocusableInTouchMode(true);
        view.requestFocus();

        final View scrollableContainer = findViewById(R.id.main_content_scrollable_container);
        if (scrollableContainer != null) {
            scrollableContainer.setScrollCaptureHint(
                    View.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS);
        }
    }

    private int mScrollableBottomPadding = -1;

    private void updateHomepageAppBar() {
        updateAppBarMinHeight();
        View bottomContainer = findViewById(R.id.bottom_search_bar_container);
        View bottomShadow = findViewById(R.id.bottom_gradient_shadow);
        View titleContainer = findViewById(R.id.homepage_title_container);
        View appBarContainer = findViewById(R.id.app_bar_container);
        // The scrolling list keeps room at the bottom for the floating search bar, which the two
        // pane layout replaces with the one at the top; drop that room there.
        View scrollableContainer = findViewById(R.id.main_content_scrollable_container);
        if (scrollableContainer != null) {
            if (mScrollableBottomPadding < 0) {
                mScrollableBottomPadding = scrollableContainer.getPaddingBottom();
            }
            scrollableContainer.setPadding(scrollableContainer.getPaddingLeft(),
                    scrollableContainer.getPaddingTop(), scrollableContainer.getPaddingRight(),
                    mIsTwoPane ? 0 : mScrollableBottomPadding);
        }
        if (mIsTwoPane) {
            findViewById(R.id.homepage_app_bar_regular_phone_view).setVisibility(View.GONE);
            if (bottomContainer != null) {
                bottomContainer.setVisibility(View.GONE);
            }
            if (bottomShadow != null) {
                bottomShadow.setVisibility(View.GONE);
            }
            if (titleContainer != null) {
                titleContainer.setVisibility(View.GONE);
            }
            if (appBarContainer != null) {
                appBarContainer.setVisibility(View.VISIBLE);
            }
            findViewById(R.id.homepage_app_bar_two_pane_view).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.homepage_app_bar_regular_phone_view).setVisibility(View.VISIBLE);
            if (bottomContainer != null) {
                bottomContainer.setVisibility(View.VISIBLE);
            }
            if (bottomShadow != null) {
                bottomShadow.setVisibility(View.VISIBLE);
            }
            if (titleContainer != null) {
                titleContainer.setVisibility(View.VISIBLE);
            }
            if (appBarContainer != null) {
                appBarContainer.setVisibility(View.GONE);
            }
            findViewById(R.id.homepage_app_bar_two_pane_view).setVisibility(View.GONE);
        }
    }

    private void updateHomepagePaddings() {
        if (!mIsEmbeddingActivityEnabled) {
            return;
        }
        if (mIsTwoPane) {
            int padding = getResources().getDimensionPixelSize(
                    R.dimen.homepage_padding_horizontal_two_pane);
            mMainFragment.setPaddingHorizontal(padding);
        } else {
            mMainFragment.setPaddingHorizontal(0);
        }
        mMainFragment.updatePreferencePadding(mIsTwoPane);
    }

    private void updateAppBarMinHeight() {
        final int searchBarHeight = getResources().getDimensionPixelSize(R.dimen.search_bar_height);
        final int margin = getResources().getDimensionPixelSize(
                mIsEmbeddingActivityEnabled && mIsTwoPane
                        ? R.dimen.homepage_app_bar_padding_two_pane
                        : R.dimen.search_bar_margin);
        findViewById(R.id.app_bar_container).setMinimumHeight(searchBarHeight + margin * 2);
    }

    // CRASH-FIX: Removed suggestion-related class
    /*
    private static class SuggestionFragCreator implements FragmentCreator {

        private final Class<? extends Fragment> mClass;
        private final boolean mIsTwoPaneLayout;

        SuggestionFragCreator(Class<? extends Fragment> clazz, boolean isTwoPaneLayout) {
            mClass = clazz;
            mIsTwoPaneLayout = isTwoPaneLayout;
        }

        @Override
        public Fragment create() {
            try {
                Fragment fragment = mClass.getConstructor().newInstance();
                return fragment;
            } catch (Exception e) {
                Log.w(TAG, "Cannot show fragment", e);
            }
            return null;
        }

        @Override
        public void init(Fragment fragment) {
            if (fragment instanceof SplitLayoutListener) {
                ((SplitLayoutListener) fragment).setSplitLayoutSupported(mIsTwoPaneLayout);
            }
        }
    }
    */

    /** The callback invoked while AE splitting. */
    private static class SplitInfoCallback implements Consumer<List<SplitInfo>> {
        private final SettingsHomepageActivity mActivity;

        private boolean mIsSplitUpdatedUI = false;

        SplitInfoCallback(SettingsHomepageActivity activity) {
            mActivity = activity;
        }

        @Override
        public void accept(List<SplitInfo> splitInfoList) {
            if (!splitInfoList.isEmpty() && !mIsSplitUpdatedUI && !mActivity.isFinishing()
                    && ActivityEmbeddingUtils.isAlreadyEmbedded(mActivity)) {
                mIsSplitUpdatedUI = true;
                mActivity.updateHomepageUI();
            }
        }
    }

    private void fixPermissionControllerIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            String currentPermissionController = getPackageManager().getPermissionControllerPackageName();
            if (TextUtils.isEmpty(currentPermissionController)
                    || "com.android.permissioncontroller".equals(currentPermissionController)) {
                return;
            }
            if ("com.android.permissioncontroller".equals(intent.getPackage())) {
                intent.setPackage(currentPermissionController);
            }
            ComponentName component = intent.getComponent();
            if (component != null && "com.android.permissioncontroller".equals(component.getPackageName())) {
                intent.setComponent(new ComponentName(currentPermissionController, component.getClassName()));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fix PermissionController intent", e);
        }
    }
}
