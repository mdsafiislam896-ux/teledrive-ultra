/**
 * TeleDrive Ultra - Application Controller (Material 3 Mobile & Desktop Experience)
 * Directly implements AWD TeleDrive / Google Drive interactions.
 */

const GhostCamera = {
    stream: null,
    facingMode: 'environment',

    async open() {
        if (window.FX) FX.playTap();
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
            alert('Camera not available or access denied: ' + err.message);
        }
    },

    close() {
        if (window.FX) FX.playTap();
        if (this.stream) {
            this.stream.getTracks().forEach(track => track.stop());
            this.stream = null;
        }
        const modal = document.getElementById('ghostCameraModal');
        if (modal) modal.classList.add('hidden');
    },

    switchCamera() {
        if (window.FX) FX.playTap();
        this.facingMode = this.facingMode === 'environment' ? 'user' : 'environment';
        this.close();
        this.open();
    },

    async capturePhoto() {
        if (window.FX) {
            FX.playCamera();
            FX.haptic('success');
        }

        const video = document.getElementById('ghostVideo');
        const canvas = document.getElementById('ghostCanvas');
        if (!video || !canvas) return;

        canvas.width = video.videoWidth || 1280;
        canvas.height = video.videoHeight || 720;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

        canvas.toBlob(async (blob) => {
            this.close();
            const fileName = `Photo_${Date.now()}.jpg`;
            await App.uploadBlobDirectly(blob, fileName, 'image/jpeg');
        }, 'image/jpeg', 0.95);
    }
};

