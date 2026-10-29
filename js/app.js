/**
 * TeleDrive Ultra - Mobile & Desktop Hybrid Application Controller
 * Manages category filters, bottom navigation, 3D cards, Ghost Camera, sound & haptics.
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
            const fileName = `GhostPhoto_${Date.now()}.jpg`;
            await App.uploadBlobDirectly(blob, fileName, 'image/jpeg');
        }, 'image/jpeg', 0.95);
    }
};

const App = {
    searchQuery: '',
    activeCategory: 'all',

    init() {
        this.refreshUI();
        this.initKeyboardShortcuts();
    },

    initKeyboardShortcuts() {
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
                MediaPlayer.close();
                GhostCamera.close();
                App.closeProfileDrawer();
                App.closeSettingsModal();
                ThemeEngine.toggleThemePicker();
            }
        });
    },

    refreshUI() {
        this.renderBreadcrumbs();
        this.renderFolders();
        this.renderFiles();
        this.renderSidebarFolders();
        this.updateBadgesAndStorage();

        if (window.lucide) lucide.createIcons();
    },

    navigateView(viewName) {
        if (window.FX) FX.tap();
        StorageManager.currentView = viewName;

        // Update Desktop Nav
        document.querySelectorAll('.nav-item').forEach(item => {
            if (item.getAttribute('data-nav') === viewName) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });

        // Update Mobile Bottom Nav
        document.querySelectorAll('.bottom-nav-btn').forEach(btn => {
            if (btn.getAttribute('data-bottom-nav') === viewName) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });

        this.refreshUI();
    },

    filterCategory(cat) {
        if (window.FX) FX.tap();
        this.activeCategory = cat;

        document.querySelectorAll('.cat-chip').forEach(chip => {
            if (chip.getAttribute('data-cat') === cat) {
                chip.classList.add('active');
            } else {
                chip.classList.remove('active');
            }
        });

        this.renderFiles();
    },

    setViewMode(mode) {
        if (window.FX) FX.tap();
        StorageManager.viewMode = mode;
        const gridBtn = document.getElementById('viewGridBtn');
        const listBtn = document.getElementById('viewListBtn');

        if (mode === 'grid') {
            gridBtn.className = 'p-1 rounded text-theme-primary bg-theme-primary/10';
            listBtn.className = 'p-1 rounded text-theme-muted hover:text-white';
        } else {
            gridBtn.className = 'p-1 rounded text-theme-muted hover:text-white';
            listBtn.className = 'p-1 rounded text-theme-primary bg-theme-primary/10';
        }
        this.renderFiles();
    },

    openFolder(folderId) {
        if (window.FX) FX.tap('medium');
        StorageManager.currentFolderId = folderId;
        StorageManager.currentView = 'all';
        this.refreshUI();
    },

    renderBreadcrumbs() {
        const container = document.getElementById('breadcrumbs');
        if (!container) return;

        const path = StorageManager.getFolderPath();
        let html = `
            <button onclick="App.openFolder('root')" class="flex items-center gap-1 text-theme-primary hover:underline font-bold">
                <i data-lucide="home" class="w-3.5 h-3.5"></i>
                <span>Home</span>
            </button>
        `;

        path.forEach(folder => {
            html += `
                <span class="text-theme-muted text-[11px]">/</span>
                <button onclick="App.openFolder('${folder.id}')" class="text-white hover:text-theme-primary font-semibold truncate max-w-[100px]">
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
                <span class="text-[9px] font-mono px-1.5 py-0.5 rounded bg-white/5 text-theme-primary">Topic</span>
            </a>
        `).join('');
    },

    renderFolders() {
        const section = document.getElementById('foldersSection');
        const grid = document.getElementById('foldersGrid');
        if (!section || !grid) return;

        if (StorageManager.currentView !== 'all' || this.activeCategory !== 'all') {
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
                <div class="folder-card tilt-3d p-3.5 cursor-pointer group" onclick="App.openFolder('${folder.id}')">
                    <div class="flex items-start justify-between mb-2.5">
                        <div class="p-2.5 rounded-xl bg-theme-primary/10 text-theme-primary border border-theme-primary/20 group-hover:scale-110 transition-transform">
                            <i data-lucide="folder" class="w-5 h-5"></i>
                        </div>
                        <span class="text-[8px] uppercase font-bold tracking-wider px-1.5 py-0.2 rounded-full bg-cyan-400/10 text-cyan-400 border border-cyan-400/20">
                            Topic
                        </span>
                    </div>
                    <h4 class="text-xs font-bold text-white truncate mb-0.5">${folder.name}</h4>
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

        // Apply Category Filter
        if (this.activeCategory === 'video') {
            files = files.filter(f => ['mp4', 'mkv', 'webm', 'mov'].includes(f.ext.toLowerCase()));
        } else if (this.activeCategory === 'audio') {
            files = files.filter(f => ['mp3', 'wav', 'flac', 'ogg'].includes(f.ext.toLowerCase()));
        } else if (this.activeCategory === 'photo') {
            files = files.filter(f => ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(f.ext.toLowerCase()));
        } else if (this.activeCategory === 'doc') {
            files = files.filter(f => ['pdf', 'doc', 'docx', 'txt', 'md', 'xlsx'].includes(f.ext.toLowerCase()));
        } else if (this.activeCategory === 'archive') {
            files = files.filter(f => ['zip', 'rar', 'tar', '7z'].includes(f.ext.toLowerCase()));
        } else if (this.activeCategory === 'vault') {
            files = files.filter(f => f.isVault);
        }

        // Apply Search Filter
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
            <div class="file-card tilt-3d p-3 flex flex-col group">
                <div class="flex items-start justify-between mb-2">
                    <div class="p-2.5 rounded-xl ${iconData.bg} ${iconData.color} border ${iconData.border}">
                        <i data-lucide="${iconData.icon}" class="w-5 h-5"></i>
                    </div>
                    <div class="flex items-center gap-1 opacity-80 group-hover:opacity-100 transition-opacity">
                        ${file.isEncrypted ? '<span title="AES-256 Encrypted"><i data-lucide="lock" class="w-3.5 h-3.5 text-emerald-400"></i></span>' : ''}
                        ${file.shared ? '<span title="mdsafiislam.tech Public Share Active"><i data-lucide="globe" class="w-3.5 h-3.5 text-cyan-400"></i></span>' : ''}
                    </div>
                </div>

                <div class="cursor-pointer mb-2.5" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    <h4 class="text-xs font-bold text-white truncate mb-0.5 hover:text-theme-primary transition-colors">${file.name}</h4>
                    <span class="text-[10px] text-theme-muted font-mono">${sizeStr} • ${file.ext.toUpperCase()}</span>
                </div>

                <div class="mt-auto pt-2 border-t border-theme-border/60 flex items-center justify-between text-xs">
                    ${!isTrash ? `
                        <button onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})" class="p-1.5 rounded-lg text-theme-primary hover:bg-theme-primary/10" title="Play">
                            <i data-lucide="play" class="w-3.5 h-3.5"></i>
                        </button>
                        <button onclick="App.shareFile('${file.id}')" class="p-1.5 rounded-lg text-theme-muted hover:text-cyan-400" title="Share">
                            <i data-lucide="share-2" class="w-3.5 h-3.5"></i>
                        </button>
                        <button onclick="App.downloadFile('${file.id}')" class="p-1.5 rounded-lg text-theme-muted hover:text-white" title="Download">
                            <i data-lucide="download" class="w-3.5 h-3.5"></i>
                        </button>
                        <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1.5 rounded-lg text-theme-muted hover:text-rose-400" title="Trash">
                            <i data-lucide="trash-2" class="w-3.5 h-3.5"></i>
                        </button>
                    ` : `
                        <button onclick="StorageManager.restoreFromTrash('${file.id}'); App.refreshUI();" class="text-[10px] text-emerald-400 font-bold hover:underline">Restore</button>
                        <button onclick="StorageManager.permanentDelete('${file.id}'); App.refreshUI();" class="text-[10px] text-rose-400 font-bold hover:underline">Delete</button>
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
            <div class="p-2.5 rounded-xl bg-theme-surface border border-theme-border flex items-center justify-between gap-3 group">
                <div class="flex items-center gap-2.5 min-w-0 flex-1 cursor-pointer" onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    <div class="p-2 rounded-lg ${iconData.bg} ${iconData.color}">
                        <i data-lucide="${iconData.icon}" class="w-4 h-4"></i>
                    </div>
                    <div class="truncate">
                        <div class="text-xs font-bold text-white truncate">${file.name}</div>
                        <div class="text-[10px] text-theme-muted font-mono">${dateStr}</div>
                    </div>
                </div>
                <div class="flex items-center gap-3 text-xs font-mono text-theme-muted">
                    <span>${sizeStr}</span>
                    <button onclick="MediaPlayer.open(${JSON.stringify(file).replace(/"/g, '&quot;')})" class="p-1 hover:text-theme-primary"><i data-lucide="play" class="w-3.5 h-3.5"></i></button>
                    <button onclick="App.shareFile('${file.id}')" class="p-1 hover:text-cyan-400"><i data-lucide="share-2" class="w-3.5 h-3.5"></i></button>
                    <button onclick="StorageManager.moveToTrash('${file.id}'); App.refreshUI();" class="p-1 hover:text-rose-400"><i data-lucide="trash-2" class="w-3.5 h-3.5"></i></button>
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
            zip: { icon: 'archive', color: 'text-amber-400', bg: 'bg-amber-500/10', border: 'border-amber-500/20' }
        };
        return map[ext.toLowerCase()] || { icon: 'file', color: 'text-theme-muted', bg: 'bg-white/5', border: 'border-white/10' };
    },

    async promptNewFolder() {
        if (window.FX) FX.tap();
        const name = prompt('Folder name (Mirrored as Telegram Forum Topic):');
        if (!name || !name.trim()) return;

        const topicResult = await TelegramEngine.createFolderTopic(name.trim());
        const threadId = topicResult ? topicResult.message_thread_id : null;

        StorageManager.createFolder(name.trim(), StorageManager.currentFolderId, threadId);
        if (window.FX) FX.playSuccess();
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
    },

    handleDragLeave(e) {
        e.preventDefault();
    },

    async handleDrop(e) {
        e.preventDefault();
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
        bannerText.textContent = `Encrypting & Uploading ${name}...`;
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
            bannerText.textContent = 'Upload Verified!';
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

    shareFile(fileId) {
        if (window.FX) FX.tap();
        StorageManager.toggleShare(fileId);
        const link = `https://mdsafiislam.tech/#share=${fileId}`;
        navigator.clipboard.writeText(link);
        alert(`Public Share Link Copied to Clipboard!\n\n${link}\n\nAnyone with this link can download through mdsafiislam.tech.`);
        this.refreshUI();
    },

    downloadFile(fileId) {
        if (window.FX) FX.tap();
        const file = StorageManager.getActiveData().files.find(f => f.id === fileId);
        if (!file) return;

        if (file.blobUrl) {
            const a = document.createElement('a');
            a.href = file.blobUrl;
            a.download = file.name;
            a.click();
        } else {
            alert(`Initiating download for ${file.name}...`);
        }
    },

    handleSearch(e) {
        this.searchQuery = e.target.value;
        this.renderFiles();
    },

    updateBadgesAndStorage() {
        const data = StorageManager.getActiveData();
        const allFiles = data.files.filter(f => !f.isTrash);
        const mediaFiles = data.files.filter(f => !f.isTrash && ['mp4', 'mkv', 'mp3', 'wav', 'jpg', 'png'].includes(f.ext.toLowerCase()));

        const bAll = document.getElementById('badgeAllCount');
        const bMedia = document.getElementById('badgeMediaCount');
        if (bAll) bAll.textContent = allFiles.length;
        if (bMedia) bMedia.textContent = mediaFiles.length;

        const usage = StorageManager.getStorageUsage();
        const storageText = document.getElementById('storageUsedText');
        if (storageText) storageText.textContent = `${usage.formatted} stored`;
    },

    openProfileDrawer() {
        if (window.FX) FX.tap();
        document.getElementById('profileDrawer').classList.remove('hidden');
    },

    closeProfileDrawer() {
        document.getElementById('profileDrawer').classList.add('hidden');
    },

    toggleSoundFX(e) {
        if (window.FX) {
            FX.soundEnabled = e.target.checked;
            localStorage.setItem('teledrive_sound', e.target.checked ? 'true' : 'false');
            if (e.target.checked) FX.playSuccess();
        }
    },

    toggleHaptics(e) {
        if (window.FX) {
            FX.hapticsEnabled = e.target.checked;
            localStorage.setItem('teledrive_haptics', e.target.checked ? 'true' : 'false');
            if (e.target.checked) FX.haptic('medium');
        }
    },

    openSettingsModal() {
        if (window.FX) FX.tap();
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
        alert('TeleDrive Ultra Configuration Saved!');
    }
};

document.addEventListener('DOMContentLoaded', () => {
    App.init();
});
