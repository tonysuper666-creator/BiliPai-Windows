package su.litvak.chromecast.api.v2;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;

/** Open Screen message_framer: big-endian uint32 length, max body 65536. */
final class BoundedCastFrame {
    static final int MAX_BODY = 65536;
    static byte[] read(Socket socket, int firstByteTimeoutMs, int frameTimeoutMs) throws IOException {
        InputStream input = socket.getInputStream();
        socket.setSoTimeout(firstByteTimeoutMs);
        int first = input.read(); if (first < 0) throw new EOFException("Cast peer closed");
        long deadline = System.nanoTime() + Duration.ofMillis(frameTimeoutMs).toNanos();
        byte[] prefix = new byte[4]; prefix[0] = (byte)first;
        fill(socket, input, prefix, 1, 3, deadline);
        int size = ByteBuffer.wrap(prefix).getInt();
        if (size <= 0 || size > MAX_BODY) throw new IOException("Invalid Cast frame length");
        byte[] body = new byte[size]; fill(socket, input, body, 0, size, deadline); return body;
    }
    private static void fill(Socket socket, InputStream input, byte[] dest, int start, int size, long deadline) throws IOException {
        for (int count=0; count<size;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Cast frame deadline exceeded");
            socket.setSoTimeout((int)Math.max(1, Duration.ofNanos(remaining).toMillis()));
            int got = input.read(dest, start + count, size - count);
            if (got < 0) throw new EOFException("Truncated Cast frame");
            count += got;
        }
    }
    static void write(Socket socket, CastChannel.CastMessage message) throws IOException {
        int size = message.getSerializedSize();
        if (size <= 0 || size > MAX_BODY) throw new IOException("Invalid Cast frame length");
        OutputStream output = socket.getOutputStream();
        output.write(ByteBuffer.allocate(4).putInt(size).array()); message.writeTo(output); output.flush();
    }
}
