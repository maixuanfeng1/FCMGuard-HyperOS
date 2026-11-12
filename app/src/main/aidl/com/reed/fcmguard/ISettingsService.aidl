package com.reed.fcmguard;
// Fixed, read-only queries. No caller-provided commands or settings writes.
interface ISettingsService {
    String readWhitelist(int userId) = 0;
    // Transaction 1 retired with the obsolete socket query; keep remaining IDs stable.
    int runtimeBitness() = 2;
    String gcmConnectionState(int userId) = 3;
    void destroy() = 16777114;
}
