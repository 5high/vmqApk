package com.vone.vmq;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.vone.qrcode.R;
import com.vone.vmq.util.PreUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import okhttp3.Response;

public class NeNotificationService2 extends NotificationListenerService {
    private static String TAG = "NeNotificationService2";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread newThread = null;
    public static boolean isRunning;
    private final NetworkReceiver netWorkReceiver = new NetworkReceiver();

    //心跳进程
    public void initAppHeart() {
        if (PreUtils.get(App.getContext(), com.vone.vmq.util.Constant.CONFIG_V2, false)) {
            // 新版不再使用心跳达到省电效果
            if (newThread != null && !newThread.isInterrupted()) {
                newThread.interrupt();
                newThread = null;
            }
        } else if (newThread == null) {
            // 开启心跳线程, 判断版本，兼容旧版本的心跳线程
            Log.d(TAG, "开始启动心跳线程");
            newThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "心跳线程启动！"); // foregroundHeart(url);
                    while (isRunning && newThread == Thread.currentThread()
                            && !PreUtils.get(App.getContext(), com.vone.vmq.util.Constant.CONFIG_V2, false)) {
                        // 如果网络状态处于未连接，不心跳
                        if (SendRequestServer.getInstance().isNetConnection(App.getContext())) {
                            SendRequestServer.getInstance().sendHeart(App.getContext(), new SendRequestServer.HeartCallback() {
                                @Override
                                public void onFailure(boolean isInit) {
                                    if (isInit) {
                                        // 如果尚未初始化，不进行心跳重试
                                        foregroundHeart();
                                    }
                                }

                                @Override
                                public void onResponse(Response response) {
                                    if (!response.isSuccessful()) {
                                        foregroundHeart();
                                    }
                                }
                            });
                        }
                        try {
                            Thread.sleep(30 * 1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            newThread.start(); //启动线程
        }
    }


    //当收到一条消息的时候回调，sbn是收到的消息
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "接受到通知消息");
        writeNotifyToFile(sbn);
        // 微信支付部分通知，会调用两次，导致统计不准确
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d(TAG, "群组摘要通知，忽略");
            return;
        }

        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (notification != null) {
            Bundle extras = notification.extras;
            if (extras != null) {
                CharSequence _title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE, "");
                CharSequence _content = extras.getCharSequence(NotificationCompat.EXTRA_TEXT, "");
                Log.d(TAG, "**********************");
                Log.d(TAG, "包名:" + pkg);
                Log.d(TAG, "标题:" + _title);
                Log.d(TAG, "内容:" + _content);
                Log.d(TAG, "**********************");
                // to string (企业微信之类的 getString 会出错，换getCharSequence)
                String title = _title.toString();
                String content = _content.toString();
                if ("com.eg.android.AlipayGphone".equals(pkg)) {
                    if (!content.equals("")) {
                        if (content.contains("通过扫码向你付款") || content.contains("成功收款")
                                || title.contains("通过扫码向你付款") || title.contains("成功收款")
                                || content.contains("店员通") || title.contains("店员通")) {
                            String money;
                            // 新版支付宝，会显示积分情况下。先匹配标题上的金额
                            if (content.contains("商家积分")) {
                                money = getMoney(title);
                                if (money == null) {
                                    money = getMoney(content);
                                }
                            } else {
                                money = getMoney(content);
                                if (money == null) {
                                    money = getMoney(title);
                                }
                            }
                            if (money != null) {
                                Log.d(TAG, "onAccessibilityEvent: 匹配成功： 支付宝 到账 " + money);
                                appPush(2, Double.parseDouble(money));
                            } else {
                                handler.post(new Runnable() {
                                    public void run() {
                                        Toast.makeText(getApplicationContext(), "监听到支付宝消息但未匹配到金额！", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                    }
                } else if ("com.tencent.mm".equals(pkg)
                        || "com.tencent.wework".equals(pkg)) {
                    if (!content.equals("")) {
                        if (title.equals("微信支付") || title.equals("微信收款助手") || title.equals("微信收款商业版")
                                || (title.equals("对外收款") || title.equals("企业微信")) &&
                                (content.contains("成功收款") || content.contains("收款通知"))) {
                            String money = getMoney(content);
                            if (money != null) {
                                Log.d(TAG, "onAccessibilityEvent: 匹配成功： 微信到账 " + money);
                                try {
                                    appPush(1, Double.parseDouble(money));
                                } catch (Exception e) {
                                    Log.d(TAG, "app push 错误！！！");
                                }
                            } else {
                                handler.post(new Runnable() {
                                    public void run() {
                                        Toast.makeText(getApplicationContext(), "监听到微信消息但未匹配到金额！", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }

                        }
                    }
                } else if ("com.vone.qrcode".equals(pkg)) {
                    if (content.equals("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")) {
                        handler.post(new Runnable() {
                            public void run() {
                                Toast.makeText(getApplicationContext(), "监听正常，如无法正常回调请联系作者反馈！", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }
        }
    }

    //当移除一条消息的时候回调，sbn是被移除的消息
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {

    }

    //当连接成功时调用，一般在开启监听后会回调一次该方法
    @Override
    public void onListenerConnected() {
        isRunning = true;
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), "监听服务开启成功！", Toast.LENGTH_SHORT).show();
            }
        });

        //注册网络状态监听广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(netWorkReceiver, filter);
        // 监听此类信息进行心跳通知
        registerHomeBroad();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isRunning = false;
        if (newThread != null) {
            newThread.interrupt();
        }
        newThread = null;
        // 取消广播监听状态
        unregisterReceiver(netWorkReceiver);
        unregisterReceiver(sysBoardReceiver);
    }

    private void registerHomeBroad() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(sysBoardReceiver, filter);
    }

    private void writeNotifyToFile(StatusBarNotification sbn) {
        if (!sbn.isClearable()) {
            return;
        }
        Log.i(TAG, "write notify message to file");
        //            具有写入权限，否则不写入
        CharSequence notificationTitle = null;
        CharSequence notificationText = null;
        CharSequence subText = null;

        Bundle extras = sbn.getNotification().extras;
        if (extras != null) {
            notificationTitle = extras.getCharSequence(Notification.EXTRA_TITLE);
            notificationText = extras.getCharSequence(Notification.EXTRA_TEXT);
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        }
        String packageName = sbn.getPackageName();
        String time = Utils.formatTime(Calendar.getInstance().getTime());

        String writText = "\n" + "[" + time + "]" + "[" + packageName + "]" + "\n" +
                "[" + notificationTitle + "]" + "\n" + "[" + notificationText + "]" + "\n" +
                "[" + subText + "]" + "\n";

        // 使用 post 异步的写入
        Utils.putStr(this, writText);
    }

    /**
     * 通知服务器收款到账
     */
    public void appPush(int type, double price) {
        initAppHeart();
        PushBean pushBean = new PushBean(
                System.currentTimeMillis(),
                price,
                type
        );
        SendRequestServer.getInstance().addPushBean(pushBean);
    }

    private void foregroundHeart() {
        final Context context = NeNotificationService2.this;
        if (isRunning) {
            final JSONObject extraJson = new JSONObject();
            try {
                extraJson.put("heart", true);
                extraJson.put("show", false);
            } catch (JSONException jsonException) {
                jsonException.printStackTrace();
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    enterForeground(context,
                            context.getString(R.string.app_name),
                            context.getString(R.string.app_is_heart), extraJson.toString());
                }
            });
        }
    }

    /**
     * 如果出现无法通知的情况，进入前台，然后主动打开通知
     */
    public static void enterForeground(Context context, String title, String text, String extra) {
        if (context == null) return;
        Log.i(TAG, "enter fore ground");
        Intent intent = new Intent(context, ForegroundServer.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ForegroundServer.GET_NOTIFY_TITLE, title == null ? "" : title);
        intent.putExtra(ForegroundServer.GET_NOTIFY_TEXT, text == null ? "" : text);
        intent.putExtra(ForegroundServer.GET_NOTIFY_EXTRA, extra == null ? "" : extra);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void exitForeground(Context context) {
        if (context == null) return;
        Log.i(TAG, "exitForeground");

        Intent intent1 = new Intent();
        intent1.setAction(Constant.FINISH_FOREGROUND_SERVICE);
        context.sendBroadcast(intent1);
    }

    public static String getMoney(String content) {
        List<String> ss = new ArrayList<>();
        for (String sss : content.replaceAll(",", "")
                .replaceAll("[^0-9.]", ",").split(",")) {
            if (sss.length() > 0)
                ss.add(sss);
        }
        if (ss.size() < 1) {
            return null;
        } else {
            return ss.get(ss.size() - 1);
        }
    }


    private final BroadcastReceiver sysBoardReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                Log.i(TAG, "receive bettery change action");
                SendRequestServer.getInstance().sendHeart(context);
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Log.i(TAG, "receive screen off action");
                SendRequestServer.getInstance().sendHeart(context);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Log.i(TAG, "receive screen on action");
                SendRequestServer.getInstance().sendHeart(context);
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                Log.i(TAG, "receive user presend action");
                SendRequestServer.getInstance().sendHeart(context);
            }
        }
    };

}
