/**
 * TeleDrive Ultra - Zero-Knowledge Cryptographic Engine
 * Provides military-grade AES-256-GCM encryption with PBKDF2 key derivation.
 * All files and metadata are encrypted on-device before reaching Telegram.
 */

const CryptoEngine = {
    // Default system master salt
    SALT_DEFAULT: 'teledrive-ultra-zero-knowledge-salt-2026',

    /**
     * Derive a 256-bit AES-GCM CryptoKey from a user passphrase using PBKDF2
     */
    async deriveKey(passphrase, saltStr = this.SALT_DEFAULT) {
        const enc = new TextEncoder();
        const baseKey = await window.crypto.subtle.importKey(
            'raw',
            enc.encode(passphrase),
            { name: 'PBKDF2' },
            false,
            ['deriveKey']
        );

        return await window.crypto.subtle.deriveKey(
            {
                name: 'PBKDF2',
                salt: enc.encode(saltStr),
                iterations: 100000,
                hash: 'SHA-256'
            },
            baseKey,
            { name: 'AES-GCM', length: 256 },
            false,
            ['encrypt', 'decrypt']
        );
    },

    /**
     * Encrypt an ArrayBuffer with AES-256-GCM.
     * Returns a new Blob/Buffer prepended with the 12-byte IV.
     */
    async encryptBuffer(buffer, key) {
        const iv = window.crypto.getRandomValues(new Uint8Array(12));
        const encryptedData = await window.crypto.subtle.encrypt(
            {
                name: 'AES-GCM',
                iv: iv
            },
            key,
            buffer
        );

        // Prepend IV (12 bytes) to the encrypted payload
        const combined = new Uint8Array(iv.length + encryptedData.byteLength);
        combined.set(iv, 0);
        combined.set(new Uint8Array(encryptedData), iv.length);
        return combined.buffer;
    },

    /**
     * Decrypt a combined buffer (12-byte IV + AES-GCM ciphertext)
     */
    async decryptBuffer(combinedBuffer, key) {
        const combined = new Uint8Array(combinedBuffer);
        const iv = combined.slice(0, 12);
        const data = combined.slice(12);

        return await window.crypto.subtle.decrypt(
            {
                name: 'AES-GCM',
                iv: iv
            },
            key,
            data
        );
    },

    /**
     * Encrypt a text string (used for stealth metadata & filenames)
     */
    async encryptText(text, key) {
        const enc = new TextEncoder();
        const buf = enc.encode(text);
        const encrypted = await this.encryptBuffer(buf, key);
        return this.arrayBufferToBase64(encrypted);
    },

    /**
     * Decrypt a base64 encrypted text string
     */
    async decryptText(base64Str, key) {
        const buf = this.base64ToArrayBuffer(base64Str);
        const decrypted = await this.decryptBuffer(buf, key);
        const dec = new TextDecoder();
        return dec.decode(decrypted);
    },

    /**
     * Compute SHA-256 hash of a file for integrity check & de-duplication
     */
    async computeHash(buffer) {
        const hashBuf = await window.crypto.subtle.digest('SHA-256', buffer);
        const hashArr = Array.from(new Uint8Array(hashBuf));
        return hashArr.map(b => b.toString(16).padStart(2, '0')).join('');
    },

    // Helpers
    arrayBufferToBase64(buffer) {
        let binary = '';
        const bytes = new Uint8Array(buffer);
        const len = bytes.byteLength;
        for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return window.btoa(binary);
    },

    base64ToArrayBuffer(base64) {
        const binaryString = window.atob(base64);
        const len = binaryString.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        return bytes.buffer;
    }
};
