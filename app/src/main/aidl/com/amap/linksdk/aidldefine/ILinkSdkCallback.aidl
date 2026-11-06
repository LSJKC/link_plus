// Adapted from the historical reference implementation's ILinkSdkCallback.java
// (AMap LinkSDK navigation callback).
//
// 包名/接口名决定 binder descriptor（com.amap.linksdk.aidldefine.ILinkSdkCallback），
// 高德侧是按这个 descriptor 回调的，不能改成我们自己的包名，否则就收不到回调。
package com.amap.linksdk.aidldefine;

import android.os.ParcelFileDescriptor;

interface ILinkSdkCallback {
    void onAmapCallback(String data);

    void onAmapCallbackWithFd(String data, in ParcelFileDescriptor fd);
}
