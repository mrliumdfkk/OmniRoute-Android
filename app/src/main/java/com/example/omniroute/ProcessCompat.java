package com.example.omniroute;

import android.os.Build;

/**
 * {@link Process#isAlive()} and {@link Process#destroyForcibly()} require API 26
 * while this app supports API 24, so keep the reflection-free fallbacks here.
 */
final class ProcessCompat {
    private ProcessCompat() {}

    static boolean isAlive(Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    static boolean isThreadAlive(Thread thread) {
        return thread != null && thread.isAlive();
    }

    /**
     * Waits up to {@code timeoutSeconds} for the process to exit.
     *
     * <p>{@link Process#waitFor(long, TimeUnit)} is API 26+ and this app supports
     * API 24, so poll instead of reflecting: the call happens once per start-up
     * probe, not in a hot loop.
     *
     * @return true when the process exited within the timeout
     */
    static boolean waitFor(Process process, int timeoutSeconds) {
        if (process == null) {
            return true;
        }
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return !isAlive(process);
            }
        }
        return !isAlive(process);
    }

    static void terminate(Process process) {
        if (process == null) {
            return;
        }

        process.destroy();
        if (!isAlive(process)) {
            return;
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        if (!isAlive(process)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            destroyForcibly(process);
        }

        closeStreams(process);
    }

    private static void destroyForcibly(Process process) {
        try {
            process.getClass().getMethod("destroyForcibly").invoke(process);
        } catch (Exception ignored) {
            process.destroy();
        }
    }

    private static void closeStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (Exception ignored) {
        }
    }
}
