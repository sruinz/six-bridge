package dev.sixbridge.manager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class ShareGrantReceiverActivity extends Activity {
    public static final String EXTRA_RESULT_RECEIVER = "share_result_receiver";
    public static final int RESULT_SHARE_ACCESS = 1;
    public static final String RESULT_READ_BYTES = "read_bytes";
    public static final String RESULT_READ_ERROR = "read_error";
    public static final String RESULT_WRITE_DENIED = "write_denied";
    public static final String RESULT_WRITE_ERROR = "write_error";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        ResultReceiver resultReceiver =
                getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);

        byte[] readBytes = null;
        String readError = null;
        try (InputStream input = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            readBytes = output.toByteArray();
        } catch (Exception error) {
            readError = error.getClass().getName();
        }

        String writeError = null;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            output.write(0);
        } catch (Exception error) {
            writeError = error.getClass().getName();
        }

        Bundle result = new Bundle();
        result.putByteArray(RESULT_READ_BYTES, readBytes);
        result.putString(RESULT_READ_ERROR, readError);
        result.putBoolean(
                RESULT_WRITE_DENIED,
                SecurityException.class.getName().equals(writeError));
        result.putString(RESULT_WRITE_ERROR, writeError);
        resultReceiver.send(RESULT_SHARE_ACCESS, result);
        finish();
    }
}
