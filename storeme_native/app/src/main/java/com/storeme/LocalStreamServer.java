package com.storeme;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

public class LocalStreamServer extends NanoHTTPD {

    // Per-stream state
    private volatile BlockingQueue<byte[]> dataQueue = null;
    private volatile AtomicBoolean streamActive = new AtomicBoolean(false);
    private volatile CountDownLatch streamStartLatch = null;
    private volatile AtomicLong fileSize = new AtomicLong(0);
    private volatile String currentRequestId = "";

    public LocalStreamServer(int port) {
        super(port);
    }

    public void startRangeStream(String requestId, long start, long length) {
        if (requestId != null && requestId.equals(currentRequestId)) {
            streamActive.set(true);
            CountDownLatch latch = this.streamStartLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    public void feedBinary(byte[] data) {
        BlockingQueue<byte[]> q = this.dataQueue;
        if (streamActive.get() && q != null) {
            try {
                q.offer(data, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
        }
    }

    public void stopRangeStream(String requestId) {
        if (requestId != null && requestId.equals(currentRequestId)) {
            streamActive.set(false);
            BlockingQueue<byte[]> q = this.dataQueue;
            if (q != null) {
                q.offer(new byte[0]); // EOF sentinel
            }
        }
    }

    public void startStream(String path, long size) {
        fileSize.set(size);
        streamActive.set(true);
        CountDownLatch latch = this.streamStartLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    public void stopStream() {
        streamActive.set(false);
        BlockingQueue<byte[]> q = this.dataQueue;
        if (q != null) {
            q.offer(new byte[0]); // EOF sentinel
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String path = parms.get("path");
        final String finalPath = path;

        if (parms.containsKey("size")) {
            try { fileSize.set(Long.parseLong(parms.get("size"))); } catch (Exception e) {}
        }

        Map<String, String> headers = session.getHeaders();
        long parsedOffset = 0;
        String rangeHeader = headers.get("range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] split = rangeHeader.substring(6).split("-");
            try { parsedOffset = Long.parseLong(split[0]); } catch (Exception e) {}
        }
        final long startOffset = parsedOffset;
        
        long size = fileSize.get();
        final long contentLength = (size > 0) ? (size - startOffset) : -1;
        long reqLength = (contentLength > 0) ? contentLength : (10 * 1024 * 1024);

        final BlockingQueue<byte[]> myQueue = new LinkedBlockingQueue<>(512);
        final CountDownLatch myLatch = new CountDownLatch(1);
        final AtomicBoolean myActive = new AtomicBoolean(false);
        final String requestId = UUID.randomUUID().toString();

        this.dataQueue = myQueue;
        this.streamStartLatch = myLatch;
        this.streamActive = myActive;
        this.currentRequestId = requestId;

        try {
            JSONObject req = new JSONObject();
            req.put("action", "download_range");
            req.put("path", finalPath);
            req.put("start", startOffset);
            req.put("length", reqLength);
            req.put("requestId", requestId);
            ClientConnectionManager.getInstance().sendData(req.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

        InputStream queueStream = new InputStream() {
            private byte[] currentBuffer = null;
            private int bufferPos = 0;
            private boolean streamBegun = false;

            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                int r = read(b, 0, 1);
                return (r == -1) ? -1 : (b[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (currentBuffer == null || bufferPos >= currentBuffer.length) {
                    if (!streamBegun) {
                        try {
                            boolean started = myLatch.await(15, TimeUnit.SECONDS);
                            if (!started) return -1;
                        } catch (InterruptedException e) {
                            return -1;
                        }
                        streamBegun = true;
                    }

                    try {
                        while (true) {
                            currentBuffer = myQueue.poll(5, TimeUnit.SECONDS);
                            if (currentBuffer != null) break;
                            if (!myActive.get()) return -1;
                        }
                    } catch (InterruptedException e) {
                        return -1;
                    }

                    if (currentBuffer.length == 0) return -1;
                    bufferPos = 0;
                }

                int available = currentBuffer.length - bufferPos;
                int toRead = Math.min(len, available);
                System.arraycopy(currentBuffer, bufferPos, b, off, toRead);
                bufferPos += toRead;
                return toRead;
            }

            @Override
            public void close() throws IOException {
                super.close();
                myActive.set(false);
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "cancel_range");
                    req.put("requestId", requestId);
                    ClientConnectionManager.getInstance().sendData(req.toString());
                } catch (Exception e) {}
            }
        };

        String mime = "application/octet-stream";
        if (finalPath != null) {
            int dot = finalPath.lastIndexOf('.');
            if (dot > 0 && dot < finalPath.length() - 1) {
                String ext = finalPath.substring(dot + 1).toLowerCase();
                String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (m != null) mime = m;
            }
        }

        Response response;
        if (contentLength > 0) {
            Response.Status status = (rangeHeader != null)
                    ? Response.Status.PARTIAL_CONTENT
                    : Response.Status.OK;
            response = newFixedLengthResponse(status, mime, queueStream, contentLength);
        } else {
            response = newChunkedResponse(Response.Status.OK, mime, queueStream);
        }

        response.addHeader("Accept-Ranges", "bytes");
        if (rangeHeader != null && size > 0) {
            response.addHeader("Content-Range", "bytes " + startOffset + "-" + (size - 1) + "/" + size);
        }
        if (finalPath != null) {
            String fileName = new java.io.File(finalPath).getName();
            response.addHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
        }
        return response;
    }
}
