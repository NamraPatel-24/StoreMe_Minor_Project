// Firebase Configuration
const firebaseConfig = {
    projectId: "storemeapp-ef3bd",
    databaseURL: "https://storemeapp-ef3bd-default-rtdb.asia-southeast1.firebasedatabase.app",
    apiKey: "AIzaSyBxrdZXwbsOArbunK7NygpZ9CFRgMQyGcA"
};
firebase.initializeApp(firebaseConfig);
const db = firebase.database();
window._connectionEstablished = false;

// Global State & RBAC Session Tracking
let currentUser = {
    username: 'admin',
    role: 'Admin', // 'Admin' | 'Uploader' | 'Viewer'
    token: ''
};
window.currentUser = currentUser;

function normalizeRole(role) {
    if (!role) return 'Admin';
    const r = String(role).trim().toLowerCase();
    if (r === 'admin') return 'Admin';
    if (r === 'uploader') return 'Uploader';
    if (r === 'viewer') return 'Viewer';
    return 'Admin';
}
window.normalizeRole = normalizeRole;

function setCurrentUserRole(role, username) {
    if (role) currentUser.role = normalizeRole(role);
    if (username) currentUser.username = username;
    applyRolePermissions();
}
window.setCurrentUserRole = setCurrentUserRole;
window.setRole = (r, u) => setCurrentUserRole(r, u);
window.getCurrentUser = () => currentUser;

function canUserDeleteFile(file) {
    if (!file) return false;
    if (currentUser.role === 'Admin') return true;
    if (currentUser.role === 'Uploader') {
        const owner = file.owner || file.uploadedBy;
        return !owner || owner === currentUser.username || owner === 'admin';
    }
    return false; // Viewer cannot delete any files
}
window.canUserDeleteFile = canUserDeleteFile;

function canUserModifyFile(file) {
    if (!file) return false;
    if (currentUser.role === 'Admin') return true;
    if (currentUser.role === 'Uploader') {
        const owner = file.owner || file.uploadedBy;
        return !owner || owner === currentUser.username || owner === 'admin';
    }
    return false; // Viewer cannot rename or move files
}
window.canUserModifyFile = canUserModifyFile;

function formatDate(timestamp) {
    if (!timestamp) return 'Today';
    const d = new Date(timestamp);
    if (isNaN(d.getTime())) return 'Today';
    return d.toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric'
    });
}
window.formatDate = formatDate;

function getFileInfo(name, isDir) {
    if (isDir) {
        return { icon: 'folder', color: '#f59e0b', type: 'Folder' }; // Amber
    }
    const ext = (name || '').split('.').pop().toLowerCase();
    if (['jpg','jpeg','png','gif','webp','svg','heic','bmp'].includes(ext)) {
        return { icon: 'image', color: '#8b5cf6', type: 'Image' }; // Purple
    }
    if (['mp4','mkv','avi','mov','webm','3gp','m4v'].includes(ext)) {
        return { icon: 'movie', color: '#ec4899', type: 'Video' }; // Pink
    }
    if (['mp3','wav','aac','flac','m4a','ogg'].includes(ext)) {
        return { icon: 'music_note', color: '#6366f1', type: 'Audio' }; // Indigo
    }
    if (ext === 'pdf') {
        return { icon: 'picture_as_pdf', color: '#ef4444', type: 'PDF' }; // Red
    }
    if (['doc','docx','odt','rtf'].includes(ext)) {
        return { icon: 'description', color: '#0284c7', type: 'Document' }; // Sky
    }
    if (['xls','xlsx','csv','ods'].includes(ext)) {
        return { icon: 'table_chart', color: '#10b981', type: 'Spreadsheet' }; // Emerald
    }
    if (['ppt','pptx','odp'].includes(ext)) {
        return { icon: 'slideshow', color: '#f97316', type: 'Presentation' }; // Orange
    }
    if (['zip','rar','tar','gz','7z'].includes(ext)) {
        return { icon: 'folder_zip', color: '#d97706', type: 'Archive' }; // Amber
    }
    if (['txt','md','log'].includes(ext)) {
        return { icon: 'article', color: '#64748b', type: 'Text' }; // Slate
    }
    if (['js','ts','py','java','kt','cpp','c','html','css','json','xml','sh','sql'].includes(ext)) {
        return { icon: 'code', color: '#2563eb', type: 'Source Code' }; // Blue
    }
    if (['apk'].includes(ext)) {
        return { icon: 'android', color: '#059669', type: 'Android App' }; // Emerald
    }
    return { icon: 'insert_drive_file', color: '#64748b', type: 'File' };
}
window.getFileInfo = getFileInfo;

let swReady = false;
const swPendingRequests = new Map(); // requestId -> MessagePort

let currentStreamPath  = '';
let currentStreamSize  = 0;
let currentStreamName  = '';
let currentRangeId     = null; // requestId currently receiving binary data

let peerConnection;
let dataChannel;
let clientId = 'web_client_' + Date.now();
let hostId = '';
let currentPath = '';
let allowedPath = '';
let connectionRef;
let selectedFile = null;
let contextTarget = null; // For right-click menu

// Download/Preview State
let downloadBuffer = [];
let downloadedBytes = 0;
let downloadExpectedSize = 0;
let currentDownloadName = '';
let currentTransferIntent = ''; // 'download' or 'preview'

// Live Preview State
let previewCategory = ''; 
let sourceBuffer = null;
let mseChunkQueue = [];
let previewChunkCount = 0;
let prevPreviewBlobUrl = null;
let mediaSource = null;
let textDecoder = new TextDecoder();

let currentDownloadHandle = null;
let currentDownloadWritable = null;
let downloadStartTime = 0;
let downloadedBytesTotal = 0;
let currentDownloadSize = 0;
let currentDownloadWriteQueue = Promise.resolve();

let uploadQueue = [];
let currentUploadObj = null;
let uploadCancelled   = false;
const CHUNK_SIZE = 65536;

let selectedFiles = new Set();
let isTrashMode = false;
let isStarredMode = false;
let currentFilesList = [];
let allFilesInCurrentDir = [];

// Chunk accumulation for chunked directory_listing
let pendingChunks = [];
let pendingTotalChunks = 0;
let pendingPath = '';
let fileListCache = {}; // Cache of loaded directory listings

// DOM Elements Helpers
const getEl = id => document.getElementById(id);

function showToast(message, type = 'info') {
    const container = getEl('toast-container') || document.body;
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    let icon = 'info';
    if (type === 'success') icon = 'check_circle';
    else if (type === 'error') icon = 'error';
    else if (type === 'warning') icon = 'warning';
    
    toast.innerHTML = `
        <span class="material-icons toast-icon">${icon}</span>
        <div class="toast-message">${message}</div>
        <span class="material-icons toast-close" onclick="this.parentElement.remove()">close</span>
    `;
    container.appendChild(toast);
    
    void toast.offsetWidth;
    toast.classList.add('show');
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 250);
    }, 4000);
}
window.showToast = showToast;

function showDestructiveConfirm(title, message, confirmText = 'Delete') {
    return new Promise((resolve) => {
        const modal = getEl('destructive-modal');
        const titleEl = getEl('destructive-modal-title');
        const bodyEl = getEl('destructive-modal-body');
        const btnCancel = getEl('destructive-modal-cancel');
        const btnConfirm = getEl('destructive-modal-confirm');
        
        if (!modal || !btnConfirm || !btnCancel) {
            resolve(window.confirm(message || title));
            return;
        }
        
        titleEl.innerText = title || 'Delete Item?';
        bodyEl.innerHTML = message || 'Are you sure you want to delete this item?';
        const labelSpan = btnConfirm.querySelector('span:last-child');
        if (labelSpan) labelSpan.innerText = confirmText;
        
        modal.style.display = 'flex';
        
        const cleanup = (result) => {
            modal.style.display = 'none';
            btnCancel.onclick = null;
            btnConfirm.onclick = null;
            resolve(result);
        };
        
        btnCancel.onclick = (e) => { e.preventDefault(); cleanup(false); };
        btnConfirm.onclick = (e) => { e.preventDefault(); cleanup(true); };
    });
}
window.showDestructiveConfirm = showDestructiveConfirm;

function applyRolePermissions() {
    const normalizedRole = normalizeRole(currentUser.role);
    currentUser.role = normalizedRole;
    
    // Top Bar Role Badge
    const roleBadge = getEl('user-role-badge');
    const roleText = getEl('role-text');
    if (roleBadge) {
        roleBadge.className = `role-badge role-${normalizedRole.toLowerCase()}`;
    }
    if (roleText) {
        roleText.innerText = normalizedRole;
    }
    
    // Top Bar Avatar
    const avatar = getEl('user-avatar');
    if (avatar) {
        avatar.innerText = (currentUser.username || 'A').charAt(0).toUpperCase();
    }
    
    // "+ New" action button wrapper
    const newBtnWrapper = document.querySelector('.new-btn-wrapper');
    if (newBtnWrapper) {
        newBtnWrapper.style.display = (normalizedRole === 'Viewer') ? 'none' : 'block';
    }
    
    // File upload input
    const fileUpload = getEl('file-upload');
    if (fileUpload) {
        fileUpload.disabled = (normalizedRole === 'Viewer');
    }
    
    // Admin User Management button
    const adminUsersBtn = getEl('btn-admin-users');
    if (adminUsersBtn) {
        adminUsersBtn.style.display = (normalizedRole === 'Admin') ? 'inline-flex' : 'none';
    }
    
    // Top Batch Action Bar buttons
    const batchDelete = getEl('batch-btn-delete');
    const batchMove = getEl('batch-btn-move');
    if (batchDelete) {
        batchDelete.style.display = (normalizedRole === 'Viewer') ? 'none' : 'inline-flex';
    }
    if (batchMove) {
        batchMove.style.display = (normalizedRole === 'Viewer') ? 'none' : 'inline-flex';
    }
    
    // Refresh table rows if already loaded
    if (currentFilesList && currentFilesList.length > 0) {
        renderFiles(currentPath, currentFilesList);
    }
}
window.applyRolePermissions = applyRolePermissions;
function showCustomPrompt(title, defaultValue = '') {
    return new Promise((resolve) => {
        const overlay = document.getElementById('custom-prompt-overlay');
        const titleEl = document.getElementById('custom-prompt-title');
        const inputEl = document.getElementById('custom-prompt-input');
        const btnCancel = document.getElementById('custom-prompt-cancel');
        const btnConfirm = document.getElementById('custom-prompt-confirm');

        titleEl.innerText = title;
        inputEl.value = defaultValue;
        overlay.style.display = 'flex';
        inputEl.focus();

        const cleanup = () => {
            overlay.style.display = 'none';
            btnCancel.onclick = null;
            btnConfirm.onclick = null;
            inputEl.onkeyup = null;
        };

        btnCancel.onclick = () => { cleanup(); resolve(null); };
        btnConfirm.onclick = () => { cleanup(); resolve(inputEl.value); };
        inputEl.onkeyup = (e) => {
            if (e.key === 'Enter') {
                cleanup(); resolve(inputEl.value);
            } else if (e.key === 'Escape') {
                cleanup(); resolve(null);
            }
        };
    });
}

// SERVICE WORKER: Register stream-worker.js
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./stream-worker.js', { scope: './' })
        .then(reg => {
            console.log('[SW] Registered:', reg.scope);
            swReady = true;
        })
        .catch(e => console.warn('[SW] Registration failed:', e));

    navigator.serviceWorker.addEventListener('message', onSwRangeRequest);
}

