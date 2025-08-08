package com.example.penpageturner;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class MyAccessibilityService extends AccessibilityService {

    private static MyAccessibilityService instance;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

    }

    @Override
    public void onInterrupt() {

    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    public void simulateClick(int x, int y) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(50); // 延迟
                    ClickUtils.click(MyAccessibilityService.this, x, y); // 模拟点击
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void performClick(AccessibilityNodeInfo node, int x, int y) {

        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
