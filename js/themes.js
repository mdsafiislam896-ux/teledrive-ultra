/**
 * TeleDrive Ultra - Visual Theme Engine
 * Controls 5 ultra-premium visual themes with instant hot-swapping and persistence.
 */

const ThemeEngine = {
    themes: [
        {
            id: 'oled',
            name: 'OLED Obsidian',
            subtitle: 'Pure AMOLED black with cyan electric glow',
            primaryColor: '#00f2fe',
            bgPreview: '#030712'
        },
        {
            id: 'nordic',
            name: 'Nordic Frost',
            subtitle: 'Frosted glass with cool ice blue tones',
            primaryColor: '#38bdf8',
            bgPreview: '#0b1329'
        },
        {
            id: 'cyberpunk',
            name: 'Cyberpunk Neon',
            subtitle: 'Futuristic night city with neon pink & cyan',
            primaryColor: '#ff007f',
            bgPreview: '#090615'
        },
        {
            id: 'luxury',
            name: 'Champagne & Emerald',
            subtitle: 'Royal gold accents with deep forest emerald',
            primaryColor: '#d4af37',
            bgPreview: '#06130e'
        },
        {
            id: 'sunset',
            name: 'Sunset Terracotta',
            subtitle: 'Warm terracotta orange with modern slate',
            primaryColor: '#fb923c',
            bgPreview: '#180d0d'
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

        this.renderThemePickerList();
    },

    toggleThemePicker() {
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
                <div onclick="ThemeEngine.setTheme('${t.id}')" class="p-3.5 rounded-2xl border cursor-pointer transition-all flex items-center justify-between ${
                    isSelected ? 'border-theme-primary bg-theme-primary/10 shadow-lg shadow-theme-primary/15' : 'border-theme-border bg-theme-bg/60 hover:border-theme-border/80'
                }">
                    <div class="flex items-center gap-3">
                        <div class="w-8 h-8 rounded-xl flex items-center justify-center border border-white/10" style="background-color: ${t.bgPreview};">
                            <span class="w-3.5 h-3.5 rounded-full" style="background-color: ${t.primaryColor}; box-shadow: 0 0 8px ${t.primaryColor};"></span>
                        </div>
                        <div>
                            <div class="text-xs font-bold text-white flex items-center gap-1.5">
                                ${t.name}
                                ${isSelected ? '<span class="text-[9px] bg-theme-primary text-black px-1.5 py-0.2 rounded font-bold">Active</span>' : ''}
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

// Initialize theme engine immediately
ThemeEngine.init();
