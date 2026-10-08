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
     *
     * 注意：AIDL 要求「要么全部方法都指定事务 id，要么全部都不指定」。
     * destroy() 用的是 Shizuku 约定的固定 id，所以 exec() 也必须显式编号。
     * 自定义方法从 1 开始即可，避开 16777114 这段保留区间。
     */
    String exec(String command) = 1;
}
