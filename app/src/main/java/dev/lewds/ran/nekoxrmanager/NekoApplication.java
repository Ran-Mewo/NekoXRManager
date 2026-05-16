package dev.lewds.ran.nekoxrmanager;

import android.app.Application;

import dev.lewds.ran.nekoxrmanager.di.ServiceLocator;

public class NekoApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ServiceLocator.init(this);
    }
}