const App = {
    searchQuery: '',
    folderHistory: ['root'],

    init() {
        this.refreshUI();
        this.initShortcuts();
    },

    initShortcuts() {
        window.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'u') {
                e.preventDefault();
                document.getElementById('fileInput').click();
            }
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
                e.preventDefault();
                document.getElementById('searchInput').focus();
            }
            if (e.key === 'Escape') {
                this.closeActionSheet();
                this.closeNewFolderModal();
                MediaPlayer.close();
                GhostCamera.close();
                this.closeSettingsModal();
                ThemeEngine.toggleThemePicker();
            }
        });
    },

    refreshUI() {
        this.renderBreadcrumbs();
        this.renderFolders();
        this.renderFiles();
        this.updateStorageStats();

        if (window.lucide) lucide.createIcons();
    },

    // ==========================================
    // 📁 NAVIGATION & FOLDER HIERARCHY
    // ==========================================

    openFolder(folderId) {
        if (window.FX) FX.tap('light');
        if (folderId !== StorageManager.currentFolderId) {
            this.folderHistory.push(folderId);
        }
        StorageManager.currentFolderId = folderId;
        StorageManager.currentView = 'all';
        this.refreshUI();
    },

    navigateBack() {
        if (window.FX) FX.tap('light');
        if (this.folderHistory.length > 1) {
            this.folderHistory.pop();
            const prev = this.folderHistory[this.folderHistory.length - 1];
            StorageManager.currentFolderId = prev;
        } else {
            StorageManager.currentFolderId = 'root';
        }
        this.refreshUI();
    },

    renderBreadcrumbs() {
        const container = document.getElementById('breadcrumbs');
        const backBtn = document.getElementById('folderBackBtn');
        if (!container) return;

        const isRoot = StorageManager.currentFolderId === 'root';
        if (backBtn) {
            if (!isRoot) {
                backBtn.classList.remove('hidden');
            } else {
                backBtn.classList.add('hidden');
            }
        }

        const currentFolder = StorageManager.getFolderById(StorageManager.currentFolderId);
        const folderName = currentFolder ? currentFolder.name : 'Home';

        if (isRoot) {
            container.innerHTML = `<span class="text-theme-text font-bold text-sm">My Drive</span>`;
        } else {
            container.innerHTML = `
                <button onclick="App.openFolder('root')" class="text-theme-muted hover:underline font-normal text-xs">My Drive</button>
                <span class="text-theme-muted text-xs">/</span>
                <span class="text-theme-text font-bold text-sm truncate max-w-[150px]">${folderName}</span>
            `;
        }
    },

    navigateView(viewName) {
        if (window.FX) FX.tap('light');
        StorageManager.currentView = viewName;

        // Bottom Navigation Active Pill
        document.querySelectorAll('.bottom-nav-item').forEach(item => {
            if (item.getAttribute('data-bottom-nav') === viewName) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });

        // Desktop Sidebar Active
        document.querySelectorAll('[data-desktop-nav]').forEach(item => {
            if (item.getAttribute('data-desktop-nav') === viewName) {
                item.className = 'flex items-center gap-3 px-4 py-2.5 rounded-full text-xs font-semibold text-theme-primary bg-theme-primary-container/30 transition-colors';
            } else {
                item.className = 'flex items-center gap-3 px-4 py-2.5 rounded-full text-xs font-semibold text-theme-muted hover:bg-theme-surface-variant transition-colors';
            }
        });

        this.refreshUI();
    },

    setViewMode(mode) {
        if (window.FX) FX.tap('light');
        StorageManager.viewMode = mode;
        const gridBtn = document.getElementById('viewGridBtn');
        const listBtn = document.getElementById('viewListBtn');

        if (mode === 'grid') {
            gridBtn.className = 'p-1 rounded text-theme-primary bg-theme-surface-variant';
            listBtn.className = 'p-1 rounded text-theme-muted hover:text-theme-text';
        } else {
            gridBtn.className = 'p-1 rounded text-theme-muted hover:text-theme-text';
            listBtn.className = 'p-1 rounded text-theme-primary bg-theme-surface-variant';
        }
        this.renderFiles();
    },

    // ==========================================
    // 📂 FOLDERS & FILES RENDERING
    // ==========================================

    renderFolders() {
        const section = document.getElementById('foldersSection');
        const grid = document.getElementById('foldersGrid');
        if (!section || !grid) return;

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
                <div class="m3-folder-card group" onclick="App.openFolder('${folder.id}')">
                    <div class="flex items-center gap-2.5 min-w-0">
                        <i data-lucide="folder" class="w-5 h-5 text-amber-500 fill-amber-500/20 shrink-0"></i>
                        <span class="text-xs font-bold text-theme-text truncate">${folder.name}</span>
                    </div>
                    <button onclick="event.stopPropagation(); App.deleteFolderPrompt('${folder.id}', '${folder.name}');" class="p-1 text-theme-muted hover:text-rose-500 rounded-full opacity-60 group-hover:opacity-100 transition-opacity">
                        <i data-lucide="more-vertical" class="w-4 h-4"></i>
                    </button>
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

        if (this.searchQuery.trim()) {
            const q = this.searchQuery.toLowerCase();
            files = files.filter(f => f.name.toLowerCase().includes(q) || f.ext.toLowerCase().includes(q));
        }

        if (files.length === 0) {
            container.innerHTML = '';
            filesSection.classList.add('hidden');
            emptyState.classList.remove('hidden');
            return;
        }

        filesSection.classList.remove('hidden');
        emptyState.classList.add('hidden');

        if (StorageManager.viewMode === 'grid') {
            container.className = 'grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3';
            container.innerHTML = files.map(file => this.renderFileGridCard(file)).join('');
        } else {
            container.className = 'space-y-2';
            container.innerHTML = files.map(file => this.renderFileListRow(file)).join('');
        }
    },

    renderFileGridCard(file) {
        const iconData = this.getFileIcon(file.ext);
        const sizeStr = TelegramEngine.formatBytes(file.size);
        const isTrash = StorageManager.currentView === 'trash';

        return `
            <div class="m3-file-card group" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                <div class="flex items-start justify-between mb-3">
                    <div class="p-2.5 rounded-xl ${iconData.bg} ${iconData.color}">
                        <i data-lucide="${iconData.icon}" class="w-5 h-5"></i>
                    </div>
                    <div class="flex items-center gap-1">
                        ${file.isEncrypted ? '<i data-lucide="lock" class="w-3.5 h-3.5 text-emerald-500"></i>' : ''}
                        ${file.shared ? '<i data-lucide="globe" class="w-3.5 h-3.5 text-blue-500"></i>' : ''}
                    </div>
                </div>

                <div class="mb-3">
                    <h4 class="text-xs font-bold text-theme-text truncate mb-0.5 group-hover:text-theme-primary transition-colors">${file.name}</h4>
                    <span class="text-[10px] text-theme-muted font-mono">${sizeStr} • ${file.ext.toUpperCase()}</span>
                </div>

                <div class="pt-2 border-t border-theme-border/60 flex items-center justify-between text-xs" onclick="event.stopPropagation()">
                    ${!isTrash ? `
                        <button onclick="App.shareFile('${file.id}')" class="p-1 text-theme-muted hover:text-blue-500 rounded" title="Share Link">
                            <i data-lucide="share-2" class="w-3.5 h-3.5"></i>
                        </button>
                        <button onclick="App.downloadFile('${file.id}')" class="p-1 text-theme-muted hover:text-theme-text rounded" title="Download">
                            <i data-lucide="download" class="w-3.5 h-3.5"></i>
                        </button>
                        <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1 text-theme-muted hover:text-rose-500 rounded" title="Move to Trash">
                            <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
                        </button>
                    ` : `
                        <button onclick="StorageManager.restoreFromTrash('${file.id}'); App.refreshUI();" class="text-[11px] text-emerald-500 font-bold hover:underline">Restore</button>
                        <button onclick="StorageManager.permanentDelete('${file.id}'); App.refreshUI();" class="text-[11px] text-rose-500 font-bold hover:underline">Delete</button>
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
            <div class="p-3 rounded-2xl bg-theme-surface border border-theme-border flex items-center justify-between gap-3 group hover:border-theme-primary transition-colors cursor-pointer" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                <div class="flex items-center gap-3 min-w-0 flex-1">
                    <div class="p-2 rounded-xl ${iconData.bg} ${iconData.color}">
                        <i data-lucide="${iconData.icon}" class="w-4 h-4"></i>
                    </div>
                    <div class="truncate">
                        <div class="text-xs font-bold text-theme-text truncate group-hover:text-theme-primary transition-colors">${file.name}</div>
                        <div class="text-[10px] text-theme-muted font-mono">${dateStr}</div>
                    </div>
                </div>
                <div class="flex items-center gap-3 text-xs font-mono text-theme-muted" onclick="event.stopPropagation()">
                    <span>${sizeStr}</span>
                    <button onclick="App.shareFile('${file.id}')" class="p-1.5 hover:text-blue-500"><i data-lucide="share-2" class="w-4 h-4"></i></button>
                    <button onclick="App.downloadFile('${file.id}')" class="p-1.5 hover:text-theme-text"><i data-lucide="download" class="w-4 h-4"></i></button>
                    <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1.5 hover:text-rose-500"><i data-lucide="trash-2" class="w-4 h-4"></i></button>
                </div>
            </div>
        `;
    },

    getFileIcon(ext) {
        const map = {
            pdf: { icon: 'file-text', color: 'text-rose-500', bg: 'bg-rose-50 dark:bg-rose-950/40' },
            mp4: { icon: 'video', color: 'text-purple-500', bg: 'bg-purple-50 dark:bg-purple-950/40' },
            mkv: { icon: 'film', color: 'text-purple-500', bg: 'bg-purple-50 dark:bg-purple-950/40' },
            mp3: { icon: 'music', color: 'text-emerald-500', bg: 'bg-emerald-50 dark:bg-emerald-950/40' },
            jpg: { icon: 'image', color: 'text-blue-500', bg: 'bg-blue-50 dark:bg-blue-950/40' },
            png: { icon: 'image', color: 'text-blue-500', bg: 'bg-blue-50 dark:bg-blue-950/40' },
            zip: { icon: 'archive', color: 'text-amber-500', bg: 'bg-amber-50 dark:bg-amber-950/40' }
        };
        return map[ext.toLowerCase()] || { icon: 'file', color: 'text-slate-500', bg: 'bg-slate-100 dark:bg-slate-800' };
    },

    // ==========================================
    // ➕ ACTION SHEET & CREATION MODALS
    // ==========================================

    openActionSheet() {
        if (window.FX) FX.playTap();
        document.getElementById('actionBottomSheet').classList.remove('hidden');
    },

    closeActionSheet() {
        document.getElementById('actionBottomSheet').classList.add('hidden');
    },

    closeActionSheetOnBackdrop(e) {
        if (e.target.id === 'actionBottomSheet') {
            this.closeActionSheet();
        }
    },

    promptNewFolder() {
        if (window.FX) FX.playTap();
        const modal = document.getElementById('newFolderModal');
        const input = document.getElementById('newFolderNameInput');
        if (modal && input) {
            input.value = '';
            modal.classList.remove('hidden');
            setTimeout(() => input.focus(), 100);
        }
    },

    closeNewFolderModal() {
        const modal = document.getElementById('newFolderModal');
        if (modal) modal.classList.add('hidden');
    },

    async confirmCreateFolder() {
        const input = document.getElementById('newFolderNameInput');
        const name = input ? input.value.trim() : '';
        if (!name) return;

        this.closeNewFolderModal();

        // Create Forum Topic on Telegram
        const topicResult = await TelegramEngine.createFolderTopic(name);
        const threadId = topicResult ? topicResult.message_thread_id : null;

        StorageManager.createFolder(name, StorageManager.currentFolderId, threadId);

        if (window.FX) {
            FX.playSuccess();
            FX.haptic('success');
        }

        this.refreshUI();
    },

    deleteFolderPrompt(id, name) {
        if (confirm(`Delete folder "${name}" and move files to trash?`)) {
            StorageManager.deleteFolder(id);
            this.refreshUI();
        }
    },

    // ==========================================
    // 📤 FILE UPLOAD ENGINE
    // ==========================================

    async handleFileSelect(e) {
        const files = Array.from(e.target.files);
        if (!files.length) return;
        for (const file of files) {
            await this.uploadBlobDirectly(file, file.name, file.type);
        }
        e.target.value = '';
    },

    async uploadBlobDirectly(blob, name, type) {
        const banner = document.getElementById('uploadBanner');
        const bannerText = document.getElementById('uploadBannerText');
        const progressBar = document.getElementById('uploadBannerProgress');

        banner.classList.remove('hidden');
        bannerText.textContent = `Uploading ${name}...`;
        progressBar.style.width = '10%';

        try {
            const arrayBuf = await blob.arrayBuffer();
            const hash = await CryptoEngine.computeHash(arrayBuf);
            const key = await CryptoEngine.deriveKey('master-vault-key-2026');
            const encryptedBuf = await CryptoEngine.encryptBuffer(arrayBuf, key);
            const encryptedBlob = new Blob([encryptedBuf], { type: 'application/octet-stream' });

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
                bannerText.textContent = `Uploading (${pct}%)...`;
            });

            const blobUrl = URL.createObjectURL(blob);

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
            bannerText.textContent = 'Upload Complete!';
            if (window.FX) {
                FX.playSuccess();
                FX.haptic('success');
            }
            setTimeout(() => banner.classList.add('hidden'), 1500);

            this.refreshUI();
        } catch (err) {
            alert('Upload failed: ' + err.message);
            banner.classList.add('hidden');
        }
    },

    uploadToVault() {
        StorageManager.currentView = 'vault';
        this.refreshUI();
        document.getElementById('fileInput').click();
    },

    async recordVoiceNote() {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const mediaRecorder = new MediaRecorder(stream);
            const chunks = [];

            alert('Recording voice note... Click OK when finished.');
            mediaRecorder.ondataavailable = e => chunks.push(e.data);
            mediaRecorder.onstop = async () => {
                const blob = new Blob(chunks, { type: 'audio/mp3' });
                const name = `VoiceNote_${Date.now()}.mp3`;
                await this.uploadBlobDirectly(blob, name, 'audio/mp3');
                stream.getTracks().forEach(t => t.stop());
            };
            mediaRecorder.start();
            setTimeout(() => {
                if (mediaRecorder.state === 'recording') mediaRecorder.stop();
            }, 60000); // Max 1 min
        } catch (err) {
            alert('Microphone access denied: ' + err.message);
        }
    },

    shareFile(fileId) {
        if (window.FX) FX.playTap();
        StorageManager.toggleShare(fileId);
        const link = `https://mdsafiislam.tech/#share=${fileId}`;
        navigator.clipboard.writeText(link);
        alert(`Link Copied!\n\n${link}\n\nAnyone with this link can download from mdsafiislam.tech`);
        this.refreshUI();
    },

    downloadFile(fileId) {
        if (window.FX) FX.playTap();
        const file = StorageManager.getActiveData().files.find(f => f.id === fileId);
        if (!file) return;

        if (file.blobUrl) {
            const a = document.createElement('a');
            a.href = file.blobUrl;
            a.download = file.name;
            a.click();
        } else {
            alert(`Downloading ${file.name}...`);
        }
    },

    handleSearch(e) {
        this.searchQuery = e.target.value;
        this.renderFiles();
    },

    updateStorageStats() {
        const usage = StorageManager.getStorageUsage();
        const desk = document.getElementById('desktopStorageText');
        if (desk) desk.textContent = `${usage.formatted} of ∞ Used`;
    },

    openSettingsModal() {
        if (window.FX) FX.playTap();
        document.getElementById('inputBotToken').value = TelegramEngine.botToken;
        document.getElementById('inputChatId').value = TelegramEngine.chatId;
        document.getElementById('inputCustomDomain').value = TelegramEngine.customDomain || 'https://mdsafiislam.tech';
        document.getElementById('inputRealPin').value = Vault.realPin;
        document.getElementById('inputDecoyPin').value = Vault.decoyPin;
        document.getElementById('settingsModal').classList.remove('hidden');
    },

    closeSettingsModal() {
        document.getElementById('settingsModal').classList.add('hidden');
    },

    saveSettings() {
        const token = document.getElementById('inputBotToken').value;
        const chat = document.getElementById('inputChatId').value;
        const domain = document.getElementById('inputCustomDomain').value;
        const realPin = document.getElementById('inputRealPin').value;
        const decoyPin = document.getElementById('inputDecoyPin').value;

        TelegramEngine.saveConfig(token, chat, domain, TelegramEngine.storageEngine);
        Vault.savePins(realPin, decoyPin);

        if (window.FX) FX.playSuccess();
        this.closeSettingsModal();
        this.refreshUI();
        alert('Settings Saved Successfully!');
    }
};

document.addEventListener('DOMContentLoaded', () => {
    App.init();
});
