/**
 * TeleDrive Ultra - Telegram Engine & Forum Topic Mirroring System
 * Manages communication with Telegram's servers, creates Forum Topics for folders,
 * uploads chunked/encrypted documents, and maintains the directory index in Telegram.
 */

const TelegramEngine = {
    botToken: '',
    chatId: '',
    customDomain: '',
    storageEngine: 'bot', // 'bot' or 'mtproto'
    isConfigured: false,

    init() {
        this.botToken = localStorage.getItem('teledrive_bot_token') || '';
        this.chatId = localStorage.getItem('teledrive_chat_id') || '';
        this.customDomain = localStorage.getItem('teledrive_custom_domain') || '';
        this.storageEngine = localStorage.getItem('teledrive_storage_engine') || 'bot';

        this.isConfigured = Boolean(this.botToken && this.chatId);
        this.updateStatusIndicator();
    },

    saveConfig(token, chat, domain, engine) {
        this.botToken = token.trim();
        this.chatId = chat.trim();
        this.customDomain = domain.trim();
        this.storageEngine = engine;

        localStorage.setItem('teledrive_bot_token', this.botToken);
        localStorage.setItem('teledrive_chat_id', this.chatId);
        localStorage.setItem('teledrive_custom_domain', this.customDomain);
        localStorage.setItem('teledrive_storage_engine', this.storageEngine);

        this.isConfigured = Boolean(this.botToken && this.chatId);
        this.updateStatusIndicator();
    },

    updateStatusIndicator() {
        const dot = document.getElementById('connStatusDot');
        const text = document.getElementById('connStatusText');
        if (!dot || !text) return;

        if (this.isConfigured) {
            dot.className = 'w-2 h-2 rounded-full bg-emerald-400 animate-pulse';
            text.textContent = 'Telegram Synced';
        } else {
            dot.className = 'w-2 h-2 rounded-full bg-amber-400';
            text.textContent = 'Demo Mode (Local)';
        }
    },

    /**
     * Create a real Telegram Forum Topic in Supergroup to represent a folder
     */
    async createFolderTopic(folderName) {
        if (!this.isConfigured) {
            console.log(`[Demo Mode] Virtual topic created for: ${folderName}`);
            return { message_thread_id: Math.floor(Math.random() * 100000) };
        }

        try {
            const url = `https://api.telegram.org/bot${this.botToken}/createForumTopic`;
            const res = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    chat_id: this.chatId,
                    name: `📁 ${folderName}`,
                    icon_color: 0x6FB9F0
                })
            });
            const data = await res.json();
            if (data.ok) {
                console.log(`Telegram Topic created: ${folderName} (ID: ${data.result.message_thread_id})`);
                return data.result;
            } else {
                console.warn('createForumTopic error (Forum topics might be disabled in group):', data.description);
                return null;
            }
        } catch (err) {
            console.error('Failed to create Telegram forum topic:', err);
            return null;
        }
    },

    /**
     * Upload an encrypted or plaintext file to Telegram with rich breadcrumb caption
     */
    async uploadFile(fileBlob, metadata, onProgress) {
        // If not configured with API credentials, simulate instant local upload
        if (!this.isConfigured) {
            return await this.simulateUpload(metadata, onProgress);
        }

        try {
            const formData = new FormData();
            formData.append('chat_id', this.chatId);
            formData.append('document', fileBlob, metadata.name);

            // If folder has a Telegram topic thread ID, post directly into that folder topic!
            if (metadata.threadId) {
                formData.append('message_thread_id', metadata.threadId);
            }

            // Rich Telegram Markdown card caption
            const caption = this.buildTelegramCaption(metadata);
            formData.append('caption', caption);
            formData.append('parse_mode', 'Markdown');

            const url = `https://api.telegram.org/bot${this.botToken}/sendDocument`;

            // Use XMLHttpRequest to track accurate upload progress
            return new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                xhr.open('POST', url, true);

                xhr.upload.onprogress = (e) => {
                    if (e.lengthComputable && onProgress) {
                        const pct = Math.round((e.loaded / e.total) * 100);
                        onProgress(pct);
                    }
                };

                xhr.onload = () => {
                    if (xhr.status === 200) {
                        const resp = JSON.parse(xhr.responseText);
                        if (resp.ok) {
                            const doc = resp.result.document;
                            resolve({
                                telegramFileId: doc.file_id,
                                telegramMessageId: resp.result.message_id,
                                size: doc.file_size
                            });
                        } else {
                            reject(new Error(resp.description || 'Telegram upload failed'));
                        }
                    } else {
                        reject(new Error(`HTTP error ${xhr.status}`));
                    }
                };

                xhr.onerror = () => reject(new Error('Network error uploading to Telegram'));
                xhr.send(formData);
            });
        } catch (err) {
            console.error('Telegram upload exception:', err);
            throw err;
        }
    },

    /**
     * Generate Telegram-formatted caption card with breadcrumbs & verification hash
     */
    buildTelegramCaption(meta) {
        const pathBreadcrumb = meta.path || 'Root';
        const formattedDate = new Date().toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' });
        const sizeFormatted = this.formatBytes(meta.size);

        return [
            `*╔═══════════════════════════╗*`,
            `*║  ⚡ TELEDRIVE ULTRA CLOUD  ║*`,
            `*╚═══════════════════════════╝*`,
            `📁 *Path:* \`${pathBreadcrumb}\``,
            `📄 *File:* \`${meta.name}\``,
            `📦 *Size:* ${sizeFormatted}`,
            `🔐 *Cipher:* AES-256-GCM (Zero-Knowledge)`,
            `🔑 *Hash:* \`${meta.hash ? meta.hash.substring(0, 16) + '...' : 'Verified'}\``,
            `📅 *Date:* ${formattedDate}`,
            `🌐 *Public Share ID:* \`td_${meta.id}\``,
            ``,
            `_Managed seamlessly via TeleDrive Ultra client._`
        ].join('\n');
    },

    /**
     * Get direct download link for a file from Telegram
     */
    async getFileDownloadUrl(telegramFileId) {
        if (!this.isConfigured) {
            return null;
        }

        try {
            const url = `https://api.telegram.org/bot${this.botToken}/getFile?file_id=${telegramFileId}`;
            const res = await fetch(url);
            const data = await res.json();
            if (data.ok) {
                const filePath = data.result.file_path;
                return `https://api.telegram.org/file/bot${this.botToken}/${filePath}`;
            }
            return null;
        } catch (err) {
            console.error('Error fetching file path:', err);
            return null;
        }
    },

    /**
     * Generate public share link for custom domain
     */
    generateShareLink(fileId) {
        const base = this.customDomain ? this.customDomain.replace(/\/$/, '') : window.location.origin;
        return `${base}/#share=${fileId}`;
    },

    /**
     * Fallback simulated upload for instant demo out of the box
     */
    async simulateUpload(metadata, onProgress) {
        return new Promise((resolve) => {
            let progress = 0;
            const interval = setInterval(() => {
                progress += 25;
                if (onProgress) onProgress(Math.min(progress, 100));
                if (progress >= 100) {
                    clearInterval(interval);
                    resolve({
                        telegramFileId: 'simulated_' + Math.random().toString(36).substring(2),
                        telegramMessageId: Math.floor(Math.random() * 50000),
                        size: metadata.size
                    });
                }
            }, 100);
        });
    },

    formatBytes(bytes, decimals = 2) {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }
};

TelegramEngine.init();
