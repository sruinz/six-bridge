package dev.sixbridge.manager.icon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TestBlockingIconProvider extends ContentProvider {
    private static final Object GENERATION_LOCK = new Object();
    private static volatile Generation generation = new Generation();

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
            result.putBoolean("completed", await(generation.openStarted));
        } else if ("await-cancellation".equals(method)) {
            result.putBoolean("completed", await(generation.cancellationReceived));
        } else if ("release".equals(method)) {
            generation.releaseOpen.countDown();
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
        String path = uri.getPath();
        if (!"/cancel-aware".equals(path) && !"/late-return".equals(path)) {
            return null;
        }
        Generation openingGeneration = generation;
        if (signal != null) {
            signal.setOnCancelListener(
                    () -> openingGeneration.cancellationReceived.countDown());
        }
        ParcelFileDescriptor[] pipe = null;
        if ("/late-return".equals(path)) {
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException error) {
                throw new IllegalStateException("Could not create test pipe", error);
            }
        }
        openingGeneration.openStarted.countDown();
        awaitRelease(openingGeneration.releaseOpen);
        if (pipe == null) {
            return null;
        }
        closeQuietly(pipe[1]);
        return new AssetFileDescriptor(
                pipe[0],
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
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

    private static void awaitRelease(CountDownLatch releaseOpen) {
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
        synchronized (GENERATION_LOCK) {
            generation.releaseOpen.countDown();
            generation = new Generation();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Test cleanup is best-effort.
        }
    }

    private static final class Generation {
        final CountDownLatch openStarted = new CountDownLatch(1);
        final CountDownLatch cancellationReceived = new CountDownLatch(1);
        final CountDownLatch releaseOpen = new CountDownLatch(1);
    }
}
