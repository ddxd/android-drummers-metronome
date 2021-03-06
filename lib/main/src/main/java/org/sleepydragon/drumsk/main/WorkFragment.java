package org.sleepydragon.drumsk.main;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;

import org.sleepydragon.drumsk.metronome.IMetronomeService;
import org.sleepydragon.drumsk.metronome.MetronomeConfig;
import org.sleepydragon.drumsk.metronome.MetronomeService;
import org.sleepydragon.drumsk.ui.api.MainFragment;
import org.sleepydragon.drumsk.util.LifecycleLogger;
import org.sleepydragon.drumsk.util.Logger;

import static org.sleepydragon.drumsk.util.Assert.assertMainThread;

public class WorkFragment extends Fragment
        implements MainFragment.TargetFragmentCallbacks, ServiceConnection {

    @NonNull
    private final Logger mLogger;
    @NonNull
    private final LifecycleLogger mLifecycleLogger;

    private Context mAppContext;
    private IMetronomeService mMetronomeService;

    public WorkFragment() {
        mLogger = new Logger(this);
        mLifecycleLogger = new LifecycleLogger(mLogger);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        mLifecycleLogger.onCreate(savedInstanceState);
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        mAppContext = getContext().getApplicationContext();

        {
            final Intent intent = new Intent(mAppContext, MetronomeService.class);
            if (!mAppContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                throw new RuntimeException("service not found: " + intent);
            }
        }
    }

    @Override
    public void onDestroy() {
        mLifecycleLogger.onDestroy();
        mAppContext.unbindService(this);
        super.onDestroy();
    }

    @Override
    @MainThread
    @Nullable
    public Boolean onMetronomeToggle(@NonNull final MainFragment fragment) {
        assertMainThread();
        mLifecycleLogger.log("onMetronomeToggle()");
        if (mMetronomeService == null) {
            return null;
        }

        final boolean isStarted;
        try {
            isStarted = mMetronomeService.isStarted();
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.isStarted() failed");
            return null;
        }

        if (isStarted) {
            return stopMetronome();
        } else {
            return startMetronome(fragment);
        }
    }

    @Nullable
    @MainThread
    private MetronomeConfig createConfigFromUi(@NonNull final MainFragment fragment) {
        final boolean audioEnabled = fragment.isAudioEnabled();
        final boolean vibrateEnabled = fragment.isVibrateEnabled();
        final Integer bpm = fragment.getBpm();
        final int minBpm = MetronomeService.getMinBpm();
        final int maxBpm = MetronomeService.getMaxBpm();
        if (bpm == null) {
            return null;
        } else if (bpm < minBpm || bpm > maxBpm) {
            mLogger.w("invalid BPM: %d (must be greater than or equal to %d and less than "
                    + "or equal to %d)", bpm, minBpm, maxBpm);
            return null;
        }

        return new MetronomeConfig(bpm, audioEnabled, vibrateEnabled);
    }

    @MainThread
    private Boolean startMetronome(@NonNull final MainFragment fragment) {
        final MetronomeConfig config = createConfigFromUi(fragment);
        if (config == null) {
            return null;
        }

        try {
            mMetronomeService.start(config);
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.start() failed");
            return null;
        }

        return true;
    }

    @MainThread
    private Boolean stopMetronome() {
        try {
            mMetronomeService.stop();
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.stop() failed");
            return null;
        }
        return false;
    }

    @Override
    @MainThread
    public void onMetronomeConfigChange(@NonNull final MainFragment fragment) {
        if (mMetronomeService == null) {
            return;
        }

        final MetronomeConfig config = createConfigFromUi(fragment);
        if (config == null) {
            return;
        }

        final boolean started;
        try {
            started = mMetronomeService.isStarted();
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.isStarted() failed");
            return;
        }

        final boolean startRequired;
        try {
            startRequired = mMetronomeService.setConfig(config);
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.setConfig() failed");
            return;
        }

        if (started && startRequired) {
            stopMetronome();
            startMetronome(fragment);
        }
    }

    @MainThread
    @Nullable
    private MetronomeConfig getMetronomeConfig() {
        assertMainThread();
        if (mMetronomeService == null) {
            return null;
        }

        try {
            return mMetronomeService.getConfig();
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.getConfig() failed");
            return null;
        }
    }

    @Override
    @MainThread
    public void updateUiFromMetronome() {
        notifyHostOfMetronomeState();
    }

    @Override
    @Nullable
    @MainThread
    public Boolean getCurrentAudioEnabled() {
        final MetronomeConfig config = getMetronomeConfig();
        return (config == null) ? null : config.audioEnabled;
    }

    @Override
    @Nullable
    @MainThread
    public Boolean getCurrentVibrateEnabled() {
        final MetronomeConfig config = getMetronomeConfig();
        return (config == null) ? null : config.vibrateEnabled;
    }

    @Override
    @MainThread
    public void onServiceConnected(final ComponentName name, final IBinder service) {
        mLifecycleLogger.onServiceConnected(name, service);
        mMetronomeService = IMetronomeService.Stub.asInterface(service);
        notifyHostOfMetronomeState();
    }

    @MainThread
    private void notifyHostOfMetronomeState() {
        if (mMetronomeService == null) {
            return;
        }

        final HostCallbacks hostCallbacks = (HostCallbacks) getContext();
        if (hostCallbacks == null) {
            return;
        }

        final MetronomeConfig config = getMetronomeConfig();

        final boolean running;
        try {
            running = mMetronomeService.isStarted();
        } catch (RemoteException e) {
            mLogger.e(e, "MetronomeService.isStarted() failed");
            return;
        }

        hostCallbacks.onMetronomeChanged(this, running, config);
    }

    @Override
    @MainThread
    public void onServiceDisconnected(final ComponentName name) {
        mLifecycleLogger.onServiceDisconnected(name);
        mMetronomeService = null;
    }

    public interface HostCallbacks {

        @MainThread
        void onMetronomeChanged(@NonNull WorkFragment fragment, boolean running,
                @Nullable MetronomeConfig config);

    }

}
