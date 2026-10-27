package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
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
 * 闹钟铃声选择（v0.74.0 自绘，替掉系统铃声选择器）。
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
 *
 * <p><b>v0.88.0 起多一个「跟随设置」项</b>：单个闹钟可以单独选铃声，也可以不选、
 * 跟着设置里的默认。两种场合共用这个对话框，靠 {@code followLabel} 区分——
 * 传 null 就是设置页那套（只有「系统默认」+ 铃声列表），传了文案就在最前面加一项。
 */
public final class RingtoneDialog {

    /** 选定了：{@code uri} 为 {@code null} 表示系统默认。 */
    public interface OnPicked {
        void onPicked(Uri uri);
    }

    /**
     * 带「跟随设置」的选定回调（v0.88.0）。
     *
     * <p>为什么不能只靠 {@code uri == null}：null 已经表示「系统默认铃声」了，
     * 而「跟随设置」在设置里选的又不是系统默认时，两者必须分开——
     * 否则用户给这条闹钟选了「系统默认」，结果却跟着设置走了。
     */
    public interface OnPickedWithFollow {
        /**
         * @param uri           选中的铃声；{@code null} = 系统默认
         * @param followSetting true = 这条闹钟不单独设铃声，跟随设置
         */
        void onPicked(Uri uri, boolean followSetting);
    }

    /** 列表里的一项。{@code follow} 与 {@code uri == null} 不能合并，见上面接口注释。 */
    private static final class Item {
        final String name;
        final Uri uri;          // 要保存的铃声；null = 系统默认
        final Uri sound;        // 试听用的真实 Uri；null = 不试听
        final boolean follow;

        Item(String name, Uri uri, Uri sound, boolean follow) {
            this.name = name;
            this.uri = uri;
            this.sound = sound;
            this.follow = follow;
        }
    }

    private RingtoneDialog() {
    }

    /** 设置页用：只有「系统默认」+ 铃声列表。 */
    public static void show(Activity activity, Uri current, OnPicked onPicked) {
        show(activity, current, null, onPicked == null ? null
                : (uri, follow) -> onPicked.onPicked(uri));
    }

    /**
     * 单个闹钟用：{@code followLabel} 非空时，第一项是「跟随设置（xxx）」。
     *
     * @param current     这条闹钟当前单独设的铃声；null 表示跟随设置
     * @param followLabel 第一项的文案；null 表示不提供「跟随」这一项
     */
    public static void show(Activity activity, Uri current, String followLabel,
                            OnPickedWithFollow onPicked) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_pick, null);
        TextView tvTitle = view.findViewById(R.id.tv_pick_title);
        TextView tvSub = view.findViewById(R.id.tv_pick_sub);
        LinearLayout list = view.findViewById(R.id.dlg_pick_list);
        tvTitle.setText("选择闹钟铃声");
        tvSub.setText("点一下试听，选好按「确定」");

        // ---------- 枚举 ----------
        final List<Item> items = new ArrayList<>();
        if (followLabel != null) {
            // 跟随设置：没有具体铃声可试听，点它只是把这条闹钟交回设置管
            items.add(new Item(followLabel, null, null, true));
        }
        Uri def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        final int defaultIndex = items.size();
        items.add(new Item("系统默认", null, def, false));

        RingtoneManager rm = new RingtoneManager(activity);
        rm.setType(RingtoneManager.TYPE_ALARM);
        try (Cursor c = rm.getCursor()) {
            while (c.moveToNext()) {
                Uri u = rm.getRingtoneUri(c.getPosition());
                if (u == null) continue;
                String t = c.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                if (t == null || t.isEmpty()) continue;
                if (def != null && u.equals(def)) {
                    // 库里那一条就是默认铃声：并到「系统默认」项，别重复列两遍
                    Item d = items.get(defaultIndex);
                    items.set(defaultIndex,
                            new Item("系统默认（" + t + "）", null, d.sound, false));
                    continue;
                }
                items.add(new Item(t, u, u, false));
            }
        } catch (Exception ignore) {
            // 个别 ROM 的媒体库会抛异常：至少还有「系统默认」那一项可用
        }

        final int n = items.size();
        int selected = -1;
        if (current == null) {
            // 没有单独设铃声：优先落在「跟随设置」，设置页则落在「系统默认」
            for (int i = 0; i < n; i++) {
                if (items.get(i).follow || items.get(i).uri == null) {
                    selected = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < n; i++) {
                Uri u = items.get(i).uri;
                if (u != null && current.equals(u)) {
                    selected = i;
                    break;
                }
            }
            // 找不到（换了设备 / 铃声被删）：一项都不选，让用户看到「当前铃声不在库里」
        }
        final int[] picked = {selected};

        // ---------- 列表 ----------
        final View[] rows = new View[n];
        final Ringtone[] playing = {null};
        final int[] playingIdx = {-1};
        for (int i = 0; i < n; i++) {
            final int idx = i;
            View row = DialogKit.optionView(activity, items.get(i).name, null);
            row.setSelected(i == selected);
            DialogKit.showCheck(row, i == selected);
            row.setOnClickListener(v -> {
                picked[0] = idx;
                for (int k = 0; k < n; k++) {
                    DialogKit.showCheck(rows[k], k == idx);
                    rows[k].setSelected(k == idx);
                }
                preview(activity, items.get(idx).sound, playing, playingIdx, idx, rows);
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
            // 当前铃声不在库里、用户又没重新选：不改动，别把人家原来的设置洗掉
            if (picked[0] < 0 || picked[0] >= n) return;
            Item it = items.get(picked[0]);
            onPicked.onPicked(it.uri, it.follow);
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
                                int[] playingIdx, int idx, View[] rows) {
        stop(playing[0]);
        playing[0] = null;
        if (playingIdx[0] >= 0 && playingIdx[0] < rows.length) {
            // 把上一个的「试听中…」擦掉
            DialogKit.setOptionSub(rows[playingIdx[0]], null);
        }
        if (uri == null) return;    // 「跟随设置」没有自己的声音可试听
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
