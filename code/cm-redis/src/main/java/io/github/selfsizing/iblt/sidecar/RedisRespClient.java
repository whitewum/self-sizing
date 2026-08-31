package io.github.selfsizing.iblt.sidecar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal binary-safe RESP2 client used by the standalone Redis sidecar.
 *
 * <p>The standalone sidecar deliberately has no dependency on any external
 * client (such as Jedis). This client implements only the commands needed by the experiment and supports
 * pipelining so a Redis key scan does not turn into one network round trip per key.</p>
 */
final class RedisRespClient implements Closeable {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RedisRespClient(String host, int port, String password, int database) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setSoTimeout(300_000);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
        if (password != null && !password.isEmpty()) {
            command(bytes("AUTH"), bytes(password));
        }
        if (database != 0) {
            command(bytes("SELECT"), bytes(Integer.toString(database)));
        }
    }

    Object command(byte[]... args) throws IOException {
        writeCommand(args);
        out.flush();
        return readReply();
    }

    List<Object> pipeline(List<byte[][]> commands) throws IOException {
        for (byte[][] command : commands) {
            writeCommand(command);
        }
        out.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(readReply());
        }
        return replies;
    }

    private void writeCommand(byte[][] args) throws IOException {
        out.write('*');
        writeAscii(args.length);
        out.write(CRLF);
        for (byte[] arg : args) {
            out.write('$');
            writeAscii(arg.length);
            out.write(CRLF);
            out.write(arg);
            out.write(CRLF);
        }
    }

    private Object readReply() throws IOException {
        int prefix = in.read();
        if (prefix < 0) {
            throw new EOFException("Redis closed connection");
        }
        switch (prefix) {
            case '+':
                return readLine();
            case '-':
                throw new IOException("Redis error: " + new String(readLine(), StandardCharsets.UTF_8));
            case ':':
                return Long.parseLong(new String(readLine(), StandardCharsets.US_ASCII));
            case '$': {
                int length = Integer.parseInt(new String(readLine(), StandardCharsets.US_ASCII));
                if (length < 0) {
                    return null;
                }
                byte[] value = readExactly(length);
                expectCrlf();
                return value;
            }
            case '*': {
                int length = Integer.parseInt(new String(readLine(), StandardCharsets.US_ASCII));
                if (length < 0) {
                    return null;
                }
                List<Object> values = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readReply());
                }
                return values;
            }
            default:
                throw new IOException("Unsupported RESP prefix: " + (char) prefix);
        }
    }

    private byte[] readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current < 0) {
                throw new EOFException("Redis closed connection inside line");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                byte[] result = new byte[bytes.length - 1];
                System.arraycopy(bytes, 0, result, 0, result.length);
                return result;
            }
            line.write(current);
            previous = current;
        }
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(value, offset, length - offset);
            if (n < 0) {
                throw new EOFException("Redis closed connection inside bulk string");
            }
            offset += n;
        }
        return value;
    }

    private void expectCrlf() throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Malformed RESP bulk terminator");
        }
    }

    private void writeAscii(long value) throws IOException {
        out.write(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
