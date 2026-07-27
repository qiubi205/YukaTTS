package com.yuukatts;

import android.os.Debug;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃日志，写入文件而非依赖 adb logcat。
 * 在 Application.onCreate 或 MainActivity.onCreate 最开头调用 init()。
 */
public class CrashLogger {

    private static File logFile;

    public static void init(File file) {
        logFile = file;
        // 确保目录存在
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        write("=== YuukaTTS v1.1.0 (Lite) ===\n");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println();
                pw.println("========== CRASH ==========");
                pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
                pw.println("PID: " + Process.myPid());
                pw.println("Thread: " + thread.getName() + " (id=" + thread.getId() + ")");
                pw.println("ABI: " + android.os.Build.CPU_ABI + " / " + android.os.Build.CPU_ABI2);
                pw.println("VM Max:  " + (Runtime.getRuntime().maxMemory() / 1048576) + " MB");
                pw.println("VM Tot:  " + (Runtime.getRuntime().totalMemory() / 1048576) + " MB");
                pw.println("VM Free: " + (Runtime.getRuntime().freeMemory() / 1048576) + " MB");
                pw.println("Nat Alloc: " + (Debug.getNativeHeapAllocatedSize() / 1048576) + " MB");
                pw.println("Nat Free:  " + (Debug.getNativeHeapFreeSize() / 1048576) + " MB");
                pw.println("Nat Size:  " + (Debug.getNativeHeapSize() / 1048576) + " MB");
                pw.println("Pss:       " + (Debug.getPss() / 1024) + " MB");
                pw.println();
                throwable.printStackTrace(pw);

                Throwable cause = throwable.getCause();
                int level = 0;
                while (cause != null && level < 10) {
                    pw.println("Caused by (" + level + "):");
                    cause.printStackTrace(pw);
                    cause = cause.getCause();
                    level++;
                }
                pw.flush();

                String report = sw.toString();
                // 同时输出到 logcat 和文件
                Log.e("YuukaTTS", report);
                write(report);
            } catch (Exception e) {
                Log.e("YuukaTTS", "CrashLogger 自身崩溃", e);
            }
            // 保持原有的崩溃行为
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }

    public static synchronized void write(String msg) {
        try {
            if (logFile == null) return;
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(msg.getBytes("UTF-8"));
            if (!msg.endsWith("\n")) fos.write('\n');
            fos.close();
        } catch (Exception e) {
            Log.e("YuukaTTS", "写入日志失败", e);
        }
    }

    public static void write(String tag, String msg) {
        write("[" + tag + "] " + msg + "\n");
    }
}
