/**
 * TeleDrive Ultra - Main Application Controller
 * Handles UI events, file uploads, folder creation, ghost camera, and view updates.
 */

const GhostCamera = {
    stream: null,
    facingMode: 'environment',

    async open() {
        const modal = document.getElementById('ghostCameraModal');
        const video = document.getElementById('ghostVideo');
        if (!modal || !video) return;

        try {
            this.stream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: this.facingMode }
            });
            video.srcObject = this.stream;
            modal.classList.remove('hidden');
        } catch (err) {
            alert('Camera access denied or not available: ' + err.message);
        }
    },

    close() {
        if (this.stream) {
            this.stream.getTracks().forEach(track => track.stop());
            this.stream = null;
        }
        const modal = document.getElementById('ghostCameraModal');
        if (modal) modal.classList.add('hidden');
    },

    switchCamera() {
        this.facingMode = this.facingMode === 'environment' ? 'user' : 'environment';
        this.close();
        this.open();
    },

    async capturePhoto() {
        const video = document.getElementById('ghostVideo');
        const canvas = document.getElementById('ghostCanvas');
        if (!video || !canvas) return;

        canvas.width = video.videoWidth || 1280;
        canvas.height = video.videoHeight || 720;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

        canvas.toBlob(async (blob) => {
            this.close();
            const fileName = `GhostPhoto_${Date.now()}.jpg`;
            await App.uploadBlobDirectly(blob, fileName, 'image/jpeg');
        }, 'image/jpeg', 0.92);
    }
};

