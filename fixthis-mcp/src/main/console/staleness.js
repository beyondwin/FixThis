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
                const drift = Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs);
                if (drift > StaleThresholdMs) {
                  const hash = `${server.serverGitSha}-${ConsoleBuildGitSha}`;
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server build is older than this console',
                    detail: `Client ${ConsoleBuildGitSha} → Server ${server.serverGitSha}. Restart fixthis-mcp.`,
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

            const MinimumSupportedProtocolVersion = '1.1';

            function checkProtocolCompat(status) {
              const v = status?.bridgeProtocolVersion;
              if (typeof v !== 'string') return;
              if (v !== MinimumSupportedProtocolVersion) {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `Bridge protocol v${v} is too old`,
                  detail: 'Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.',
                  hash: `protocol-${v}`,
                });
              }
            }

            function checkSidekickBuildEpoch(status) {
              const sidekickEpoch = status?.sidekickBuildEpochMs;
              if (typeof sidekickEpoch !== 'number') return;
              const drift = Math.abs(sidekickEpoch - ConsoleBuildEpochMs);
              if (drift > StaleThresholdMs) {
                renderStalenessBanner({
                  severity: 'warning',
                  headline: 'Sample app sidekick is older than this console',
                  detail: 'Reinstall the sample APK to refresh.',
                  hash: `sidekick-${sidekickEpoch}`,
                });
              }
            }
