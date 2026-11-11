package com.reed.fcmguard;

// The privileged process exposes only validated settings operations, never a shell.
interface ISettingsService {
    String read(String key, int userId) = 0;
    boolean write(String key, String value, int userId) = 1;
    void destroy() = 16777114;
}
