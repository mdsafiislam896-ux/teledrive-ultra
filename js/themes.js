/**
 * TeleDrive Ultra - Expanded 6-Theme Visual Engine
 * Instant hot-swapping, ambient glow accents, and custom theme overrides.
 */

const ThemeEngine = {
    themes: [
        {
            id: 'oled',
            name: 'OLED Obsidian',
            subtitle: 'Pure AMOLED black with cyan laser glow (Zero Battery Drain)',
            primaryColor: '#00f2fe',
            accentColor: '#4facfe',
            bgPreview: '#030712'
        },
        {
            id: 'cyberpunk',
            name: 'Cyberpunk Neon 2077',
            subtitle: 'High-tech dark violet with neon magenta & electric cyan',
            primaryColor: '#ff007f',
            accentColor: '#00ffff',
            bgPreview: '#090615'
        },
        {
            id: 'nordic',
            name: 'Nordic Frosted Glass',
            subtitle: 'Hyper-realistic frosted sapphire glass with soft blur',
            primaryColor: '#38bdf8',
            accentColor: '#818cf8',
            bgPreview: '#0b1329'
        },
        {
            id: 'luxury',
            name: 'Royal Champagne & Emerald',
            subtitle: 'Prestige 24K gold accents on deep velvet emerald',
            primaryColor: '#d4af37',
            accentColor: '#10b981',
            bgPreview: '#06130e'
        },
        {
            id: 'titanium',
            name: 'Liquid Titanium Pro',
            subtitle: 'Sleek metallic space gray with Apple Pro aesthetics',
            primaryColor: '#e2e8f0',
            accentColor: '#94a3b8',
            bgPreview: '#0f172a'
        },
        {
            id: 'tokyo',
            name: 'Tokyo Neon Sunset',
            subtitle: 'Warm sunset gradients on midnight indigo',
            primaryColor: '#fb923c',
            accentColor: '#ec4899',
            bgPreview: '#180d1e'
        }
    ],

    currentTheme: 'oled',

    init() {
        const savedTheme = localStorage.getItem('teledrive_theme') || 'oled';
        this.setTheme(savedTheme);
        this.renderThemePickerList();
    },

    setTheme(themeId) {
        const theme = this.themes.find(t => t.id === themeId) || this.themes[0];
        this.currentTheme = theme.id;
        document.documentElement.setAttribute('data-theme', theme.id);
        localStorage.setItem('teledrive_theme', theme.id);

        const nameLabel = document.getElementById('currentThemeName');
        if (nameLabel) {
            nameLabel.textContent = theme.name.split(' ')[0];
        }

        if (window.FX) {
            FX.playSuccess();
            FX.haptic('medium');
        }

        this.renderThemePickerList();
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
                    isSelected ? 'border-theme-primary bg-theme-primary/10 shadow-lg shadow-theme-primary/15' : 'border-theme-border bg-theme-bg/60 hover:border-theme-border/80'
                }">
                    <div class="flex items-center gap-3">
                        <div class="w-9 h-9 rounded-xl flex items-center justify-center border border-white/10 relative overflow-hidden" style="background-color: ${t.bgPreview};">
                            <span class="w-4 h-4 rounded-full" style="background-color: ${t.primaryColor}; box-shadow: 0 0 10px ${t.primaryColor};"></span>
                        </div>
                        <div>
                            <div class="text-xs font-bold text-white flex items-center gap-1.5">
                                ${t.name}
                                ${isSelected ? '<span class="text-[9px] bg-theme-primary text-black px-1.5 py-0.2 rounded-full font-extrabold uppercase">Active</span>' : ''}
                            </div>
                            <div class="text-[10px] text-theme-muted">${t.subtitle}</div>
                        </div>
                    </div>
                    <i data-lucide="${isSelected ? 'check-circle-2' : 'circle'}" class="w-4 h-4 ${isSelected ? 'text-theme-primary' : 'text-theme-muted'}"></i>
                </div>
            `;
        }).join('');

        if (window.lucide) {
            lucide.createIcons();
        }
    }
};

ThemeEngine.init();
