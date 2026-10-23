// Firebase Configuration
const firebaseConfig = {
    projectId: "storemeapp-ef3bd",
    databaseURL: "https://storemeapp-ef3bd-default-rtdb.asia-southeast1.firebasedatabase.app",
    apiKey: "AIzaSyBxrdZXwbsOArbunK7NygpZ9CFRgMQyGcA"
};
firebase.initializeApp(firebaseConfig);
const db = firebase.database();

const urlParams = new URLSearchParams(window.location.search);
const hostId = urlParams.get('host');
const token = urlParams.get('token');

// ── State ─────────────────────────────────────────────────────
let peerConnection;
let dataChannel;
let clientId = 'share_client_' + Date.now();
let connectionRef;
let sharePath = '';
let shareName = '';
let shareMimeType = '';
let shareSize = 0;

// Transfer buffer (shared between download + preview)
let transferBuffer = [];
let downloadedBytes = 0;
let downloadExpectedSize = 0;
let transferMode = 'download'; // 'download' | 'preview'

let swReady = false;
const swPendingRequests = new Map();
let currentRangeId = null;
let downloadStartTime = 0;

// ── Init ──────────────────────────────────────────────────────
if (!hostId || !token) {
    document.getElementById('file-name').innerText = "Invalid Link";
    document.getElementById('file-size').innerText = "Host ID or Token is missing.";
    document.getElementById('file-icon').innerText = "error_outline";
    document.getElementById('file-icon').style.color = "#d93025";
} else {
    connectToHost();
}

// ── Helpers ───────────────────────────────────────────────────
function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024, sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function getFileType(name) {
    const ext = name.split('.').pop().toLowerCase();
    if (['jpg','jpeg','png','gif','webp','bmp','svg'].includes(ext)) return 'image';
    if (['mp4','webm','ogg','mov','mkv'].includes(ext)) return 'video';
    if (['mp3','wav','ogg','m4a','flac','aac'].includes(ext)) return 'audio';
    if (['txt','md','js','json','css','html','xml','csv','log','py','java','kt','sh','ts'].includes(ext)) return 'text';
    if (['pdf'].includes(ext)) return 'pdf';
    return 'other';
}

function getMimeType(name) {
    const ext = name.split('.').pop().toLowerCase();
    const map = {
        jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
        gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml',
        mp4: 'video/mp4', webm: 'video/webm', ogg: 'video/ogg',
        mov: 'video/quicktime', mkv: 'video/x-matroska',
        mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4',
        flac: 'audio/flac', aac: 'audio/aac',
        txt: 'text/plain', md: 'text/plain', json: 'application/json',
        pdf: 'application/pdf'
    };
    return map[ext] || 'application/octet-stream';
}

// ── WebRTC Connection ─────────────────────────────────────────
async function connectToHost() {
    connectionRef = db.ref('rooms/' + hostId + '/connections/' + clientId);

    const rtcConfig = { iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
    ]};
    peerConnection = new RTCPeerConnection(rtcConfig);

    dataChannel = peerConnection.createDataChannel('fileChannel');
    dataChannel.binaryType = 'arraybuffer';

    // Connection timeout
    let connectionTimeout = setTimeout(() => {
        if (dataChannel.readyState !== 'open') {
            document.getElementById('file-name').innerText = "Host Offline";
            document.getElementById('file-size').innerText = "The host device is currently offline or unreachable.";
            document.getElementById('file-icon').innerText = "cloud_off";
            document.getElementById('file-icon').style.color = "#5f6368";
            peerConnection.close();
        }
    }, 15000);

    dataChannel.onopen = () => {
        clearTimeout(connectionTimeout);
        console.log("DataChannel Open — requesting share info");
        dataChannel.send(JSON.stringify({ action: 'get_share_info' }));
    };

    dataChannel.onmessage = (event) => {
        if (typeof event.data === 'string') {
            handleJsonMessage(event.data);
        } else {
            handleBinaryChunk(event.data);
        }
    };

    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            connectionRef.child('candidates/client').push({
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid,
                sdpMLineIndex: event.candidate.sdpMLineIndex
            });
        }
    };

    const offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);

    await connectionRef.child('offer').set({
        type: offer.type,
        sdp: offer.sdp,
        username: "share_user_" + token,
        password: token
    });

    let isAnswerSet = false;
    connectionRef.child('answer').on('value', (snapshot) => {
        const data = snapshot.val();
        if (data && data.sdp && !isAnswerSet) {
            isAnswerSet = true;
            peerConnection.setRemoteDescription(
                new RTCSessionDescription({ type: data.type, sdp: data.sdp })
            ).catch(console.error);
        }
    });

    connectionRef.child('candidates/host').on('child_added', (snapshot) => {
        const data = snapshot.val();
        if (data && data.candidate) {
            peerConnection.addIceCandidate(new RTCIceCandidate(data)).catch(console.error);
        }
    });
}

