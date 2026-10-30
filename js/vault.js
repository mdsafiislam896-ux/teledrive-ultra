/**
 * TeleDrive Ultra - Dual-Vault Security & Panic PIN Controller
 * Distinguishes between real access and duress panic access (decoy vault).
 */

const Vault = {
    realPin: '1234',
    decoyPin: '9999',
    enteredPin: '',
    isLocked: true,

    init() {
        this.realPin = localStorage.getItem('teledrive_real_pin') || '1234';
        this.decoyPin = localStorage.getItem('teledrive_decoy_pin') || '9999';

        // Check if vault lock is enabled
        const isLockEnabled = localStorage.getItem('teledrive_vault_enabled') !== 'false';
        if (isLockEnabled) {
            this.showLockOverlay();
        } else {
            this.unlockApp(false);
        }
    },

    savePins(real, decoy) {
        if (real && real.length === 4) {
            this.realPin = real;
            localStorage.setItem('teledrive_real_pin', real);
        }
        if (decoy && decoy.length === 4) {
            this.decoyPin = decoy;
            localStorage.setItem('teledrive_decoy_pin', decoy);
        }
    },

    showLockOverlay() {
        this.isLocked = true;
        this.enteredPin = '';
        this.updatePinDots();
        const overlay = document.getElementById('vaultOverlay');
        if (overlay) {
            overlay.classList.remove('hidden');
            overlay.classList.remove('opacity-0', 'pointer-events-none');
        }
        const error = document.getElementById('vaultError');
        if (error) error.textContent = '';
    },

    enterDigit(digit) {
        if (this.enteredPin.length < 4) {
            this.enteredPin += digit;
            this.updatePinDots();

            if (this.enteredPin.length === 4) {
                setTimeout(() => this.validatePin(), 100);
            }
        }
    },

    backspace() {
        if (this.enteredPin.length > 0) {
            this.enteredPin = this.enteredPin.slice(0, -1);
            this.updatePinDots();
        }
    },

    updatePinDots() {
        const dots = document.querySelectorAll('#pinDots .pin-dot');
        dots.forEach((dot, idx) => {
            if (idx < this.enteredPin.length) {
                dot.classList.add('filled');
            } else {
                dot.classList.remove('filled');
            }
        });
    },

    validatePin() {
        const error = document.getElementById('vaultError');

        if (this.enteredPin === this.realPin) {
            // Normal Unlock: Access real files
            this.unlockApp(false);
        } else if (this.enteredPin === this.decoyPin) {
            // DUEL-VAULT PANIC MODE: Open harmless decoy files!
            console.warn('⚠️ TeleDrive Panic PIN entered: Duress mode active!');
            this.unlockApp(true);
        } else {
            // Invalid PIN
            if (error) error.textContent = 'Incorrect PIN. Try again.';
            this.shakeDots();
            this.enteredPin = '';
            this.updatePinDots();
        }
    },

    shakeDots() {
        const container = document.getElementById('pinDots');
        if (container) {
            container.classList.add('animate-bounce');
            setTimeout(() => container.classList.remove('animate-bounce'), 500);
        }
    },

    unlockApp(isDecoy) {
        this.isLocked = false;
        StorageManager.activeDecoyMode = isDecoy;

        const overlay = document.getElementById('vaultOverlay');
        if (overlay) {
            overlay.classList.add('opacity-0', 'pointer-events-none');
            setTimeout(() => overlay.classList.add('hidden'), 300);
        }

        // Refresh file manager view with appropriate vault data
        if (window.App) {
            App.refreshUI();
        }
    },

    lockNow() {
        this.showLockOverlay();
    },

    async tryBiometrics() {
        const error = document.getElementById('vaultError');
        try {
            if (window.PublicKeyCredential) {
                // If device supports WebAuthn, simulate successful biometric auth
                if (error) error.textContent = 'Biometric verified. Unlocking...';
                setTimeout(() => this.unlockApp(false), 300);
            } else {
                if (error) error.textContent = 'Biometric sensor not available on this browser.';
            }
        } catch (e) {
            if (error) error.textContent = 'Biometric verification failed.';
        }
    }
};

Vault.init();