function onSwRangeRequest(event) {
    const msg = event.data;
    if (msg.type === 'range_request') {
        const { id, start, end, length, totalSize, filename } = msg;
        const port = event.ports[0];
        swPendingRequests.set(id, port);

        sendData({
            action:    'download_range',
            path:      currentStreamPath,
            requestId: id,
            start:     start,
            length:    length
        });
    } else if (msg.type === 'range_cancel') {
        const port = swPendingRequests.get(msg.id);
        if (port) port.close();
        swPendingRequests.delete(msg.id);
        sendData({ action: 'cancel_range', requestId: msg.id });
    }
}

function sendData(obj) {
    if (dataChannel && dataChannel.readyState === 'open') {
        dataChannel.send(JSON.stringify(obj));
    }
}

let answerApplied = false;
let pendingIceCandidates = [];
let processedCandidates = new Set();

function connectToHost() {
    // Close any existing peer connection first
    if (typeof peerConnection !== 'undefined' && peerConnection) {
        peerConnection.close();
        peerConnection = null;
    }
    
    // Detach all old Firebase listeners
    if (typeof hostId !== 'undefined' && hostId && typeof clientId !== 'undefined' && clientId) {
        db.ref('rooms').child(hostId).child('connections').child(clientId).child('answer').off();
        db.ref('rooms').child(hostId).child('connections').child(clientId).child('candidates').child('host').off();
    }

    answerApplied = false;
    pendingIceCandidates = [];
    processedCandidates = new Set();

    const idInput = getEl('host-id').value.trim();
    const passInput = getEl('password').value.trim();
    if (!idInput || !passInput) {
        showToast("Please enter both Host ID and Password.");
        return;
    }
    
    hostId = idInput;
    getEl('login-status').innerText = "Connecting to Host...";
    getEl('login-status').style.color = "#fbbc04";

    if (window.connectionTimeout) clearTimeout(window.connectionTimeout);
    window.connectionTimeout = setTimeout(() => {
        const statusEl = getEl('login-status');
        if (statusEl) {
            statusEl.innerText = 'Connection timed out. Check that the host is online and try again.';
            statusEl.style.color = '#ea4335';
        }
    }, 30000);

    const config = {
        iceServers: [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' },
            { urls: 'stun:stun2.l.google.com:19302' },
            { urls: 'stun:stun3.l.google.com:19302' },
            { urls: 'stun:stun4.l.google.com:19302' },
            {
                urls: 'turn:openrelay.metered.ca:80',
                username: 'openrelayproject',
                credential: 'openrelayproject'
            },
            {
                urls: 'turn:openrelay.metered.ca:443',
                username: 'openrelayproject',
                credential: 'openrelayproject'
            },
            {
                urls: 'turn:openrelay.metered.ca:443?transport=tcp',
                username: 'openrelayproject',
                credential: 'openrelayproject'
            },
            {
                urls: 'turn:openrelay.metered.ca:80?transport=tcp',
                username: 'openrelayproject',
                credential: 'openrelayproject'
            }
        ]
    };

    peerConnection = new RTCPeerConnection(config);
    dataChannel = peerConnection.createDataChannel('fileTransfer'); dataChannel.binaryType = 'arraybuffer';

    dataChannel.onopen = () => {
        if (window.connectionTimeout) clearTimeout(window.connectionTimeout);
        console.log("DataChannel Open!");
        const loginOverlay = getEl('login-overlay');
        if (loginOverlay) loginOverlay.classList.remove('active');
        const appContainer = getEl('app-container');
        if (appContainer) appContainer.classList.remove('hidden');
        // Update username; role will be authenticated from host_info
        const uname = (document.getElementById('username') ? document.getElementById('username').value.trim() : '') || 'admin';
        currentUser.username = uname;
        applyRolePermissions();
        showToast(`Connected as ${currentUser.username}`, "success");
        // We will wait for host_info to perform the initial directory load based on allowedPath
        window.initialLoadDone = false;
        sendData({ action: 'get_storage_stats' });
        sendData({ action: 'get_host_info' });
        sendData({ action: 'get_storage_breakdown' });
    };
    
    dataChannel.onclose = () => {
        console.log("DataChannel Closed!");
        showDisconnected("Connection lost. The host may have closed the app.");
    };

    dataChannel.onerror = (err) => {
        console.log("DataChannel Error:", err);
        showDisconnected("Connection error. Please reconnect.");
    };

    dataChannel.onmessage = (event) => {
        if (typeof event.data === 'string') {
            handleJsonMessage(JSON.parse(event.data));
        } else {
            handleBinaryChunk(event.data);
        }
    };

    peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
            db.ref('rooms').child(hostId).child('connections').child(clientId).child('candidates').child('client').push({
                sdpMLineIndex: event.candidate.sdpMLineIndex,
                sdpMid: event.candidate.sdpMid,
                candidate: event.candidate.candidate
            });
        }
    };

    peerConnection.createOffer().then(offer => peerConnection.setLocalDescription(offer)).then(() => {
        db.ref('rooms').child(hostId).child('connections').child(clientId).child('offer').set({
            sdp: peerConnection.localDescription.sdp,
            type: peerConnection.localDescription.type,
            password: passInput,
            username: (document.getElementById('username') ? document.getElementById('username').value.trim() : 'admin') || 'admin'
        });
    });

    db.ref('rooms').child(hostId).child('connections').child(clientId).child('answer').on('value', async function handleAnswer(snapshot) {
        if (answerApplied) return;
        const val = snapshot.val();
        if (!val) return;
        
        if (val.type === 'access_denied') {
            db.ref('rooms').child(hostId).child('connections').child(clientId).child('answer').off('value', handleAnswer);
            showToast("Incorrect Password!");
            db.ref('rooms').child(hostId).child('connections').child(clientId).child('answer').remove();
            db.ref('rooms').child(hostId).child('connections').child(clientId).child('offer').remove();
            getEl('login-status').innerText = "Access Denied.";
            getEl('login-status').style.color = "#ea4335";
            return;
        }
        
        if (!val.sdp || !val.type) return;
        db.ref('rooms').child(hostId).child('connections').child(clientId).child('answer').off('value', handleAnswer);

        if (peerConnection.signalingState !== 'have-local-offer') return;

        try {
            await peerConnection.setRemoteDescription(new RTCSessionDescription(val));
            answerApplied = true;
            
            for (const candidate of pendingIceCandidates) {
                try { await peerConnection.addIceCandidate(new RTCIceCandidate(candidate)); } catch(e) {}
            }
            pendingIceCandidates = [];
        } catch(e) {
            console.error('setRemoteDescription error:', e);
        }
    });

    db.ref('rooms').child(hostId).child('connections').child(clientId).child('candidates').child('host').on('child_added', async snapshot => {
        const data = snapshot.val();
        if (!data) return;
        
        const key = JSON.stringify(data);
        if (processedCandidates.has(key)) return;
        processedCandidates.add(key);
        
        if (!peerConnection.remoteDescription) {
            pendingIceCandidates.push(data);
        } else {
            try { await peerConnection.addIceCandidate(new RTCIceCandidate(data)); } catch(e) {}
        }
    });
}

