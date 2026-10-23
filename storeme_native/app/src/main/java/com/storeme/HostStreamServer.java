package com.storeme;

import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HostStreamServer extends NanoHTTPD {
    
    // Maps token -> actual absolute file path (share links)
    private Map<String, String> shareTokens = new ConcurrentHashMap<>();
    // Short-lived stream tokens: token -> filePath
    private Map<String, String> streamTokens = new ConcurrentHashMap<>();

    public HostStreamServer(int port) {
        super(port);
    }
    
    public void addShareToken(String token, String path) {
        shareTokens.put(token, path);
    }
    
    public void removeShareToken(String token) {
        shareTokens.remove(token);
    }

    public String addStreamToken(String filePath) {
        String token = java.util.UUID.randomUUID().toString().substring(0, 12);
        streamTokens.put(token, filePath);
        return token;
    }

    private Response serveFile(IHTTPSession session, File file) {
        try {
            String mimeType = "application/octet-stream";
            int dotIndex = file.getName().lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = file.getName().substring(dotIndex + 1).toLowerCase();
                String possibleMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (possibleMime != null) mimeType = possibleMime;
            }

            Map<String, String> headers = session.getHeaders();
            long fileLen = file.length();
            String rangeHeader = headers.get("range");

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                long startOffset = 0;
                long endOffset = fileLen - 1;
                String range = rangeHeader.substring(6);
                String[] parts = range.split("-");
                try { startOffset = Long.parseLong(parts[0]); } catch (Exception e) {}
                try { if (parts.length > 1 && !parts[1].isEmpty()) endOffset = Long.parseLong(parts[1]); } catch (Exception e) {}

                long contentLen = endOffset - startOffset + 1;
                FileInputStream fis = new FileInputStream(file);
                fis.skip(startOffset);
                Response res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, contentLen);
                res.addHeader("Content-Range", "bytes " + startOffset + "-" + endOffset + "/" + fileLen);
                res.addHeader("Accept-Ranges", "bytes");
                res.addHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
                return res;
            } else {
                FileInputStream fis = new FileInputStream(file);
                Response res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileLen);
                res.addHeader("Accept-Ranges", "bytes");
                res.addHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
                return res;
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Error: " + e.getMessage());
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> parms = session.getParms();
        
        if ("/stream".equals(uri)) {
            String token = parms.get("token");
            if (token != null && streamTokens.containsKey(token)) {
                String path = streamTokens.get(token);
                File file = new File(path);
                if (file.exists() && !file.isDirectory()) {
                    return serveFile(session, file);
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File Not Found or Token Expired");
        }

        if ("/share".equals(uri)) {
            String token = parms.get("token");
            if (token != null && shareTokens.containsKey(token)) {
                String path = shareTokens.get(token);
                File file = new File(path);
                if (file.exists() && !file.isDirectory()) {
                    return serveFile(session, file);
                } else {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File Not Found");
                }
            } else {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, NanoHTTPD.MIME_PLAINTEXT, "Invalid or Expired Token");
            }
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
    }
}

