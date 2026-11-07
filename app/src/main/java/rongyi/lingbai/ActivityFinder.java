package rongyi.lingbai;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

final class ActivityFinder {
    private ActivityFinder() {}

    static Activity from(Context context) {
        Context current = context;
        int guard = 0;
        while (current instanceof ContextWrapper && guard++ < 10) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