function handleJsonMessage(msg) {
    try {
        if (msg.action === 'host_offline') {
            // Host closed the app — show disconnect screen immediately
            showDisconnected("Host has gone offline. Please wait for the host to restart.");
            return;
        } else if (msg.action === 'access_denied') {
            const reason = msg.message || (msg.path ? `Access Denied: ${msg.path}` : 'Operation not permitted');
            showToast(reason, 'error');
            if (msg.path && currentPath === msg.path) {
                if (allowedPath) loadDirectory(allowedPath);
                else loadDirectory('');
            }
        } else if (msg.action === 'auth_success' || msg.action === 'session_info') {
            if (msg.role) setCurrentUserRole(msg.role, msg.username);
            if (msg.token) currentUser.token = msg.token;
            showToast(`Authenticated as ${currentUser.username} (${currentUser.role})`, 'success');
        } else if (msg.action === 'directory_listing') {
            // Accumulate chunked responses before rendering
            const chunk = msg.chunk || 1;
            const total = msg.total_chunks || 1;
            if (chunk === 1) {
                pendingChunks = [];
                pendingPath = msg.path;
                pendingTotalChunks = total;
                if (msg.error) {
                    console.warn('Directory listing error:', msg.error);
                    const flc = getEl('file-list-container');
                    if (flc) {
                        if (isTrashMode || msg.error.includes('.storeme_trash')) {
                            flc.innerHTML = `
                                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 24px;color:#a0aec0;text-align:center;">
                                    <span class="material-icons" style="font-size:64px;opacity:0.5;margin-bottom:16px;">delete_outline</span>
                                    <h3 style="font-size:18px;font-weight:500;margin-bottom:8px;">Trash is empty</h3>
                                    <p style="font-size:14px;max-width:400px;">Items moved to the trash will appear here.</p>
                                </div>`;
                        } else {
                            flc.innerHTML = `
                                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 24px;color:#5f6368;text-align:center;">
                                    <span class="material-icons" style="font-size:64px;color:#EA4335;margin-bottom:16px;">folder_off</span>
                                    <h3 style="font-size:18px;font-weight:500;color:#202124;margin-bottom:8px;">Storage Permission Required</h3>
                                    <p style="font-size:14px;max-width:400px;">On the <strong>host device</strong>, go to:<br>
                                    <strong>Settings → Apps → StoreMe → Permissions → Files &amp; Media → Allow access to all files</strong><br><br>
                                    Then restart the StoreMe server on the host.</p>
                                </div>`;
                        }
                    }
                    return;
                }
            }
            const filesChunk = msg.files || [];
            for (let i = 0; i < filesChunk.length; i++) pendingChunks.push(filesChunk[i]);
            if (chunk >= total) {
                currentPath = pendingPath;
                renderFiles(pendingPath, pendingChunks);
            }
        } else if (msg.action === 'search_results') {
            const results = msg.results || [];
            if (results.length === 0) {
                document.getElementById('file-list').innerHTML = '<div class="empty-state"><span class="material-icons">search_off</span><p>No files found</p></div>';
            } else {
                renderFiles(currentPath, results, true);
            }
        } else if (msg.action === 'file_transfer_start') {
            if (currentTransferIntent === 'download') {
                downloadExpectedSize = msg.size;
                downloadBuffer = [];
                downloadedBytes = 0;
                getEl('dl-filename').innerText = currentDownloadName;
                getEl('download-widget').classList.remove('hidden');
            } else if (currentTransferIntent === 'preview') {
                const c = getEl('preview-content');
                if (previewCategory === 'video') {
                    if (window.MediaSource) {
                        mediaSource = new MediaSource();
                        const vid = document.createElement('video');
                        vid.controls = true;
                        vid.autoplay = true;
                        vid.style.maxWidth = '100%';
                        vid.style.maxHeight = '85vh';
                        vid.style.boxShadow = '0 4px 20px rgba(0,0,0,0.5)';
                        vid.src = URL.createObjectURL(mediaSource);
                        
                        const ext = currentDownloadName.split('.').pop().toLowerCase();
                        const mimeType = ext === 'webm' ? 'video/webm; codecs="vp8,vorbis"' : 'video/mp4; codecs="avc1.42E01E, mp4a.40.2"';

                        mediaSource.addEventListener('sourceopen', () => {
                            try {
                                sourceBuffer = mediaSource.addSourceBuffer(mimeType);
                                sourceBuffer.addEventListener('updateend', () => {
                                    if (mseChunkQueue.length > 0 && !sourceBuffer.updating) {
                                        sourceBuffer.appendBuffer(mseChunkQueue.shift());
                                    }
                                });
                            } catch(e) {
                                console.warn('MSE sourceBuffer failed:', e);
                                sourceBuffer = null;
                                mseChunkQueue = [];
                            }
                        });
                        
                        c.innerHTML = '';
                        c.appendChild(vid);
                        window.currentPreviewUrl = vid.src;
                        
                        vid.onerror = (e) => {
                            console.warn('[Stream] Video element error', vid.error, e);
                            c.innerHTML = '<div style="color:white;text-align:center;padding:48px;">' + 
                                '<span class="material-icons" style="font-size:72px;opacity:0.6;margin-bottom:16px;">videocam_off</span>' + 
                                '<p style="font-size:20px;margin-bottom:8px;">Format Not Supported</p>' + 
                                '<p style="color:rgba(255,255,255,0.7);margin-bottom:24px;">The <b>' + ext.toUpperCase() + '</b> file contains a video codec or format that your browser cannot natively decode.<br>Google Drive bypasses this by using cloud servers to transcode videos, but this is a direct peer-to-peer connection.</p>' + 
                                '<button onclick="downloadFromPreview()" style="padding:12px 32px;background:#1a73e8;color:white;border:none;border-radius:24px;font-size:15px;cursor:pointer;font-weight:500;">Download to Play</button>' +
                                '</div>';
                            
                            window.currentPreviewUrl = "";
                            currentDownloadName = currentDownloadName;
                        };
                    } else {
                        mediaSource = null;
                        c.innerHTML = '<div style="color:white;font-size:20px;">Loading video ' + (typeof formatSize === 'function' ? formatSize(msg.size) : msg.size) + '...</div>';
                    }
                } else if (previewCategory === 'audio') {
            const ext = currentDownloadName.split('.').pop().toLowerCase();
            const blob = new Blob(downloadBuffer, { type: 'audio/' + (ext === 'mp3' ? 'mpeg' : ext) });
            const url = URL.createObjectURL(blob);
            window.currentPreviewUrl = url;
            if (c) {
                c.innerHTML = '<div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;padding:40px;text-align:center;">'
                    + '<span class="material-icons" style="font-size:80px;color:var(--brand-primary);margin-bottom:20px;">audiotrack</span>'
                    + '<p style="font-size:18px;margin-bottom:20px;color:#fff;">' + currentDownloadName + '</p>'
                    + '<audio controls autoplay style="width:100%;max-width:500px;outline:none;" src="' + url + '"></audio>'
                    + '</div>';
            }
        } else if (previewCategory === 'image') {
                    c.innerHTML = '<div id="img-wrap" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%;"><img id="live-img" style="max-width:100%;max-height:85vh;object-fit:contain;box-shadow:0 4px 20px rgba(0,0,0,0.5);" src="" alt="Loading..."></div>';
                } else if (previewCategory === 'text') {
                    c.innerHTML = '<pre id="live-text" style="background:#202124;color:#e8eaed;padding:24px;border-radius:8px;max-width:900px;max-height:85vh;overflow:auto;width:100%;font-family:monospace;white-space:pre-wrap;word-break:break-all;"></pre>';
                } else if (previewCategory === 'pdf') {
                    c.innerHTML = '<div style="color:white;text-align:center;"><div style="width:64px;height:64px;border:4px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p id="fallback-progress" style="color:rgba(255,255,255,0.7);">Loading PDF... 0%</p></div>';
                } else {
                    c.innerHTML = '<div style="color:white;font-size:20px;">Loading ' + (typeof formatSize === 'function' ? formatSize(msg.size) : msg.size) + '...</div>';
                }
            }
        } else if (msg.action === 'file_transfer_end') {
            if (currentTransferIntent === 'download') {
                finishDownload();
            } else if (currentTransferIntent === 'preview') {
                finishPreview();
            }
        } else if (msg.action === 'upload_ack') {
            if (msg.path && msg.path.endsWith('.keep_storeme_web')) {
                sendData({ action: 'delete', path: msg.path });
                setTimeout(() => loadDirectory(currentPath), 1000);
            } else if (currentUploadObj && msg.path === currentUploadObj.destPath && currentUploadObj.file) {
                startChunking(currentUploadObj.file, msg.path, msg.offset || 0);
            }
        } else if (msg.action === 'storage_stats') {
            const total = Number(msg.total) || 1;
            const used = Number(msg.used) || 0;
            const pct = Math.min(100, Math.round((used / total) * 100));
            const pb = getEl('storage-progress-bar');
            if (pb) pb.style.width = pct + '%';
            const st = getEl('storage-text');
            if (st) st.innerText = `${used} GB of ${total} GB used (${pct}%)`;
        } else if (msg.action === 'host_info') {
            if (msg.allowed_path) allowedPath = msg.allowed_path;
            if (msg.role) setCurrentUserRole(msg.role, msg.username);
            const el = getEl('host-info-content');
            if (el) el.innerHTML = "<b>Model:</b> " + (msg.manufacturer || '') + " " + (msg.model || '') + "<br><b>Battery:</b> " + (msg.batteryLevel || '100') + "% " + (msg.isCharging ? '(Charging)' : '') + "<br><b>Status:</b> Online &amp; Connected via WebRTC";
            
            if (!window.initialLoadDone) {
                window.initialLoadDone = true;
                currentPath = allowedPath || '';
                loadDirectory(currentPath);
            }
        } else if (msg.action === 'user_list') {
            if (msg.users && Array.isArray(msg.users)) {
                localUserAccounts = msg.users;
                refreshAdminUsersList();
            }
        } else if (msg.action === 'storage_breakdown') {
            renderStorageDashboard(msg.data);
            updateStorageSegmentedBar(msg.data);
        } else if (msg.action === 'link_created') {
            const tokenMatch = msg.token.match(/token=([a-zA-Z0-9]+)/);
            const pureToken = tokenMatch ? tokenMatch[1] : msg.token;
            const localUrl = window.location.origin + '/?host=' + hostId + '&token=' + pureToken;
            if (navigator.clipboard) {
                navigator.clipboard.writeText(localUrl).then(() => {
                    showToast("✅ Link copied to clipboard!\n\n" + localUrl);
                }).catch(err => {
                    prompt("Copy your share link:", localUrl);
                });
            } else {
                prompt("Copy your share link:", localUrl);
            }
        } else if (msg.action === 'range_data') {
            currentRangeId = msg.requestId;
        } else if (msg.action === 'range_end') {
            const rid = msg.requestId || currentRangeId;
            if (rid && rid.startsWith('direct_dl_')) {
                if (currentDownloadWritable) {
                    currentDownloadWriteQueue = currentDownloadWriteQueue.then(() => {
                        return currentDownloadWritable.close();
                    }).then(() => {
                        currentDownloadWritable = null;
                        getEl('download-widget').classList.add('hidden');
                    }).catch(console.error);
                }
                currentRangeId = null;
                return;
            }
            const port = swPendingRequests.get(rid);
            if (port) {
                port.postMessage({ type: 'end' });
                swPendingRequests.delete(rid);
            }
            currentRangeId = null;
        }
    } catch (e) {
        console.error("Parse error", e);
    }
}

function handleBinaryChunk(buffer) {
    if (currentRangeId) {
        if (currentRangeId.startsWith('direct_dl_')) {
            if (currentDownloadWritable) {
                currentDownloadWriteQueue = currentDownloadWriteQueue.then(() => {
                    return currentDownloadWritable.write(buffer);
                }).catch(console.error);
                
                downloadedBytesTotal += buffer.byteLength;
                const pct = Math.round((downloadedBytesTotal / currentDownloadSize) * 100);
                const elapsedSec = Math.max(0.1, (Date.now() - downloadStartTime) / 1000);
                const speedMBps = (downloadedBytesTotal / (1024 * 1024)) / elapsedSec;
                const dlMB = (downloadedBytesTotal / (1024 * 1024)).toFixed(1);
                const totalMB = (currentDownloadSize / (1024 * 1024)).toFixed(1);
                
                const speed = speedMBps.toFixed(1);
                const dl = dlMB;
                const total = totalMB;
                getEl('dl-progress-bar').style.width = pct + '%';
                getEl('dl-percentage').innerText = pct + '%';
                getEl('dl-speed-size').innerText = `${speed} MB/s - ${dl}/${total} MB`;
            }
            return;
        }
        const port = swPendingRequests.get(currentRangeId);
        if (port) {
            port.postMessage({ type: 'chunk', data: buffer }, [buffer]);
        }
        return;
    }

    downloadedBytes += buffer.byteLength;
    previewChunkCount++;

    if (currentTransferIntent === 'download') {
        downloadBuffer.push(buffer);
        if (downloadExpectedSize > 0) {
            const pct = Math.round((downloadedBytes / downloadExpectedSize) * 100);
            getEl('dl-progress-bar').style.width = pct + '%';
            getEl('dl-percentage').innerText = pct + '%';
            getEl('dl-speed-size').innerText = `${(downloadedBytes / (1024*1024)).toFixed(1)} MB / ${(downloadExpectedSize / (1024*1024)).toFixed(1)} MB`;
        }
        return;
    }

    if (previewCategory === 'video') {
        downloadBuffer.push(buffer); 
        
        if (sourceBuffer) {
            const chunk = new Uint8Array(buffer);
            if (sourceBuffer.updating) {
                mseChunkQueue.push(chunk);
            } else {
                try { sourceBuffer.appendBuffer(chunk); }
                catch(e) {
                    console.warn('MSE appendBuffer error:', e);
                    sourceBuffer = null; 
                    mseChunkQueue = [];
                    const c = getEl('preview-content');
                    if (c && c.innerHTML.includes('<video')) {
                        c.innerHTML = '<div style="color:white;text-align:center;"><div style="width:64px;height:64px;border:4px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p id="fallback-progress" style="color:rgba(255,255,255,0.7);">Buffering video... 0%</p></div>';
                    }
                }
            }
        } else {
            if (downloadExpectedSize > 0) {
                const pct = Math.round((downloadedBytes / downloadExpectedSize) * 100);
                const p = getEl('fallback-progress');
                if (p) p.innerText = 'Buffering video... ' + pct + '%';
            }
        }
    } else if (previewCategory === 'image') {
        downloadBuffer.push(buffer);
        const shouldUpdate = previewChunkCount % 20 === 0 ||
            (downloadExpectedSize > 0 && [25,50,75].includes(Math.round((downloadedBytes / downloadExpectedSize) * 100)));
        if (shouldUpdate) {
            if (prevPreviewBlobUrl) URL.revokeObjectURL(prevPreviewBlobUrl);
            const partial = new Blob(downloadBuffer.slice());
            prevPreviewBlobUrl = URL.createObjectURL(partial);
            const img = getEl('live-img');
            if (img) img.src = prevPreviewBlobUrl;
        }
    } else if (previewCategory === 'text') {
        const pre = getEl('live-text');
        if (pre) {
            pre.textContent += textDecoder.decode(new Uint8Array(buffer), { stream: true });
            pre.scrollTop = pre.scrollHeight; 
        }
    } else if (previewCategory === 'pdf') {
        downloadBuffer.push(buffer);
        if (downloadExpectedSize > 0) {
            const pct = Math.round((downloadedBytes / downloadExpectedSize) * 100);
            const p = getEl('fallback-progress');
            if (p) p.innerText = 'Loading PDF... ' + pct + '%';
        }
    } else {
        downloadBuffer.push(buffer);
        if (downloadExpectedSize > 0) {
            const pct = Math.round((downloadedBytes / downloadExpectedSize) * 100);
            const c = getEl('preview-content');
            if (c) {
                const spinner = c.querySelector('p');
                if (spinner && (previewCategory === 'video' || previewCategory === 'other')) {
                    spinner.textContent = pct < 100 ? `Buffering... ${pct}%` : 'Almost ready...';
                }
            }
        }
    }
}


