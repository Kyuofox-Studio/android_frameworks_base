/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controls the state and flow for screenshots.
 */
public class ScreenshotController {
    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Consumer<Uri> finisher;
        public ScreenshotController.ActionsReadyListener mActionsReadyListener;

        void clearImage() {
            image = null;
        }
    }

    /**
     * Structure returned by the SaveImageInBackgroundTask
     */
    static class SavedImageData {
        public Uri uri;
        public Notification.Action shareAction;
        public Notification.Action editAction;
        public Notification.Action deleteAction;
        public List<Notification.Action> smartActions;

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            uri = null;
            shareAction = null;
            editAction = null;
            deleteAction = null;
            smartActions = null;
        }
    }

    abstract static class ActionsReadyListener {
        abstract void onActionsReady(ScreenshotController.SavedImageData imageData);
    }

    private static final String TAG = "ScreenshotController";

    // These strings are used for communicating the action invoked to
    // ScreenshotNotificationSmartActionsProvider.
    static final String EXTRA_ACTION_TYPE = "android:screenshot_action_type";
    static final String EXTRA_ID = "android:screenshot_id";
    static final String ACTION_TYPE_DELETE = "Delete";
    static final String ACTION_TYPE_SHARE = "Share";
    static final String ACTION_TYPE_EDIT = "Edit";
    static final String EXTRA_SMART_ACTIONS_ENABLED = "android:smart_actions_enabled";
    static final String EXTRA_ACTION_INTENT = "android:screenshot_action_intent";

    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String EXTRA_CANCEL_NOTIFICATION = "android:screenshot_cancel_notification";
    static final String EXTRA_DISALLOW_ENTER_PIP = "android:screenshot_disallow_enter_pip";


    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 6000;

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private final Context mContext;
    private final ScreenshotNotificationsController mNotificationsController;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final UiEventLogger mUiEventLogger;

    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics;
    private final AccessibilityManager mAccessibilityManager;
    private final MediaActionSound mCameraSound;
    private final ScrollCaptureClient mScrollCaptureClient;
    private final DeviceConfigProxy mConfigProxy;

    private final Binder mWindowToken;
    private ScreenshotView mScreenshotView;
    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;

    private Animator mScreenshotAnimation;
    private Animator mDismissAnimation;

    private Runnable mOnCompleteRunnable;
    private boolean mInDarkMode;
    private boolean mDirectionLTR;
    private boolean mOrientationPortrait;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT);
                    ScreenshotController.this.dismissScreenshot(false);
                    mOnCompleteRunnable.run();
                    break;
                default:
                    break;
            }
        }
    };

    @Inject
    ScreenshotController(
            Context context,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController screenshotNotificationsController,
            ScrollCaptureClient scrollCaptureClient,
            UiEventLogger uiEventLogger,
            DeviceConfigProxy configProxy) {
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsController;
        mScrollCaptureClient = scrollCaptureClient;
        mUiEventLogger = uiEventLogger;

        final DisplayManager dm = requireNonNull(context.getSystemService(DisplayManager.class));
        mDisplay = dm.getDisplay(DEFAULT_DISPLAY);
        mContext = context.createDisplayContext(mDisplay);
        mWindowManager = mContext.getSystemService(WindowManager.class);

        mAccessibilityManager = AccessibilityManager.getInstance(mContext);
        mConfigProxy = configProxy;

        reloadAssets();
        Configuration config = mContext.getResources().getConfiguration();
        mInDarkMode = config.isNightModeActive();
        mDirectionLTR = config.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
        mOrientationPortrait = config.orientation == ORIENTATION_PORTRAIT;
        mWindowToken = new Binder("ScreenshotController");
        mScrollCaptureClient.setHostWindowToken(mWindowToken);

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.setFitInsetsTypes(0 /* types */);
        mWindowLayoutParams.token = mWindowToken;

        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    void takeScreenshotFullscreen(Consumer<Uri> finisher, Runnable onComplete) {
        mOnCompleteRunnable = onComplete;

        mDisplay.getRealMetrics(mDisplayMetrics);
        takeScreenshotInternal(
                finisher,
                new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
    }

    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, int userId, ComponentName topComponent,
            Consumer<Uri> finisher, Runnable onComplete) {
        // TODO: use task Id, userId, topComponent for smart handler
        mOnCompleteRunnable = onComplete;

        if (screenshot == null) {
            Log.e(TAG, "Got null bitmap from screenshot message");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            mOnCompleteRunnable.run();
            return;
        }

        if (aspectRatiosMatch(screenshot, visibleInsets, screenshotScreenBounds)) {
            saveScreenshot(screenshot, finisher, screenshotScreenBounds, visibleInsets, false);
        } else {
            saveScreenshot(screenshot, finisher,
                    new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight()), Insets.NONE,
                    true);
        }
    }

    /**
     * Displays a screenshot selector
     */
    @SuppressLint("ClickableViewAccessibility")
    void takeScreenshotPartial(final Consumer<Uri> finisher, Runnable onComplete) {
        dismissScreenshot(true);
        mOnCompleteRunnable = onComplete;

        mWindowManager.addView(mScreenshotView, mWindowLayoutParams);

        mScreenshotView.takePartialScreenshot(
                rect -> takeScreenshotInternal(finisher, rect));
    }

    boolean isDismissing() {
        return (mDismissAnimation != null && mDismissAnimation.isRunning());
    }

    /**
     * Clears current screenshot
     */
    void dismissScreenshot(boolean immediate) {
        Log.v(TAG, "clearing screenshot");
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        mScreenshotView.getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mScreenshotView);
        if (!immediate) {
            mDismissAnimation = mScreenshotView.createScreenshotDismissAnimation();
            mDismissAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    clearScreenshot();
                }
            });
            mDismissAnimation.start();
        } else {
            clearScreenshot();
        }
    }

    private void onConfigChanged(Configuration newConfig) {
        boolean needsUpdate = false;
        // dark mode
        if (newConfig.isNightModeActive()) {
            // Night mode is active, we're using dark theme
            if (!mInDarkMode) {
                mInDarkMode = true;
                needsUpdate = true;
            }
        } else {
            // Night mode is not active, we're using the light theme
            if (mInDarkMode) {
                mInDarkMode = false;
                needsUpdate = true;
            }
        }

        // RTL configuration
        switch (newConfig.getLayoutDirection()) {
            case View.LAYOUT_DIRECTION_LTR:
                if (!mDirectionLTR) {
                    mDirectionLTR = true;
                    needsUpdate = true;
                }
                break;
            case View.LAYOUT_DIRECTION_RTL:
                if (mDirectionLTR) {
                    mDirectionLTR = false;
                    needsUpdate = true;
                }
                break;
        }

        // portrait/landscape orientation
        switch (newConfig.orientation) {
            case ORIENTATION_PORTRAIT:
                if (!mOrientationPortrait) {
                    mOrientationPortrait = true;
                    needsUpdate = true;
                }
                break;
            case ORIENTATION_LANDSCAPE:
                if (mOrientationPortrait) {
                    mOrientationPortrait = false;
                    needsUpdate = true;
                }
                break;
        }

        if (needsUpdate) {
            reloadAssets();
        }
    }

    /**
     * Update assets (called when the dark theme status changes). We only need to update the dismiss
     * button and the actions container background, since the buttons are re-inflated on demand.
     */
    private void reloadAssets() {
        boolean wasAttached = mScreenshotView != null && mScreenshotView.isAttachedToWindow();
        if (wasAttached) {
            mWindowManager.removeView(mScreenshotView);
        }

        // Inflate the screenshot layout
        mScreenshotView = (ScreenshotView)
                LayoutInflater.from(mContext).inflate(R.layout.global_screenshot, null);

        // TODO(159460485): Remove this when focus is handled properly in the system
        mScreenshotView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                // Once the user touches outside, stop listening for input
                setWindowFocusable(false);
            }
            return false;
        });

        mScreenshotView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissScreenshot(false);
                return true;
            }
            return false;
        });

        if (wasAttached) {
            mWindowManager.addView(mScreenshotView, mWindowLayoutParams);
        }
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshotInternal(Consumer<Uri> finisher, Rect crop) {
        // copy the input Rect, since SurfaceControl.screenshot can mutate it
        Rect screenRect = new Rect(crop);
        int width = crop.width();
        int height = crop.height();
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        final SurfaceControl.DisplayCaptureArgs captureArgs =
                new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                        .setSourceCrop(crop)
                        .setSize(width, height)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                SurfaceControl.captureDisplay(captureArgs);
        Bitmap screenshot = screenshotBuffer == null ? null : screenshotBuffer.asBitmap();

        if (screenshot == null) {
            Log.e(TAG, "Screenshot bitmap was null");
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            mOnCompleteRunnable.run();
            return;
        }

        saveScreenshot(screenshot, finisher, screenRect, Insets.NONE, true);
    }

    private void saveScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect,
            Insets screenInsets, boolean showFlash) {
        if (mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event =
                    new AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setContentDescription(
                    mContext.getResources().getString(R.string.screenshot_saving_title));
            mAccessibilityManager.sendAccessibilityEvent(event);
        }

        if (mScreenshotView.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (mDismissAnimation == null || !mDismissAnimation.isRunning()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED);
            }
            dismissScreenshot(true);
        }

        mScreenBitmap = screenshot;

        if (!isUserSetupComplete()) {
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(finisher);
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        onConfigChanged(mContext.getResources().getConfiguration());

        if (mDismissAnimation != null && mDismissAnimation.isRunning()) {
            mDismissAnimation.cancel();
        }

        // The window is focusable by default
        setWindowFocusable(true);

        // Start the post-screenshot animation
        startAnimation(finisher, screenRect, screenInsets, showFlash);

        if (mConfigProxy.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SCREENSHOT_SCROLLING_ENABLED, false)) {
            mScrollCaptureClient.request(DEFAULT_DISPLAY, (connection) ->
                    mScreenshotView.showScrollChip(() ->
                            runScrollCapture(connection,
                                    () -> dismissScreenshot(false))));
        }
    }

    private void runScrollCapture(ScrollCaptureClient.Connection connection,
            Runnable after) {
        new ScrollCaptureController(mContext, connection).run(after);
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private void saveScreenshotAndToast(Consumer<Uri> finisher) {
        // Play the shutter sound to notify that we've taken a screenshot
        mScreenshotHandler.post(() -> {
            mCameraSound.play(MediaActionSound.SHUTTER_CLICK);
        });

        saveScreenshotInWorkerThread(finisher,
                new ScreenshotController.ActionsReadyListener() {
                    @Override
                    void onActionsReady(ScreenshotController.SavedImageData imageData) {
                        finisher.accept(imageData.uri);
                        if (imageData.uri == null) {
                            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
                            mNotificationsController.notifyScreenshotError(
                                    R.string.screenshot_failed_to_save_text);
                        } else {
                            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);

                            mScreenshotHandler.post(() -> {
                                Toast.makeText(mContext, R.string.screenshot_saved_title,
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                });
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Consumer<Uri> finisher, Rect screenRect, Insets screenInsets,
            boolean showFlash) {
        mScreenshotHandler.post(() -> {
            if (!mScreenshotView.isAttachedToWindow()) {
                mWindowManager.addView(mScreenshotView, mWindowLayoutParams);
            }

            mScreenshotView.prepareForAnimation(mScreenBitmap, screenRect, screenInsets);

            mScreenshotHandler.post(() -> {
                mScreenshotView.getViewTreeObserver().addOnComputeInternalInsetsListener(
                        mScreenshotView);

                mScreenshotAnimation =
                        mScreenshotView.createScreenshotDropInAnimation(screenRect, showFlash,
                                this::onElementTapped);

                saveScreenshotInWorkerThread(finisher,
                        new ScreenshotController.ActionsReadyListener() {
                            @Override
                            void onActionsReady(
                                    ScreenshotController.SavedImageData imageData) {
                                showUiOnActionsReady(imageData);
                            }
                        });

                // Play the shutter sound to notify that we've taken a screenshot
                mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

                mScreenshotAnimation.start();
            });
        });
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(
            Consumer<Uri> finisher,
            @Nullable ScreenshotController.ActionsReadyListener actionsReadyListener) {
        ScreenshotController.SaveImageInBackgroundData
                data = new ScreenshotController.SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(
                    new ScreenshotController.ActionsReadyListener() {
                        @Override
                        void onActionsReady(ScreenshotController.SavedImageData imageData) {
                            logSuccessOnActionsReady(imageData);
                        }
                    });
        }

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, mScreenshotSmartActions, data);
        mSaveInBgTask.execute();
    }

    /**
     * Sets up the action shade and its entrance animation, once we get the screenshot URI.
     */
    private void showUiOnActionsReady(ScreenshotController.SavedImageData imageData) {
        logSuccessOnActionsReady(imageData);

        AccessibilityManager accessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);

        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        mScreenshotHandler.sendMessageDelayed(
                mScreenshotHandler.obtainMessage(MESSAGE_CORNER_TIMEOUT),
                timeoutMs);

        if (imageData.uri != null) {
            mScreenshotHandler.post(() -> {
                if (mScreenshotAnimation != null && mScreenshotAnimation.isRunning()) {
                    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            mScreenshotView.setChipIntents(
                                    imageData, event -> onElementTapped(event));
                        }
                    });
                } else {
                    mScreenshotView.setChipIntents(
                            imageData, this::onElementTapped);
                }
            });
        }
    }

    private void onElementTapped(ScreenshotEvent event) {
        mUiEventLogger.log(event);
        dismissScreenshot(false);
        mOnCompleteRunnable.run();
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logSuccessOnActionsReady(ScreenshotController.SavedImageData imageData) {
        if (imageData.uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_save_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
        }
    }

    private void clearScreenshot() {
        if (mScreenshotView.isAttachedToWindow()) {
            mWindowManager.removeView(mScreenshotView);
        }

        mScreenshotView.reset();
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }


    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        if (focusable) {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (mScreenshotView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mScreenshotView, mWindowLayoutParams);
        }
    }

    /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
    private static boolean aspectRatiosMatch(Bitmap bitmap, Insets bitmapInsets,
            Rect screenBounds) {
        int insettedWidth = bitmap.getWidth() - bitmapInsets.left - bitmapInsets.right;
        int insettedHeight = bitmap.getHeight() - bitmapInsets.top - bitmapInsets.bottom;

        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            Log.e(TAG, String.format(
                    "Provided bitmap and insets create degenerate region: %dx%d %s",
                    bitmap.getWidth(), bitmap.getHeight(), bitmapInsets));
            return false;
        }

        float insettedBitmapAspect = ((float) insettedWidth) / insettedHeight;
        float boundsAspect = ((float) screenBounds.width()) / screenBounds.height();

        boolean matchWithinTolerance = Math.abs(insettedBitmapAspect - boundsAspect) < 0.1f;
        if (!matchWithinTolerance) {
            Log.d(TAG, String.format("aspectRatiosMatch: don't match bitmap: %f, bounds: %f",
                    insettedBitmapAspect, boundsAspect));
        }

        return matchWithinTolerance;
    }
}
