package com.crbyob.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Saves the log archive (LogExport.writeZip) to Downloads, or shares it from the dashboard's Export log. */
final class LogFiles {
    private LogFiles() {}

    /** Authority of the FileProvider in AndroidManifest.xml (res/xml/log_paths.xml). */
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".logs";

    /** Where the archive went: its file name and where to find it, for the report's Log field. */
    static final class Saved {
        final String name, where;

        Saved(String name, String where) {
            this.name = name;
            this.where = where;
        }
    }

    static void write(Context c, OutputStream os) throws IOException {
        Lines l = lines(c);
        LogExport.writeZip(os, c.getFilesDir(), l.pinned, l.recent, AppVersion.bugReportLine());
    }

    /** The log for a report: recent lines and the scanner setup lines. */
    static final class Lines {
        final List<String> recent, pinned;

        Lines(List<String> recent, List<String> pinned) {
            this.recent = recent;
            this.pinned = pinned;
        }
    }

    /**
     * The recent lines from the end of bridge.jsonl (they survive restarts and crashes; BLog's in-memory ring when
     * there's no file), and the setup lines from those plus this run's.
     */
    static Lines lines(Context c) {
        List<String> recent = LogExport.recentFromFiles(c.getFilesDir(), LogExport.TAIL_BYTES);
        if (recent.isEmpty()) recent = BLog.recent(Integer.MAX_VALUE);
        return new Lines(recent, LogExport.mergePinned(recent, BLog.pinned()));
    }

    /**
     * Writes the archive to Downloads: through MediaStore on Android 10+ (no permission), else to the app's own
     * Download folder on shared storage. Slow for a big log (it redacts every line): call it off the UI thread.
     */
    static Saved saveToDownloads(Context c) throws IOException {
        String name = LogExport.fileName(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("MediaStore refused " + name);
            try (OutputStream os = cr.openOutputStream(u)) {
                if (os == null) throw new IOException("no stream for " + u);
                write(c, os);
            } catch (IOException | RuntimeException e) {
                cr.delete(u, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
            try (Cursor q = cr.query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (q != null && q.moveToFirst() && q.getString(0) != null) name = q.getString(0);   // "(1)" when taken
            }
            BLog.log("log_exported", "to", "downloads", "name", name);
            return new Saved(name, "Downloads");
        }
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IOException("no shared storage");
        File f = new File(dir, name);
        try (OutputStream os = new FileOutputStream(f)) {
            write(c, os);
        }
        BLog.log("log_exported", "to", "app_downloads", "name", name);
        return new Saved(name, "Android/data/" + c.getPackageName() + "/files/Download");
    }

    /** Export log: writes the archive to the cache and opens the share sheet with it. */
    static void share(Activity a) {
        Toast.makeText(a, "Preparing the log…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dir = new File(a.getCacheDir(), "exports");
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) o.delete();      // one export at a time
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
                File f = new File(dir, LogExport.fileName(System.currentTimeMillis()));
                try (OutputStream os = new FileOutputStream(f)) {
                    write(a, os);
                }
                BLog.log("log_exported", "to", "share", "bytes", f.length());
                Uri u = uriFor(a, f);
                a.runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, u)
                            .putExtra(Intent.EXTRA_SUBJECT, "CR-BYOB log")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri(f.getName(), u));
                    a.startActivity(Intent.createChooser(send, "Export log"));
                });
            } catch (Exception e) {
                BLog.log("log_export_error", "err", String.valueOf(e));
                a.runOnUiThread(() -> Toast.makeText(a, "Couldn't export the log: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "log-export").start();
    }

    /** A content:// URI for a file under cache/exports, served by LogProvider. */
    private static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }
}
