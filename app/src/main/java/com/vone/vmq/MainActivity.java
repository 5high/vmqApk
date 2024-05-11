package com.vone.vmq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.BuildConfig;
import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;
import com.vone.vmq.util.PreUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private TextView txthost;
    private TextView txt_id;
    private TextView txtkey;


    int id = 0;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txthost = (TextView) findViewById(R.id.txt_host);
        txt_id = (TextView) findViewById(R.id.txt_id);
        txtkey = (TextView) findViewById(R.id.txt_key);

        //检测通知使用权是否启用
        if (!isNotificationListenersEnabled()) {
            //跳转到通知使用权页面
            gotoNotificationAccessSetting();
        } else if (!Utils.checkBatteryWhiteList(this)) {
            Utils.gotoBatterySetting(this);
        }
        //重启监听服务
        if (!NeNotificationService2.isRunning) {
            toggleNotificationListenerService(this);
        }
        //读入保存的配置数据并显示
        String configStr = PreUtils.get(this, Constant.SERVER_CONFIG, "");
        if (!TextUtils.isEmpty(configStr)) {
            try {
                JSONObject configJson = new JSONObject(configStr);
                txthost.setText(" 通知地址：" + configJson.optString("url"));
                txt_id.setText(" 账户ID号：" + configJson.optString("appId", "兼容旧版(无账户ID)"));
                txtkey.setText(" 通讯密钥：" + configJson.optString("key"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        Toast.makeText(MainActivity.this, "v免签开源免费免签系统 " + BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show();
    }

    //扫码配置
    public void startQrCode(View v) {
        // 申请相机权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        // 申请文件读写权限（部分朋友遇到相册选图需要读写权限的情况，这里一并写一下）
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, Constant.REQ_PERM_EXTERNAL_STORAGE);
            return;
        }
        // 二维码扫码
        Intent intent = new Intent(MainActivity.this, CaptureActivity.class);
        startActivityForResult(intent, Constant.REQ_QR_CODE);
    }

    //手动配置
    public void doInput(View v) {
        final EditText inputServer = new EditText(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请输入配置数据").setView(inputServer)
                .setNegativeButton("取消", null);
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                String scanResult = inputServer.getText().toString();
                saveConfig(scanResult);
            }
        });
        builder.show();
    }

    //检测心跳
    public void doStart(View view) {
        // 发送一次心跳
        SendRequestServer.getInstance().sendHeart(this, null);
    }

    //检测监听
    public void checkPush(View v) {
        Notification mNotification;
        NotificationManager mNotificationManager;
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("1",
                    "Channel1", NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(channel);

            Notification.Builder builder = new Notification.Builder(this, "1");

            mNotification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        } else {
            mNotification = new Notification.Builder(MainActivity.this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        }
        //Toast.makeText(MainActivity.this, "已推送信息，如果权限，那么将会有下一条提示！", Toast.LENGTH_SHORT).show();

        mNotificationManager.notify(id++, mNotification);
    }

    //各种权限的判断
    private void toggleNotificationListenerService(Context context) {
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        // 不要每次打开都显示
        // Toast.makeText(MainActivity.this, "监听服务启动中...", Toast.LENGTH_SHORT).show();
    }

    public boolean isNotificationListenersEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (int i = 0; i < names.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(names[i]);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected boolean gotoNotificationAccessSetting() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {//普通情况下找不到的时候需要再特殊处理找一次
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ComponentName cn = new ComponentName("com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity");
                intent.setComponent(cn);
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings");
                startActivity(intent);
                return true;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            Toast.makeText(this, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //扫描结果回调
        if (requestCode == Constant.REQ_QR_CODE && resultCode == RESULT_OK) {
            Bundle bundle = data.getExtras();
            String scanResult = bundle.getString(Constant.INTENT_EXTRA_KEY_QR_SCAN);

            if (TextUtils.isEmpty(scanResult)) {
                Toast.makeText(MainActivity.this, "扫码错误，请您扫描网站上显示的二维码!", Toast.LENGTH_SHORT).show();
                return;
            }
            saveConfig(scanResult);
        }
    }

    /**
     * 统一的保存配置信息的位置
     *
     * @param input
     */
    @SuppressLint("SetTextI18n")
    private void saveConfig(String input) {
        Log.d("MainActivity", input);
        JSONObject configJson = new JSONObject();
        if (input.startsWith("http")) {
            try {
                URL testUrl = new URL(input);

                configJson.put("url", testUrl.getProtocol() + "://" + testUrl.getHost());

                String[] tmp = testUrl.getPath().substring("/".length()).split("/");
                if (tmp.length != 2) {
                    Toast.makeText(MainActivity.this, "二维码错误，请您扫描网站上显示的二维码!", Toast.LENGTH_SHORT).show();
                    return;
                }
                configJson.put("key", tmp[0]);
                configJson.put("appId", tmp[1]);
            } catch (MalformedURLException | JSONException e) {
                e.printStackTrace();
            }
            PreUtils.put(this, Constant.CONFIG_V2, true);
        } else {  // 兼容旧版 vmqphp
            String[] tmp = input.split("/");
            if (tmp.length != 2) {
                Toast.makeText(MainActivity.this, "二维码错误，请您扫描网站上显示的二维码!", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                configJson.put("url", tmp[0]);
                configJson.put("key", tmp[1]);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            PreUtils.put(this, Constant.CONFIG_V2, false);
        }
        txthost.setText(" 通知地址：" + configJson.optString("url"));
        txt_id.setText(" 账户ID号：" + configJson.optString("appId", "兼容旧版(无账户ID)"));
        txtkey.setText(" 通讯密钥：" + configJson.optString("key"));

        PreUtils.put(this, Constant.SERVER_CONFIG, configJson.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case Constant.REQ_PERM_CAMERA:
                // 摄像头权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的相机访问权限", Toast.LENGTH_LONG).show();
                }
                break;
            case Constant.REQ_PERM_EXTERNAL_STORAGE:
                // 文件读写权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的文件读写权限", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }
}
