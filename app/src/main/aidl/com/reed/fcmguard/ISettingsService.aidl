package com.reed.fcmguard;
// Fixed queries and an explicit grant to this application only. No arbitrary commands/targets.
interface ISettingsService {
    String readWhitelist(int userId) = 0;
    // Transaction 1 retired with the obsolete socket query; keep remaining IDs stable.
    int runtimeBitness() = 2;
    String gcmConnectionState(int userId) = 3;
    void grantDiagnosticAccess() = 4;
    void destroy() = 16777114;
}