function finishDownload() {
    getEl('download-widget').classList.add('hidden');
    const blob = new Blob(downloadBuffer);
    const url = URL.createObjectURL(blob);
    const a = getEl('download-anchor');
    if (a) {
        a.href = url;
        a.download = currentDownloadName;
        a.click();
    }
    URL.revokeObjectURL(url);
    downloadBuffer = [];
}

function finishPreview() {
    try {
        const c = getEl('preview-content');
        const sb = sourceBuffer; 
        const ms = mediaSource;

        if (previewCategory === 'video' && sb) {
            const tryEnd = () => {
                try {
                    if (!sb.updating && mseChunkQueue.length === 0) {
                        if (ms && ms.readyState === 'open') ms.endOfStream();
                    } else {
                        setTimeout(tryEnd, 80);
                    }
                } catch(e) { console.warn('MSE endOfStream error:', e); }
            };
            tryEnd();
        } else if (previewCategory === 'video') {
            const blob = new Blob(downloadBuffer, { type: 'video/mp4' });
            const url = URL.createObjectURL(blob);
            window.currentPreviewUrl = url;
            if (c) {
                c.innerHTML = '';
                const vid = document.createElement('video');
                vid.controls = true;
                vid.autoplay = true;
                vid.src = url;
                vid.style.cssText = 'width:100%;height:100%;background:#000;outline:none;';
                c.appendChild(vid);
            }
        } else if (previewCategory === 'image') {
            if (prevPreviewBlobUrl) { URL.revokeObjectURL(prevPreviewBlobUrl); prevPreviewBlobUrl = null; }
            const blob = new Blob(downloadBuffer);
            const url = URL.createObjectURL(blob);
            window.currentPreviewUrl = url;
            const img = getEl('live-img');
            if (img) {
                img.src = url;
            } else if (c) {
                c.innerHTML = '<img src="' + url + '" alt="' + currentDownloadName + '">';
            }
        } else if (previewCategory === 'text') {
            const pre = getEl('live-text');
            if (pre) pre.textContent += textDecoder.decode(); 
        } else if (previewCategory === 'pdf') {
            const blob = new Blob(downloadBuffer, { type: 'application/pdf' });
            const url = URL.createObjectURL(blob);
            window.currentPreviewUrl = url;
            if (c) {
                c.innerHTML = `<iframe src="${url}" style="width:100%;height:100%;border:none;background:#fff;outline:none;"></iframe>`;
            }
        } else {
            const blob = new Blob(downloadBuffer);
            const url = URL.createObjectURL(blob);
            window.currentPreviewUrl = url;
            if (c) {
                c.innerHTML = '<div style="color:white;text-align:center;padding:48px;">'
                    + '<span class="material-icons" style="font-size:72px;opacity:0.6;">insert_drive_file</span>'
                    + '<p style="margin:16px 0;font-size:18px;">' + currentDownloadName + '</p>'
                    + '<button onclick="downloadFromPreview()" style="padding:12px 32px;background:#1a73e8;color:white;border:none;border-radius:24px;font-size:15px;cursor:pointer;font-weight:500;">Download File</button>'
                    + '</div>';
            }
        }
        const pf = getEl('preview-filename');
        if (pf) pf.innerText = currentDownloadName;
    } catch (err) {
        const c = getEl('preview-content');
        if (c) c.innerHTML = '<div style="color:#f28b82;font-size:18px;padding:32px;text-align:center;">Ã¢Å¡Â Ã¯Â¸Â  Preview error: ' + err.message + '</div>';
        console.error('finishPreview error:', err);
    } finally {
        downloadBuffer = [];
        sourceBuffer = null;
        mseChunkQueue = [];
    }
}

function closePreview() {
    const modal = getEl('preview-modal');
    if (modal) modal.style.display = 'none';
    const c = getEl('preview-content');
    if (c) {
        const vid = c.querySelector('video');
        if (vid) { vid.pause(); vid.src = ''; }
        c.innerHTML = '';
    }
    if (window.currentPreviewUrl) {
        URL.revokeObjectURL(window.currentPreviewUrl);
        window.currentPreviewUrl = null;
    }
    if (prevPreviewBlobUrl) {
        URL.revokeObjectURL(prevPreviewBlobUrl);
        prevPreviewBlobUrl = null;
    }
    mediaSource = null;
    sourceBuffer = null;
    mseChunkQueue = [];
    previewCategory = '';
}

function downloadFromPreview() {
    if (window.currentPreviewUrl && currentDownloadName) {
        const a = getEl('download-anchor');
        if (a) {
            a.href = window.currentPreviewUrl;
            a.download = currentDownloadName;
            a.click();
        }
    }
}

function loadDirectory(path) {
window.loadDirectory = loadDirectory;
    currentPath = path;
    // Show loading state
    const flc = getEl('file-list-container');
    if (flc) flc.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;padding:48px;color:#5f6368;"><span class="material-icons" style="margin-right:8px;animation:spin 1s linear infinite;">refresh</span> Loading...</div>';
    sendData({ action: 'list_dir', path: path });
}

function fetchHostInfo() {
    const modal = getEl('host-info-modal');
    if (modal) modal.style.display = 'flex';
    const el = getEl('host-info-content');
    if (el) el.innerHTML = '<p style="color:#5f6368;">Fetching device info...</p>';
    sendData({ action: 'get_host_info' });
    sendData({ action: 'get_storage_stats' });
}

function getStarred() {
    try { return JSON.parse(localStorage.getItem('storeme_starred') || '[]'); }
    catch(e) { return []; }
}

function saveStarred(arr) {
    localStorage.setItem('storeme_starred', JSON.stringify(arr));
}

function loadTrash() {
    isTrashMode = true;
    isStarredMode = false;
    loadDirectory('.storeme_trash');
}

function loadStarred() {
    isStarredMode = true;
    isTrashMode = false;
    currentPath = 'Starred';
    renderFiles('Starred', getStarred());
}

let currentSortColumn = 'name';
let currentSortAsc = true;

function sortByColumn(col) {
    if (currentSortColumn === col) {
        currentSortAsc = !currentSortAsc;
    } else {
        currentSortColumn = col;
        currentSortAsc = true;
    }
    
    ['name', 'modified', 'size'].forEach(c => {
        const icon = getEl(`sort-icon-${c}`);
        if (icon) {
            if (c === currentSortColumn) {
                icon.innerText = currentSortAsc ? 'arrow_upward' : 'arrow_downward';
                icon.style.opacity = '1';
                icon.style.color = 'var(--brand-primary)';
            } else {
                icon.innerText = 'unfold_more';
                icon.style.opacity = '0.6';
                icon.style.color = '';
            }
        }
    });

    if (currentFilesList && currentFilesList.length > 0) {
        currentFilesList.sort((a, b) => {
            if (a.isDir && !b.isDir) return -1;
            if (!a.isDir && b.isDir) return 1;
            
            let valA = a[col] || '';
            let valB = b[col] || '';
            
            if (col === 'size') {
                valA = Number(a.size || 0);
                valB = Number(b.size || 0);
                return currentSortAsc ? valA - valB : valB - valA;
            }
            if (col === 'modified') {
                valA = new Date(a.modified || a.modifiedTime || 0).getTime();
                valB = new Date(b.modified || b.modifiedTime || 0).getTime();
                return currentSortAsc ? valA - valB : valB - valA;
            }
            return currentSortAsc ? String(valA).localeCompare(String(valB)) : String(valB).localeCompare(String(valA));
        });
        renderFiles(currentPath, currentFilesList);
    }
}
window.sortByColumn = sortByColumn;

function updateActionBar() {
    const topBar = getEl('top-batch-bar');
    const listHeader = getEl('list-header');
    const legacyBar = getEl('action-bar');
    const sc = getEl('selection-count');
    const selectAllCb = getEl('select-all');
    const batchSelectAll = getEl('batch-select-all');
    
    if (selectedFiles.size > 0) {
        if (topBar) topBar.classList.add('active');
        if (listHeader) listHeader.style.display = 'none';
        if (legacyBar) legacyBar.classList.add('active');
        if (sc) sc.innerText = `${selectedFiles.size} item${selectedFiles.size > 1 ? 's' : ''} selected`;
        if (batchSelectAll) batchSelectAll.checked = true;
        
        // RBAC adaptation for batch buttons
        const batchDelete = getEl('batch-btn-delete');
        const batchMove = getEl('batch-btn-move');
        if (batchDelete) {
            batchDelete.style.display = (currentUser.role === 'Viewer') ? 'none' : 'inline-flex';
        }
        if (batchMove) {
            batchMove.style.display = (currentUser.role === 'Viewer') ? 'none' : 'inline-flex';
        }
    } else {
        if (topBar) topBar.classList.remove('active');
        if (listHeader) listHeader.style.display = 'flex';
        if (legacyBar) legacyBar.classList.remove('active');
        if (selectAllCb) selectAllCb.checked = false;
        if (batchSelectAll) batchSelectAll.checked = false;
    }
}

function clearSelection() {
    selectedFiles.clear();
    document.querySelectorAll('.file-row').forEach(r => {
        r.classList.remove('selected');
        const cb = r.querySelector('input[type="checkbox"]');
        if (cb) cb.checked = false;
    });
    updateActionBar();
    const selectAllCb = getEl('select-all');
    if (selectAllCb) selectAllCb.checked = false;
}

function toggleSelectAll(event) {
    const isChecked = event.target.checked;
    selectedFiles.clear();
    
    if (isChecked) {
        currentFilesList.forEach(f => {
            if (f.name !== '.storeme_trash' && !f.name.endsWith('.keep_storeme_web') && !f.isBack) {
                selectedFiles.add(f);
            }
        });
    }
    
    document.querySelectorAll('.file-row').forEach(row => {
        const cb = row.querySelector('input[type="checkbox"]');
        if (cb) cb.checked = isChecked;
        if (isChecked) {
            row.classList.add('selected');
        } else {
            row.classList.remove('selected');
        }
    });
    updateActionBar();
}

function toggleSelection(f, row, checkbox) {
    if (selectedFiles.has(f)) {
        selectedFiles.delete(f);
        row.classList.remove('selected');
        if (checkbox) checkbox.checked = false;
    } else {
        selectedFiles.add(f);
        row.classList.add('selected');
        if (checkbox) checkbox.checked = true;
    }
    updateActionBar();
    if (selectedFiles.size === 1) showDetails(Array.from(selectedFiles)[0]);
    else closeDetails();
}

function updateContextMenuVisibility(file) {
    const ctxRename = getEl('ctx-rename');
    const ctxMove = getEl('ctx-move');
    const ctxDelete = getEl('ctx-delete');
    const ctxDivider = getEl('ctx-divider');
    
    if (currentUser.role === 'Viewer') {
        if (ctxRename) ctxRename.style.display = 'none';
        if (ctxMove) ctxMove.style.display = 'none';
        if (ctxDelete) ctxDelete.style.display = 'none';
        if (ctxDivider) ctxDivider.style.display = 'none';
    } else if (currentUser.role === 'Uploader') {
        const canDelete = file ? canUserDeleteFile(file) : true;
        const canModify = file ? canUserModifyFile(file) : true;
        if (ctxRename) ctxRename.style.display = canModify ? 'flex' : 'none';
        if (ctxMove) ctxMove.style.display = canModify ? 'flex' : 'none';
        if (ctxDelete) ctxDelete.style.display = canDelete ? 'flex' : 'none';
        if (ctxDivider) ctxDivider.style.display = (canDelete || canModify) ? 'block' : 'none';
    } else { // Admin
        if (ctxRename) ctxRename.style.display = 'flex';
        if (ctxMove) ctxMove.style.display = 'flex';
        if (ctxDelete) ctxDelete.style.display = 'flex';
        if (ctxDivider) ctxDivider.style.display = 'block';
    }
}

