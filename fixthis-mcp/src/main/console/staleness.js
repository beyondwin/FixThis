// @requires state.js
            // staleness.js — detects stale fixthis-mcp / sidekick by comparing build epochs.
            const StaleThresholdMs = 5 * 60 * 1000;
            const StalenessDismissKey = 'fixthis.console.stalenessDismissedHash';

            async function checkServerStaleness() {
              try {
                const resp = await fetch('/api/server-version');
                if (resp.status === 404) {
                  // /api/server-version not present = pre-Task-5 fixthis-mcp = stale
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server is older than this console',
                    detail: 'Restart fixthis-mcp to apply the latest server code.',
                    hash: 'pre-version-endpoint',
                  });
                  return;
                }
                if (!resp.ok) return; // 5xx etc. ambiguous — silent
                const server = await resp.json();
                const drift = Math.abs(server.serverBuildEpochMs - (window.FixThisConsoleConfig?.buildMeta?.buildEpochMs ?? 0));
                if (drift > StaleThresholdMs) {
                  const consoleSha = window.FixThisConsoleConfig?.buildMeta?.gitSha ?? 'unknown';
                  const hash = `${server.serverGitSha}-${consoleSha}`;
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server build is older than this console',
                    detail: `Client ${consoleSha} → Server ${server.serverGitSha}. Restart fixthis-mcp.`,
                    hash,
                  });
                }
              } catch (_err) {
                // network or JSON error — silent
              }
            }

            function renderStalenessBanner(info) {
              const banner = document.getElementById('stalenessBanner');
              if (!banner) return;
              const dismissed = sessionStorage.getItem(StalenessDismissKey);
              if (dismissed === info.hash) return;
              banner.dataset.severity = info.severity || 'warning';
              const headlineSlot = banner.querySelector('[data-headline]');
              if (headlineSlot) headlineSlot.textContent = info.headline;
              const detailSlot = banner.querySelector('[data-detail]');
              if (detailSlot) detailSlot.textContent = info.detail;
              banner.dataset.hash = info.hash;
              banner.hidden = false;
            }

            document.getElementById('stalenessBanner')?.querySelector('[data-dismiss]')
              ?.addEventListener('click', (event) => {
                const banner = event.currentTarget.closest('#stalenessBanner');
                if (!banner) return;
                sessionStorage.setItem(StalenessDismissKey, banner.dataset.hash || '');
                banner.hidden = true;
              });

            const MinimumSupportedProtocolVersion = '1.3';

            // Parse "1.1" -> [1, 1]; "1" -> [1]; non-numeric / null / undefined -> null.
            function parseProtocolVersion(s) {
              if (typeof s !== 'string') return null;
              const parts = s.split('.').map((token) => Number(token));
              if (parts.length === 0) return null;
              if (parts.some((n) => !Number.isFinite(n))) return null;
              return parts;
            }

            // Returns negative / 0 / positive. Shorter array right-padded with 0.
            function compareProtocolVersion(a, b) {
              const len = Math.max(a.length, b.length);
              for (let i = 0; i < len; i++) {
                const ai = a[i] ?? 0;
                const bi = b[i] ?? 0;
                if (ai !== bi) return ai - bi;
              }
              return 0;
            }

            function checkProtocolCompat(status) {
              const reported = parseProtocolVersion(status?.bridgeProtocolVersion);
              const expected = parseProtocolVersion(MinimumSupportedProtocolVersion);
              if (!reported || !expected) return;
              const cmp = compareProtocolVersion(reported, expected);
              if (cmp === 0) return;
              if (cmp < 0) {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `Sample app bridge protocol v${status.bridgeProtocolVersion} is older than this console (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.',
                  hash: `protocol-sidekick-old-${status.bridgeProtocolVersion}`,
                });
              } else {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `This console is older than sample app bridge protocol v${status.bridgeProtocolVersion} (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Restart fixthis-mcp + hard reload the browser tab to update console.',
                  hash: `protocol-console-old-${status.bridgeProtocolVersion}`,
                });
              }
            }

            function checkSidekickBuildEpoch(status) {
              const sidekickEpoch = status?.sidekickBuildEpochMs;
              if (typeof sidekickEpoch !== 'number') return;
              const drift = Math.abs(sidekickEpoch - (window.FixThisConsoleConfig?.buildMeta?.buildEpochMs ?? 0));
              if (drift > StaleThresholdMs) {
                renderStalenessBanner({
                  severity: 'warning',
                  headline: 'Sample app sidekick is older than this console',
                  detail: 'Reinstall the sample APK to refresh.',
                  hash: `sidekick-${sidekickEpoch}`,
                });
              }
            }
