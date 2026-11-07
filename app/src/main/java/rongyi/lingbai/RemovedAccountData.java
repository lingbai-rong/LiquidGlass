package rongyi.lingbai;

import android.app.job.JobScheduler;
import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.security.KeyStore;

/** Local upgrade cleanup only; does not contact the former account server. */
final class RemovedAccountData {
    private RemovedAccountData() {}

    static void clear(Context context) {
        if (!"rongyi.lingbai".equals(context.getPackageName())) return;
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(0x4C470262);
        } catch (RuntimeException ignored) {}

        clearPreferences(context);
        try {
            clearPreferences(context.createDeviceProtectedStorageContext());
        } catch (RuntimeException ignored) {}
        try {
            new AtomicFile(new File(context.getNoBackupFilesDir(),
                    "auth/remembered_credentials.bin")).delete();
        } catch (RuntimeException ignored) {}
        try {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            for (String alias : new String[]{
                    "liquidglass_device_proof_p256_v1",
                    "liquidglass_license_session_aes_v1",
                    "liquidglass_remembered_credentials_aes_v1"}) {
                keys.deleteEntry(alias);
            }
        } catch (Exception ignored) {}
    }

    private static void clearPreferences(Context context) {
        for (String group : new String[]{"license_private_session", "license_device_identity",
                "license_entitlement"}) {
            try {
                context.deleteSharedPreferences(group);
            } catch (RuntimeException ignored) {}
        }
    }
}
