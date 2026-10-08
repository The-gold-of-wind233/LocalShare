package com.localshare.app;

/**
 * 运行在 shell（uid 2000）/ root（uid 0）进程中的特权服务。
 *
 * 说明：destroy() 的事务码是 Shizuku 约定的固定值 16777114，
 * 用于在 UserService 版本变化时让旧进程退出。
 */
interface IShellService {

    /** Shizuku 约定的销毁方法 */
    void destroy() = 16777114;

    /**
     * 执行一条 shell 命令。
     * 返回格式为 "exitCode\n输出内容"，便于调用方判断与排错。
     */
    String exec(String command);
}
