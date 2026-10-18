package com.gruning.mimir.tweaker;

import android.os.Bundle;

interface IRestoreGuardianProcess {
    Bundle arm(IBinder ownerToken);
    boolean confirmReady();
    Bundle heartbeat(in Bundle signedHeartbeat);
    Bundle requestRestore();
    Bundle terminal();
    boolean cleanup();
}