function renderFiles(path, files, isSearch = false) {
    if (!isSearch) {
        allFilesInCurrentDir = [...files];
    }
    currentFilesList = files;
    const fileListContainer = getEl('fileListContainer') || getEl('file-list-container');
    const pathBar = getEl('pathBar') || getEl('path-bar');
    
    if (fileListContainer) fileListContainer.innerHTML = '';
    clearSelection();
    
    let html = '';
    if (isStarredMode) {
        html = '<span class="breadcrumb">Starred</span>';
    } else if (isTrashMode) {
        html = '<span class="breadcrumb">Trash</span>';
    } else {
        let displayPath = path;
        const driveRoot = allowedPath ? allowedPath : '';
        if (allowedPath && path.startsWith(allowedPath)) {
            displayPath = path.substring(allowedPath.length);
        }
        
        const parts = displayPath.split('/').filter(p => p.length > 0);
        
        if (parts.length > 0) {
            const fullParts = path.split('/').filter(p => p.length > 0);
            let parentPath = fullParts.slice(0, -1).join('/');
            if (path.startsWith('/') && !parentPath.startsWith('/')) parentPath = '/' + parentPath;
            html += '<span class="material-icons" style="cursor:pointer; margin-right:8px; vertical-align:middle;" onclick="loadDirectory(\'' + parentPath + '\')" title="Go up">arrow_upward</span>';
        }
        
        const isShareUI = new URLSearchParams(window.location.search).has('token');
        html += '<span class="breadcrumb" onclick="loadDirectory(\'' + driveRoot + '\')">' + (isShareUI ? 'Shared Files' : 'My Drive') + '</span>';
        let cur = allowedPath ? allowedPath : '';
        if (cur.endsWith('/')) cur = cur.slice(0, -1);
        
        parts.forEach(p => {
            cur += '/' + p;
            const currentLocal = cur;
            html += '<span class="material-icons" style="font-size: 16px; margin: 0 4px; color: var(--text-muted); vertical-align: middle;">chevron_right</span><span class="breadcrumb" onclick="loadDirectory(\'' + currentLocal + '\')">' + p + '</span>';
        });
    }
    if (pathBar) pathBar.innerHTML = html;

    files.sort((a,b) => {
        if(a.isDir && !b.isDir) return -1;
        if(!a.isDir && b.isDir) return 1;
        return a.name.localeCompare(b.name);
    });

    const stars = getStarred();

    files.forEach(f => {
        if (f.isBack || f.name === '.storeme_trash' || f.name.endsWith('.keep_storeme_web')) return;
        const row = document.createElement('div');
        row.className = 'file-row';
        
        const isDir = f.isDir === true || f.isDir === 'true';
        const fileInfo = getFileInfo(f.name, isDir);
        const ownerName = f.owner || f.uploadedBy || 'admin';
        const modifiedStr = formatDate(f.modified || f.modifiedTime || f.mtime);
        const formattedSize = isDir ? '—' : (typeof formatSize === 'function' ? formatSize(f.size) : f.size);
        
        const isStarred = stars.some(s => s.path === f.path);
        const starIcon = isStarred ? `<span class="material-icons" style="color:#f59e0b;font-size:16px;">star</span>` : '';
        
        let rowActionsHtml = `
            <button class="row-action-btn" onclick="event.stopPropagation(); window.shareFile('${f.path}')" title="Share"><span class="material-icons" style="font-size:16px;">share</span></button>
            <button class="row-action-btn" onclick="event.stopPropagation(); window.downloadFile('${f.path}')" title="Download"><span class="material-icons" style="font-size:16px;">file_download</span></button>
        `;
        if (canUserDeleteFile(f)) {
            rowActionsHtml += `
                <button class="row-action-btn btn-danger-action" onclick="event.stopPropagation(); window.deleteFile('${f.path}')" title="Delete"><span class="material-icons" style="font-size:16px; color: var(--status-danger);">delete</span></button>
            `;
        }
        
        row.innerHTML = `
            <div class="col-check" onclick="event.stopPropagation()">
                <input type="checkbox" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--brand-primary);">
            </div>
            <div class="col-name" style="display:flex; align-items:center; gap:8px;">
                <span class="material-icons file-type-icon" style="color:${fileInfo.color}; font-size:20px; flex-shrink:0;">${fileInfo.icon}</span>
                <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:500;">${f.name}</span>
                ${starIcon}
            </div>
            <div class="col-owner" title="${ownerName}">${ownerName}</div>
            <div class="col-modified">${modifiedStr}</div>
            <div class="col-size">${formattedSize}</div>
            <div class="col-actions">
                <div class="row-actions">${rowActionsHtml}</div>
            </div>
        `;

        const checkbox = row.querySelector('input[type="checkbox"]');
        if (checkbox) checkbox.onchange = (e) => toggleSelection(f, row, checkbox);

        row.onclick = (e) => {
            if (e.shiftKey || e.ctrlKey || e.metaKey) {
                toggleSelection(f, row, checkbox);
            } else {
                clearSelection();
                toggleSelection(f, row, checkbox);
            }
        };

        row.ondblclick = () => {
            if (f.isDir) {
                if (isStarredMode) {
                    isStarredMode = false;
                    document.querySelectorAll('.nav-links li').forEach(l=>l.classList.remove('active'));
                }
                loadDirectory(f.path);
            } else {
                startDownload(f, 'preview');
            }
        };

        row.oncontextmenu = (e) => {
            e.preventDefault();
            if (!selectedFiles.has(f)) {
                clearSelection();
                toggleSelection(f, row, checkbox);
            }
            updateContextMenuVisibility(f);
            const contextMenu = getEl('contextMenu') || getEl('context-menu');
            if (contextMenu) {
                contextMenu.style.left = e.pageX + 'px';
                contextMenu.style.top = e.pageY + 'px';
                contextMenu.classList.add('active');
            }
        };

        if (fileListContainer) fileListContainer.appendChild(row);
    });

    if (fileListContainer && fileListContainer.children.length === 0) {
        if (isTrashMode) {
            fileListContainer.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 24px;color:var(--text-muted);text-align:center;">
                    <span class="material-icons" style="font-size:56px;opacity:0.4;margin-bottom:12px;">delete_outline</span>
                    <div style="font-size:16px;font-weight:600;margin-bottom:6px;color:var(--text-primary);">Trash is empty</div>
                    <div style="font-size:13px;">Items moved to trash will appear here.</div>
                </div>
            `;
        } else if (isStarredMode) {
            fileListContainer.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 24px;color:var(--text-muted);text-align:center;">
                    <span class="material-icons" style="font-size:56px;opacity:0.4;margin-bottom:12px;">star_border</span>
                    <div style="font-size:16px;font-weight:600;margin-bottom:6px;color:var(--text-primary);">No starred files</div>
                    <div style="font-size:13px;">Add stars to files you want to find quickly.</div>
                </div>
            `;
        } else {
            fileListContainer.innerHTML = `
                <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 24px;color:var(--text-muted);text-align:center;">
                    <span class="material-icons" style="font-size:56px;opacity:0.4;margin-bottom:12px;">folder_open</span>
                    <div style="font-size:16px;font-weight:600;margin-bottom:6px;color:var(--text-primary);">This folder is empty</div>
                    <div style="font-size:13px;">${currentUser.role === 'Viewer' ? 'No files stored in this directory.' : 'Drag files here or click New to upload.'}</div>
                </div>
            `;
        }
    }
}

async function multiAction(action) {
    if (selectedFiles.size === 0) return;
    const files = Array.from(selectedFiles);
    
    if (action === 'delete') {
        if (currentUser.role === 'Viewer') {
            showToast("Access Denied: Viewer role cannot delete files.", "error");
            return;
        }
        let filesToDelete = files;
        if (currentUser.role === 'Uploader') {
            const unowned = files.filter(f => !canUserDeleteFile(f));
            if (unowned.length > 0) {
                showToast(`Access Denied: Cannot delete ${unowned.length} item(s) owned by another user.`, "error");
                filesToDelete = files.filter(f => canUserDeleteFile(f));
                if (filesToDelete.length === 0) return;
            }
        }
        const count = filesToDelete.length;
        const confirmed = await showDestructiveConfirm(
            `Delete ${count} Item${count > 1 ? 's' : ''}`,
            `Are you sure you want to delete <strong>${count} item${count > 1 ? 's' : ''}</strong>? This action will move them to the trash.`,
            "Delete"
        );
        if (confirmed) {
            filesToDelete.forEach(f => sendData({ action: 'delete', path: f.path }));
            showToast(`Moved ${count} item${count > 1 ? 's' : ''} to trash.`, 'info');
            clearSelection();
            setTimeout(() => isStarredMode ? loadStarred() : loadDirectory(currentPath), 1000);
        }
    } else if (action === 'move') {
        if (currentUser.role === 'Viewer') {
            showToast("Access Denied: Viewer role cannot move files.", "error");
            return;
        }
        if (currentUser.role === 'Uploader') {
            const unowned = files.filter(f => !canUserModifyFile(f));
            if (unowned.length > 0) {
                showToast(`Access Denied: Cannot move files owned by another user.`, "error");
                return;
            }
        }
        const modal = document.getElementById('move-modal');
        const select = document.getElementById('move-destination-select');
        let opts = '';
        if (currentPath && currentPath !== '/' && currentPath !== '') {
            const parent = currentPath.split('/').slice(0, -1).join('/') || '';
            opts += `<option value="${parent}">.. (Parent Directory)</option>`;
        }
        opts += `<option value="">My Drive (Root)</option>`;
        const dirs = allFilesInCurrentDir.filter(f => f.isDir);
        dirs.forEach(d => {
            opts += `<option value="${d.path}">${d.name}</option>`;
        });
        select.innerHTML = opts;
        modal.style.display = 'flex';
    } else if (action === 'download') {
        const choice = await showCustomPrompt("Download as ZIP or Sequentially?", "ZIP");
        if (choice && choice.toUpperCase() === 'ZIP') {
            sendData({ action: 'download_zip', paths: files.map(f => f.path) });
        } else if (choice) {
            files.forEach(f => {
                if (!f.isDir) startDownload(f, 'download');
            });
        }
    } else if (action === 'share') {
        sendData({ action: 'create_link', paths: files.map(f => f.path) });
    } else if (action === 'star') {
        let stars = getStarred();
        let added = 0;
        let removed = 0;
        files.forEach(f => {
            const index = stars.findIndex(s => s.path === f.path);
            if (index > -1) {
                stars.splice(index, 1);
                removed++;
            } else {
                stars.push(f);
                added++;
            }
        });
        saveStarred(stars);
        showToast(`Starred: ${added} added, ${removed} removed.`);
        if (isStarredMode) {
            loadStarred();
        } else {
            renderFiles(currentPath, currentFilesList);
        }
    }
}

async function contextAction(action) {
    if (selectedFiles.size === 0) return;
    const files = Array.from(selectedFiles);

    if (action === 'rename') {
        if (currentUser.role === 'Viewer') {
            showToast("Access Denied: Viewer role cannot rename files.", "error");
            return;
        }
        if (files.length > 1) {
            showToast("Cannot rename multiple files at once.", "warning");
            return;
        }
        const f = files[0];
        if (!canUserModifyFile(f)) {
            showToast("Access Denied: Cannot rename files owned by another user.", "error");
            return;
        }
        const newName = await showCustomPrompt("Enter new name for " + f.name, f.name);
        if (newName && newName !== f.name) {
            sendData({ action: 'rename', path: f.path, newName: newName });
            setTimeout(() => loadDirectory(currentPath), 1000);
        }
    } else if (action === 'preview') {
        if (files.length > 1) {
            showToast("Cannot preview multiple files at once.", "warning");
            return;
        }
        const f = files[0];
        if (!f.isDir) startDownload(f, 'preview');
    } else {
        multiAction(action);
    }
}

