package org.pkgj.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Isolated SAF provider for synthetic fixtures. Exposes no user directories. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    private File root() { File dir = new File(getContext().getCacheDir(), "download-fixtures"); dir.mkdirs(); return dir; }
    private File file(String id) {
        if ("root".equals(id)) return root();
        File file = new File(root(), id);
        try { if (!file.getCanonicalPath().startsWith(root().getCanonicalPath() + File.separator)) throw new IllegalArgumentException(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        Map<String,Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "root"); values.put(Root.COLUMN_DOCUMENT_ID, "root"); values.put(Root.COLUMN_TITLE, "Fixtures"); values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        add(cursor, values); return cursor;
    }
    private void add(MatrixCursor cursor, Map<String,Object> values) {
        Object[] row = new Object[cursor.getColumnCount()];
        for (int i = 0; i < row.length; i++) row[i] = values.get(cursor.getColumnNames()[i]);
        cursor.addRow(row);
    }
    private Cursor documents(String[] projection, File[] files) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE});
        if (files != null) for (File f : files) {
            if (!f.exists()) continue;
            Map<String,Object> values = new HashMap<>();
            values.put(Document.COLUMN_DOCUMENT_ID, f.equals(root()) ? "root" : root().toPath().relativize(f.toPath()).toString());
            values.put(Document.COLUMN_DISPLAY_NAME, f.getName()); values.put(Document.COLUMN_SIZE, f.length());
            values.put(Document.COLUMN_MIME_TYPE, f.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
            values.put(Document.COLUMN_FLAGS, f.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE);
            add(cursor, values);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) { return documents(projection, new File[]{file(id)}); }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) { return documents(projection, file(id).listFiles()); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!file(parent).isDirectory() || name.contains("/") || name.equals("..")) throw new FileNotFoundException();
        String id = "root".equals(parent) ? name : parent + "/" + name;
        File f = file(id);
        try { if (!(Document.MIME_TYPE_DIR.equals(mime) ? f.mkdir() : f.createNewFile())) throw new IOException(); }
        catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return id;
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if (new File(root(), "fail-rename").delete()) throw new FileNotFoundException("Fixture rename failure");
        if (name.contains("/") || name.equals("..")) throw new FileNotFoundException();
        File target = new File(file(id).getParentFile(), name);
        if (target.exists() || !file(id).renameTo(target)) throw new FileNotFoundException("Rename failed");
        return root().toPath().relativize(target.toPath()).toString();
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (!file(id).delete()) throw new FileNotFoundException(); }
    @Override public boolean isChildDocument(String parent, String id) { return !parent.equals(id) && file(id).exists() && file(id).toPath().startsWith(file(parent).toPath()); }
    private void removeTree(File f) { File[] children = f.listFiles(); if (children != null) for (File child : children) removeTree(child); f.delete(); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("reset-fixtures".equals(method)) { File[] files = root().listFiles(); if (files != null) for (File f : files) removeTree(f); return new Bundle(); }
        if ("fail-next-rename".equals(method)) { try { new File(root(), "fail-rename").createNewFile(); } catch (IOException e) { throw new IllegalStateException(e); } return new Bundle(); }
        return super.call(method, arg, extras);
    }
}
