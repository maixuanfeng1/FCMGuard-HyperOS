package com.reed.fcmguard;

import com.reed.fcmguard.ISettingsObserver;

// The privileged process exposes only validated settings operations, never a shell.
interface ISettingsService {
    String read(String key, int userId) = 0;
    boolean write(String key, String value, int userId) = 1;
    boolean observe(String key, int userId, ISettingsObserver observer) = 2;
    void stopObserving(ISettingsObserver observer) = 3;
    void destroy() = 16777114;
}