function showDetails(f) {
    selectedFile = f;
    const detailsPanel = getEl('detailsPanel') || getEl('details-panel');
    if (detailsPanel) detailsPanel.classList.add('active');
    
    const info = getFileInfo(f.name, f.isDir);
    const dn = getEl('detail-name'); if (dn) dn.innerText = f.name;
    const dt = getEl('detail-type'); if (dt) dt.innerText = info.type;
    const ds = getEl('detail-size'); if (ds) ds.innerText = f.isDir ? '—' : (typeof formatSize === 'function' ? formatSize(f.size) : f.size);
    const dOwner = getEl('detail-owner'); if (dOwner) dOwner.innerText = f.owner || f.uploadedBy || 'admin';
    const di = getEl('detail-icon'); if (di) di.innerHTML = `<span class="material-icons" style="font-size: 56px; color: ${info.color};">${info.icon}</span>`;
    
    const btn = getEl('btn-download');
    if (btn) {
        if (!f.isDir) { btn.style.display = 'flex'; btn.onclick = () => startDownload(f, 'download'); }
        else { btn.style.display = 'none'; }
    }
    const dp = getEl('download-progress'); if (dp) dp.innerText = '';
}

function closeDetails() {
    const detailsPanel = getEl('detailsPanel') || getEl('details-panel');
    if (detailsPanel) detailsPanel.classList.remove('active');
}

function startDownload(file, intent = 'download') {
    currentDownloadName = file.name;
    currentTransferIntent = intent;

    if (intent === 'preview') {
        previewCategory = (typeof getFileCategory === 'function') ? getFileCategory(file.name) : 'other';

        downloadBuffer = [];
        downloadedBytes = 0;
        mediaSource = null;
        sourceBuffer = null;
        mseChunkQueue = [];
        previewChunkCount = 0;
        currentRangeId = null;
        if (prevPreviewBlobUrl) { URL.revokeObjectURL(prevPreviewBlobUrl); prevPreviewBlobUrl = null; }
        if (window.currentPreviewUrl) { URL.revokeObjectURL(window.currentPreviewUrl); window.currentPreviewUrl = null; }

        const c = getEl('preview-content');
        const pf = getEl('preview-filename'); if (pf) pf.innerText = file.name;
        const pm = getEl('preview-modal'); if (pm) pm.style.display = 'flex';

        if (previewCategory === 'video' && swReady) {
            currentStreamPath = file.path;
            currentStreamSize = file.size;
            currentStreamName = file.name;

            const ext   = file.name.split('.').pop().toLowerCase();
            const mime  = ext === 'webm' ? 'video/webm' : 'video/mp4';
            const reqId = 'stream_' + Date.now();

            const streamUrl = `./stream/${reqId}?size=${file.size}&name=${encodeURIComponent(file.name)}&mime=${encodeURIComponent(mime)}`;

            if (c) {
                c.innerHTML = '';
                const vid = document.createElement('video');
                vid.controls = true;
                vid.autoplay = true;
                vid.style.cssText = 'width:100%;height:100%;background:#000;outline:none;';
                vid.src = streamUrl;
                c.appendChild(vid);

                vid.onerror = (e) => {
                    console.warn('[Stream] Video element error', vid.error, e);
                    c.innerHTML = '<div style="color:white;text-align:center;padding:48px;">' + 
                        '<span class="material-icons" style="font-size:72px;opacity:0.6;margin-bottom:16px;">videocam_off</span>' + 
                        '<p style="font-size:20px;margin-bottom:8px;">Format Not Supported</p>' + 
                        '<p style="color:rgba(255,255,255,0.7);margin-bottom:24px;">The <b>' + ext.toUpperCase() + '</b> file contains a video codec or format that your browser cannot natively decode.<br>Google Drive bypasses this by using cloud servers to transcode videos, but this is a direct peer-to-peer connection.</p>' + 
                        '<button onclick="downloadFromPreview()" style="padding:12px 32px;background:#1a73e8;color:white;border:none;border-radius:24px;font-size:15px;cursor:pointer;font-weight:500;">Download to Play</button>' +
                        '</div>';
                    
                    window.currentPreviewUrl = streamUrl;
                    currentDownloadName = file.name;
                };
            }
            return;
        }

        if (c) {
            if (previewCategory === 'image') {
                c.innerHTML = '<div id="img-wrap" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%;"><div style="color:white;text-align:center;"><div style="width:64px;height:64px;border:4px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p style="color:rgba(255,255,255,0.7);">Loading image...</p></div></div>';
            } else if (previewCategory === 'text') {
                c.innerHTML = '<pre id="live-text" style="background:#202124;color:#e8eaed;padding:24px;border-radius:8px;max-width:900px;max-height:85vh;overflow:auto;width:100%;font-family:monospace;white-space:pre-wrap;word-break:break-all;"></pre>';
            } else if (previewCategory === 'video') {
                c.innerHTML = '<div style="color:white;text-align:center;"><div style="width:64px;height:64px;border:4px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p id="fallback-progress" style="color:rgba(255,255,255,0.7);">Buffering video...</p></div>';
            } else {
                c.innerHTML = '<div style="color:white;text-align:center;"><div style="width:64px;height:64px;border:4px solid rgba(255,255,255,0.3);border-top-color:white;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p style="color:rgba(255,255,255,0.7);">Loading ' + (typeof formatSize === 'function' ? formatSize(file.size) : file.size) + '...</p></div>';
            }
        }
        // *** CRITICAL: Actually request the file from the host over WebRTC ***
        sendData({ action: 'stream', path: file.path, offset: 0 });
    } else {
        startDirectDownload(file);
    }
}

async function startDirectDownload(file) {
    if (window.showSaveFilePicker) {
        try {
            currentDownloadHandle = await window.showSaveFilePicker({ suggestedName: file.name });
            currentDownloadWritable = await currentDownloadHandle.createWritable();
            downloadStartTime = Date.now();
            downloadedBytesTotal = 0;
            currentDownloadSize = file.size;
            currentDownloadWriteQueue = Promise.resolve();
            
            getEl('dl-filename').innerText = file.name;
            getEl('download-widget').classList.remove('hidden');
            
            const reqId = 'direct_dl_' + Date.now();
            currentRangeId = reqId;
            sendData({
                action: 'download_range',
                path: file.path,
                requestId: reqId,
                start: 0,
                length: file.size
            });
            return;
        } catch (e) {
            console.error(e);
            const dp = getEl('download-progress'); if (dp) dp.innerText = 'Download cancelled or failed.';
            setTimeout(() => { const dp = getEl('download-progress'); if (dp) dp.innerText = ''; }, 3000);
            return;
        }
    }
    
    const dp = getEl('download-progress'); if (dp) dp.innerText = 'Starting RAM buffer download...';
    sendData({ action: 'download', path: file.path, offset: 0 });
}

async function createNewFolder() {
    if (currentUser.role === 'Viewer') {
        showToast("Access Denied: Viewer role cannot create folders.", "error");
        return;
    }
    const name = await showCustomPrompt('Folder name:');
    if (!name || !name.trim()) return;
    const folderPath = (currentPath ? currentPath + '/' : '') + name.trim();
    const dummyPath = folderPath + '/.keep_storeme_web';
    sendData({ action: 'upload_start', path: dummyPath, size: 0 });
    setTimeout(() => loadDirectory(currentPath), 1500);
}

function uploadFile(event) {
    if (currentUser.role === 'Viewer') {
        showToast("Access Denied: Viewer role cannot upload files.", "error");
        if (event && event.target) event.target.value = '';
        return;
    }
    const files = event.target.files;
    if (!files || files.length === 0) return;

    for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const destPath = (currentPath ? currentPath + '/' : '') + file.name;
        uploadQueue.push({ id: Date.now() + i, file: file, destPath: destPath, status: 'pending', loaded: 0, size: file.size, startTime: Date.now() });
    }
    event.target.value = '';
    uploadCancelled = false;
    renderUploadQueue();
    processNextUpload();
}

function processNextUpload() {
    if (currentUploadObj && currentUploadObj.status === 'uploading') return;
    const next = uploadQueue.find(u => u.status === 'pending');
    if (!next) return;
    currentUploadObj = next;
    currentUploadObj.status = 'uploading';
    currentUploadObj.startTime = Date.now();
    renderUploadQueue();
    sendData({ action: 'upload_start', path: currentUploadObj.destPath, size: currentUploadObj.size });
}

function toggleUploadDrawerMinimize() {
    const drawer = getEl('upload-panel');
    const minIcon = getEl('upload-drawer-minimize');
    if (drawer) {
        drawer.classList.toggle('minimized');
        if (minIcon) {
            minIcon.innerText = drawer.classList.contains('minimized') ? 'expand_less' : 'expand_more';
        }
    }
}
window.toggleUploadDrawerMinimize = toggleUploadDrawerMinimize;

function closeUploadDrawer() {
    const drawer = getEl('upload-panel');
    if (drawer) drawer.style.display = 'none';
}
window.closeUploadDrawer = closeUploadDrawer;

function cancelSingleUpload(id) {
    const item = uploadQueue.find(u => u.id === id);
    if (!item) return;
    if (item.status === 'uploading') {
        cancelUpload();
    } else {
        uploadQueue = uploadQueue.filter(u => u.id !== id);
        renderUploadQueue();
    }
}
window.cancelSingleUpload = cancelSingleUpload;

function renderUploadQueue() {
    const panel = getEl('upload-panel');
    const itemsContainer = getEl('upload-drawer-items');
    const titleEl = getEl('upload-drawer-title');
    if (!panel) return;
    
    if (uploadQueue.length === 0) {
        panel.style.display = 'none';
        return;
    }
    panel.style.display = 'flex';
    
    const activeUploads = uploadQueue.filter(u => u.status === 'uploading' || u.status === 'pending');
    if (titleEl) {
        if (activeUploads.length > 0) {
            titleEl.innerText = `Uploading ${uploadQueue.length} file(s)...`;
        } else {
            titleEl.innerText = `All uploads completed (${uploadQueue.length})`;
        }
    }
    
    if (!itemsContainer) return;
    
    let html = '';
    uploadQueue.forEach(u => {
        let pct = u.size > 0 ? Math.round((u.loaded / u.size) * 100) : 0;
        if (u.status === 'done') pct = 100;
        
        let statusText = `${pct}%`;
        let speedText = '';
        if (u.status === 'uploading') {
            const elapsed = (Date.now() - (u.startTime || Date.now())) / 1000;
            if (elapsed > 0 && u.loaded > 0) {
                const speedBytes = u.loaded / elapsed;
                speedText = (speedBytes / (1024 * 1024)).toFixed(1) + ' MB/s';
            }
        } else if (u.status === 'pending') {
            statusText = 'Queued';
        } else if (u.status === 'done') {
            statusText = 'Completed';
        }
        
        const isDone = u.status === 'done';
        const fileInfo = getFileInfo(u.file.name, false);
        
        html += `
            <div class="upload-drawer-item">
                <span class="material-icons upload-item-icon" style="color: ${isDone ? 'var(--status-success)' : 'var(--brand-primary)'}; font-size: 22px;">
                    ${isDone ? 'check_circle' : fileInfo.icon}
                </span>
                <div class="upload-item-details">
                    <div class="upload-item-name" title="${u.file.name}">${u.file.name}</div>
                    <div class="upload-item-progress-bar">
                        <div class="upload-item-progress-fill ${isDone ? 'done' : ''}" style="width:${pct}%"></div>
                    </div>
                    <div class="upload-item-sub">
                        <span>${speedText ? speedText + ' &bull; ' : ''}${formatSize(u.loaded)} / ${formatSize(u.size)}</span>
                        <span>${statusText}</span>
                    </div>
                </div>
                ${isDone ? '' : `
                    <span class="material-icons upload-item-cancel" onclick="cancelSingleUpload(${u.id})" title="Cancel">cancel</span>
                `}
            </div>
        `;
    });
    
    itemsContainer.innerHTML = html;
}

