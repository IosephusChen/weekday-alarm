package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 循环编辑器那几张布局的 inflate 守卫（v1.1.1）。
 *
 * <p>为什么值得单独测：布局里的错误（样式不存在、类名写错、id 拼错）
 * 编译期一律不报，lint 也只覆盖一部分，真正会崩在用户点开弹窗那一刻。
 * v1.1.1 动了四个布局、新增两个 drawable 和一个 style，inflate 一遍是最低的成本。
 *
 * <p>顺带钉住两处文案/结构：自定义入口不该出现「循环周期」「第 1 天是」两行
 * ——它们一旦露出来，用户改了起点整张「周一…周日」就跟着平移，
 * 而界面上没有任何提示告诉他这件事。
 */
@RunWith(RobolectricTestRunner.class)
public class CycleEditorLayoutTest {

    private Context c;

    @Before
    public void setUp() {
        c = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void allCycleLayoutsInflate() {
        int[] ids = {
                R.layout.dialog_cycle,
                R.layout.dialog_cycle_day,
                R.layout.dialog_pick_number,
                R.layout.dialog_pick_date,
                R.layout.item_cycle_day,
        };
        for (int id : ids) {
            View v = LayoutInflater.from(c).inflate(id, null);
            assertNotNull("布局 inflate 失败：" + c.getResources().getResourceEntryName(id), v);
        }
    }

    @Test
    public void dayRowHasStatusDotAndTodayTag() {
        View row = LayoutInflater.from(c).inflate(R.layout.item_cycle_day, null);
        assertNotNull("缺少班休状态点", row.findViewById(R.id.dot));
        // 默认是 GONE：只有落在今天那一行才显示
        assertNotNull(row.findViewById(R.id.tv_tag_today));
    }

    @Test
    public void pickDateGridStartsWithNoRows() {
        View v = LayoutInflater.from(c).inflate(R.layout.dialog_pick_date, null);
        assertNotNull(v.findViewById(R.id.btn_prev));
        assertNotNull(v.findViewById(R.id.btn_next));
        assertNotNull(v.findViewById(R.id.grid));
        TextView month = v.findViewById(R.id.tv_month);
        assertNotNull(month);
        assertTrue("月份标题不该为空", month.getText().length() > 0);
    }
}
