package com.towerops.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.towerops.app.R;
import com.towerops.app.api.ShuyunApi;
import com.towerops.app.model.Session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 省内待办 Fragment
 *
 * 对应易语言"待办简易工单"查询功能：
 *  - 按小组（第一小组 6人 / 第二小组 5人）分类
 *  - 按工单类型（全部/应急/投诉/综合/其他）过滤
 *  - 按处理人过滤（单人 or 全部）
 *  - 5线程并发查询，加互斥锁写入列表
 *  - 仿生延迟（站点分组标注）
 */
public class ProvinceInnerOrderFragment extends Fragment {

    private static final String TAG = "ProvinceInnerFrag";

    // ── 分组常量（用于 station_name 匹配） ──────────────────────────
    // 每项：{常量关键词, 分组名称}
    private static final String[][] STATION_GROUP_RULES = {
        {"卢智伟",   "卢智伟、杨桂"},
        {"杨桂",     "卢智伟、杨桂"},
        {"高树调",   "高树调、倪传井"},
        {"倪传井",   "高树调、倪传井"},
        {"苏忠前",   "苏忠前、许方喜"},
        {"许方喜",   "苏忠前、许方喜"},
        {"黄经兴",   "黄经兴、蔡亮"},
        {"蔡亮",     "黄经兴、蔡亮"},
        {"陈德岳",   "陈德岳"},
    };

    // ── 小组成员 ────────────────────────────────────────────────────
    private static final String[][] GROUP1_MEMBERS = {
        {"12001", "林甲雨"},
        {"22961", "卢智伟"},
        {"12005", "高树调"},
        {"12004", "苏忠前"},
        {"12003", "黄经兴"},
        {"12007", "陶大取"}
    };
    private static final String[][] GROUP2_MEMBERS = {
        {"11961", "刘娟娟"},
        {"11956", "朱兴达"},
        {"11954", "王成"},
        {"11953", "夏念悦"},
        {"11950", "梅传威"}
    };

    // ── 行政区划代码（与数运审核保持一致）────────────────────────────────
    private static final String[] CITY_AREA_CODES = {
        "330326", "330329", "330302", "330327", "330328",
        "330381", "330382", "330303", "330305", "330324", "330383"
    };
    private static final String[] CITY_AREA_NAMES = {
        "平阳县", "泰顺县", "鹿城区", "苍南县", "文成县",
        "瑞安市", "乐清市", "龙湾区", "洞头区", "永嘉县", "龙港市"
    };

    // ── 工单类型 ────────────────────────────────────────────────────
    private static final String[] ORDER_TYPE_NAMES  = {"全部", "应急", "投诉", "综合", "其他"};
    private static final String[] ORDER_TYPE_CODES  = {
        "1124,1220,1028,1063",
        "1028",
        "1063",
        "1124,1220",
        "1118"
    };

    // ── UI ──────────────────────────────────────────────────────────
    private RecyclerView rvOrders;
    private ProvinceInnerOrderAdapter adapter;
    private Spinner spinnerGroup;
    private Spinner spinnerPerson;
    private Spinner spinnerOrderType;
    private Spinner spinnerCounty;      // 区县选择器
    private Button btnQuery;
    private TextView tvStatus;

    // ── 状态 ────────────────────────────────────────────────────────
    private int selectedGroupIndex      = 0;   // 0=第一小组, 1=第二小组
    private int selectedPersonIndex     = 0;   // 0=全部, 1..n=具体人
    private int selectedOrderTypeIndex  = 0;   // 工单类型下标
    private int selectedCountyIndex     = 0;   // 区县下标
    private volatile boolean isQuerying = false;

