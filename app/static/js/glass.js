/* Decorative glass reflections; native controls retain all interaction and focus. */
(() => {
    'use strict';
    const selector = '.header .controls button, .header #searchWrapper, #currencyToggle, .summary-card';
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const pointer = window.matchMedia('(hover: hover) and (pointer: fine)');
    let targets = [];
    let attached = false;
    let suspended = false;
    let frame = null;
    let pending = null;

    function disabled(element) {
        return element.matches(':disabled, [aria-disabled="true"]');
    }

    function clear(element) {
        element.classList.remove('glass-reflection-active');
        element.style.removeProperty('--glass-pointer-x');
        element.style.removeProperty('--glass-pointer-y');
    }

    function cancelFrame() {
        if (frame !== null) window.cancelAnimationFrame(frame);
        frame = null;
        pending = null;
    }

    function update() {
        frame = null;
        const sample = pending;
        pending = null;
        if (!sample || !attached || disabled(sample.element) || !sample.element.isConnected) return;
        const bounds = sample.element.getBoundingClientRect();
        if (!bounds.width || !bounds.height) return;
        const x = Math.max(0, Math.min(bounds.width, sample.x - bounds.left));
        const y = Math.max(0, Math.min(bounds.height, sample.y - bounds.top));
        sample.element.style.setProperty('--glass-pointer-x', `${x.toFixed(1)}px`);
        sample.element.style.setProperty('--glass-pointer-y', `${y.toFixed(1)}px`);
        sample.element.classList.add('glass-reflection-active');
    }

    function track(event) {
        const element = event.currentTarget;
        if (event.pointerType !== 'mouse' || disabled(element)) {
            if (pending?.element === element) cancelFrame();
            clear(element);
            return;
        }
        pending = { element, x: event.clientX, y: event.clientY };
        if (frame === null) frame = window.requestAnimationFrame(update);
    }

    function leave(event) {
        if (pending?.element === event.currentTarget) cancelFrame();
        clear(event.currentTarget);
    }

    function sync() {
        const enabled = !suspended && !document.hidden && !motion.matches && pointer.matches;
        if (enabled === attached) return;
        attached = enabled;
        cancelFrame();
        for (const element of targets) {
            clear(element);
            for (const type of ['pointerenter', 'pointermove']) {
                if (enabled) element.addEventListener(type, track, { passive: true });
                else element.removeEventListener(type, track);
            }
            for (const type of ['pointerleave', 'pointercancel']) {
                if (enabled) element.addEventListener(type, leave, { passive: true });
                else element.removeEventListener(type, leave);
            }
        }
    }

    function initialize() {
        targets = Array.from(document.querySelectorAll(selector));
        targets.forEach((element) => element.classList.add('glass-reflection'));
        motion.addEventListener('change', sync);
        pointer.addEventListener('change', sync);
        document.addEventListener('visibilitychange', sync);
        window.addEventListener('pagehide', () => { suspended = true; sync(); });
        window.addEventListener('pageshow', () => { suspended = false; sync(); });
        sync();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
    else initialize();
})();
