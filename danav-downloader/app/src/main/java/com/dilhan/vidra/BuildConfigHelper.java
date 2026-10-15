package com.dilhan.vidra;

import android.content.Context;

final class BuildConfigHelper {
    private BuildConfigHelper() {}
    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }
}
