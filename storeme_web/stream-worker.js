/**
 * StoreMeNative Stream Service Worker
 * 
 * Intercepts fetch requests to /stream/<requestId> and pipes bytes
 * received from the main thread (via WebRTC DataChannel) into a
 * ReadableStream that the <video> element sees as a normal HTTP response.
 *
 * Protocol:
 *  Main thread → SW:  { type:'resolve', id, controller }  -- not possible, use port
 *  Main thread → SW:  message via MessageChannel port:
 *    { type:'chunk',  data: ArrayBuffer }
 *    { type:'end' }
 *    { type:'error', message }
 *  SW → Main thread: { type:'range_request', id, start, end, totalSize }
 */

const STREAM_SCOPE = '/stream/';

// Map of requestId → { controller: ReadableStreamDefaultController, port: MessagePort }
const pendingStreams = new Map();

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);

    if (!url.pathname.startsWith(STREAM_SCOPE)) return; // not our request

    // URL format: /stream/<requestId>?size=<bytes>&name=<filename>
    const baseRequestId = url.pathname.slice(STREAM_SCOPE.length);
    const uniqueFetchId = baseRequestId + '_' + Math.random().toString(36).substr(2, 9);
    
    const totalSize = parseInt(url.searchParams.get('size') || '0', 10);
    const filename  = url.searchParams.get('name') || 'video.mp4';
    const mimeType  = url.searchParams.get('mime') || 'video/mp4';

    // Parse HTTP Range header from video element
    const rangeHeader = event.request.headers.get('Range');
    let start = 0;
    let end   = totalSize - 1;

    if (rangeHeader) {
        const m = rangeHeader.match(/bytes=(\d+)-(\d*)/);
        if (m) {
            start = parseInt(m[1], 10);
            end   = m[2] ? parseInt(m[2], 10) : Math.min(start + 2 * 1024 * 1024 - 1, totalSize - 1);
        }
    }

    const length = end - start + 1;

    const stream = new ReadableStream({
        start(controller) {
            // Register this stream so main thread can push bytes into it
            const { port1, port2 } = new MessageChannel();
            pendingStreams.set(uniqueFetchId, { controller, port: port1 });

            port1.onmessage = (e) => {
                const msg = e.data;
                if (msg.type === 'chunk') {
                    controller.enqueue(new Uint8Array(msg.data));
                } else if (msg.type === 'end') {
                    controller.close();
                    pendingStreams.delete(uniqueFetchId);
                    port1.close();
                } else if (msg.type === 'error') {
                    controller.error(new Error(msg.message));
                    pendingStreams.delete(uniqueFetchId);
                    port1.close();
                }
            };
            port1.start();

            // Notify main thread: "I need bytes start..end for uniqueFetchId, here's your port"
            if (event.clientId) {
                self.clients.get(event.clientId).then(client => {
                    if (client) {
                        client.postMessage(
                            { type: 'range_request', id: uniqueFetchId, start, end, length, totalSize, filename },
                            [port2]
                        );
                    } else {
                        // Fallback if client not found
                        self.clients.matchAll({ includeUncontrolled: true, type: 'window' }).then(clients => {
                            if (clients && clients.length > 0) {
                                clients[0].postMessage(
                                    { type: 'range_request', id: uniqueFetchId, start, end, length, totalSize, filename },
                                    [port2]
                                );
                            }
                        });
                    }
                });
            } else {
                self.clients.matchAll({ includeUncontrolled: true, type: 'window' }).then(clients => {
                    if (clients && clients.length > 0) {
                        clients[0].postMessage(
                            { type: 'range_request', id: uniqueFetchId, start, end, length, totalSize, filename },
                            [port2]
                        );
                    }
                });
            }
        },
        cancel() {
            const s = pendingStreams.get(uniqueFetchId);
            if (s) {
                s.port.close();
                pendingStreams.delete(uniqueFetchId);
            }
            // Notify main thread to cancel the WebRTC transfer
            self.clients.matchAll({ includeUncontrolled: true, type: 'window' }).then(clients => {
                if (clients && clients.length > 0) {
                    clients[0].postMessage({ type: 'range_cancel', id: uniqueFetchId });
                }
            });
        }
    });

    const headers = {
        'Content-Type': mimeType,
        'Content-Length': String(length),
        'Accept-Ranges': 'bytes',
        'Content-Disposition': `inline; filename="${filename}"`
    };
    if (rangeHeader) {
        headers['Content-Range'] = `bytes ${start}-${end}/${totalSize}`;
    }

    const responseInit = {
        status: rangeHeader ? 206 : 200,
        headers: headers
    };

    event.respondWith(new Response(stream, responseInit));
});
