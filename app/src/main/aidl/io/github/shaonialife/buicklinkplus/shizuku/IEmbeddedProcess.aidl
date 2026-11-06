package io.github.shaonialife.buicklinkplus.shizuku;

import android.os.ParcelFileDescriptor;

// 输出通过管道传递，避免大型 dumpsys 结果超过 Binder 事务上限
interface IEmbeddedProcess {
    ParcelFileDescriptor stdout();
    ParcelFileDescriptor stderr();
    int waitFor(long timeoutMs);
    void destroy();
}
