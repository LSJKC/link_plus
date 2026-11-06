// Adapted from the historical reference implementation's ILinkSdkControl.java
// (AMap LinkSDK wearable/glass service interface).
//
// 包名必须保持 com.amap.linksdk.aidldefine：AIDL 生成的 binder descriptor 由「包名+接口名」
// 决定，高德 App 侧的 Stub.onTransact 会 enforceInterface("com.amap.linksdk.aidldefine.ILinkSdkControl")。
// 改成 io.github.shaonialife.* 会让跨进程调用直接被 SecurityException 拒绝（AMap 不会认识新名字）。
package com.amap.linksdk.aidldefine;

import com.amap.linksdk.aidldefine.ILinkSdkCallback;

interface ILinkSdkControl {
    String authenticate();

    String execute(String data);

    boolean registerListener(ILinkSdkCallback callback);

    boolean unregisterListener(ILinkSdkCallback callback);

    boolean ping();
}