    // ── 主线程Handler ────────────────────────────────────────────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 并发相关 ─────────────────────────────────────────────────────
    private final ReentrantLock listLock = new ReentrantLock();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_province_inner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupSpinners();
        setupListeners();
    }

    // ── 初始化 ───────────────────────────────────────────────────────

    private void initViews(View view) {
        rvOrders       = view.findViewById(R.id.rvProvinceInnerOrders);
        spinnerGroup   = view.findViewById(R.id.spinnerPIGroup);
        spinnerPerson  = view.findViewById(R.id.spinnerPIPerson);
        spinnerOrderType = view.findViewById(R.id.spinnerPIOrderType);
        spinnerCounty  = view.findViewById(R.id.spinnerPICounty);
        btnQuery       = view.findViewById(R.id.btnPIQuery);
        tvStatus       = view.findViewById(R.id.tvPIStatus);

        adapter = new ProvinceInnerOrderAdapter();
        rvOrders.setLayoutManager(new LinearLayoutManager(getContext()));
        rvOrders.setAdapter(adapter);
    }

    private void setupSpinners() {
        // 小组
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{"第一小组", "第二小组"});
        groupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGroup.setAdapter(groupAdapter);

        // 区县（行政区划代码）
        ArrayAdapter<String> countyAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, CITY_AREA_NAMES);
        countyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCounty.setAdapter(countyAdapter);

        // 处理人（随小组动态生成）
        refreshPersonSpinner();

        // 工单类型
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, ORDER_TYPE_NAMES);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOrderType.setAdapter(typeAdapter);
    }

    /** 根据当前选中小组刷新处理人下拉 */
    private void refreshPersonSpinner() {
        String[][] members = selectedGroupIndex == 0 ? GROUP1_MEMBERS : GROUP2_MEMBERS;
        String[] names = new String[members.length + 1];
        names[0] = "全部";
        for (int i = 0; i < members.length; i++) {
            names[i + 1] = members[i][1];
        }
        ArrayAdapter<String> personAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, names);
        personAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPerson.setAdapter(personAdapter);
        selectedPersonIndex = 0;
    }

    private void setupListeners() {
        spinnerGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedGroupIndex = position;
                refreshPersonSpinner();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerPerson.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPersonIndex = position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerCounty.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCountyIndex = position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerOrderType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedOrderTypeIndex = position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnQuery.setOnClickListener(v -> startQuery());
    }

    // ── 查询主逻辑 ───────────────────────────────────────────────────

    private void startQuery() {
        if (isQuerying) {
            Toast.makeText(getContext(), "查询中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取PC登录Token
        Session s = Session.get();
        String pcToken     = s.shuyunPcToken;
        String cookieToken = s.shuyunPcTokenCookie;
        if (cookieToken == null || cookieToken.isEmpty()) cookieToken = pcToken;

        if (pcToken == null || pcToken.isEmpty()) {
            Toast.makeText(getContext(), "请先在监控页面登录PC端", Toast.LENGTH_SHORT).show();
            return;
        }

        // 确定查询哪些人
        String[][] members  = selectedGroupIndex == 0 ? GROUP1_MEMBERS : GROUP2_MEMBERS;
        String  orderType   = ORDER_TYPE_CODES[selectedOrderTypeIndex];

        // 区号：使用用户选择的行政区划代码
        String cityArea = CITY_AREA_CODES[selectedCountyIndex];

        final String finalPcToken     = pcToken;
        final String finalCookieToken = cookieToken;
        final String finalCityArea    = cityArea;

        // 确定要查询的成员列表（全部 or 指定人）
        final List<String[]> targets = new ArrayList<>();
        if (selectedPersonIndex == 0) {
            // 全部
            for (String[] member : members) {
                targets.add(member);
            }
        } else {
            // 选中人（下标从1开始，减1得members索引）
            int idx = selectedPersonIndex - 1;
            if (idx < members.length) {
                targets.add(members[idx]);
            }
        }

        if (targets.isEmpty()) {
            Toast.makeText(getContext(), "无可查询人员", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── 启动查询 ──
        isQuerying = true;
        btnQuery.setEnabled(false);
        btnQuery.setText("查询中...");
        adapter.setData(new ArrayList<>());
        tvStatus.setText("查询中 0/" + targets.size() + "...");

        final AtomicInteger doneCount = new AtomicInteger(0);
        final int totalCount          = targets.size();
        // 结果集（线程安全）
        final List<ShuyunApi.ProvinceInnerTaskInfo> resultList = new ArrayList<>();

        // 5线程池（与易语言一致）
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(5, totalCount));

        for (String[] member : targets) {
            final String userId   = member[0];
            final String handler  = member[1];

            executor.submit(() -> {
                try {
                    String json = ShuyunApi.getProvinceInnerTaskList(
                            finalPcToken, finalCookieToken, userId, orderType, finalCityArea);

                    List<ShuyunApi.ProvinceInnerTaskInfo> taskList =
                            ShuyunApi.parseProvinceInnerTaskList(json, handler, "");

                    // 为每条工单标注分组
                    for (ShuyunApi.ProvinceInnerTaskInfo task : taskList) {
                        task.groupName = matchGroup(task.station_name);
                    }

                    // 加锁写入结果集
                    listLock.lock();
                    try {
                        resultList.addAll(taskList);
                    } finally {
                        listLock.unlock();
                    }

                    int done = doneCount.incrementAndGet();
                    mainHandler.post(() -> {
                        tvStatus.setText("查询中 " + done + "/" + totalCount
                                + "... " + handler + " (" + taskList.size() + "条)");
                    });

                } catch (Exception e) {
                    int done = doneCount.incrementAndGet();
                    mainHandler.post(() -> {
                        tvStatus.setText("查询中 " + done + "/" + totalCount + "...");
                    });
                }

                // 最后一个完成时，更新UI
                if (doneCount.get() >= totalCount) {
                    // 按序号重新排（先按处理人顺序，保持原有顺序稳定）
                    for (int i = 0; i < resultList.size(); i++) {
                        resultList.get(i).index = String.valueOf(i + 1);
                    }
                    mainHandler.post(() -> {
                        adapter.setData(resultList);
                        tvStatus.setText("查询完成，共 " + resultList.size() + " 条工单");
                        btnQuery.setEnabled(true);
                        btnQuery.setText("我的待办");
                        isQuerying = false;
                    });
                }
            });
        }

        executor.shutdown();
    }

    /** 根据 station_name 匹配分组名称 */
    private String matchGroup(String stationName) {
        if (stationName == null || stationName.isEmpty()) return "";
        for (String[] rule : STATION_GROUP_RULES) {
            if (stationName.contains(rule[0])) {
                return rule[1];
            }
        }
        return "";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
