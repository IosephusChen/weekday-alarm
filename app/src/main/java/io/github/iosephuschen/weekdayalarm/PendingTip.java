package io.github.iosephuschen.weekdayalarm;

/**
 * 引导收尾那句话的暂存处（A5，2026-11-09）。
 *
 * <p>为什么不在引导页直接说：Toast 是 {@code TYPE_TOAST} 系统窗口，不跟 Activity 走。
 * {@code GuideActivity.complete()} 弹完立刻 {@code finish()}，首页 {@code onResume}
 * 紧接着请求通知权限、弹出系统框——于是提示压在框上。六段录屏逐帧实测：提示 1.48s 出现、
 * 4.29s 仍在；权限框 1.65s 渐入、3.46s 渐出，两者共存约 1.5 秒，框消失后提示还留着。
 *
 * <p>所以首次引导那句改成：引导页只把「要说什么」存进来，首页等权限框有结果再说
 * （{@code MainActivity.onRequestPermissionsResult} → {@code showPendingTip()}）。
 * 文案给两句而不是一句：用户点「拒绝」时不能再承诺「会响」，这是
 * {@code GuideActivity.complete()} 里 2026-11-05 那段注释要防的假话。
 *
 * <p>放静态字段的理由：首页这时**已经存在**，走的是 {@code onResume} 复用，不重建，
 * Intent extra 传不过去。不放 {@code Prefs}：它只是一次性 UI 反馈，落盘还得额外管
 * 「什么时候清」，而进程一死就该忘掉。
 *
 * <p>一次性：{@link #take()} 取走即清空，取第二次拿到 null——回调与 {@code onResume}
 * 都可能来问，重复显示就成了两句一样的提示叠在一起。
 */
final class PendingTip {

    private static PendingTip pending;

    /** 通知通的时候说这句。 */
    final String ok;
    /** 通知不通的时候说这句。 */
    final String warn;

    private PendingTip(String ok, String warn) {
        this.ok = ok;
        this.warn = warn;
    }

    static void put(String ok, String warn) {
        pending = new PendingTip(ok, warn);
    }

    static boolean has() {
        return pending != null;
    }

    /** 取走并清空。 */
    static PendingTip take() {
        PendingTip p = pending;
        pending = null;
        return p;
    }

    /** 只给测试用：静态字段会跨用例残留，下一条测试就会在没引导的情况下看见这句话。 */
    static void clear() {
        pending = null;
    }
}
