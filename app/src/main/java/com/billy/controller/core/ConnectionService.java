package com.billy.controller.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.billy.controller.R;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.billy.controller.core.Status.RUNNING;
import static com.billy.controller.core.Status.STARTING;
import static com.billy.controller.core.Status.STOPPED;
import static com.billy.controller.core.Status.STOPPING;
import static com.billy.controller.core.Status.WAITING_CLIENT;

/**
 * @author billy.qi
 * @since 17/5/26 13:02
 */
public class ConnectionService extends Service {

    private static AtomicBoolean running = new AtomicBoolean();
    ExecutorService mExecutorService;  //create a thread pool
    public static final String IP = "127.0.0.1";
    public static final int PORT = 9099;
    public static final int CLIENT_COUNT = 1;//只接受指定数量的客户端
    private static final String TAG = "ConnectionService";
    long startTime;
    ServerSocket ss = null;
    Socket client = null;
    BufferedReader in = null;
    private Status status;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutorService = Executors.newSingleThreadExecutor();
        setRunning(false);
    }

    @Override
    public void onDestroy() {
        DebugListenerManager.clear();
        if (mExecutorService != null && !mExecutorService.isShutdown()) {
            mExecutorService.shutdownNow();
        }
        closeSocket();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            boolean stop = intent.getBooleanExtra("stop", false);
            if (stop) {
                stopConnection();
            } else {
                if (running.compareAndSet(false, true)) {
                    logcat("set running = true");
                    setStatus(STOPPED);
                    new StartConnectionThread(startId).start();
                } else {
                    setStatus(status);
                    stopSelf(startId);
                }
                sendConnectBroadcast();
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void sendConnectBroadcast() {
        try {
            Intent intent = new Intent(getString(R.string.log_action));
            intent.putExtra("ip", IP);
            intent.putExtra("port", PORT);
            sendBroadcast(intent);
        } catch(Exception ignored) {
        }
    }

    public static void addListener(IDebugListener listener) {
        DebugListenerManager.addListener(listener);
    }

    public static void removeListener(IDebugListener listener) {
        DebugListenerManager.removeListener(listener);
    }

    private void setRunning(boolean value) {
        running.set(value);
        logcat("set running = " + value);
    }

    private void logcat(String message) {
        Log.i(TAG, message);
    }

    private void setStatus(Status st) {
        status = st;
        logcat("status:" + st.toString());
        DebugListenerManager.onStatus(st);
    }

    private void processMessage(String msg) {
        //single的异步线程执行保证msg的处理顺序
        mExecutorService.execute(new ProcessMessageTask(msg));
    }

    private class ProcessMessageTask implements Runnable {
        String message;

        ProcessMessageTask(String message) {
            this.message = message;
        }

        @Override
        public void run() {
            DebugListenerManager.onMessage(message);
        }
    }


    private void closeSocket() {
        if (client != null) {
            try{
                client.shutdownInput();//关闭输入流，让in.readLine()阻塞中止
                client.shutdownOutput();//关闭输出流，让客户端的in.readLine()阻塞中止
            } catch(Exception ignored) {
            }
        }
        close(in);
        close(client);
        close(ss);
        in = null;
        client = null;
        ss = null;
    }

    private void close(Closeable closeable) {
        if (closeable != null) {
            try{
                closeable.close();
            } catch(Exception ignored) {
            }
        }
    }

    private void stopConnection() {
        setStatus(STOPPING);
        if (status == WAITING_CLIENT) {
            //通过创建一个无用的client来让ServerSocket跳过accept阻塞，从而中止
            Socket socket = null;
            try {
                socket = new Socket(IP, PORT);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                close(socket);
            }
        } else {
            closeSocket();
        }
    }

    private class StartConnectionThread extends Thread {
        private int startId;

        StartConnectionThread(int startId) {
            this.startId = startId;
        }

        @Override
        public void run() {
            if (status == STOPPED) {
                try {
                    setStatus(STARTING);
                    startConnection();
                } catch(Exception ignored) {
                } finally {
                    closeSocket();
                    setStatus(STOPPED);
                    setRunning(false);
                }
            }
            stopSelf(startId);
        }

        private void startConnection() throws IOException {
            //创建一个ServerSocket ，监听客户端socket的连接请求
            ss = new ServerSocket(PORT, CLIENT_COUNT);
            setStatus(WAITING_CLIENT);
            client = ss.accept();
            in = null;
            startTime = 0;
            String msg;
            if (status == WAITING_CLIENT) {
                setStatus(RUNNING);
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                while(status == RUNNING && (msg = in.readLine()) != null) {
                    processMessage(msg);
                }
            }
        }
    }
}
