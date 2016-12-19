package ru.yourok.loader;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import go.m3u8.M3u8;
import ru.yourok.m3u8loader.utils.Notifications;

import static ru.yourok.loader.LoaderServiceHandler.loadersQueue;

/**
 * Created by yourok on 15.12.16.
 */

public class LoaderService extends Service {
    private Notifications notifications;
    private static LoaderService instance;
    private static LoaderServiceCallbackUpdate loaderServiceCallback;

    public static final int CMD_NONE = 0;
    public static final int CMD_START = 1;
    public static final int CMD_STOP = 2;

    public interface LoaderServiceCallbackUpdate {
        void onUpdateLoader(int id);
    }

    public LoaderService() {
        super();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Options.getInstance(this).LoadList();
        notifications = new Notifications(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int cmd = 0;
        if (intent != null && intent.hasExtra("command"))
            cmd = intent.getIntExtra("command", 0);
        switch (cmd) {
            case CMD_NONE:
                break;
            case CMD_START: {
                Start();
                break;
            }
            case CMD_STOP: {
                Stop();
                break;
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (instance != null)
            synchronized (instance) {
                instance = null;
            }
        Options.getInstance(this).SaveList();
        notifications.removeNotification();
        super.onDestroy();
    }

    public static void registerOnUpdateLoader(LoaderServiceCallbackUpdate onUpdate) {
        loaderServiceCallback = onUpdate;
        if (instance != null && instance.id != -1)
            instance.checkState(instance.id);
    }

    public static void startService(Context context) {
        Intent intent = new Intent(context, LoaderService.class);
        context.startService(intent);
    }

    public static void load(Context context) {
        Intent intent = new Intent(context, LoaderService.class);
        intent.putExtra("command", CMD_START);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, LoaderService.class);
        intent.putExtra("command", CMD_STOP);
        context.startService(intent);
    }

////////////////
/// Wroking funcs

    private Boolean isLoading = false;
    private Boolean isChecked = false;
    private int id = -1;

    public void Start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (isLoading) {
                    if (isLoading) return;
                    isLoading = true;
                }
                for (int id : loadersQueue) {
                    if (!isLoading) break;
                    Loader loader = LoaderServiceHandler.GetLoader(id);
                    if (loader == null)
                        continue;
                    if (loader.GetState() != null && loader.GetState().getStage() == M3u8.Stage_Finished)
                        continue;
                    sendNotif(id);
                    checkState(id);
                    load(loader);
                }
                synchronized (isLoading) {
                    isLoading = false;
                }
            }
        }).start();
    }

    public void Stop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                loadersQueue.clear();
                synchronized (isLoading) {
                    if (!isLoading) return;
                    isLoading = false;
                }
                for (int i = 0; i < LoaderServiceHandler.SizeLoaders(); i++)
                    if (LoaderServiceHandler.GetLoader(i).IsWorking())
                        LoaderServiceHandler.GetLoader(i).Stop();

                for (int i = 0; i < LoaderServiceHandler.SizeLoaders(); i++)
                    LoaderServiceHandler.GetLoader(i).PollState();
                updateNotif();
            }
        }).start();
    }

    private void load(Loader loader) {
        loader.SetThreads(Options.getInstance(this).GetThreads());
        loader.SetTimeout(Options.getInstance(this).GetTimeout());
        loader.SetTempDir(Options.getInstance(this).GetTempDir());
        loader.SetOutDir(Options.getInstance(this).GetOutDir());
        String ret = "";
        if (loader.GetList() == null)
            ret = loader.LoadList();
        if (ret.isEmpty())
            loader.Load();
        updateNotif();
    }

    void updateNotif() {
        if (id != -1)
            sendNotif(id);
    }

    void sendNotif(final int id) {
        this.id = id;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (loaderServiceCallback != null)
                    loaderServiceCallback.onUpdateLoader(id);
                notifications.createNotification(id);
            }
        }).start();
    }

    private void checkState(final int id) {

        synchronized (isChecked) {
            if (isChecked) return;
            isChecked = true;
        }

        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                int countNil = 0;
                while (true) {
                    Loader loader = LoaderServiceHandler.GetLoader(id);
                    if (loader == null)
                        break;
                    if (loader.PollState() == null)
                        countNil++;
                    else
                        countNil = 0;
                    updateNotif();

                    if (!isLoading || countNil > 30)
                        break;
                }
                synchronized (isChecked) {
                    isChecked = false;
                }
            }
        });
        th.start();
    }
}