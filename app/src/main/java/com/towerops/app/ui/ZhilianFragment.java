package com.towerops.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.towerops.app.R;
import com.towerops.app.api.ZhilianApi;
import com.towerops.app.model.Session;
import com.towerops.app.worker.ZhilianMonitorTask;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 智联工单Fragment —— 对应易语言的智联工单监测界面
 */
public class ZhilianFragment extends Fragment {

    private static final String TAG = "ZhilianFragment";

    // UI控件
    private TextView tvZhilianStatus, tvUnclaimedCount, tvClaimedCount, tvLog;
    private CheckBox cbAutoAccept, cbAutoRevert;
    private Button btnStartZhilian, btnStopZhilian;
    private TabLayout tabLayoutZhilian;
    private RecyclerView rvUnclaimed, rvClaimed;
    private ScrollView svLog;
    private View tvEmpty;

    // 适配器
    private ZhilianAdapter unclaimedAdapter;
    private ZhilianAdapter claimedAdapter;

    // 监控任务
    private ZhilianMonitorTask monitorTask;
    private Thread monitorThread;

    // 主线程Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 当前选中的Tab
    private int currentTab = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_zhilian, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        setupRecyclerViews();
        setupListeners();
        loadConfig();
    }

    private void bindViews(View view) {
        tvZhilianStatus = view.findViewById(R.id.tvZhilianStatus);
        tvUnclaimedCount = view.findViewById(R.id.tvUnclaimedCount);
        tvClaimedCount = view.findViewById(R.id.tvClaimedCount);
        tvLog = view.findViewById(R.id.tvLog);
        cbAutoAccept = view.findViewById(R.id.cbAutoAccept);
        cbAutoRevert = view.findViewById(R.id.cbAutoRevert);
        btnStartZhilian = view.findViewById(R.id.btnStartZhilian);
        btnStopZhilian = view.findViewById(R.id.btnStopZhilian);
        tabLayoutZhilian = view.findViewById(R.id.tabLayoutZhilian);
        rvUnclaimed = view.findViewById(R.id.rvUnclaimed);
        rvClaimed = view.findViewById(R.id.rvClaimed);
        svLog = view.findViewById(R.id.svLog);
        tvEmpty = view.findViewById(R.id.tvEmpty);
    }

    private void setupRecyclerViews() {
        // 未领取列表
        rvUnclaimed.setLayoutManager(new LinearLayoutManager(getContext()));
        unclaimedAdapter = new ZhilianAdapter();
        unclaimedAdapter.setShowAcceptButton(true);
        unclaimedAdapter.setOnItemActionListener(new ZhilianAdapter.OnItemActionListener() {
            @Override
            public void onAcceptClick(ZhilianApi.ZhilianTaskInfo task) {
                manualAccept(task);
            }

            @Override
            public void onRevertClick(ZhilianApi.ZhilianTaskInfo task) {
                // 未领取列表不显示回单按钮
            }
        });
        rvUnclaimed.setAdapter(unclaimedAdapter);

        // 已领取列表
        rvClaimed.setLayoutManager(new LinearLayoutManager(getContext()));
        claimedAdapter = new ZhilianAdapter();
        claimedAdapter.setShowRevertButton(true);
        claimedAdapter.setOnItemActionListener(new ZhilianAdapter.OnItemActionListener() {
            @Override
            public void onAcceptClick(ZhilianApi.ZhilianTaskInfo task) {
                // 已领取列表不显示接单按钮
            }

            @Override
            public void onRevertClick(ZhilianApi.ZhilianTaskInfo task) {
                manualRevert(task);
            }
        });
        rvClaimed.setAdapter(claimedAdapter);
    }

    private void setupListeners() {
        // Tab切换
        tabLayoutZhilian.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                if (currentTab == 0) {
                    rvUnclaimed.setVisibility(View.VISIBLE);
                    rvClaimed.setVisibility(View.GONE);
                } else {
                    rvUnclaimed.setVisibility(View.GONE);
                    rvClaimed.setVisibility(View.VISIBLE);
                }
                updateEmptyState();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 启动监控
        btnStartZhilian.setOnClickListener(v -> startMonitor());

        // 停止监控
        btnStopZhilian.setOnClickListener(v -> stopMonitor());

        // 配置变更监听
        cbAutoAccept.setOnCheckedChangeListener((buttonView, isChecked) -> saveConfig());
        cbAutoRevert.setOnCheckedChangeListener((buttonView, isChecked) -> saveConfig());
    }

    private void loadConfig() {
        Session s = Session.get();
        if (!s.zhilianConfig.isEmpty()) {
            String[] cfg = s.zhilianConfig.split("\u0001", -1);
            if (cfg.length >= 2) {
                cbAutoAccept.setChecked("true".equalsIgnoreCase(cfg[0]));
                cbAutoRevert.setChecked("true".equalsIgnoreCase(cfg[1]));
            }
        }
    }

    private void saveConfig() {
        Session s = Session.get();
        String accept = cbAutoAccept.isChecked() ? "true" : "false";
        String revert = cbAutoRevert.isChecked() ? "true" : "false";
        s.zhilianConfig = accept + "\u0001" + revert;
        // 可以在这里添加持久化保存
    }

    private void startMonitor() {
        if (monitorTask != null && monitorTask.isRunning()) {
            Toast.makeText(getContext(), "监控已在运行中", Toast.LENGTH_SHORT).show();
            return;
        }

        saveConfig();

        monitorTask = new ZhilianMonitorTask();
        monitorTask.setCallback(new ZhilianMonitorTask.Callback() {
            @Override
            public void onStatusUpdate(String message) {
                appendLog(message);
                tvZhilianStatus.setText(message);
            }

            @Override
            public void onUnclaimedOrders(List<ZhilianApi.ZhilianTaskInfo> orders) {
                unclaimedAdapter.setData(orders);
                tvUnclaimedCount.setText("未领取: " + orders.size());
                updateEmptyState();
            }

            @Override
            public void onClaimedOrders(List<ZhilianApi.ZhilianTaskInfo> orders) {
                claimedAdapter.setData(orders);
                tvClaimedCount.setText("已领取: " + orders.size());
                updateEmptyState();
            }

            @Override
            public void onTaskAccepted(String id, boolean success) {
                String msg = success ? "接单成功" : "接单失败";
                Toast.makeText(getContext(), msg + ": " + id, Toast.LENGTH_SHORT).show();
                appendLog(msg + ": " + id);
            }

            @Override
            public void onTaskReverted(String id, boolean success) {
                String msg = success ? "回单成功" : "回单失败";
                Toast.makeText(getContext(), msg + ": " + id, Toast.LENGTH_SHORT).show();
                appendLog(msg + ": " + id);
            }
        });

        monitorThread = new Thread(monitorTask);
        monitorThread.start();

        btnStartZhilian.setEnabled(false);
        btnStopZhilian.setEnabled(true);

        appendLog("智联监控已启动");
    }

    private void stopMonitor() {
        if (monitorTask != null) {
            monitorTask.stop();
            monitorTask = null;
        }

        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }

        btnStartZhilian.setEnabled(true);
        btnStopZhilian.setEnabled(false);

        tvZhilianStatus.setText("智联监控已停止");
        appendLog("智联监控已停止");
    }

    private void manualAccept(ZhilianApi.ZhilianTaskInfo task) {
        new Thread(() -> {
            appendLog("手动接单: " + task.siteName);
            String result = ZhilianApi.acceptTask(task.id, task.versionId);
            boolean success = ZhilianApi.isSuccess(result);

            mainHandler.post(() -> {
                String msg = success ? "接单成功" : "接单失败";
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                appendLog(msg + ": " + task.siteName);
            });
        }).start();
    }

    private void manualRevert(ZhilianApi.ZhilianTaskInfo task) {
        new Thread(() -> {
            appendLog("手动回单: " + task.siteName);
            String result = ZhilianApi.revertTask(task.id, task.siteId);
            boolean success = ZhilianApi.isSuccess(result);

            mainHandler.post(() -> {
                String msg = success ? "回单成功" : "回单失败";
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                appendLog(msg + ": " + task.siteName);
            });
        }).start();
    }

    private void updateEmptyState() {
        boolean isEmpty;
        if (currentTab == 0) {
            isEmpty = unclaimedAdapter.getItemCount() == 0;
        } else {
            isEmpty = claimedAdapter.getItemCount() == 0;
        }
        tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logLine = "[" + time + "] " + message + "\n";

        mainHandler.post(() -> {
            tvLog.append(logLine);
            // 自动滚动到底部
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopMonitor();
    }
}