// ── JSON Messages ─────────────────────────────────────────────
function handleJsonMessage(jsonStr) {
    try {
        const msg = JSON.parse(jsonStr);

        if (msg.action === 'share_info') {
            sharePath = msg.path;
            shareName = msg.name;
            shareSize = msg.size;
            shareMimeType = getMimeType(msg.name);
            const fileType = getFileType(msg.name);

            document.getElementById('file-name').innerText = msg.name;
            document.getElementById('file-size').innerText = formatSize(msg.size) + ' · ' + (msg.isDir ? 'Folder' : msg.name.split('.').pop().toUpperCase());

            // Set icon based on type
            const iconMap = { image: 'image', video: 'videocam', audio: 'audiotrack', text: 'article', pdf: 'picture_as_pdf', other: 'insert_drive_file' };
            document.getElementById('file-icon').innerText = iconMap[fileType] || 'insert_drive_file';

            if (!msg.isDir) {
                document.getElementById('btn-download').disabled = false;
                // Show preview button only for previewable types
                if (['image','video','audio','text','pdf'].includes(fileType)) {
                    document.getElementById('btn-preview').disabled = false;
                    // Show preview loading area
                    const pa = document.getElementById('preview-area');
                    pa.classList.remove('hidden');
                    pa.classList.add('light');
                    pa.innerHTML = `<div class="preview-loading">
                        <span class="material-icons">preview</span>
                        <span>Tap "Preview" to load file preview</span>
                    </div>`;
                }
            } else {
                document.getElementById('btn-download').innerHTML = '<span class="material-icons" style="font-size:18px;">folder_zip</span> Download Folder (ZIP)';
                document.getElementById('btn-download').disabled = false;
                shareName = msg.name + '.zip';
                shareMimeType = 'application/zip';
            }

        } else if (msg.action === 'file_transfer_start') {
            transferBuffer = [];
            downloadedBytes = 0;
            downloadExpectedSize = msg.size;
            downloadStartTime = Date.now();

            const pw = document.getElementById('progress-wrap');
            pw.style.display = 'block';
            document.getElementById('progress-fill').style.width = '0%';
            document.getElementById('progress-text').innerText = '0%';

            if (transferMode === 'preview') {
                const pa = document.getElementById('preview-area');
                pa.classList.remove('hidden', 'light');
                pa.innerHTML = `<div class="preview-loading">
                    <span class="material-icons">hourglass_top</span>
                    <span>Loading preview...</span>
                </div>`;
            } else {
                document.getElementById('btn-download').disabled = true;
                document.getElementById('btn-download').innerHTML = '<span class="material-icons" style="font-size:18px;">hourglass_top</span> Downloading...';
            }

        } else if (msg.action === 'range_data') {
            currentRangeId = msg.requestId;
        } else if (msg.action === 'range_end') {
            const rid = msg.requestId || currentRangeId;
            const port = swPendingRequests.get(rid);
            if (port) {
                port.postMessage({ type: 'end' });
                swPendingRequests.delete(rid);
            }
            currentRangeId = null;
        } else if (msg.action === 'file_transfer_end') {
            document.getElementById('progress-fill').style.width = '100%';
            document.getElementById('progress-text').innerText = '100%';

            const blob = new Blob(transferBuffer, { type: shareMimeType });

            if (transferMode === 'preview') {
                showPreview(blob);
                // Re-enable both buttons
                document.getElementById('btn-preview').disabled = false;
                document.getElementById('btn-preview').innerHTML = '<span class="material-icons" style="font-size:18px;">visibility</span> Preview';
                document.getElementById('btn-download').disabled = false;
                // Hide progress after preview
                setTimeout(() => { document.getElementById('progress-wrap').style.display = 'none'; }, 1000);
            } else {
                // Trigger actual download
                document.getElementById('btn-download').innerHTML = '<span class="material-icons" style="font-size:18px;">check_circle</span> Downloaded!';
                const url = URL.createObjectURL(blob);
                const a = document.getElementById('download-anchor');
                a.href = url;
                a.download = shareName;
                a.click();
                setTimeout(() => {
                    URL.revokeObjectURL(url);
                    transferBuffer = [];
                    document.getElementById('btn-download').disabled = false;
                    document.getElementById('btn-download').innerHTML = '<span class="material-icons" style="font-size:18px;">download</span> Download';
                }, 2000);
            }
        }
    } catch (e) {
        console.error("Parse error", e);
    }
}

