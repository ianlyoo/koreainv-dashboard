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

let accountCards = [{
    broker: 'kis', label: '', appKey: '', appSecret: '', account: '',
    tossAccounts: [], lookupMessage: '', lookupLoading: false
}];
const tossLookupTimers = new Map();

function escapeAttributeValue(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function accountCardTemplate(card, index) {
    const isLast = accountCards.length <= 1;
    const removeAttrs = isLast
        ? 'disabled aria-disabled="true"'
        : '';
    const broker = card.broker === 'toss' ? 'toss' : 'kis';
    const isToss = broker === 'toss';
    const tossAccounts = Array.isArray(card.tossAccounts) ? card.tossAccounts : [];
    const tossOptions = tossAccounts.map((account) => {
        const value = escapeAttributeValue(account.account_seq);
        const label = escapeAttributeValue(account.display_name || `토스증권 계좌 #${account.account_seq}`);
        return `<option value="${value}" ${String(card.account) === String(account.account_seq) ? 'selected' : ''}>${label}</option>`;
    }).join('');
    const accountField = isToss
        ? `
            <div class="form-group">
                <label for="account_${index}_cano">토스 계좌</label>
                <div class="toss-account-discovery">
                    <button type="button" class="btn-account-lookup"
                        onclick="discoverTossAccounts(${index})" ${card.lookupLoading ? 'disabled' : ''}>
                        ${card.lookupLoading ? '계좌 조회 중...' : '토스 계좌 불러오기'}
                    </button>
                    <select id="account_${index}_cano" name="cano" required
                        onchange="selectTossAccount(${index}, this.value)" ${tossAccounts.length ? '' : 'disabled'}>
                        <option value="">${tossAccounts.length ? '계좌를 선택하세요' : 'CLIENT ID와 SECRET을 입력하세요'}</option>
                        ${tossOptions}
                    </select>
                </div>
                <div class="account-lookup-status" aria-live="polite">${escapeAttributeValue(card.lookupMessage || '')}</div>
            </div>`
        : `
            <div class="form-group">
                <label for="account_${index}_cano">계좌번호 (8자리 + 상품코드 2자리)</label>
                <input type="text" id="account_${index}_cano" name="cano" required autocomplete="off"
                    inputmode="numeric" maxlength="10" placeholder="예: 1234567801"
                    oninput="sanitizeAccountInput(this)" value="${escapeAttributeValue(card.account)}">
            </div>`;
    return `
        <div class="account-card" role="listitem" data-account-index="${index}">
            <div class="account-card-header">
                <span class="account-card-title">계좌 ${index + 1}</span>
                <button type="button" class="btn-remove-account" ${removeAttrs}
                    onclick="removeAccountCard(${index})" aria-label="계좌 ${index + 1} 삭제" title="계좌 삭제">
                    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
                        stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>
            <div class="form-group">
                <label for="account_${index}_broker">증권사</label>
                <select id="account_${index}_broker" name="broker" onchange="changeAccountBroker(${index}, this.value)">
                    <option value="kis" ${broker === 'kis' ? 'selected' : ''}>한국투자증권 (KIS)</option>
                    <option value="toss" ${broker === 'toss' ? 'selected' : ''}>토스증권</option>
                </select>
            </div>
            <div class="form-group">
                <label for="account_${index}_label">계좌 이름</label>
                <input type="text" id="account_${index}_label" name="label" required autocomplete="off"
                    placeholder="예: 주식 계좌" value="${escapeAttributeValue(card.label)}">
            </div>
            <div class="form-group">
                <label for="account_${index}_app_key">${isToss ? 'CLIENT ID' : 'APP KEY'}</label>
                <input type="text" id="account_${index}_app_key" name="app_key" required autocomplete="off"
                    placeholder="${isToss ? '토스증권 CLIENT ID' : '한국투자증권 APP KEY'}" value="${escapeAttributeValue(card.appKey)}"
                    ${isToss ? `oninput="handleTossCredentialInput(${index})"` : ''}>
            </div>
            <div class="form-group">
                <label for="account_${index}_app_secret">${isToss ? 'CLIENT SECRET' : 'APP SECRET'}</label>
                <input type="password" id="account_${index}_app_secret" name="app_secret" required
                    autocomplete="new-password" placeholder="${isToss ? '토스증권 CLIENT SECRET' : '한국투자증권 APP SECRET'}" value="${escapeAttributeValue(card.appSecret)}"
                    ${isToss ? `oninput="handleTossCredentialInput(${index})" onblur="scheduleTossAccountLookup(${index}, 0)"` : ''}>
            </div>
            ${accountField}
        </div>
    `;
}

function renderAccountCards() {
    tossLookupTimers.forEach((timer) => window.clearTimeout(timer));
    tossLookupTimers.clear();
    const container = document.getElementById('account-cards');
    if (!container) return;
    container.innerHTML = accountCards.map(accountCardTemplate).join('');
}

function syncAccountCardState() {
    const container = document.getElementById('account-cards');
    if (!container) return;
    const cards = container.querySelectorAll('.account-card');
    cards.forEach((card, index) => {
        const read = (id) => {
            const el = document.getElementById(`account_${index}_${id}`);
            return el ? el.value : '';
        };
        if (accountCards[index]) {
            const previous = accountCards[index];
            accountCards[index] = {
                broker: read('broker') || 'kis',
                label: read('label'),
                appKey: read('app_key'),
                appSecret: read('app_secret'),
                account: read('cano'),
                tossAccounts: previous.tossAccounts || [],
                lookupMessage: previous.lookupMessage || '',
                lookupLoading: !!previous.lookupLoading
            };
        }
    });
}

function addAccountCard() {
    syncAccountCardState();
    accountCards.push({
        broker: 'kis', label: '', appKey: '', appSecret: '', account: '',
        tossAccounts: [], lookupMessage: '', lookupLoading: false
    });
    renderAccountCards();
    const newIndex = accountCards.length - 1;
    const firstInput = document.getElementById(`account_${newIndex}_label`);
    if (firstInput) firstInput.focus();
}

function removeAccountCard(index) {
    if (accountCards.length <= 1) return;
    syncAccountCardState();
    accountCards.splice(index, 1);
    renderAccountCards();
    const addBtn = document.getElementById('add-account-btn');
    if (addBtn) addBtn.focus();
}

function sanitizeAccountInput(input) {
    const maxLength = Number(input.maxLength) > 0 ? Number(input.maxLength) : 19;
    const digits = input.value.replace(/\D/g, '').slice(0, maxLength);
    if (input.value !== digits) {
        input.value = digits;
    }
}

function changeAccountBroker(index, broker) {
    syncAccountCardState();
    if (accountCards[index]) {
        accountCards[index].broker = broker === 'toss' ? 'toss' : 'kis';
        accountCards[index].account = '';
        accountCards[index].tossAccounts = [];
        accountCards[index].lookupMessage = '';
        accountCards[index].lookupLoading = false;
    }
    renderAccountCards();
    document.getElementById(`account_${index}_${broker === 'toss' ? 'app_key' : 'cano'}`)?.focus();
}

function selectTossAccount(index, accountSeq) {
    if (accountCards[index]) accountCards[index].account = String(accountSeq || '');
}

function handleTossCredentialInput(index) {
    const card = accountCards[index];
    if (!card || card.broker !== 'toss') return;
    card.appKey = document.getElementById(`account_${index}_app_key`)?.value || '';
    card.appSecret = document.getElementById(`account_${index}_app_secret`)?.value || '';
    card.account = '';
    card.tossAccounts = [];
    card.lookupMessage = '';
    const select = document.getElementById(`account_${index}_cano`);
    if (select) {
        select.value = '';
        select.disabled = true;
    }
    scheduleTossAccountLookup(index, 700);
}

function scheduleTossAccountLookup(index, delay = 700) {
    const existing = tossLookupTimers.get(index);
    if (existing) window.clearTimeout(existing);
    const appKey = document.getElementById(`account_${index}_app_key`)?.value.trim();
    const appSecret = document.getElementById(`account_${index}_app_secret`)?.value;
    if (!appKey || !appSecret) return;
    tossLookupTimers.set(index, window.setTimeout(() => discoverTossAccounts(index), delay));
}

async function discoverTossAccounts(index) {
    syncAccountCardState();
    const card = accountCards[index];
    if (!card || card.broker !== 'toss' || card.lookupLoading) return;
    if (!card.appKey.trim() || !card.appSecret) {
        card.lookupMessage = 'CLIENT ID와 CLIENT SECRET을 먼저 입력하세요.';
        renderAccountCards();
        return;
    }
    card.lookupLoading = true;
    card.lookupMessage = '토스증권에서 계좌 목록을 조회하고 있습니다.';
    renderAccountCards();
    try {
        const response = await fetch('/api/toss/accounts/discover', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ client_id: card.appKey.trim(), client_secret: card.appSecret })
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.detail || '토스 계좌 조회에 실패했습니다.');
        if (accountCards[index] !== card) return;
        const options = Array.isArray(data.accounts) ? data.accounts : [];
        card.tossAccounts = options;
        card.account = options.length === 1 ? String(options[0].account_seq) : '';
        card.lookupMessage = options.length === 1
            ? `${options[0].display_name} 계좌가 자동 선택됐습니다.`
            : options.length > 1
                ? '사용할 토스 계좌를 선택하세요.'
                : '사용 가능한 토스증권 계좌가 없습니다.';
    } catch (error) {
        if (accountCards[index] !== card) return;
        card.tossAccounts = [];
        card.account = '';
        card.lookupMessage = error.message || '토스 계좌 조회에 실패했습니다.';
    } finally {
        card.lookupLoading = false;
        renderAccountCards();
    }
}

