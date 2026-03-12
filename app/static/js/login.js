class Starfield {
    constructor() {
        this.canvas = document.getElementById('starfield');
        if (!this.canvas) return;

        this.ctx = this.canvas.getContext('2d');
        this.stars = [];
        this.comets = [];
        this.numStars = 220;
        this.pointer = { x: 0, y: 0 };
        this.pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
        this.animationFrame = null;
        this.lastTime = 0;
        this.cometTimer = 0;

        this.resize();
        window.addEventListener('resize', () => this.resize());

        this.init();
        this.animate();
    }

    resize() {
        this.pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
        this.width = window.innerWidth;
        this.height = window.innerHeight;
        this.canvas.width = Math.floor(this.width * this.pixelRatio);
        this.canvas.height = Math.floor(this.height * this.pixelRatio);
        this.canvas.style.width = `${this.width}px`;
        this.canvas.style.height = `${this.height}px`;
        this.ctx.setTransform(this.pixelRatio, 0, 0, this.pixelRatio, 0, 0);
        this.init();
    }

    init() {
        this.stars = [];
        for (let i = 0; i < this.numStars; i++) {
            const depth = Math.random();
            const layer = depth > 0.78 ? 'foreground' : depth > 0.35 ? 'midground' : 'background';
            const baseSize = layer === 'foreground' ? Math.random() * 1.8 + 0.6 : layer === 'midground' ? Math.random() * 1.2 + 0.3 : Math.random() * 0.8 + 0.15;
            const baseOpacity = layer === 'foreground' ? Math.random() * 0.35 + 0.35 : layer === 'midground' ? Math.random() * 0.24 + 0.18 : Math.random() * 0.16 + 0.08;
            const drift = layer === 'foreground' ? (Math.random() - 0.5) * 36 : layer === 'midground' ? (Math.random() - 0.5) * 20 : (Math.random() - 0.5) * 8;

            this.stars.push({
                x: Math.random() * this.width,
                y: Math.random() * this.height,
                size: baseSize,
                opacity: baseOpacity,
                twinkleOffset: Math.random() * Math.PI * 2,
                twinkleSpeed: Math.random() * 0.018 + 0.006,
                speedX: (Math.random() - 0.5) * (layer === 'foreground' ? 0.18 : layer === 'midground' ? 0.09 : 0.03),
                speedY: Math.random() * (layer === 'foreground' ? 0.18 : layer === 'midground' ? 0.1 : 0.05) + 0.01,
                drift,
                color: layer === 'foreground' && Math.random() > 0.75 ? 'amber' : layer === 'midground' && Math.random() > 0.78 ? 'blue' : 'white',
                layer
            });
        }

        this.comets = [];
    }

    setPointer(x, y) {
        this.pointer.x = x;
        this.pointer.y = y;
    }

    spawnComet() {
        const fromLeft = Math.random() > 0.5;
        this.comets.push({
            x: fromLeft ? Math.random() * this.width * 0.35 : this.width - Math.random() * this.width * 0.35,
            y: Math.random() * this.height * 0.36,
            vx: fromLeft ? Math.random() * 2.8 + 2.4 : -(Math.random() * 2.8 + 2.4),
            vy: Math.random() * 1.1 + 0.75,
            length: Math.random() * 120 + 120,
            life: 0,
            ttl: Math.random() * 1200 + 900
        });
    }

    animate() {
        const time = performance.now();
        const delta = this.lastTime ? time - this.lastTime : 16;
        this.lastTime = time;
        this.ctx.clearRect(0, 0, this.width, this.height);

        for (const star of this.stars) {
            star.x += star.speedX * (delta / 16);
            star.y += star.speedY * (delta / 16);
            if (star.x < -8) star.x = this.width + 8;
            if (star.x > this.width + 8) star.x = -8;
            if (star.y > this.height + 8) {
                star.y = -8;
                star.x = Math.random() * this.width;
            }

            const twinkle = 0.72 + Math.sin(time * star.twinkleSpeed + star.twinkleOffset) * 0.28;
            const drawX = star.x + this.pointer.x * star.drift;
            const drawY = star.y + this.pointer.y * star.drift * 0.35;

            this.ctx.beginPath();
            this.ctx.arc(drawX, drawY, star.size, 0, Math.PI * 2);
            if (star.color === 'amber') {
                this.ctx.fillStyle = `rgba(251, 191, 36, ${star.opacity * twinkle})`;
            } else if (star.color === 'blue') {
                this.ctx.fillStyle = `rgba(147, 197, 253, ${star.opacity * twinkle})`;
            } else {
                this.ctx.fillStyle = `rgba(255, 255, 255, ${star.opacity * twinkle})`;
            }
            this.ctx.fill();

            if (star.layer !== 'background') {
                this.ctx.beginPath();
                this.ctx.arc(drawX, drawY, star.size * 2.8, 0, Math.PI * 2);
                this.ctx.fillStyle = star.color === 'amber'
                    ? `rgba(251, 191, 36, ${star.opacity * 0.07})`
                    : `rgba(96, 165, 250, ${star.opacity * 0.08})`;
                this.ctx.fill();
            }
        }

        this.cometTimer += delta;
        if (this.cometTimer > 2800 && this.comets.length < 2) {
            this.spawnComet();
            this.cometTimer = Math.random() * 1200;
        }

        this.comets = this.comets.filter((comet) => {
            comet.life += delta;
            comet.x += comet.vx * (delta / 16);
            comet.y += comet.vy * (delta / 16);

            const alpha = Math.max(0, 1 - comet.life / comet.ttl);
            const trailX = comet.x - comet.vx * comet.length * 0.22;
            const trailY = comet.y - comet.vy * comet.length * 0.22;

            const gradient = this.ctx.createLinearGradient(comet.x, comet.y, trailX, trailY);
            gradient.addColorStop(0, `rgba(255, 250, 240, ${alpha * 0.95})`);
            gradient.addColorStop(0.2, `rgba(251, 191, 36, ${alpha * 0.45})`);
            gradient.addColorStop(1, 'rgba(255, 255, 255, 0)');

            this.ctx.strokeStyle = gradient;
            this.ctx.lineWidth = 1.6;
            this.ctx.beginPath();
            this.ctx.moveTo(comet.x, comet.y);
            this.ctx.lineTo(trailX, trailY);
            this.ctx.stroke();

            this.ctx.beginPath();
            this.ctx.arc(comet.x, comet.y, 1.6, 0, Math.PI * 2);
            this.ctx.fillStyle = `rgba(255, 250, 240, ${alpha})`;
            this.ctx.fill();

            return alpha > 0 && comet.x > -160 && comet.x < this.width + 160 && comet.y < this.height + 160;
        });

        this.animationFrame = requestAnimationFrame(() => this.animate());
    }
}

