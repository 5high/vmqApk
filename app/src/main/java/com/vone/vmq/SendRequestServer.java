package com.vone.vmq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;
import com.vone.vmq.util.PreUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 单独创建一个类进行心跳、以及订单通知
 */
public class SendRequestServer {
    private static SendRequestServer sendRequestServer;

    private final Object lock = new Object();
    private final LinkedList<PushBean> queue = new LinkedList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean startPush = false;
    private PowerManager.WakeLock mWakeLock = null;
    private final String TAG = "SendRequestServer";

    private SendRequestServer() {
        // 开启推送线程
        Thread pushThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (!startPush) {  // 当前未开启推送
                        synchronized (lock) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }
                    // 网络断开状态省电不尝试任何访问
                    if (!isNetConnection(App.getContext())) {
                        synchronized (lock) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }
                    // poll() 删除第一个元素并返回
                    // peek() 返回第一个元素但不删除
                    PushBean pushBean = queue.peek();
                    if (pushBean == null) {
                        synchronized (lock) {
                            pushBean = queue.peek();
                            if (pushBean == null) {
                                // 如果队列空，则休眠此线程
                                try {
                                    lock.wait();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                continue;
                            }
                        }
                    }
                    /*
                     * 超过 5分钟 未推送成功，此项不再推送，需要手动补单
                     */
                    if (pushBean.pushTime < System.currentTimeMillis() - (300 * 1000)) {
                        sendFaildNotify(queue.poll());  // 删除第一个元素
                        continue;
                    }
                    // 从队列获取一个数据，开始推送方法
                    if (push(pushBean)) {
                        queue.poll();  // 删除第一个元素
                    } else {
                        try {    // 推送失败，暂停 5 秒中再尝试，避免反复尝试导致问题
                            //noinspection BusyWait
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        });
        pushThread.start();
    }

    public static SendRequestServer getInstance() {
        if (sendRequestServer == null) {
            synchronized (SendRequestServer.class) {
                if (sendRequestServer == null) {
                    sendRequestServer = new SendRequestServer();
                }
            }
        }
        return sendRequestServer;
    }

    /**
     * 当本地推送失败时，弹提示手动补单
     *
     * @param pushBean
     */
    private void sendFaildNotify(PushBean pushBean) {
        if (pushBean == null) {
            return;
        }
        String type = "未知";
        switch (pushBean.type) {
            case 1:
                type = "微信";
                break;
            case 2:
                type = "支付宝";
                break;
        }
        Utils.sendNotifyMessage(App.getContext(), "推送失败",
                "本次" + type + "支付信息推送至服务器失败，金额: " + pushBean.price);
    }

    /**
     * @param pushBean
     * @return
     */
    private boolean push(PushBean pushBean) {
        final Context context = App.getContext();
        String configStr = PreUtils.get(context, Constant.SERVER_CONFIG, "");
        if (TextUtils.isEmpty(configStr)) {
            return false;
        }
        try {
            JSONObject configJson = new JSONObject(configStr);
            // 读取配置
            String serverUrl = configJson.optString("url", "");
            String key = configJson.optString("key");
            String appId = configJson.optString("appId", "");


            String sign = md5(pushBean.type + "" + pushBean.price + pushBean.pushTime + key);
            String reqUrl;
            // 旧版的 vmqPhp 服务，不包含 http 开头，以及 appId，兼容
            if (!serverUrl.startsWith("http")) {
                // 旧版的需要自行拼接 http 到前方
                reqUrl = "http://" + serverUrl + "/appPush?t=" + pushBean.pushTime +
                        "&type=" + pushBean.type + "&price=" + pushBean.price + "&sign=" + sign;
            } else {
                HashMap<String, String> reqParams = new HashMap<>();
                reqParams.put("appId", appId);
                reqParams.put("t", String.valueOf(pushBean.pushTime));
                reqParams.put("type", String.valueOf(pushBean.type));
                reqParams.put("price", String.valueOf(pushBean.price));
                reqParams.put("sign", sign);
                reqUrl = serverUrl + "/appPush?" + getParamUrl(reqParams);
            }
            Log.d(TAG, "onResponse  push: 开始:" + reqUrl);
            acquireWakeLock(context);
            Request request = new Request.Builder().url(reqUrl).method("GET", null).build();
            Call call = Utils.getOkHttpClient().newCall(request);
            try {
                Response response = call.execute();
                Log.d(TAG, "onResponse  push: " + response.body().string());
                // 返回 200 状态码即可视为成功
                if (!response.isSuccessful()) {
                    foregroundPost(context);
                } else {
                    NeNotificationService2.exitForeground(context);
                }
                return response.isSuccessful();
            } catch (IOException e) {
                e.printStackTrace();
                foregroundPost(context);
                return false;
            } finally {
                releaseWakeLock();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setStartPush(boolean startPush) {
        boolean needNotify = startPush != this.startPush;
        this.startPush = startPush;
        if (needNotify) {
            synchronized (lock) {
                lock.notify();
            }
        }
    }

    /**
     * 判断当前是否有网络连接,但是如果该连接的网络无法上网，也会返回true
     */
    public boolean isNetConnection(Context mContext) {
        if (mContext != null) {
            try {
                ConnectivityManager connectivityManager =
                        (ConnectivityManager) mContext.
                                getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                return networkInfo.isConnected();
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    public void addPushBean(PushBean pushBean) {
        // 推送到队列中
        queue.offer(pushBean);
        // 唤醒锁
        synchronized (lock) {
            lock.notify();
        }
    }

    public void sendHeart(final Context context) {
        sendHeart(context, new HeartCallback() {
            @Override
            public void onFailure(boolean isInit) {

            }

            @Override
            public void onResponse(Response response) {

            }
        });
    }

    /**
     * 发送一次心跳
     *
     * @param context 发送心跳需要读取配置
     */
    public void sendHeart(final Context context, final HeartCallback callback) {
        String configStr = PreUtils.get(context, Constant.SERVER_CONFIG, "");
        if (TextUtils.isEmpty(configStr)) {
            if (callback != null) {
                callback.onFailure(false);
            } else {
                showTip(context, "当前尚未配置服务器");
            }
            return;
        }
        try {
            JSONObject configJson = new JSONObject(configStr);
            sendHeart(context, configJson, callback);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendHeart(final Context context, JSONObject configJson, final HeartCallback callback) {
        String serverUrl = configJson.optString("url", "");
        String key = configJson.optString("key");
        String appId = configJson.optString("appId", "");

        // 状态检测
        if (TextUtils.isEmpty(serverUrl) || TextUtils.isEmpty(key)) {
            if (callback != null) {
                callback.onFailure(false);
            } else {
                showTip(context, "当前尚未配置服务器");
            }
            return;
        }

        if (!isNetConnection(context)) {
            if (callback != null) {
                callback.onFailure(true);
            } else {
                showTip(context, "当前未连接互联网");
            }
            return;
        }

        String t = String.valueOf(new Date().getTime());
        String sign = md5(t + key);

        String reqUrl;
        // 旧版的 vmqPhp 服务，不包含 http 开头，以及 appId，兼容
        if (!serverUrl.startsWith("http")) {
            // 旧版的需要自行拼接 http 到前方
            reqUrl = "http://" + serverUrl + "/appHeart?t=" + t + "&sign=" + sign;
        } else {
            HashMap<String, String> reqParams = new HashMap<>();
            reqParams.put("appId", appId);
            reqParams.put("t", t);
            reqParams.put("sign", sign);
            reqUrl = serverUrl + "/appHeart?" + getParamUrl(reqParams);
        }

        Request request = new Request.Builder().url(reqUrl).method("GET", null).build();
        Call call = Utils.getOkHttpClient().newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d("Request", "onFailure: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure(true);
                } else {
                    showTip(context, "心跳状态错误，请检查配置是否正确!");
                }
            }

            @Override
            public void onResponse(Call call, final Response response) throws IOException {
                if (callback != null) {
                    callback.onResponse(response);
                } else {
                    final String resbody = response.body().string();
                    Log.d("Request", "onResponse: " + resbody);
                    showTip(context, "心跳返回：" + resbody);
                }
            }
        });
    }

    private void showTip(final Context context, final String tip) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, tip, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static String md5(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result.append(temp);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    private long enterForegroundTime = 0;

    /**
     * 进入前台
     */
    private void foregroundPost(final Context context) {
        // 600秒 十分钟内只允许进入一次，避免反复弹窗打扰
        if (enterForegroundTime < System.currentTimeMillis() - 600000) {
            enterForegroundTime = System.currentTimeMillis();
            final JSONObject extraJson = new JSONObject();
            try {
                extraJson.put("show", true);
            } catch (JSONException jsonException) {
                jsonException.printStackTrace();
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    NeNotificationService2.enterForeground(context,
                            context.getString(R.string.app_name),
                            context.getString(R.string.app_is_post), extraJson.toString());
                }
            });
        }
    }

    /**
     * 拼接 url 请求参数
     *
     * @param params 参数列表
     * @return 拼接后的url
     */
    public String getParamUrl(HashMap<String, String> params) {
        if (params == null || params.size() == 0) return "";
        boolean start = true;
        StringBuilder urlBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            try {
                urlBuilder.append(start ? "" : "&")
                        .append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                if (start) {
                    start = false;
                }
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

        }
        return urlBuilder.toString();
    }

    @SuppressLint("InvalidWakeLockTag")
    public void acquireWakeLock(final Context context) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (null == mWakeLock) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "WakeLock");
                    }
                }
                if (null != mWakeLock) {
                    mWakeLock.acquire(5000);
                }
            }
        });

    }

    //释放设备电源锁
    public void releaseWakeLock() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (null != mWakeLock) {
                    mWakeLock.release();
                    mWakeLock = null;
                }
            }
        });
    }

    public interface HeartCallback {
        void onFailure(boolean isInit);

        void onResponse(Response response);
    }

}