function parseAccountNumber(raw) {
    const digits = String(raw || '').replace(/\D/g, '');
    if (digits.length !== 8 && digits.length !== 10) return null;
    return {
        cano: digits.slice(0, 8),
        acnt_prdt_cd: digits.length === 10 ? digits.slice(8) : '01'
    };
}

function parseBrokerAccount(raw, broker) {
    const digits = String(raw || '').replace(/\D/g, '');
    if (broker === 'toss') {
        return digits && Number(digits) > 0
            ? { cano: digits, acnt_prdt_cd: '' }
            : null;
    }
    return parseAccountNumber(digits);
}

function validateSetupForm() {
    const errors = [];
    const accounts = [];
    const invalidFields = [];

    accountCards.forEach((card, index) => {
        const label = card.label.trim();
        const broker = card.broker === 'toss' ? 'toss' : 'kis';
        const appKey = card.appKey.trim();
        const appSecret = card.appSecret;
        const parsed = parseBrokerAccount(card.account, broker);

        if (!label) {
            errors.push(`계좌 ${index + 1}: 계좌 이름을 입력하세요.`);
            invalidFields.push(`account_${index}_label`);
        }
        if (!appKey) {
            errors.push(`계좌 ${index + 1}: ${broker === 'toss' ? 'CLIENT ID' : 'APP KEY'}를 입력하세요.`);
            invalidFields.push(`account_${index}_app_key`);
        }
        if (!appSecret) {
            errors.push(`계좌 ${index + 1}: ${broker === 'toss' ? 'CLIENT SECRET' : 'APP SECRET'}을 입력하세요.`);
            invalidFields.push(`account_${index}_app_secret`);
        }
        if (!parsed) {
            errors.push(`계좌 ${index + 1}: ${broker === 'toss' ? '토스 계좌를 불러와 선택하세요.' : '계좌번호는 숫자 8자리 또는 10자리로 입력하세요.'}`);
            invalidFields.push(`account_${index}_cano`);
        } else {
            accounts.push({
                label,
                broker,
                app_key: appKey,
                app_secret: appSecret,
                cano: parsed.cano,
                acnt_prdt_cd: parsed.acnt_prdt_cd
            });
        }
    });

    const pin = document.getElementById('setup_pin').value;
    if (!/^\d{4,6}$/.test(pin)) {
        errors.push('PIN은 4~6자리 숫자로 입력하세요.');
        invalidFields.push('setup_pin');
    }

    const errDiv = document.getElementById('setup-error');
    document.querySelectorAll('#setup-form .invalid').forEach((el) => {
        el.classList.remove('invalid');
        el.removeAttribute('aria-invalid');
    });
    invalidFields.forEach((fieldId) => {
        const el = document.getElementById(fieldId);
        if (el) {
            el.classList.add('invalid');
            el.setAttribute('aria-invalid', 'true');
        }
    });

    if (errors.length > 0) {
        errDiv.textContent = errors.join(' ');
        errDiv.style.display = 'block';
        const firstInvalid = document.getElementById(invalidFields[0]);
        if (firstInvalid) firstInvalid.focus();
        return null;
    }

    errDiv.style.display = 'none';
    return accounts;
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

        if (!data.setup_complete) {
            renderAccountCards();
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

    syncAccountCardState();
    const accounts = validateSetupForm();
    if (!accounts) return;

    const rawPin = document.getElementById('setup_pin').value;

    btn.disabled = true;
    text.style.display = 'none';
    spinner.style.display = 'block';
    errDiv.style.display = 'none';

    const formData = new FormData();
    formData.append('accounts_json', JSON.stringify(accounts));
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
