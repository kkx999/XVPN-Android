package com.xvpn.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, non-exported provider for the single APK validated by InAppUpdater. */
public final class UpdateFileProvider extends ContentProvider {
    private static final String FILE_NAME = "xvpn-update.apk";

    static Uri uri(android.content.Context context) {
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".fileprovider")
                .appendPath(FILE_NAME).build();
    }

    private File updateFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null || !FILE_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("Unknown update file");
        }
        File file = new File(new File(getContext().getCacheDir(), "updates"), FILE_NAME);
        if (!file.isFile()) throw new FileNotFoundException("Update APK is unavailable");
        return file;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(updateFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = updateFile(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(FILE_NAME);
                else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
                else row.add(null);
            }
            return cursor;
        } catch (FileNotFoundException ignored) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
