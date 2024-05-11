package com.vone.vmq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/**
 * 网络广播接收器，用于判断当前是否连接网络，避免反复推送
 * 在断网情况自动暂停推送，达到省电效果
 */
public class NetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // 根据网络状况，判断是否需要进行推送
        // 该广播会多次重复发送，所以需要判断去重
        SendRequestServer.getInstance().setStartPush(SendRequestServer.getInstance()
                .isNetConnection(context));
    }
}