function setupPointerMotion(starfield) {
    const root = document.documentElement;
    let frame = null;

    const updatePointer = (clientX, clientY) => {
        const normalizedX = ((clientX / window.innerWidth) - 0.5) * 2;
        const normalizedY = ((clientY / window.innerHeight) - 0.5) * 2;

        root.style.setProperty('--pointer-x', normalizedX.toFixed(4));
        root.style.setProperty('--pointer-y', normalizedY.toFixed(4));

        if (starfield) {
            starfield.setPointer(normalizedX, normalizedY);
        }
        frame = null;
    };

    window.addEventListener('pointermove', (event) => {
        if (frame) return;
        frame = requestAnimationFrame(() => updatePointer(event.clientX, event.clientY));
    }, { passive: true });

    window.addEventListener('pointerleave', () => {
        root.style.setProperty('--pointer-x', '0');
        root.style.setProperty('--pointer-y', '0');
        if (starfield) {
            starfield.setPointer(0, 0);
        }
    });
}

function activateView(viewId) {
    const views = document.querySelectorAll('.view-section');
    views.forEach((view) => view.classList.remove('active'));

    const nextView = document.getElementById(viewId);
    if (nextView) {
        nextView.classList.add('active');
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let starfield = null;

    if (!prefersReducedMotion) {
        starfield = new Starfield();
        setupPointerMotion(starfield);
    }

    try {
        const res = await fetch('/api/status');
        const data = await res.json();

        if (data.authenticated) {
            window.location.href = '/';
            return;
        }

        activateView(data.setup_complete ? 'login-view' : 'setup-view');
    } catch (err) {
        console.error('Failed to check status', err);

        activateView('login-view');

        const errDiv = document.getElementById('login-error');
        if (errDiv) {
            errDiv.textContent = '상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.';
            errDiv.style.display = 'block';
        }
    }
});

async function handleSetup(e) {
    e.preventDefault();
    const btn = document.getElementById('setup-btn');
    const text = btn.querySelector('.btn-text');
    const spinner = btn.querySelector('.spinner');
    const errDiv = document.getElementById('setup-error');

    const rawKey = document.getElementById('app_key').value.trim();
    const rawSecret = document.getElementById('app_secret').value.trim();
    const rawCano = document.getElementById('cano').value.trim();
    const rawPin = document.getElementById('setup_pin').value;

    btn.disabled = true;
    text.style.display = 'none';
    spinner.style.display = 'block';
    errDiv.style.display = 'none';

    const formData = new FormData();
    formData.append('app_key', rawKey);
    formData.append('app_secret', rawSecret);
    formData.append('cano', rawCano.substring(0, 8));
    formData.append('acnt_prdt_cd', rawCano.substring(8) || '01');
    formData.append('pin', rawPin);

    try {
        const res = await fetch('/api/setup', {
            method: 'POST',
            body: formData
        });

        if (res.ok) {
            window.location.href = '/';
        } else {
            const errorData = await res.json();
            throw new Error(errorData.detail || 'Setup failed');
        }
    } catch (err) {
        errDiv.textContent = err.message;
        errDiv.style.display = 'block';
        btn.disabled = false;
        text.style.display = 'block';
        spinner.style.display = 'none';
    }
}

async function handleLogin(e) {
    e.preventDefault();
    const btn = document.getElementById('login-btn');
    const text = btn.querySelector('.btn-text');
    const spinner = btn.querySelector('.spinner');
    const errDiv = document.getElementById('login-error');

    btn.disabled = true;
    text.style.display = 'none';
    spinner.style.display = 'block';
    errDiv.style.display = 'none';

    const formData = new FormData(e.target);

    try {
        const res = await fetch('/api/login', {
            method: 'POST',
            body: formData
        });

        if (res.ok) {
            window.location.href = '/';
        } else {
            const errorData = await res.json();
            throw new Error(errorData.detail || 'Login failed');
        }
    } catch (err) {
        errDiv.textContent = '비밀번호가 올바르지 않습니다.';
        errDiv.style.display = 'block';
        btn.disabled = false;
        text.style.display = 'block';
        spinner.style.display = 'none';

        const pinInput = document.getElementById('login_pin');
        pinInput.value = '';
        pinInput.focus();
    }
}