// ── Binary Chunks ─────────────────────────────────────────────
function handleBinaryChunk(buffer) {
    if (currentRangeId) {
        const port = swPendingRequests.get(currentRangeId);
        if (port) {
            port.postMessage({ type: 'chunk', data: buffer }, [buffer]);
        }
        return;
    }

    transferBuffer.push(buffer);
    downloadedBytes += buffer.byteLength;
    if (downloadExpectedSize > 0) {
        const pct = Math.min(99, Math.round((downloadedBytes / downloadExpectedSize) * 100));
        const elapsedSec = Math.max(0.1, (Date.now() - downloadStartTime) / 1000);
        const speedMBps = (downloadedBytes / (1024 * 1024)) / elapsedSec;
        document.getElementById('progress-text').innerText = pct + '% (' + speedMBps.toFixed(1) + ' MB/s)';
        document.getElementById('progress-fill').style.width = pct + '%';
    }
}

// ── Preview Renderer ──────────────────────────────────────────
function showPreview(blob) {
    const fileType = getFileType(shareName);
    const url = URL.createObjectURL(blob);
    const pa = document.getElementById('preview-area');
    pa.classList.remove('hidden');

    if (fileType === 'image') {
        pa.classList.add('light');
        pa.style.background = '#fafafa';
        pa.innerHTML = `<img id="preview-image" src="${url}" alt="${shareName}" style="max-width:100%;max-height:480px;object-fit:contain;display:block;margin:0 auto;"/>`;
    } else if (fileType === 'video') {
        pa.classList.remove('light');
        pa.style.background = '#000';
        pa.innerHTML = `<video playsinline id="preview-video" controls autoplay style="width:100%;max-height:480px;display:block;outline:none;">
            <source src="${url}" type="${shareMimeType}">
            Your browser does not support this video format.
        </video>`;
    } else if (fileType === 'audio') {
        pa.classList.add('light');
        pa.style.background = '#fafafa';
        pa.innerHTML = `<div style="padding:40px 24px;width:100%;text-align:center;">
            <span class="material-icons" style="font-size:72px;color:#1a73e8;display:block;margin-bottom:16px;">audiotrack</span>
            <p style="color:#202124;font-weight:600;margin-bottom:16px;">${shareName}</p>
            <audio playsinline id="preview-audio" controls autoplay style="width:100%;">
                <source src="${url}" type="${shareMimeType}">
            </audio>
        </div>`;
    } else if (fileType === 'text') {
        pa.classList.add('light');
        pa.style.background = '#fafafa';
        const reader = new FileReader();
        reader.onload = (e) => {
            pa.innerHTML = `<pre id="preview-text" style="width:100%;padding:24px;font-family:monospace;font-size:13px;line-height:1.6;color:#202124;white-space:pre-wrap;word-break:break-word;max-height:480px;overflow-y:auto;border:none;background:#fafafa;">${e.target.result.replace(/</g,'&lt;').replace(/>/g,'&gt;')}</pre>`;
        };
        reader.readAsText(blob);
    } else if (fileType === 'pdf') {
        pa.classList.add('light');
        pa.style.background = '#fafafa';
        pa.innerHTML = `<iframe src="${url}" style="width:100%;height:480px;border:none;display:block;"></iframe>`;
    } else {
        pa.innerHTML = `<div class="no-preview">
            <span class="material-icons">insert_drive_file</span>
            <span>No preview available for this file type</span>
        </div>`;
    }
}

