/**
 * TeleDrive Ultra - Theme Engine (Material 3 Light & Dark Ecosystem)
 * Features crisp Google Drive-style Light themes and premium Dark themes.
 */

const ThemeEngine = {
    themes: [
        {
            id: 'm3-light',
            name: 'Material Clean Light',
            mode: 'light',
            subtitle: 'Official Google Drive style crisp white & soft surfaces',
            primaryColor: '#0b57d0',
            bgPreview: '#f8fafd'
        },
        {
            id: 'nordic-light',
            name: 'Nordic Sky Light',
            mode: 'light',
            subtitle: 'Minimalist white with refreshing sky blue accents',
            primaryColor: '#0284c7',
            bgPreview: '#f0f9ff'
        },
        {
            id: 'm3-dark',
            name: 'Material 3 Dark',
            mode: 'dark',
            subtitle: 'Subtle charcoal and modern Android 15 dark mode',
            primaryColor: '#a8c7fa',
            bgPreview: '#1e1f20'
        },
        {
            id: 'oled',
            name: 'OLED Pure Black',
            mode: 'dark',
            subtitle: 'Pitch-black AMOLED with cyan laser accents',
            primaryColor: '#00f2fe',
            bgPreview: '#000000'
        },
        {
            id: 'cyberpunk',
            name: 'Cyberpunk Neon',
            mode: 'dark',
            subtitle: 'Dark violet with neon pink and electric cyan',
            primaryColor: '#ff007f',
            bgPreview: '#090615'
        }
    ],

    currentTheme: 'm3-light',

    init() {
        const savedTheme = localStorage.getItem('teledrive_theme') || 'm3-light';
        this.setTheme(savedTheme, false);
    },

    setTheme(themeId, playSound = true) {
        const theme = this.themes.find(t => t.id === themeId) || this.themes[0];
        this.currentTheme = theme.id;
        document.documentElement.setAttribute('data-theme', theme.id);
        localStorage.setItem('teledrive_theme', theme.id);

        // Update Theme Switcher Icon (Sun/Moon)
        const toggleIcon = document.getElementById('quickThemeIcon');
        if (toggleIcon) {
            toggleIcon.setAttribute('data-lucide', theme.mode === 'light' ? 'moon' : 'sun');
            if (window.lucide) lucide.createIcons();
        }

        if (playSound && window.FX) {
            FX.playSuccess();
            FX.haptic('medium');
        }

        this.renderThemePickerList();
    },

    toggleQuickMode() {
        const current = this.themes.find(t => t.id === this.currentTheme);
        if (current && current.mode === 'light') {
            this.setTheme('m3-dark');
        } else {
            this.setTheme('m3-light');
        }
    },

    toggleThemePicker() {
        if (window.FX) FX.tap();
        const modal = document.getElementById('themePickerModal');
        if (!modal) return;
        modal.classList.toggle('hidden');
    },

    renderThemePickerList() {
        const container = document.getElementById('themeListContainer');
        if (!container) return;

        container.innerHTML = this.themes.map(t => {
            const isSelected = t.id === this.currentTheme;
            return `
                <div onclick="ThemeEngine.setTheme('${t.id}')" class="p-3.5 rounded-2xl border cursor-pointer transition-all flex items-center justify-between group ${
                    isSelected ? 'border-theme-primary bg-theme-primary/10 shadow-sm' : 'border-theme-border bg-theme-surface hover:border-theme-border/80'
                }">
                    <div class="flex items-center gap-3">
                        <div class="w-9 h-9 rounded-xl flex items-center justify-center border border-black/10 dark:border-white/10" style="background-color: ${t.bgPreview};">
                            <span class="w-4 h-4 rounded-full" style="background-color: ${t.primaryColor};"></span>
                        </div>
                        <div>
                            <div class="text-xs font-bold text-theme-text flex items-center gap-1.5">
                                ${t.name}
                                <span class="text-[9px] uppercase px-1.5 py-0.2 rounded font-extrabold ${t.mode === 'light' ? 'bg-amber-100 text-amber-800' : 'bg-slate-700 text-slate-200'}">${t.mode}</span>
                                ${isSelected ? '<span class="text-[9px] bg-theme-primary text-white dark:text-black px-1.5 py-0.2 rounded font-bold">Active</span>' : ''}
                            </div>
                            <div class="text-[10px] text-theme-muted">${t.subtitle}</div>
                        </div>
                    </div>
                    <i data-lucide="${isSelected ? 'check-circle-2' : 'circle'}" class="w-4 h-4 ${isSelected ? 'text-theme-primary' : 'text-theme-muted'}"></i>
                </div>
            `;
        }).join('');

        if (window.lucide) lucide.createIcons();
    }
};

ThemeEngine.init();
