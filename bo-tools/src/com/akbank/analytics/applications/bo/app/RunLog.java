package com.akbank.analytics.applications.bo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Konsola ve dosyaya aynı anda yazan basit log. Tam stack trace sadece dosyaya yazılır. */
public final class RunLog implements AutoCloseable {

    private final PrintWriter file;
    private final Path path;

    private RunLog(PrintWriter file, Path path) {
        this.file = file;
        this.path = path;
    }

    public static RunLog open(Path dir) throws IOException {
        return open(dir, "run");
    }

    public static RunLog open(Path dir, String prefix) throws IOException {
        Files.createDirectories(dir);
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".log";
        Path p = dir.resolve(name);
        return new RunLog(new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8), true), p);
    }

    public Path getPath() { return path; }

    public void info(String msg)  { write("INFO ", msg); }
    public void warn(String msg)  { write("WARN ", msg); }
    public void error(String msg) { write("ERROR", msg); }

    public void error(String msg, Throwable t) {
        write("ERROR", msg + " : " + describe(t));
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        synchronized (this) {
            file.println(sw);
        }
    }

    public static String describe(Throwable t) {
        String s = t.toString();
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == t ? s : s + "  <-  " + root;
    }

    private synchronized void write(String level, String msg) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + level + " " + msg;
        System.out.println(line);
        file.println(line);
    }

    @Override
    public void close() {
        file.close();
    }
}