async function startChunking(file, path, startOffset) {
    if (uploadCancelled) return;
    let offset = startOffset || 0;
    const BLOCK_SIZE = 2 * 1024 * 1024;
    const CHUNK_SIZE = 65536;

    while (offset < file.size && !uploadCancelled) {
        const slice = file.slice(offset, offset + BLOCK_SIZE);
        const arrayBuffer = await slice.arrayBuffer();
        
        let blockOffset = 0;
        while (blockOffset < arrayBuffer.byteLength && !uploadCancelled) {
            while (dataChannel && dataChannel.bufferedAmount > 4 * 1024 * 1024) {
                await new Promise(r => setTimeout(r, 5));
                if (uploadCancelled) break;
            }
            if (uploadCancelled) break;

            const chunk = arrayBuffer.slice(blockOffset, blockOffset + CHUNK_SIZE);
            dataChannel.send(chunk);
            blockOffset += chunk.byteLength;
            offset += chunk.byteLength;
        }

        if (currentUploadObj) {
            currentUploadObj.loaded = offset;
            renderUploadQueue();
        }
    }

    if (uploadCancelled) {
        sendData({ action: 'upload_cancel', path });
        return;
    }

    sendData({ action: 'upload_end', path });
    if (currentUploadObj) {
        currentUploadObj.loaded = file.size;
        currentUploadObj.status = 'done';
        renderUploadQueue();
    }
    
    // Register ownership locally in current directory file list
    const uploadedFileName = path.split('/').pop();
    const existingFile = allFilesInCurrentDir.find(f => f.name === uploadedFileName || f.path === path);
    if (existingFile) {
        existingFile.owner = currentUser.username;
    } else {
        allFilesInCurrentDir.push({
            name: uploadedFileName,
            path: path,
            size: file.size,
            isDir: false,
            owner: currentUser.username,
            modified: Date.now()
        });
    }
    
    showToast(`Uploaded "${uploadedFileName}" successfully`, "success");
    loadDirectory(currentPath);
    processNextUpload();
}

function cancelUpload() {
    uploadCancelled = true;
    if (currentUploadObj) {
        sendData({ action: 'upload_cancel', path: currentUploadObj.destPath });
    }
    const panel = getEl('upload-panel');
    if (panel) panel.style.display = 'none';
}

function toggleNewMenu(e) {
    e.stopPropagation();
    const menu = getEl('new-menu');
    if (menu) menu.style.display = menu.style.display === 'block' ? 'none' : 'block';
}

document.addEventListener('click', (e) => {
    const newMenu = getEl('new-menu');
    if (newMenu && !e.target.closest('.new-btn-wrapper')) {
        newMenu.style.display = 'none';
    }
    const contextMenu = getEl('context-menu');
    if (contextMenu && !e.target.closest('#context-menu')) {
        contextMenu.classList.remove('active');
    }
});

document.addEventListener('contextmenu', (e) => {
    if (!e.target.closest('.file-row')) {
        const contextMenu = getEl('context-menu');
        if (contextMenu) contextMenu.classList.remove('active');
    }
});

// Helper for formatting sizes
function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Helper for file category
function getFileCategory(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    if (['mp4', 'webm', 'ogg', 'mkv', 'avi', 'mov', '3gp', 'm4v', 'wmv', 'flv'].includes(ext)) return 'video';
    if (['mp3', 'wav', 'ogg', 'm4a', 'aac', 'flac'].includes(ext)) return 'audio';
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'heic', 'bmp'].includes(ext)) return 'image';
    if (['txt', 'md', 'js', 'json', 'css', 'html', 'xml', 'csv', 'log', 'py', 'java', 'kt', 'sh'].includes(ext)) return 'text';
    if (ext === 'pdf') return 'pdf';
    return 'other';
}

/**
 * showDisconnected — call this whenever connection drops for any reason.
 * Resets all state, clears file list, shows the login overlay with a
 * friendly message and a "Try to Reconnect" button.
 */
function showDisconnected(reason) {
    // Close WebRTC objects cleanly
    try { if (dataChannel) dataChannel.close(); } catch(e) {}
    try { if (peerConnection) peerConnection.close(); } catch(e) {}
    dataChannel = null;
    peerConnection = null;

    // Reset file browsing state
    currentPath = '';
    fileListCache = {};
    pendingChunks = [];

    // Clear the file list UI
    const flc = getEl('file-list-container');
    if (flc) flc.innerHTML = '';

    // Show the login overlay
    getEl('app-container').classList.add('hidden');
    getEl('login-overlay').classList.add('active');

    // Show the reason message with a reconnect button
    const statusEl = getEl('login-status');
    statusEl.style.color = "#ea4335";
    statusEl.innerHTML = `
        <div style="margin-bottom:8px;">⚠️ ${reason}</div>
        <button onclick="connectToHost()" style="
            background:#004A77;color:#fff;border:none;
            padding:8px 20px;border-radius:20px;cursor:pointer;
            font-size:13px;font-weight:600;margin-top:4px;">
            🔄 Try to Reconnect
        </button>`;
}

// Disconnect function triggered by Avatar click
window.disconnectHost = function() {
    console.log("Disconnecting from host...");
    showDisconnected("You disconnected from the host.");
};

// --- Mobile sidebar toggle ------------------------------
function toggleSidebar() {
    const sidebar = document.querySelector('.sidebar');
    const overlay = document.getElementById('mobile-overlay');
    sidebar.classList.toggle('open');
    overlay.classList.toggle('active');
}

function closeSidebar() {
    const sidebar = document.querySelector('.sidebar');
    const overlay = document.getElementById('mobile-overlay');
    sidebar.classList.remove('open');
    overlay.classList.remove('active');
}

getEl('dl-cancel-btn').onclick = () => {
    currentTransferIntent = null;
    downloadBuffer = [];
    if (currentDownloadWritable) {
        currentDownloadWritable.abort().catch(console.error);
        currentDownloadWritable = null;
    }
    if (currentRangeId && currentRangeId.startsWith('direct_dl_')) {
        currentRangeId = null;
    }
    getEl('download-widget').classList.add('hidden');
    if (typeof showToast === 'function') showToast('Download canceled');
};

getEl('search-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
        const q = e.target.value.trim();
        if (q) {
            currentFilesList = [];
            renderFiles(currentPath, []); // Show empty while loading
            sendData({ action: 'search', query: q, path: currentPath });
        } else {
            loadDirectory(currentPath);
        }
    }
});

function initSearch() {
    const searchInput = document.getElementById('search-input');
    const searchSubtitle = document.getElementById('search-subtitle');
    if (!searchInput) return;
    
    let searchTimeout;
    searchInput.addEventListener('input', () => {
        const query = searchInput.value.trim().toLowerCase();
        clearTimeout(searchTimeout);
        
        if (!query) {
            renderFiles(currentPath, allFilesInCurrentDir, false);
            if (searchSubtitle) searchSubtitle.style.display = 'none';
            return;
        }
        
        if (query.length >= 2) {
            searchTimeout = setTimeout(() => {
                sendData({ action: 'search', query: query, path: '' });
                const fileList = document.getElementById('file-list');
                if (fileList) fileList.innerHTML = '<div class="empty-state"><span class="material-icons" style="font-size:48px;color:var(--primary)">manage_search</span><p>Searching across all folders...</p></div>';
                if (searchSubtitle) searchSubtitle.style.display = 'block';
            }, 400);
        }
    });
}
document.addEventListener('DOMContentLoaded', initSearch);

function saveDownloadProgress(fileName, receivedBytes, totalBytes) {
    try {
        localStorage.setItem('sm_dl_' + fileName, JSON.stringify({
            received: receivedBytes,
            total: totalBytes,
            timestamp: Date.now()
        }));
    } catch(e) {
    }
}

function clearDownloadProgress(fileName) {
    try {
        localStorage.removeItem('sm_dl_' + fileName);
    } catch(e) {}
}

function getDownloadProgress(fileName) {
    try {
        var data = localStorage.getItem('sm_dl_' + fileName);
        if (data) return JSON.parse(data);
    } catch(e) {}
    return null;
}

window.shareFile = function(path) {
    sendData({ action: 'create_link', paths: [path] });
};

window.downloadFile = function(path) {
    const file = currentFilesList.find(f => f.path === path) || allFilesInCurrentDir.find(f => f.path === path);
    if (file && !file.isDir) startDownload(file, 'download');
};

window.deleteFile = async function(path) {
    if (currentUser.role === 'Viewer') {
        showToast("Access Denied: Viewer role cannot delete files.", "error");
        return;
    }
    const file = currentFilesList.find(f => f.path === path) || allFilesInCurrentDir.find(f => f.path === path);
    if (file && !canUserDeleteFile(file)) {
        showToast("Access Denied: Cannot delete files owned by another user.", "error");
        return;
    }
    const fileName = file ? file.name : (path.split('/').pop() || 'item');
    const confirmed = await showDestructiveConfirm(
        "Delete Item",
        `Are you sure you want to delete <strong>${fileName}</strong>? This action will move it to the trash.`,
        "Delete"
    );
    if (confirmed) {
        sendData({ action: 'delete', path: path });
        showToast(`Moved "${fileName}" to trash.`, 'info');
        setTimeout(() => isStarredMode ? loadStarred() : loadDirectory(currentPath), 1000);
    }
};

window.confirmMove = function() {
    if (currentUser.role === 'Viewer') {
        showToast("Access Denied: Viewer role cannot move files.", "error");
        return;
    }
    const select = document.getElementById('move-destination-select');
    const targetDir = select.value;
    const modal = document.getElementById('move-modal');
    modal.style.display = 'none';

    if (selectedFiles.size === 0) return;
    const files = Array.from(selectedFiles);
    
    files.forEach(f => {
        let dest = targetDir;
        if (!dest.endsWith('/')) dest += '/';
        dest += f.name;
        if (dest.startsWith('/')) dest = dest.substring(1);
        sendData({ action: 'move', path: f.path, newPath: dest });
    });
    
    setTimeout(() => loadDirectory(currentPath), 1000);
};

// Full-Window Drag and Drop Zone
let dragCounter = 0;

document.addEventListener('dragenter', (e) => {
    e.preventDefault();
    dragCounter++;
    if (currentUser.role === 'Viewer') return;
    const dropOverlay = getEl('drop-overlay');
    if (dropOverlay) {
        dropOverlay.classList.add('active');
        const folderNameEl = getEl('drop-folder-name');
        if (folderNameEl) folderNameEl.innerText = 'Uploading to ' + (currentPath || 'My Drive');
    }
});

document.addEventListener('dragover', (e) => {
    e.preventDefault();
});

document.addEventListener('dragleave', (e) => {
    e.preventDefault();
    dragCounter--;
    if (dragCounter <= 0) {
        dragCounter = 0;
        const dropOverlay = getEl('drop-overlay');
        if (dropOverlay) dropOverlay.classList.remove('active');
    }
});