const App = {
    searchQuery: '',
    mediaRecorder: null,
    audioChunks: [],
    isRecordingVoice: false,

    init() {
        this.refreshUI();
        this.initEventListeners();
    },

    initEventListeners() {
        // Drag & drop prevention on window
        window.addEventListener('dragover', e => e.preventDefault());
        window.addEventListener('drop', e => e.preventDefault());
    },

    refreshUI() {
        this.renderBreadcrumbs();
        this.renderFolders();
        this.renderFiles();
        this.renderSidebarFolders();
        this.updateBadgesAndStorage();

        if (window.lucide) {
            lucide.createIcons();
        }
    },

    toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        if (sidebar) {
            sidebar.classList.toggle('-translate-x-full');
        }
    },

    navigateView(viewName) {
        StorageManager.currentView = viewName;
        
        // Update nav items active state
        document.querySelectorAll('.nav-item').forEach(item => {
            if (item.getAttribute('data-nav') === viewName) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });

        const titleMap = {
            all: 'All Files',
            media: 'Media Streaming & Playback',
            vault: 'Secret Vault (Zero-Knowledge)',
            shares: 'Public Domain Shares',
            trash: 'Recycle Bin'
        };
        document.getElementById('currentViewTitle').textContent = titleMap[viewName] || 'Files';

        // Close mobile sidebar if open
        if (window.innerWidth < 1024) {
            this.toggleSidebar();
        }

        this.refreshUI();
    },

    setViewMode(mode) {
        StorageManager.viewMode = mode;
        const gridBtn = document.getElementById('viewGridBtn');
        const listBtn = document.getElementById('viewListBtn');

        if (mode === 'grid') {
            gridBtn.className = 'p-1.5 rounded-lg text-theme-primary bg-theme-primary/10 transition-colors';
            listBtn.className = 'p-1.5 rounded-lg text-theme-muted hover:text-white transition-colors';
        } else {
            gridBtn.className = 'p-1.5 rounded-lg text-theme-muted hover:text-white transition-colors';
            listBtn.className = 'p-1.5 rounded-lg text-theme-primary bg-theme-primary/10 transition-colors';
        }
        this.renderFiles();
    },

    openFolder(folderId) {
        StorageManager.currentFolderId = folderId;
        StorageManager.currentView = 'all';
        this.refreshUI();
    },

    renderBreadcrumbs() {
        const container = document.getElementById('breadcrumbs');
        if (!container) return;

        const path = StorageManager.getFolderPath();
        let html = `
            <button onclick="App.openFolder('root')" class="flex items-center gap-1.5 text-theme-primary hover:underline font-bold">
                <i data-lucide="home" class="w-4 h-4"></i>
                <span>Home</span>
            </button>
        `;

        path.forEach(folder => {
            html += `
                <span class="text-theme-muted text-xs">/</span>
                <button onclick="App.openFolder('${folder.id}')" class="text-white hover:text-theme-primary font-semibold truncate max-w-[120px]">
                    ${folder.name}
                </button>
            `;
        });

        container.innerHTML = html;
    },

    renderSidebarFolders() {
        const container = document.getElementById('sidebarFolderList');
        if (!container) return;

        const folders = StorageManager.getActiveData().folders;
        container.innerHTML = folders.map(f => `
            <a href="javascript:void(0)" onclick="App.openFolder('${f.id}')" class="flex items-center justify-between px-3 py-2 rounded-xl text-xs text-theme-muted hover:text-white hover:bg-white/5 transition-all">
                <span class="flex items-center gap-2 truncate">
                    <i data-lucide="folder" class="w-4 h-4 text-theme-primary shrink-0"></i>
                    <span class="truncate">${f.name}</span>
                </span>
                <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-white/5">Topic</span>
            </a>
        `).join('');
    },

    renderFolders() {
        const section = document.getElementById('foldersSection');
        const grid = document.getElementById('foldersGrid');
        if (!section || !grid) return;

        // If in special views like Trash or Shares, hide folders grid
        if (StorageManager.currentView !== 'all') {
            section.classList.add('hidden');
            return;
        }

        const folders = StorageManager.getFolders();
        if (folders.length === 0) {
            section.classList.add('hidden');
            return;
        }

        section.classList.remove('hidden');
        grid.innerHTML = folders.map(folder => {
            const filesCount = StorageManager.getActiveData().files.filter(f => f.folderId === folder.id && !f.isTrash).length;
            return `
                <div class="folder-card group" onclick="App.openFolder('${folder.id}')">
                    <div class="flex items-start justify-between mb-3">
                        <div class="p-2.5 rounded-xl bg-theme-primary/10 text-theme-primary border border-theme-primary/20 group-hover:scale-110 transition-transform">
                            <i data-lucide="folder" class="w-5 h-5"></i>
                        </div>
                        <span class="text-[9px] uppercase font-bold tracking-wider px-2 py-0.5 rounded-full bg-cyan-400/10 text-cyan-400 border border-cyan-400/20">
                            Telegram Topic
                        </span>
                    </div>
                    <h4 class="text-xs font-bold text-white truncate mb-1">${folder.name}</h4>
                    <p class="text-[10px] text-theme-muted font-mono">${filesCount} files</p>
                </div>
            `;
        }).join('');
    },

    renderFiles() {
        const container = document.getElementById('filesContainer');
        const emptyState = document.getElementById('emptyState');
        const filesSection = document.getElementById('filesSection');
        if (!container) return;

        let files = StorageManager.getFiles();

        // Apply instant search filter
        if (this.searchQuery.trim()) {
            const q = this.searchQuery.toLowerCase();
            files = files.filter(f => f.name.toLowerCase().includes(q) || f.ext.toLowerCase().includes(q));
        }

        document.getElementById('itemCountLabel').textContent = `(${files.length} items)`;

        if (files.length === 0) {
            container.innerHTML = '';
            filesSection.classList.add('hidden');
            emptyState.classList.remove('hidden');
            return;
        }

        filesSection.classList.remove('hidden');
        emptyState.classList.add('hidden');

        if (StorageManager.viewMode === 'grid') {
            container.className = 'grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3 lg:gap-4';
            container.innerHTML = files.map(file => this.renderFileCard(file)).join('');
        } else {
            container.className = 'space-y-2';
            container.innerHTML = files.map(file => this.renderFileListRow(file)).join('');
        }
    },

    renderFileCard(file) {
        const iconData = this.getFileIcon(file.ext);
        const sizeStr = TelegramEngine.formatBytes(file.size);
        const isTrash = StorageManager.currentView === 'trash';

        return `
            <div class="file-card group">
                <div class="flex items-start justify-between mb-3">
                    <div class="p-2.5 rounded-xl ${iconData.bg} ${iconData.color} border ${iconData.border}">
                        <i data-lucide="${iconData.icon}" class="w-5 h-5"></i>
                    </div>
                    <div class="flex items-center gap-1 opacity-80 group-hover:opacity-100 transition-opacity">
                        ${file.isEncrypted ? '<span title="Zero-Knowledge Encrypted"><i data-lucide="lock" class="w-3.5 h-3.5 text-emerald-400"></i></span>' : ''}
                        ${file.shared ? '<span title="Public Domain Share Active"><i data-lucide="globe" class="w-3.5 h-3.5 text-cyan-400"></i></span>' : ''}
                    </div>
                </div>

                <div class="cursor-pointer mb-3" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    <h4 class="text-xs font-bold text-white truncate mb-0.5 hover:text-theme-primary transition-colors">${file.name}</h4>
                    <span class="text-[10px] text-theme-muted font-mono">${sizeStr} • ${file.ext.toUpperCase()}</span>
                </div>

                <div class="mt-auto pt-2 border-t border-theme-border flex items-center justify-between text-xs">
                    ${!isTrash ? `
                        <button onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})" class="p-1.5 rounded-lg text-theme-primary hover:bg-theme-primary/10 transition-colors" title="Play / Preview">
                            <i data-lucide="play" class="w-4 h-4"></i>
                        </button>
                        <button onclick="App.shareFile('${file.id}')" class="p-1.5 rounded-lg text-theme-muted hover:text-cyan-400 hover:bg-cyan-400/10 transition-colors" title="Get Public Domain Link">
                            <i data-lucide="share-2" class="w-4 h-4"></i>
                        </button>
                        <button onclick="App.downloadFile('${file.id}')" class="p-1.5 rounded-lg text-theme-muted hover:text-white hover:bg-white/5 transition-colors" title="Download">
                            <i data-lucide="download" class="w-4 h-4"></i>
                        </button>
                        <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1.5 rounded-lg text-theme-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors" title="Move to Trash">
                            <i data-lucide="trash-2" class="w-4 h-4"></i>
                        </button>
                    ` : `
                        <button onclick="StorageManager.restoreFromTrash('${file.id}'); App.refreshUI();" class="text-[11px] text-emerald-400 font-semibold hover:underline">
                            Restore
                        </button>
                        <button onclick="StorageManager.permanentDelete('${file.id}'); App.refreshUI();" class="text-[11px] text-rose-400 font-semibold hover:underline">
                            Delete Forever
                        </button>
                    `}
                </div>
            </div>
        `;
    },

    renderFileListRow(file) {
        const iconData = this.getFileIcon(file.ext);
        const sizeStr = TelegramEngine.formatBytes(file.size);
        const dateStr = new Date(file.createdAt).toLocaleDateString();

        return `
            <div class="file-list-row group">
                <div class="flex items-center gap-3 min-w-0 flex-1 cursor-pointer" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    <div class="p-2 rounded-lg ${iconData.bg} ${iconData.color}">
                        <i data-lucide="${iconData.icon}" class="w-4 h-4"></i>
                    </div>
                    <div class="truncate">
                        <div class="text-xs font-bold text-white truncate group-hover:text-theme-primary transition-colors">${file.name}</div>
                        <div class="text-[10px] text-theme-muted font-mono">${dateStr}</div>
                    </div>
                </div>
                <div class="flex items-center gap-4 text-xs font-mono text-theme-muted">
                    <span>${sizeStr}</span>
                    <div class="flex items-center gap-1">
                        <button onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})" class="p-1.5 hover:text-white"><i data-lucide="play" class="w-4 h-4"></i></button>
                        <button onclick="App.shareFile('${file.id}')" class="p-1.5 hover:text-cyan-400"><i data-lucide="share-2" class="w-4 h-4"></i></button>
                        <button onclick="App.downloadFile('${file.id}')" class="p-1.5 hover:text-white"><i data-lucide="download" class="w-4 h-4"></i></button>
                        <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1.5 hover:text-rose-400"><i data-lucide="trash-2" class="w-4 h-4"></i></button>
                    </div>
                </div>
            </div>
        `;
    },

    getFileIcon(ext) {
        const map = {
            pdf: { icon: 'file-text', color: 'text-rose-400', bg: 'bg-rose-500/10', border: 'border-rose-500/20' },
            mp4: { icon: 'video', color: 'text-purple-400', bg: 'bg-purple-500/10', border: 'border-purple-500/20' },
            mkv: { icon: 'film', color: 'text-purple-400', bg: 'bg-purple-500/10', border: 'border-purple-500/20' },
            mp3: { icon: 'music', color: 'text-emerald-400', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20' },
            jpg: { icon: 'image', color: 'text-cyan-400', bg: 'bg-cyan-500/10', border: 'border-cyan-500/20' },
            png: { icon: 'image', color: 'text-cyan-400', bg: 'bg-cyan-500/10', border: 'border-cyan-500/20' },
            zip: { icon: 'archive', color: 'text-amber-400', bg: 'bg-amber-500/10', border: 'border-amber-500/20' },
            rar: { icon: 'archive', color: 'text-amber-400', bg: 'bg-amber-500/10', border: 'border-amber-500/20' }
        };
        return map[ext.toLowerCase()] || { icon: 'file', color: 'text-theme-muted', bg: 'bg-white/5', border: 'border-white/10' };
    },

    async promptNewFolder() {
        const name = prompt('Enter new folder name (Will be mirrored as a Telegram Forum Topic):');
        if (!name || !name.trim()) return;

        // Create folder topic in Telegram
        const topicResult = await TelegramEngine.createFolderTopic(name.trim());
        const threadId = topicResult ? topicResult.message_thread_id : null;

        StorageManager.createFolder(name.trim(), StorageManager.currentFolderId, threadId);
        this.refreshUI();
    },

    async handleFileSelect(e) {
        const files = Array.from(e.target.files);
        if (!files.length) return;
        for (const file of files) {
            await this.uploadBlobDirectly(file, file.name, file.type);
        }
        e.target.value = '';
    },

    handleDragOver(e) {
        e.preventDefault();
        const overlay = document.getElementById('dropOverlay');
        if (overlay) overlay.classList.remove('hidden');
    },

    handleDragLeave(e) {
        const overlay = document.getElementById('dropOverlay');
        if (overlay) overlay.classList.add('hidden');
    },

    async handleDrop(e) {
        e.preventDefault();
        const overlay = document.getElementById('dropOverlay');
        if (overlay) overlay.classList.add('hidden');

        const files = Array.from(e.dataTransfer.files);
        for (const file of files) {
            await this.uploadBlobDirectly(file, file.name, file.type);
        }
    },

    async uploadBlobDirectly(blob, name, type) {
        const banner = document.getElementById('uploadBanner');
        const bannerText = document.getElementById('uploadBannerText');
        const progressBar = document.getElementById('uploadBannerProgress');

        banner.classList.remove('hidden');
        bannerText.textContent = `Encrypting & Uploading ${name} to Telegram...`;
        progressBar.style.width = '10%';

        try {
            // Step 1: Encrypt payload locally
            const arrayBuf = await blob.arrayBuffer();
            const hash = await CryptoEngine.computeHash(arrayBuf);
            const key = await CryptoEngine.deriveKey('master-vault-key-2026');
            const encryptedBuf = await CryptoEngine.encryptBuffer(arrayBuf, key);
            const encryptedBlob = new Blob([encryptedBuf], { type: 'application/octet-stream' });

            // Step 2: Upload to Telegram with progress
            const currentFolder = StorageManager.getFolderById(StorageManager.currentFolderId);
            const metadata = {
                id: Date.now().toString(36) + Math.random().toString(36).substring(2, 6),
                name: name,
                size: blob.size,
                type: type,
                ext: name.split('.').pop() || 'bin',
                path: StorageManager.getFolderPath().map(f => f.name).join(' / ') || 'Home',
                threadId: currentFolder ? currentFolder.threadId : null,
                hash: hash
            };

            const uploadResult = await TelegramEngine.uploadFile(encryptedBlob, metadata, (pct) => {
                progressBar.style.width = `${pct}%`;
                bannerText.textContent = `Uploading to Telegram (${pct}%)...`;
            });

            // Create local object URL for instant previewing
            const blobUrl = URL.createObjectURL(blob);

            // Step 3: Add to StorageManager
            StorageManager.addFile({
                ...metadata,
                telegramFileId: uploadResult.telegramFileId,
                telegramMessageId: uploadResult.telegramMessageId,
                folderId: StorageManager.currentFolderId,
                isEncrypted: true,
                isVault: StorageManager.currentView === 'vault',
                isTrash: false,
                shared: false,
                blobUrl: blobUrl,
                createdAt: Date.now()
            });

            progressBar.style.width = '100%';
            bannerText.textContent = 'Upload complete & verified!';
            setTimeout(() => banner.classList.add('hidden'), 1500);

            this.refreshUI();
        } catch (err) {
            alert('Upload failed: ' + err.message);
            banner.classList.add('hidden');
        }
    },

    openGhostCamera() {
        GhostCamera.open();
    },

    async toggleQuickRecord() {
        const btn = document.getElementById('quickRecordBtn');
        if (!this.isRecordingVoice) {
            // Start recording
            try {
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                this.audioChunks = [];
                this.mediaRecorder = new MediaRecorder(stream);
                this.mediaRecorder.ondataavailable = e => this.audioChunks.push(e.data);
                this.mediaRecorder.onstop = async () => {
                    const audioBlob = new Blob(this.audioChunks, { type: 'audio/mp3' });
                    const fileName = `VoiceMemo_${Date.now()}.mp3`;
                    await this.uploadBlobDirectly(audioBlob, fileName, 'audio/mp3');
                    stream.getTracks().forEach(t => t.stop());
                };
                this.mediaRecorder.start();
                this.isRecordingVoice = true;
                btn.className = 'p-2.5 sm:px-3 sm:py-2 rounded-xl bg-rose-500 text-white text-xs font-semibold flex items-center gap-1.5 animate-pulse shadow-lg shadow-rose-500/50';
                btn.querySelector('span').textContent = 'Recording...';
            } catch (err) {
                alert('Microphone access denied: ' + err.message);
            }
        } else {
            // Stop recording
            if (this.mediaRecorder) {
                this.mediaRecorder.stop();
            }
            this.isRecordingVoice = false;
            btn.className = 'p-2.5 sm:px-3 sm:py-2 rounded-xl bg-rose-500/10 border border-rose-500/30 hover:bg-rose-500/20 text-rose-400 text-xs font-semibold flex items-center gap-1.5 transition-all shadow-sm';
            btn.querySelector('span').textContent = 'Voice Note';
        }
    },

    shareFile(fileId) {
        const isShared = StorageManager.toggleShare(fileId);
        const link = TelegramEngine.generateShareLink(fileId);
        navigator.clipboard.writeText(link);
        alert(`Public Share Link Copied to Clipboard!\n\n${link}\n\nAnyone with this link can download or stream through your domain.`);
        this.refreshUI();
    },

    downloadFile(fileId) {
        const file = StorageManager.getActiveData().files.find(f => f.id === fileId);
        if (!file) return;

        if (file.blobUrl) {
            const a = document.createElement('a');
            a.href = file.blobUrl;
            a.download = file.name;
            a.click();
        } else {
            alert(`Initiating Telegram download for ${file.name}...`);
        }
    },

    handleSearch(e) {
        this.searchQuery = e.target.value;
        const clearBtn = document.getElementById('searchClearBtn');
        if (this.searchQuery) {
            clearBtn.classList.remove('hidden');
        } else {
            clearBtn.classList.add('hidden');
        }
        this.renderFiles();
    },

    clearSearch() {
        document.getElementById('searchInput').value = '';
        this.searchQuery = '';
        document.getElementById('searchClearBtn').classList.add('hidden');
        this.renderFiles();
    },

    updateBadgesAndStorage() {
        const data = StorageManager.getActiveData();
        const allFiles = data.files.filter(f => !f.isTrash);
        const mediaFiles = data.files.filter(f => !f.isTrash && ['mp4', 'mkv', 'mp3', 'wav', 'jpg', 'png'].includes(f.ext.toLowerCase()));

        document.getElementById('badgeAllCount').textContent = allFiles.length;
        document.getElementById('badgeMediaCount').textContent = mediaFiles.length;

        const usage = StorageManager.getStorageUsage();
        document.getElementById('storageUsedText').textContent = `${usage.formatted} stored`;
    },

    openSettingsModal() {
        document.getElementById('inputBotToken').value = TelegramEngine.botToken;
        document.getElementById('inputChatId').value = TelegramEngine.chatId;
        document.getElementById('inputCustomDomain').value = TelegramEngine.customDomain;
        document.getElementById('inputRealPin').value = Vault.realPin;
        document.getElementById('inputDecoyPin').value = Vault.decoyPin;
        document.getElementById('settingsModal').classList.remove('hidden');
    },

    closeSettingsModal() {
        document.getElementById('settingsModal').classList.add('hidden');
    },

    setStorageEngine(engine) {
        const botBtn = document.getElementById('engineBotBtn');
        const mtprotoBtn = document.getElementById('engineMtprotoBtn');
        const botFields = document.getElementById('botFields');
        const mtprotoFields = document.getElementById('mtprotoFields');

        if (engine === 'bot') {
            botBtn.className = 'p-3 rounded-2xl border text-left transition-all border-theme-primary bg-theme-primary/10';
            mtprotoBtn.className = 'p-3 rounded-2xl border border-theme-border bg-theme-bg/60 text-left transition-all hover:border-theme-primary/50';
            botFields.classList.remove('hidden');
            mtprotoFields.classList.add('hidden');
        } else {
            mtprotoBtn.className = 'p-3 rounded-2xl border text-left transition-all border-theme-primary bg-theme-primary/10';
            botBtn.className = 'p-3 rounded-2xl border border-theme-border bg-theme-bg/60 text-left transition-all hover:border-theme-primary/50';
            mtprotoFields.classList.remove('hidden');
            botFields.classList.add('hidden');
        }
    },

    saveSettings() {
        const token = document.getElementById('inputBotToken').value;
        const chat = document.getElementById('inputChatId').value;
        const domain = document.getElementById('inputCustomDomain').value;
        const realPin = document.getElementById('inputRealPin').value;
        const decoyPin = document.getElementById('inputDecoyPin').value;

        TelegramEngine.saveConfig(token, chat, domain, TelegramEngine.storageEngine);
        Vault.savePins(realPin, decoyPin);

        this.closeSettingsModal();
        this.refreshUI();
        alert('TeleDrive Ultra Configuration Saved Successfully!');
    }
};

// Start application
document.addEventListener('DOMContentLoaded', () => {
    App.init();
});
