package dev.muv.patcher;

import android.os.ParcelFileDescriptor;

interface IRemoteFs {
    ParcelFileDescriptor openWrite(String path) = 1;
    String readText(String path) = 2;
    boolean exists(String path) = 3;
    void destroy() = 16777114;
}
