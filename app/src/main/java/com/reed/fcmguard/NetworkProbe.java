package com.reed.fcmguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.*;

/** Tests this app's network path, never the private GMS connection or message delivery. */
final class NetworkProbe {
    enum State { NOT_CHECKED, OFFLINE, REACHABLE, UNREACHABLE, UNAVAILABLE }
    private static final ExecutorService IO = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> { Thread t = new Thread(runnable, "push-network-probe"); t.setDaemon(true); return t; });
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) { return false; }
    }
    static State check(Context c) {
        if (!online(c)) return State.OFFLINE;
        Future<State> future;
        try {
            future = IO.submit(() -> {
                InetAddress[] addresses = InetAddress.getAllByName("mtalk.google.com");
                // Bound work and test the normal push port and documented 443 fallback.
                for (int port : new int[]{5228, 443}) {
                    for (int i = 0; i < Math.min(2, addresses.length); i++) {
                        if (Thread.currentThread().isInterrupted()) return State.UNAVAILABLE;
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(addresses[i], port), 1500);
                            return State.REACHABLE;
                        } catch (java.io.IOException ignored) { }
                    }
                }
                return State.UNREACHABLE;
            });
        } catch (RejectedExecutionException e) { return State.UNAVAILABLE; }
        try { return future.get(8, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return State.UNAVAILABLE; }
        catch (ExecutionException e) { return State.UNREACHABLE; }
        catch (TimeoutException e) { return State.UNAVAILABLE; }
        finally { future.cancel(true); }
    }
}
