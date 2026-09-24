package com.example.tallycustomerapp.web

/**
 * Whole-company mirror collector.
 *
 * The portal is a JavaScript/SPA style application. Many report entries do not
 * expose a normal href, so the collector uses BOTH:
 *  1) URL/link discovery; and
 *  2) safe report-menu click traversal with history.back().
 *
 * Company identity is taken from an explicit company field or the already
 * detected Android/session company. A report title is never treated as a
 * company name.
 */
object SyncCollector {
    fun script(): String = """
        (function() {
            if (window.__tallyWholeCompanyMirrorInstalled) {
                if (window.TallyOfflineMirror) {
                    window.TallyOfflineMirror.captureCurrentPage(true);
                }
                return;
            }
            window.__tallyWholeCompanyMirrorInstalled = true;

            const STATUS_ID = 'tally-offline-mirror-status';
            const STOP_ID = 'tally-offline-mirror-stop';
            const CHUNK_SIZE = 120000;
            const CAPTURE_DELAY = 1800;
            const CLICK_CAPTURE_DELAY = 2600;
            const RETURN_DELAY = 1400;
            const STATE_KEY = 'tallyWholeMirrorInteractiveStateV2';

            let lastCapturedKey = '';
            let captureTimer = null;
            let sweepTimer = null;

            const REPORT_WORDS = [
                'balance sheet', 'profit & loss', 'profit and loss',
                'day book', 'ledger vouchers', 'group outstandings',
                'stock summary', 'sales register', 'purchase register',
                'bills receivable', 'bills payable', 'trial balance',
                'cash/bank book', 'cash bank book', 'cash flow summary',
                'stock query', 'stock journal', 'final accounts reports',
                'receivable & payable reports', 'cash/fund flow reports',
                'ledger reports', 'group reports', 'voucher reports',
                'registers', 'stock item reports', 'stock group reports',
                'stock movement analysis reports', 'cost centre', 'cost center',
                'ratio analysis', 'manufacturing', 'payroll', 'receipts',
                'payments', 'journal', 'contra', 'sales', 'purchase',
                'outstanding', 'ledger', 'voucher', 'stock', 'cash flow',
                'fund flow', 'bank', 'godown', 'item', 'group'
            ];

            function clean(v) {
                return (v || '')
                    .replace(/\u00a0/g, ' ')
                    .replace(/\s+/g, ' ')
                    .trim();
            }

            function bridge() {
                return window.AndroidBridge || null;
            }

            function isVisible(el) {
                if (!el || !(el instanceof HTMLElement)) return false;
                const r = el.getBoundingClientRect();
                const s = window.getComputedStyle(el);
                return r.width > 0 && r.height > 0 &&
                    s.display !== 'none' &&
                    s.visibility !== 'hidden';
            }

            function getStoredSessionCompany() {
                try {
                    return {
                        companyName: clean(sessionStorage.getItem('tallyActiveCompany') || ''),
                        serialNumber: clean(sessionStorage.getItem('tallyActiveSerial') || '')
                    };
                } catch (_) {
                    return { companyName: '', serialNumber: '' };
                }
            }

            function rememberSessionCompany(companyName, serialNumber) {
                if (!companyName) return;
                try {
                    sessionStorage.setItem('tallyActiveCompany', companyName);
                    if (serialNumber) {
                        sessionStorage.setItem('tallyActiveSerial', serialNumber);
                    }
                } catch (_) {}
            }

            function getCompanyFromPage() {
                let companyName = '';
                let serialNumber = '';

                for (const table of Array.from(document.querySelectorAll('table'))) {
                    const text = clean(table.innerText);
                    if (!/Company\s*Name/i.test(text)) continue;

                    const row = Array.from(table.querySelectorAll('tr'))
                        .find(r => /CONNECTED/i.test(clean(r.innerText)));
                    if (!row) continue;

                    const cells = Array.from(row.querySelectorAll('td,th'))
                        .map(x => clean(x.innerText))
                        .filter(Boolean);

                    if (cells.length) {
                        companyName = cells[0] || '';
                        serialNumber = cells.find(x =>
                            /^\d{5,}$/.test(x) ||
                            /^[A-Z0-9_-]{6,}$/i.test(x)
                        ) || '';
                    }

                    if (companyName) break;
                }

                const selectors = [
                    '[data-company-name]', '.company-name', '#companyName',
                    '[class*="company-name"]', '[class*="companyName"]',
                    '[aria-label*="company" i]'
                ];

                if (!companyName) {
                    for (const selector of selectors) {
                        const el = document.querySelector(selector);
                        if (!el) continue;

                        const text = clean(
                            el.getAttribute('data-company-name') ||
                            el.textContent
                        );

                        if (text && text.length < 180) {
                            companyName = text;
                            break;
                        }
                    }
                }

                const active = bridge();
                if (!companyName && active && active.getActiveCompanyName) {
                    companyName = clean(active.getActiveCompanyName());
                    serialNumber = active.getActiveSerialNumber
                        ? clean(active.getActiveSerialNumber())
                        : '';
                }

                if (!companyName) {
                    const stored = getStoredSessionCompany();
                    companyName = stored.companyName;
                    serialNumber = serialNumber || stored.serialNumber;
                }

                if (!companyName) {
                    const bodyText = clean(
                        (document.body &&
                            document.body.innerText || '').slice(0, 6000)
                    );
                    const m = bodyText.match(
                        /Company\s*(?:Name)?\s*[:\-]\s*([^\n|]{2,120})/i
                    );
                    if (m) companyName = clean(m[1]);
                }

                if (companyName && !/login|sign in|customer portal/i.test(companyName)) {
                    rememberSessionCompany(companyName, serialNumber);
                    if (active && active.setActiveCompany) {
                        active.setActiveCompany(companyName, serialNumber || '');
                    }
                } else {
                    companyName = '';
                }

                return { companyName, serialNumber };
            }

            function isLoginPage() {
                const title = clean(document.title);
                const body = clean(
                    (document.body && document.body.innerText || '').slice(0, 3500)
                );
                const password = !!document.querySelector('input[type="password"]');
                return password && /login|sign in|password/i.test(title + ' ' + body);
            }

            function normalizeUrl(raw) {
                try {
                    const u = new URL(raw, location.href);
                    if (u.protocol !== 'https:' ||
                        u.hostname !== 'customer.tallysolutions.com') {
                        return '';
                    }
                    if (!u.pathname.startsWith('/customerapp')) return '';
                    return u.href.replace(/#$/, '');
                } catch (_) {
                    return '';
                }
            }

            function isNavigationUrl(url) {
                if (!url) return false;
                let p = '';
                try {
                    p = new URL(url).pathname.toLowerCase();
                } catch (_) {
                    return false;
                }

                return !/(\/(logout|signout|signin|login|delete|remove|create|new|edit|save|submit)(\/|$))/i.test(p);
            }

            function maybeOpenMenu() {
                const candidates = Array.from(
                    document.querySelectorAll('button,[role="button"],[aria-label]')
                );

                for (const el of candidates) {
                    const label = clean(
                        (el.getAttribute('aria-label') || '') +
                        ' ' + (el.innerText || '')
                    ).toLowerCase();

                    if (!label) continue;

                    if (/hamburger|menu|navigation|drawer/.test(label) &&
                        !/logout|delete|remove|save|submit/.test(label)) {
                        try {
                            if (isVisible(el)) el.click();
                            return true;
                        } catch (_) {}
                    }
                }
                return false;
            }

            function discoverLinks() {
                const out = new Set();

                const push = value => {
                    const u = normalizeUrl(value);
                    if (!u) return;
                    if (!isNavigationUrl(u)) return;
                    if (u === normalizeUrl(location.href)) return;
                    out.add(u);
                };

                for (const a of Array.from(document.querySelectorAll('a[href]'))) {
                    push(a.href);
                }

                const attrNames = [
                    'data-href', 'data-url', 'data-link',
                    'routerlink', 'routerLink',
                    'ng-reflect-router-link', 'to'
                ];

                const nodes = Array.from(document.querySelectorAll(
                    'a[href],[data-href],[data-url],[data-link],' +
                    '[routerlink],[routerLink],[ng-reflect-router-link],[to],[onclick]'
                ));

                for (const el of nodes) {
                    for (const attr of attrNames) {
                        const v = el.getAttribute && el.getAttribute(attr);
                        if (v) push(v);
                    }

                    const onclick = el.getAttribute && el.getAttribute('onclick');
                    if (onclick) {
                        const m = onclick.match(
                            /(?:location(?:\.href)?|window\.open|navigate)\s*\(\s*['"]([^'"]+)['"]/i
                        );
                        if (m) push(m[1]);
                    }
                }

                return Array.from(out);
            }

            function looksLikeReportsIndex() {
                const title = clean(document.title).toLowerCase();
                const body = clean(
                    (document.body && document.body.innerText || '').slice(0, 12000)
                ).toLowerCase();

                if (/list of reports/.test(title)) return true;
                if (/search report by name/.test(body)) return true;
                if (/common reports/.test(body) && /balance sheet/.test(body)) return true;

                return false;
            }

            function reportCandidateText(text) {
                const t = clean(text);
                if (!t) return false;
                if (t.length < 2 || t.length > 100) return false;
                if (/logout|sign out|delete|remove|save|submit|create|new company|login/i.test(t)) {
                    return false;
                }

                const low = t.toLowerCase();
                return REPORT_WORDS.some(word => low === word || low.includes(word));
            }

            function getInteractiveReportLabels() {
                if (!looksLikeReportsIndex()) return [];

                const labels = new Map();
                const selector =
                    'a,button,[role="button"],li,[onclick],[ng-click],td,' +
                    '[routerlink],[routerLink],[class*="report"],[class*="Report"]';

                for (const el of Array.from(document.querySelectorAll(selector))) {
                    if (!isVisible(el)) continue;

                    const text = clean(el.innerText || el.textContent || '');
                    if (!reportCandidateText(text)) continue;

                    const label = text
                        .split('\n')
                        .map(clean)
                        .filter(Boolean)
                        .join(' ');

                    if (!labels.has(label) || label.length < labels.get(label).length) {
                        labels.set(label, label);
                    }
                }

                return Array.from(labels.values());
            }

            function findClickableReport(label) {
                const selector =
                    'a,button,[role="button"],li,[onclick],[ng-click],td,' +
                    '[routerlink],[routerLink],[class*="report"],[class*="Report"]';

                const matches = [];

                for (const el of Array.from(document.querySelectorAll(selector))) {
                    if (!isVisible(el)) continue;

                    const text = clean(el.innerText || el.textContent || '');
                    if (!text) continue;

                    const normalizedText = text.toLowerCase();
                    const normalizedLabel = clean(label).toLowerCase();

                    const exact = normalizedText === normalizedLabel;
                    const contains = normalizedText.includes(normalizedLabel);

                    if (!exact && !contains) continue;
                    if (!reportCandidateText(text)) continue;

                    let score = Math.abs(text.length - label.length);
                    if (!exact) score += 200;

                    matches.push({ el, score });
                }

                matches.sort((a, b) => a.score - b.score);
                return matches.length ? matches[0].el : null;
            }

            function readInteractiveState() {
                try {
                    const raw = sessionStorage.getItem(STATE_KEY);
                    return raw ? JSON.parse(raw) : null;
                } catch (_) {
                    return null;
                }
            }

            function writeInteractiveState(state) {
                try {
                    sessionStorage.setItem(STATE_KEY, JSON.stringify(state));
                } catch (_) {}
            }

            function clearInteractiveState() {
                try {
                    sessionStorage.removeItem(STATE_KEY);
                    sessionStorage.removeItem(STATE_KEY + ':returnPending');
                } catch (_) {}
            }

            function startOrRestoreSweep(ctx) {
                if (!looksLikeReportsIndex()) return false;

                const hubUrl = normalizeUrl(location.href);
                const currentCompany = clean(ctx.companyName);

                let state = readInteractiveState();

                if (!state ||
                    state.hubUrl !== hubUrl ||
                    state.companyName !== currentCompany) {
                    state = {
                        hubUrl,
                        companyName: currentCompany,
                        labels: getInteractiveReportLabels(),
                        index: 0,
                        active: true
                    };
                    writeInteractiveState(state);
                }

                const returnPending = (() => {
                    try {
                        return sessionStorage.getItem(STATE_KEY + ':returnPending') === '1';
                    } catch (_) {
                        return false;
                    }
                })();

                if (returnPending) {
                    try {
                        sessionStorage.removeItem(STATE_KEY + ':returnPending');
                    } catch (_) {}
                }

                if (!state.labels || state.index >= state.labels.length) {
                    state.active = false;
                    writeInteractiveState(state);
                    status('REPORT MENU SAVED • Opening queued pages…');

                    const b = bridge();
                    if (b && b.requestNextMirrorPage) {
                        setTimeout(() => b.requestNextMirrorPage(), 700);
                    }
                    return true;
                }

                if (returnPending) {
                    // Returning from the previously opened report. Continue with
                    // the next label after the restored report list is rendered.
                }

                clearTimeout(sweepTimer);
                sweepTimer = setTimeout(() => {
                    const currentState = readInteractiveState();
                    if (!currentState || !currentState.active) return;

                    const label = currentState.labels[currentState.index];
                    if (!label) {
                        currentState.active = false;
                        writeInteractiveState(currentState);
                        const b = bridge();
                        if (b && b.requestNextMirrorPage) {
                            b.requestNextMirrorPage();
                        }
                        return;
                    }

                    const target = findClickableReport(label);

                    if (!target) {
                        currentState.index += 1;
                        writeInteractiveState(currentState);
                        startOrRestoreSweep(ctx);
                        return;
                    }

                    currentState.index += 1;
                    writeInteractiveState(currentState);

                    try {
                        sessionStorage.setItem(STATE_KEY + ':returnPending', '1');
                    } catch (_) {}

                    status('Opening report ' + currentState.index +
                        '/' + currentState.labels.length + ': ' + label);

                    try {
                        target.scrollIntoView({block: 'center', inline: 'nearest'});
                    } catch (_) {}

                    try {
                        target.click();
                    } catch (_) {
                        try {
                            target.dispatchEvent(new MouseEvent('click', {
                                bubbles: true,
                                cancelable: true,
                                view: window
                            }));
                        } catch (_) {}
                    }

                    setTimeout(() => captureCurrentPage(true), CLICK_CAPTURE_DELAY);
                }, 700);

                return true;
            }

            function handleInteractiveReturn() {
                let pending = false;
                try {
                    pending = sessionStorage.getItem(STATE_KEY + ':returnPending') === '1';
                } catch (_) {}

                if (!pending) return false;
                if (looksLikeReportsIndex()) return false;

                setTimeout(() => {
                    try {
                        history.back();
                    } catch (_) {}
                }, RETURN_DELAY);

                return true;
            }

            function status(text) {
                const el = document.getElementById(STATUS_ID);
                if (el) el.textContent = text;
            }

            function refreshStatus() {
                const b = bridge();

                if (!b || !b.getMirrorProgress) {
                    status('DETECTING COMPANY…');
                    return;
                }

                try {
                    const p = JSON.parse(b.getMirrorProgress());
                    const company = clean(p.company || '');
                    if (!company) {
                        status('DETECTING COMPANY…');
                    } else {
                        status(
                            'AUTO SAVING: ' + company +
                            ' • Saved: ' + p.saved +
                            ' • Queue: ' + p.queued
                        );
                    }
                } catch (_) {
                    status('DETECTING COMPANY…');
                }
            }

            function sendHtml(ctx) {
                const b = bridge();
                if (!b) return false;

                let companyName = clean(ctx.companyName);
                let serialNumber = clean(ctx.serialNumber);

                if (!companyName && b.getActiveCompanyName) {
                    companyName = clean(b.getActiveCompanyName());
                    serialNumber = b.getActiveSerialNumber
                        ? clean(b.getActiveSerialNumber())
                        : serialNumber;
                }

                if (!companyName) {
                    const stored = getStoredSessionCompany();
                    companyName = stored.companyName;
                    serialNumber = serialNumber || stored.serialNumber;
                }

                const snapshotId =
                    'page-' + Date.now() + '-' +
                    Math.random().toString(16).slice(2);

                const url = normalizeUrl(location.href);

                if (!url || !companyName || isLoginPage()) {
                    return false;
                }

                const overlay = document.getElementById(STATUS_ID);
                const stopButton = document.getElementById(STOP_ID);

                if (overlay) overlay.remove();
                if (stopButton) stopButton.remove();

                const html =
                    '<!doctype html>\n' +
                    (document.documentElement
                        ? document.documentElement.outerHTML
                        : document.body.outerHTML);

                if (overlay && document.body) document.body.appendChild(overlay);
                if (stopButton && document.body) document.body.appendChild(stopButton);

                b.beginPageSnapshot(
                    snapshotId,
                    companyName,
                    serialNumber,
                    url,
                    document.title || url
                );

                for (let i = 0; i < html.length; i += CHUNK_SIZE) {
                    b.pushPageSnapshotChunk(
                        snapshotId,
                        html.substring(i, Math.min(i + CHUNK_SIZE, html.length))
                    );
                }

                b.commitPageSnapshot(
                    snapshotId,
                    companyName,
                    serialNumber,
                    url,
                    document.title || url
                );

                rememberSessionCompany(companyName, serialNumber);
                return true;
            }

            function captureCurrentPage(force) {
                if (!document.body || isLoginPage()) return;

                const ctx = getCompanyFromPage();
                const b = bridge();
                if (!b) return;

                if (ctx.companyName && b.setActiveCompany) {
                    b.setActiveCompany(ctx.companyName, ctx.serialNumber || '');
                    rememberSessionCompany(
                        ctx.companyName,
                        ctx.serialNumber || ''
                    );
                }

                const key =
                    normalizeUrl(location.href) + '|' +
                    clean(document.title) + '|' +
                    clean(
                        (document.body && document.body.innerText || '')
                            .slice(0, 600)
                    );

                if (!force &&
                    key === lastCapturedKey &&
                    Date.now() - (window.__lastTallyMirrorCaptureAt || 0) < 3000) {
                    return;
                }

                lastCapturedKey = key;
                window.__lastTallyMirrorCaptureAt = Date.now();

                const urls = discoverLinks();

                if (b.enqueueDiscoveredUrls &&
                    (ctx.companyName ||
                        (b.getActiveCompanyName &&
                            clean(b.getActiveCompanyName())))) {
                    b.enqueueDiscoveredUrls(
                        ctx.companyName || clean(b.getActiveCompanyName()),
                        ctx.serialNumber || (
                            b.getActiveSerialNumber
                                ? clean(b.getActiveSerialNumber())
                                : ''
                        ),
                        JSON.stringify(urls)
                    );
                }

                installOverlay();
                refreshStatus();
                sendHtml(ctx);

                if (looksLikeReportsIndex()) {
                    startOrRestoreSweep(ctx);
                } else if (handleInteractiveReturn()) {
                    // The interactive report page will return to the report list.
                    // Do not let the URL queue pull us away before history.back().
                } else if (b.requestNextMirrorPage) {
                    setTimeout(() => b.requestNextMirrorPage(), 900);
                }
            }

            function installOverlay() {
                if (!document.body) return;

                if (!document.getElementById(STATUS_ID)) {
                    const st = document.createElement('div');
                    st.id = STATUS_ID;
                    st.textContent = 'DETECTING COMPANY…';
                    st.style.cssText =
                        'position:fixed;right:8px;top:76px;z-index:2147483647;' +
                        'background:rgba(0,0,0,.80);color:#fff;padding:7px 11px;' +
                        'border-radius:8px;font:700 12px sans-serif;max-width:330px;' +
                        'box-shadow:0 2px 8px rgba(0,0,0,.30);';
                    document.body.appendChild(st);
                }

                if (!document.getElementById(STOP_ID)) {
                    const btn = document.createElement('button');
                    btn.id = STOP_ID;
                    btn.textContent = 'STOP AUTO SAVE';
                    btn.style.cssText =
                        'position:fixed;right:8px;top:112px;z-index:2147483647;' +
                        'background:#b71c1c;color:#fff;border:0;padding:6px 10px;' +
                        'border-radius:7px;font:700 11px sans-serif;';
                    btn.onclick = function(e) {
                        e.preventDefault();
                        e.stopPropagation();

                        const b = bridge();
                        if (b && b.stopWholeCompanyMirror) {
                            b.stopWholeCompanyMirror();
                        }

                        status('AUTO SAVE STOPPED');
                    };
                    document.body.appendChild(btn);
                }
            }

            window.TallyOfflineMirror = {
                captureCurrentPage,
                discoverLinks,
                discoverReportLabels: getInteractiveReportLabels
            };

            document.addEventListener('click', function(e) {
                const target = e.target && e.target.closest
                    ? e.target.closest(
                        'a,button,[role="button"],li,tr,' +
                        '[routerlink],[routerLink],[data-company-name]'
                    )
                    : null;

                if (!target) return;

                const text = clean(
                    target.getAttribute &&
                    target.getAttribute('data-company-name') ||
                    target.innerText ||
                    target.textContent ||
                    ''
                );

                if (/company|connected|open|serial/i.test(text)) {
                    const ctx = getCompanyFromPage();
                    if (ctx.companyName) {
                        const b = bridge();
                        if (b && b.setActiveCompany) {
                            b.setActiveCompany(
                                ctx.companyName,
                                ctx.serialNumber || ''
                            );
                        }
                    }
                }

                installOverlay();
                scheduleCapture(1200);
            }, true);

            window.addEventListener('popstate', () => scheduleCapture(900));
            window.addEventListener('hashchange', () => scheduleCapture(900));
            window.addEventListener('pageshow', () => scheduleCapture(1000));

            const observer = new MutationObserver(() => {
                installOverlay();
            });

            if (document.body) {
                observer.observe(
                    document.body,
                    {childList: true, subtree: true}
                );
            }

            function scheduleCapture(delay) {
                clearTimeout(captureTimer);
                captureTimer = setTimeout(
                    () => captureCurrentPage(false),
                    delay || CAPTURE_DELAY
                );
            }

            installOverlay();
            maybeOpenMenu();

            setInterval(() => {
                installOverlay();
                refreshStatus();
            }, 1000);

            setTimeout(maybeOpenMenu, 600);
            setTimeout(() => {
                installOverlay();
                captureCurrentPage(true);
            }, 1200);

            setTimeout(() => captureCurrentPage(true), 3000);
        })();
    """.trimIndent()
}
