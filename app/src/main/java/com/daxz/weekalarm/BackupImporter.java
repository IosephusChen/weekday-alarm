package com.daxz.weekalarm;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 导入旧备份的完整流程（v1.47.0）：选文件 → 读文本 → 校验格式 → 二次确认 → 落盘 → 回调。
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
                        new RoundedDialog.Builder(activity)
                                .setTitle("这个文件不能导入")
                                .setMessage(check.message)
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    String summary = ConfigBackup.describe(json);
                    new RoundedDialog.Builder(activity)
                            .setTitle("导入配置？")
                            .setMessage("会用这份配置覆盖当前的全部设置：闹钟、排班基准、"
                                    + "请假/加班标记、音量铃声、睡前提醒。"
                                    + (fromGuide ? "\n\n包括你刚才选的班表。" : "")
                                    + "\n\n"
                                    + (summary.isEmpty() ? "" : summary + "\n\n")
                                    + "覆盖后无法撤销，当前设置不会自动备份。")
                            .setPositiveButton("覆盖导入", (d, w) ->
                                    importNow(activity, json, callback))
                            .setNegativeButton("取消", null)
                            .show();
                });
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
            new RoundedDialog.Builder(activity)
                    .setTitle("导入失败")
                    .setMessage(r.message + "\n\n当前设置没有被改动。")
                    .setPositiveButton("知道了", null)
                    .show();
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
