/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.VolumePolicy;
import android.os.Bundle;
import android.view.WindowManager.LayoutParams;

import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.tristate.TriStateUiController;
import com.android.systemui.tristate.TriStateUiControllerImpl;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.statusbar.NotificationMediaManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Implementation of VolumeComponent backed by the new volume dialog.
 */
public class VolumeDialogComponent implements VolumeComponent, TunerService.Tunable,
        VolumeDialogControllerImpl.UserActivityListener, TriStateUiController.UserActivityListener {

    public static final String VOLUME_DOWN_SILENT = "sysui_volume_down_silent";
    public static final String VOLUME_UP_SILENT = "sysui_volume_up_silent";
    public static final String VOLUME_SILENT_DO_NOT_DISTURB = "sysui_do_not_disturb";

    public static final boolean DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT = false;
    public static final boolean DEFAULT_VOLUME_UP_TO_EXIT_SILENT = false;
    public static final boolean DEFAULT_DO_NOT_DISTURB_WHEN_SILENT = false;

    private final SystemUI mSysui;
    protected final Context mContext;
    private NotificationMediaManager mMediaManager;
    private final VolumeDialogControllerImpl mController;
    private TriStateUiControllerImpl mTriStateController;
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_LOCALE
            | ActivityInfo.CONFIG_ASSETS_PATHS | ActivityInfo.CONFIG_UI_MODE);
    private VolumeDialog mDialog;
    private VolumePolicy mVolumePolicy = new VolumePolicy(
            DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT,  // volumeDownToEnterSilent
            DEFAULT_VOLUME_UP_TO_EXIT_SILENT,  // volumeUpToExitSilent
            DEFAULT_DO_NOT_DISTURB_WHEN_SILENT,  // doNotDisturbWhenSilent
            400    // vibrateToSilentDebounce
    );

    public VolumeDialogComponent(SystemUI sysui, Context context) {
        mSysui = sysui;
        mContext = context;
        mController = (VolumeDialogControllerImpl) Dependency.get(VolumeDialogController.class);
        mController.setUserActivityListener(this);
        boolean hasAlertSlider = mContext.getResources().
                getBoolean(com.android.internal.R.bool.config_hasAlertSlider);
        // Allow plugins to reference the VolumeDialogController.
        Dependency.get(PluginDependencyProvider.class)
                .allowPluginDependency(VolumeDialogController.class);
        Dependency.get(ExtensionController.class).newExtension(VolumeDialog.class)
                .withPlugin(VolumeDialog.class)
                .withDefault(this::createDefault)
                .withCallback(dialog -> {
                    if (mDialog != null) {
                        mDialog.destroy();
                    }
                    mDialog = dialog;
                    mDialog.init(LayoutParams.TYPE_VOLUME_OVERLAY, mVolumeDialogCallback);
                    if (hasAlertSlider) {
                        if (mTriStateController != null) {
                            mTriStateController.destroy();
                        }
                        mTriStateController = new TriStateUiControllerImpl(mContext);
                        mTriStateController.init(LayoutParams.TYPE_VOLUME_OVERLAY, this);
                    }
                }).build();
        applyConfiguration();
        Dependency.get(TunerService.class).addTunable(this, VOLUME_DOWN_SILENT, VOLUME_UP_SILENT,
                VOLUME_SILENT_DO_NOT_DISTURB);
    }

    protected VolumeDialog createDefault() {
        VolumeDialogImpl impl = new VolumeDialogImpl(mContext);
        impl.setStreamImportant(AudioManager.STREAM_SYSTEM, false);
        impl.setAutomute(true);
        impl.setSilentMode(false);
        impl.initText(mMediaManager);
        return impl;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        boolean volumeDownToEnterSilent = mVolumePolicy.volumeDownToEnterSilent;
        boolean volumeUpToExitSilent = mVolumePolicy.volumeUpToExitSilent;
        boolean doNotDisturbWhenSilent = mVolumePolicy.doNotDisturbWhenSilent;

        if (VOLUME_DOWN_SILENT.equals(key)) {
            volumeDownToEnterSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT);
        } else if (VOLUME_UP_SILENT.equals(key)) {
            volumeUpToExitSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_VOLUME_UP_TO_EXIT_SILENT);
        } else if (VOLUME_SILENT_DO_NOT_DISTURB.equals(key)) {
            doNotDisturbWhenSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_DO_NOT_DISTURB_WHEN_SILENT);
        }

        setVolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent, doNotDisturbWhenSilent,
                mVolumePolicy.vibrateToSilentDebounce);
    }

    private void setVolumePolicy(boolean volumeDownToEnterSilent, boolean volumeUpToExitSilent,
            boolean doNotDisturbWhenSilent, int vibrateToSilentDebounce) {
        mVolumePolicy = new VolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent,
                doNotDisturbWhenSilent, vibrateToSilentDebounce);
        mController.setVolumePolicy(mVolumePolicy);
    }

    void setEnableDialogs(boolean volumeUi, boolean safetyWarning) {
        mController.setEnableDialogs(volumeUi, safetyWarning);
    }

    @Override
    public void onUserActivity() {
        final KeyguardViewMediator kvm = mSysui.getComponent(KeyguardViewMediator.class);
        if (kvm != null) {
            kvm.userActivity();
        }
    }

    private void applyConfiguration() {
        mController.setVolumePolicy(mVolumePolicy);
        mController.showDndTile(true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            mController.mCallbacks.onConfigurationChanged();
        }
    }

    @Override
    public void dismissNow() {
        mController.dismiss();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // noop
    }

    @Override
    public void register() {
        mController.register();
        DndTile.setCombinedIcon(mContext, true);
    }

    @Override
    public void initDependencies(NotificationMediaManager mediaManager) {
        mMediaManager = mediaManager;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }

    private void startSettings(Intent intent) {
        Dependency.get(ActivityStarter.class).startActivity(intent,
                true /* onlyProvisioned */, true /* dismissShade */);
    }

    @Override
    public void onTriStateUserActivity() {
        onUserActivity();
    }

    private final VolumeDialogImpl.Callback mVolumeDialogCallback = new VolumeDialogImpl.Callback() {
        @Override
        public void onZenSettingsClicked() {
            startSettings(ZenModePanel.ZEN_SETTINGS);
        }

        @Override
        public void onZenPrioritySettingsClicked() {
            startSettings(ZenModePanel.ZEN_PRIORITY_SETTINGS);
        }
    };

}
