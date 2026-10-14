package com.dilhan.vidra;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Plays files from the private folder: decrypts on the fly (seekable), never writes plain data to disk. */
@androidx.media3.common.util.UnstableApi
final class VaultDataSource implements DataSource {
    private final Context ctx;
    private final DataSource fallback;
    private DataSource active;
    private InputStream in;
    private Uri uri;
    private long left;

    VaultDataSource(Context c, DataSource fallback) { ctx = c.getApplicationContext(); this.fallback = fallback; }

    static final class Factory implements DataSource.Factory {
        private final Context ctx;
        private final DefaultDataSource.Factory def;
        Factory(Context c) { ctx = c.getApplicationContext(); def = new DefaultDataSource.Factory(ctx); }
        @NonNull @Override public DataSource createDataSource() { return new VaultDataSource(ctx, def.createDataSource()); }
    }

    @Override public void addTransferListener(@NonNull TransferListener l) { fallback.addTransferListener(l); }

    @Override public long open(@NonNull DataSpec spec) throws IOException {
        uri = spec.uri;
        if (!Vault.isVault(uri)) { active = fallback; return fallback.open(spec); }
        active = null;
        String id = uri.getHost();
        if (!Vault.isUnlocked()) throw new IOException("The private folder is locked");
        long size = Vault.plainSize(ctx, id);
        if (spec.position > size) throw new IOException("bad position");
        try { in = Vault.open(ctx, id, false, spec.position); }
        catch (Exception e) { throw new IOException(e); }
        left = spec.length != C.LENGTH_UNSET ? spec.length : size - spec.position;
        return left;
    }

    @Override public int read(@NonNull byte[] buf, int off, int len) throws IOException {
        if (active != null) return active.read(buf, off, len);
        if (len == 0) return 0;
        if (left == 0) return C.RESULT_END_OF_INPUT;
        int n = in.read(buf, off, (int) Math.min(len, left));
        if (n <= 0) return C.RESULT_END_OF_INPUT;
        left -= n;
        return n;
    }

    @Nullable @Override public Uri getUri() { return active != null ? active.getUri() : uri; }

    @NonNull @Override public Map<String, List<String>> getResponseHeaders() { return active != null ? active.getResponseHeaders() : Collections.emptyMap(); }

    @Override public void close() throws IOException {
        if (active != null) { active.close(); active = null; return; }
        if (in != null) { try { in.close(); } finally { in = null; } }
    }
}
