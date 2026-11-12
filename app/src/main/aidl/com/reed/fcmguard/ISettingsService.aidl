package com.reed.fcmguard;
// Fixed, read-only queries. No caller-provided commands or settings writes.
interface ISettingsService {
    String readWhitelist(int userId) = 0;
    String fcmSocketEvidence(int userId) = 1;
    int runtimeBitness() = 2;
    void destroy() = 16777114;
}
