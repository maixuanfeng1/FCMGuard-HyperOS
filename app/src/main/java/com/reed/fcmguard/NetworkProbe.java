package com.reed.fcmguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
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
                List<InetAddress> addresses = candidates(InetAddress.getAllByName("mtalk.google.com"));
                // Bound work and test the normal push port and documented 443 fallback.
                for (int port : new int[]{5228, 443}) {
                    for (InetAddress address : addresses) {
                        if (Thread.currentThread().isInterrupted()) return State.UNAVAILABLE;
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(address, port), 1500);
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
    static List<InetAddress> candidates(InetAddress[] resolved) {
        List<InetAddress> result = new ArrayList<>(2);
        boolean ipv4 = false, ipv6 = false;
        for (InetAddress address : resolved) {
            boolean v4 = address instanceof Inet4Address;
            if (v4 ? ipv4 : ipv6) continue;
            result.add(address);
            if (v4) ipv4 = true; else ipv6 = true;
            if (ipv4 && ipv6) break;
        }
        return result;
    }
}
