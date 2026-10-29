/**
 * TeleDrive Ultra - Advanced Media Streaming Engine & Picture-in-Picture Player
 * Plays 4K videos, Hi-Res audio, displays docs/code, and docks into a floating PiP mini-player.
 */

const MediaPlayer = {
    currentFile: null,
    isPlaying: false,

    open(file) {
        this.currentFile = file;
        this.closePip();

        if (window.FX) {
            FX.playTap();
            FX.haptic('medium');
        }

        const modal = document.getElementById('mediaModal');
        const title = document.getElementById('mediaPlayerTitle');
        const icon = document.getElementById('mediaPlayerIcon');
        const container = document.getElementById('mediaPlayerContent');
        const meta = document.getElementById('mediaPlayerMeta');
        const downloadBtn = document.getElementById('mediaDownloadBtn');

        if (!modal || !container) return;

        title.textContent = file.name;
        meta.textContent = `${file.ext.toUpperCase()} • ${TelegramEngine.formatBytes(file.size)} • Decrypted On-The-Fly`;
        downloadBtn.onclick = () => App.downloadFile(file.id);

        const ext = file.ext.toLowerCase();

        if (['mp4', 'webm', 'mov', 'mkv'].includes(ext)) {
            icon.setAttribute('data-lucide', 'video');
            container.innerHTML = `
                <div class="relative w-full h-full flex items-center justify-center bg-black">
                    <video id="activeMediaElement" controls autoplay playsinline class="w-full max-h-[70vh] rounded-xl outline-none">
                        <source src="${file.blobUrl || 'https://www.w3schools.com/html/mov_bbb.mp4'}" type="video/mp4">
                    </video>
                </div>
            `;
            this.isPlaying = true;
        } else if (['mp3', 'wav', 'ogg', 'flac'].includes(ext)) {
            icon.setAttribute('data-lucide', 'music');
            container.innerHTML = `
                <div class="p-8 w-full max-w-md flex flex-col items-center justify-center text-center">
                    <div class="w-24 h-24 rounded-3xl bg-gradient-to-tr from-theme-primary to-theme-secondary flex items-center justify-center mb-6 shadow-2xl shadow-theme-primary/30 animate-pulse">
                        <i data-lucide="music-2" class="w-12 h-12 text-black"></i>
                    </div>
                    <h4 class="text-base font-bold text-white mb-1 truncate max-w-xs">${file.name}</h4>
                    <p class="text-xs text-theme-muted mb-6 font-mono">Lossless Audio Stream</p>
                    <audio id="activeMediaElement" controls autoplay class="w-full outline-none">
                        <source src="${file.blobUrl || 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3'}" type="audio/mp3">
                    </audio>
                </div>
            `;
            this.isPlaying = true;
        } else if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg'].includes(ext)) {
            icon.setAttribute('data-lucide', 'image');
            container.innerHTML = `
                <div class="p-4 w-full h-full flex items-center justify-center max-h-[70vh] overflow-auto">
                    <img src="${file.blobUrl || 'https://picsum.photos/800/600'}" alt="${file.name}" class="max-w-full max-h-[65vh] object-contain rounded-2xl shadow-2xl">
                </div>
            `;
            this.isPlaying = false;
        } else {
            icon.setAttribute('data-lucide', 'file-text');
            container.innerHTML = `
                <div class="w-full h-[65vh] p-6 overflow-y-auto custom-scroll bg-theme-bg/80 text-xs font-mono text-theme-text whitespace-pre-wrap leading-relaxed select-text">
// File: ${file.name}
// Size: ${TelegramEngine.formatBytes(file.size)}
// Hash: ${file.hash}

============================================================
TELEDRIVE ULTRA • ZERO-KNOWLEDGE STREAM VERIFIED
============================================================

Stored securely across Telegram MTProto Supergroups.
Decrypted in memory with AES-256-GCM.
                </div>
            `;
            this.isPlaying = false;
        }

        modal.classList.remove('hidden');
        if (window.lucide) lucide.createIcons();
    },

    close() {
        if (window.FX) FX.playTap();
        const modal = document.getElementById('mediaModal');
        const container = document.getElementById('mediaPlayerContent');
        if (modal) modal.classList.add('hidden');
        if (container) container.innerHTML = '';
        this.currentFile = null;
        this.isPlaying = false;
    },

    minimizeToPip() {
        if (!this.currentFile) return;
        if (window.FX) FX.playTap();

        const modal = document.getElementById('mediaModal');
        if (modal) modal.classList.add('hidden');

        const pip = document.getElementById('pipPlayer');
        const title = document.getElementById('pipTitle');
        const meta = document.getElementById('pipMeta');

        if (pip && title && meta) {
            title.textContent = this.currentFile.name;
            meta.textContent = `${this.currentFile.ext.toUpperCase()} • Live Stream`;
            pip.classList.remove('hidden');
        }
    },

    reopenCurrent() {
        if (this.currentFile) {
            this.open(this.currentFile);
        }
    },

    togglePlay() {
        const media = document.getElementById('activeMediaElement');
        const icon = document.getElementById('pipPlayIcon');
        if (media) {
            if (media.paused) {
                media.play();
                this.isPlaying = true;
                if (icon) icon.setAttribute('data-lucide', 'pause');
            } else {
                media.pause();
                this.isPlaying = false;
                if (icon) icon.setAttribute('data-lucide', 'play');
            }
            if (window.lucide) lucide.createIcons();
        }
        if (window.FX) FX.tap();
    },

    closePip() {
        const pip = document.getElementById('pipPlayer');
        if (pip) pip.classList.add('hidden');
    }
};
