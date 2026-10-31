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
                    // v0.87.1：读文件挪到后台线程。本地文件只有几 KB，主线程也无所谓；
                    // 但用户从网盘 / 云盘选文件时，SAF 会先把文件下载下来——下载期间主线程
                    // 卡住就是 ANR，而它偏偏发生在刚点完「导入」的那一刻，用户只会觉得
                    // 「这 App 卡死了」。上限 512KB 的读法不变，只是换了线程。
                    new Thread(() -> {
                        String json = readText(activity, uri);
                        ConfigBackup.Result check =
                                json == null ? null : ConfigBackup.validate(json);
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            if (json == null) {
                                Toast.makeText(activity, "读取文件失败，换一个文件试试",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
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
                    }, "backup-import-read").start();
                });
    }

    /**
     * 导入确认框的正文（v0.76.5 结构化，v1.1.0 分块）。
     *
     * <p>原来是一大段文字：覆盖范围、引导页附加句、文件摘要、警告全拼在一个字符串里，
     * 四五行走完用户还没找到「这个文件是什么时候导的」。v0.76.5 改成三段：
     * 一句说明（会覆盖什么）→ 摘要信息行 → 一句警告。
     *
     * <p>v1.1.0 再分一次：摘要单独收进一块淡底卡、行间加发丝线，警告从「一行红字」
     * 变成一块淡红条。原来三段的字号、颜色、左边距完全一样，四行看着一样重——
     * 而这四行里，「覆盖后无法撤销」是唯一一件不可逆的事。
     */
    static View confirmBody(Context activity, String json, boolean fromGuide) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView intro = new TextView(activity);
        intro.setText(activity.getString(R.string.import_confirm_body)
                + (fromGuide ? "\n" + activity.getString(R.string.import_confirm_guide_extra) : ""));
        intro.setTextColor(ContextCompat.getColor(activity, R.color.body));
        intro.setTextSize(13);
        intro.setLineSpacing(0, 1.5f);
        body.addView(intro);

        ConfigBackup.Summary sum = ConfigBackup.summarize(json);
        if (sum != null) {
            java.util.List<View> rows = new java.util.ArrayList<>();
            rows.add(RowKit.kv(activity,
                    activity.getString(R.string.summary_exported_at), sum.exportedAt));
            rows.add(RowKit.kv(activity,
                    activity.getString(R.string.summary_alarms), sum.alarmCount + " 个"));
            if (sum.vacationDays > 0) {
                rows.add(RowKit.kv(activity, activity.getString(R.string.summary_vacation),
                        sum.vacationDays + " 天"));
            }
            if (sum.overtimeDays > 0) {
                rows.add(RowKit.kv(activity, activity.getString(R.string.summary_overtime),
                        sum.overtimeDays + " 天"));
            }
            // v1.1.0：RowKit.stack 现在自带淡底卡和发丝线，和「已导出」用同一块皮肤
            body.addView(RowKit.stack(activity, rows.toArray(new View[0])),
                    RowKit.rowLp(activity, 14));
        }

        TextView warn = new TextView(activity);
        warn.setText(R.string.import_confirm_warning);
        warn.setTextColor(ContextCompat.getColor(activity, R.color.danger));
        warn.setTextSize(13);
        warn.setLineSpacing(0, 1.4f);
        warn.setBackgroundResource(R.drawable.bg_danger_soft);
        int wp = dp(activity, 12);
        warn.setPadding(wp, dp(activity, 10), wp, dp(activity, 10));
        warn.setLayoutParams(RowKit.rowLp(activity, 12));
        body.addView(warn);
        return body;
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
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