// ── Actions ───────────────────────────────────────────────────
window.startPreview = function() {
    if (dataChannel && dataChannel.readyState === 'open' && sharePath) {
        transferMode = 'preview';
        document.getElementById('btn-preview').disabled = true;

        const fileType = getFileType(shareName);
        if (swReady && (fileType === 'video' || fileType === 'audio')) {
            const reqId = 'stream_' + Date.now();
            const streamUrl = `./stream/${reqId}?size=${shareSize}&name=${encodeURIComponent(shareName)}&mime=${encodeURIComponent(shareMimeType)}`;
            
            const pa = document.getElementById('preview-area');
            pa.classList.remove('hidden', 'light');
            
            if (fileType === 'video') {
                pa.innerHTML = `<video playsinline controls autoplay style="width:100%;max-height:480px;display:block;outline:none;" src="${streamUrl}"></video>`;
            } else {
                pa.classList.add('light');
                pa.style.background = '#fafafa';
                pa.innerHTML = `<div style="padding:40px 24px;width:100%;text-align:center;">
                    <span class="material-icons" style="font-size:72px;color:#1a73e8;display:block;margin-bottom:16px;">audiotrack</span>
                    <p style="color:#202124;font-weight:600;margin-bottom:16px;">${shareName}</p>
                    <audio playsinline controls autoplay style="width:100%;" src="${streamUrl}"></audio>
                </div>`;
            }
            document.getElementById('btn-preview').innerHTML = '<span class="material-icons" style="font-size:18px;">visibility</span> Streaming';
            return;
        }

        document.getElementById('btn-preview').innerHTML = '<span class="material-icons" style="font-size:18px;">hourglass_top</span> Loading...';
        dataChannel.send(JSON.stringify({ action: 'download', path: sharePath, offset: 0 }));
    }
};

window.startDownload = function() {
    if (dataChannel && dataChannel.readyState === 'open' && sharePath) {
        transferMode = 'download';
        document.getElementById('btn-download').disabled = true;
        document.getElementById('btn-download').innerHTML = '<span class="material-icons" style="font-size:18px;">hourglass_top</span> Downloading...';
        dataChannel.send(JSON.stringify({ action: 'download', path: sharePath, offset: 0 }));
    }
};

if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./stream-worker.js', { scope: './' })
        .then(reg => { swReady = true; })
        .catch(e => console.warn('[SW] Registration failed:', e));

    navigator.serviceWorker.addEventListener('message', (event) => {
        const msg = event.data;
        if (msg.type === 'range_request') {
            swPendingRequests.set(msg.id, event.ports[0]);
            if (dataChannel && dataChannel.readyState === 'open') {
                dataChannel.send(JSON.stringify({
                    action: 'download_range',
                    path: sharePath,
                    requestId: msg.id,
                    start: msg.start,
                    length: msg.length
                }));
            }
        } else if (msg.type === 'range_cancel') {
            const port = swPendingRequests.get(msg.id);
            if (port) port.close();
            swPendingRequests.delete(msg.id);
            if (dataChannel && dataChannel.readyState === 'open') {
                dataChannel.send(JSON.stringify({ action: 'cancel_range', requestId: msg.id }));
            }
        }
    });
}
