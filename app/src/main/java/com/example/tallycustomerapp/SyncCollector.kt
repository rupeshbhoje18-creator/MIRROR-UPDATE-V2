package com.example.tallycustomerapp.web

/**
 * Whole-company mirror collector.
 *
 * The browser remains authenticated. When a company is opened, this script:
 * 1) identifies the active company;
 * 2) opens common menu/drawer controls so hidden navigation links enter the DOM;
 * 3) saves the current page HTML;
 * 4) discovers same-company navigation URLs across the page;
 * 5) sends those URLs to Android, which opens them one by one automatically;
 * 6) repeats recursively until the discoverable queue is empty.
 *
 * No accounting values are invented. Full rendered DOM is stored so reports do
 * not need a specific Ledger/Voucher/Stock table shape to be mirrored.
 */
object SyncCollector {
    fun script(): String = """
        (function() {
            if (window.__tallyWholeCompanyMirrorInstalled) {
                if (window.TallyOfflineMirror) window.TallyOfflineMirror.captureCurrentPage();
                return;
            }
            window.__tallyWholeCompanyMirrorInstalled = true;

            const STATUS_ID = 'tally-offline-mirror-status';
            const STOP_ID = 'tally-offline-mirror-stop';
            const CHUNK_SIZE = 120000;
            const CAPTURE_DELAY = 1600;
            let lastCapturedKey = '';
            let captureTimer = null;

            function clean(v) {
                return (v || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
            }

            function hash(v) {
                let h = 2166136261;
                for (let i = 0; i < v.length; i++) {
                    h ^= v.charCodeAt(i);
                    h = Math.imul(h, 16777619);
                }
                return (h >>> 0).toString(16);
            }

            function bridge() { return window.AndroidBridge || null; }

            function getCompanyFromPage() {
                let companyName = '';
                let serialNumber = '';

                for (const table of Array.from(document.querySelectorAll('table'))) {
                    const text = clean(table.innerText);
                    if (!/Company\s*Name/i.test(text)) continue;
                    const row = Array.from(table.querySelectorAll('tr')).find(r => /CONNECTED/i.test(r.innerText));
                    if (!row) continue;
                    const cells = Array.from(row.querySelectorAll('td,th')).map(x => clean(x.innerText)).filter(Boolean);
                    if (cells.length) {
                        companyName = cells[0] || '';
                        serialNumber = cells.find(x => /^\d{5,}|^[A-Z0-9_-]{6,}$/i.test(x) && x !== companyName) || '';
                        if (!companyName && cells[1]) companyName = cells[1];
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
                        if (el) {
                            const text = clean(el.getAttribute('data-company-name') || el.textContent);
                            if (text && text.length < 180) { companyName = text; break; }
                        }
                    }
                }

                const active = bridge();
                if (!companyName && active && active.getActiveCompanyName) {
                    companyName = clean(active.getActiveCompanyName());
                    serialNumber = active.getActiveSerialNumber ? clean(active.getActiveSerialNumber()) : '';
                }

                if (!companyName) {
                    const bodyText = clean((document.body && document.body.innerText || '').slice(0, 5000));
                    const m = bodyText.match(/Company\s*(?:Name)?\s*[:\-]\s*([^\n]{2,120})/i);
                    if (m) companyName = clean(m[1]);
                }

                if (!companyName && document.title) {
                    const title = clean(document.title);
                    if (!/login|sign in|customer portal/i.test(title)) companyName = title;
                }

                return { companyName, serialNumber };
            }

            function isLoginPage() {
                const title = clean(document.title);
                const body = clean((document.body && document.body.innerText || '').slice(0, 3500));
                const password = !!document.querySelector('input[type="password"]');
                return password && /login|sign in|password/i.test(title + ' ' + body);
            }

            function normalizeUrl(raw) {
                try {
                    const u = new URL(raw, location.href);
                    if (u.protocol !== 'https:' || u.hostname !== 'customer.tallysolutions.com') return '';
                    if (!u.pathname.startsWith('/customerapp')) return '';
                    return u.href.replace(/#$/, '');
                } catch (_) { return ''; }
            }

            function isNavigationUrl(url) {
                if (!url) return false;
                let p = '';
                try { p = new URL(url).pathname.toLowerCase(); } catch (_) { return false; }
                return !/(\/(logout|signout|signin|login|delete|remove|create|new|edit|save|submit)(\/|$))/i.test(p);
            }

            function maybeOpenMenu() {
                const candidates = Array.from(document.querySelectorAll('button,[role="button"],[aria-label]'));
                let clicked = false;
                for (const el of candidates) {
                    const label = clean((el.getAttribute('aria-label') || '') + ' ' + (el.innerText || '')).toLowerCase();
                    if (!label) continue;
                    if (/hamburger|menu|navigation|drawer/.test(label) && !/logout/.test(label)) {
                        try { el.click(); clicked = true; } catch (_) {}
                        break;
                    }
                }
                return clicked;
            }

            function discoverLinks() {
                const out = new Set();

                const push = value => {
                    const u = normalizeUrl(value);
                    if (u && isNavigationUrl(u) && u !== normalizeUrl(location.href)) out.add(u);
                };

                for (const a of Array.from(document.querySelectorAll('a[href]'))) push(a.href);

                const attrNames = ['data-href','data-url','data-link','routerlink','routerLink','ng-reflect-router-link','to'];
                for (const el of Array.from(document.querySelectorAll('a[href],[data-href],[data-url],[data-link],[routerlink],[routerLink],[ng-reflect-router-link],[to],[onclick]'))) {
                    for (const attr of attrNames) {
                        const v = el.getAttribute && el.getAttribute(attr);
                        if (v) push(v);
                    }
                    const onclick = el.getAttribute && el.getAttribute('onclick');
                    if (onclick) {
                        const m = onclick.match(/(?:location(?:\.href)?|window\.open|navigate)\s*\(\s*['"]([^'"]+)['"]/i);
                        if (m) push(m[1]);
                    }
                }

                return Array.from(out);
            }

            function status(text) {
                const el = document.getElementById(STATUS_ID);
                if (el) el.textContent = text;
            }

            function refreshStatus() {
                const b = bridge();
                if (!b || !b.getMirrorProgress) return;
                try {
                    const p = JSON.parse(b.getMirrorProgress());
                    status('AUTO SAVING COMPANY • Saved: ' + p.saved + ' • Queue: ' + p.queued);
                } catch (_) {}
            }

            function sendHtml(ctx) {
                const b = bridge();
                if (!b) return;
                const snapshotId = 'page-' + Date.now() + '-' + Math.random().toString(16).slice(2);
                const url = normalizeUrl(location.href);
                if (!url || !ctx.companyName || isLoginPage()) return;
                const overlay = document.getElementById(STATUS_ID);
                const stopButton = document.getElementById(STOP_ID);
                if (overlay) overlay.remove();
                if (stopButton) stopButton.remove();
                const html = '<!doctype html>\n' + (document.documentElement ? document.documentElement.outerHTML : document.body.outerHTML);
                if (overlay) document.body.appendChild(overlay);
                if (stopButton) document.body.appendChild(stopButton);

                b.beginPageSnapshot(snapshotId, ctx.companyName, ctx.serialNumber || '', url, document.title || url);
                for (let i = 0; i < html.length; i += CHUNK_SIZE) {
                    b.pushPageSnapshotChunk(snapshotId, html.substring(i, Math.min(i + CHUNK_SIZE, html.length)));
                }
                b.commitPageSnapshot(snapshotId, ctx.companyName, ctx.serialNumber || '', url, document.title || url);
            }

            function captureCurrentPage() {
                if (!document.body || isLoginPage()) return;
                const ctx = getCompanyFromPage();
                const b = bridge();
                if (!b) return;

                if (ctx.companyName && b.setActiveCompany) {
                    b.setActiveCompany(ctx.companyName, ctx.serialNumber || '');
                }

                const key = normalizeUrl(location.href) + '|' + clean(document.title);
                if (key === lastCapturedKey && Date.now() - (window.__lastTallyMirrorCaptureAt || 0) < 3000) return;
                lastCapturedKey = key;
                window.__lastTallyMirrorCaptureAt = Date.now();

                const urls = discoverLinks();
                if (b.enqueueDiscoveredUrls && ctx.companyName) {
                    b.enqueueDiscoveredUrls(ctx.companyName, ctx.serialNumber || '', JSON.stringify(urls));
                }
                status('Saving page + discovering ' + urls.length + ' links…');
                sendHtml(ctx);
                refreshStatus();
                if (b.requestNextMirrorPage) b.requestNextMirrorPage();
            }

            function scheduleCapture(delay) {
                clearTimeout(captureTimer);
                captureTimer = setTimeout(captureCurrentPage, delay || CAPTURE_DELAY);
            }

            function installOverlay() {
                if (!document.body) return;
                if (!document.getElementById(STATUS_ID)) {
                    const st = document.createElement('div');
                    st.id = STATUS_ID;
                    st.textContent = 'Starting whole-company offline save…';
                    st.style.cssText = 'position:fixed;right:8px;top:76px;z-index:2147483647;background:rgba(0,0,0,.80);color:#fff;padding:7px 11px;border-radius:8px;font:700 12px sans-serif;max-width:310px;box-shadow:0 2px 8px rgba(0,0,0,.30);';
                    document.body.appendChild(st);
                }
                if (!document.getElementById(STOP_ID)) {
                    const btn = document.createElement('button');
                    btn.id = STOP_ID;
                    btn.textContent = 'STOP AUTO SAVE';
                    btn.style.cssText = 'position:fixed;right:8px;top:112px;z-index:2147483647;background:#b71c1c;color:#fff;border:0;padding:6px 10px;border-radius:7px;font:700 11px sans-serif;';
                    btn.onclick = function(e) {
                        e.preventDefault(); e.stopPropagation();
                        const b = bridge();
                        if (b && b.stopWholeCompanyMirror) b.stopWholeCompanyMirror();
                        status('AUTO SAVE STOPPED');
                    };
                    document.body.appendChild(btn);
                }
            }

            window.TallyOfflineMirror = { captureCurrentPage, discoverLinks };

            function detectCompanyFromClickedElement(target) {
                const row = target && target.closest ? target.closest('tr,[role="row"],li,[class*="company"],[data-company-name]') : null;
                if (!row) return;
                const text = clean(row.innerText || '');
                if (!text || !/(company|connected|open|serial)/i.test(text)) return;
                let name = clean(row.getAttribute('data-company-name') || '');
                const cells = Array.from(row.querySelectorAll('td,th')).map(x => clean(x.innerText)).filter(Boolean);
                if (!name && cells.length) {
                    name = cells.find(x => !/connected|offline|status|serial|open|select/i.test(x) && x.length > 1) || '';
                }
                if (!name) {
                    const m = text.match(/(?:company(?:\s*name)?|name)\s*[:\-]\s*([^\n|]{2,120})/i);
                    if (m) name = clean(m[1]);
                }
                if (!name || /login|sign in|customer portal/i.test(name)) return;
                const b = bridge();
                if (b && b.setActiveCompany) b.setActiveCompany(name, '');
            }

            document.addEventListener('click', function(e) {
                const target = e.target && e.target.closest ? e.target.closest('a,button,[role="button"],li,tr') : null;
                if (!target) return;
                detectCompanyFromClickedElement(target);
                installOverlay();
                scheduleCapture(1800);
            }, true);
            window.addEventListener('popstate', () => scheduleCapture(1000));
            window.addEventListener('hashchange', () => scheduleCapture(1000));

            const observer = new MutationObserver(() => {
                installOverlay();
                scheduleCapture(2500);
            });
            if (document.body) observer.observe(document.body, {childList:true, subtree:true});

            installOverlay();
            maybeOpenMenu();
            setTimeout(maybeOpenMenu, 500);
            setTimeout(() => { installOverlay(); captureCurrentPage(); }, 1000);
            setTimeout(captureCurrentPage, 2600);
        })();
    """.trimIndent()
}
