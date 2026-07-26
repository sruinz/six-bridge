package dev.sixbridge.manager.icon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TestBlockingIconProvider extends ContentProvider {
    public static volatile CountDownLatch openStarted = new CountDownLatch(1);
    public static volatile CountDownLatch cancellationReceived = new CountDownLatch(1);
    public static volatile CountDownLatch releaseOpen = new CountDownLatch(1);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("reset".equals(method)) {
            reset();
            result.putBoolean("completed", true);
        } else if ("await-open".equals(method)) {
            result.putBoolean("completed", await(openStarted));
        } else if ("await-cancellation".equals(method)) {
            result.putBoolean("completed", await(cancellationReceived));
        } else if ("release".equals(method)) {
            releaseOpen.countDown();
            result.putBoolean("completed", true);
        }
        return result;
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            Uri uri,
            String mode,
            CancellationSignal signal) {
        return openBlocking(uri, signal);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
            Uri uri,
            String mimeTypeFilter,
            Bundle opts,
            CancellationSignal signal) {
        return openBlocking(uri, signal);
    }

    private AssetFileDescriptor openBlocking(Uri uri, CancellationSignal signal) {
        if (!"/cancel-aware".equals(uri.getPath())) {
            return null;
        }
        if (signal != null) {
            signal.setOnCancelListener(() -> cancellationReceived.countDown());
        }
        openStarted.countDown();
        awaitRelease();
        return null;
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
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        return 0;
    }

    private static void awaitRelease() {
        while (true) {
            try {
                releaseOpen.await();
                return;
            } catch (InterruptedException ignored) {
                // Client interruption must not bypass explicit test cleanup.
            }
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void reset() {
        openStarted = new CountDownLatch(1);
        cancellationReceived = new CountDownLatch(1);
        releaseOpen = new CountDownLatch(1);
    }
}
