package com.example.memoreminder;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.Base64;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.PopupWindow;
import android.animation.ValueAnimator;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private final ArrayList<Event> events = new ArrayList<>();
    private Calendar selectedDate = Calendar.getInstance();
    private ScheduleView scheduleView;
    private String sourceTitle = "";
    private String sourceText = "";
    private final ArrayList<Article> articles = new ArrayList<>();
    private final ArrayList<ChecklistGroup> checklistGroups = new ArrayList<>();
    private final ArrayList<ChecklistTemplate> checklistTemplates = new ArrayList<>();
    private int selectedChecklistGroup = 0;
    private ChecklistView checklistView;
    private TextView currentRulePreview;
    private TextView currentCopyButton;
    private String currentPage = "schedule";
    private static final String PREFS = "memo_reminder_data";

    private final int bg = Color.rgb(246, 250, 244);
    private final int green = Color.rgb(142, 184, 126);
    private final int checklistAccent = Color.rgb(162, 93, 70);
    private final int checklistBg = Color.rgb(252, 247, 244);
    private final int dark = Color.rgb(39, 43, 40);
    private final int muted = Color.rgb(145, 153, 145);

    @Override public void onCreate(Bundle state) {
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }
        loadData();
        ensureChecklistData();
        requestExactAlarmPermissionIfNeeded();
        restoreScheduledReminders();
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("last_main_tab_checklist", false)) showChecklist(); else showSchedule();
        getWindow().getDecorView().postDelayed(this::promptFloatingNotificationIfNeeded, 500);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!events.isEmpty()) restoreScheduledReminders();
    }

    private void requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarm != null && !alarm.canScheduleExactAlarms()) {
                android.widget.Toast.makeText(this, "请允许精确闹钟权限，提醒才能准时触发", android.widget.Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            }
        }
    }

    private void promptFloatingNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < 24) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("floating_prompt_shown_v2", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("开启弹窗提醒")
                .setMessage("为了像微信一样在屏幕顶部弹出日程提醒，请在下一页开启“浮动通知/横幅通知”。")
                .setNegativeButton("稍后再说", (dialog, which) -> { })
                .setPositiveButton("去开启", (dialog, which) -> {
                    prefs.edit().putBoolean("floating_prompt_shown_v2", true).apply();
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                }).show();
    }

    private void restoreScheduledReminders() {
        long now = System.currentTimeMillis();
        for (Event event : events) {
            if (!event.done && event.time > now) scheduleReminder(event);
        }
    }

    private void seedEvents() {
        Calendar base = (Calendar) selectedDate.clone();
        base.set(Calendar.HOUR_OF_DAY, 9); base.set(Calendar.MINUTE, 0);
        events.add(new Event("去机场", "提前办理值机并确认登机口", base.getTimeInMillis(), "提前3小时"));
        Calendar second = (Calendar) base.clone(); second.set(Calendar.HOUR_OF_DAY, 12); second.set(Calendar.MINUTE, 0);
        events.add(new Event("入住酒店", "办理入住，放下行李", second.getTimeInMillis(), "提前30分钟"));
        Calendar third = (Calendar) base.clone(); third.add(Calendar.DAY_OF_MONTH, 1); third.set(Calendar.HOUR_OF_DAY, 9);
        events.add(new Event("参观博物馆", "提前准备门票和身份证件", third.getTimeInMillis(), "准时"));
    }

    private void loadData() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sourceTitle = prefs.getString("sourceTitle", ""); sourceText = prefs.getString("sourceText", "");
        String savedArticles = prefs.getString("articles", "");
        if (!savedArticles.isEmpty()) {
            for (String item : savedArticles.split("\\n")) {
                String[] parts = item.split("\\|", -1);
                if (parts.length != 2) continue;
                try { articles.add(new Article(decode(parts[0]), decode(parts[1]))); } catch (Exception ignored) { }
            }
        }
        if (articles.isEmpty() && !sourceText.trim().isEmpty()) articles.add(new Article(sourceTitle, sourceText));
        String packed = prefs.getString("events", "");
        if (!packed.isEmpty()) {
            for (String item : packed.split("\\n")) {
                String[] parts = item.split("\\|", -1); if (parts.length != 5) continue;
                try { Event e = new Event(decode(parts[0]), decode(parts[1]), Long.parseLong(parts[2]), decode(parts[3])); e.done = "1".equals(parts[4]); events.add(e); } catch (Exception ignored) { }
            }
        }
        if (events.isEmpty()) seedEvents();
        long savedDate = prefs.getLong("selected_date", 0L);
        if (savedDate > 0L) selectedDate.setTimeInMillis(savedDate);
        String packedGroups = prefs.getString("checklist_groups", "");
        if (!packedGroups.isEmpty()) {
            for (String groupLine : packedGroups.split("\\n")) {
                String[] groupParts = groupLine.split("\\|", -1);
                if (groupParts.length < 1) continue;
                ChecklistGroup group = new ChecklistGroup(decode(groupParts[0]));
                if (groupParts.length > 1 && !groupParts[1].isEmpty()) {
                    for (String itemPart : groupParts[1].split(",", -1)) {
                        String[] itemParts = itemPart.split(":", -1);
                        if (itemParts.length != 2) continue;
                        group.items.add(new ChecklistItem(decode(itemParts[0]), "1".equals(itemParts[1])));
                    }
                }
                checklistGroups.add(group);
            }
        }
        selectedChecklistGroup = Math.max(0, Math.min(prefs.getInt("checklist_selected_group", 0), checklistGroups.size() - 1));
        String packedTemplates = prefs.getString("checklist_templates", "");
        if (!packedTemplates.isEmpty()) {
            for (String templateLine : packedTemplates.split("\\n")) {
                String[] parts = templateLine.split("\\|", -1);
                if (parts.length < 1) continue;
                ChecklistTemplate template = new ChecklistTemplate(decode(parts[0]));
                if (parts.length > 1 && !parts[1].isEmpty()) for (String item : parts[1].split(",", -1)) template.items.add(decode(item));
                checklistTemplates.add(template);
            }
        }
    }

    private void ensureChecklistData() {
        if (checklistGroups.isEmpty()) checklistGroups.add(new ChecklistGroup("我的清单"));
        selectedChecklistGroup = Math.max(0, Math.min(selectedChecklistGroup, checklistGroups.size() - 1));
    }

    private void saveData() {
        StringBuilder packed = new StringBuilder();
        for (Event e : events) { if (packed.length() > 0) packed.append('\n'); packed.append(encode(e.title)).append('|').append(encode(e.detail)).append('|').append(e.time).append('|').append(encode(e.reminder)).append('|').append(e.done ? '1' : '0'); }
        StringBuilder savedArticles = new StringBuilder();
        for (Article article : articles) {
            if (savedArticles.length() > 0) savedArticles.append('\n');
            savedArticles.append(encode(article.title)).append('|').append(encode(article.text));
        }
        StringBuilder packedGroups = new StringBuilder();
        for (ChecklistGroup group : checklistGroups) {
            if (packedGroups.length() > 0) packedGroups.append('\n');
            StringBuilder itemText = new StringBuilder();
            for (ChecklistItem item : group.items) {
                if (itemText.length() > 0) itemText.append(',');
                itemText.append(encode(item.title)).append(':').append(item.done ? '1' : '0');
            }
            packedGroups.append(encode(group.title)).append('|').append(itemText);
        }
        StringBuilder packedTemplates = new StringBuilder();
        for (ChecklistTemplate template : checklistTemplates) {
            if (packedTemplates.length() > 0) packedTemplates.append('\n');
            StringBuilder itemText = new StringBuilder();
            for (String item : template.items) { if (itemText.length() > 0) itemText.append(','); itemText.append(encode(item)); }
            packedTemplates.append(encode(template.title)).append('|').append(itemText);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("sourceTitle", sourceTitle).putString("sourceText", sourceText).putString("articles", savedArticles.toString()).putString("events", packed.toString()).putString("checklist_groups", packedGroups.toString()).putString("checklist_templates", packedTemplates.toString()).putInt("checklist_selected_group", selectedChecklistGroup).putLong("selected_date", selectedDate.getTimeInMillis()).putBoolean("last_main_tab_checklist", "checklist".equals(currentPage)).apply();
    }

    private String encode(String value) { return Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP); }
    private String decode(String value) { return new String(Base64.decode(value, Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8); }

    private void showSchedule() {
        currentPage = "schedule";
        saveData();
        scheduleView = new ScheduleView(this);
        setContentView(scheduleView);
    }

    private void showChecklist() {
        currentPage = "checklist";
        saveData();
        checklistView = new ChecklistView(this);
        setContentView(checklistView);
    }

    @Override public void onBackPressed() {
        if ("editor".equals(currentPage)) { showArticleList(); return; }
        if ("articles".equals(currentPage)) { showSchedule(); return; }
        if ("allSchedules".equals(currentPage)) { showSchedule(); return; }
        if ("allScheduleSelection".equals(currentPage)) { showAllSchedules(); return; }
        if ("scheduleDayDetail".equals(currentPage)) { showAllSchedules(); return; }
        if ("checklistTemplates".equals(currentPage)) { showChecklist(); return; }
        if ("checklist".equals(currentPage)) { showSchedule(); return; }
        super.onBackPressed();
    }

    private List<Event> eventsForSelectedDay() {
        ArrayList<Event> result = new ArrayList<>();
        for (Event event : events) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(event.time);
            if (c.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR)
                    && c.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR)) result.add(event);
        }
        Collections.sort(result, new Comparator<Event>() {
            @Override public int compare(Event a, Event b) { return Long.compare(a.time, b.time); }
        });
        return result;
    }

    private String monthLabel() {
        String[] names = {"一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"};
        return names[selectedDate.get(Calendar.MONTH)];
    }

    private void openEventEditor(final Event existing) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout box = formBox();
        box.setBackground(round(Color.WHITE, 22));
        final EditText title = field("日程标题", existing == null ? "" : existing.title);
        final EditText detail = field("日程详细描述", existing == null ? "" : existing.detail);
        detail.setMinLines(3); detail.setGravity(Gravity.TOP);
        final TextView time = new TextView(this);
        time.setTextSize(14); time.setTextColor(dark); time.setPadding(4, 12, 4, 12);
        final Calendar chosen = Calendar.getInstance();
        if (existing != null) chosen.setTimeInMillis(existing.time); else {
            chosen.setTimeInMillis(selectedDate.getTimeInMillis()); chosen.set(Calendar.HOUR_OF_DAY, 9); chosen.set(Calendar.MINUTE, 0);
        }
        Runnable refreshTime = () -> time.setText("时间：" + new SimpleDateFormat("yyyy年M月d日  HH:mm", Locale.CHINA).format(chosen.getTime()));
        refreshTime.run();
        time.setOnClickListener(v -> new DatePickerDialog(this, (view, y, m, d) -> {
            chosen.set(y, m, d);
            showTimePicker(chosen, refreshTime);
        }, chosen.get(Calendar.YEAR), chosen.get(Calendar.MONTH), chosen.get(Calendar.DAY_OF_MONTH)).show());
        String[] reminders = {"准时", "提前5分钟", "提前30分钟", "提前1小时", "提前1天", "自定义提前时间"};
        Spinner reminder = new Spinner(this);
        reminder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, reminders));
        if (existing != null) for (int i = 0; i < reminders.length; i++) if (reminders[i].equals(existing.reminder)) reminder.setSelection(i);
        reminder.setPadding(0, 8, 0, 8);
        EditText customMinutes = field("自定义提前分钟数，例如 90", "");
        customMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        customMinutes.setVisibility(reminder.getSelectedItemPosition() == reminders.length - 1 ? View.VISIBLE : View.GONE);
        reminder.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { customMinutes.setVisibility(position == reminders.length - 1 ? View.VISIBLE : View.GONE); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        TextView heading = label(existing == null ? "新增日程" : "编辑日程");
        heading.setTextSize(20); heading.setTextColor(dark); heading.setPadding(dp(4), dp(3), dp(4), dp(12));
        box.addView(heading); box.addView(title); box.addView(detail); box.addView(time); box.addView(label("提醒方式")); box.addView(reminder); box.addView(customMinutes);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        if (existing != null) {
            TextView delete = action("删除日程", false); delete.setTextColor(Color.rgb(210, 82, 76)); actions.addView(delete);
            Space spacer = new Space(this); actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除日程")
                    .setMessage("确定删除这个日程及其提醒吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (confirm, which) -> {
                        cancelReminder(existing);
                        events.remove(existing);
                        saveData(); dialog.dismiss(); scheduleView.invalidate();
                    }).show());
        }
        TextView cancel = action("取消", false); TextView save = action("保存", true); actions.addView(cancel); actions.addView(save); box.addView(actions);
        dialog.setContentView(box);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) { title.setError("请输入日程标题"); return; }
            String r = (String) reminder.getSelectedItem();
            if ("自定义提前时间".equals(r)) { if (customMinutes.getText().toString().trim().isEmpty()) { customMinutes.setError("请输入分钟数"); return; } r = "提前" + customMinutes.getText().toString().trim() + "分钟"; }
            Event saved;
            if (existing == null) { saved = new Event(title.getText().toString(), detail.getText().toString(), chosen.getTimeInMillis(), r); events.add(saved); }
            else { existing.title = title.getText().toString(); existing.detail = detail.getText().toString(); existing.time = chosen.getTimeInMillis(); existing.reminder = r; saved = existing; }
            scheduleReminder(saved);
            saveData(); dialog.dismiss(); scheduleView.invalidate();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.22f); window.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88f), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private void showTimePicker(final Calendar chosen, final Runnable refreshTime) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = formBox();
        box.setBackground(round(Color.WHITE, 22));
        TextView heading = label("选择时间");
        heading.setTextSize(19); heading.setTextColor(dark); heading.setPadding(dp(4), dp(2), dp(4), dp(8));
        TimePicker picker = new TimePicker(this);
        picker.setIs24HourView(true);
        picker.setHour(chosen.get(Calendar.HOUR_OF_DAY));
        picker.setMinute(chosen.get(Calendar.MINUTE));
        box.addView(heading);
        picker.setPadding(0, dp(14), 0, dp(18));
        box.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);
        TextView cancel = action("取消", false);
        TextView confirm = action("确定", true);
        actions.addView(cancel); actions.addView(confirm); box.addView(actions);
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            chosen.set(Calendar.HOUR_OF_DAY, picker.getHour());
            chosen.set(Calendar.MINUTE, picker.getMinute());
            refreshTime.run();
            dialog.dismiss();
        });
        dialog.setContentView(box);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.22f);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openTextEditor(final Article article) {
        currentPage = "editor";
        LinearLayout page = pageRoot();
        LinearLayout header = pageHeader("文本日程", v -> showArticleList(), v -> showTextMenu()); page.addView(header);
        EditText title = field("文章标题", article == null ? "" : article.title); EditText body = field("粘贴行程文本", article == null ? "" : article.text);
        body.setMinLines(18); body.setGravity(Gravity.TOP); body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        TextView rulePreview = new TextView(this); rulePreview.setText(aiScheduleRule()); rulePreview.setTextSize(12); rulePreview.setTextColor(dark); rulePreview.setPadding(dp(16), dp(14), dp(16), dp(14)); rulePreview.setBackground(round(Color.WHITE, 16)); rulePreview.setTextIsSelectable(true); rulePreview.setVisibility(View.GONE);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, dp(240)); ruleLp.setMargins(dp(20), 0, dp(20), dp(8)); page.addView(rulePreview, ruleLp);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(dp(20), dp(10), dp(20), dp(18));
        TextView save = action("重新生成日程", true); actions.addView(save); page.addView(actions);
        currentRulePreview = rulePreview; currentCopyButton = new TextView(this);
        rulePreview.setOnClickListener(v -> copyRuleToClipboard(currentCopyButton, rulePreview));
        save.setOnClickListener(v -> {
            String savedTitle = title.getText().toString().trim().isEmpty() ? "未命名文章" : title.getText().toString().trim();
            String savedText = body.getText().toString();
            if (article == null) articles.add(new Article(savedTitle, savedText));
            else { article.title = savedTitle; article.text = savedText; }
            sourceTitle = savedTitle; sourceText = savedText;
            rebuildEventsFromArticles(); saveData(); showSchedule();
        });
        setContentView(page);
    }

    private String aiScheduleRule() {
        return "请把下面的旅行/备忘录文本整理成日程，严格按以下格式输出：\n"
                + "1. 每行只输出一条日程，不要编号、项目符号、解释或 Markdown。\n"
                + "2. 格式必须是：MM月DD日 HH:mm 日程标题，提醒方式\n"
                + "3. 日期必须写完整，例如：10月03日；时间必须使用24小时制，例如：08:30。\n"
                + "4. 日程标题放在时间后面，标题要简短，不要包含逗号。\n"
                + "5. 提醒方式只能写：准时、提前5分钟、提前30分钟、提前1小时、提前1天；其他时长请写成提前N分钟。\n"
                + "6. 没有明确提醒要求时写：准时。\n"
                + "7. 没有明确日期时，使用原文中最近的明确日期；无法判断时不要编造。\n"
                + "8. 示例：\n"
                + "10月03日 08:00 前往机场，提前180分钟\n"
                + "现在只输出整理后的日程行：";
    }

    private void copyRuleToClipboard(TextView button, TextView preview) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            android.widget.Toast.makeText(this, "系统剪切板不可用，请检查系统权限", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String rule = aiScheduleRule();
        try {
            // 安卓不提供普通应用可申请的剪切板读写运行时权限。只写入，不回读校验，避免触发系统隐私限制。
            clipboard.setPrimaryClip(ClipData.newPlainText("日程整理规则", rule));
            Log.i("MemoReminder", "AI schedule rule copied, chars=" + rule.length());
            android.widget.Toast.makeText(this, "已复制到剪贴板", android.widget.Toast.LENGTH_LONG).show();
        } catch (SecurityException error) {
            Log.w("MemoReminder", "Clipboard write was blocked", error);
            android.widget.Toast.makeText(this, "系统阻止了剪贴板写入，请检查系统剪贴板权限", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void shareRuleWithAi() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, aiScheduleRule());
        startActivity(Intent.createChooser(share, "把整理规则发送给 AI"));
    }

    private void showTextMenu() {
        showMenu(new String[]{"复制 AI 整理规则", "直接分享给 AI"}, new Runnable[]{() -> copyRuleToClipboard(currentCopyButton, currentRulePreview), this::shareRuleWithAi});
    }

    private void showScheduleMenu() {
        showMenu(new String[]{"日程导入", "所有日程"}, new Runnable[]{this::showArticleList, this::showAllSchedules});
    }

    private ArrayList<String> allScheduleDateKeys() {
        LinkedHashMap<String, ArrayList<Event>> grouped = groupedSchedules();
        return new ArrayList<>(grouped.keySet());
    }

    private LinkedHashMap<String, ArrayList<Event>> groupedSchedules() {
        LinkedHashMap<String, ArrayList<Event>> grouped = new LinkedHashMap<>();
        ArrayList<Event> sorted = new ArrayList<>(events); Collections.sort(sorted, (a, b) -> Long.compare(a.time, b.time));
        SimpleDateFormat format = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
        for (Event event : sorted) { String key = format.format(new Date(event.time)); if (!grouped.containsKey(key)) grouped.put(key, new ArrayList<>()); grouped.get(key).add(event); }
        return grouped;
    }

    private void showAllSchedules() {
        LinearLayout page = pageRoot(); page.addView(pageHeader("所有日程", v -> showSchedule()));
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(10), dp(20), dp(24));
        LinkedHashMap<String, ArrayList<Event>> grouped = groupedSchedules();
        for (Map.Entry<String, ArrayList<Event>> entry : grouped.entrySet()) {
            TextView date = cardText(entry.getKey(), ""); date.setTextSize(16); list.addView(date, rowParams(48, 0, 0, 0, 8));
            date.setOnClickListener(v -> showScheduleDayDetail(entry.getValue().get(0).time));
            date.setOnLongClickListener(v -> { showAllScheduleSelection(); return true; });
        }
        if (grouped.isEmpty()) { TextView empty = label("还没有日程"); empty.setTextSize(16); empty.setPadding(dp(20), dp(24), dp(20), dp(24)); list.addView(empty); }
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); currentPage = "allSchedules";
    }

    private void showScheduleDayDetail(long dateMillis) {
        Calendar day = Calendar.getInstance(); day.setTimeInMillis(dateMillis); selectedDate.setTimeInMillis(dateMillis); saveData();
        LinearLayout page = pageRoot(); page.addView(pageHeader(new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(day.getTime()), v -> showAllSchedules()));
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(14), dp(20), dp(24));
        ArrayList<Event> dayEvents = new ArrayList<>(); for (Event event : events) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(event.time); if (c.get(Calendar.YEAR)==day.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR)==day.get(Calendar.DAY_OF_YEAR)) dayEvents.add(event); }
        Collections.sort(dayEvents, (a,b) -> Long.compare(a.time,b.time));
        for (final Event event : dayEvents) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, 0, 0, 0);
            TextView time = label(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(event.time))); time.setTextSize(14); time.setTextColor(green); time.setGravity(Gravity.CENTER);
            TextView marker = label("●"); marker.setTextSize(15); marker.setTextColor(green); marker.setGravity(Gravity.CENTER);
            TextView title = label(event.title); title.setTextSize(15); title.setTextColor(dark); title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(dp(16), 0, dp(8), 0); title.setBackground(round(Color.WHITE, 16));
            row.addView(time, new LinearLayout.LayoutParams(dp(64), -1)); row.addView(marker, new LinearLayout.LayoutParams(dp(34), -1)); row.addView(title, new LinearLayout.LayoutParams(0, -1, 1)); list.addView(row, rowParams(32, 0, 0, 0, 8));
        }
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); currentPage = "scheduleDayDetail";
    }

    private void showAllScheduleSelection() {
        LinearLayout page = pageRoot(); LinearLayout header = pageHeader("选择日期", v -> showAllSchedules()); page.addView(header);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(dp(20), 0, dp(20), dp(8));
        TextView selectAll = action("全选", false); TextView delete = actionColor("删除", false, Color.rgb(210, 82, 76)); actions.addView(selectAll); actions.addView(delete); page.addView(actions);
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), 0, dp(20), dp(24));
        ArrayList<CheckBox> checks = new ArrayList<>(); ArrayList<String> selectableDates = allScheduleDateKeys();
        for (String dateKey : selectableDates) { CheckBox check = new CheckBox(this); check.setText(dateKey); check.setTextSize(15); check.setTextColor(dark); check.setButtonTintList(android.content.res.ColorStateList.valueOf(green)); list.addView(check, rowParams(48, 0, 0, 0, 6)); checks.add(check); }
        selectAll.setOnClickListener(v -> { boolean all = true; for (CheckBox check : checks) if (!check.isChecked()) { all = false; break; } for (CheckBox check : checks) check.setChecked(!all); });
        delete.setOnClickListener(v -> { ArrayList<String> selectedDates = new ArrayList<>(); for (int i=0;i<checks.size();i++) if (checks.get(i).isChecked()) selectedDates.add(selectableDates.get(i)); if (selectedDates.isEmpty()) { android.widget.Toast.makeText(this, "请先选择日期", android.widget.Toast.LENGTH_SHORT).show(); return; } new AlertDialog.Builder(this).setTitle("删除日期").setMessage("删除所选日期下的全部日程吗？").setNegativeButton("取消", null).setPositiveButton("删除", (d,w) -> { SimpleDateFormat format = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA); ArrayList<Event> remove = new ArrayList<>(); for (Event event : events) if (selectedDates.contains(format.format(new Date(event.time)))) remove.add(event); events.removeAll(remove); saveData(); showAllSchedules(); }); });
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); currentPage = "allScheduleSelection";
    }

    private LinearLayout.LayoutParams rowParams(int height, int left, int top, int right, int bottom) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height < 0 ? -2 : dp(height)); lp.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return lp; }

    private void showMenu(String[] labels, Runnable[] actions) {
        showMenu(labels, actions, green);
    }

    private void showMenu(String[] labels, Runnable[] actions, int accent) {
        final Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout menu = new LinearLayout(this); menu.setOrientation(LinearLayout.VERTICAL); menu.setPadding(dp(8), dp(8), dp(8), dp(8)); menu.setBackground(round(Color.WHITE, 18));
        for (int i = 0; i < labels.length; i++) { TextView item = actionColor(labels[i], false, accent); item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL); item.setPadding(dp(16), 0, dp(16), 0); final int index = i; item.setOnClickListener(v -> { dialog.dismiss(); actions[index].run(); }); menu.addView(item, new LinearLayout.LayoutParams(-1, dp(48))); }
        dialog.setContentView(menu); dialog.setCanceledOnTouchOutside(true); dialog.show();
        Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.22f); WindowManager.LayoutParams lp = window.getAttributes(); lp.width = dp(210); lp.height = WindowManager.LayoutParams.WRAP_CONTENT; lp.gravity = Gravity.TOP | Gravity.RIGHT; lp.x = dp(14); lp.y = dp(74); window.setAttributes(lp); }
    }

    private void showArticleList() {
        currentPage = "articles";
        LinearLayout page = pageRoot();
        page.addView(pageHeader("已保存文本", v -> showSchedule()));
        TextView add = action("＋ 文本", true); LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(48)); addLp.setMargins(dp(20), dp(12), dp(20), dp(8)); page.addView(add, addLp); add.setOnClickListener(v -> openTextEditor(null));
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(4), dp(20), dp(24));
        if (!articles.isEmpty()) {
            for (final Article saved : articles) {
                ArticleRow item = new ArticleRow(saved);
                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, dp(48)); itemLp.setMargins(0, 0, 0, dp(8)); list.addView(item, itemLp);
            }
        }
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private void scheduleReminder(Event event) {
        long minutes = 0;
        Matcher matcher = Pattern.compile("提前(\\d+)分钟").matcher(event.reminder);
        if (event.reminder.contains("提前1小时")) minutes = 60;
        else if (event.reminder.contains("提前1天")) minutes = 24 * 60;
        else if (matcher.find()) minutes = Long.parseLong(matcher.group(1));
        long triggerAt = event.time - minutes * 60_000L;
        if (triggerAt <= System.currentTimeMillis()) return;
        Intent intent = new Intent(this, ReminderReceiver.class);
        intent.putExtra("title", event.title);
        intent.putExtra("detail", event.detail);
        int requestCode = (int) (event.time ^ event.title.hashCode());
        PendingIntent pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                Intent showApp = new Intent(this, MainActivity.class);
                PendingIntent showIntent = PendingIntent.getActivity(this, requestCode + 1, showApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                alarm.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending);
            }
            else alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        } catch (SecurityException ignored) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    private void cancelReminder(Event event) {
        int requestCode = (int) (event.time ^ event.title.hashCode());
        Intent intent = new Intent(this, ReminderReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarm != null) alarm.cancel(pending);
        pending.cancel();
    }

    private void parseText(String text) {
        events.clear();
        parseTextIntoEvents(text);
        if (events.isEmpty()) seedEvents();
    }

    private void rebuildEventsFromArticles() {
        events.clear();
        for (Article article : articles) parseTextIntoEvents(article.text);
        if (events.isEmpty()) seedEvents();
    }

    private void parseTextIntoEvents(String text) {
        Pattern p = Pattern.compile("(?:(\\d{1,2})月(\\d{1,2})日\\s*)?(\\d{1,2})[:：](\\d{2})\\s*(.*)");
        for (String line : text.split("\\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.matches()) continue;
            Calendar c = (Calendar) selectedDate.clone();
            if (m.group(1) != null) { c.set(Calendar.MONTH, Integer.parseInt(m.group(1)) - 1); c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(2))); }
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(3))); c.set(Calendar.MINUTE, Integer.parseInt(m.group(4)));
            String raw = m.group(5).trim();
            String title = raw.replaceAll("[，,].*", "").trim();
            Matcher customReminder = Pattern.compile("提前(\\d+)分钟").matcher(raw);
            String reminder = raw.contains("提前1天") ? "提前1天" : raw.contains("提前1小时") ? "提前1小时" : raw.contains("提前30分钟") ? "提前30分钟" : raw.contains("提前5分钟") ? "提前5分钟" : customReminder.find() ? "提前" + customReminder.group(1) + "分钟" : "准时";
            // Keep the complete imported line in the detail so punctuation used to
            // separate the reminder cannot cause any source text to be lost.
            Event event = new Event(title, raw, c.getTimeInMillis(), reminder);
            events.add(event); scheduleReminder(event);
        }
    }

    private void confirmDeleteArticle(final Article article) {
        new AlertDialog.Builder(this)
                .setTitle("删除文本")
                .setMessage("删除后，该文本生成的日程也会删除，确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    articles.remove(article);
                    rebuildEventsFromArticles();
                    saveData();
                    showArticleList();
                }).show();
    }

    private ChecklistGroup currentChecklistGroup() { ensureChecklistData(); return checklistGroups.get(selectedChecklistGroup); }

    private void addChecklistGroup() {
        final Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = formBox(); box.setBackground(round(Color.WHITE, 22));
        TextView heading = label("新建清单"); heading.setTextSize(20); heading.setTextColor(dark); heading.setPadding(dp(4), dp(3), dp(4), dp(12));
        final EditText input = field("分组名称", "");
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        TextView cancel = actionColor("取消", false, checklistAccent), add = actionColor("添加", true, checklistAccent); actions.addView(cancel); actions.addView(add);
        box.addView(heading); box.addView(input); box.addView(actions); dialog.setContentView(box);
        cancel.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> { String name = input.getText().toString().trim(); if (name.isEmpty()) { input.setError("请输入分组名称"); return; } checklistGroups.add(new ChecklistGroup(name)); selectedChecklistGroup = checklistGroups.size() - 1; saveData(); dialog.dismiss(); checklistView.invalidate(); });
        dialog.show(); Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.22f); window.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88f), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private void addChecklistItem() {
        final Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = formBox(); box.setBackground(round(Color.WHITE, 22));
        TextView heading = label("添加清单项"); heading.setTextSize(20); heading.setTextColor(dark); heading.setPadding(dp(4), dp(3), dp(4), dp(12));
        final EditText input = field("清单项", "");
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        TextView cancel = actionColor("取消", false, checklistAccent), add = actionColor("添加", true, checklistAccent); actions.addView(cancel); actions.addView(add);
        box.addView(heading); box.addView(input); box.addView(actions); dialog.setContentView(box);
        cancel.setOnClickListener(v -> dialog.dismiss());
        add.setOnClickListener(v -> { String text = input.getText().toString().trim(); if (text.isEmpty()) { input.setError("请输入清单项"); return; } currentChecklistGroup().items.add(new ChecklistItem(text, false)); saveData(); dialog.dismiss(); checklistView.invalidate(); });
        dialog.show(); Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.22f); window.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88f), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private void showChecklistGroupMenu(final int index) {
        new AlertDialog.Builder(this).setItems(new String[]{"删除清单", "存为模板", "导入模板"}, (dialog, which) -> {
            if (which == 0) {
                if (checklistGroups.size() == 1) { android.widget.Toast.makeText(this, "至少保留一个清单", android.widget.Toast.LENGTH_SHORT).show(); return; }
                new AlertDialog.Builder(this).setTitle("删除清单").setMessage("清单中的所有项目都会删除，确定继续吗？")
                        .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                            checklistGroups.remove(index); selectedChecklistGroup = Math.min(selectedChecklistGroup, checklistGroups.size() - 1); saveData(); checklistView.invalidate();
                        }).show();
            } else if (which == 1) {
                showSaveChecklistTemplateDialog(index);
            } else {
                selectedChecklistGroup = index;
                showChecklistTemplates();
            }
        }).show();
    }

    private void showSaveChecklistTemplateDialog(final int index) {
        final Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = formBox(); box.setBackground(round(Color.WHITE, 22));
        TextView heading = label("存为模板"); heading.setTextSize(20); heading.setTextColor(dark); heading.setPadding(dp(4), dp(3), dp(4), dp(12));
        final EditText name = field("模板名称", checklistGroups.get(index).title);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(12), 0, 0);
        TextView cancel = actionColor("取消", false, checklistAccent), save = actionColor("保存", true, checklistAccent); actions.addView(cancel); actions.addView(save);
        box.addView(heading); box.addView(name); box.addView(actions); dialog.setContentView(box);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String title = name.getText().toString().trim(); if (title.isEmpty()) { name.setError("请输入模板名称"); return; }
            ChecklistTemplate template = new ChecklistTemplate(title); for (ChecklistItem item : checklistGroups.get(index).items) template.items.add(item.title); checklistTemplates.add(template); saveData(); dialog.dismiss();
        });
        dialog.show(); Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.22f); window.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.88f), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private void showChecklistMenu() {
        showMenu(new String[]{"删除清单", "存为模板", "导入模板"}, new Runnable[]{
                () -> deleteChecklistGroup(selectedChecklistGroup),
                () -> showSaveChecklistTemplateDialog(selectedChecklistGroup),
                this::showChecklistTemplates
        }, checklistAccent);
    }

    private void deleteChecklistGroup(final int index) {
        if (checklistGroups.size() == 1) { android.widget.Toast.makeText(this, "至少保留一个清单", android.widget.Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("删除清单").setMessage("清单中的所有项目都会删除，确定继续吗？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    checklistGroups.remove(index); selectedChecklistGroup = Math.min(selectedChecklistGroup, checklistGroups.size() - 1); saveData(); checklistView.invalidate();
                }).show();
    }

    private void showChecklistTemplates() {
        LinearLayout page = pageRoot(); page.setBackgroundColor(checklistBg); page.addView(pageHeader("导入模板", v -> showChecklist()));
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(10), dp(20), dp(20));
        if (checklistTemplates.isEmpty()) {
            TextView empty = label("还没有模板"); empty.setTextSize(16); empty.setPadding(dp(20), dp(24), dp(20), dp(24)); list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final ChecklistTemplate template : checklistTemplates) {
            TemplateRow row = new TemplateRow(template);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, 0, 0, dp(8)); list.addView(row, lp);
        }
        scroll.addView(list); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page); currentPage = "checklistTemplates";
    }

    private void confirmDeleteChecklistTemplate(final ChecklistTemplate template) {
        new AlertDialog.Builder(this).setTitle("删除模板").setMessage("确定删除“" + template.title + "”吗？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> { checklistTemplates.remove(template); saveData(); showChecklistTemplates(); }).show();
    }

    private final class TemplateRow extends FrameLayout {
        private final TextView card;
        private final int deleteWidth = dp(64);
        private float downX;
        private float offset;
        private boolean moved;
        TemplateRow(final ChecklistTemplate template) {
            super(MainActivity.this); setClipChildren(true); setBackground(round(Color.WHITE, 16));
            TextView delete = new TextView(MainActivity.this); delete.setText("删除"); delete.setTextSize(15); delete.setTextColor(Color.WHITE); delete.setGravity(Gravity.CENTER); delete.setBackground(round(Color.rgb(218, 92, 84), 16));
            LayoutParams deleteLp = new LayoutParams(deleteWidth, -1, Gravity.RIGHT); addView(delete, deleteLp); delete.setTranslationX(deleteWidth); delete.setOnClickListener(v -> confirmDeleteChecklistTemplate(template));
            card = cardText(template.title, ""); card.setTextSize(15); LayoutParams cardLp = new LayoutParams(-1, -1); addView(card, cardLp);
            card.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downX = event.getRawX(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float distance = event.getRawX() - downX; if (Math.abs(distance) > dp(6)) moved = true;
                        offset = Math.max(-deleteWidth, Math.min(0, offset + distance)); downX = event.getRawX(); card.setTranslationX(offset); delete.setTranslationX(deleteWidth + offset); return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && offset == 0) {
                            ChecklistGroup group = currentChecklistGroup();
                            for (String item : template.items) {
                                boolean exists = false;
                                for (ChecklistItem existing : group.items) if (existing.title.equals(item)) { exists = true; break; }
                                if (!exists) group.items.add(new ChecklistItem(item, false));
                            }
                            saveData(); showChecklist();
                        }
                        else if (offset > -deleteWidth / 2f) { offset = 0; card.setTranslationX(0); delete.setTranslationX(deleteWidth); }
                        else { offset = -deleteWidth; card.setTranslationX(offset); delete.setTranslationX(0); }
                        return true;
                    default: return true;
                }
            });
        }
    }

    private void confirmDeleteChecklistItem(final ChecklistItem item) {
        new AlertDialog.Builder(this).setTitle("删除清单项").setMessage("确定删除“" + item.title + "”吗？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> { currentChecklistGroup().items.remove(item); saveData(); checklistView.invalidate(); }).show();
    }

    private final class ChecklistView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF plus = new RectF();
        private float downX, downY;
        private ChecklistItem swipedItem;
        private ChecklistItem dragItem;
        private boolean dragging;
        private ChecklistItem dragCandidate;
        private boolean dragReady;
        private float scrollOffset;
        private Runnable dragStart;
        private boolean completedExpanded = true;
        private final Bitmap completedChevron;
        private ChecklistItem animItem;
        private boolean animDone;
        private int animFromOrdinal;
        private int animToOrdinal;
        private float animProgress = 1f;
        ChecklistView(Context context) { super(context); setBackgroundColor(checklistBg); setLayerType(View.LAYER_TYPE_SOFTWARE, null); completedChevron = BitmapFactory.decodeResource(getResources(), getResources().getIdentifier("completed_chevron", "drawable", getPackageName())); }
        private void drawText(Canvas c, String text, float x, float y, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); c.drawText(text, x, y, paint); }
        private void drawCentered(Canvas c, String text, float center, float y, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); c.drawText(text, center - paint.measureText(text) / 2f, y, paint); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float w = getWidth(), d = w / 1080f; ChecklistGroup group = currentChecklistGroup();
            drawText(c, group.title, 42*d, 94*d, 21*d, dark);
            drawText(c, "⋯", w - 105*d, 95*d, 21*d, dark);
            float tabTop = 128*d, tabHeight = 68*d, tabX = 30*d;
            for (int i = 0; i < checklistGroups.size(); i++) {
                paint.setTextSize(16*d*2.3f); float tabWidth = Math.max(128*d, paint.measureText(checklistGroups.get(i).title) + 46*d);
                paint.setStyle(Paint.Style.FILL); paint.setColor(i == selectedChecklistGroup ? Color.rgb(235, 218, 208) : Color.WHITE); c.drawRoundRect(new RectF(tabX, tabTop, tabX + tabWidth, tabTop + tabHeight), 18*d, 18*d, paint);
                if (i == selectedChecklistGroup) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f*d); paint.setColor(Color.rgb(205, 157, 135)); c.drawRoundRect(new RectF(tabX+.75f*d, tabTop+.75f*d, tabX+tabWidth-.75f*d, tabTop+tabHeight-.75f*d), 17*d, 17*d, paint); }
                drawBoth(c, checklistGroups.get(i).title, tabX + tabWidth/2f, tabTop + tabHeight/2f, 16*d, i == selectedChecklistGroup ? dark : muted); tabX += tabWidth + 10*d;
            }
            float addX = Math.min(tabX, w - 76*d); paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); c.drawRoundRect(new RectF(addX, tabTop, addX+66*d, tabTop+tabHeight), 19*d, 19*d, paint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2*d); paint.setColor(checklistAccent); c.drawRoundRect(new RectF(addX+1*d, tabTop+1*d, addX+65*d, tabTop+tabHeight-1*d), 18*d, 18*d, paint); drawBoth(c, "+", addX+33*d, tabTop+tabHeight/2f, 24*d, checklistAccent);
            ArrayList<ChecklistItem> active = new ArrayList<>(), completed = new ArrayList<>(); for (ChecklistItem item : group.items) { if (item.done) completed.add(item); else active.add(item); }
            c.save(); c.clipRect(0, 220*d, w, getHeight()-145*d);
            float listTop = 236*d;
            if (!active.isEmpty()) {
                for (int i = 0; i < active.size(); i++) drawItem(c, active.get(i), i, animatedY(active.get(i), listTop + 50*d + i*123*d, i, false, 123*d)-scrollOffset, d, w);
            }
            float completedTop = listTop + Math.max(1, active.size())*123*d + 48*d;
            if (!completed.isEmpty()) {
                drawText(c, "已完成", 92*d, completedTop + 40*d-scrollOffset, 18*d, Color.rgb(175, 181, 176)); drawText(c, "" + completed.size(), w-105*d, completedTop + 40*d-scrollOffset, 18*d, Color.rgb(175, 181, 176));
                if (completedChevron != null) { float cx = w-62*d, cy = completedTop + 32*d-scrollOffset, size = 20*d; c.drawBitmap(completedChevron, null, new RectF(cx-size/2f, cy-size/2f, cx+size/2f, cy+size/2f), paint); }
                if (completedExpanded) for (int i = 0; i < completed.size(); i++) drawItem(c, completed.get(i), active.size()+i, animatedY(completed.get(i), completedTop + 115*d + i*117*d, i, true, 117*d)-scrollOffset, d, w);
            }
            c.restore();
            plus.set(w-200*d, getHeight()-325*d, w-60*d, getHeight()-185*d); paint.setStyle(Paint.Style.FILL); paint.setColor(checklistAccent); c.drawCircle(plus.centerX(), plus.centerY(), 70*d, paint); drawBoth(c, "+", plus.centerX(), plus.centerY(), 42*d, Color.WHITE);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); c.drawRect(0, getHeight()-145*d, w, getHeight(), paint); drawCentered(c, "清单", w*.25f, getHeight()-63*d, 15*d, checklistAccent); drawCentered(c, "日程", w*.75f, getHeight()-63*d, 15*d, muted);
        }
        private void drawItem(Canvas c, ChecklistItem item, int globalIndex, float y, float d, float w) {
            boolean swiped = item == swipedItem; float right = w-60*d; float cardRight = swiped ? w-150*d : right;
            if (swiped) { paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(218, 92, 84)); c.drawRoundRect(new RectF(w-150*d, y-50*d, right, y+51*d), 14*d, 14*d, paint); drawBoth(c, "删除", w-105*d, y, 14*d, Color.WHITE); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); if (!item.done) paint.setShadowLayer(4*d, 0, 2*d, 0x16000000); c.drawRoundRect(new RectF(42*d, y-50*d, cardRight, y+51*d), 22*d, 22*d, paint); paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3*d); paint.setColor(item.done ? Color.rgb(220, 223, 220) : Color.rgb(180, 187, 181)); c.drawRoundRect(new RectF(92*d, y-20*d, 132*d, y+20*d), 9*d, 9*d, paint);
            if (item.done) { paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(220, 223, 220)); c.drawRoundRect(new RectF(92*d, y-20*d, 132*d, y+20*d), 9*d, 9*d, paint); drawCentered(c, "✓", 112*d, y+14*d, 16*d, Color.WHITE); }
            int color = item.done ? Color.rgb(168, 175, 169) : Color.rgb(18, 23, 19); drawVertical(c, item.title, 166*d, y, 17*d, color);
        }
        private void drawVertical(Canvas c, String text, float x, float centerY, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setFakeBoldText(false); paint.setTextSize(size * 2.3f); paint.setColor(color); Paint.FontMetrics metrics = paint.getFontMetrics(); float baseline = centerY - (metrics.ascent + metrics.descent) / 2f; c.drawText(text, x, baseline, paint); }
        private void drawBoth(Canvas c, String text, float centerX, float centerY, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); Paint.FontMetrics metrics = paint.getFontMetrics(); float baseline = centerY - (metrics.ascent + metrics.descent) / 2f; c.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint); }
        private float animatedY(ChecklistItem item, float baseY, int ordinal, boolean done, float spacing) {
            if (item == animItem && done == animDone) return baseY + (animFromOrdinal - animToOrdinal) * spacing * (1f - animProgress);
            if (animItem != null && done == animDone && animFromOrdinal != animToOrdinal) {
                if (animToOrdinal > animFromOrdinal && ordinal > animFromOrdinal && ordinal <= animToOrdinal) return baseY - spacing * (1f - animProgress);
                if (animToOrdinal < animFromOrdinal && ordinal >= animToOrdinal && ordinal < animFromOrdinal) return baseY + spacing * (1f - animProgress);
            }
            return baseY;
        }
        private void clampScroll(float d) {
            ChecklistGroup group = currentChecklistGroup(); int activeCount = 0, completedCount = 0; for (ChecklistItem item : group.items) { if (item.done) completedCount++; else activeCount++; }
            float contentBottom = 236*d + Math.max(1, activeCount)*123*d + 48*d;
            if (completedCount > 0) contentBottom += 40*d + (completedExpanded ? 115*d + completedCount*117*d : 62*d);
            float max = Math.max(0, contentBottom - (getHeight()-165*d)); scrollOffset = Math.max(0, Math.min(max, scrollOffset));
        }
        private int itemAt(float y, float d) {
            ChecklistGroup group = currentChecklistGroup(); int activeCount = 0; for (ChecklistItem item : group.items) if (!item.done) activeCount++;
            float listTop = 236*d;
            y += scrollOffset;
            if (y >= listTop && y <= listTop + activeCount*123*d) { int i = (int)((y-listTop)/123*d); return findItemIndex(false, i); }
            float completedTop = listTop + Math.max(1, activeCount)*123*d + 48*d;
            if (completedExpanded && y >= completedTop+75*d) { int i = (int)((y-(completedTop+75*d))/117*d); return findItemIndex(true, i); }
            return -1;
        }
        private int findItemIndex(boolean done, int ordinal) { ChecklistGroup group = currentChecklistGroup(); int n=0; for (int i=0;i<group.items.size();i++) if (group.items.get(i).done==done) { if(n++==ordinal)return i; } return -1; }
        private void moveChecklistItem(ChecklistItem moving, ChecklistItem target) { if (moving == target || moving.done != target.done) return; ChecklistGroup group = currentChecklistGroup(); group.items.remove(moving); int targetIndex = group.items.indexOf(target); if (targetIndex < 0) group.items.add(moving); else group.items.add(targetIndex, moving); }
        private int ordinalOf(ChecklistItem item, boolean done) { int ordinal = 0; for (ChecklistItem candidate : currentChecklistGroup().items) { if (candidate.done == done) { if (candidate == item) return ordinal; ordinal++; } } return -1; }
        private void startReorderAnimation(ChecklistItem item, boolean done, int from, int to) {
            if (from == to) return; animItem = item; animDone = done; animFromOrdinal = from; animToOrdinal = to; animProgress = 0f;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(180); animator.addUpdateListener(v -> { animProgress = (Float)v.getAnimatedValue(); invalidate(); }); animator.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { animProgress = 1f; animItem = null; invalidate(); } }); animator.start();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float d=getWidth()/1080f, x=event.getX(), y=event.getY();
            if (event.getAction()==MotionEvent.ACTION_DOWN) {
                downX=x; downY=y;
                int pressed = itemAt(y, d); dragCandidate = pressed >= 0 ? currentChecklistGroup().items.get(pressed) : null; dragItem = null; dragReady = false; dragging = false;
                if (dragCandidate != null) { dragStart = () -> { dragItem = dragCandidate; dragReady = true; }; postDelayed(dragStart, 450); }
                return true;
            }
            if (event.getAction()==MotionEvent.ACTION_MOVE) {
                if (!dragReady) {
                    if (Math.abs(y-downY) > Math.abs(x-downX)) { if (dragStart != null) removeCallbacks(dragStart); scrollOffset += downY-y; downY=y; clampScroll(d); invalidate(); }
                    return true;
                }
                if (dragItem != null && Math.abs(y-downY) > 8*d) {
                    int targetIndex = itemAt(y, d);
                    if (targetIndex >= 0) { ChecklistItem target = currentChecklistGroup().items.get(targetIndex); if (target != dragItem && target.done == dragItem.done) { int from = ordinalOf(dragItem, dragItem.done), to = ordinalOf(target, target.done); moveChecklistItem(dragItem, target); startReorderAnimation(dragItem, dragItem.done, from, to); dragging = true; invalidate(); } }
                }
                return true;
            }
            if (event.getAction()!=MotionEvent.ACTION_UP) return true;
            if (dragStart != null) { removeCallbacks(dragStart); dragStart = null; }
            if (dragging || dragReady) { if (dragging) saveData(); dragItem = null; dragCandidate = null; dragReady = false; dragging = false; invalidate(); return true; }
            dragCandidate = null;
            if (y>getHeight()-130*d) { if(x>=getWidth()/2f) showSchedule(); return true; }
            if (y < 130*d && x > getWidth()-175*d) { showChecklistMenu(); return true; }
            if (plus.contains(x,y)) { addChecklistItem(); return true; }
            if (y>=118*d && y<=208*d) { int index=groupAt(x,d); if(index>=0){ selectedChecklistGroup=index;swipedItem=null;saveData();invalidate(); } else { float addX = Math.min(groupTabsEnd(d), getWidth()-76*d); if (x >= addX && x <= addX+66*d) addChecklistGroup(); } return true; }
            ChecklistGroup current = currentChecklistGroup(); int completedCount = 0; for (ChecklistItem item : current.items) if (item.done) completedCount++;
            float completedTop = 236*d + Math.max(1, current.items.size()-completedCount)*123*d + 48*d;
            if (completedCount > 0 && y+scrollOffset >= completedTop-8*d && y+scrollOffset <= completedTop+62*d) { completedExpanded = !completedExpanded; swipedItem=null; invalidate(); return true; }
            int index=itemAt(y,d); if(index>=0) { ChecklistItem item=currentChecklistGroup().items.get(index); float dx=downX-x, dy=downY-y; if(swipedItem==item && Math.abs(dy)<24*d && x>getWidth()-180*d){confirmDeleteChecklistItem(item);return true;} if(dx>70*d && Math.abs(dy)<24*d && dx>Math.abs(dy)*1.2f){swipedItem=dragCandidate == null ? item : dragCandidate;invalidate();return true;} if(Math.abs(dx)<30*d && Math.abs(dy)<30*d){if(x<=150*d){item.done=!item.done;saveData();}swipedItem=null;invalidate();} else {swipedItem=null;invalidate();} }
            return true;
        }
        private int groupAt(float x,float d){float tabX=30*d;for(int i=0;i<checklistGroups.size();i++){paint.setTextSize(16*d*2.3f);float width=Math.max(128*d,paint.measureText(checklistGroups.get(i).title)+46*d);if(x>=tabX&&x<=tabX+width)return i;tabX+=width+10*d;}return -1;}
        private float groupTabsEnd(float d){float tabX=30*d;for(int i=0;i<checklistGroups.size();i++){paint.setTextSize(16*d*2.3f);tabX+=Math.max(128*d,paint.measureText(checklistGroups.get(i).title)+46*d)+10*d;}return tabX;}
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable round(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout pageRoot() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setBackgroundColor(bg); l.setPadding(0, 0, 0, 0); return l; }
    private LinearLayout formBox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(22), dp(18), dp(22), dp(14)); return l; }
    private LinearLayout pageHeader(String title, View.OnClickListener back) { return pageHeader(title, back, null); }
    private LinearLayout pageHeader(String title, View.OnClickListener back, View.OnClickListener menuClick) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), 0, dp(8), 0); row.setMinimumHeight(dp(52));
        ImageView close = new ImageView(this); int backIcon = getResources().getIdentifier("nav_back", "drawable", getPackageName()); close.setImageResource(backIcon); close.setScaleType(ImageView.ScaleType.CENTER); close.setPadding(0, 0, 0, 0); close.setScaleX(0.46f); close.setScaleY(0.46f); close.setContentDescription("返回"); close.setOnClickListener(back);
        TextView name = new TextView(this); name.setText(title); name.setTextSize(17); name.setTextColor(dark); name.setGravity(Gravity.CENTER); LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -1, 1); row.addView(close, new LinearLayout.LayoutParams(dp(52), -1)); row.addView(name, nameLp);
        TextView right = new TextView(this); right.setText(menuClick == null ? "" : "⋯"); right.setTextSize(18); right.setTextColor(dark); right.setGravity(Gravity.CENTER); if (menuClick != null) right.setOnClickListener(menuClick); row.addView(right, new LinearLayout.LayoutParams(dp(52), -1)); return row;
    }
    private TextView action(String text, boolean filled) { return actionColor(text, filled, green); }
    private TextView actionColor(String text, boolean filled, int accent) { TextView t = new TextView(this); t.setText(text); t.setTextSize(15); t.setTextColor(filled ? Color.WHITE : accent); t.setGravity(Gravity.CENTER); t.setPadding(dp(15), 0, dp(15), 0); t.setBackground(round(filled ? accent : Color.TRANSPARENT, 18)); t.setMinHeight(dp(44)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44)); lp.setMargins(dp(5), 0, dp(5), 0); t.setLayoutParams(lp); return t; }
    private TextView cardText(String title, String subtitle) { TextView t = new TextView(this); t.setText(subtitle == null || subtitle.isEmpty() ? title : title + "\n" + subtitle); t.setTextSize(17); t.setTextColor(dark); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(18), 0, dp(18), 0); t.setBackground(round(Color.WHITE, 18)); return t; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextSize(15); e.setTextColor(dark); e.setHintTextColor(muted); e.setPadding(dp(16), dp(10), dp(16), dp(10)); e.setBackground(round(Color.WHITE, 16)); return e; }
    private TextView label(String text) { TextView t = new TextView(this); t.setText(text); t.setTextSize(12); t.setTextColor(muted); t.setPadding(dp(4), dp(4), dp(4), 0); return t; }

    private final class ArticleRow extends FrameLayout {
        private final TextView card;
        private float downX;
        private float offset;
        private boolean moved;
        ArticleRow(final Article article) {
            super(MainActivity.this);
            setClipChildren(true);
            setBackground(round(Color.WHITE, 16));
            TextView delete = new TextView(MainActivity.this);
            delete.setText("删除"); delete.setTextSize(15); delete.setTextColor(Color.WHITE); delete.setGravity(Gravity.CENTER);
            delete.setBackground(round(Color.rgb(218, 92, 84), 16));
            final int deleteWidth = dp(64);
            LayoutParams deleteLp = new LayoutParams(deleteWidth, -1, Gravity.RIGHT); addView(delete, deleteLp);
            delete.setTranslationX(deleteWidth);
            delete.setOnClickListener(v -> confirmDeleteArticle(article));
            card = cardText(article.title, "");
            card.setTextSize(15);
            LayoutParams cardLp = new LayoutParams(-1, -1); addView(card, cardLp);
            card.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float distance = event.getRawX() - downX;
                        if (Math.abs(distance) > dp(6)) moved = true;
                        offset = Math.max(-deleteWidth, Math.min(0, offset + distance));
                        card.setTranslationX(offset); delete.setTranslationX(deleteWidth + offset); downX = event.getRawX(); return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) offset = offset < -dp(32) ? -deleteWidth : 0;
                        if (moved) { card.setTranslationX(offset); delete.setTranslationX(deleteWidth + offset); }
                        else if (offset == 0) post(() -> openTextEditor(article));
                        else { offset = 0; card.setTranslationX(0); delete.setTranslationX(deleteWidth); }
                        return true;
                    default: return true;
                }
            });
        }
    }

    private final class ScheduleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private boolean monthExpanded = false; private RectF plus = new RectF(); private float touchDownY; private float touchDownX, touchLastY, scrollOffset; private final Map<String,Float> dateScrollOffsets=new LinkedHashMap<>(); private String scrollDateKey=""; private boolean timelineDragging; private float monthAnimationProgress = 1f; private int monthAnimationDirection = 0;
        ScheduleView(Context c) { super(c); paint.setTypeface(android.graphics.Typeface.create("sans", 0)); setBackgroundColor(bg); }
        private void text(Canvas c, String s, float x, float y, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); c.drawText(s, x, y, paint); }
        private void centeredText(Canvas c, String s, float centerX, float y, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); c.drawText(s, centerX - paint.measureText(s) / 2f, y, paint); }
        private void centeredVerticalText(Canvas c, String s, float x, float centerY, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setFakeBoldText(false); paint.setTextSize(size * 2.3f); paint.setColor(color); Paint.FontMetrics metrics = paint.getFontMetrics(); float baseline = centerY - (metrics.ascent + metrics.descent) / 2f; c.drawText(s, x, baseline, paint); }
        private void centeredBothText(Canvas c, String s, float centerX, float centerY, float size, int color) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * 2.3f); paint.setColor(color); Paint.FontMetrics metrics = paint.getFontMetrics(); float baseline = centerY - (metrics.ascent + metrics.descent) / 2f; c.drawText(s, centerX - paint.measureText(s) / 2f, baseline, paint); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); syncDateScroll(); float w = getWidth(); float d = w / 1080f;
            text(c, monthLabel(), 42*d, 84*d, 21*d, dark);
            text(c, "⋯", w - 105*d, 84*d, 21*d, dark);
            if (monthAnimationDirection != 0) { c.save(); c.clipRect(0, 130*d, w, 640*d); c.translate(monthAnimationDirection * (1f - monthAnimationProgress) * w, 0); }
            int first = firstDayOfWeek(selectedDate), days = selectedDate.getActualMaximum(Calendar.DAY_OF_MONTH); int rows = monthExpanded ? monthRows() : 1;
            float top = 180*d; float col = w/7f;
            String[] weeks = {"一", "二", "三", "四", "五", "六", "日"};
            for (int i = 0; i < 7; i++) centeredText(c, weeks[i], col*i + col/2, top, 11*d, muted);
            if (monthExpanded) {
                for (int day = 1; day <= days; day++) { int pos = first + day - 1; float x = pos%7*col + col/2, y = top + 78*d + pos/7*dateRowHeight()*d; if (day == selectedDate.get(Calendar.DAY_OF_MONTH)) { paint.setColor(green); c.drawCircle(x, y-8*d, 42*d, paint); } centeredText(c, ""+day, x, y, 15*d, day == selectedDate.get(Calendar.DAY_OF_MONTH) ? Color.WHITE : dark); }
            } else {
                Calendar row = (Calendar) selectedDate.clone(); int weekday = (row.get(Calendar.DAY_OF_WEEK) + 5) % 7; row.add(Calendar.DAY_OF_MONTH, -weekday);
                for (int i = 0; i < 7; i++) { int day = row.get(Calendar.DAY_OF_MONTH); float x = col*i + col/2; boolean selected = row.get(Calendar.YEAR) == selectedDate.get(Calendar.YEAR) && row.get(Calendar.DAY_OF_YEAR) == selectedDate.get(Calendar.DAY_OF_YEAR); if (selected) { paint.setColor(green); c.drawCircle(x, top + 78*d, 42*d, paint); } centeredText(c, ""+day, x, top + 87*d, 15*d, selected ? Color.WHITE : dark); row.add(Calendar.DAY_OF_MONTH, 1); }
            }
            if (monthAnimationDirection != 0) c.restore();
            float contentTop = (monthExpanded ? 180*d + 78*d + monthRows()*dateRowHeight()*d + 20*d : 180*d + 153*d + 20*d); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f*d); paint.setColor(Color.rgb(222,228,221)); c.drawLine(30*d,contentTop-10*d,w-30*d,contentTop-10*d,paint); clampScheduleScroll(contentTop,d); c.save(); c.clipRect(0, contentTop, w, getHeight()-145*d); c.translate(0,-scrollOffset); drawTimeline(c, contentTop, d, w); c.restore();
            plus.set(w-200*d, getHeight()-325*d, w-60*d, getHeight()-185*d); paint.setColor(green); c.drawCircle(plus.centerX(), plus.centerY(), 70*d, paint); centeredBothText(c, "+", plus.centerX(), plus.centerY(), 42*d, Color.WHITE);
            drawBottomTabs(c, w, d, false);
        }
        private void drawBottomTabs(Canvas c, float w, float d, boolean checklist) {
            float top = getHeight() - 145*d; paint.setColor(Color.WHITE); c.drawRect(0, top, w, getHeight(), paint);
            centeredText(c, "清单", w*0.25f, top + 82*d, 15*d, checklist ? checklistAccent : muted);
            centeredText(c, "日程", w*0.75f, top + 82*d, 15*d, checklist ? muted : green);
        }
        private void drawTimeline(Canvas c, float top, float d, float w) {
            List<Event> dayEvents = eventsForSelectedDay(); float y = top + 38*d; float lineX = 175*d; float lineEnd = y + Math.max(0, dayEvents.size()-1)*130*d; paint.setStrokeWidth(5*d); paint.setColor(Color.rgb(218, 226, 217)); c.drawLine(lineX, y, lineX, lineEnd, paint);
            if (!dayEvents.isEmpty()) {
                long nowMillis = System.currentTimeMillis();
                float progressY = y;
                if (nowMillis >= dayEvents.get(dayEvents.size()-1).time) progressY = lineEnd;
                else if (nowMillis > dayEvents.get(0).time) {
                    for (int i = 0; i < dayEvents.size()-1; i++) {
                        long start = dayEvents.get(i).time, end = dayEvents.get(i+1).time;
                        if (nowMillis >= start && nowMillis < end) {
                            float fraction = (float)(nowMillis - start) / (float)Math.max(1L, end - start);
                            progressY = y + (i + fraction) * 130*d;
                            break;
                        }
                    }
                }
                paint.setColor(green); c.drawLine(lineX, y, lineX, progressY, paint);
            }
            Calendar now = Calendar.getInstance();
            for (int i=0; i<dayEvents.size(); i++) { Event e = dayEvents.get(i); Calendar ec=Calendar.getInstance(); ec.setTimeInMillis(e.time); boolean done=e.done; boolean past=!done && e.time < now.getTimeInMillis(); float yy=y+i*130*d; String tm=new SimpleDateFormat("h:mm a", Locale.US).format(ec.getTime());
                text(c, tm, 22*d, yy+6*d, 13.5f*d, done ? muted : (past ? green : muted)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3*d); paint.setColor(done ? muted : (past ? green : muted)); c.drawCircle(lineX, yy, 18*d, paint); if(done){paint.setStyle(Paint.Style.FILL); c.drawCircle(lineX, yy, 14*d, paint); centeredText(c,"✓",lineX,yy+6*d,14*d,Color.WHITE);} 
                RectF card = new RectF(210*d, yy-34*d, w-18*d, yy+52*d); paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); c.drawRoundRect(card, 15*d, 15*d, paint); int color=done?Color.rgb(178,184,179):Color.rgb(18,23,19); centeredVerticalText(c,e.title, 237*d, card.centerY(), 15.5f*d, color);
            }
            if(dayEvents.isEmpty()) centeredText(c,"今天还没有日程",w/2f,y+10*d,17*d,muted);
        }
        private int firstDayOfWeek(Calendar c) { Calendar x=(Calendar)c.clone(); x.set(Calendar.DAY_OF_MONTH,1); int v=x.get(Calendar.DAY_OF_WEEK)-Calendar.SUNDAY; return (v+6)%7; }
        private float dateRowHeight(){return 74f;}
        private int monthRows(){return (firstDayOfWeek(selectedDate)+selectedDate.getActualMaximum(Calendar.DAY_OF_MONTH)+6)/7;}
        private String scheduleDateKey(){return selectedDate.get(Calendar.YEAR)+"-"+selectedDate.get(Calendar.MONTH)+"-"+selectedDate.get(Calendar.DAY_OF_MONTH);}
        private void syncDateScroll(){String key=scheduleDateKey();if(!key.equals(scrollDateKey)){if(!scrollDateKey.isEmpty())dateScrollOffsets.put(scrollDateKey,scrollOffset);scrollDateKey=key;Float saved=dateScrollOffsets.get(key);scrollOffset=saved==null?0:saved;}}
        private void clampScheduleScroll(float contentTop,float d){int count=eventsForSelectedDay().size();float bottom=contentTop+38*d+Math.max(0,count-1)*130*d+52*d+56*d;float max=Math.max(0,bottom-(getHeight()-145*d));scrollOffset=Math.max(0,Math.min(max,scrollOffset));dateScrollOffsets.put(scheduleDateKey(),scrollOffset);}
        @Override public boolean onTouchEvent(MotionEvent e) { float d=getWidth()/1080f; float contentTop=(monthExpanded?180*d+78*d+monthRows()*dateRowHeight()*d+20*d:180*d+153*d+20*d); if(e.getAction()==MotionEvent.ACTION_DOWN){touchDownX=e.getX();touchDownY=e.getY();touchLastY=touchDownY;timelineDragging=false;return true;} if(e.getAction()==MotionEvent.ACTION_MOVE){if(touchDownY>=contentTop&&touchDownY<getHeight()-145*d&&Math.abs(e.getY()-touchDownY)>Math.abs(e.getX()-touchDownX)){timelineDragging=true;scrollOffset+=touchLastY-e.getY();touchLastY=e.getY();clampScheduleScroll(contentTop,d);invalidate();}return true;} if(e.getAction()!=MotionEvent.ACTION_UP)return true; float x=e.getX(),y=e.getY(); if(timelineDragging){timelineDragging=false;return true;}
            if (y > getHeight() - 135*d) { if (x < getWidth()/2f) showChecklist(); return true; }
            if(plus.contains(x,y)){openEventEditor(null);return true;} if(y<125*d && x>getWidth()-175*d){ showScheduleMenu(); return true; }
            float dateAreaBottom = monthExpanded ? 180*d + 78*d + monthRows()*dateRowHeight()*d + 15*d : 180*d + 125*d;
            if(touchDownY>120*d && touchDownY<dateAreaBottom){ float dx=x-touchDownX, dy=y-touchDownY; if(Math.abs(dx)>160*d && Math.abs(dx)>Math.abs(dy)*1.2f){ switchMonth(dx<0 ? 1 : -1); return true; } if(dy>35*d){ monthExpanded=true; invalidate(); return true; } if(-dy>35*d){ monthExpanded=false; invalidate(); return true; } if(selectDateAt(x, y, d)) { invalidate(); return true; } monthExpanded=!monthExpanded; invalidate(); return true; }
            contentTop=(monthExpanded?180*d+78*d+monthRows()*dateRowHeight()*d+20*d:180*d+153*d+20*d); float firstY=contentTop+38*d; List<Event> list=eventsForSelectedDay(); int index=(int)((y+scrollOffset-firstY+42*d)/(130*d)); if(index>=0&&index<list.size()){Event selected=list.get(index); if(x<205*d){selected.done=!selected.done;saveData();invalidate();}else openEventEditor(selected);}
            return true; }
        private void switchMonth(int direction) { selectedDate.add(Calendar.MONTH, direction); saveData(); monthAnimationDirection = direction > 0 ? 1 : -1; monthAnimationProgress = 0f; ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(260); animator.addUpdateListener(v -> { monthAnimationProgress = (Float) v.getAnimatedValue(); invalidate(); }); animator.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { monthAnimationProgress = 1f; monthAnimationDirection = 0; invalidate(); } }); animator.start(); }
        private boolean selectDateAt(float x, float y, float d) {
            float top = 180*d, col = getWidth()/7f;
            if (!monthExpanded) {
                if (y < top + 42*d || y > top + 118*d) return false;
                int index = Math.max(0, Math.min(6, (int)(x / col)));
                Calendar row = (Calendar) selectedDate.clone();
                int weekday = (row.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                row.add(Calendar.DAY_OF_MONTH, -weekday + index);
                selectedDate.set(Calendar.YEAR, row.get(Calendar.YEAR)); selectedDate.set(Calendar.MONTH, row.get(Calendar.MONTH)); selectedDate.set(Calendar.DAY_OF_MONTH, row.get(Calendar.DAY_OF_MONTH));
                return true;
            }
            float gridTop = top + 42*d;
            if (y < gridTop || y > gridTop + monthRows()*dateRowHeight()*d) return false;
            int column = Math.max(0, Math.min(6, (int)(x / col))); int rowIndex = (int)((y - gridTop) / (dateRowHeight()*d));
            int day = rowIndex*7 + column - firstDayOfWeek(selectedDate) + 1;
            if (day < 1 || day > selectedDate.getActualMaximum(Calendar.DAY_OF_MONTH)) return false;
            selectedDate.set(Calendar.DAY_OF_MONTH, day); saveData(); return true;
        }
    }

    private static final class Event { String title, detail, reminder; long time; boolean done; Event(String t,String d,long time,String r){title=t;detail=d;this.time=time;reminder=r;} }
    private static final class Article { String title, text; Article(String title, String text) { this.title = title; this.text = text; } }
    private static final class ChecklistGroup { String title; ArrayList<ChecklistItem> items = new ArrayList<>(); ChecklistGroup(String title) { this.title = title; } }
    private static final class ChecklistItem { String title; boolean done; ChecklistItem(String title, boolean done) { this.title = title; this.done = done; } }
    private static final class ChecklistTemplate { String title; ArrayList<String> items = new ArrayList<>(); ChecklistTemplate(String title) { this.title = title; } }
}
