package com.droiddeck.launcher.session;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import com.droiddeck.launcher.core.SessionPart;
import com.droiddeck.launcher.video.AndroidMediaCodec;
import com.droiddeck.launcher.video.ExchangeDeadline;
import com.droiddeck.launcher.video.MediaCodecProtocol;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public final class MediaCodecDecodeComponent extends SessionPart {
    private static final String TAG = "MediaCodecBridge";
    private final File directory;
    private final File path;
    private final Set<Client> clients = new HashSet<>();
    private LocalSocket bound;
    private LocalServerSocket server;
    private boolean stopped;
    private boolean suspended;

    public MediaCodecDecodeComponent(File filesDir) {
        directory = new File(new File(filesDir, "mc"), UUID.randomUUID().toString().replace("-", ""));
        path = new File(directory, "d.sock");
    }

    public String socketPath() { return path.getAbsolutePath(); }

    @Override public synchronized void start() {
        if (stopped || bound != null) return;
        LocalSocket owner = new LocalSocket(LocalSocket.SOCKET_STREAM);
        try {
            if (!directory.mkdirs()) throw new IOException("cannot create private bridge directory");
            Os.chmod(directory.getAbsolutePath(), 0700);
            if (socketPath().getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 108) {
                throw new IOException("bridge pathname exceeds Unix socket limit");
            }
            owner.bind(new LocalSocketAddress(socketPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(socketPath(), 0600);
            LocalServerSocket listener = new LocalServerSocket(owner.getFileDescriptor());
            bound = owner;
            server = listener;
            Thread accept = new Thread(() -> accept(listener), "mediacodec-accept");
            accept.setDaemon(true);
            accept.start();
            Thread watchdog = new Thread(this::watchdog, "mediacodec-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            Log.i(TAG, "hardware compatibility listener ready");
        } catch (Exception failure) {
            close(owner);
            path.delete();
            directory.delete();
            Log.w(TAG, "hardware compatibility listener unavailable", failure);
        }
    }

    private void accept(LocalServerSocket listener) {
        while (true) {
            LocalSocket accepted = null;
            try {
                accepted = listener.accept();
                if (accepted.getPeerCredentials().getUid() != Process.myUid()) {
                    close(accepted);
                    continue;
                }
            } catch (IOException failure) {
                if (accepted != null) {
                    close(accepted);
                    continue;
                }
                return;
            }
            Client client = new Client(accepted);
            synchronized (this) {
                if (stopped || suspended || server != listener || clients.size() >= 2) {
                    close(client.socket);
                    continue;
                }
                clients.add(client);
            }
            Thread worker = new Thread(() -> serve(client), "mediacodec-connection");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Client client) {
        try {
            new MediaCodecProtocol(new AndroidMediaCodec()).serve(client.socket.getInputStream(), client.socket.getOutputStream(), deadline -> {
                client.deadline.arm(deadline);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.net.SocketTimeoutException("DDMC exchange deadline exceeded");
                client.socket.setSoTimeout((int) Math.max(1, (remaining + 999_999) / 1_000_000));
            });
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "hardware compatibility connection ended: " + failure.getClass().getSimpleName());
        } finally {
            close(client.socket);
            synchronized (this) { clients.remove(client); }
        }
    }

    public synchronized void setSuspended(boolean value) {
        suspended = value;
        if (value) for (Client client : clients) close(client.socket);
    }

    private void watchdog() {
        while (true) {
            ArrayList<Client> active;
            synchronized (this) {
                if (stopped) return;
                active = new ArrayList<>(clients);
            }
            for (Client client : active) if (client.deadline.expire()) close(client.socket);
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class Client {
        final LocalSocket socket;
        final ExchangeDeadline deadline = new ExchangeDeadline();

        Client(LocalSocket socket) { this.socket = socket; }
    }

    @Override public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        LocalServerSocket listener = server;
        server = null;
        if (bound != null) {
            try { bound.shutdownInput(); } catch (IOException ignored) {}
            close(bound);
            bound = null;
        }
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) {}
        }
        for (Client client : clients) close(client.socket);
        path.delete();
        directory.delete();
    }

    private static void close(LocalSocket socket) {
        try { socket.shutdownInput(); } catch (IOException ignored) {}
        try { socket.shutdownOutput(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }
}
