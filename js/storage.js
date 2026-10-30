/**
 * TeleDrive Ultra - Virtual File System & Local-First State Manager
 * Maintains folders, files, breadcrumbs, tags, and trash bin with IndexedDB/LocalStorage.
 */

const StorageManager = {
    currentFolderId: 'root',
    currentView: 'all', // 'all', 'media', 'vault', 'shares', 'trash'
    viewMode: 'grid', // 'grid' or 'list'
    activeDecoyMode: false,

    // Real Vault State
    data: {
        folders: [
            { id: 'f_movies', name: 'Movies & Series', parentId: 'root', threadId: null, icon: 'film', createdAt: Date.now() - 86400000 * 2 },
            { id: 'f_docs', name: 'Work Documents', parentId: 'root', threadId: null, icon: 'file-text', createdAt: Date.now() - 86400000 * 5 },
            { id: 'f_photos', name: 'Family & Camera', parentId: 'root', threadId: null, icon: 'camera', createdAt: Date.now() - 86400000 * 10 }
        ],
        files: [
            {
                id: 'demo_1',
                name: 'TeleDrive_Security_Whitepaper.pdf',
                folderId: 'f_docs',
                size: 2450000,
                type: 'application/pdf',
                ext: 'pdf',
                hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                telegramFileId: 'demo_file_1',
                threadId: null,
                isEncrypted: true,
                isVault: false,
                isTrash: false,
                shared: false,
                createdAt: Date.now() - 3600000 * 3
            },
            {
                id: 'demo_2',
                name: 'Cyberpunk_Atmosphere_Soundtrack.mp3',
                folderId: 'f_movies',
                size: 8900000,
                type: 'audio/mp3',
                ext: 'mp3',
                hash: '1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b',
                telegramFileId: 'demo_file_2',
                threadId: null,
                isEncrypted: false,
                isVault: false,
                isTrash: false,
                shared: true,
                createdAt: Date.now() - 3600000 * 12
            }
        ]
    },

    // Decoy Vault State (Shown when Decoy PIN is entered under coercion)
    decoyData: {
        folders: [
            { id: 'f_recipes', name: 'Cooking Recipes', parentId: 'root', threadId: null, icon: 'book-open', createdAt: Date.now() }
        ],
        files: [
            {
                id: 'decoy_1',
                name: 'Grandmas_Apple_Pie.txt',
                folderId: 'f_recipes',
                size: 12000,
                type: 'text/plain',
                ext: 'txt',
                hash: 'decoy_hash_1',
                telegramFileId: 'decoy_file_1',
                threadId: null,
                isEncrypted: false,
                isVault: false,
                isTrash: false,
                shared: false,
                createdAt: Date.now()
            }
        ]
    },

    init() {
        this.load();
    },

    load() {
        const saved = localStorage.getItem('teledrive_vfs_data');
        if (saved) {
            try {
                this.data = JSON.parse(saved);
            } catch (e) {
                console.error('Error loading VFS:', e);
            }
        }
    },

    save() {
        if (!this.activeDecoyMode) {
            localStorage.setItem('teledrive_vfs_data', JSON.stringify(this.data));
        }
    },

    getActiveData() {
        return this.activeDecoyMode ? this.decoyData : this.data;
    },

    getFolders(parentId = this.currentFolderId) {
        return this.getActiveData().folders.filter(f => f.parentId === parentId);
    },

    getFolderById(id) {
        if (id === 'root') return { id: 'root', name: 'Home' };
        return this.getActiveData().folders.find(f => f.id === id);
    },

    createFolder(name, parentId = this.currentFolderId, threadId = null) {
        const id = 'f_' + Date.now().toString(36) + Math.random().toString(36).substring(2, 5);
        const folder = {
            id,
            name,
            parentId,
            threadId,
            icon: 'folder',
            createdAt: Date.now()
        };
        this.getActiveData().folders.push(folder);
        this.save();
        return folder;
    },

    deleteFolder(id) {
        const target = this.getActiveData();
        // Remove folder
        target.folders = target.folders.filter(f => f.id !== id);
        // Move files in this folder to trash
        target.files.forEach(file => {
            if (file.folderId === id) file.isTrash = true;
        });
        this.save();
    },

    getFiles(folderId = this.currentFolderId, view = this.currentView) {
        let files = this.getActiveData().files;

        if (view === 'trash') {
            return files.filter(f => f.isTrash);
        }

        // Exclude trash files for standard views
        files = files.filter(f => !f.isTrash);

        if (view === 'media') {
            return files.filter(f => ['mp4', 'mkv', 'webm', 'mov', 'mp3', 'wav', 'flac', 'ogg', 'jpg', 'jpeg', 'png', 'gif', 'webp'].includes(f.ext.toLowerCase()));
        }

        if (view === 'vault') {
            return files.filter(f => f.isVault);
        }

        if (view === 'shares') {
            return files.filter(f => f.shared);
        }

        // Standard 'all' view: match current folder
        return files.filter(f => f.folderId === folderId);
    },

    addFile(fileObj) {
        this.getActiveData().files.unshift(fileObj);
        this.save();
    },

    moveToTrash(fileId) {
        const file = this.getActiveData().files.find(f => f.id === fileId);
        if (file) {
            file.isTrash = true;
            this.save();
        }
    },

    restoreFromTrash(fileId) {
        const file = this.getActiveData().files.find(f => f.id === fileId);
        if (file) {
            file.isTrash = false;
            this.save();
        }
    },

    permanentDelete(fileId) {
        const target = this.getActiveData();
        target.files = target.files.filter(f => f.id !== fileId);
        this.save();
    },

    toggleShare(fileId) {
        const file = this.getActiveData().files.find(f => f.id === fileId);
        if (file) {
            file.shared = !file.shared;
            this.save();
            return file.shared;
        }
        return false;
    },

    getFolderPath(folderId = this.currentFolderId) {
        const path = [];
        let curr = this.getFolderById(folderId);
        while (curr && curr.id !== 'root') {
            path.unshift(curr);
            curr = this.getFolderById(curr.parentId);
        }
        return path;
    },

    getStorageUsage() {
        const allFiles = this.getActiveData().files.filter(f => !f.isTrash);
        const totalBytes = allFiles.reduce((acc, f) => acc + (f.size || 0), 0);
        return {
            totalBytes,
            totalFiles: allFiles.length,
            formatted: TelegramEngine.formatBytes(totalBytes)
        };
    }
};

StorageManager.init();
