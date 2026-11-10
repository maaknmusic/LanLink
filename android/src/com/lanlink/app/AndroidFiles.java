package com.lanlink.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lanlink.core.FileReceiver;

/** Saves received files to Downloads/LanLink, and reads names and sizes of files the user picks. */
final class AndroidFiles implements FileReceiver.Sink {
    private final Context context;
    private final Map<OutputStream, Uri> pending = new ConcurrentHashMap<>();
    private final Map<OutputStream, File> legacy = new ConcurrentHashMap<>();

    AndroidFiles(Context context) {
        this.context = context.getApplicationContext();
    }

    static String folderLabel() {
        return Build.VERSION.SDK_INT >= 29 ? "Downloads/LanLink" : "Android/data/com.lanlink.app/files/Download/LanLink";
    }

    @Override
    public OutputStream open(String name, long size) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanLink");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver r = context.getContentResolver();
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Cannot create " + name);
            OutputStream raw = r.openOutputStream(uri);
            if (raw == null) throw new IOException("Cannot write " + name);
            OutputStream out = new BufferedOutputStream(raw, 256 * 1024);
            pending.put(out, uri);
            return out;
        }
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LanLink");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File f = new File(dir, name);
        for (int i = 1; f.exists(); i++) f = new File(dir, i + "_" + name);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 256 * 1024);
        legacy.put(out, f);
        return out;
    }

    @Override
    public void finished(OutputStream stream, String name, long size, boolean ok) {
        Uri uri = pending.remove(stream);
        if (uri != null) {
            ContentResolver r = context.getContentResolver();
            if (ok) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                r.update(uri, v, null, null);
            } else {
                r.delete(uri, null, null);
            }
        }
        File f = legacy.remove(stream);
        if (f != null && !ok) f.delete();
        Hub.toast(context, ok ? "Received " + name + " (" + folderLabel() + ")" : "Transfer of " + name + " failed");
    }

    /** {display name, size or -1} for a content Uri. */
    static Object[] describe(Context c, Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int n = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = cur.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && cur.getString(n) != null) name = cur.getString(n);
                if (s >= 0 && !cur.isNull(s)) size = cur.getLong(s);
            }
        } catch (RuntimeException ignored) {
        }
        return new Object[] {name, size};
    }
}
