// 内置无线 ADB 激活已按需求整体移除；此 AIDL 仅为遗留调用点的降级占位，
// 没有实现（compat.CarInputClient.service 恒为空），不执行任何 ADB/shell/root 操作。
package io.github.shaonialife.buicklinkplus.compat;

import android.view.Surface;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

// 遗留接口占位：真实输入服务已随内置 ADB 激活一并删除，仅保留方法签名供编译兼容。
interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // 单指拖拽共用一次 DOWN，MOVE 使用真实时间戳，保留滚动惯性
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // 受信应用屏允许第三方任务进入，避免跳回手机主屏
    // trusted=false 用于私有侧栏；独立焦点和锁屏仍按系统能力设置受信标记
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // 创建者调整屏幕尺寸，系统通知应用重新布局，任务留在原屏
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    boolean ping();
    String diag();

    // 清理旧服务进程时，用 PID 排除当前进程
    int pid();

    // 设置本服务持有虚拟屏的方向策略，返回窗口服务是否接受调用
    boolean setDisplayIgnoreOrientation(int displayId, boolean enabled);

    // 读取本服务持有屏幕的实际逻辑宽高与方向，失败返回空数组
    int[] displayGeometry(int displayId);
    // 新接口只追加，旧事务编号保持不变
    int capabilities();
    ParcelFileDescriptor startAudioCapture(long session, int sampleRate, IBinder client);
    void stopAudioCapture(long session);
    boolean moveTaskToDisplay(int taskId, int sourceDisplayId, int displayId);
}
