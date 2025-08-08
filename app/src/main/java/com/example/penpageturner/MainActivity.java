package com.example.penpageturner;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    // 定义蓝牙UUID
    private static final UUID PEN_SERVICE_UUID = UUID.fromString("165b0001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID PEN_STATUS_UUID = UUID.fromString("165b0004-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID PEN_COMMAND_UUID = UUID.fromString("165b0002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // 请求码
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;

    private TextView statusText;
    private Button btnControlService;
    private BluetoothGatt bluetoothGatt;
    private boolean isServiceRunning = false;
    private BluetoothAdapter bluetoothAdapter;

    /////每隔1分钟发送一次0x55160401,阻止休眠
    private Handler mCommandHandler = new Handler(); // 用于定时发送指令
    private Runnable mSendCommandTask = new Runnable() {
        @Override
        public void run() {
            if (bluetoothGatt != null) {
                sendCommand(bluetoothGatt, new byte[]{0x55, 0x04,  0x16, 0x01});
            }
            // 1分钟后再次执行
            mCommandHandler.postDelayed(this, 10000);
        }
    };
    /////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkOverlayPermission() ;
        statusText = findViewById(R.id.statusText);
        btnControlService = findViewById(R.id.btnControlService);


        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        btnControlService.setOnClickListener(v -> {
            if (!isServiceRunning) {
                checkAndStartService();
            } else {
                stopService();
            }
        });
    }

    private void checkAndStartService() {
        if (bluetoothAdapter == null) {
            showToast("设备不支持蓝牙");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {

            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }


        if (checkPermissions()) {
            startBluetoothService();
        } else {
            requestPermissions();
        }
    }

    private boolean checkPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {

            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startBluetoothService();
            } else {
                showToast("需要所有权限才能使用该功能");
                showPermissionExplanationDialog();
            }
        }
    }

    private void showPermissionExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限说明")
                .setMessage("本应用需要以下权限才能正常工作:\n\n" +
                        "- 蓝牙权限(扫描和连接设备)\n" +
                        "- 位置权限(发现附近设备)")
                .setPositiveButton("去设置", (dialog, which) -> openAppSettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {

                checkAndStartService();
            } else {
                showToast("需要启用蓝牙才能使用该功能");
            }
        }
    }

    private void startBluetoothService() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }


        if (!checkPermissions()) {
            requestPermissions();
            return;
        }


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (device.getName() != null && device.getName().contains("vivo Pencil2")) {
                connectToPen(device);
                return;
            }
        }

        showToast("未找到配对的触控笔");
    }

    private void connectToPen(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        bluetoothGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.d("GATT", "11状态变化: status=" + status + ", newState=" + newState);
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e("GATT", "连接断开，错误码: " + status);
                    runOnUiThread(() -> {
                        isServiceRunning = false;
                        btnControlService.setText("启动服务");
                        statusText.setText("服务状态: 已断开");
                    });
                }
            }


            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BluetoothGattService service = gatt.getService(PEN_SERVICE_UUID);
                    if (service != null) {

                        sendCommand(gatt, new byte[]{0x55, 0x04, 0x16, 0x01});

                        //设置定时任务
                        mCommandHandler.postDelayed(mSendCommandTask, 10000);

                        BluetoothGattCharacteristic commandChar = service.getCharacteristic(PEN_STATUS_UUID);
                        if (commandChar != null) {
                            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                return;
                            }
                            gatt.setCharacteristicNotification(commandChar, true);

                            BluetoothGattDescriptor descriptor = commandChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }

                            runOnUiThread(() -> {
                                isServiceRunning = true;
                                btnControlService.setText("停止服务");
                                statusText.setText("服务状态: 已启动");
                                showToast("服务已启动");
                            });
                        }
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

                if (characteristic.getUuid().equals(PEN_STATUS_UUID)) {
                    byte[] data = characteristic.getValue();
                    if (data != null && data.length > 0) {
                        handlePenCommand(data[0]);
                    }
                }
            }
        });
    }

    private void sendCommand(BluetoothGatt gatt, byte[] data) {
        if (gatt == null) return;

        BluetoothGattService service = gatt.getService(PEN_SERVICE_UUID);
        if (service == null) return;

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(PEN_COMMAND_UUID);
        if (characteristic == null) return;

        characteristic.setValue(data);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        gatt.writeCharacteristic(characteristic);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAccessibilityReceiver();
        checkAccessibilityService();
    }
    private void registerAccessibilityReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContentObserver observer = new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange) {
                    if (isAccessibilityServiceEnabled()) {
                        finish();
                        startActivity(getIntent());
                    }
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    observer
            );
        }
    }
    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + MyAccessibilityService.class.getName(); // 使用完整类名
        try {
            int enabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );

            if (enabled == 1) {
                String services = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                return services != null && Pattern.compile(Pattern.quote(serviceName), Pattern.CASE_INSENSITIVE)
                        .matcher(services).find();
            }
        } catch (Exception e) {
            Log.e("Accessibility", "检查失败", e);
        }
        return false;
    }
    private void checkAccessibilityService() {
        boolean isEnabled = isAccessibilityServiceEnabled();
        Log.d("Accessibility", "服务状态: " + isEnabled);
        if (!isAccessibilityServiceEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要无障碍权限")
                    .setMessage("触控笔翻页功能需要开启无障碍服务才能正常工作")
                    .setPositiveButton("去开启", (dialog, which) -> openAccessibilitySettings())
                    .setNegativeButton("取消", null)
                    .setCancelable(false)
                    .show();
        }
    }
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            Toast.makeText(this,
                    "请找到【" + getString(R.string.app_name) + "】并开启无障碍服务",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("Accessibility", "跳转设置失败", e);
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                startActivity(intent);
            } catch (Exception ex) {
                Log.e("Accessibility", "跳转常规设置失败", ex);
            }
        }
    }
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("请允许显示在其他应用上方，否则无法模拟点击")
                        .setPositiveButton("去设置", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }
    }



    private void handlePenCommand(byte command) {
        View decorView = getWindow().getDecorView();
        Rect visibleRect = new Rect();
        decorView.getWindowVisibleDisplayFrame(visibleRect);

        int usableWidth = visibleRect.width();
        int usableHeight = visibleRect.height();
        int offsetX = visibleRect.left;
        int offsetY = visibleRect.top;


        if (command != 16 && command != 17) return;

        int x = offsetX + ((command == 17) ? (int)(usableWidth*0.2) : (int)(usableWidth*0.8));
        int y = offsetY + (int)(usableHeight*0.5);


        MyAccessibilityService service = MyAccessibilityService.getInstance();
        if (service != null) {
            service.simulateClick(x, y);
        } else {
            Log.e("Touch", "无障碍服务未初始化");
        }


    }




    private void stopService() {
        //关闭定时任务
        mCommandHandler.removeCallbacks(mSendCommandTask);
        if (bluetoothGatt != null) {
            try {
                // 发送停止命令
                sendCommand(bluetoothGatt, new byte[]{0x55, 0x04, 0x16, 0x00});

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.disconnect();
                    bluetoothGatt.close();
                }
            } catch (Exception e) {
                Log.e("MainActivity", "停止服务失败", e);
            } finally {
                bluetoothGatt = null;
            }
        }

        isServiceRunning = false;
        btnControlService.setText("启动服务");
        statusText.setText("服务状态: 已停止");
        showToast("服务已停止");
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.close();
                }
            } catch (Exception e) {
                Log.e("MainActivity", "关闭蓝牙失败", e);
            }
        }
    }
}