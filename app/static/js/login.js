document.addEventListener('DOMContentLoaded', async () => {
    try {
        const res = await fetch('/api/status');
        const data = await res.json();

        if (data.authenticated) {
            window.location.href = '/';
            return;
        }

        if (data.setup_complete) {
            document.getElementById('login-view').classList.add('active');
        } else {
            document.getElementById('setup-view').classList.add('active');
        }
    } catch (err) {
        console.error('Failed to check status', err);
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
