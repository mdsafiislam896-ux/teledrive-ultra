/**
 * TeleDrive Ultra - 3D Reaction, Haptics, Audio FX & Animation Engine
 * Synthesizes next-gen audio feedback, hardware haptics, and 3D gyroscope/mouse parallax.
 */

const FX = {
    audioCtx: null,
    soundEnabled: true,
    hapticsEnabled: true,

    init() {
        this.soundEnabled = localStorage.getItem('teledrive_sound') !== 'false';
        this.hapticsEnabled = localStorage.getItem('teledrive_haptics') !== 'false';

        // Initialize Web Audio API on first user interaction
        const initAudio = () => {
            if (!this.audioCtx) {
                const AudioContext = window.AudioContext || window.webkitAudioContext;
                if (AudioContext) {
                    this.audioCtx = new AudioContext();
                }
            }
            window.removeEventListener('pointerdown', initAudio);
            window.removeEventListener('keydown', initAudio);
        };
        window.addEventListener('pointerdown', initAudio);
        window.addEventListener('keydown', initAudio);

        this.initTiltEngine();
        this.initRippleEngine();
    },

    // ==========================================
    // 🔊 SYNTHESIZED WEB AUDIO SOUND FX
    // ==========================================

    playTap() {
        if (!this.soundEnabled || !this.audioCtx) return;
        try {
            const ctx = this.audioCtx;
            if (ctx.state === 'suspended') ctx.resume();
            
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            
            osc.type = 'sine';
            osc.frequency.setValueAtTime(1400, ctx.currentTime);
            osc.frequency.exponentialRampToValueAtTime(800, ctx.currentTime + 0.04);
            
            gain.gain.setValueAtTime(0.08, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.04);
            
            osc.connect(gain);
            gain.connect(ctx.destination);
            
            osc.start();
            osc.stop(ctx.currentTime + 0.04);
        } catch (e) {}
    },

    playSuccess() {
        if (!this.soundEnabled || !this.audioCtx) return;
        try {
            const ctx = this.audioCtx;
            if (ctx.state === 'suspended') ctx.resume();

            const notes = [587.33, 880, 1174.66]; // D5, A5, D6
            notes.forEach((freq, idx) => {
                const osc = ctx.createOscillator();
                const gain = ctx.createGain();
                const startTime = ctx.currentTime + (idx * 0.06);
                
                osc.type = 'triangle';
                osc.frequency.setValueAtTime(freq, startTime);
                
                gain.gain.setValueAtTime(0.12, startTime);
                gain.gain.exponentialRampToValueAtTime(0.001, startTime + 0.18);
                
                osc.connect(gain);
                gain.connect(ctx.destination);
                
                osc.start(startTime);
                osc.stop(startTime + 0.18);
            });
        } catch (e) {}
    },

    playCamera() {
        if (!this.soundEnabled || !this.audioCtx) return;
        try {
            const ctx = this.audioCtx;
            if (ctx.state === 'suspended') ctx.resume();

            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            
            osc.type = 'square';
            osc.frequency.setValueAtTime(120, ctx.currentTime);
            osc.frequency.linearRampToValueAtTime(80, ctx.currentTime + 0.05);
            
            gain.gain.setValueAtTime(0.15, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.07);
            
            osc.connect(gain);
            gain.connect(ctx.destination);
            
            osc.start();
            osc.stop(ctx.currentTime + 0.07);
        } catch (e) {}
    },

    playLock() {
        if (!this.soundEnabled || !this.audioCtx) return;
        try {
            const ctx = this.audioCtx;
            if (ctx.state === 'suspended') ctx.resume();

            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            
            osc.type = 'sine';
            osc.frequency.setValueAtTime(220, ctx.currentTime);
            osc.frequency.exponentialRampToValueAtTime(60, ctx.currentTime + 0.12);
            
            gain.gain.setValueAtTime(0.25, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.12);
            
            osc.connect(gain);
            gain.connect(ctx.destination);
            
            osc.start();
            osc.stop(ctx.currentTime + 0.12);
        } catch (e) {}
    },

    // ==========================================
    // 📳 HARDWARE HAPTIC FEEDBACK (VIBRATION)
    // ==========================================

    haptic(type = 'light') {
        if (!this.hapticsEnabled || !navigator.vibrate) return;
        try {
            if (type === 'light') {
                navigator.vibrate(10);
            } else if (type === 'medium') {
                navigator.vibrate(25);
            } else if (type === 'success') {
                navigator.vibrate([15, 30, 25]);
            } else if (type === 'error') {
                navigator.vibrate([35, 50, 40]);
            }
        } catch (e) {}
    },

    tap(type = 'light') {
        this.playTap();
        this.haptic(type);
    },

    // ==========================================
    // 🧊 3D CARD PARALLAX TILT ENGINE
    // ==========================================

    initTiltEngine() {
        document.addEventListener('mousemove', (e) => {
            const cards = document.querySelectorAll('.tilt-3d');
            cards.forEach(card => {
                const rect = card.getBoundingClientRect();
                // Check if cursor is hovering or near the card
                if (
                    e.clientX >= rect.left &&
                    e.clientX <= rect.right &&
                    e.clientY >= rect.top &&
                    e.clientY <= rect.bottom
                ) {
                    const x = e.clientX - rect.left;
                    const y = e.clientY - rect.top;
                    const centerX = rect.width / 2;
                    const centerY = rect.height / 2;
                    const rotateX = ((y - centerY) / centerY) * -10;
                    const rotateY = ((x - centerX) / centerX) * 10;

                    card.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale3d(1.02, 1.02, 1.02)`;
                }
            });
        });

        document.addEventListener('mouseout', (e) => {
            if (e.target && e.target.closest && e.target.closest('.tilt-3d')) {
                const card = e.target.closest('.tilt-3d');
                card.style.transform = 'perspective(1000px) rotateX(0deg) rotateY(0deg) scale3d(1, 1, 1)';
            }
        });
    },

    // ==========================================
    // 💧 LIQUID RIPPLE TOUCH EFFECT
    // ==========================================

    initRippleEngine() {
        document.addEventListener('pointerdown', (e) => {
            const target = e.target.closest('.btn-ripple, .folder-card, .file-card, .keypad-btn, .nav-item, .cat-chip');
            if (!target) return;

            const rect = target.getBoundingClientRect();
            const ripple = document.createElement('span');
            const size = Math.max(rect.width, rect.height) * 2;
            const x = e.clientX - rect.left - size / 2;
            const y = e.clientY - rect.top - size / 2;

            ripple.style.width = ripple.style.height = `${size}px`;
            ripple.style.left = `${x}px`;
            ripple.style.top = `${y}px`;
            ripple.className = 'liquid-ripple';

            target.appendChild(ripple);

            setTimeout(() => {
                ripple.remove();
            }, 600);
        });
    }
};

FX.init();
