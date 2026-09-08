(() => {
    'use strict';
    const storageKey = 'dashboard_appearance_mode';
    const validPreferences = new Set(['system', 'light', 'dark']);
    const media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    let preference = 'dark';
    try {
        const saved = localStorage.getItem(storageKey);
        if (validPreferences.has(saved)) preference = saved;
    } catch (_error) { /* Appearance remains usable when browser storage is unavailable. */ }

    function apply() {
        const theme = preference === 'system' ? (media?.matches ? 'dark' : 'light') : preference;
        const changed = document.documentElement.dataset.theme !== theme;
        document.documentElement.dataset.theme = theme;
        document.documentElement.style.colorScheme = theme;
        const select = document.getElementById('appearanceMode');
        if (select) select.value = preference;
        if (changed) window.dispatchEvent(new CustomEvent('dashboard:themechange', { detail: { theme, preference } }));
    }

    function setPreference(value) {
        preference = validPreferences.has(value) ? value : 'dark';
        try { localStorage.setItem(storageKey, preference); } catch (_error) { /* Optional persistence. */ }
        apply();
    }

    window.dashboardTheme = {
        get preference() { return preference; },
        get theme() { return document.documentElement.dataset.theme; },
        setPreference,
    };
    apply();
    if (media?.addEventListener) media.addEventListener('change', () => { if (preference === 'system') apply(); });
    else if (media?.addListener) media.addListener(() => { if (preference === 'system') apply(); });
    window.addEventListener('storage', (event) => {
        if (event.key !== storageKey && event.key !== null) return;
        preference = validPreferences.has(event.newValue) ? event.newValue : 'dark';
        apply();
    });
    document.addEventListener('DOMContentLoaded', () => {
        apply();
        document.getElementById('appearanceMode')?.addEventListener('change', (event) => setPreference(event.target.value));
    }, { once: true });
})();
