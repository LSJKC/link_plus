package io.github.shaonialife.buicklinkplus.shizuku;

import io.github.shaonialife.buicklinkplus.shizuku.ICarInput;
import io.github.shaonialife.buicklinkplus.shizuku.IEmbeddedProcess;

// 内置 shell 服务只允许本安装实例的应用 UID 调用
interface IEmbeddedShell {
    int protocolVersion();
    int uid();
    int pid();
    ICarInput input();
    IEmbeddedProcess execute(String command);
    void shutdown();
}