document.addEventListener('drop', (e) => {
    e.preventDefault();
    dragCounter = 0;
    const dropOverlay = getEl('drop-overlay');
    if (dropOverlay) dropOverlay.classList.remove('active');
    
    if (currentUser.role === 'Viewer') {
        showToast("Access Denied: Viewer role cannot upload files.", "error");
        return;
    }
    
    const files = e.dataTransfer.files;
    if (!files || files.length === 0) return;
    
    for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const destPath = (currentPath ? currentPath + '/' : '') + file.name;
        uploadQueue.push({ id: Date.now() + i, file: file, destPath: destPath, status: 'pending', loaded: 0, size: file.size, startTime: Date.now() });
    }
    uploadCancelled = false;
    renderUploadQueue();
    processNextUpload();
});

// Keyboard Shortcut: Delete key handling
document.addEventListener('keydown', (e) => {
    if (e.key === 'Delete' || e.key === 'Backspace') {
        if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) return;
        if (currentUser.role === 'Viewer') return;
        if (selectedFiles.size > 0) {
            multiAction('delete');
        }
    }
});

document.addEventListener('DOMContentLoaded', () => {
    const urlParams = new URLSearchParams(window.location.search);
    const hParam = urlParams.get('host');
    const tParam = urlParams.get('token');
    if (tParam) {
        currentUser.role = 'Viewer';
        applyRolePermissions();
        document.body.classList.add('share-mode-ui');
        const style = document.createElement('style');
        style.textContent = `body.share-mode-ui .sidebar { display: none !important; } body.share-mode-ui .search-bar { display: none !important; } body.share-mode-ui #search-subtitle { display: none !important; } body.share-mode-ui .avatar { display: none !important; } body.share-mode-ui .main-body { max-width: 900px; margin: 24px auto !important; border-radius: 12px; box-shadow: var(--shadow-md); height: calc(100vh - 48px); border: 1px solid var(--border-default); } body.share-mode-ui #app-container { background: var(--bg-canvas); padding: 0; }`;
        document.head.appendChild(style);
    }
    if (hParam && tParam) {
        const hInput = document.getElementById('host-id');
        const pInput = document.getElementById('password');
        if (hInput) hInput.value = hParam;
        if (pInput) pInput.value = tParam;
        setTimeout(() => connectToHost(), 500);
    }
});

function updateStorageSegmentedBar(data) {
    const segBar = getEl('storage-segmented-bar');
    if (!segBar || !Array.isArray(data)) return;
    
    let totalSize = data.reduce((acc, cur) => acc + (cur.size || 0), 0);
    if (totalSize === 0) totalSize = 1;
    
    let html = '';
    data.forEach(item => {
        const pct = Math.max(1, (item.size / totalSize) * 100);
        const color = item.color || '#3b82f6';
        html += `<div class="storage-seg" style="width: ${pct}%; background: ${color};" title="${item.category}: ${typeof formatSize === 'function' ? formatSize(item.size) : item.size}"></div>`;
    });
    segBar.innerHTML = html;
}
window.updateStorageSegmentedBar = updateStorageSegmentedBar;

function loadStorageDashboard() {
    isTrashMode = false;
    isStarredMode = false;
    const fileListContainer = getEl('fileListContainer') || getEl('file-list-container');
    const pathBar = getEl('pathBar') || getEl('path-bar');
    if (pathBar) pathBar.innerHTML = '<span class="breadcrumb">Storage Dashboard</span>';
    if (fileListContainer) fileListContainer.innerHTML = '<div style="padding: 24px; color: var(--text-secondary);">Loading storage breakdown...</div>';
    sendData({ action: 'get_storage_breakdown' });
}

function renderStorageDashboard(data) {
    const fileListContainer = getEl('fileListContainer') || getEl('file-list-container');
    if (!fileListContainer) return;
    
    let totalSize = 0;
    data.forEach(item => totalSize += item.size || 0);

    let html = '<div style="display: flex; flex-wrap: wrap; gap: 16px; padding: 24px;">';
    
    data.forEach(item => {
        const pct = totalSize > 0 ? ((item.size / totalSize) * 100).toFixed(1) : 0;
        html += `
            <div class="storage-card" style="flex: 1 1 220px; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-md); padding: 18px; cursor: pointer; transition: all 0.15s ease; box-shadow: var(--shadow-xs);" onclick="loadCategory('${item.category}')" onmouseover="this.style.boxShadow='var(--shadow-md)'; this.style.borderColor='var(--border-strong)';" onmouseout="this.style.boxShadow='var(--shadow-xs)'; this.style.borderColor='var(--border-default)';">
                <div style="display: flex; align-items: center; margin-bottom: 14px;">
                    <span class="material-icons" style="color: ${item.color || 'var(--brand-primary)'}; font-size: 28px; background: var(--bg-surface-subtle); padding: 10px; border-radius: var(--radius-md); margin-right: 12px;">${item.icon || 'folder'}</span>
                    <div>
                        <h3 style="margin: 0; font-size: 15px; font-weight: 600; color: var(--text-primary);">${item.category}</h3>
                        <p style="margin: 2px 0 0; font-size: 12px; color: var(--text-secondary);">${item.count} files</p>
                    </div>
                </div>
                <div>
                    <div style="display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 8px; font-weight: 500; font-variant-numeric: tabular-nums;">
                        <span>${typeof formatSize === 'function' ? formatSize(item.size) : item.size}</span>
                        <span style="color: var(--text-secondary);">${pct}%</span>
                    </div>
                    <div style="width: 100%; background: var(--bg-surface-subtle); height: 6px; border-radius: 3px; overflow: hidden;">
                        <div style="width: ${pct}%; background: ${item.color || 'var(--brand-primary)'}; height: 100%;"></div>
                    </div>
                </div>
            </div>
        `;
    });
    
    html += '</div>';
    fileListContainer.innerHTML = html;
}

function loadCategory(category) {
    currentPath = 'Category: ' + category;
    const fileListContainer = getEl('fileListContainer') || getEl('file-list-container');
    if (fileListContainer) fileListContainer.innerHTML = '<div style="padding: 24px; color: var(--text-secondary);">Loading ' + category + '...</div>';
    sendData({ action: 'list_category', category: category });
}

// ================= ADMIN USER MANAGEMENT =================
let localUserAccounts = [
    { username: 'admin', role: 'admin', permissions: ['/'] },
    { username: 'sarah_uploader', role: 'uploader', permissions: ['/'] },
    { username: 'john_viewer', role: 'viewer', permissions: ['/'] }
];

async function openAdminUsersModal() {
    if (currentUser.role !== 'Admin') {
        showToast("Access Denied: Only Admin can access User Management", "error");
        return;
    }
    const modal = getEl('admin-users-modal');
    if (modal) modal.style.display = 'flex';
    if (dataChannel && dataChannel.readyState === 'open') {
        sendData({ action: 'list_users' });
    }
    await refreshAdminUsersList();
}
window.openAdminUsersModal = openAdminUsersModal;

function closeAdminUsersModal() {
    const modal = getEl('admin-users-modal');
    if (modal) modal.style.display = 'none';
}
window.closeAdminUsersModal = closeAdminUsersModal;

async function refreshAdminUsersList() {
    const tbody = getEl('admin-users-table-body');
    if (!tbody) return;
    
    let users = localUserAccounts;
    try {
        const res = await fetch('/api/users', {
            headers: { 'Authorization': 'Bearer ' + (currentUser.token || '') }
        });
        if (res.ok) {
            const data = await res.json();
            if (data.users && Array.isArray(data.users)) {
                users = data.users;
                localUserAccounts = users;
            }
        }
    } catch (e) {
        // Fallback to local accounts
    }
    
    tbody.innerHTML = '';
    users.forEach(u => {
        const tr = document.createElement('tr');
        const uRole = (u.role || 'viewer').toLowerCase();
        tr.innerHTML = `
            <td style="font-weight: 600;">${u.username}</td>
            <td>
                <span class="role-badge role-${uRole}">
                    <span class="role-dot"></span>
                    ${uRole.charAt(0).toUpperCase() + uRole.slice(1)}
                </span>
            </td>
            <td>
                <div style="display: flex; gap: 6px; align-items: center;">
                    <select id="role-select-${u.username}" style="padding: 4px 8px; border-radius: var(--radius-sm); border: 1px solid var(--border-default); font-size: 12px;">
                        <option value="viewer" ${uRole === 'viewer' ? 'selected' : ''}>Viewer</option>
                        <option value="uploader" ${uRole === 'uploader' ? 'selected' : ''}>Uploader</option>
                        <option value="admin" ${uRole === 'admin' ? 'selected' : ''}>Admin</option>
                    </select>
                    <button class="btn-subtle" onclick="adminUpdateUserRole('${u.username}')" title="Save Role">
                        <span class="material-icons" style="font-size: 16px; color: var(--brand-primary);">check</span>
                    </button>
                </div>
            </td>
            <td>
                ${u.username === 'admin' ? '<span style="color: var(--text-muted); font-size: 12px;">System Owner</span>' : `
                    <button class="btn-subtle" onclick="adminDeleteUser('${u.username}')" title="Delete User">
                        <span class="material-icons" style="font-size: 16px; color: var(--status-danger);">delete</span>
                    </button>
                `}
            </td>
        `;
        tbody.appendChild(tr);
    });
}

async function adminUpdateUserRole(targetUsername) {
    const select = getEl(`role-select-${targetUsername}`);
    if (!select) return;
    const newRole = select.value;
    
    try {
        await fetch('/api/users/update_role', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + (currentUser.token || '')
            },
            body: JSON.stringify({ targetUsername, role: newRole })
        });
    } catch(e) {}
    
    const user = localUserAccounts.find(u => u.username === targetUsername);
    if (user) user.role = newRole;
    if (dataChannel && dataChannel.readyState === 'open') {
        sendData({ action: 'update_user_role', targetUsername, role: newRole });
    }
    showToast(`Updated role for ${targetUsername} to ${newRole}`, "success");
    refreshAdminUsersList();
}
window.adminUpdateUserRole = adminUpdateUserRole;

async function adminCreateUser() {
    const unameInput = getEl('admin-new-username');
    const pwdInput = getEl('admin-new-password');
    const roleSelect = getEl('admin-new-role');
    
    const username = unameInput ? unameInput.value.trim() : '';
    const password = pwdInput ? pwdInput.value.trim() : '';
    const role = roleSelect ? roleSelect.value : 'uploader';
    
    if (!username) {
        showToast("Please enter a username", "warning");
        return;
    }
    
    try {
        await fetch('/api/users', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + (currentUser.token || '')
            },
            body: JSON.stringify({ username, password, role })
        });
    } catch(e) {}
    
    if (!localUserAccounts.some(u => u.username === username)) {
        localUserAccounts.push({ username, role, permissions: ['/'] });
    }
    if (dataChannel && dataChannel.readyState === 'open') {
        sendData({ action: 'add_user', username, password, role });
    }
    
    showToast(`User "${username}" created with role ${role}`, "success");
    if (unameInput) unameInput.value = '';
    if (pwdInput) pwdInput.value = '';
    refreshAdminUsersList();
}
window.adminCreateUser = adminCreateUser;

async function adminDeleteUser(targetUsername) {
    const confirmed = await showDestructiveConfirm(
        "Delete User Account",
        `Are you sure you want to delete user account <strong>${targetUsername}</strong>? They will lose all access.`,
        "Delete User"
    );
    if (!confirmed) return;
    
    try {
        await fetch('/api/users/delete', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + (currentUser.token || '')
            },
            body: JSON.stringify({ targetUsername })
        });
    } catch(e) {}
    
    localUserAccounts = localUserAccounts.filter(u => u.username !== targetUsername);
    if (dataChannel && dataChannel.readyState === 'open') {
        sendData({ action: 'remove_user', targetUsername });
    }
    showToast(`User "${targetUsername}" removed`, "info");
    refreshAdminUsersList();
}
window.adminDeleteUser = adminDeleteUser;
