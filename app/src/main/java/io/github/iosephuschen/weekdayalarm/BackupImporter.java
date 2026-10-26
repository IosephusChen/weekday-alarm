package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 导入旧备份的完整流程（v0.57.0）：选文件 → 读文本 → 校验格式 → 二次确认 → 落盘 → 回调。
 *
 * <p>从 {@code SettingsActivity} 里抽出来，因为引导页也要用。原来这段（含 {@code readText}、
 * {@code doImport} 和三个对话框）写死在设置页里，复制一份到 612 行的引导页是最差的选项。
 *
 * <p>两个宿主只有两处不同，都通过参数处理：
 * <ul>
 *   <li>确认文案——引导页要额外提醒「会覆盖你刚选的班表」；</li>
 *   <li>导入成功之后做什么——设置页刷新本页并提示，引导页跳到权限页。</li>
 * </ul>
 * 失败路径（读不到文件、格式不支持、落盘失败）两处完全一致，所以留在这里。
 */
public final class BackupImporter {

    /** 导入成功后的回调；失败不回调（对话框已经在内部弹过）。 */
    public interface OnImported {
        void onImported(ConfigBackup.Result result);
    }

    private BackupImporter() {
    }

    /**
     * 注册导入用的文件选择器。**必须在 Activity 进入 STARTED 之前调用**（在 onCreate 里即可），
     * 这是 ActivityResult API 的硬性要求。
     *
     * @param fromGuide 引导页流程：确认文案多一句「含刚选的班表」，成功后的动作由回调决定
     */
    public static ActivityResultLauncher<String[]> register(AppCompatActivity activity,
                                                            OnImported callback,
                                                            boolean fromGuide) {
        return activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;      // 用户取消
                    String json = readText(activity, uri);
                    if (json == null) {
                        Toast.makeText(activity, "读取文件失败，换一个文件试试",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    ConfigBackup.Result check = ConfigBackup.validate(json);
                    if (!check.ok) {
                        DialogKit.showMessage(activity, "这个文件不能导入", check.message);
                        return;
                    }
                    DialogKit.showMessage(activity,
                            activity.getString(R.string.dialog_import_confirm_title),
                            confirmBody(activity, json, fromGuide),
                            activity.getString(R.string.import_confirm_positive),
                            (d, w) -> importNow(activity, json, callback),
                            activity.getString(R.string.dialog_cancel), null);
                });
    }

    /**
     * 导入确认框的正文（v0.76.5 结构化）。
     *
     * <p>原来是一大段文字：覆盖范围、引导页附加句、文件摘要、警告全拼在一个字符串里，
     * 四五行走完用户还没找到「这个文件是什么时候导的」。改成三段：
     * 一句说明（会覆盖什么）→ 摘要信息行（导出时间 / 闹钟 / 请假加班标记）→
     * 一句警告（danger 色）。文件解析不出摘要时只留说明和警告，不显示空行。
     */
    static View confirmBody(Context activity, String json, boolean fromGuide) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView intro = new TextView(activity);
        intro.setText(activity.getString(R.string.import_confirm_body)
                + (fromGuide ? "\n" + activity.getString(R.string.import_confirm_guide_extra) : ""));
        intro.setTextColor(ContextCompat.getColor(activity, R.color.sub));
        intro.setTextSize(12);
        intro.setLineSpacing(0, 1.2f);
        body.addView(intro);

        ConfigBackup.Summary sum = ConfigBackup.summarize(json);
        if (sum != null) {
            body.addView(RowKit.kv(activity,
                            activity.getString(R.string.summary_exported_at), sum.exportedAt),
                    RowKit.rowLp(activity, 12));
            body.addView(RowKit.kv(activity,
                            activity.getString(R.string.summary_alarms), sum.alarmCount + " 个"),
                    RowKit.rowLp(activity, 7));
            if (sum.vacationDays > 0) {
                body.addView(RowKit.kv(activity,
                                activity.getString(R.string.summary_vacation),
                                sum.vacationDays + " 天"),
                        RowKit.rowLp(activity, 7));
            }
            if (sum.overtimeDays > 0) {
                body.addView(RowKit.kv(activity,
                                activity.getString(R.string.summary_overtime),
                                sum.overtimeDays + " 天"),
                        RowKit.rowLp(activity, 7));
            }
        }

        TextView warn = new TextView(activity);
        warn.setText(R.string.import_confirm_warning);
        warn.setTextColor(ContextCompat.getColor(activity, R.color.danger));
        warn.setTextSize(12);
        warn.setLineSpacing(0, 1.2f);
        warn.setLayoutParams(RowKit.rowLp(activity, 7));
        body.addView(warn);
        return body;
    }

    /**
     * 拉起文件选择。不同文件管理器给 .json 报的 MIME 五花八门（application/json、text/plain、
     * octet-stream 都有），不限定类型反而更好找。
     *
     * @return false 表示这台机器上没有可用的文件管理器，调用方负责提示
     */
    public static boolean pick(ActivityResultLauncher<String[]> launcher) {
        try {
            launcher.launch(new String[]{"*/*"});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void importNow(AppCompatActivity activity, String json, OnImported callback) {
        ConfigBackup.Result r = ConfigBackup.importJson(activity, json);
        if (!r.ok) {
            DialogKit.showMessage(activity, "导入失败",
                    r.message + "\n\n当前设置没有被改动。");
            return;
        }
        ConfigBackup.applyAfterImport(activity);
        if (callback != null) callback.onImported(r);
    }

    /** 读所选文件（上限 512KB；配置只有几 KB，防止误选大文件把内存打爆）。 */
    private static String readText(AppCompatActivity activity, Uri uri) {
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) return null;
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
