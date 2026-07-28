package dev.sixbridge.manager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class TestConversionDocumentProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.sixbridge.manager.test.documents";
    private static File root;

    @Override
    public boolean onCreate() {
        root = new File(getContext().getCacheDir(), "conversion-document-provider");
        return root.mkdirs() || root.isDirectory();
    }

    public static Uri uri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        try {
            if ("seed".equals(method)) {
                java.nio.file.Files.write(file(arg).toPath(), extras.getByteArray("bytes"));
            } else if ("read".equals(method)) {
                result.putByteArray("bytes", java.nio.file.Files.readAllBytes(file(arg).toPath()));
            } else if ("exists".equals(method)) {
                result.putBoolean("exists", file(arg).isFile());
            }
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("Could not operate test document", error);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if ("null".equals(name)) {
            return null;
        }
        if ("mid-write".equals(name)) {
            ParcelFileDescriptor[] pipe = createReliablePipe();
            closeQuietly(pipe[0]);
            return pipe[1];
        }
        if ("close-error".equals(name)) {
            ParcelFileDescriptor[] pipe = createReliablePipe();
            new Thread(
                    () -> {
                        try {
                            Thread.sleep(20);
                            pipe[0].closeWithError("provider close error");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        } catch (IOException ignored) {
                            // The client already observed a write failure.
                        }
                    },
                    "conversion-document-close-error")
                    .start();
            return pipe[1];
        }
        return ParcelFileDescriptor.open(file(name), ParcelFileDescriptor.parseMode(mode));
    }

    private static ParcelFileDescriptor[] createReliablePipe() {
        try {
            return ParcelFileDescriptor.createReliablePipe();
        } catch (IOException error) {
            throw new IllegalStateException("Could not create reliable test pipe", error);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // The client will observe a write failure either way.
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String name = name(uri);
        if ("delete-fail".equals(name) || "cancel-delete-fail".equals(name)) {
            return 0;
        }
        if ("mid-write".equals(name) || "close-error".equals(name)) {
            return 1;
        }
        return file(name).delete() ? 1 : 0;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.sixbridge.bundle";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }

    private static String name(Uri uri) {
        return uri.getLastPathSegment();
    }

    private static File file(String name) {
        if (root == null) {
            throw new IllegalStateException("Provider is not initialized");
        }
        return new File(root, name);
    }
}
