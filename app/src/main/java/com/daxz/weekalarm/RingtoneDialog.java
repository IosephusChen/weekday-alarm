package com.daxz.weekalarm;

import android.app.Activity;
import android.content.DialogInterface;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟铃声选择（v1.61.0 自绘，替掉系统铃声选择器）。
 *
 * <p>原来走 {@code RingtoneManager.ACTION_RINGTONE_PICKER}，弹出来的是系统自己的一套
 * 界面：不同 ROM 两副面孔，列表是「一行灰字」，和 App 里其它对话框完全不搭。
 * 用户截图点名「太简陋」，这里改成自绘卡片列表，并保留系统选择器最关键的那个体验——
 * <b>点一下就试听</b>。
 *
 * <p>与系统选择器的两点差别：
 * <ul>
 *   <li>多一步「确定」。试听和生效必须分开，不然手指划过三行就换了三次铃声；</li>
 *   <li>只列闹钟铃声（{@code TYPE_ALARM}），不给「静音」——响铃闹钟选静音没有意义，
 *       想安静有音量条。</li>
 * </ul>
 *
 * <p>枚举用 {@link RingtoneManager#getCursor()}：它已经把「默认」排在第一位，
 * 其余按系统铃声库的顺序。取不到标题的条目跳过，不显示一行空白。
 */
public final class RingtoneDialog {

    /** 选定了：{@code uri} 为 {@code null} 表示系统默认。 */
    public interface OnPicked {
        void onPicked(Uri uri);
    }

    private RingtoneDialog() {
    }

    public static void show(Activity activity, Uri current, OnPicked onPicked) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_pick, null);
        TextView tvTitle = view.findViewById(R.id.tv_pick_title);
        TextView tvSub = view.findViewById(R.id.tv_pick_sub);
        LinearLayout list = view.findViewById(R.id.dlg_pick_list);
        tvTitle.setText("选择闹钟铃声");
        tvSub.setText("点一下试听，选好按「确定」");

        // ---------- 枚举 ----------
        final List<Uri> uris = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Uri def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        uris.add(def);
        names.add("系统默认");
        RingtoneManager rm = new RingtoneManager(activity);
        rm.setType(RingtoneManager.TYPE_ALARM);
        try (Cursor c = rm.getCursor()) {
            while (c.moveToNext()) {
                Uri u = rm.getRingtoneUri(c.getPosition());
                if (u == null) continue;
                String t = c.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                if (t == null || t.isEmpty()) continue;
                if (def != null && u.equals(def)) {
                    // 库里那一条就是默认铃声：并到第一项，别重复列两遍
                    names.set(0, "系统默认（" + t + "）");
                    continue;
                }
                uris.add(u);
                names.add(t);
            }
        } catch (Exception ignore) {
            // 个别 ROM 的媒体库会抛异常：至少还有「系统默认」那一项可用
        }

        final int n = uris.size();
        int selected = -1;
        if (current != null) {
            for (int i = 0; i < n; i++) {
                if (current.equals(uris.get(i))) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected < 0 && current == null) selected = 0;   // 「系统默认」就是当前值
        final int[] picked = {selected};

        // ---------- 列表 ----------
        final View[] rows = new View[n];
        final Ringtone[] playing = {null};
        final int[] playingIdx = {-1};
        for (int i = 0; i < n; i++) {
            final int idx = i;
            View row = DialogKit.optionView(activity, names.get(i), null);
            row.setSelected(i == selected);
            DialogKit.showCheck(row, i == selected);
            row.setOnClickListener(v -> {
                picked[0] = idx;
                for (int k = 0; k < n; k++) {
                    DialogKit.showCheck(rows[k], k == idx);
                    rows[k].setSelected(k == idx);
                }
                preview(activity, uris.get(idx), playing, playingIdx, idx, rows, names);
            });
            rows[i] = row;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = Math.round(10 * activity.getResources()
                    .getDisplayMetrics().density);
            list.addView(row, lp);
        }

        RoundedDialog.Builder b = new RoundedDialog.Builder(activity);
        b.setView(view);
        b.setPositiveButton("确定", (d, w) -> {
            stop(playing[0]);
            playing[0] = null;
            if (onPicked == null) return;
            // 第 0 项是「系统默认」：给 null 而不是默认铃声的 Uri——
            // Prefs 里 null 就表示「跟随系统」，用户改系统默认铃声时我们跟着变
            onPicked.onPicked(picked[0] <= 0 ? null : uris.get(picked[0]));
        });
        b.setNegativeButton("取消", (d, w) -> {
            stop(playing[0]);
            playing[0] = null;
        });
        androidx.appcompat.app.AlertDialog dialog = b.create();
        // 点对话框外面、按返回键都要停——铃声在后台响起来是最吵的一种 bug
        dialog.setOnDismissListener(d -> {
            stop(playing[0]);
            playing[0] = null;
        });
        dialog.show();
    }

    /** 试听：先把上一个停掉，再播新的。 */
    private static void preview(Activity activity, Uri uri, Ringtone[] playing,
                                int[] playingIdx, int idx, View[] rows, List<String> names) {
        stop(playing[0]);
        playing[0] = null;
        if (playingIdx[0] >= 0 && playingIdx[0] < rows.length) {
            // 把上一个的「试听中…」擦掉
            DialogKit.setOptionSub(rows[playingIdx[0]], null);
        }
        try {
            Ringtone r = RingtoneManager.getRingtone(activity, uri);
            if (r == null) return;
            r.play();
            playing[0] = r;
            playingIdx[0] = idx;
            DialogKit.setOptionSub(rows[idx], "试听中…");
        } catch (Exception ignore) {
            // 媒体库里的条目可能已经没了：试听失败不该让对话框崩掉
        }
    }

    private static void stop(Ringtone r) {
        if (r == null) return;
        try {
            if (r.isPlaying()) r.stop();
        } catch (Exception ignore) {
        }
    }
}
